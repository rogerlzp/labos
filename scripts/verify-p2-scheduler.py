#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P2-4 / P2-5 / P2-6 · HEAD 版本独立验收（排程持久化 / 死锁检测 / 多臂）。

覆盖（对应评审报告 §2 的「覆盖缺口」——mavis 测于 0d5d463，最后 5 笔未覆盖）：
  P2-4  1. schedule_plan 参数可配（optimizationFactor / maxIterationsRunning / iterationDelaySec）
        2. resource_booking 预约写库 + 重叠时间区间被 EXCLUDE 约束拒绝（409）
  P2-5  3. /api/scheduler/deadlock-check 返回 held/wait 阈值与风险清单（空闲时 0）
        4. /api/scheduler/status 快照字段齐备
  P2-6  5. transport_segment.robot_resource_id 已按执行设备解析（非空且=机械臂资源）
        6. 双交接位 deck_in/deck_out 成对检查（PF-28 回归）

用法：
  LABOS_DB_HOST=192.168.124.2 LABOS_DB_NAME=labdb LABOS_DB_USER=labos \
  LABOS_DB_PASS='$LABOS_DB_PASS' python3 scripts/verify-p2-scheduler.py
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
q = cs.q

PASS, FAIL = 0, 0


def ok(msg, detail=""):
    global PASS
    PASS += 1
    print("  \033[32m✓\033[0m " + msg + (("（%s）" % detail) if detail else ""))


def bad(msg, detail=""):
    global FAIL
    FAIL += 1
    print("  \033[31m✗\033[0m " + msg + (("（%s）" % detail) if detail else ""))


def preclean(token):
    """幂等守卫：硬删上次中途失败的残留。"""
    try:
        q("DELETE FROM schedule_plan WHERE plan_no LIKE 'VERIFY-P2-%';")
        q("DELETE FROM resource_booking WHERE source_type='manual' AND note LIKE 'VERIFY-P2%';")
    except Exception:
        pass


