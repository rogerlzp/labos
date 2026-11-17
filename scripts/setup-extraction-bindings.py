#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""配「高通量核酸提取工作站」的**点位×方法**与**设备池**。

**两条与流程图无关的绑定链**（后端全套都有，前端界面已补齐）：

| 链 | 表 | 配什么 | 本脚本怎么配 |
|---|---|---|---|
| 点位×方法 | `position_method` | 哪个点位允许跑哪些方法 | 提取仪两个工位各绑该型号的全部方法；分液器板位绑分液方法 |
| 设备池 | `device_pool` | 同型号多实例池 | 已在 setup-extraction-station.py 建好 |

⚠ **节点绑定（`node_binding`）不在本脚本里**（2026-11-15 移走）——
   节点绑定必须**与流程图同源生成**（`setup-extraction-flow.py`）：
   node_key 是按位置分配的，图一改旧绑定就会原地存活指向别的节点，
   运行期「绑定优先级最高」⇒ **静默用错设备**（实测踩到）。
   详见 setup-extraction-flow.py 里的说明。

用法：
    LABOS_API=http://127.0.0.1:18082 python3 scripts/setup-extraction-bindings.py

⚠ 幂等：点位×方法是「已存在就跳过」（后端唯一键）⇒ 重跑结果一样。
"""

import json
import os
import sys
import urllib.error
import urllib.request
import urllib.parse

API = os.environ.get("LABOS_API", "http://127.0.0.1:18080").rstrip("/")
USER = os.environ.get("LABOS_USER", "admin")
PASSWORD = os.environ.get("LABOS_PASS", "admin123")

# 岛 / 设备编号 / 流程编号都在共享配置层（scripts/extraction_config.py）。
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from extraction_config import (  # noqa: E402
    ISLAND_CODE, disp_codes, ext_codes, stack_code,
)

WF_CODE = os.environ.get("LABOS_WF_CODE", "EXT-WORKFLOW-01")
EXT_CODES = ext_codes()
DISP_CODES = disp_codes()
STACK_CODE = stack_code()

TOKEN = None


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
    print(f"✓ 已登录 {USER}")


def main():
    print("=" * 68)
    print("配「高通量核酸提取工作站」的绑定链")
    print("=" * 68)
    print(f"API: {API}")
    login()

    # ---- 岛与设备
    section("1. 取岛与设备")
    st, topo = call("GET", "/api/topology")
    island = next((i for i in topo.get("islands", []) if i.get("code") == ISLAND_CODE), None)
    if island is None:
        sys.exit(f"✘ 找不到岛 {ISLAND_CODE}")
    devs = {d["code"]: d for d in island.get("devices", [])}
    ok(f"岛 {ISLAND_CODE}：{len(devs)} 台设备")

    # ---- 点位
    section("2. 取点位（device_point.id —— 绑定用的是它，不是 resource_node.id）")
    st, pos = call("GET", "/api/positions", query={"islandId": island["id"]})
    points = pos.get("items", [])
    # /api/positions 返回 pointId（配置层主键）—— 绑定必须用它
    by_code = {p.get("code"): p for p in points}
    ok(f"点位 {len(points)} 个")

    # ---- 方法清单
    section("3. 取方法清单（methodId —— device_method.id）")
    methods_by_device = {}
    for code, d in devs.items():
        st, m = call("GET", f"/api/devices/{d['resourceId']}/methods")
        items = [x for x in m.get("items", []) if x.get("methodId")]
        methods_by_device[code] = items
    ok(f"方法清单取完（{sum(len(v) for v in methods_by_device.values())} 条）")

    # ---- 点位×方法
    section("4. 配点位×方法（position_method）")
    added = skipped = failed = 0

    def bind_position_method(point_id, method_id):
        nonlocal added, skipped, failed
        st, r = call("POST", f"/api/positions/{point_id}/methods", {
            "methodId": method_id, "scopeKind": "global",
            "purpose": "该工位允许执行", "allocation": "explicit", "priority": 100,
        })
        if st == 200:
            added += 1
        elif st == 409 or "已存在" in str(r):
            skipped += 1
        else:
            failed += 1
            if failed <= 3:
                bad(f"绑方法失败（{st}）", str(r)[:150])

    # 提取仪：两个工位各绑该型号全部方法
    #   操作位（INT，机械臂够不到）：内部工序方法
    #   交互位（EXT，机械臂可达）：换板相关方法
    for code in EXT_CODES:
        d = devs.get(code)
        if not d:
            continue
        ms = methods_by_device.get(code, [])
        for suffix in ("INT", "EXT"):
            pc = f"{code}-{suffix}"
            p = by_code.get(pc)
            if not p:
                bad(f"点位 {pc} 不存在")
                continue
            pid = p.get("pointId")
            if pid is None:
                bad(f"点位 {pc} 没有 pointId")
                continue
            for m in ms:
                bind_position_method(pid, m["methodId"])
    # 分液器：板位绑分液/搅拌方法；搅拌工位绑搅拌方法
    for code in DISP_CODES:
        d = devs.get(code)
        if not d:
            continue
        ms = methods_by_device.get(code, [])
        for suffix in ("PLATE", "STIR"):
            pc = f"{code}-{suffix}"
            p = by_code.get(pc)
            if not p or p.get("pointId") is None:
                continue
            pid = p["pointId"]
            for m in ms:
                bind_position_method(pid, m["methodId"])
    # 耗材栈：板位绑取放板方法
    st = None
    p = by_code.get(f"{STACK_CODE}-S01")
    if p and p.get("pointId"):
        ms = methods_by_device.get(STACK_CODE, [])
        for m in ms:
            bind_position_method(p["pointId"], m["methodId"])
    ok(f"点位×方法：新增 {added} · 已存在 {skipped} · 失败 {failed}")

    # ---- 节点绑定
    #
    # ⚠ **节点绑定不在这里配了**（2026-11-15 移走）：
    #   节点绑定挂在 `node_binding.node_id` 上，而 **node_key 是按位置分配的**
    #   （Activity_0004 = 第 4 个建出来的节点）。流程图一改，同一个 Activity_0004
    #   可能变成**另一台设备的节点**，而 node_id 还在 ⇒ **旧绑定原地存活、指向新节点**
    #   ⇒ 运行期「绑定优先级最高」⇒ **静默用错设备**。
    #   实测踩到：「P_AUTO-3 洗脱」的 Elute 发给了 P_AUTO-1 ⇒ P_AUTO-3 永不报
    #   【等待交互】⇒ 流程卡死，而日志/界面没有一处指向真凶。
    #
    #   ⇒ 现在**图和绑定在同一个脚本里一起生成**（setup-extraction-flow.py），
    #      生成器知道每个节点绑谁 ⇒ 不可能张冠李戴；写完还**回读自检**。
    #
    #   本脚本只管**与图无关**的两件事：点位×方法、设备池。
    section("5. 节点绑定归流程脚本管（本脚本不再写）")
    ok("节点绑定 = setup-extraction-flow.py（与图同源生成 + 回读自检）")

    print()
    print("=" * 68)
    print("绑定链配完（点位×方法 + 设备池）。")
    print("  · 点位×方法：设计器里节点能选到方法了")
    print("  · 节点绑定：在 setup-extraction-flow.py 里与图同源生成（不在这里）")
    print("下一步：setup-extraction-flow.py → seed-extraction-durations.py → 起模拟器跑一次端到端")
    print("=" * 68)


if __name__ == "__main__":
    main()
