#!/usr/bin/env python3
"""整线模拟器统一入口：按**中控设备清单**把该起的模拟器全部拉起来。

为什么要有这个脚本
------------------
之前每条 `DeviceSimulator` 都是手工 nohup 起的（12 条长命令，端口/设备ID/方法集
各写一遍），加一台设备就得再抄一条，抄错端口就表现为"中控说离线、模拟器说我在跑"。
而**这些值中控全都有**（`GET /api/devices/registry`：deviceId / code / endpoint /
simProfile / modelId / methods）—— 所以脚本只做三件事：

  1. 拉清单 → 每台设备一条模拟器进程（端口从 `endpoint` 解析）；
  2. 方法集优先用清单里的 `methods`（= 库里 `device_method` 的登记），
     个别设备用下面的 `METHOD_OVERRIDES` 压住（保持现状，避免重启后行为漂移）；
  3. 主动上报事件按**设备类型**给（见 `SPONTANEOUS`），复刻现有配置。

用法
----
    python3 scripts/sim-all.py --status      # 只看：清单 / 端口 / 是否在监听
    python3 scripts/sim-all.py               # 只补没起来的（幂等，不动已在跑的）
    python3 scripts/sim-all.py --restart     # 全停再全起（会短暂中断）
    python3 scripts/sim-all.py --stop        # 全停
    python3 scripts/sim-all.py --only D9608-1
    python3 scripts/sim-all.py --registry http://127.0.0.1:18080   # 默认同此

注意
----
- 只起 **CLI 模拟器**（`com.labos.kernel.sim.DeviceSimulator`），不动 GUI 面板
  （`SimulatorApp`）与 sidecar（那两套各有入口）。
- 设备在库里必须有 `driver.status=1` 且 `endpoint` 非空，否则清单里根本不会出现
  —— 那是"没接驱动"，不是"模拟器没起"。
"""

import argparse
import json
import os
import signal
import socket
import subprocess
import sys
import time
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KERNEL = os.path.join(ROOT, "labos-kernel")
CP = "out:lib/postgresql-42.7.3.jar:lib/spring/*"
MAIN = "com.labos.kernel.sim.DeviceSimulator"
LOG_DIR = "/tmp/labos-sim"

DEFAULT_REGISTRY = "http://127.0.0.1:18080"

# 方法集压住（保持重启前后行为一致）。
# DISP1-6（EL406）现在跑的是这两个方法；不压就会回落到 washer 画像的 6 个方法，
# 原来该被拒的调用会悄悄通过 —— 这种"重启变宽松"最难查。
METHOD_OVERRIDES = {f"DISP{i}": "Run,RunProtocol" for i in range(1, 7)}

# 主动上报事件（`method=eventType@delayMs*`，`*` = 每次成功都报，无限次）。
#   extractor：做完内部工序报【等待交互】，卸磁套报【流程完成】——
#              现场就是"跑到交换位停下等机械臂"，不报这个中控永远等下去。
#   dispenser：分液完成报【流程完成】。
SPONTANEOUS = {
    "Extractors": ("PickTipComb=waiting_interaction@300*;"
                   "Bind=waiting_interaction@300*;"
                   "Incubate=waiting_interaction@300*;"
                   "Wash=waiting_interaction@300*;"
                   "Elute=waiting_interaction@300*;"
                   "DropTipComb=process_done@300*"),
    "Dispensers & Washers": ("Dispense=process_done@300*;"
                             "Dispense96=process_done@300*;"
                             "Dispense384=process_done@300*;"
                             "CleanPipe=process_done@300*"),
}


def fetch(registry_url):
    url = registry_url.rstrip("/") + "/api/devices/registry"
    with urllib.request.urlopen(url, timeout=5) as r:
        return json.loads(r.read().decode("utf-8")).get("items", [])


def fetch_online(items):
    """code → 内核心跳判定的 online。

    两条来源，按可靠性排序：
      ① registry 里带 `online` 字段就用它（后端加字段后走这条，最省事）；
      ② 否则直接读库的 `device_state.online` —— `/api/topology` 要登录，
          `/api/devices/registry` 是免鉴权的，脚本拿不到会话，只能读库。
    读不到（没 psql / 没口令）就返回空 dict，表格里显示 "-"。
    """
    if items and all("online" in it for it in items):
        return {it.get("code"): bool(it.get("online")) for it in items}
    return _online_from_db()


