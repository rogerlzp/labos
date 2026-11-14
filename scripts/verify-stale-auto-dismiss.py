#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""陈旧暂停自动 dismiss（P2 治理）· 生效性验收。

口径：autoDismissDays>0 时，暂停超过该天数前的 waiting_human run 被自动 dismiss
（aborted + EC-HUMAN-DISMISS）；autoDismissDays=0 只告警不动。

用例：
  ① 造一条 paused_at=2 天前的 waiting_human run；
  ② 设 autoDismissDays=1（PUT /api/settings/stale-pause）；
  ③ 触发 POST /api/scheduler/stale-check（手动，绕过 60s 周期）；
  ④ 断言：该 run → aborted + EC-HUMAN-DISMISS；
  ⑤ 还原 autoDismissDays=0 + 清理。

用法：
  LABOS_DB_HOST=... LABOS_DB_NAME=... LABOS_DB_USER=... LABOS_DB_PASS=... \
  python3 scripts/verify-stale-auto-dismiss.py
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


def main():
    token = api("/api/auth/login", "POST",
                {"username": "admin", "password": "admin123"})["token"]

    # 幂等守卫：清残留 + 还原设置（按 run_no 清，因为 dismiss 会把 pause_reason 置 NULL）
    q("DELETE FROM run WHERE run_no LIKE 'VERIFY-STALE-%';")
    api("/api/settings/stale-pause", "PUT", token=token, body={"autoDismissDays": 0})

    # 造一条 2 天前的 waiting_human run（run 表，workflow 类型）；
    # 注意 dismissRunsConditional 按 updated_at 过滤（stalePausedRuns 按 paused_at），两个都要设旧。
    run_id = int(q("INSERT INTO run (tenant_id, run_no, run_type, status, pause_kind, pause_reason, paused_at, updated_at) "
                   "VALUES (1, 'VERIFY-STALE-1', 'workflow', 'paused', 'waiting_human', 'VERIFY-STALE', "
                   "now() - interval '2 days', now() - interval '2 days') RETURNING id").strip().split("\n")[0])
    ok("造 2 天前 waiting_human run", "run=%d" % run_id)

    # 设 autoDismissDays=1 + 触发
    r = api("/api/settings/stale-pause", "PUT", token=token, body={"autoDismissDays": 1})
    if r.get("autoDismissDays") == 1:
        ok("设 autoDismissDays=1")
    else:
        bad("设 autoDismissDays 失败", str(r))

    r = api("/api/scheduler/stale-check", "POST", token=token)
    if r.get("dismissed", 0) >= 1:
        ok("stale-check 返回 dismissed>=1", str(r))
    else:
        bad("stale-check 未 dismiss", str(r))

    # 断言 run 已 aborted + EC-HUMAN-DISMISS
    st = q("SELECT status, error_code FROM run WHERE id=%d" % run_id)
    parts = st.strip().split("|")
    if len(parts) == 2 and parts[0] == "aborted" and "EC-HUMAN-DISMISS" in (parts[1] or ""):
        ok("陈旧 waiting_human run 已自动 dismiss", "status=%s err=%s" % (parts[0], parts[1]))
    else:
        bad("陈旧 run 未正确 dismiss", st.strip())

    # 还原 + 清理
    api("/api/settings/stale-pause", "PUT", token=token, body={"autoDismissDays": 0})
    q("DELETE FROM run WHERE run_no LIKE 'VERIFY-STALE-%';")

    print("\n通过 %d / 失败 %d" % (PASS, FAIL))
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
