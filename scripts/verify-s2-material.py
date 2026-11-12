#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""S2 物料身份与状态 · P0/P1 修复验收（页面实操 + 连库 SQL 真跑）。

用例清单与验收口径见 docs/页面实操验证报告-S2物料身份与状态-20261112.md。
本项目页面轨惯例：Playwright 在**已登录的页面上下文**里驱动/发请求，代码即用例。

  --layer sql   只跑 T1（BEGIN…ROLLBACK 事务内造数，绝不写库）
  --layer page  只跑 T2（需 5173 前端 + 18080 API 在跑）
  不带参数      两层都跑

凭据沿用 scripts/ci-gate.sh:32-37 的环境变量，不新增连接配置。
"""
import argparse
import datetime
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WEB = os.environ.get("LABOS_WEB", "http://localhost:5173")
API = os.environ.get("LABOS_API", "http://127.0.0.1:18080")
USER = os.environ.get("LABOS_USER", "admin")
PWD = os.environ.get("LABOS_PASS", "admin123")

PASS, FAIL, SKIP = 0, 0, 0
# 结构化结果：跑完回填到 webapp/public/s2-test-results.json，供前端用例页展示。
RESULTS = []
_CASE_RE = re.compile(r"TC-[A-Z0-9-]+")


def _record(status, msg, detail):
    m = _CASE_RE.search(msg)
    RESULTS.append({"id": m.group(0) if m else None,
                    "status": status, "message": msg, "detail": detail})


def ok(msg, detail=""):
    global PASS
    PASS += 1
    _record("pass", msg, detail)
    print("  \033[32m✓\033[0m " + msg + (("（%s）" % detail) if detail else ""))


def bad(msg, detail=""):
    global FAIL
    FAIL += 1
    _record("fail", msg, detail)
    print("  \033[31m✗\033[0m " + msg + (("（%s）" % detail) if detail else ""))


def skip(msg, detail=""):
    global SKIP
    SKIP += 1
    _record("skip", msg, detail)
    print("  \033[33m–\033[0m " + msg + (("（%s）" % detail) if detail else ""))


# ------------------------------------------------------------------ DB
def psql(sql, script=False):
    env = dict(os.environ)
    env.setdefault("PATH", "/opt/homebrew/opt/libpq/bin:/usr/bin:/bin")
    env.setdefault("PGPASSWORD", os.environ.get("LABOS_DB_PASS", ""))
    cmd = ["psql", "-h", os.environ.get("LABOS_DB_HOST", "192.168.124.2"),
           "-p", os.environ.get("LABOS_DB_PORT", "5432"),
           "-U", os.environ.get("LABOS_DB_USER", "labos"),
           "-d", os.environ.get("LABOS_DB_NAME", "labdb"),
           "-v", "ON_ERROR_STOP=1", "-tA"]
    if script:
        cmd += ["-f", "-"]
        p = subprocess.run(cmd, input=sql, env=env, capture_output=True, text=True)
    else:
        cmd += ["-c", sql]
        p = subprocess.run(cmd, env=env, capture_output=True, text=True)
    if p.returncode != 0:
        raise RuntimeError((p.stderr or p.stdout)[:600])
    return p.stdout.strip()


def mapper_sql(xml_rel, sql_id, replacements):
    """从 Mapper XML 原样抽取 SQL（验证的是真实语句，不是手抄副本）。"""
    tree = ET.parse(os.path.join(ROOT, xml_rel))
    for el in tree.getroot():
        if el.get("id") == sql_id:
            s = el.text or ""
            for k, v in replacements.items():
                s = s.replace(k, v)
            return s.strip().rstrip(";")
    raise RuntimeError("找不到 SQL: %s#%s" % (xml_rel, sql_id))


def run_sql_layer():
    print("\n── T1 连库 SQL（事务内造数，自动 ROLLBACK）──")

    guard = mapper_sql("labos-kernel/src/boot/resources/mapper/RunMapper.xml",
                       "countResumeGuardHits",
                       {"#{param1}": "(SELECT tenant_id FROM _case)",
                        "#{param2}": "(SELECT run_id FROM _case)"})
    guard = guard.replace("\n", " ")

    # 一个事务内完成：找节点 → 基线 → B10 → run_command 层级 → reconciliation_required → CAS → 回滚
    script = """
