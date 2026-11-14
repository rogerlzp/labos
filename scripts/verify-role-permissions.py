#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""角色权限矩阵（F 组补齐）：admin/operator/designer/viewer 对读写端点的 200/403。

口径（RbacRules.canWrite）：admin/operator/designer 可写；viewer 只读。
写端点：POST /api/schedule-plans、POST /api/scheduler/reload-config、PUT /api/settings/stale-pause。
另加 DELETE /api/schedule-plans/{id}（排程计划多了要能删）。

**幂等性（2026-11-14 修）**
本脚本以前有两个坑，导致**第二次跑必失败**：
  ① planNo 固定为 `VERIFY-PERM-<role>`，而 `uk_schedule_plan UNIQUE (tenant_id, plan_no)`
     **不带状态条件** ⇒ 归档（status=archived）**不释放 plan_no** ⇒ 第二次建同一个号 → 409。
  ② 清理用 `PUT status=archived` ⇒ 号一直被占，脚本**只能跑一次**。
现在：
  · planNo 带运行期唯一后缀（不靠清理保证不撞）；
  · 清理改成 **DELETE /api/schedule-plans/{id}**（物理删，释放 plan_no）；
  · 顺带把**历史残留**的 VERIFY-PERM-* 一并删掉（数据库干净才准开始）。

**自备账号**：operator / designer / viewer 三个账号由本脚本自己建/重置口令
（CI 新装库只有内核启动时 seed 的 admin）。账号是**夹具**不是数据基线 ⇒ 不删除，
只保证存在且口令正确（与 verify-role-nav.py 共用 verify-designer 同一个账号）。

用法：
  python3 scripts/verify-role-permissions.py
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

API = os.environ.get("LABOS_API", "http://127.0.0.1:18080")

PASS, FAIL = 0, 0

# 账号：(用户名, 口令, 角色, 显示名)
ACCOUNTS = [
    ("admin", "admin123", "admin", "管理员"),
    ("zhangsan", "zhangsan123", "operator", "张三"),
    ("verify-designer", "verify123", "designer", "验证编排"),
    ("lisi", "lisi123", "viewer", "李四"),
]
# admin 由内核启动时 seed 出来；其余三个本脚本自备
SELF_SERVED = ACCOUNTS[1:]


def ok(msg, detail=""):
    global PASS
    PASS += 1
    print("  \033[32m✓\033[0m " + msg + (("（%s）" % detail) if detail else ""))


def bad(msg, detail=""):
    global FAIL
    FAIL += 1
    print("  \033[31m✗\033[0m " + msg + (("（%s）" % detail) if detail else ""))


def login(user, pw):
    req = urllib.request.Request(API + "/api/auth/login", method="POST",
                                 data=json.dumps({"username": user, "password": pw}).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode()).get("token")


def call(token, path, method="GET", body=None):
    req = urllib.request.Request(API + path, method=method,
                                 data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Content-Type": "application/json",
                                          "Authorization": "Bearer " + token})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode())
        except Exception:
            return e.code, {}


def list_users(token):
    st, body = call(token, "/api/users")
    if st != 200:
        return []
    return body.get("items") or []


def ensure_account(admin_token):
    """自备账号：没有就建，有就重置口令并启用（CI 新装库只有 admin）。"""
    users = {u.get("username"): u for u in list_users(admin_token)}
    for user, pw, role, display in SELF_SERVED:
        u = users.get(user)
        if u is None:
            st, body = call(admin_token, "/api/users", "POST",
                            {"username": user, "displayName": display,
                             "role": role, "password": pw})
            if st == 200:
                ok("账号 %s（%s）已创建" % (user, role))
            else:
                bad("账号 %s 创建失败" % user, "HTTP %d %s" % (st, str(body)[:80]))
                continue
        else:
            st, body = call(admin_token, "/api/users/%d" % int(u["id"]), "PUT",
                            {"role": role, "password": pw, "enabled": True})
            if st == 200:
                ok("账号 %s（%s）口令已重置" % (user, role))
            else:
                bad("账号 %s 重置失败" % user, "HTTP %d %s" % (st, str(body)[:80]))


def cleanup_plans(admin_token):
    """删掉所有 VERIFY-PERM 开头的排程计划（含历史残留的 archived 行）。

    为什么必须 DELETE 而不是归档：`uk_schedule_plan` 不带状态条件，
    归档不释放 plan_no ⇒ 号被永久占住 ⇒ 下次跑必然 409。
    """
    st, body = call(admin_token, "/api/schedule-plans")
    if st != 200:
        bad("清理前读排程列表失败", "HTTP %d" % st)
        return
    items = body.get("items") or []
    targets = [it for it in items
               if str(it.get("planNo", "")).startswith("VERIFY-PERM")]
    deleted = 0
    for it in targets:
        st2, _ = call(admin_token, "/api/schedule-plans/%d" % int(it["id"]), "DELETE")
        if st2 == 200:
            deleted += 1
        else:
            bad("清理排程 %s 失败" % it.get("planNo"), "HTTP %d" % st2)
    ok("清理前删除历史残留排程 %d 条" % deleted)