def _online_from_db():
    env = dict(os.environ)
    if not env.get("LABOS_DB_PASS"):
        cfg = "/tmp/labos-env.txt"           # 项目约定的本机变量文件（不入库）
        if os.path.exists(cfg):
            for line in open(cfg, encoding="utf-8"):
                line = line.strip()
                if line.startswith("LABOS_DB_") and "=" in line:
                    k, v = line.split("=", 1)
                    env[k] = v
    host = env.get("LABOS_DB_HOST")
    if not host or not env.get("LABOS_DB_PASS"):
        return {}
    sql = ("SELECT rn.code, COALESCE(ds.online, false) FROM resource_node rn "
           "LEFT JOIN device_state ds ON ds.resource_id = rn.id AND ds.tenant_id = rn.tenant_id")
    try:
        r = subprocess.run(["psql", "-h", host,
                            "-p", env.get("LABOS_DB_PORT", "5432"),
                            "-U", env.get("LABOS_DB_USER", "labos"),
                            "-d", env.get("LABOS_DB_NAME", "labdb"),
                            "-q", "-tA", "-F", "|", "-c", sql],
                           capture_output=True, text=True, timeout=8,
                           env=dict(env, PGPASSWORD=env["LABOS_DB_PASS"]))
    except Exception:
        return {}
    if r.returncode != 0:
        return {}
    out = {}
    for line in r.stdout.splitlines():
        if "|" in line:
            code, val = line.split("|", 1)
            out[code] = val.strip() in ("t", "true", "1")
    return out


def port_of(endpoint):
    if not endpoint:
        return 0
    hp = endpoint
    if hp.startswith("tcp://"):
        hp = hp[6:]
    idx = hp.rfind(":")
    try:
        return int(hp[idx + 1:]) if idx > 0 else 0
    except ValueError:
        return 0


def listening(port, host="127.0.0.1"):
    if port <= 0:
        return False
    try:
        with socket.create_connection((host, port), timeout=0.6):
            return True
    except OSError:
        return False


def running_pids():
    """当前在跑的 CLI 模拟器 PID（不含 GUI）。"""
    out = subprocess.run(["ps", "-eo", "pid=,command="], capture_output=True, text=True).stdout
    pids = []
    for line in out.splitlines():
        if MAIN in line and "SimulatorApp" not in line:
            pids.append(int(line.split(None, 1)[0]))
    return pids


