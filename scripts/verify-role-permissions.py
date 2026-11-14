#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""角色权限矩阵（F 组补齐）：admin/operator/designer/viewer 对读写端点的 200/403。

口径（RbacRules.canWrite）：admin/operator/designer 可写；viewer 只读。
写端点：POST /api/schedule-plans、POST /api/scheduler/reload-config、PUT /api/settings/stale-pause。

用法：
  python3 scripts/verify-role-permissions.py
"""
import json
import os
import sys
import urllib.error
import urllib.request

API = os.environ.get("LABOS_API", "http://127.0.0.1:18080")

PASS, FAIL = 0, 0


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


ACCOUNTS = [
    ("admin", "admin123", "admin"),
    ("zhangsan", "zhangsan123", "operator"),
    ("verify-designer", "verify123", "designer"),
    ("lisi", "lisi123", "viewer"),
]
WRITE_ENDPOINTS = [
    ("POST /api/schedule-plans",
     ("POST", "/api/schedule-plans", {"planNo": "VERIFY-PERM", "optimizationFactor": 50,
                                      "maxIterationsRunning": 4, "iterationDelaySec": 0})),
    ("POST /api/scheduler/reload-config", ("POST", "/api/scheduler/reload-config", None)),
    ("PUT /api/settings/stale-pause", ("PUT", "/api/settings/stale-pause", {"autoDismissDays": 0})),
]


def main():
    tokens = {}
    for user, pw, role in ACCOUNTS:
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
    for name, (method, path, body) in WRITE_ENDPOINTS:
        for role in ("admin", "operator", "designer", "viewer"):
            # schedule-plans 的 planNo 按角色唯一，避免重复 409 干扰权限断言
            actual_body = body
            if name == "POST /api/schedule-plans" and body:
                actual_body = dict(body, planNo="VERIFY-PERM-%s" % role)
            st, resp = call(tokens[role], path, method, actual_body)
            expect = 403 if role == "viewer" else 200
            if st == expect:
                ok("%s %s → %d（期望）" % (role, name, st))
            else:
                bad("%s %s → %d（期望 %d）" % (role, name, st, expect), str(resp)[:80])

    # 清理：归档掉所有 VERIFY-PERM 开头的 plan（admin 权限）
    st, plans = call(tokens["admin"], "/api/schedule-plans")
    for it in (plans.get("items") or []):
        if str(it.get("planNo", "")).startswith("VERIFY-PERM"):
            call(tokens["admin"], "/api/schedule-plans/%d" % it["id"], "PUT", {"status": "archived"})

    print("\n通过 %d / 失败 %d" % (PASS, FAIL))
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