def main():
    try:
        admin_tok = login("admin", "admin123")
    except Exception as e:
        print("  \033[31m✗\033[0m admin 登录失败：%s" % str(e)[:120])
        return 1
    ok("登录 admin(admin)")

    ensure_account(admin_tok)
    cleanup_plans(admin_tok)

    tokens = {}
    for user, pw, role, _ in ACCOUNTS:
        try:
            tokens[role] = login(user, pw)
            ok("登录 %s(%s)" % (user, role))
        except Exception as e:
            bad("登录 %s 失败" % user, str(e)[:120])
            return 1

    print("── 读：GET /api/scheduler/config（四角色都应 200）──")
    for role in ("admin", "operator", "designer", "viewer"):
        st, body = call(tokens[role], "/api/scheduler/config")
        if st == 200:
            ok("%s GET config 200" % role)
        else:
            bad("%s GET config 非 200" % role, "HTTP %d" % st)

    print("── 写：admin/operator/designer 200，viewer 403 ──")
    # planNo 带运行期唯一后缀：不靠清理保证不撞（同一个号建两次就是 409）
    stamp = "%d" % int(time.time() * 1000)
    for name, (method, path, body) in WRITE_ENDPOINTS:
        for role in ("admin", "operator", "designer", "viewer"):
            actual_body = body
            if name == "POST /api/schedule-plans" and body:
                actual_body = dict(body, planNo="VERIFY-PERM-%s-%s" % (role, stamp))
            st, resp = call(tokens[role], path, method, actual_body)
            expect = 403 if role == "viewer" else 200
            if st == expect:
                ok("%s %s → %d（期望）" % (role, name, st))
            else:
                bad("%s %s → %d（期望 %d）" % (role, name, st, expect), str(resp)[:80])

    print("── 删：admin/operator/designer 200，viewer 403 ──")
    # 每个可写角色**自己建一条再删自己那条**：
    # 共用一条会让先删的那个（admin）把后面的变 404 —— 测的就不是权限了。
    victim_no = "VERIFY-PERM-DELVICTIM-%s" % stamp
    st, body = call(tokens["admin"], "/api/schedule-plans", "POST",
                     {"planNo": victim_no, "optimizationFactor": 50,
                      "maxIterationsRunning": 4, "iterationDelaySec": 0})
    if st != 200:
        bad("建待删排程失败", "HTTP %d %s" % (st, str(body)[:80]))
    else:
        vid = None
        st, plans = call(tokens["admin"], "/api/schedule-plans")
        for it in (plans.get("items") or []):
            if it.get("planNo") == victim_no:
                vid = int(it["id"])
                break
        if vid is None:
            bad("找不到待删排程", victim_no)
        else:
            # viewer 先来：应当被拒，且**排程还在**（不能被顺手删掉）
            st, resp = call(tokens["viewer"], "/api/schedule-plans/%d" % vid, "DELETE")
            if st == 403:
                ok("viewer DELETE /api/schedule-plans → 403（期望）")
            else:
                bad("viewer DELETE → %d（期望 403）" % st, str(resp)[:80])
            # 注意：没有 GET /api/schedule-plans/{id} 端点（只��列表），
            # 所以"还在不在"要从**列表**里找 —— 别用单查，会误判成 404。
            st, plans2 = call(tokens["admin"], "/api/schedule-plans")
            still = any(it.get("planNo") == victim_no for it in (plans2.get("items") or []))
            if still:
                ok("viewer 被拒后排程仍在（没被误删）")
            else:
                bad("viewer 被拒后排程消失了", victim_no)

    for role in ("admin", "operator", "designer"):
        plan_no = "VERIFY-PERM-DEL-%s-%s" % (role, stamp)
        st, body = call(tokens[role], "/api/schedule-plans", "POST",
                         {"planNo": plan_no, "optimizationFactor": 50,
                          "maxIterationsRunning": 4, "iterationDelaySec": 0})
        if st != 200:
            bad("%s 建待删排程失败" % role, "HTTP %d %s" % (st, str(body)[:80]))
            continue
        st, plans = call(tokens["admin"], "/api/schedule-plans")
        vid = None
        for it in (plans.get("items") or []):
            if it.get("planNo") == plan_no:
                vid = int(it["id"])
                break
        if vid is None:
            bad("%s 找不到待删排程" % role, plan_no)
            continue
        st, resp = call(tokens[role], "/api/schedule-plans/%d" % vid, "DELETE")
        if st == 200:
            ok("%s DELETE /api/schedule-plans → 200（期望）" % role)
        else:
            bad("%s DELETE /api/schedule-plans → %d（期望 200）" % (role, st), str(resp)[:80])

    # 清理：删掉所有 VERIFY-PERM 开头的 plan（幂等兜底）
    cleanup_plans(tokens["admin"])

    print("\n通过 %d / 失败 %d" % (PASS, FAIL))
    return 0 if FAIL == 0 else 1


WRITE_ENDPOINTS = [
    ("POST /api/schedule-plans",
     ("POST", "/api/schedule-plans", {"planNo": "VERIFY-PERM", "optimizationFactor": 50,
                                      "maxIterationsRunning": 4, "iterationDelaySec": 0})),
    ("POST /api/scheduler/reload-config", ("POST", "/api/scheduler/reload-config", None)),
    ("PUT /api/settings/stale-pause", ("PUT", "/api/settings/stale-pause", {"autoDismissDays": 0})),
]


if __name__ == "__main__":
    sys.exit(main())
