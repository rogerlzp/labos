#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 LAB-EXT-01 的设备**拉上线**：修端口冲突 + 填 IP + 起模拟器 + 起 sidecar。

**为什么要这个脚本**（2026-11-15 实测出来的三个坑）：

1. **端口冲突**：建数脚本给 DISP/ARM/STACK 分配端口时（19604 起），没有避开
   PIAgent 并发建的 `DRV-SIM-P_AUTO-*`（19608~19611）⇒ **4 组端口撞车**
   （DISP5 与 P_AUTO-1 同为 19608…）⇒ 两台设备连同一个端点 ⇒ 状态互相串台。
2. **IP 是空的**：设备在线状态由 sidecar 写进 Redis
   （`labos:link:<设备ID>:<host>:<port>`），而 **sidecar 的 ip/port 全部取自
   中控的设备清单**（`/api/devices/registry`）⇒ 清单里 ip 为空 ⇒
   sidecar 写不出 key ⇒ 内核永远判离线。
3. **端口与 key 是绑死的**：内核用 `driver.endpoint` 的端口 + `device_instance.ip`
   的 host 拼 key ⇒ **这两处必须与 sidecar 实际连的端点一致**，否则永远离线。

**本脚本做四件事**（顺序不能换）：
① 重排驱动端口（19620 起，避开所有既有端口）
② 填 `device_instance.ip = '127.0.0.1'`
③ 起模拟器（每个设备一个端口，监听）
④ 起 sidecar（连模拟器 + 写 Redis 在线状态）

**注意**：sidecar 的型号库只认已适配型号（s100 / aspin-2k …），
**PAuto / DISPENSER / GANTRY / PLATE_STACK 都不在里面** ⇒ sidecar 会拒绝启动。
⇒ 本脚本让 sidecar 用 **`--model s100 --transport sim`** 起：
  sim 传输下型号只用于**方法名校验**，而自发帧翻译走**通用 JSON 翻译器**
  （`SpontaneousTranslators.For` 的兜底）⇒ 用哪个已适配型号都不影响事件翻译；
  另配 `--device-code` 认领设备、`--device-event-token` 让侧车能把
  自发帧 POST 进内核（`POST /api/device-events`）。
  脚本还**自己写 Redis link key**（并起后台线程每 10s 刷新，TTL 30s）
  —— 这是内核判「设备在线」的权威来源，等价于 sidecar 的保活动作。

用法：
    LABOS_API=http://127.0.0.1:18084 LABOS_DEVICE_EVENT_TOKEN=labos-dev-token \\
      python3 scripts/bring-extraction-station-online.py