def stop_all(with_sidecar=False):
    pids = running_pids()
    for pid in pids:
        try:
            os.kill(pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
    for _ in range(20):
        if not running_pids():
            break
        time.sleep(0.3)
    print(f"已停止 {len(pids)} 个模拟器进程")
    if with_sidecar:
        out = subprocess.run(["ps", "-eo", "pid=,command="], capture_output=True, text=True).stdout
        n = 0
        for line in out.splitlines():
            if "Labos.Sidecar" in line and "--device-code" in line:
                try:
                    os.kill(int(line.split(None, 1)[0]), signal.SIGTERM)
                    n += 1
                except (ProcessLookupError, ValueError):
                    pass
        print(f"已停止 {n} 个 sidecar 进程（在线状态会在 30s TTL 后转离线）")
    return pids


SIDECAR_PROJ = os.path.join(ROOT, "sidecar-dotnet", "Labos.Sidecar")
SIDECAR_LINK_BASE = 19700          # sidecar 的链路端口（driver.endpoint 指向模拟器，这里只做人机通道/事件）


def sidecar_running(code):
    """该设备的 sidecar 是否已在跑（匹配 `--device-code <code>`）。

    <p>为什么按 device-code 而不是端口：一台设备会看到两个进程
    （`dotnet run` 父 + `Labos.Sidecar` 子），按端口判会重复起。
    """
    out = subprocess.run(["ps", "-eo", "command="], capture_output=True, text=True).stdout
    return any(f"--device-code {code}" in line for line in out.splitlines())


def start_sidecar(item, index, registry_url):
    """起一台 sidecar：它负责写 Redis `labos:link:<id>:<ip>:<port>`（内核心跳的唯一依据）。

    <p><b>不起 sidecar 的后果</b>：模拟器监听正常、中控也连得上，但 `device_state.online`
    一直是 false —— 因为在线状态不是 TCP 探测，而是 sidecar 每 10s 刷新的 Redis 键
    （TTL 30s）。流程预检会因此把整条流程判成"无可用设备"。
    """
    code = item.get("code") or "?"
    if sidecar_running(code):
        print(f"  = {code}: sidecar 已在跑")
        return False
    sim_port = port_of(item.get("endpoint"))
    link_port = SIDECAR_LINK_BASE + index
    for _ in range(30):
        if not listening(link_port):
            break
        link_port += 1
    args = ["dotnet", "run", "--no-build", "--",
            "--model", "s100", "--transport", "sim",
            "--sim-host", "127.0.0.1", "--sim-port", str(sim_port),
            "--port", str(link_port),
            "--registry-url", registry_url,
            "--redis-host", "127.0.0.1", "--redis-port", "6379",
            "--device-code", code, "--device-event-token", "labos-dev-token"]
    os.makedirs(LOG_DIR, exist_ok=True)
    log = open(os.path.join(LOG_DIR, f"sidecar-{code}.log"), "ab")
    subprocess.Popen(args, cwd=SIDECAR_PROJ, stdin=subprocess.DEVNULL,
                     stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    print(f"  + {code}: sidecar → link {link_port}（sim {sim_port}）")
    return True


def start_one(item, registry_url):
    code = item.get("code") or "?"
    port = port_of(item.get("endpoint"))
    if port <= 0:
        print(f"  ! {code}: endpoint 解析不出端口（{item.get('endpoint')}），跳过")
        return False
    if listening(port):
        print(f"  = {code}: 已在监听 {port}，跳过")
        return False

    methods = METHOD_OVERRIDES.get(code) or ",".join(item.get("methods") or [])
    args = ["java", "-cp", CP, MAIN,
            "--registry", registry_url,
            "--port", str(port)]
    if methods:
        args += ["--methods", methods]
    spont = SPONTANEOUS.get(item.get("deviceType") or "")
    if spont:
        args += ["--spontaneous", spont]

    os.makedirs(LOG_DIR, exist_ok=True)
    log = open(os.path.join(LOG_DIR, f"{code}.log"), "ab")
    subprocess.Popen(args, cwd=KERNEL, stdin=subprocess.DEVNULL,
                     stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    print(f"  + {code}: 启动 → {port}"
          f"（方法 {len(methods.split(',')) if methods else 0} 条"
          f"{'，含自发事件' if spont else ''}）")
    return True


def show(items, registry_url=DEFAULT_REGISTRY):
    pids = running_pids()
    online = fetch_online(items)
    print(f"{'设备':<18}{'端口':>7}  {'监听':<5}{'心跳在线':<8}{'型号':<12}{'方法':>5}")
    print("-" * 68)
    for it in items:
        port = port_of(it.get("endpoint"))
        code = it.get("code", "?")
        print(f"{code:<18}{port:>7}  "
              f"{'是' if listening(port) else '否':<5}"
              f"{'是' if online.get(code) else '否':<8}"
              f"{(it.get('modelId') or '-'):<12}{len(it.get('methods') or []):>5}")
    print("-" * 68)
    print(f"清单 {len(items)} 台；CLI 模拟器进程 {len(pids)} 个；"
          f"心跳在线 {sum(1 for i in items if online.get(i.get('code')))} 台")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--registry", default=DEFAULT_REGISTRY)
    ap.add_argument("--status", action="store_true", help="只打印现状")
    ap.add_argument("--restart", action="store_true", help="先全停再全起")
    ap.add_argument("--stop", action="store_true", help="全停")
    ap.add_argument("--only", help="只处理某台（设备编号）")
    ap.add_argument("--exclude", default="ID-",
                    help="按前缀排除（逗号分隔，默认 ID- = 旧演示岛设备，"
                         "它们从来不是 CLI 模拟器管的，起 generic 反而会干扰演示岛）")
    ap.add_argument("--with-sidecar", action="store_true",
                    help="同时确保每台设备的 sidecar 在跑（**在线状态靠它**："
                         "它每 10s 刷 Redis labos:link:*，TTL 30s；不起就永远 offline）")
    args = ap.parse_args()

    if args.stop:
        stop_all(args.with_sidecar)
        return 0

    try:
        items = fetch(args.registry)
    except Exception as e:
        print(f"拉不到设备清单（{args.registry}）：{e}", file=sys.stderr)
        print("内核没起？或 --registry 给错了。", file=sys.stderr)
        return 2

    prefixes = [p.strip() for p in (args.exclude or "").split(",") if p.strip()]
    if prefixes and not args.only:
        skipped = [x for x in items if any((x.get("code") or "").startswith(p) for p in prefixes)]
        items = [x for x in items if not any((x.get("code") or "").startswith(p) for p in prefixes)]
        if skipped and args.status:
            print(f"（按 --exclude 排除 {len(skipped)} 台：{prefixes}）")

    if args.only:
        items = [x for x in items if x.get("code") == args.only]
        if not items:
            print(f"清单里没有 {args.only}", file=sys.stderr)
            return 2

    if args.status:
        show(items, args.registry)
        return 0

    if args.restart:
        stop_all(args.with_sidecar)
        time.sleep(1)

    started = 0
    for it in items:
        if start_one(it, args.registry):
            started += 1
    if started:
        time.sleep(3)   # 等它们各自认领设备、绑端口

    if args.with_sidecar:
        for i, it in enumerate(items, start=1):
            start_sidecar(it, i, args.registry)
        time.sleep(3)

    show(items, args.registry)
    return 0


if __name__ == "__main__":
    sys.exit(main())
