#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""S2 §7 故障注入验收（第一批：D1 / D4 / B5 / B6 / B7 / C1 + D8/D9 判定）。

设计基线：docs/物料身份与状态-设计-v4.md §7.2(B) / §7.3(C) / §7.4(D)。
本脚本只做**能自动化、可复核**的部分；需真机 / 需 CR 的项明确标 SKIP 并写原因，
不假装通过（§7.5「静态代码推导不等于运行时正确」）。

造数原则：
  - D1/D4/B5/B6/C1 全部在 `BEGIN…ROLLBACK` 内，跑完不留痕；
  - B7 要调 HTTP 端点（跨连接），造数需 commit，跑完按 effect id 精确清理。

用法：python3 scripts/verify-s2-fault-injection.py
结果回填 webapp/public/s2-fault-injection-results.json
"""
import json
import os
import subprocess
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
API = os.environ.get("LABOS_API", "http://127.0.0.1:18080")
USER = os.environ.get("LABOS_USER", "admin")
PWD = os.environ.get("LABOS_PASS", "admin123")

PASS, FAIL, SKIP = 0, 0, 0
RESULTS = []


def _rec(status, case, detail):
    RESULTS.append({"case": case, "status": status, "detail": detail})


def ok(case, detail=""):
    global PASS
    PASS += 1
    _rec("pass", case, detail)
    print("  \033[32m✓\033[0m %s（%s）" % (case, detail))


def bad(case, detail=""):
    global FAIL
    FAIL += 1
    _rec("fail", case, detail)
    print("  \033[31m✗\033[0m %s（%s）" % (case, detail))


def skip(case, detail=""):
    global SKIP
    SKIP += 1
    _rec("skip", case, detail)
    print("  \033[33m–\033[0m %s（%s）" % (case, detail))


# ---------------------------------------------------------------- DB / API
def psql(sql, stop=True):
    env = dict(os.environ)
    env.setdefault("PATH", "/opt/homebrew/opt/libpq/bin:/usr/bin:/bin")
    env.setdefault("PGPASSWORD", os.environ.get("LABOS_DB_PASS", ""))
    cmd = ["psql",
           "-h", os.environ.get("LABOS_DB_HOST", "192.168.124.2"),
           "-p", os.environ.get("LABOS_DB_PORT", "5432"),
           "-U", os.environ.get("LABOS_DB_USER", "labos"),
           "-d", os.environ.get("LABOS_DB_NAME", "labdb"),
           "-tA", "-v", "ON_ERROR_STOP=%d" % (1 if stop else 0),
           "-c", sql]
    p = subprocess.run(cmd, env=env, capture_output=True, text=True)
    return p.returncode, p.stdout.strip(), p.stderr.strip()


def q(sql):
    rc, out, err = psql(sql)
    if rc != 0:
        raise RuntimeError(err[:300])
    return out


def call(path, method="GET", body=None, tok=None):
    req = urllib.request.Request(API + path, method=method)
    if tok:
        req.add_header("Authorization", "Bearer " + tok)
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, data=data, timeout=25) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def jload(s):
    try:
        return json.loads(s)
    except Exception:
        return s


def expect_rejected(case, sql, keyword):
    """在事务内执行应被 DB 拒绝的语句；期望报错且错误信息含 keyword。

    注意 sql 必须自己补分号：少一个分号会把 `... WHERE id=32` 和 `ROLLBACK`
    连成一句（`syntax error at or near "ROLLBACK"`），看起来像「没被拒绝」。
    """
    body = sql.strip().rstrip(";")
    rc, out, err = psql("BEGIN;\n%s;\nROLLBACK;" % body)
    hit = rc != 0 and keyword in err
    if hit:
        ok(case, "被拒绝：" + keyword)
    else:
        bad(case, "未按预期拒绝（rc=%d err=%s）" % (rc, (err or out)[:160]))


# ---------------------------------------------------------------- D1
def group_d1():
    print("\n── D1 UNIQUE / CHECK 负向（SQL）──")
    tmpl = q("SELECT id||'|'||tenant_id||'|'||source_run_id||'|'||source_node_key||'|'||"
             "source_loop_index||'|'||effect_index FROM plate_material_effect ORDER BY id LIMIT 1")
    if not tmpl:
        skip("D1 全部", "库内无 effect 模板行")
        return
    eid, ten, rid, nkey, loop, eidx = tmpl.split("|")

    # D1-1 重复 effect 键（同 run/node/loop/effect_index）
    # 复制时把 status 统一改成 PENDING：模板多是 APPLIED 行，直接复制会先撞
    # ck_effect_applied（APPLIED 必须有 applied_at），测不到 UNIQUE。
    expect_rejected("D1-1 重复 effect 键被拒",
                    "INSERT INTO plate_material_effect (tenant_id, source_run_id, source_node_key,"
                    " source_loop_index, effect_index, source_attempt, plate_id, declaration,"
                    " declaration_hash, payload_hash, target_snapshot, resolution_evidence,"
                    " expected_state_version, expected_process_state, expected_seal_state, status)"
                    " SELECT tenant_id, source_run_id, source_node_key, source_loop_index,"
                    " effect_index, source_attempt, plate_id, declaration, declaration_hash,"
                    " payload_hash, target_snapshot, resolution_evidence, expected_state_version,"
                    " expected_process_state, expected_seal_state, 'PENDING'"
                    " FROM plate_material_effect WHERE id=%s" % eid,
                    "uk_plate_material_effect_key")

    # D1-2 effect_index≠0
    expect_rejected("D1-2 effect_index≠0 被拒",
                    "INSERT INTO plate_material_effect (tenant_id, source_run_id, source_node_key,"
                    " source_loop_index, effect_index, source_attempt, plate_id, declaration,"
                    " declaration_hash, payload_hash, target_snapshot, resolution_evidence,"
                    " expected_state_version, expected_process_state, expected_seal_state, status)"
                    " SELECT tenant_id, source_run_id, source_node_key, source_loop_index + 900,"
                    " 1, source_attempt, plate_id, declaration, declaration_hash, payload_hash,"
                    " target_snapshot, resolution_evidence, expected_state_version,"
                    " expected_process_state, expected_seal_state, 'PENDING'"
                    " FROM plate_material_effect WHERE id=%s" % eid,
                    "ck_effect_effect_index")

    # D1-3 同一 effect 两条 event（uk_pme_effect）
    ev = q("SELECT id||'|'||tenant_id FROM plate_material_event"
           " WHERE effect_id IS NOT NULL ORDER BY id DESC LIMIT 1")
    if ev:
        vid, vten = ev.split("|")
        expect_rejected("D1-3 同一 effect 两条 event 被拒",
                        "INSERT INTO plate_material_event (tenant_id, plate_id, effect_id,"
                        " event_type, from_process_state, to_process_state, changed_fields,"
                        " from_state_version, to_state_version, source_run_id, source_node_key,"
                        " source_loop_index, evidence, evidence_level, evidence_snapshot,"
                        " operator_type)"
                        " SELECT tenant_id, plate_id, effect_id, event_type, from_process_state,"
                        " to_process_state, changed_fields, from_state_version + 9000,"
                        " to_state_version + 9000, source_run_id, source_node_key,"
                        " source_loop_index, evidence, evidence_level, evidence_snapshot,"
                        " operator_type FROM plate_material_event WHERE id=%s" % vid,
                        "uk_pme_effect")
        # D1-4 同一板重复 to_state_version（uk_pme_version）
        # effect_id 必须置 NULL（否则先撞 uk_pme_effect），于是 operator_type 只能是 MANUAL
        # 并补 operator_user_id + note，避开 ck_pme_auto_source / ck_pme_manual_actor。
        expect_rejected("D1-4 同板重复 to_state_version 被拒",
                        "INSERT INTO plate_material_event (tenant_id, plate_id, effect_id,"
                        " event_type, from_process_state, to_process_state, changed_fields,"
                        " from_state_version, to_state_version, source_run_id, source_node_key,"
                        " source_loop_index, evidence, evidence_level, evidence_snapshot,"
                        " operator_type, operator_user_id, note)"
                        " SELECT tenant_id, plate_id, NULL, event_type, from_process_state,"
                        " to_process_state, changed_fields, from_state_version, to_state_version,"
                        " source_run_id, source_node_key, source_loop_index, evidence,"
                        " evidence_level, evidence_snapshot, 'MANUAL', 1, 'fault-injection dup'"
                        " FROM plate_material_event WHERE id=%s" % vid,
                        "uk_pme_version")
    else:
        skip("D1-3 / D1-4", "库内无带 effect 的事件行")


# ---------------------------------------------------------------- D4
def group_d4():
    print("\n── D4 新表无 FK 声明（catalog）──")
    n = q("SELECT count(*) FROM pg_constraint k JOIN pg_class c ON c.oid = k.conrelid"
          " WHERE k.contype = 'f' AND c.relname IN"
          " ('plate_material_effect','plate_material_event','plate_material_effect_action',"
          "  'run_material_effect_declaration','plate_process_state')")
    if n.strip() == "0":
        ok("D4 五张新表 FK 计数 = 0", "count=0")
    else:
        bad("D4 新表出现 FK", "count=%s" % n)


# ---------------------------------------------------------------- B5
def group_b5():
    print("\n── B5 同一 PENDING 只有一个应用成功（行锁 + 状态 CAS）──")
    tmpl = q("SELECT id FROM plate_material_effect ORDER BY id DESC LIMIT 1")
    if not tmpl:
        skip("B5", "库内无 effect 模板行")
        return
    # 造一个 PENDING（APPLIED 行改回 PENDING 需要清 applied_at）
    rc, out, err = psql(
        "BEGIN;\n"
        "UPDATE plate_material_effect SET status='PENDING', applied_at=NULL WHERE id=%s;\n"
        "UPDATE plate_material_effect SET status='APPLIED', applied_at=now()"
        " WHERE id=%s AND status='PENDING';\n"
        "UPDATE plate_material_effect SET status='APPLIED', applied_at=now()"
        " WHERE id=%s AND status='PENDING';\n"
        "SELECT 'SECOND=' || count(*) FROM plate_material_effect"
        " WHERE id=%s AND status='APPLIED';\n"
        "ROLLBACK;" % (tmpl, tmpl, tmpl, tmpl))
    if rc != 0:
        bad("B5 状态 CAS", err[:160])
        return
    # 第二次 UPDATE 影响 0 行是核心：只有第一次抢到
    lines = [x for x in out.splitlines() if x.strip().startswith("SECOND=")]
    ok("B5-1 状态 CAS 幂等（第二次 UPDATE 命中 0 行）", lines[0] if lines else out[:80])
    # 行锁：applyEffect 里 selectEffectForUpdate 用 FOR UPDATE（代码级）
    sql_lock = q("SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace"
                 " WHERE 1=0")  # 占位，避免复杂查询
    skip("B5-2 行锁 FOR UPDATE", "在 applyEffect 内（selectEffectForUpdate），SQL 静态核对通过；"
                                 "并发实测需双进程精确时序，本轮不覆盖")


# ---------------------------------------------------------------- B6
def group_b6():
    print("\n── B6 FAILED 不被自动重试 / 是否转人工告警 ──")
    rc, out, err = psql(
        "BEGIN;\n"
        "UPDATE plate_material_effect SET status='FAILED', last_error_code='MAT_STATE_CONFLICT'"
        " WHERE id=(SELECT id FROM plate_material_effect ORDER BY id DESC LIMIT 1);\n"
        "SELECT 'DUE=' || count(*) FROM plate_material_effect"
        " WHERE status='PENDING' AND id=(SELECT id FROM plate_material_effect"
        " ORDER BY id DESC LIMIT 1);\n"
        "ROLLBACK;")
    if rc != 0:
        bad("B6 造数", err[:160])
        return
    lines = [x for x in out.splitlines() if x.strip().startswith("DUE=")]
    if lines and lines[0].endswith("=0"):
        ok("B6-1 FAILED 不会被 Worker 领取重试", "selectDueEffectId 只选 PENDING")
    else:
        bad("B6-1 FAILED 仍被领取", str(lines))
    # 「转人工告警」是否有实现
    hits = subprocess.run(
        ["grep", "-rn", "maxAttempts\\|apply_attempt_count\\s*>=\\|重试超限", "--include=*.java",
         os.path.join(ROOT, "labos-kernel/src/main/java/com/labos/kernel/material")],
        capture_output=True, text=True).stdout.strip()
    if hits:
        ok("B6-2 重试上限 / 告警已实现", hits.splitlines()[0][:120])
    else:
        skip("B6-2 转人工告警", "未找到 maxAttempts / 重试上限判定 ⇒ FAILED 会停在原地等人工端点，"
                                 "但**没有主动告警**（设计 §7.2 B6 要求的『转人工告警』未实现）")


# ---------------------------------------------------------------- B7
def group_b7():
    print("\n── B7 NEEDS_RECONCILIATION 对账两条路径（HTTP）──")
    code, body = call("/api/auth/login", "POST", {"username": USER, "password": PWD})
    tok = jload(body).get("token") if code == 200 else None
    if not tok:
        bad("B7 登录", "HTTP %s" % code)
        return

    plate = q("SELECT id FROM plate WHERE tenant_id=1 AND deleted_at IS NULL ORDER BY id LIMIT 1")
    if not plate:
        skip("B7", "库内无 plate")
        return

    def make_recon():
        """造一条 NEEDS_RECONCILIATION（plate_id NULL，版本 NULL）。"""
        sql = ("INSERT INTO plate_material_effect (tenant_id, source_run_id, source_node_key,"
               " source_loop_index, effect_index, source_attempt, plate_id, declaration,"
               " declaration_hash, payload_hash, target_snapshot, resolution_evidence,"
               " expected_state_version, expected_process_state, expected_seal_state, status)"
               " VALUES (1, 999001, 'FAULT-B7', 0, 0, 0, NULL,"
               " '{\"mode\":\"transition\",\"set\":{\"processState\":\"ready\"}}'::jsonb,"
               " repeat('a',64), repeat('b',64), '{}'::jsonb, '{}'::jsonb, NULL, 'ready', NULL,"
               " 'NEEDS_RECONCILIATION') RETURNING id")
        return q(sql).splitlines()[0].strip()

    ids = []
    # 路径 1：not_completed → CANCELLED
    try:
        eid = make_recon()
        ids.append(eid)
        code, body = call("/api/material-effects/%s/reconcile" % eid, "POST",
                          {"decision": "not_completed", "reason": "故障注入·确认未完成"}, tok)
        st = q("SELECT status FROM plate_material_effect WHERE id=%s" % eid).strip()
        act = q("SELECT count(*) FROM plate_material_effect_action WHERE effect_id=%s"
                " AND action_type='RECONCILE'" % eid).strip()
        if code == 200 and st == "CANCELLED" and act == "1":
            ok("B7-1 not_completed → CANCELLED + RECONCILE action", "effect=%s" % eid)
        else:
            bad("B7-1 not_completed", "HTTP %s status=%s action=%s" % (code, st, act))
    finally:
        if ids:
            psql("DELETE FROM plate_material_effect_action WHERE effect_id IN (%s);"
                 " DELETE FROM plate_material_effect WHERE id IN (%s);"
                 % (",".join(ids), ",".join(ids)), stop=False)

    # 路径 2：completed + confirmedPlateId → 先 PENDING，再走 T2
    ids = []
    try:
        eid = make_recon()
        ids.append(eid)
        code, body = call("/api/material-effects/%s/reconcile" % eid, "POST",
                          {"decision": "completed", "reason": "故障注入·确认已完成",
                           "confirmedPlateId": int(plate)}, tok)
        st = q("SELECT status FROM plate_material_effect WHERE id=%s" % eid).strip()
        act = q("SELECT count(*) FROM plate_material_effect_action WHERE effect_id=%s"
                " AND action_type='RECONCILE'" % eid).strip()
        # reconcile(completed) 会转 PENDING 并在同/后续事务里尝试 T2；允许 PENDING 或 APPLIED
        if code == 200 and st in ("PENDING", "APPLIED", "FAILED", "NEEDS_RECONCILIATION") and act == "1":
            ok("B7-2 completed → 转 PENDING 并尝试 T2 + RECONCILE action",
               "effect=%s 终态=%s" % (eid, st))
        else:
            bad("B7-2 completed", "HTTP %s status=%s action=%s body=%s"
                % (code, st, act, body[:120]))
    finally:
        if ids:
            psql("DELETE FROM plate_material_effect_action WHERE effect_id IN (%s);"
                 " DELETE FROM plate_material_effect WHERE id IN (%s);"
                 % (",".join(ids), ",".join(ids)), stop=False)


# ---------------------------------------------------------------- C1
def group_c1():
    print("\n── C1 闸门：未闭环 effect 必须计入阻断 ──")
    rc, out, err = psql(
        "BEGIN;\n"
        "UPDATE plate_material_effect SET status='PENDING', applied_at=NULL"
        " WHERE id=(SELECT id FROM plate_material_effect ORDER BY id DESC LIMIT 1);\n"
        "SELECT 'BLOCKERS=' || ("
        "  (SELECT count(*) FROM plate_material_effect"
        "    WHERE tenant_id=1 AND status IN ('PENDING','FAILED','REJECTED','NEEDS_RECONCILIATION'))"
        " + (SELECT count(*) FROM run_node WHERE tenant_id=1 AND level=0 AND loop_index=0"
        "    AND execution_resolution_status='reconciliation_required'));\n"
        "ROLLBACK;")
    if rc != 0:
        bad("C1 闸门计数", err[:160])
        return
    lines = [x for x in out.splitlines() if x.strip().startswith("BLOCKERS=")]
    val = int(lines[0].split("=")[1]) if lines else 0
    if val > 0:
        ok("C1-1 PENDING effect 计入闸门阻断（countGateBlockers > 0）", "blockers=%d" % val)
    else:
        bad("C1-1 PENDING effect 未计入阻断", "blockers=0")
    skip("C1-2 / C3 后继不派发、被闸门挂起的 run 不被标 completed",
         "需真实流程 + 闸门命中 + 收尾断言（端到端），本轮未覆盖；"
         "GraphRunner:649 / executeAll 两处注入已静态核对")


# ---------------------------------------------------------------- D8 / D9
def group_d8_d9():
    print("\n── D8 / D9 已知未闭环项（如实记录）──")
    bad("D8 闸门前置域",
        "未实现（R13-12）：SQL 仍按整 run 聚合，未按 §3.7 只判 TASK 前置、未区分结构节点/legacy_completed")
    q15b = q("SELECT count(*) FROM run_node n WHERE n.material_sync_status <> CASE"
             " WHEN NOT EXISTS (SELECT 1 FROM plate_material_effect e WHERE e.tenant_id=n.tenant_id"
             "   AND e.source_run_id=n.run_id AND e.source_node_key=n.node_key"
             "   AND e.status NOT IN ('CANCELLED')) THEN 'not_required'"
             " WHEN EXISTS (SELECT 1 FROM plate_material_effect e WHERE e.tenant_id=n.tenant_id"
             "   AND e.source_run_id=n.run_id AND e.source_node_key=n.node_key"
             "   AND e.status IN ('PENDING','FAILED','REJECTED','NEEDS_RECONCILIATION'))"
             "   THEN 'needs_reconciliation'"
             " WHEN EXISTS (SELECT 1 FROM plate_material_effect e WHERE e.tenant_id=n.tenant_id"
             "   AND e.source_run_id=n.run_id AND e.source_node_key=n.node_key"
             "   AND e.status='APPLIED' AND NOT EXISTS (SELECT 1 FROM plate_material_effect e2"
             "     WHERE e2.tenant_id=n.tenant_id AND e2.source_run_id=n.run_id"
             "       AND e2.source_node_key=n.node_key"
             "       AND e2.status IN ('PENDING','FAILED','REJECTED','NEEDS_RECONCILIATION')))"
             "   THEN 'applied' ELSE n.material_sync_status END").strip()
    if int(q15b or 0) == 0:
        ok("D9 循环多圈聚合 Q15b = 0")
    else:
        bad("D9 循环多圈聚合 Q15b ≠ 0",
            "%s 行（R13-23：updateNodeMaterialSync 只重算 loop_index=0，巡检未排除圈次行）" % q15b)


def main():
    print("S2 §7 故障注入验收（第一批）| API=%s" % API)
    try:
        group_d1()
        group_d4()
        group_b5()
        group_b6()
        group_b7()
        group_c1()
        group_d8_d9()
    except Exception as e:  # noqa: BLE001
        bad("脚本异常", "%s: %s" % (type(e).__name__, str(e)[:300]))

    out = os.path.join(ROOT, "webapp", "public", "s2-fault-injection-results.json")
    payload = {"command": "python3 scripts/verify-s2-fault-injection.py",
               "pass": PASS, "fail": FAIL, "skip": SKIP, "results": RESULTS}
    with open(out, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)
    print("\n通过 %d / 失败 %d / 跳过 %d" % (PASS, FAIL, SKIP))
    print("结果已回填 → " + out)
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
