#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""配「高通量核酸提取工作站」的绑定链：点位×方法 + 节点绑定。

**三条绑定链**（后端全套都有，前端界面本轮刚补齐）：

| 链 | 表 | 配什么 | 本脚本怎么配 |
|---|---|---|---|
| 点位×方法 | `position_method` | 哪个点位允许跑哪些方法 | 提取仪两个工位各绑该型号的全部方法；分液器板位绑分液方法 |
| 节点绑定 | `node_binding` | 流程节点 → 点位/设备实例/池 + 装板方式 + **扫码策略** | 每个设备节点绑到对应点位；机械臂节点绑到机械臂 |
| 设备池 | `device_pool` | 同型号多实例池 | 已在 setup-extraction-station.py 建好 |

**扫码策略**（客户文档 §1.1：机械臂含扫码枪，每次抓板扫码记录）：
⇒ 机械臂的搬运节点用 `validate_both`（进出都校验）；
   设备的换板节点用 `validate_in`（送进去时校验）。

用法：
    LABOS_API=http://127.0.0.1:18082 python3 scripts/setup-extraction-bindings.py

⚠ 幂等：点位×方法是「已存在就跳过」（后端唯一键），节点绑定是**整体替换**
   （PUT 语义）⇒ 重跑结果一样。
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

ISLAND_CODE = "LAB-EXT-01"
WF_CODE = "EXT-WORKFLOW-01"

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
    for i in range(1, 5):
        code = f"P_AUTO-{i}"
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
    for i in range(1, 7):
        code = f"DISP{i}"
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
    p = by_code.get("STACK1-S01")
    if p and p.get("pointId"):
        ms = methods_by_device.get("STACK1", [])
        for m in ms:
            bind_position_method(p["pointId"], m["methodId"])
    ok(f"点位×方法：新增 {added} · 已存在 {skipped} · 失败 {failed}")

    # ---- 节点绑定
    section("5. 配节点绑定（node_binding）—— 扫码策略在这里")
    st, lst = call("GET", "/api/workflows", query={"limit": "300", "q": WF_CODE})
    wf = next((w for w in lst.get("items", []) if w.get("code") == WF_CODE), None)
    if wf is None:
        sys.exit(f"✘ 找不到流程 {WF_CODE}（先跑 setup-extraction-flow.py）")
    wf_id = wf["id"]
    st, detail = call("GET", f"/api/workflows/{wf_id}")
    tasks = detail.get("tasks", [])
    ok(f"流程 {WF_CODE}（id={wf_id}）：{len(tasks)} 个节点")

    # 节点名 → 绑定目标。看板/设计器里的节点名是脚本生成的（"P_AUTO-1 启动提取流程" 等）
    bound = 0
    for t in tasks:
        name = t.get("name") or ""
        method = t.get("method") or ""
        if method == "__awaitEvent":
            continue  # 逻辑节点不绑设备
        # 从节点名里认出目标设备与工位
        target = None
        for code in devs:
            if name.startswith(code + " "):
                target = code
                break
        if target is None and name.startswith("机械臂 "):
            # 机械臂节点的名字是「机械臂 放磁套板到 P_AUTO-1 交互位」⇒ 绑到 ARM1
            target = "ARM1"
        if target is None:
            continue
        d = devs[target]
        # 提取仪：绑交互位（deck_out，机械臂可达那侧）
        suffix = "EXT" if target.startswith("P_AUTO-") else "PLATE"
        pc = f"{target}-{suffix}"
        p = by_code.get(pc)
        position_id = p.get("pointId") if p else None
        if position_id is None and target != "ARM1":
            continue
        # 扫码策略：
        #   设备节点 = 换板/执行动作 ⇒ 进板时校验（validate_in）
        #   机械臂节点 = 抓板扫码 ⇒ 进出都校验（validate_both）
        is_arm = target == "ARM1"
        barcode = "validate_both" if is_arm else "validate_in"
        # 装板方式：设备节点装一块板；机械臂节点不放板
        loading = "do_not_load" if is_arm else "load_single"
        payload = {
            "bindings": [{
                # 机械臂没有自己的板位（它去别人的点位取放板）⇒ 只绑设备实例
                "positionId": position_id,
                "deviceInstanceId": d.get("instanceId"),
                "methodId": (methods_by_device.get(target) or [{}])[0].get("methodId"),
                "loadingPattern": loading,
                "barcodePolicy": barcode,
                "leaveAtStation": False,
                "params": {},
            }]
        }
        # ⚠ URL 里要用 **nodeKey**（tasks[].key，如 Activity_0002），
        #   不是节点名（tasks[].name）—— 名字有中文和空格，得 URL 编码。
        node_key = t.get("key")
        if not node_key:
            continue
        st, r = call("PUT",
                     f"/api/workflows/{wf_id}/nodes/{urllib.parse.quote(node_key, safe='')}/bindings",
                     payload)
        if st == 200:
            bound += 1
        else:
            bad(f"节点绑定失败：{name}（{st}）", str(r)[:180])
    ok(f"节点绑定：{bound} 个节点")

    print()
    print("=" * 68)
    print("绑定链配完。")
    print("  · 点位×方法：设计器里节点能选到方法了")
    print("  · 节点绑定：扫码策略已设（机械臂 validate_both / 设备 validate_in）")
    print("下一步：在设计器 /designer/%d 里核对；然后起模拟器跑一次端到端" % wf_id)
    print("=" * 68)


if __name__ == "__main__":
    main()
