#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""建「高通量核酸提取工作站」的现场数据（岛 / 设备 / 点位 / 池）。

**为什么是脚本而不是手点**：这套数据有 18 台设备 + 60 个耗材栈槽位 + 提取仪双工位
—— 手点几百次，而且每次都要选对型号/工位语义。脚本**可复跑**（幂等）：
改了参数重跑即可，不用重来一遍。

**走真实 HTTP API**（不是直接写 SQL）⇒ 走的每一步都是平台上真的那条路
（建岛 → 部署实例 → 造点位 → 建池），等于**顺带验证了接口能不能用**。

用法：
    LABOS_API=http://127.0.0.1:18082 \
    LABOS_USER=admin LABOS_PASS=admin123 \
    python3 scripts/setup-extraction-station.py

⚠ **幂等口径**：岛按 code 找、池按 code 找、设备按 code 找 ⇒ 重跑不会造重复。
   点位用 spec/grid 端点（后端本身就是幂等的：同编码复用并更新）。

参考：
    private/实际流程/高通量核酸提取工作站描述.docx（客户文档 §1.1/§1.3/§3）
    private/实际流程/高通量核酸提取工作站-流程节点序列草案.md
"""

import json
import os
import sys
import urllib.error
import urllib.request

API = os.environ.get("LABOS_API", "http://127.0.0.1:18080").rstrip("/")
USER = os.environ.get("LABOS_USER", "admin")
PASSWORD = os.environ.get("LABOS_PASS", "admin123")

# 岛 / 设备编号 / 端口段都在共享配置层（scripts/extraction_config.py）：
# 环境变量可覆盖 ⇒ 同一套脚本能建**多个**岛，且编号与端口都不撞。
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from extraction_config import (  # noqa: E402
    ISLAND_CODE, ISLAND_NAME, arm_code, disp_codes, ext_codes, pick_ports,
    pool_code, stack_code,
)

TOKEN = None


def call(method, path, body=None, query=None):
    """调一次 API；返回 (status, json)。非 2xx 不抛异常（调用方自己判）。"""
    url = API + path
    if query:
        url += "?" + "&".join(f"{k}={v}" for k, v in query.items())
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if TOKEN:
        req.add_header("Authorization", f"Bearer {TOKEN}")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read().decode("utf-8")
            return r.status, (json.loads(raw) if raw.strip() else {})
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"error": raw[:300]}


def ok(msg):
    print(f"  ✔ {msg}")


def bad(msg, detail=""):
    print(f"  ✘ {msg}")
    if detail:
        print(f"      {detail}")


def section(t):
    print()
    print(f"── {t}")


# ---------------------------------------------------------------- 登录


def login():
    global TOKEN
    st, j = call("POST", "/api/auth/login", {"username": USER, "password": PASSWORD})
    if st != 200 or not j.get("token"):
        sys.exit(f"✘ 登录失败（{st}）：{j}")
    TOKEN = j["token"]
    print(f"✓ 已登录 {USER}")


# ---------------------------------------------------------------- 查型号


def model_ids():
    """型号 id → {deviceType, capability, model}。"""
    st, j = call("GET", "/api/device-models")
    if st != 200:
        sys.exit(f"✘ 读型号库失败（{st}）：{j}")
    out = {}
    for m in j.get("items", []):
        out[m["modelId"]] = m
    return out


def find_model(models, device_type=None, capability=None):
    """按设备类型或能力找型号（取第一个）。

    ⚠ 用 **capability** 匹配更稳：deviceType 的写法随登记人变化
    （提取仪登记成 "Extraction"、分液器 "Dispensers & Washers"、搅拌 "MIX"），
    而 capability 是平台词表里的固定值（EXTRACT/WASH/MIX/TRANSFER/STORE）。
    """
    for mid, m in models.items():
        if device_type and m.get("deviceType") != device_type:
            continue
        if capability and m.get("capability") != capability:
            continue
        if not device_type and not capability:
            continue
        return mid, m
    return None, None


def find_model_by_code(models, code):
    """按**型号 code** 精确找（如 P_AUTO）。

    为什么需要它：EXTRACT 能力下有两个型号 —— P_AUTO（客户真设备，方法集 12 个
    真实动作）与 CAROUSEL_EXTRACTOR_GENERIC（泛型，方法名完全不同）⇒
    按 capability 取第一个会**取错型号**，流程要的 Bind/Elute/Incubate/Wash/
    PickTipComb/DropTipComb 只有 P_AUTO 有（实测泛型只命中 1/7）。
    """
    for mid, m in models.items():
        if m.get("code") == code:
            return mid, m
    return None, None


# ---------------------------------------------------------------- 建岛


def ensure_driver(code, name, endpoint):
    """登记驱动（按 code 幂等）。返回 driver id。

    ⚠ 设备**必须有驱动**：驱动没绑 ⇒ 驱动注册表按 resource_capability 分能力，
    设备不在任何驱动名下 ⇒ 现象是"设备在界面上有点不动"（EC-06-0001 驱动未加载）。
    """
    st, j = call("GET", "/api/drivers")
    for d in j.get("items", []):
        if d.get("code") == code:
            return d.get("id")
    st, j = call("POST", "/api/drivers", {
        "code": code, "name": name, "protocol": "mock", "endpoint": endpoint,
        "vendor": "simulator", "resident": True,
    })
    if st != 200:
        bad(f"登记驱动 {code} 失败（{st}）", str(j)[:200])
        return None
    ok(f"驱动已登记：{code} → {endpoint}")
    return j.get("id")


def ensure_island():
    st, j = call("GET", "/api/topology")
    for isl in j.get("islands", []):
        if isl.get("code") == ISLAND_CODE:
            ok(f"岛已存在：{ISLAND_CODE}（id={isl['id']}）")
            return isl["id"]
    st, j = call("POST", "/api/islands", {"code": ISLAND_CODE, "name": ISLAND_NAME})
    if st != 200:
        sys.exit(f"✘ 建岛失败（{st}）：{j}")
    ok(f"岛已建：{ISLAND_CODE}（id={j['id']}）")
    return j["id"]


# ---------------------------------------------------------------- 部署设备


def deploy(island_id, model_id, code, name, driver_id=None, ip=None):
    """部署一个设备实例（按 code 幂等：已存在就复用）。"""
    st, topo = call("GET", "/api/topology")
    for isl in topo.get("islands", []):
        for d in isl.get("devices", []):
            if d.get("code") == code:
                ok(f"设备已存在：{code}（resourceId={d['resourceId']}）")
                return d["resourceId"]
    q = {"islandId": island_id}
    if ip:
        q["ip"] = ip
    if driver_id:
        q["driverId"] = driver_id
    st, j = call("POST", f"/api/device-models/{model_id}/deploy", query=q)
    if st != 200:
        bad(f"部署 {code} 失败（{st}）", str(j)[:200])
        return None
    rid = j.get("resourceId")
    ok(f"设备已部署：{name}（resourceId={rid}）")
    return rid


def rename_device(resource_id, code, name):
    """改设备编号与名字（deploy 生成的编号不符合现场命名时要改）。"""
    st, j = call("PUT", f"/api/devices/{resource_id}", {"name": name})
    if st != 200:
        bad(f"改设备名 {code} 失败（{st}）", str(j)[:200])
        return False
    # 编号单独走 PUT /api/devices/{id}/code —— 三处同步改在同一事务里
    st, j = call("PUT", f"/api/devices/{resource_id}/code", {"code": code})
    if st != 200:
        bad(f"改设备编号 {code} 失败（{st}）", str(j)[:200])
        return False
    ok(f"设备已改名改号：{code} / {name}")
    return True


# ---------------------------------------------------------------- 造点位


def spec_points(resource_id, device_code, points):
    """按规格造点位（后端幂等：同编码复用并更新）。"""
    st, j = call("POST", f"/api/devices/{resource_id}/points/spec",
                 {"deviceCode": device_code, "points": points})
    if st != 200:
        bad(f"造点位 {device_code} 失败（{st}）", str(j)[:250])
        return 0
    ok(f"点位已造：{device_code}（{j.get('created')} 个）")
    return j.get("created", 0)


def grid_points(resource_id, device_code, cols, rows, slot_kind="nest", slot_index_base=1,
                max_stack=1, max_height_mm=45, plate_types="deep96"):
    """批量造网格点位（耗材栈那种均匀布局）。"""
    body = {
        "deviceCode": device_code,
        "cols": cols, "rows": rows,
        "pointKind": "transfer_point",
        "slotKind": slot_kind,
        "slotIndexBase": slot_index_base,
        "maxStack": max_stack,
        "plateTypes": plate_types,
        "isEntry": True, "isExit": True, "robotReachable": True,
    }
    if max_height_mm is not None:
        body["maxHeightMm"] = max_height_mm
    st, j = call("POST", f"/api/devices/{resource_id}/points/grid", body)
    if st != 200:
        bad(f"造网格 {device_code} 失败（{st}）", str(j)[:250])
        return 0
    ok(f"网格点位已造：{device_code}（{j.get('created')} 个）")
    return j.get("created", 0)


# ---------------------------------------------------------------- 建池


def ensure_pool(island_id, code, name, pool_kind, strategy, members):
    """建池（按 code 幂等）。members 是 deviceInstanceId 列表。"""
    st, j = call("GET", "/api/pools", query={"islandId": island_id})
    for p in j.get("items", []):
        if p.get("code") == code:
            ok(f"池已存在：{code}（id={p['id']}，{p.get('memberCount')} 个成员）")
            return p["id"]
    body = {
        "code": code, "name": name, "islandId": island_id,
        "poolKind": pool_kind, "strategy": strategy,
        "skipOnError": True, "skipOnOffline": True,
        "allowIterationOnError": True, "minNestCount": 1,
        "members": [{"deviceInstanceId": i, "priority": 100} for i in members],
    }
    st, j = call("POST", "/api/pools", body)
    if st != 200:
        bad(f"建池 {code} 失败（{st}）", str(j)[:250])
        return None
    ok(f"池已建：{code}（id={j['id']}，{j.get('memberCount')} 个成员）")
    return j["id"]


# ---------------------------------------------------------------- 主流程


def main():
    print("=" * 68)
    print("建「高通量核酸提取工作站」现场数据")
    print("=" * 68)
    print(f"API: {API}")
    login()

    models = model_ids()
    print(f"✓ 型号库 {len(models)} 条")

    # ---- 岛
    section("1. 建岛")
    island_id = ensure_island()

    # ---- 型号
    section("2. 找型号")
    # 按 **capability** 找（EXTRACT/WASH/TRANSFER/STORE）——
    # deviceType 的写法随登记人变化，按能力找才稳。
    ext_model, ext_m = find_model(models, capability="EXTRACT")
    disp_model, disp_m = find_model(models, capability="WASH")
    gantry_model, gantry_m = find_model(models, capability="TRANSFER")
    stack_model, stack_m = find_model(models, capability="STORE")
    # 搅拌单元**不做独立设备**（用户口径）⇒ 不查 MIX 型号、也不建搅拌设备。
    # 搅拌做成 DISP 的搅拌工位/状态（见下面造点位的部分）。
    for label, m in (("提取仪", ext_m), ("分液器", disp_m),
                     ("轨道四轴臂", gantry_m), ("耗材栈", stack_m)):
        if m is None:
            bad(f"型号库里找不到 {label}（按能力匹配）")
            sys.exit(1)
        ok(f"{label}：modelId={m['modelId']} capability={m.get('capability')}")

    # ---- 设备
    section("3. 登记驱动（每台设备一个 mock 驱动 —— 没驱动就点不动）")
    # 端点端口**挑空闲的**（不固定顺排）：固定顺排会与既有岛撞端口
    #   （实测撞过 4 组：DISP5 与 P_AUTO-1 同为 19608 ⇒ 两台设备连同一端点 ⇒ 状态串台）。
    st, drv_rows = call("GET", "/api/drivers")
    used_ports = []
    for row in (drv_rows.get("items") or []):
        ep = str(row.get("endpoint") or "")
        if ":" in ep:
            try:
                used_ports.append(int(ep.rsplit(":", 1)[1]))
            except ValueError:
                pass
    ext_list = ext_codes()
    dev_list = ([(c, f"自动化分液器 {c}", disp_model) for c in disp_codes()]
                + [(arm_code(), f"轨道式四轴机械臂 {arm_code()}", gantry_model),
                   (stack_code(), f"自动化耗材栈 {stack_code()}", stack_model)])
    ports = pick_ports(len(dev_list) + len(ext_list), used=used_ports)
    drivers = {}
    for (code, name, model_id), port in zip(dev_list, ports):
        drv = ensure_driver(f"DRV-SIM-{code}", f"{name} 模拟驱动",
                            f"tcp://127.0.0.1:{port}")
        drivers[code] = drv

    section("4. 部署设备")
    # 提取仪：**用既有的 P_AUTO-1~4**（PAuto 磁珠法核酸提取仪 = 客户真设备型号，
    #   库里已有实例与 12 个真实动作方法）⇒ 不重复造。
    #   其余（分液器/臂/栈）用泛型型号新建。
    deployed = []

    def deploy_named(model_id, code, name, driver_id=None):
        # 先看是否已存在（幂等）：已存在就**直接用**，不要再改编号
        # （编号已经是现场编号了，再改一次会撞 409 "编号已被占用"）。
        st, topo0 = call("GET", "/api/topology")
        for isl in topo0.get("islands", []):
            if isl.get("id") != island_id:
                continue
            for d in isl.get("devices", []):
                if d.get("code") == code:
                    ok(f"设备已存在：{code}（resourceId={d['resourceId']}）")
                    deployed.append((code, name, d["resourceId"]))
                    return d["resourceId"]
        rid = deploy(island_id, model_id, code, name, driver_id=driver_id)
        if rid is None:
            return None
        if not rename_device(rid, code, name):
            return None
        deployed.append((code, name, rid))
        return rid

    disp_ids = []
    for c in disp_codes():
        rid = deploy_named(disp_model, c, f"自动化分液器 {c}", drivers.get(c))
        if rid:
            disp_ids.append(rid)
    arm_id = deploy_named(gantry_model, arm_code(), f"轨道式四轴机械臂 {arm_code()}",
                          drivers.get(arm_code()))
    stack_id = deploy_named(stack_model, stack_code(), f"自动化耗材栈 {stack_code()}",
                             drivers.get(stack_code()))

    # 重新取一次，拿改完的编号与 instanceId
    st, topo = call("GET", "/api/topology")
    by_code = {}
    for isl in topo.get("islands", []):
        if isl.get("id") == island_id:
            for d in isl.get("devices", []):
                by_code[d["code"]] = d
    ok(f"拓扑里已有 {len(by_code)} 台设备")

    # 提取仪：型号 **P_AUTO**（客户真设备型号，方法集 12 个真实动作）。
    #   编号带岛标记（LABOS_DEV_TAG）⇒ 能建第二个岛而不撞编号
    #   （device_instance 的唯一约束 UNIQUE(tenant_id, code) **不按岛隔离**）。
    #   ⚠ 型号不能选泛型 CAROUSEL_EXTRACTOR_GENERIC：流程要的 7 个方法
    #     （Bind/Elute/Incubate/Wash/PickTipComb/DropTipComb/GetStatus）
    #     泛型只命中 1 个 ⇒ 流程一跑就「方法未授权」。
    ext_list = ext_codes()
    have_ext = [c for c in ext_list if c in by_code]
    if len(have_ext) != len(ext_list):
        # 本岛还没有这 4 台 ⇒ 用 P_AUTO 型号**新建**（建第二个岛走这条路）
        pauto_model, pauto_m = find_model_by_code(models, "P_AUTO")
        if pauto_model is None:
            bad("型号库里找不到 P_AUTO（客户真设备型号）")
            sys.exit(1)
        ok(f"提取仪型号：P_AUTO（modelId={pauto_model}，厂商 {pauto_m.get('vendor')}）")
        for c in ext_list:
            if c in by_code:
                continue
            deploy_named(pauto_model, c, f"PAuto 磁珠法核酸提取仪 {c}", drivers.get(c))
    else:
        ok(f"提取仪：沿用本岛既有的 {', '.join(have_ext)}（PAuto 磁珠法核酸提取仪）")

    # 给 4 台提取仪补 mock 驱动（端口取预留段里的，不与别的设备撞）
    for c, port in zip(ext_list, ports[len(dev_list):]):
        d = by_code.get(c)
        if not d:
            continue
        drv = ensure_driver(f"DRV-SIM-{c}", f"{c} 模拟驱动",
                            f"tcp://127.0.0.1:{port}")
        if drv:
            st, j = call("PUT", f"/api/devices/{d['resourceId']}", {"driverId": drv})
            if st != 200:
                bad(f"给 {c} 绑驱动失败（{st}）", str(j)[:150])

    # 提取仪可能是刚部署的 ⇒ 造点位前再取一次拓扑
    st, topo = call("GET", "/api/topology")
    by_code = {}
    for isl in topo.get("islands", []):
        if isl.get("id") == island_id:
            for d in isl.get("devices", []):
                by_code[d["code"]] = d
    ok(f"拓扑里共有 {len(by_code)} 台设备")

    # ---- 点位
    section("5. 造点位")
    # 5a. 提取仪双工位：操作位（机械臂够不到）+ 交互位（够得到）
    for code in ext_list:
        d = by_code.get(code)
        if not d:
            bad(f"{code} 不在拓扑里，跳过")
            continue
        spec_points(d["resourceId"], code, [
            {"code": f"{code}-INT", "label": "INT1", "slotKind": "deck_in",
             "pointKind": "transfer_point"},
            {"code": f"{code}-EXT", "label": "EXT1", "slotKind": "deck_out",
             "pointKind": "transfer_point"},
        ])

    # 5b. 分液器：板位（可达）+ 搅拌工位
    #     **搅拌单元不做独立设备**（用户口径）⇒ 搅拌是 DISP 的**工位/状态**，
    #     不是另一台设备 ⇒ 这里只给 DISP 建两个点位：
    #       PLATE   —— 机械臂放板处
    #       STIR    —— 搅拌工位（对应搅拌单元；DISP2 是顶置搅拌，其余是磁力搅拌）
    for i, code in enumerate(disp_codes(), start=1):
        d = by_code.get(code)
        if not d:
            bad(f"{code} 不在拓扑里，跳过")
            continue
        stir_label = "STIR-TOP" if i == 2 else "STIR-MAG"
        spec_points(d["resourceId"], code, [
            {"code": f"{code}-PLATE", "label": "PLATE", "slotKind": "nest",
             "pointKind": "transfer_point"},
            {"code": f"{code}-STIR", "label": stir_label, "slotKind": "nest",
             "pointKind": "transfer_point"},
        ])

    # 5c. 耗材栈 60 槽，**按耗材类型分区**（用户口径：「需要按照不同的耗材类型来分开」）
    #
    #     为什么用 spec 而不是 grid：grid 端点的 positionLabel 是**整台设备一个值**，
    #     表达不了"第 7 号槽放磁套板、第 23 号槽放样本板"。
    #     spec 端点逐条带 label ⇒ label 落到 device_point.position_label
    #     （现场贴的槽位标签，语义正好就是"这个槽放什么"）。
    #
    #     容量分配（60 槽，每批 4 板 ⇒ 每角色 8 槽够跑 2 批；waste 4 槽）：
    #       sample 8 · magnet 8 · lysis 8 · w1 8 · w2 8 · w3 8 · elute 8 · waste 4
    STACK_ROLES = [
        ("SAMPLE", 8), ("MAGNET", 8), ("LYSIS", 8), ("W1", 8),
        ("W2", 8), ("W3", 8), ("ELUTE", 8), ("WASTE", 4),
    ]
    if stack_id:
        d = by_code.get(stack_code())
        if d:
            points = []
            slot = 0
            for role, count in STACK_ROLES:
                for _ in range(count):
                    slot += 1
                    points.append({
                        "code": f"{stack_code()}-S{slot:02d}",
                        "label": role,          # ← 耗材类型（现场贴的槽位标签）
                        "slotKind": "nest",
                        "slotIndex": slot,      # 稳定槽位号 1..60
                        "pointKind": "transfer_point",
                    })
            n = spec_points(d["resourceId"], stack_code(), points)
            if n == len(points):
                ok(f"耗材栈 {len(points)} 槽已按耗材类型分区："
                   + " · ".join(f"{r}×{c}" for r, c in STACK_ROLES))
            else:
                bad(f"耗材栈槽位数不对：期望 {len(points)}，实际 {n}")

    # 5d. 设备池：4 台提取仪同型号 ⇒ 一个 operation 池；6 台分液器分两池
    section("6. 建设备池")
    def instance_of(code):
        d = by_code.get(code)
        return d.get("instanceId") if d else None

    ext_instances = [instance_of(c) for c in ext_list]
    ext_instances = [i for i in ext_instances if i]
    if len(ext_instances) == 4:
        ensure_pool(island_id, pool_code("POOL-EXT"), "提取仪池（4 台同型号，运行时挑一台）",
                    "operation", "first_available", ext_instances)
    else:
        bad(f"提取仪实例不齐（{len(ext_instances)}/4），跳过建池")

    # 分液器：DISP2 是磁珠工位（顶置搅拌），其余 5 台是试剂工位（磁力搅拌）
    # ⇒ 分两个池，让流程能分别选"做磁珠分装的那台"和"做洗涤分液的那台"
    disp_all = disp_codes()
    disp2 = instance_of(disp_all[1])          # 第 2 台 = 磁珠工位（顶置搅拌）
    others = [instance_of(c) for i, c in enumerate(disp_all, start=1) if i != 2]
    others = [i for i in others if i]
    if disp2:
        ensure_pool(island_id, pool_code("POOL-DISP-MAGNET"),
                    f"磁珠分装分液器池（{disp_all[1]}，顶置搅拌）",
                    "operation", "first_available", [disp2])
    if len(others) == 5:
        ensure_pool(island_id, pool_code("POOL-DISP-REAGENT"),
                    "试剂分液器池（5 台，磁力搅拌）",
                    "operation", "first_available", others)
    else:
        bad(f"试剂分液器实例不齐（{len(others)}/5），跳过建池")

    print()
    print("=" * 68)
    print("完成。岛 / 设备 / 点位 / 池 都已建好（脚本可复跑，幂等）。")
    print("下一步：建流程骨架（8 阶段 + 7 个 __awaitEvent）。")
    print("=" * 68)


if __name__ == "__main__":
    main()