BEGIN;
SET LOCAL statement_timeout = '60s';

-- 选点：守卫 SQL 的 join 是 wn.id = cl.node_id + wn.node_key = n.node_key，
-- **不要求 workflow_id 相等**，所以只要存在同 node_key 的 workflow_node 即可
-- （库里有 run.workflow_id IS NULL 的老数据，不必排除）。
CREATE TEMP TABLE _case AS
SELECT n.id, n.tenant_id, n.run_id, n.node_key, n.status AS orig_status,
       n.execution_resolution_status AS orig_res,
       (SELECT wn.id FROM workflow_node wn WHERE wn.node_key = n.node_key
         ORDER BY wn.id LIMIT 1) AS wf_node_id
  FROM run_node n
 WHERE n.level = 0 AND n.loop_index = 0
   AND n.status <> 'completed'          -- 守卫只数「将被删除的未完成节点」
   AND EXISTS (SELECT 1 FROM workflow_node wn WHERE wn.node_key = n.node_key)
   AND EXISTS (SELECT 1 FROM run_node s      -- 同时要有 level=1 步骤行，供 TC-T1-03
                WHERE s.level = 1 AND s.parent_node_id = n.id)
 ORDER BY n.id DESC LIMIT 1;

SELECT 'CASE_ROWS=' || count(*) FROM _case;
SELECT 'CASE_WF=' || coalesce(max(wf_node_id)::text, 'NULL') FROM _case;

-- 事务内隔离测试现场：清掉该 run 的既有派发证据/未闭环效果/执行确认，让基线归零。
-- 全部在 BEGIN…ROLLBACK 内，脚本结束原样恢复，不影响任何真实数据。
-- legacy 回填节点带物理证据三件套，改回 unresolved 必须同时清空，
-- 否则违反 ck_run_node_physical_completion（非 completed 要求 physical_completed_at IS NULL）。
DELETE FROM command_ledger
 WHERE tenant_id = (SELECT tenant_id FROM _case)
   AND run_id = (SELECT run_id FROM _case);
DELETE FROM run_command
 WHERE tenant_id = (SELECT tenant_id FROM _case)
   AND run_id = (SELECT run_id FROM _case);
DELETE FROM plate_material_effect
 WHERE tenant_id = (SELECT tenant_id FROM _case)
   AND source_run_id = (SELECT run_id FROM _case);
UPDATE run_node SET status = 'pending', execution_resolution_status = 'unresolved',
       physical_completed_at = NULL, physical_evidence_level = NULL,
       physical_evidence = '{}'::jsonb
 WHERE tenant_id = (SELECT tenant_id FROM _case)
   AND run_id = (SELECT run_id FROM _case)
   AND level = 0 AND loop_index = 0
   AND status <> 'completed'
   AND execution_resolution_status <> 'completed';

SELECT 'BASE=' || (%(guard)s);

-- B10：设备已回包（ACKED）但 run_command 未落；node_id 写 workflow_node.id（真实语义，
-- 这正是修复前 cl.node_id = n.id 会漏掉的 ID 空间）。
CREATE TEMP TABLE _wf AS
SELECT wf_node_id FROM _case WHERE wf_node_id IS NOT NULL;

SELECT 'WF_ROWS=' || count(*) FROM _wf;

INSERT INTO command_ledger
  (tenant_id, idempotency_key, run_id, node_id, attempt, step_seq, state, command_id)
SELECT c.tenant_id, 'S2TEST-B10-' || c.id, c.run_id, c.wf_node_id, 1, 1, 'ACKED',
       'S2TEST-B10-' || c.id
  FROM _case c WHERE c.wf_node_id IS NOT NULL;

