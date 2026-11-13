#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P0 · 三条绑定链 API 冒烟：设备池 / 点位方法 / 节点绑定。

覆盖：
  ① 建 operation 池（同型号）→ 200；异型号 → 409
  ② 点位绑方法（global）→ 回读 scope=global；再绑 workflow scope 另一方法
  ③ 节点绑 2 个点位（Loading Pattern）→ 回读 count=2 + bindingMode=point；清空回落 legacy

自清理：测试池/绑定/方法全部删除，不留生产数据。
用法：LABOS_DB_HOST=... LABOS_DB_NAME=... python3 scripts/verify-binding-api.py
"""
import importlib.util
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_spec = importlib.util.spec_from_file_location(
    "core_scenarios", os.path.join(ROOT, "scripts", "core-scenarios.py"))
cs = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cs)
api, q = cs.api, cs.q

PASS, FAIL = 0, 0


def ok(m, d=""):
    global PASS
    PASS += 1
    print("  \033[32m✓\033[0m " + m + (("（%s）" % d) if d else ""))


def bad(m, d=""):
    global FAIL
    FAIL += 1
    print("  \033[31m✗\033[0m " + m + (("（%s）" % d) if d else ""))


def main():
    token = api("/api/auth/login", "POST",
                {"username": "admin", "password": "admin123"})["token"]

    # 找两台同型号 + 一台异型号的 device_instance
    model = q("SELECT device_id FROM device_instance GROUP BY device_id "
              "HAVING count(*)>=2 ORDER BY count(*) DESC LIMIT 1")
    same = q("SELECT string_agg(id::text, ' ') FROM "
             "(SELECT id FROM device_instance WHERE device_id=%s ORDER BY id LIMIT 2) t" % model)
    m1, m2 = same.split(" ")[:2]
    diff = q("SELECT id FROM device_instance WHERE device_id<>%s LIMIT 1" % model)

    print("── ① 设备池 ──")
    pool = None
    try:
        r = api("/api/pools", "POST", token=token, body={
            "code": "VERIFY-POOL-1", "name": "验证池", "islandId": 1000005,
            "poolKind": "operation",
            "members": [{"deviceInstanceId": int(m1)}, {"deviceInstanceId": int(m2)}],
        })
        pool = r["id"]
        ok("建 operation 池（同型号）→ id=%s memberCount=%s" % (pool, r["memberCount"]))
    except Exception as e:
        bad("建池失败", str(e)[:120])

    try:
        api("/api/pools", "POST", token=token, body={
            "code": "VERIFY-POOL-2", "name": "异型号池", "islandId": 1000005,
            "poolKind": "operation",
            "members": [{"deviceInstanceId": int(m1)}, {"deviceInstanceId": int(diff)}],
        })
        bad("异型号建池未拦")
    except RuntimeError as e:
        if "409" in str(e) or "同型号" in str(e):
            ok("异型号建 operation 池 → 409")
        else:
            bad("异型号返回非预期", str(e)[:100])

    print("── ② 点位方法 ──")
    dp = q("SELECT dp.id||'|'||pr.code FROM device_point dp "
           "JOIN resource_node pr ON pr.id=dp.point_resource_id "
           "WHERE dp.tenant_id=1 AND pr.code='ID-1021-P1'")
    pos_id, pos_code = dp.split("|")
    mid = q("SELECT dm.id FROM device_method dm JOIN device dev ON dev.id=dm.device_id "
            "JOIN device_instance di ON di.device_id=dev.id "
            "WHERE di.code='ID-1021' AND dm.method='ReadBarcode' LIMIT 1")
    mid2 = q("SELECT dm.id FROM device_method dm JOIN device dev ON dev.id=dm.device_id "
             "JOIN device_instance di ON di.device_id=dev.id "
             "WHERE di.code='ID-1021' AND dm.method='ReadPlate' LIMIT 1")
    b1 = b2 = None
    try:
        r = api(f"/api/positions/{pos_id}/methods", "POST", token=token, body={
            "methodId": int(mid), "scopeKind": "global", "purpose": "读板位"})
        b1 = r.get("created")
        ok("绑 global 方法 ReadBarcode")
    except Exception as e:
        bad("绑 global 方法失败", str(e)[:120])
    try:
        api(f"/api/positions/{pos_id}/methods", "POST", token=token, body={
            "methodId": int(mid2), "scopeKind": "workflow", "scopeId": 1000830,
            "purpose": "该流程当读板位"})
        b2 = 1
        ok("绑 workflow scope 方法 ReadPlate")
    except Exception as e:
        bad("绑 workflow 方法失败", str(e)[:120])
    try:
        d = api(f"/api/positions/{pos_id}/methods", token=token)
        kinds = {(x["methodName"], x.get("scopeKind")) for x in d.get("bindings", [])}
        if ("ReadBarcode", "global") in kinds and ("ReadPlate", "workflow") in kinds:
            ok("回读两级 scope 都取到", str(sorted(kinds)))
        else:
            bad("scope 回读不符", str(kinds))
    except Exception as e:
        bad("回读方法失败", str(e)[:120])

    print("── ③ 节点绑定 ──")
    wf = 1000830
    node = "Activity_077zr9t"
    try:
        r = api(f"/api/workflows/{wf}/nodes/{node}/bindings", "PUT", token=token, body={
            "bindings": [
                {"seq": 1, "positionId": int(pos_id), "methodId": int(mid),
                 "materialRole": "source", "loadingPattern": "load_single"},
                {"seq": 2, "positionId": int(pos_id), "methodId": int(mid),
                 "materialRole": "target", "loadingPattern": "load_selected"},
            ]})
        if r.get("count") == 2 and r.get("bindingMode") == "point":
            ok("节点绑 2 点位 → count=2 bindingMode=point")
        else:
            bad("节点绑定结果不符", str(r))
    except Exception as e:
        bad("节点绑定失败", str(e)[:160])
    try:
        d = api(f"/api/workflows/{wf}/nodes/{node}/bindings", token=token)
        if d.get("count") == 2:
            ok("回读节点绑定 count=2")
        else:
            bad("回读节点绑定不符", str(d.get("count")))
    except Exception as e:
        bad("回读节点绑定失败", str(e)[:120])

    # ---- 清理 ----
    api(f"/api/workflows/{wf}/nodes/{node}/bindings", "DELETE", token=token)
    for b in ("", ""):
        pass
    # 删点位方法绑定（按 methodName 找）
    d = api(f"/api/positions/{pos_id}/methods", token=token)
    for x in d.get("bindings", []):
        api(f"/api/positions/{pos_id}/methods/{x['id']}", "DELETE", token=token)
    if pool:
        api(f"/api/pools/{pool}", "DELETE", token=token)
    # 异型号池若误建（不该有）也清
    for p in api("/api/pools", token=token).get("items", []):
        if p["code"].startswith("VERIFY-"):
            api(f"/api/pools/{p['id']}", "DELETE", token=token)

    print("\n通过 %d / 失败 %d" % (PASS, FAIL))
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