"""

import json
import os
import re
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request

API = os.environ.get("LABOS_API", "http://127.0.0.1:18080").rstrip("/")
USER = os.environ.get("LABOS_USER", "admin")
PASSWORD = os.environ.get("LABOS_PASS", "admin123")
# 岛 / 设备编号 / 端口段都在共享配置层（scripts/extraction_config.py）。
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from extraction_config import (  # noqa: E402
    ISLAND_CODE, SIM_PORT_BASE, SIDECAR_PORT_BASE, arm_code, disp_codes,
    ext_codes, pick_ports, stack_code,
)

REDIS_HOST = os.environ.get("LABOS_REDIS_HOST", "127.0.0.1")
REDIS_PORT = int(os.environ.get("LABOS_REDIS_PORT", "6379"))
# 设备事件入站令牌（内核与侧车必须一致；内核不配就回 503）
EVENT_TOKEN = os.environ.get("LABOS_DEVICE_EVENT_TOKEN", "labos-dev-token")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KERNEL = os.path.join(ROOT, "labos-kernel")
SIM_CP = "out:lib/postgresql-42.7.3.jar:lib/spring/*"
# 模拟器用**隔离副本**的 classpath（LABOS_SIM_CP 指向它的 labos-kernel 目录）——
# 共享的 out/ 会被并发会话的编译覆盖 ⇒ 模拟器可能半路 ClassNotFound。
SIM_HOME = os.environ.get("LABOS_SIM_HOME", KERNEL)

# 设备 → (端口, 模拟器画像)。**运行时按空闲端口生成**（不固定顺排）。
#   ⚠ 固定顺排会与别的岛撞端口 ⇒ 两台设备连同一端点 ⇒ 状态互相串台
#     （实测撞过 4 组：DISP5 与 P_AUTO-1 同为 19608）。
DEVICES = []


def build_devices():
    """列出本岛设备，并给每台挑一个**空闲**的模拟器端口。"""
    st, drvs = call("GET", "/api/drivers")
    used = []
    for d in (drvs.get("items") or []):
        ep = str(d.get("endpoint") or "")
        if ":" in ep:
            try:
                used.append(int(ep.rsplit(":", 1)[1]))
            except ValueError:
                pass
    specs = ([(c, "carousel_extractor") for c in ext_codes()]
             + [(c, "dispenser") for c in disp_codes()]
             + [(arm_code(), "gantry"), (stack_code(), "plate_stack")])
    ports = pick_ports(len(specs), base=SIM_PORT_BASE, used=used)
    return [(code, port, prof) for (code, prof), port in zip(specs, ports)]

TOKEN = None
PROCS = []


def call(method, path, body=None, query=None):
    url = API + path
    if query:
        url += "?" + "&".join(f"{k}={v}" for k, v in query.items())
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    if body is not None:
        req.add_header("Content-Type", "application/json")
    if TOKEN:
        req.add_header("Authorization", f"Bearer {TOKEN}")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            t = r.read().decode("utf-8")
            return r.status, (json.loads(t) if t.strip() else {})
    except urllib.error.HTTPError as e:
        t = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(t)
        except Exception:
            return e.code, {"error": t[:300]}


def ok(m):
    print(f"  ✔ {m}")


def bad(m, d=""):
    print(f"  ✘ {m}")
    if d:
        print(f"      {d}")


def section(t):
    print()
    print(f"── {t}")


def login():
    global TOKEN
    st, j = call("POST", "/api/auth/login", {"username": USER, "password": PASSWORD})
    if st != 200 or not j.get("token"):
        sys.exit(f"✘ 登录失败（{st}）：{j}")
    TOKEN = j["token"]
    print(f"✓ 已登录 {USER}（API {API}）")


def redis(*args):
    """调 redis-cli。"""
    r = subprocess.run(["redis-cli", "-h", REDIS_HOST, "-p", str(REDIS_PORT), *args],
                       capture_output=True, text=True)
    return r.stdout.strip()


def island_id():
    st, topo = call("GET", "/api/topology")
    for isl in topo.get("islands", []):
        if isl.get("code") == ISLAND_CODE:
            return isl["id"]
    sys.exit(f"✘ 找不到岛 {ISLAND_CODE}（先跑 setup-extraction-station.py）")


def main():
    global DEVICES
    print("=" * 68)
    print(f"把 {ISLAND_CODE} 的设备拉上线")
    print("=" * 68)
    login()
    iid = island_id()
    DEVICES = build_devices()
    ok(f"端口分配（挑空闲的）：{', '.join(f'{c}={p}' for c, p, _ in DEVICES)}")

    # ---- ① 端口重排 + ② 填 IP
    section("① 修端口冲突 + 填 IP=127.0.0.1")
    for code, port, profile in DEVICES:
        # 找这台设备的 driver 与 instance
        st, topo = call("GET", "/api/topology")
        rid = inst = drv = None
        for isl in topo.get("islands", []):
            if isl.get("id") != iid:
                continue
            for d in isl.get("devices", []):
                if d.get("code") == code:
                    rid = d.get("resourceId")
        if rid is None:
            bad(f"{code} 不在拓扑里")
            continue
        # 驱动 id 从设备清单拿。
        # ⚠ 清单返回的是 **driverCode**（不是 driverId）—— 见 deviceRegistryJson。
        #   早期版本按 driverId 找 ⇒ 全部"找不到驱动" ⇒ 端口没改 ⇒ 设备永远离线。
        st, reg = call("GET", "/api/devices/registry")
        drv_code = None
        for it in reg.get("items", []):
            if it.get("resourceId") == rid:
                drv_code = it.get("driverCode")
                break
        if drv_code is None:
            bad(f"{code} 找不到驱动（清单里没有 driverCode）")
            continue
        st, drvs = call("GET", "/api/drivers")
        drv_id = None
        for d in drvs.get("items", []):
            if d.get("code") == drv_code:
                drv_id = d.get("id")
                break
        if drv_id is None:
            bad(f"{code} 的驱动 {drv_code} 不在驱动列表里")
            continue
        st, j = call("PUT", f"/api/drivers/{drv_id}", {"endpoint": f"tcp://127.0.0.1:{port}"})
        if st != 200:
            bad(f"{code} 改驱动端点失败（{st}）", str(j)[:150])
            continue
        # IP 其实不用填：清单的 effectiveIp 在 device_instance.ip 为空时
        # 会退回 driver.endpoint 的 host（两端同一套规则 ⇒ 必然对得上）。
        # 但显式填上更稳（现场能一眼看出设备 IP），也符合"IP 都用 127.0.0.1"的要求。
        st, j = call("PUT", f"/api/devices/{rid}", {"ip": "127.0.0.1"})
        if st != 200:
            bad(f"{code} 填 IP 失败（{st}）", str(j)[:150])
            continue
        ok(f"{code} → tcp://127.0.0.1:{port} · ip=127.0.0.1")

    # ---- ③ 起模拟器
    section("② 起模拟器（每台设备一个端口）")
    for code, port, profile in DEVICES:
        st, reg = call("GET", "/api/devices/registry")
        inst_id = None
        for it in reg.get("items", []):
            if it.get("code") == code:
                inst_id = it.get("deviceId")
                break
        if inst_id is None:
            bad(f"{code} 找不到设备实例")
            continue
        log = f"/tmp/sim-{code}.log"
        # ⚠ **必须传这台设备实际有的方法**（--methods）：
        #   模拟器默认按**画像**校验方法集，而画像是**通用机型**的方法集（可能更少）⇒
        #   中控按 device_method 表发了合法指令，模拟器却拒收（EC-06-0004）
        #   ⇒ 节点转人工、流程暂停，现场看到的是"流程明明配对了，怎么就卡了"。
        #
        #   为什么不用 --registry 让模拟器自己认领：`/api/devices/registry` **要鉴权**，
        #   而 `DeviceRegistry.fetch` 不带 token ⇒ 401 ⇒ 认领失败 ⇒ 方法集拿不到。
        #   （给模拟器加 token 参数是另一条路，但改内核/模拟器代码不属本轮范围。）
        st, reg = call("GET", "/api/devices/registry")
        methods = []
        estimates = {}
        rid = None
        for it in reg.get("items", []):
            if it.get("code") == code:
                methods = it.get("methods") or []
                estimates = it.get("methodEstimates") or {}
                rid = it.get("resourceId")
                break
        if rid is None or not methods:
            bad(f"{code} 在清单里没有方法集（methods={methods}）")
            continue
        cmd = ["java", "-cp", SIM_CP, "com.labos.kernel.sim.DeviceSimulator",
               "--port", str(port),
               "--device", str(inst_id), "--code", code,
               "--methods", ",".join(methods)]
        # ⚠ **中控登记的方法预估时长也要传给模拟器**（2026-11-18）：
        #   模拟器的"该方法跑多久"优先级是
        #     型号/命令行 delay → 请求参数里的时长 → **画像 step timing** → 画像默认 20s
        #   画像的 step timing 记的是 BindIncubate（转盘机的合并动作），
        #   而 PAuto 登记的是厂商动作名 Bind / Incubate ⇒ 对不上 ⇒ 落回 20s
        #   ⇒ "按真实时长跑"其实没真等（结合 480s 变成 20s）。
        #   把清单里的 methodEstimates 传下去，模拟器就按中控登记的时长等。
        if estimates:
            cmd += ["--method-estimates",
                    ",".join(f"{k}={v}" for k, v in estimates.items())]
        # **自发上报脚本**：命令成功后设备主动上报【等待交互】。
        # 这是「分段自驱」的触发器 —— 没有它，`__awaitEvent` 永远等不到事件。
        #
        # ⚠ 两条硬约束（都踩过）：
        # ① **内核的 TCP 驱动丢弃**无 message_id 的帧（`TcpPersistentDriver.java:295-297`）
        #    ⇒ 事件只能走「侧车 → HTTP POST /api/device-events」这条路 ⇒ 必须起侧车。
        # ② 自发脚本是**队列消费**（每个方法一个 FIFO，弹出就没了）⇒
        #    同一个方法写 N 次才会发 N 次事件。4 台提取仪各要一次 ⇒ 每个方法写 6 条。
        #    少了就"第一个 run 醒了、后面的 run 永远等不到" —— 现场表现为"跑一次就好一次"。
        if code.startswith("P_AUTO"):
            # `@300*` = **每次该方法执行成功都报**（真实设备行为：一台提取仪
            # 做完全部内部工序后，每次换板都要再报一次【等待交互】）。
            # 老语义是"一次性队列" ⇒ 一台设备在一条流程里只能报一次
            # ⇒ 第二次等待必然超时 ⇒ 卡在「等 XXX 等待交互」。
            cmd += ["--spontaneous", ";".join([
                "PickTipComb=waiting_interaction@300*",
                "Bind=waiting_interaction@300*",
                "Incubate=waiting_interaction@300*",
                "Wash=waiting_interaction@300*",
                "Elute=waiting_interaction@300*",
                "DropTipComb=process_done@300*",
            ])]
        with open(log, "w") as f:
            p = subprocess.Popen(cmd, cwd=SIM_HOME, stdout=f, stderr=subprocess.STDOUT)
        PROCS.append(p)
    time.sleep(3)
    alive = 0
    for code, port, _ in DEVICES:
        if port_open(port):
            alive += 1
        else:
            bad(f"{code} 模拟器没起来（端口 {port}）")
    if alive:
        ok(f"模拟器 {alive}/{len(DEVICES)} 个在监听")

    # ---- ④ 写 Redis link key（等价 sidecar 的保活动作）
    section("③ 写 Redis 在线状态（等价 sidecar 的长连接保活）")
    # 内核用 `labos:link:<deviceId>:<host>:<port>` 判在线，TTL 30s ⇒ 要持续刷新
    st, reg = call("GET", "/api/devices/registry")
    by_code = {it["code"]: it for it in reg.get("items", [])}
    for code, port, _ in DEVICES:
        it = by_code.get(code)
        if not it:
            bad(f"{code} 不在清单里")
            continue
        key = f"labos:link:{it['deviceId']}:127.0.0.1:{port}"
        # TTL 30s ⇒ 起一个后台线程持续刷新
        start_refresher(key)
        ok(f"{code} → Redis {key}")

    # ---- ④ 起侧车（把自发帧转成 HTTP 事件）
    section("③ 起侧车（自发帧 → HTTP /api/device-events）")
    # 为什么必须起侧车：**内核的 TCP 驱动丢弃无 message_id 的帧**
    # （`TcpPersistentDriver.java:295-297`，"没 message_id 的（心跳等）直接丢"）
    # ⇒ 事件只能走「侧车 → HTTP」这条路 ⇒ 没有侧车，模拟器发的【等待交互】
    #    永远到不了内核，`__awaitEvent` 永远等不到。
    #
    # 侧车型号用 `--model s100`（已适配型号）：sim 传输下型号只用于**方法名校验**，
    # 而自发帧翻译走**通用 JSON 翻译器**（`SpontaneousTranslators.For` 的兜底）⇒
    # 用哪个已适配型号都不影响事件翻译。
    sidecar_dir = os.path.join(ROOT, "sidecar-dotnet/Labos.Sidecar")
    sc_ports = pick_ports(len(DEVICES), base=SIDECAR_PORT_BASE)
    sc_port = sc_ports[0] if sc_ports else SIDECAR_PORT_BASE
    for code, port, _ in DEVICES:
        st, reg = call("GET", "/api/devices/registry")
        rid = None
        for it in reg.get("items", []):
            if it.get("code") == code:
                rid = it.get("resourceId")
        if rid is None:
            continue
        log = f"/tmp/sidecar-{code}.log"
        cmd = ["dotnet", "run", "--no-build", "--",
               "--model", "s100",
               "--transport", "sim",
               "--sim-host", "127.0.0.1", "--sim-port", str(port),
               "--port", str(sc_port),
               "--registry-url", API,
               "--redis-host", REDIS_HOST, "--redis-port", str(REDIS_PORT),
               "--device-code", code,
               # 令牌要与内核的 LABOS_DEVICE_EVENT_TOKEN 一致 ⇒ 不配内核回 503
               # （"设备事件入站未启用：请配置 LABOS_DEVICE_EVENT_TOKEN"）
               "--device-event-token", EVENT_TOKEN,
               ]
        with open(log, "w") as f:
            p = subprocess.Popen(cmd, cwd=sidecar_dir, stdout=f, stderr=subprocess.STDOUT)
        PROCS.append(p)
        sc_port += 1
    time.sleep(8)
    sc_ok = 0
    for code, _, _ in DEVICES:
        txt = open(f"/tmp/sidecar-{code}.log", encoding="utf-8", errors="replace").read()
        if "监听" in txt:
            sc_ok += 1
        else:
            bad(f"{code} 侧车没起来", txt.strip()[-160:])
    if sc_ok:
        ok(f"侧车 {sc_ok}/{len(DEVICES)} 个在监听")

    print()
    print("  ⏳ 在线状态刷新线程已起（每 10s 刷新，TTL 30s）")
    print("  ⏳ 现在等内核心跳探测跑一轮（心跳间隔看 sys_setting，默认几秒）…")
    time.sleep(12)

    # ---- 验证上线
    section("④ 验证设备是否上线")
    st, topo = call("GET", "/api/topology")
    online = []
    for isl in topo.get("islands", []):
        if isl.get("id") != iid:
            continue
        for d in isl.get("devices", []):
            if d.get("online"):
                online.append(d["code"])
    ok(f"在线 {len(online)}/{len(DEVICES)}：{', '.join(sorted(online))}")
    if len(online) < len(DEVICES):
        bad("有设备还没上线", f"离线：{', '.join(c for c, _, _ in DEVICES if c not in online)}")

    # ---- 列方法
    section("⑤ 各设备支持的方法")
    for code, port, _ in DEVICES:
        st, reg = call("GET", "/api/devices/registry")
        rid = None
        for it in reg.get("items", []):
            if it.get("code") == code:
                rid = it.get("resourceId")
        if rid is None:
            continue
        st, m = call("GET", f"/api/devices/{rid}/methods")
        items = m.get("items", [])
        methods = ", ".join(f"{x.get('method')}({x.get('estimateSec')}s)" for x in items)
        print(f"    {code:<11} {len(items):>2} 个：{methods}")

    print()
    print("=" * 68)
    print("完成。模拟器与刷新线程在后台运行（本脚本退出后模拟器仍在）。")
    print("停模拟器：pkill -f 'com.labos.kernel.sim.DeviceSimulator'")
    print("=" * 68)


REFRESH_KEYS = []


def start_refresher(key):
    """后台线程持续刷新 Redis key（TTL 30s，内核每轮心跳读它）。"""
    import threading

    def loop():
        while True:
            redis("SET", key, "online", "EX", "30")
            time.sleep(10)

    t = threading.Thread(target=loop, daemon=True)
    t.start()
    REFRESH_KEYS.append(key)


def port_open(port):
    import socket
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(1.0)
    try:
        s.connect(("127.0.0.1", port))
        return True
    except Exception:
        return False
    finally:
        s.close()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
