#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
权限收口联调（RBAC）：HTTP 角色矩阵 + 前端只读体验。

覆盖：
  1. admin：可读用户列表、可建用户（operator / viewer）、非法角色返回 400
  2. viewer：可读业务数据（GET 200），写操作 403，读用户管理 403
  3. operator：可写业务（建实验 200），但用户管理 403（仅 admin）
  4. 前端：viewer 登录后 Header 显示"只读"、实验页「执行实验」禁用（title 含只读）、
     看不到用户管理菜单、/users 显示"仅管理员"；admin 登录 Header 显示"管理员"且按钮可用。

用法：
  LABOS_DB_HOST=... LABOS_DB_NAME=... LABOS_DB_USER=... LABOS_DB_PASS=... \
    python3 scripts/verify-rbac.py [--api http://127.0.0.1:18080] [--base http://localhost:5173]
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

PASS = 0
FAIL = 0


def ok(msg):
    global PASS
    PASS += 1
    print("  \u2713 " + msg)


def bad(msg, detail=""):
    global FAIL
    FAIL += 1
    print("  \u2717 " + msg + (("  (" + str(detail)[:160] + ")") if detail else ""))


def step(t):
    print("\n\u2550\u2550 " + t + " \u2550\u2550")


class Api:
    def __init__(self, base, token=None):
        self.base, self.token = base, token

    def call(self, method, path, body=None):
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
        req = urllib.request.Request(self.base + path, data=data, method=method)
        if self.token:
            req.add_header("Authorization", "Bearer " + self.token)
        if data is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                raw = r.read().decode("utf-8", "replace")
                try:
                    return r.status, json.loads(raw)
                except json.JSONDecodeError:
                    return r.status, raw
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8", "replace")
            try:
                return e.code, json.loads(raw)
            except json.JSONDecodeError:
                return e.code, {"error": raw[:200]}
        except Exception as e:  # noqa: BLE001
            return 0, {"error": str(e)[:200]}


def login(base, username, password):
    code, j = Api(base).call("POST", "/api/auth/login",
                            {"username": username, "password": password})
    return (j.get("token") if isinstance(j, dict) else None), code, j


def db_connect():
    need = [k for k in ("LABOS_DB_HOST", "LABOS_DB_NAME", "LABOS_DB_USER") if not os.environ.get(k)]
    if need:
        return None
    try:
        import psycopg2
        return psycopg2.connect(
            host=os.environ["LABOS_DB_HOST"],
            port=int(os.environ.get("LABOS_DB_PORT", "5432")),
            dbname=os.environ["LABOS_DB_NAME"],
            user=os.environ["LABOS_DB_USER"],
            password=os.environ.get("LABOS_DB_PASS", ""),
        )
    except Exception:  # noqa: BLE001
        return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--api", default="http://127.0.0.1:18080")
    ap.add_argument("--base", default="http://localhost:5173")
    ap.add_argument("--user", default="admin")
    ap.add_argument("--pass", dest="pwd", default="admin123")
    args = ap.parse_args()

    tag = int(time.time()) % 100000
    op_user = "rbac_op_%d" % tag
    viewer_user = "rbac_viewer_%d" % tag
    pwd = "Lab@12345"
    created_exp = []

    admin_token = None
    try:
        admin_token, code, j = login(args.api, args.user, args.pwd)
        if not admin_token:
            print("admin 登录失败：" + str(j))
            return 2
        admin = Api(args.api, admin_token)

        step("1. admin：用户管理可写、非法角色拒绝")
        code, j = admin.call("GET", "/api/users")
        ok("admin 可读用户列表（200）") if code == 200 else bad("admin 读用户列表失败", (code, j))
        code, j = admin.call("POST", "/api/users",
                             {"username": op_user, "displayName": "RBAC 操作员",
                              "role": "operator", "password": pwd})
        ok("admin 建 operator 账号成功") if code == 200 else bad("建 operator 失败", (code, j))
        code, j = admin.call("POST", "/api/users",
                             {"username": viewer_user, "displayName": "RBAC 只读",
                              "role": "viewer", "password": pwd})
        ok("admin 建 viewer 账号成功") if code == 200 else bad("建 viewer 失败", (code, j))
        code, j = admin.call("POST", "/api/users",
                             {"username": "rbac_boss_%d" % tag, "displayName": "非法角色",
                              "role": "boss", "password": pwd})
        err = (j.get("error") if isinstance(j, dict) else "") or ""
        ok("非法角色被拒（400 且提示角色不合法）") if code == 400 and "角色不合法" in err \
            else bad("非法角色未被拒", (code, j))

        step("2. viewer：只读（业务可读、写操作 403、用户管理 403）")
        vtoken, code, j = login(args.api, viewer_user, pwd)
        ok("viewer 登录成功") if vtoken else bad("viewer 登录失败", (code, j))
        viewer = Api(args.api, vtoken)
        code, _ = viewer.call("GET", "/api/experiments?limit=5")
        ok("viewer 可读实验列表（200）") if code == 200 else bad("viewer 读实验失败", code)
        code, j = viewer.call("POST", "/api/experiments", {"name": "rbac-viewer-should-fail"})
        ok("viewer 建实验被拒（403）") if code == 403 else bad("viewer 写操作未拦截", (code, j))
        code, j = viewer.call("GET", "/api/users")
        ok("viewer 读用户管理被拒（403）") if code == 403 else bad("viewer 读用户未拦截", (code, j))
        code, j = viewer.call("POST", "/api/users",
                              {"username": "rbac_x_%d" % tag, "role": "admin", "password": pwd})
        ok("viewer 建用户被拒（403）") if code == 403 else bad("viewer 建用户未拦截", (code, j))

        step("3. operator：可写业务、不能管账号")
        otoken, code, j = login(args.api, op_user, pwd)
        ok("operator 登录成功") if otoken else bad("operator 登录失败", (code, j))
        operator = Api(args.api, otoken)
        code, j = operator.call("POST", "/api/experiments",
                                {"name": "rbac-operator-允许建实验-%d" % tag})
        if code == 200:
            eid = j.get("id") if isinstance(j, dict) else j
            created_exp.append(int(eid))
            ok("operator 可建实验（写权限生效）")
        else:
            bad("operator 建实验失败", (code, j))
        code, _ = operator.call("GET", "/api/users")
        ok("operator 读用户管理被拒（403）") if code == 403 else bad("operator 读用户未拦截", code)
        code, _ = operator.call("POST", "/api/users",
                                {"username": "rbac_y_%d" % tag, "role": "admin", "password": pwd})
        ok("operator 建用户被拒（403，不能提权）") if code == 403 else bad("operator 建用户未拦截", code)

        step("4. 前端：viewer 只读体验 / admin 正常")
        from playwright.sync_api import sync_playwright
        with sync_playwright() as p:
            b = p.chromium.launch()

            def do_login(page, u, pw):
                page.goto(args.base, wait_until="networkidle")
                page.locator('input[type="text"]').first.fill(u)
                page.locator('input[type="password"]').first.fill(pw)
                page.get_by_role("button", name="登 录").click() if page.get_by_role(
                    "button", name="登 录").count() else page.get_by_role(
                    "button", name="登录").click()
                page.wait_for_selector("text=流程库", timeout=25000)

            vp = b.new_page(viewport={"width": 1500, "height": 1000})
            vp.set_default_timeout(25000)
            do_login(vp, viewer_user, pwd)
            vp.wait_for_timeout(1500)
            header = vp.inner_text("header")
            ok("viewer Header 显示角色「只读」") if "只读" in header else bad("viewer Header 无只读", header[:120])
            ok("viewer 看不到「用户管理」菜单") if "用户管理" not in vp.inner_text("body") else bad("viewer 仍看到用户管理菜单")
            vp.goto(args.base + "/experiments/844", wait_until="networkidle")
            vp.wait_for_timeout(2500)
            btn = vp.get_by_role("button", name="执行实验")
            disabled = btn.count() > 0 and btn.first.is_disabled()
            ok("viewer 实验页「执行实验」按钮禁用") if disabled else bad("viewer 执行实验按钮未禁用")
            title = btn.first.get_attribute("title") if btn.count() else ""
            ok("禁用理由提示含「只读」") if title and "只读" in title else bad("禁用按钮无只读提示", title)
            vp.goto(args.base + "/users", wait_until="networkidle")
            vp.wait_for_timeout(1200)
            ok("viewer 访问 /users 显示仅管理员提示") if "仅管理员" in vp.inner_text("body") else bad("viewer /users 无守卫提示")
            vp.screenshot(path="/tmp/rbac-viewer.png")

            ap_ = b.new_page(viewport={"width": 1500, "height": 1000})
            ap_.set_default_timeout(25000)
            do_login(ap_, args.user, args.pwd)
            ap_.wait_for_timeout(1500)
            aheader = ap_.inner_text("header")
            ok("admin Header 显示角色「管理员」") if "管理员" in aheader else bad("admin Header 无管理员", aheader[:120])
            ap_.goto(args.base + "/experiments/844", wait_until="networkidle")
            ap_.wait_for_timeout(2500)
            abtn = ap_.get_by_role("button", name="执行实验")
            ok("admin 实验页「执行实验」按钮可用") if abtn.count() > 0 and abtn.first.is_enabled() \
                else bad("admin 执行实验按钮不可用")
            ap_.screenshot(path="/tmp/rbac-admin.png")
            b.close()
    except Exception as e:  # noqa: BLE001
        bad("异常", str(e))
    finally:
        conn = db_connect()
        if conn:
            try:
                cur = conn.cursor()
                cur.execute("DELETE FROM sys_user WHERE username LIKE 'rbac_%'")
                # 按名兜底（BUG-20261104-02）：只按 created_exp 删实验时，若本进程被强杀
                # 漏进了 finally，历史遗留的 rbac 实验就永远清不掉；这里按名字再删一遍，
                # 与上面的 username LIKE 'rbac_%' 对称。先删 experiment_run 满足外键。
                cur.execute("DELETE FROM experiment_run WHERE experiment_id IN "
                            "(SELECT id FROM experiment WHERE name LIKE 'rbac-%')")
                cur.execute("DELETE FROM experiment WHERE name LIKE 'rbac-%'")
                for eid in created_exp:
                    cur.execute("DELETE FROM experiment_run WHERE experiment_id=%s", (eid,))
                    cur.execute("DELETE FROM experiment WHERE id=%s", (eid,))
                conn.commit()
            except Exception as e:  # noqa: BLE001
                print("清理失败：" + str(e))
            finally:
                conn.close()
        print("\n已清理 RBAC 测试账号与实验")

    print()
    print("通过 %d / 失败 %d" % (PASS, FAIL))
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
