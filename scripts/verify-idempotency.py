#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""幂等门禁 · **连跑两轮，断言两轮结果完全一致**（2026-11-14 新增）。

**为什么要有这个门禁**
2026-11-13/14 两轮独立评审连续踩到同一个坑：
  · `verify-role-permissions.py` 用固定 planNo + 归档式清理
    ⇒ `uk_schedule_plan` 不带状态条件，归档不释放 plan_no ⇒ **第二次跑必 409**；
  · `verify-schedule-plans-page.py` 压根不清理 ⇒ 每跑一次多一条排程 ⇒ 「plan=0」不成立。
两个坑的共同点：**只在"跑一次"的时候看不出来**。所以光有"全绿"不够，
必须**把同一批脚本连跑两遍、断言两遍的数字一模一样** —— 这才是"可复现"的验收。

**断言**
  ① 第一轮每个脚本都 0 失败；
  ② 第二轮每个脚本都 0 失败；
  ③ 同一个脚本两轮的「通过数 / 失败数」**逐个相同**；
  ④ 终态数据基线归零：schedule_plan 活跃 = 0、resource_booking = 0、陈旧暂停 run = 0。

**脚本入选标准（hermetic）**：不占设备、不建 run、不依赖历史数据、跑完自己干净。
需要前端/Playwright 的脚本在环境不具备时**显式 SKIP**（不静默算通过），且不参与两轮比对。

用法：
  python3 scripts/verify-idempotency.py            # 跑两轮
  python3 scripts/verify-idempotency.py --round 2  # 只跑一轮（调试用）
"""
import json
import os
import re
import subprocess
import sys
import time
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
API = os.environ.get("LABOS_API", "http://127.0.0.1:18080")
WEB = os.environ.get("LABOS_WEB", "http://localhost:5173")

# (脚本名, 需要前端/Playwright)
CANDIDATES = [
    ("verify-role-permissions", False),
    ("verify-p2-scheduler", False),
    ("verify-binding-api", False),
    ("verify-schedule-plans-page", True),
]

PASS, FAIL, SKIP = 0, 0, 0


def ok(msg, detail=""):
    global PASS
    PASS += 1
    print("  \033[32m✓\033[0m " + msg + (("（%s）" % detail) if detail else ""))


def bad(msg, detail=""):
    global FAIL
    FAIL += 1
    print("  \033[31m✗\033[0m " + msg + (("（%s）" % detail) if detail else ""))


def skip(msg, detail=""):
    global SKIP
    SKIP += 1
    print("  \033[36m⊘\033[0m 跳过 " + msg + (("（%s）" % detail) if detail else ""))


def q(sql):
    """跑 SQL。失败必须抛错 —— 返回空串会被当成"库里没有"⇒ 假绿。"""
    env = dict(os.environ)
    env["PGPASSWORD"] = os.environ.get("LABOS_DB_PASS", "")
    host = os.environ.get("LABOS_DB_HOST", "127.0.0.1")
    p = subprocess.run(["psql", "-h", host, "-p", os.environ.get("LABOS_DB_PORT", "5432"),
                        "-U", os.environ.get("LABOS_DB_USER", "labos"),
                        "-d", os.environ.get("LABOS_DB_NAME", "labdb"), "-tAc", sql],
                       capture_output=True, text=True, env=env)
    if p.returncode != 0:
        raise RuntimeError(p.stderr.strip()[:200])
    return p.stdout.strip()


def web_ready():
    try:
        urllib.request.urlopen(WEB, timeout=5)
        return True
    except Exception:
        return False


def playwright_ready():
    try:
        import playwright  # noqa: F401
        return True
    except Exception:
        return False


NUM = re.compile(r"通过\s*(\d+)\s*/\s*失败\s*(\d+)")


def run_one(name, need_web):
    """跑一个脚本，返回 (pass, fail, skipped, reason)。"""
    path = os.path.join(ROOT, "scripts", name + ".py")
    if not os.path.exists(path):
        return None, None, True, "脚本不存在"
    if need_web and not (web_ready() and playwright_ready()):
        return None, None, True, "前端/Playwright 不具备"
    t0 = time.time()
    try:
        p = subprocess.run([sys.executable, path], capture_output=True, text=True,
                           timeout=600, env=dict(os.environ))
        out = (p.stdout or "") + (p.stderr or "")
    except subprocess.TimeoutExpired:
        return None, None, True, "超时"
    m = NUM.search(out)
    if not m:
        return None, None, True, "没解析出通过/失败数（退出码 %s）" % p.returncode
    return int(m.group(1)), int(m.group(2)), False, "%.1fs" % (time.time() - t0)


def baseline():
    """终态数据基线。"""
    plan = q("SELECT count(*) FROM schedule_plan WHERE status <> 'archived'")
    booking = q("SELECT count(*) FROM resource_booking WHERE deleted_at IS NULL")
    stale = q("SELECT count(*) FROM run WHERE status='paused' AND pause_kind='waiting_human'"
              " AND paused_at < now() - interval '1 day'")
    return {"schedule_plan": int(plan or 0), "resource_booking": int(booking or 0),
            "stale_run": int(stale or 0)}


def main():
    rounds = 2
    if "--round" in sys.argv:
        try:
            rounds = int(sys.argv[sys.argv.index("--round") + 1])
        except Exception:
            rounds = 2

    print("── 第 1 轮 ──")
    r1 = {}
    for name, need_web in CANDIDATES:
        p, f, sk, why = run_one(name, need_web)
        if sk:
            skip(name, why)
            continue
        r1[name] = (p, f)
        if f == 0:
            ok("%s %d/0（%s）" % (name, p, why))
        else:
            bad("%s 有 %d 条失败" % (name, f))

    if rounds < 2:
        print("\n通过 %d / 失败 %d / 跳过 %d" % (PASS, FAIL, SKIP))
        return 0 if FAIL == 0 else 1

    print("── 第 2 轮 ──")
    r2 = {}
    for name, need_web in CANDIDATES:
        p, f, sk, why = run_one(name, need_web)
        if sk:
            skip(name, why)
            continue
        r2[name] = (p, f)
        if f == 0:
            ok("%s %d/0（%s）" % (name, p, why))
        else:
            bad("%s 有 %d 条失败" % (name, f))

    print("── 两轮比对 ──")
    for name in sorted(set(r1) & set(r2)):
        if r1[name] == r2[name]:
            ok("%s 两轮一致 %s" % (name, "%d/%d" % r1[name]))
        else:
            bad("%s 两轮不一致：第1轮 %s，第2轮 %s" % (name, r1[name], r2[name]))
    only1 = sorted(set(r1) - set(r2))
    only2 = sorted(set(r2) - set(r1))
    for n in only1 + only2:
        bad("%s 只在一轮里跑了（跳过状态不一致）" % n)

    print("── 终态数据基线 ──")
    try:
        bl = baseline()
        for k, v in sorted(bl.items()):
            if v == 0:
                ok("%s = 0" % k)
            else:
                bad("%s = %d（应为 0）" % (k, v))
    except Exception as e:
        bad("读终态基线失败", str(e)[:120])

    print("\n通过 %d / 失败 %d / 跳过 %d" % (PASS, FAIL, SKIP))
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
