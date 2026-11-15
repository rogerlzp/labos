#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""按客户《高通量核酸提取工作站 · 软件控制流程说明书》灌状态字典。

**口径来源**：
- §1.4 板状态枚举（6 态）：空板 / 待分液 / 实验中 / 待交互（等待送入提取仪）/
  已完成（产物板）/ 废弃耗材板
- §1.5 设备状态枚举（按设备类别 5 组）

**为什么走 API 而不是写 SQL**：
- 顺带验证端点能不能用（写 SQL 验证不了接口）；
- 现场重放时不用懂库结构。

**幂等**：只补**缺的那条**，不改已有条目（已有条目可能是现场改过的）。

用法：
    LABOS_API=http://127.0.0.1:18084 python3 scripts/seed-workstation-state-dicts.py
"""

import json
import os
import sys
import urllib.error
import urllib.request

API = os.environ.get("LABOS_API", "http://127.0.0.1:18080").rstrip("/")
USER = os.environ.get("LABOS_USER", "admin")
PASSWORD = os.environ.get("LABOS_PASS", "admin123")

TOKEN = None


def call(method, path, body=None, query=None):
    url = API + path
    if query:
        url += "?" + "&".join(f"{k}={v}" for k, v in query.items())
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    if data:
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


# ---------------------------------------------------------------- 口径

# §1.4 板状态：(平台 code, 平台显示名, 客户文档叫法, 排序)
# ⚠ 平台 code 是**通用**口径（raw/ready/…），客户叫法放在 docLabel；
#   既有条目**不改名**（plate_process_state 已被运行时消费，改名会打断既有物料效果）。
PLATE_STATES = [
    ("raw", "未处理", "空板", 10),
    ("ready", "已就绪", "待分液", 20),
    ("in_progress", "处理中", "实验中", 30),
    ("waiting_transfer", "待交互（等待送入提取仪）", "待交互（等待送入提取仪）", 40),
    ("completed", "已完成", "已完成（产物板）", 90),
    ("discarded", "已废弃", "废弃耗材板", 99),
]

# §1.5 设备业务状态：device_kind 用**平台 device_type 口径**（与文档 5 类设备一一对应）
DEVICE_KINDS = {
    "extractor": [
        ("idle", "空闲", 10),
        ("initializing", "初始化中", 20),
        ("waiting_interaction", "等待交互", 30),
        ("turntable_running", "转盘运行中", 40),
        ("operating", "操作位执行工序（裂解/磁吸/W1/W2/W3/洗脱）", 50),
        ("finished", "流程结束", 60),
        ("fault", "故障报错", 70),
    ],
    "dispenser": [
        ("ready", "就绪", 10),
        ("dispensing", "分液中", 20),
        ("dispensed", "分液完成", 30),
        ("fault", "故障", 40),
    ],
    "stirrer": [
        ("running", "运行", 10),
        ("stopped", "停止", 20),
        ("fault", "故障", 30),
    ],
    "robot": [
        ("home", "原点", 10),
        ("moving", "移动中", 20),
        ("handling", "取放板中", 30),
        ("occupied", "占位", 40),
        ("fault", "故障", 50),
    ],
    "plate_hotel": [
        ("sufficient", "充足", 10),
        ("warning", "预警", 20),
        ("insufficient", "不足", 30),
    ],
}


def main():
    print("=" * 68)
    print("灌状态字典（客户文档 §1.4 板状态 + §1.5 设备业务状态）")
    print("=" * 68)
    print(f"API: {API}")
    st, j = call("POST", "/api/auth/login", {"username": USER, "password": PASSWORD})
    if st != 200 or not j.get("token"):
        sys.exit(f"✘ 登录失败（{st}）：{j}")
    global TOKEN
    TOKEN = j["token"]
    print(f"✓ 已登录 {USER}")

    # ---------------------------------------------------------------- 板状态
    section("1. 板状态（§1.4 六态）")
    st, j = call("GET", "/api/plate-process-states", query={"includeDisabled": "true"})
    if st != 200:
        sys.exit(f"✘ 读板状态字典失败（{st}）：{j}")
    existing = {it["code"]: it for it in j.get("items", [])}
    added = patched = 0
    for code, display, doc_label, sort in PLATE_STATES:
        cur = existing.get(code)
        if cur is None:
            st, r = call("POST", "/api/plate-process-states", {
                "code": code, "displayName": display,
                "sortOrder": sort, "docLabel": doc_label,
            })
            if st == 200:
                added += 1
            else:
                bad(f"新建板状态失败：{code}（{st}）", str(r)[:150])
            continue
        # 已存在 ⇒ **只补 docLabel**（显示名/排序可能是现场改过的，不动）
        if not (cur.get("docLabel") or "").strip():
            st, r = call("PATCH", f"/api/plate-process-states/{code}", {"docLabel": doc_label})
            if st == 200:
                patched += 1
            else:
                bad(f"补客户叫法失败：{code}（{st}）", str(r)[:150])
    ok(f"板状态：新建 {added} · 补客户叫法 {patched} · 已有 {len(existing)}")

    # ---------------------------------------------------------------- 设备业务状态
    section("2. 设备业务状态（§1.5 五类设备）")
    st, j = call("GET", "/api/device-business-states")
    if st != 200:
        sys.exit(f"✘ 读设备业务状态字典失败（{st}）：{j}")
    have = {(it["deviceKind"], it["stateCode"]) for it in j.get("items", [])}
    added = patched = 0
    for kind, states in DEVICE_KINDS.items():
        for code, name, sort in states:
            if (kind, code) in have:
                continue
            st, r = call("POST", "/api/device-business-states", {
                "deviceKind": kind, "stateCode": code,
                "stateName": name, "sortOrder": sort,
            })
            if st == 200:
                added += 1
            else:
                bad(f"新建设备状态失败：{kind}/{code}（{st}）", str(r)[:150])
    ok(f"设备业务状态：新建 {added} · 已有 {len(have)}")

    # ---------------------------------------------------------------- 复核
    section("3. 复核（回读比对）")
    st, j = call("GET", "/api/plate-process-states", query={"includeDisabled": "true"})
    got = {it["code"]: it for it in j.get("items", [])}
    missing = [c for c, _, _, _ in PLATE_STATES if c not in got]
    if missing:
        bad(f"板状态缺：{missing}")
    else:
        ok(f"板状态 {len(PLATE_STATES)} 态齐（客户叫法 "
           f"{sum(1 for c, _, _, _ in PLATE_STATES if (got[c].get('docLabel') or '').strip())} 条已填）")

    st, j = call("GET", "/api/device-business-states")
    got = {(it["deviceKind"], it["stateCode"]) for it in j.get("items", [])}
    missing = [(k, c) for k, ss in DEVICE_KINDS.items() for c, _, _ in ss if (k, c) not in got]
    if missing:
        bad(f"设备状态缺：{missing}")
    else:
        ok(f"设备业务状态 {sum(len(v) for v in DEVICE_KINDS.values())} 条齐"
           f"（{len(DEVICE_KINDS)} 类设备）")

    print()
    print("=" * 68)
    print("状态字典已灌好。管理页：/state-dicts")
    print("=" * 68)


if __name__ == "__main__":
    main()