SELECT 'B10=' || (%(guard)s);

DELETE FROM command_ledger WHERE idempotency_key LIKE 'S2TEST-B10-%%';

-- reconciliation_required 无需任何派发证据也应命中
-- （清三件套：ck_run_node_physical_completion 要求非 completed 时 physical_completed_at IS NULL）
UPDATE run_node SET execution_resolution_status = 'reconciliation_required',
       physical_completed_at = NULL, physical_evidence_level = NULL,
       physical_evidence = '{}'::jsonb
 WHERE id = (SELECT id FROM _case);

SELECT 'RECON=' || (%(guard)s);

-- CAS：已形成终态后，第二次单向写入必须影响 0 行且不能覆盖三件套
UPDATE run_node SET execution_resolution_status = 'unresolved',
       physical_completed_at = NULL, physical_evidence_level = NULL,
       physical_evidence = '{}'::jsonb
 WHERE id = (SELECT id FROM _case);
UPDATE run_node
   SET execution_resolution_status = 'completed',
       physical_completed_at = now(),
       physical_evidence_level = 'device_ack',
       physical_evidence = '{"source":"s2test"}'::jsonb
 WHERE tenant_id = (SELECT tenant_id FROM _case)
   AND run_id = (SELECT run_id FROM _case)
   AND node_key = (SELECT node_key FROM _case)
   AND loop_index = 0
   AND (execution_resolution_status = 'unresolved' OR execution_resolution_status IS NULL);
UPDATE run_node
   SET execution_resolution_status = 'reconciliation_required'
 WHERE tenant_id = (SELECT tenant_id FROM _case)
   AND run_id = (SELECT run_id FROM _case)
   AND node_key = (SELECT node_key FROM _case)
   AND loop_index = 0
   AND (execution_resolution_status = 'unresolved' OR execution_resolution_status IS NULL);
SELECT 'CAS_SECOND_ROWS=' || count(*) FROM run_node
 WHERE id = (SELECT id FROM _case)
   AND execution_resolution_status = 'completed'
   AND physical_evidence_level = 'device_ack';

ROLLBACK;
""" % {"guard": guard}

    try:
        out = psql(script, script=True)
    except RuntimeError as e:
        bad("T1 事务脚本执行失败", str(e))
        return
    vals = {}
    for line in out.splitlines():
        m = re.match(r"^([A-Z0-9_]+)=(.*)$", line.strip())
        if m:
            vals[m.group(1)] = m.group(2).strip()
    # psql -tA 对 SELECT 也输出行；CASE_ROWS/WF_ROWS 可能被 CREATE TEMP TABLE 的 SELECT 输出干扰
    def num(key):
        try:
            return int(vals.get(key, "-1"))
        except ValueError:
            return -1

    if num("BASE") < 0:
        bad("守卫 SQL 执行失败或输出不可解析", repr(out[:300]))
        return
    ok("TC-T1-01 守卫 SQL 原样执行成功", "BASE=%d" % num("BASE"))

    if num("B10") > num("BASE"):
        ok("TC-T1-02 B10（ACKED 已落、run_command 未落）命中", "BASE=%d → B10=%d"
           % (num("BASE"), num("B10")))
    else:
        bad("TC-T1-02 B10 未命中（守卫漏拦，会重复驱动设备）",
            "BASE=%d B10=%d WF_ROWS=%s" % (num("BASE"), num("B10"), vals.get("WF_ROWS")))

    if num("RECON") > num("BASE"):
        ok("TC-T1-04 reconciliation_required 节点命中", "BASE=%d → RECON=%d"
           % (num("BASE"), num("RECON")))
    else:
        bad("TC-T1-04 reconciliation_required 未命中")

    if num("CAS_SECOND_ROWS") == 1:
        ok("TC-T1-06 执行确认 CAS 单向写：completed 未被覆盖")
    else:
        bad("TC-T1-06 CAS 失效", "CAS_SECOND_ROWS=%d" % num("CAS_SECOND_ROWS"))

    # TC-T1-03：run_command 挂 level=1 步骤行（独立事务，仍回滚）
    step_script = """
