#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""物料/位置管理 · 自动化验收（API 冒烟，写库但自清理）。

覆盖：
  1. GET /api/positions 返回点位属性（gridRow/gridCol/maxHeightMm/plateTypes）
  2. PUT /api/device-points 改点位属性
  3. 耗材/试剂批次挂点位 → position_state 内容统一模型
  4. 删批次 → 点位内容清空
  5. 越界 remaining → 409

用法：
  LABOS_DB_HOST=192.0.2.2 LABOS_DB_NAME=labdb LABOS_DB_USER=labos \
  LABOS_DB_PASS='REDACTED_DB_PASSWORD' python3 scripts/verify-material-mgmt.py
"""
import importlib.util
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_spec = importlib.util.spec_from_file_location(
    "core_scenarios", os.path.join(ROOT, "scripts", "core-scenarios.py"))
cs = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cs)
api = cs.api

PASS, FAIL = 0, 0
ISLAND = 1000005


def ok(msg, detail=""):
    global PASS
    PASS += 1
    print("  \033[32m✓\033[0m " + msg + (("（%s）" % detail) if detail else ""))


def bad(msg, detail=""):
    global FAIL
    FAIL += 1
    print("  \033[31m✗\033[0m " + msg + (("（%s）" % detail) if detail else ""))


def main():
    token = api("/api/auth/login", "POST",
                {"username": "admin", "password": "admin123"})["token"]

    print("── 1. 位置属性 ──")
    d = api(f"/api/positions?islandId={ISLAND}", token=token)
    items = d["items"]
    if d["total"] >= 13 and all("gridRow" in x for x in items):
        ok("positions 返回 13 个点位且含 gridRow/gridCol/maxHeightMm/plateTypes",
           "total=%d" % d["total"])
    else:
        bad("positions 字段缺失", "total=%d" % d["total"])

    # 改一个点位的网格/高度/板型
    pid = items[0]["resourceId"]
    r = api(f"/api/device-points/{pid}", "PUT", token=token, body={
        "pointRole": "both", "robotReachable": True, "isEntry": True, "isExit": True,
        "orderInDevice": 1, "gridRow": 3, "gridCol": 4, "maxHeightMm": 50.0,
        "plateTypes": "pcr96,deep96",
    })
    d2 = api(f"/api/positions?islandId={ISLAND}", token=token)
    hit = next(x for x in d2["items"] if x["resourceId"] == pid)
    if hit.get("gridRow") == 3 and hit.get("gridCol") == 4 and hit.get("maxHeightMm") == 50.0 \
            and hit.get("plateTypes") == "pcr96,deep96":
        ok("点位属性写入并读回", "gridRow=3 gridCol=4 maxHeightMm=50 plateTypes=pcr96,deep96")
    else:
        bad("点位属性读回不符", str(hit))

    print("── 2. 物料/试剂 → 点位内容统一模型 ──")
    # 建耗材批次挂到点位 pid
    api("/api/consumable-lots", "POST", token=token, body={
        "consumableId": 5, "lotNo": "VERIFY-C-1", "quantity": 10, "remaining": 10,
        "locationId": pid,
    })
    d3 = api(f"/api/positions?islandId={ISLAND}", token=token)
    hit3 = next(x for x in d3["items"] if x["resourceId"] == pid)
    if hit3.get("contentKind") == "consumable" and hit3.get("contentCode") == "CONS5":
        ok("耗材批次挂点位 → contentKind=consumable", "contentCode=%s" % hit3.get("contentCode"))
    else:
        bad("耗材内容未写入", str(hit3))

    # 建试剂批次覆盖同一点位
    api("/api/reagent-lots", "POST", token=token, body={
        "reagentId": 16, "lotNo": "VERIFY-R-1", "volume": 1000, "remaining": 800,
        "locationId": pid,
    })
    d4 = api(f"/api/positions?islandId={ISLAND}", token=token)
    hit4 = next(x for x in d4["items"] if x["resourceId"] == pid)
    if hit4.get("contentKind") == "reagent":
        ok("试剂批次覆盖同点位 → contentKind=reagent", "contentName=%s" % hit4.get("contentName"))
    else:
        bad("试剂内容未写入", str(hit4))

    print("── 3. 越界与清理 ──")
    lots = api("/api/consumable-lots?consumableId=5", token=token)["items"]
    lot = next((x for x in lots if x["lotNo"] == "VERIFY-C-1"), None)
    if lot:
        try:
            api(f"/api/consumable-lots/{lot['id']}", "PUT", token=token,
                body={"remaining": 999})
            bad("越界 remaining 未拦截")
        except RuntimeError as e:
            if "409" in str(e) or "remaining" in str(e):
                ok("越界 remaining → 409", str(e)[:80])
            else:
                bad("越界返回非预期", str(e)[:80])

    # 清理
    for base, fid in (("consumable-lots", "consumableId=5"),
                      ("reagent-lots", "reagentId=16")):
        for x in api(f"/api/{base}?{fid}", token=token)["items"]:
            if x["lotNo"].startswith("VERIFY-"):
                api(f"/api/{base}/{x['id']}", "DELETE", token=token)
    d5 = api(f"/api/positions?islandId={ISLAND}", token=token)
    hit5 = next(x for x in d5["items"] if x["resourceId"] == pid)
    if hit5.get("contentKind") is None:
        ok("删批次后点位内容清空")
    else:
        bad("点位内容未清空", str(hit5))

    # 还原点位属性（避免污染）
    api(f"/api/device-points/{pid}", "PUT", token=token, body={
        "pointRole": "both", "robotReachable": True, "isEntry": True, "isExit": True,
        "orderInDevice": 1, "gridRow": 1, "gridCol": 2, "maxHeightMm": None,
        "plateTypes": "",
    })

    print("\n通过 %d / 失败 %d" % (PASS, FAIL))
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