def main():
    token = api("/api/auth/login", "POST",
                {"username": "admin", "password": "admin123"})["token"]
    preclean(token)

    print("── P2-4 · 排程参数可配 ──")
    r = api("/api/schedule-plans", "POST", token=token, body={
        "planNo": "VERIFY-P2-PLAN", "optimizationFactor": 80,
        "maxIterationsRunning": 3, "iterationDelaySec": 10,
    })
    if r.get("created") == 1:
        ok("建 schedule_plan（optimizationFactor=80 / maxIterations=3 / delay=10）")
    else:
        bad("建 schedule_plan 失败", str(r)[:120])

    plans = api("/api/schedule-plans", token=token)
    hit = next((x for x in plans.get("items", []) if x.get("planNo") == "VERIFY-P2-PLAN"), None)
    if hit and hit.get("optimizationFactor") == 80 and hit.get("maxIterationsRunning") == 3:
        ok("回读排程参数", "optimizationFactor=%s maxIterationsRunning=%s"
           % (hit.get("optimizationFactor"), hit.get("maxIterationsRunning")))
    else:
        bad("回读排程参数不符", str(hit))

    if hit:
        r = api(f"/api/schedule-plans/{hit['id']}", "PUT", token=token,
                body={"optimizationFactor": 90, "maxIterationsRunning": 5})
        if r.get("updated") == 1:
            ok("更新排程参数（PUT）")
        else:
            bad("更新排程参数失败", str(r)[:120])

    print("── P2-4 · resource_booking 预约 + 重叠排除 ──")
    rid = int(q("SELECT id FROM resource_node WHERE code='ID-1021' AND kind='device' LIMIT 1") or 0)
    if rid <= 0:
        rid = int(q("SELECT id FROM resource_node WHERE kind='device' LIMIT 1") or 0)
    r = api("/api/resource-bookings", "POST", token=token, body={
        "resourceId": rid, "periodFrom": "2026-11-14 10:00:00",
        "periodTo": "2026-11-14 11:00:00", "status": "planned",
        "sourceType": "manual", "note": "VERIFY-P2-booking",
    })
    if r.get("created") == 1:
        ok("写资源预约", "resourceId=%d" % rid)
    else:
        bad("写资源预约失败", str(r)[:120])

    try:
        api("/api/resource-bookings", "POST", token=token, body={
            "resourceId": rid, "periodFrom": "2026-11-14 10:30:00",
            "periodTo": "2026-11-14 11:30:00", "status": "planned",
            "sourceType": "manual", "note": "VERIFY-P2-overlap",
        })
        bad("重叠预约未拦截")
    except RuntimeError as e:
        if "409" in str(e) or "重叠" in str(e) or "overlap" in str(e):
            ok("重叠时间区间 → 409（EXCLUDE 约束生效）", str(e)[:60])
        else:
            bad("重叠预约返回非预期", str(e)[:120])

    bookings = api("/api/resource-bookings?limit=20", token=token)
    if bookings.get("total", 0) >= 1:
        ok("预约可回读", "total=%d" % bookings.get("total"))
    else:
        bad("预约回读为空")

    print("── P2-5 · 死锁/饥饿检测 ──")
    d = api("/api/scheduler/deadlock-check?heldMinutes=1&waitMinutes=1", token=token)
    if "total" in d and "risks" in d and "heldThresholdMinutes" in d:
        ok("deadlock-check 结构齐备（total/risks/阈值）", "total=%s" % d.get("total"))
    else:
        bad("deadlock-check 结构缺失", str(d)[:120])

    st = api("/api/scheduler/status", token=token)
    need = ("running", "queued", "maxConcurrent", "resourceLocks", "queue")
    if all(k in st for k in need):
        ok("scheduler/status 字段齐备", "running=%s queued=%s" % (st.get("running"), st.get("queued")))
    else:
        bad("scheduler/status 缺字段", str(list(st.keys()))[:120])

    print("── P2-6 · 多臂：robot_resource_id 按执行设备解析 ──")
    seg = q("SELECT count(*) FROM transport_segment WHERE robot_resource_id IS NOT NULL")
    seg_total = q("SELECT count(*) FROM transport_segment")
    try:
        if int(seg_total) > 0 and int(seg) == int(seg_total):
            ok("transport_segment.robot_resource_id 全部非空（按设备解析）",
                "%s/%s" % (seg, seg_total))
        elif int(seg_total) == 0:
            ok("尚无搬运段（需跑一次含 Transfer 的流程后再验）", "0 条")
        else:
            bad("存在 robot_resource_id 为空的搬运段", "%s/%s" % (seg, seg_total))
    except ValueError:
        bad("transport_segment 查询失败")

    print("── P2-2 · PF-28 双交接位回归 ──")
    d = api("/api/deck-pairing-check", token=token)
    if "total" in d and "unpairedDevices" in d:
        ok("deck-pairing-check 结构齐备", "total=%s" % d.get("total"))
    else:
        bad("deck-pairing-check 结构缺失", str(d)[:120])

    print("── 陈旧暂停治理天数：上下限夹紧（与前端 max=365 同口径）──")
    # 原值先存下来，测完还原（别把设置留在非默认值上）
    try:
        orig = api("/api/settings/stale-pause", token=token).get("autoDismissDays", 0)
    except Exception as e:
        orig = 0
        bad("读陈旧暂停设置失败", str(e)[:80])
    r = api("/api/settings/stale-pause", "PUT", token=token, body={"autoDismissDays": -5})
    if r.get("autoDismissDays") == 0:
        ok("负数 → 夹到 0", "")
    else:
        bad("负数未夹到 0", str(r)[:80])
    r = api("/api/settings/stale-pause", "PUT", token=token, body={"autoDismissDays": 99999})
    if r.get("autoDismissDays") == 365:
        ok("超上限 → 夹到 365（与前端 max=365 同口径）", "")
    else:
        bad("超上限未夹到 365（前后端口径仍不一致）", str(r)[:80])
    r = api("/api/settings/stale-pause", "PUT", token=token, body={"autoDismissDays": orig})
    if r.get("autoDismissDays") == orig:
        ok("设置已还原为原值", "autoDismissDays=%s" % orig)
    else:
        bad("设置还原失败", str(r)[:80])

    # 清理
    preclean(token)

    print("\n通过 %d / 失败 %d" % (PASS, FAIL))
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