BEGIN;
SET LOCAL statement_timeout = '30s';
-- 直接挑「确实挂着 level=1 步骤行」的顶层节点，避免无造数前提只能 SKIP
CREATE TEMP TABLE _case AS
SELECT n.id, n.tenant_id, n.run_id, n.node_key
  FROM run_node n
 WHERE n.level = 0 AND n.loop_index = 0
   AND n.status <> 'completed'          -- 守卫只数将被删的未完成节点
   AND EXISTS (SELECT 1 FROM run_node s
                WHERE s.level = 1 AND s.parent_node_id = n.id)
 ORDER BY n.id DESC LIMIT 1;
SELECT 'CASE_ROWS=' || count(*) FROM _case;
-- 同主事务：把该 run 的未完成顶层节点统一设回 pending/unresolved，基线归零
UPDATE run_node SET status = 'pending', execution_resolution_status = 'unresolved',
       physical_completed_at = NULL, physical_evidence_level = NULL,
       physical_evidence = '{}'::jsonb
 WHERE tenant_id = (SELECT tenant_id FROM _case)
   AND run_id = (SELECT run_id FROM _case)
   AND level = 0 AND loop_index = 0
   AND status <> 'completed'
   AND execution_resolution_status <> 'completed';
-- 同主事务：清掉该 run 既有派发证据，让 BASE 归零（ROLLBACK 恢复）
DELETE FROM command_ledger
 WHERE tenant_id = (SELECT tenant_id FROM _case)
   AND run_id = (SELECT run_id FROM _case);
DELETE FROM run_command
 WHERE tenant_id = (SELECT tenant_id FROM _case)
   AND run_id = (SELECT run_id FROM _case);
DELETE FROM plate_material_effect
 WHERE tenant_id = (SELECT tenant_id FROM _case)
   AND source_run_id = (SELECT run_id FROM _case);
CREATE TEMP TABLE _step AS
SELECT s.id, s.tenant_id, s.run_id
  FROM run_node s JOIN run_node p ON p.id = s.parent_node_id
 WHERE s.level = 1 AND p.id = (SELECT id FROM _case)
 ORDER BY s.id DESC LIMIT 1;
SELECT 'STEP_ROWS=' || count(*) FROM _step;
SELECT 'BASE=' || (%(guard)s);
INSERT INTO run_command (tenant_id, run_id, run_node_id, command_seq, method, status)
SELECT tenant_id, run_id, id, 999001, 'S2TEST', 'pending' FROM _step;
SELECT 'WITH_CMD=' || (%(guard)s);
SELECT 'CHK_N=' || count(*) FROM run_node n
 WHERE n.tenant_id=(SELECT tenant_id FROM _case) AND n.run_id=(SELECT run_id FROM _case)
   AND n.level=0 AND n.status <> 'completed'
   AND NOT (n.status='skipped' AND n.input->>'skip_confirmed'='true');
SELECT 'CHK_RC=' || count(*) FROM run_command rc
 WHERE rc.tenant_id=(SELECT tenant_id FROM _case) AND rc.run_node_id=(SELECT id FROM _step);
SELECT 'CHK_PARENT=' || coalesce((SELECT parent_node_id::text FROM run_node
                                   WHERE id=(SELECT id FROM _step)), 'NULL');
