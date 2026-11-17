#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""提取线的**放料**：播种模拟板 + 把板放到耗材栈的磁套槽。

**为什么必须有这一步**（2026-11-17 从零建岛实测）：
新岛的点位账本全是空的 ⇒ 流程第一段搬运「从栈取磁套板」**无板可搬** ⇒
报 `EC-POSITION-OCCUPIED`（源位无板）⇒ 节点转人工、run 暂停。
（老岛能跑是因为栈里还留着上一轮跑完的板 —— **靠残留兜底，不是靠配置**。）

两条硬约束（都踩过）：
① 板在库里是**一板一位**（`uk_pos_plate` = UNIQUE(tenant_id, plate_id)）
   ⇒ 原岛那 4 块磁套板还占着原岛的槽位，**新岛必须用新条码**；
② `POST /api/plates/{barcode}/locate` 的 `actualLocation` **必须是数字（点位资源 id）**，
   传点位 code 会静默不写位置账本（`Long.parseLong` 失败就 return）。

用法：
    LABOS_ISLAND_CODE=LAB-EXT-02 LABOS_DEV_TAG=-02- \\
    python3 scripts/setup-extraction-materials.py
"""

import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from extraction_config import ISLAND_CODE, mag_plate_barcodes  # noqa: E402

API = os.environ.get("LABOS_API", "http://127.0.0.1:18080").rstrip("/")
USER = os.environ.get("LABOS_USER", "admin")
PASSWORD = os.environ.get("LABOS_PASS", "admin123")
DB_HOST = os.environ.get("LABOS_DB_HOST", "192.168.124.2")
DB_PORT = os.environ.get("LABOS_DB_PORT", "5432")
DB_NAME = os.environ.get("LABOS_DB_NAME", "labdb")
DB_USER = os.environ.get("LABOS_DB_USER", "labos")
DB_PASS = os.environ.get("LABOS_DB_PASS", "")

TOKEN = None


def api(method, path, body=None):
    url = API + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if TOKEN:
        req.add_header("Authorization", f"Bearer {TOKEN}")
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            raw = r.read().decode("utf-8")
            return r.status, (json.loads(raw) if raw.strip() else {})
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"error": raw[:300]}


def psql(sql):
    """跑一条 SQL（播种静态物料主数据；不用它改运行状态）。"""
    env = dict(os.environ, PGPASSWORD=DB_PASS)
    p = subprocess.run(["psql", "-h", DB_HOST, "-p", DB_PORT, "-U", DB_USER,
                        "-d", DB_NAME, "-tA", "-c", sql],
                       capture_output=True, text=True, env=env)
    return p.returncode, p.stdout.strip(), p.stderr.strip()


def main():
    print("=" * 68)
    print(f"{ISLAND_CODE} · 放料（播种模拟板 + 放到耗材栈磁套槽）")
    print("=" * 68)
    st, j = api("POST", "/api/auth/login", {"username": USER, "password": PASSWORD})
    if st != 200 or not j.get("token"):
        sys.exit(f"✘ 登录失败（{st}）：{j}")
    global TOKEN
    TOKEN = j["token"]

    barcodes = mag_plate_barcodes()

    # ---- ① 播种板（静态物料主数据；平台没有建板 API，只能落库）
    print()
    print("── 1. 播种模拟板 ──")
    for bc in barcodes:
        rc, out, err = psql(
            "INSERT INTO plate (tenant_id, barcode, plate_type, rows, cols, status, "
            "identity_status, created_at, updated_at) "
            f"VALUES (1, '{bc}', 'unknown', 96, 12, 'active', 'PROVISIONAL', now(), now()) "
            "ON CONFLICT (tenant_id, barcode) DO NOTHING;")
        if rc != 0:
            print(f"  ✘ {bc} 播种失败：{err[:150]}")
        else:
            print(f"  ✔ {bc}")

    # ---- ② 找磁套槽
    print()
    print("── 2. 取耗材栈磁套槽 ──")
    st, topo = api("GET", "/api/topology")
    island = next((i for i in topo.get("islands", []) if i.get("code") == ISLAND_CODE), None)
    if island is None:
        sys.exit(f"✘ 找不到岛 {ISLAND_CODE}")
    st, pos = api("GET", f"/api/positions?islandId={island['id']}")
    magnet = sorted([p for p in (pos.get("items") or [])
                      if (p.get("positionLabel") or "").upper() == "MAGNET"],
                     key=lambda p: p.get("code") or "")
    if len(magnet) < 4:
        sys.exit(f"✘ 磁套槽只有 {len(magnet)} 个，不够 4 台提取仪各一块")
    slots = magnet[:4]
    print(f"  磁套槽：{', '.join(s['code'] for s in slots)}")

    # ---- ③ 放板（actualLocation 必须是**点位资源 id**，不是 code）
    print()
    print("── 3. 放板（人工盘点指认位置）──")
    for bc, slot in zip(barcodes, slots):
        rid = slot.get("resourceId") or slot.get("pointResourceId")
        if not rid:
            print(f"  ✘ {slot.get('code')} 拿不到资源 id")
            continue
        st, j = api("POST", f"/api/plates/{bc}/locate", {"actualLocation": str(rid)})
        ok = j.get("ok")
        print(f"  {'✔' if ok else '✘'} {bc} → {slot.get('code')} (id={rid}) {'' if ok else j}")

    # ---- ④ 核对位置账本
    print()
    print("── 4. 核对位置账本 ──")
    ids = ",".join(str(s.get("resourceId")) for s in slots)
    rc, out, err = psql(
        "SELECT r.code || ' | plate=' || COALESCE(p.plate_id, '(空)') "
        "FROM resource_node r LEFT JOIN position_state p ON p.resource_id = r.id "
        f"WHERE r.id IN ({ids}) ORDER BY r.code;")
    print("  " + (out.replace("\n", "\n  ") if out else err[:200]))


if __name__ == "__main__":
    main()