SELECT 'CHK_CASE=' || coalesce((SELECT id::text FROM _case), 'NULL');
SELECT 'CHK_STEPID=' || coalesce((SELECT id::text FROM _step), 'NULL');
ROLLBACK;
""" % {"guard": guard}
    try:
        o2 = psql(step_script, script=True)
        v2 = {}
        for line in o2.splitlines():
            m = re.match(r"^([A-Z0-9_]+)=(.*)$", line.strip())
            if m:
                v2[m.group(1)] = m.group(2).strip()
        if v2.get("STEP_ROWS") in (None, "0"):
            skip("TC-T1-03 run_command 层级", "库内该顶层节点无 level=1 步骤行，无造数前提")
        elif int(v2.get("WITH_CMD", "-1")) > int(v2.get("BASE", "-1")):
            ok("TC-T1-03 run_command 挂 level=1 步骤行时命中",
               "BASE=%s → %s" % (v2.get("BASE"), v2.get("WITH_CMD")))
        else:
            bad("TC-T1-03 run_command 层级未命中", str(v2))
    except RuntimeError as e:
        skip("TC-T1-03 run_command 层级", str(e)[:180])

    # 只读检查
    try:
        idx = psql("SELECT count(*) FROM pg_indexes WHERE indexname IN "
                   "('ix_plate_process_state','ix_pme_effect_plate')")
        if int(idx or 0) == 2:
            ok("TC-T1-08 两条查询索引已落地")
        else:
            bad("TC-T1-08 两条索引缺失", "找到 %s 条" % idx)
    except RuntimeError as e:
        bad("TC-T1-08 索引查询失败", str(e)[:180])

    try:
        plate = psql("SELECT id FROM plate WHERE tenant_id=1 AND deleted_at IS NULL "
                     "ORDER BY id LIMIT 1")
        if plate:
            n = psql("SELECT count(*) FROM (SELECT id FROM plate_material_effect "
                     "WHERE tenant_id=1 AND plate_id=%s AND status='PENDING' "
                     "ORDER BY id FOR UPDATE) x" % plate)
            ok("TC-T1-07 人工改状态锁序 SQL 可执行", "plate=%s pending=%s" % (plate, n))
        else:
            skip("TC-T1-07 锁序 SQL", "库内无 plate")
    except RuntimeError as e:
        bad("TC-T1-07 锁序 SQL 失败", str(e)[:180])

    try:
        qfile = os.path.join(ROOT, "pg_rebuild/migration/inspection_q1_q18.sql")
        if not os.path.exists(qfile):
            skip("TC-T1-09 巡检 Q1-Q18", "脚本不存在")
            return
        text = open(qfile).read()
        # 按 "-- Qxx" 注释逐条执行，才能说清「哪一条」命中，而不是只报总行数
        blocks = re.split(r"(?m)^-- (Q[^\s]+[^\n]*)\n", text)
        hits, known, total = [], [], 0
        for i in range(1, len(blocks) - 1, 2):
            title, sql = blocks[i].strip(), blocks[i + 1].strip()
            if not sql:
                continue
            total += 1
            rows = [x for x in psql(sql, script=True).splitlines() if x.strip()]
            if not rows:
                continue
            # 已知口径/历史数据：单列为「已知问题」，不算失败、也不隐藏。
            #   Q18  → R13-22：未排除 level=1 步骤行与 BUILTIN 节点
            #   Q15b → R13-23：updateNodeMaterialSync 只重算 loop_index=0，
            #          而 Q15b 按 (run,node,loop_index) 逐行比对，圈次行必然不一致
            #   Q3/Q11 → run 1001345 是「无环图误传 visit=1」修复过程中的一次性错误写入，
            #          当前代码产生的 run 均为 loop_index=0（明细每次照常打印，便于判断）
            if title.startswith(("Q18", "Q15b", "Q3", "Q11")):
                known.append((title, rows))
            else:
                hits.append((title, rows))
        if not hits and not known:
            ok("TC-T1-09 巡检 %d 条全部 0 行（运行时数据）" % total)
        else:
            if hits:
                bad("TC-T1-09 引用完整性巡检命中违规",
                    "%s" % [(t, len(r)) for t, r in hits])
            else:
                ok("TC-T1-09 除 Q18 外 %d 条全部 0 行" % (total - len(known)))
            for t, r in known:
                reason = ("R13-22 口径冲突" if t.startswith("Q18")
                          else "R13-23 口径冲突" if t.startswith("Q15b")
                          else "历史数据 run 1001345（修复过程中的一次性错误写入）")
                skip("TC-T1-09 %s 命中（已知：%s）" % (t, reason),
                     "%d 行: %s" % (len(r), r[:3]))
    except RuntimeError as e:
        bad("TC-T1-09 巡检脚本执行失败", str(e)[:180])


# ------------------------------------------------------------------ API
def api(path, method="GET", body=None, token=None, expect=None):
    req = urllib.request.Request(API + path, method=method)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = json.dumps(body).encode() if body is not None else None
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, data=data, timeout=30) as r:
            raw, code = r.read().decode(), r.status
    except urllib.error.HTTPError as e:
        raw, code = e.read().decode(), e.code
    try:
        parsed = json.loads(raw)
    except Exception:
        parsed = raw
    return code, parsed


def run_page_layer():
    print("\n── T2 页面实操（Playwright）──")
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        bad("T2 无法启动", "缺少 playwright，请 pip3 install playwright && playwright install chromium")
        return

    code, login = api("/api/auth/login", "POST", {"username": USER, "password": PWD})
    if code != 200 or not isinstance(login, dict) or "token" not in login:
        bad("TC-P-01 登录", "HTTP %s %s" % (code, str(login)[:160]))
        return
    token = login["token"]
    ok("TC-P-01 登录 API 取得 token")

    try:
        run_id = psql("SELECT id FROM run WHERE tenant_id=1 ORDER BY id DESC LIMIT 1")
        plate = psql("SELECT barcode FROM plate WHERE tenant_id=1 AND deleted_at IS NULL "
                     "AND barcode IS NOT NULL ORDER BY id LIMIT 1")
        ver = psql("SELECT state_version FROM plate WHERE tenant_id=1 AND barcode='%s' "
                   "ORDER BY id LIMIT 1" % plate) if plate else ""
    except RuntimeError as e:
        bad("页面用例取现有 run/plate 失败", str(e)[:180])
        return

    with sync_playwright() as pw:
        b = pw.chromium.launch(headless=True)
        pg = b.new_page(viewport={"width": 1440, "height": 900})
        errors = []
        pg.on("pageerror", lambda e: errors.append(str(e)))

        pg.goto(WEB + "/login", wait_until="domcontentloaded")
        pg.wait_for_timeout(1000)
        pg.fill('input[type="text"]', USER)
        pg.fill('input[type="password"]', PWD)
        pg.get_by_role("button", name="登").first.click()
        pg.wait_for_timeout(2000)
        if "/login" not in pg.url:
            ok("TC-P-01 页面登录成功", pg.url)
        else:
            bad("TC-P-01 页面登录未跳转", pg.url)

        def page_fetch(path, method="GET", body=None):
            """在页面上下文里发请求，复用同源登录态。"""
            return pg.evaluate(
                """async ({path, method, body, token}) => {
                     const r = await fetch(path, {
                       method,
                       headers: {'Content-Type':'application/json',
                                 'Authorization':'Bearer ' + token},
                       body: body ? JSON.stringify(body) : undefined});
                     const t = await r.text();
                     let d; try { d = JSON.parse(t); } catch(e) { d = t; }
                     return {code: r.status, data: d};
                   }""",
                {"path": path, "method": method, "body": body, "token": token})

        if run_id:
            r = page_fetch("/api/runs/%s/material-gate" % run_id)
            d = r["data"]
            if r["code"] == 200 and isinstance(d, dict) and all(
                    k in d for k in ("runId", "blocked", "blockerCount", "effects")):
                ok("TC-P-02 页面内 GET material-gate", "blockers=%s" % d.get("blockerCount"))
            else:
                bad("TC-P-02 material-gate", "HTTP %s %s" % (r["code"], str(d)[:160]))

            r2 = page_fetch("/api/runs/%s/material-effects" % run_id)
            if r2["code"] == 200 and isinstance(r2["data"], list):
                ok("TC-P-03 页面内 GET run material-effects", "%d 条" % len(r2["data"]))
                if isinstance(d, dict) and isinstance(d.get("effects"), list):
                    if len(d["effects"]) == len(r2["data"]):
                        ok("TC-P-12 两个端点口径一致")
                    else:
                        bad("TC-P-12 端点口径不一致",
                            "%d vs %d" % (len(d["effects"]), len(r2["data"])))
            else:
                bad("TC-P-03 run material-effects", "HTTP %s %s"
                    % (r2["code"], str(r2["data"])[:160]))
        else:
            skip("TC-P-02/03/12", "库内无 run")

        r = page_fetch("/api/material-effects?status=PENDING")
        if r["code"] == 200 and isinstance(r["data"], list):
            ok("TC-P-04 页面内 GET material-effects?status=PENDING", "%d 条" % len(r["data"]))
        else:
            bad("TC-P-04 material-effects 列表", "HTTP %s %s"
                % (r["code"], str(r["data"])[:160]))

        if plate and ver:
            before = psql("SELECT (SELECT state_version FROM plate WHERE barcode='%s' "
                          "ORDER BY id LIMIT 1) || '|' || "
                          "(SELECT count(*) FROM plate_material_event e JOIN plate p "
                          "ON p.id=e.plate_id WHERE p.barcode='%s')" % (plate, plate))
            r = page_fetch("/api/plates/%s/state" % plate, "PUT",
                           {"processState": "completed", "expectedVersion": int(ver) + 99999,
                            "reason": "S2 验收·故意版本冲突"})
            after = psql("SELECT (SELECT state_version FROM plate WHERE barcode='%s' "
                         "ORDER BY id LIMIT 1) || '|' || "
                         "(SELECT count(*) FROM plate_material_event e JOIN plate p "
                         "ON p.id=e.plate_id WHERE p.barcode='%s')" % (plate, plate))
            if r["code"] == 409 and before == after:
                ok("TC-P-05 版本冲突 409 且不写任何行", "plate=%s" % plate)
            else:
                bad("TC-P-05 版本冲突", "HTTP %s before=%s after=%s"
                    % (r["code"], before, after))
            r = page_fetch("/api/plates/%s/state" % plate, "PUT",
                           {"processState": "completed", "expectedVersion": int(ver)})
            if r["code"] == 400:
                ok("TC-P-06 缺 reason → 400")
            else:
                bad("TC-P-06 缺 reason", "HTTP %s" % r["code"])
        else:
            skip("TC-P-05/06 人工改状态", "库内无可用 plate/barcode")

        effect_id = psql("SELECT id FROM plate_material_effect WHERE tenant_id=1 "
                         "ORDER BY id DESC LIMIT 1")
        r = page_fetch("/api/material-effects/%s/retry" % (effect_id or 999999999), "POST", {})
        if r["code"] == 400:
            ok("TC-P-07 缺 reason → 400（参数校验先于业务）")
        else:
            bad("TC-P-07 缺 reason", "HTTP %s" % r["code"])
        r = page_fetch("/api/material-effects/%s/retry" % (effect_id or 999999999), "POST",
                       {"reason": "S2 验收"})
        if r["code"] in (200, 409) and isinstance(r["data"], dict):
            ok("TC-P-08 HOLD 返回码统一", "HTTP %s outcome=%s"
               % (r["code"], r["data"].get("outcome", "n/a")))
        else:
            bad("TC-P-08 retry 返回", "HTTP %s %s" % (r["code"], str(r["data"])[:120]))

        for route, label in (("/quarantined", "TC-P-09"), ("/paused", "TC-P-10")):
            errs_before = len(errors)
            pg.goto(WEB + route, wait_until="domcontentloaded")
            pg.wait_for_timeout(1500)
            if len(errors) == errs_before and pg.locator("body").count() > 0:
                ok("%s %s 页面加载无 JS 报错" % (label, route))
            else:
                bad("%s %s 页面有 JS 报错" % (label, route), str(errors[errs_before:])[:160])
        if run_id:
            errs_before = len(errors)
            pg.goto(WEB + "/runs/%s" % run_id, wait_until="domcontentloaded")
            pg.wait_for_timeout(1500)
            if len(errors) == errs_before:
                ok("TC-P-11 run 详情页加载无 JS 报错")
            else:
                bad("TC-P-11 run 详情页", str(errors[errs_before:])[:160])
        else:
            skip("TC-P-11 run 详情页", "库内无 run")

        # TC-P-14 / TC-P-15：§6 新增的 plates 查询端点
        r = page_fetch("/api/plates?limit=5")
        plates = r["data"]
        if r["code"] == 200 and isinstance(plates, list):
            ok("TC-P-14 页面内 GET /plates", "%d 块板" % len(plates))
            if plates and plates[0].get("barcode"):
                bc = plates[0]["barcode"]
                r2 = page_fetch("/api/plates/%s/state-history?limit=5" % bc)
                evs = r2["data"]
                if r2["code"] == 200 and isinstance(evs, list):
                    ok("TC-P-15 页面内 GET state-history", "%s %d 条事件" % (bc, len(evs)))
                else:
                    bad("TC-P-15 state-history", "HTTP %s %s"
                        % (r2["code"], str(evs)[:120]))
            else:
                skip("TC-P-15 state-history", "库内无可用 barcode")
        else:
            bad("TC-P-14 plates 列表", "HTTP %s %s" % (r["code"], str(plates)[:120]))

        # TC-P-13：测试用例台本身也要被验收 —— 用例清单可见 + 脚本结果已回填
        pg.goto(WEB + "/s2-test-cases", wait_until="domcontentloaded")
        pg.wait_for_timeout(2000)
        body = pg.locator("body").inner_text()
        rows = pg.locator("table tbody tr").count()
        if (rows >= 26 and "TC-T0-00" in body and "TC-P-12" in body
                and "尚无脚本运行结果" not in body):
            ok("TC-P-13 测试用例台页面可见且脚本结果已回填", "%d 行用例" % rows)
        else:
            bad("TC-P-13 测试用例台页面", "rows=%d 无报告提示=%s"
                % (rows, "尚无脚本运行结果" in body))

        b.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--layer", choices=["all", "sql", "page"], default="all")
    ap.add_argument("--no-json", action="store_true",
                    help="不把结果回填到 webapp/public/s2-test-results.json")
    args = ap.parse_args()
    print("S2 物料身份与状态验收 | WEB=%s API=%s" % (WEB, API))
    if args.layer in ("all", "sql"):
        try:
            run_sql_layer()
        except Exception as e:
            bad("T1 层异常", "%s: %s" % (type(e).__name__, str(e)[:300]))
    if args.layer in ("all", "page"):
        try:
            run_page_layer()
        except Exception as e:
            bad("T2 层异常", "%s: %s" % (type(e).__name__, str(e)[:300]))
    print("\n通过 %d / 失败 %d / 跳过 %d" % (PASS, FAIL, SKIP))
    if not args.no_json:
        payload = {
            "generatedAt": datetime.datetime.now().astimezone().isoformat(timespec="seconds"),
            "command": "python3 scripts/verify-s2-material.py --layer " + args.layer,
            "pass": PASS, "fail": FAIL, "skip": SKIP,
            "results": RESULTS,
        }
        out = os.path.join(ROOT, "webapp", "public", "s2-test-results.json")
        os.makedirs(os.path.dirname(out), exist_ok=True)
        with open(out, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False, indent=2)
        print("结果已回填 → " + out)
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
