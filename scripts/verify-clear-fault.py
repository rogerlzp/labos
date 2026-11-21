#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""**人工清除设备故障**端点的验收脚本（POST /api/devices/{id}/clear-fault）。

**为什么要有这个脚本（2026-11-19 实测）**
设备命令失败后内核把 `last_error` 写上、`status` 落 `'error'`；而能力池
（`GraphMapper.selectCapabilityPools`）会**排除 error 设备** ⇒ 这台设备在岛内
「不存在」⇒ 岛边界闸门 PF-13 直接拦下整条流程：
    「节点需要 TRANSFER 能力，但流程所属的岛 N 内没有具备该能力的设备」
⇒ **一次失败就再也跑不起来**：机械臂失败一次 → ARM1 进 error → 之后所有搬运节点
被 PF-13 拦死，而平台原本**没有任何**清故障的通道 ⇒ 现场只能直接改库。
本端点补上这条通道（设备锁只在持有它的 run 已到终态时才释放）。

**验的 5 件事**
  ① 注入故障 → 端点清除 → last_error 空、status 回 idle
  ② 锁持有者**已终结** → 锁被释放
  ③ 锁持有者**在途**（paused/run） → 锁**必须保留**（不能抢走别人的设备）
  ④ 设备实例不存在 → 404 + 清晰报错
  ⑤ **端到端**：ARM1 进 error → 派发被 PF-13 拦死 → 端点清故障 → 再派发即跑起来

**用法**
    python3 scripts/verify-clear-fault.py             # 跑 ①~④（会临时改设备状态，测完复原）
    python3 scripts/verify-clear-fault.py --e2e       # 连 ⑤ 一起跑（会真的起一次实验 run）

⚠ 测试用例会**临时改写 device_state**，跑完复原；别在客户现场库上跑。
"""
import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

API = os.environ.get("LABOS_API", "http://127.0.0.1:18080").rstrip("/")
USER = os.environ.get("LABOS_USER", "admin")
PASSWORD = os.environ.get("LABOS_PASS", "admin123")
DB_HOST = os.environ.get("LABOS_DB_HOST", "192.168.124.2")
DB_PORT = os.environ.get("LABOS_DB_PORT", "5432")
DB_NAME = os.environ.get("LABOS_DB_NAME", "labdb")
DB_USER = os.environ.get("LABOS_USER_DB", "labos")
DB_PASS = os.environ.get("LABOS_DB_PASS", "Labos@Lab2026")
PSQL = os.environ.get("LABOS_PSQL", "psql")

ARM_CODE = os.environ.get("LABOS_TEST_DEVICE", "ARM1")

TOKEN = None
PASS = 0
FAIL = 0


def psql(sql):
    env = dict(os.environ, PGPASSWORD=DB_PASS)
    p = subprocess.run([PSQL, "-h", DB_HOST, "-p", DB_PORT, "-U", DB_USER, "-d", DB_NAME,
                        "-A", "-t", "-F", "|", "-c", sql],
                       capture_output=True, text=True, env=env)
    if p.returncode != 0:
        sys.exit("✘ SQL 失败：\n" + p.stderr.strip())
    return [ln for ln in p.stdout.strip().splitlines() if ln]


def call(method, path, body=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(API + path, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if TOKEN:
        req.add_header("Authorization", "Bearer " + TOKEN)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            t = r.read().decode("utf-8")
            return r.status, (json.loads(t) if t.strip() else {})
    except urllib.error.HTTPError as e:
        return e.code, {"error": e.read().decode("utf-8", "replace")[:200]}
    except Exception as e:
        return 0, {"error": str(e)}


def ok(msg):
    global PASS
    PASS += 1
    print("  \033[32m✓\033[0m " + msg)


def bad(msg):
    global FAIL
    FAIL += 1
    print("  \033[31m✘\033[0m " + msg)


def dev_ids(code):
    rows = psql("SELECT di.id, di.resource_id FROM device_instance di "
                "WHERE di.code = '%s' AND di.deleted_at IS NULL" % code)
    if not rows:
        sys.exit("✘ 找不到设备实例 %s" % code)
    inst, res = rows[0].split("|")
    return int(inst), int(res)


def state(res):
    r = psql("SELECT COALESCE(last_error,''), COALESCE(status,''), "
             "COALESCE(current_run_id::text,''), COALESCE(current_method,'') "
             "FROM device_state WHERE tenant_id=1 AND resource_id=%d" % res)
    return r[0].split("|") if r else ["", "", "", ""]


def set_state(res, err=None, status=None, run_id=None):
    psql("UPDATE device_state SET last_error=%s, status=%s, current_run_id=%s, updated_at=now() "
         "WHERE tenant_id=1 AND resource_id=%d" % (
         "NULL" if err is None else "'%s'" % err,
         "status",
         "NULL" if run_id is None else str(run_id),
         res))


def clear(inst, note):
    return call("POST", "/api/devices/%d/clear-fault" % inst, {"note": note})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--e2e", action="store_true", help="连端到端（会真的起一次实验 run）一起跑")
    args = ap.parse_args()
    global TOKEN
    st, j = call("POST", "/api/auth/login", {"username": USER, "password": PASSWORD})
    if st != 200 or not j.get("token"):
        sys.exit("✘ 登录失败（%s）" % st)
    TOKEN = j["token"]
    inst, res = dev_ids(ARM_CODE)
    print("设备 %s：instance=%d resource=%d" % (ARM_CODE, inst, res))
    before = state(res)

    print("\n── ① 注入故障 → 人工清除")
    set_state(res, err="EC-TEST-INJECT", status="'error'")
    st, j = clear(inst, "验收脚本：① 注入故障")
    after = state(res)
    if st == 200 and not after[0] and after[1] == "idle":
        ok("清除成功：last_error 空、status 回 idle")
    else:
        bad("清除结果不对：%s %s" % (st, json.dumps(j, ensure_ascii=False)[:120]))

    print("\n── ② 锁持有者已终结 → 锁应释放")
    done = psql("SELECT id FROM run WHERE status IN ('completed','failed','aborted','cancelled') "
                "ORDER BY id DESC LIMIT 1")
    set_state(res, err="EC-TEST-LOCK", status="'error'", run_id=done[0])
    st, j = clear(inst, "验收脚本：② 锁持有者已终结")
    after = state(res)
    if st == 200 and not after[2]:
        ok("锁已释放（持有者 run %s 已终结）" % done[0])
    else:
        bad("锁没释放：current_run_id=%s" % after[2])

    print("\n── ③ 锁持有者在途 → 锁必须保留")
    live = psql("SELECT id FROM run WHERE status IN ('running','paused','queued') "
                "ORDER BY id DESC LIMIT 1")
    if not live:
        print("  （库中没有在途 run，跳过：无从验证「不抢别人设备」这条语义）")
    else:
        set_state(res, err="EC-TEST-LOCK2", status="'error'", run_id=live[0])
        st, j = clear(inst, "验收脚本：③ 锁持有者在途")
        after = state(res)
        if st == 200 and after[2] == live[0]:
            ok("锁保留正确（在途 run %s 的设备没被抢走）" % live[0])
        else:
            bad("锁被误释放：current_run_id=%s（应为 %s）" % (after[2], live[0]))

    print("\n── ④ 设备实例不存在 → 404")
    st, j = call("POST", "/api/devices/99999999/clear-fault", {})
    if st == 404:
        ok("404 + %s" % str(j.get("error", ""))[:60])
    else:
        bad("期望 404，实际 %s" % st)

    # 复原
    psql("UPDATE device_state SET last_error=%s, status=COALESCE(status,'idle'), updated_at=now() "
         "WHERE tenant_id=1 AND resource_id=%d" % ("NULL", res))
    psql("UPDATE device_state SET current_run_id=NULL, current_method=NULL WHERE tenant_id=1 AND resource_id=%d" % res)

    if args.e2e:
        print("\n── ⑤ 端到端：故障 → PF-13 拦死 → 清故障 → 恢复")
        run_id = os.environ.get("LABOS_TEST_RUN")
        if not run_id:
            print("  （未给 LABOS_TEST_RUN，跳过：需要一条已配好位置的实验 run）")
        else:
            run_id = int(run_id)
            set_state(res, err="EC-POSITION-OCCUPIED", status="'error'")
            call("POST", "/api/experiment-runs/%d/dispatch" % run_id)
            time.sleep(20)
            row = psql("SELECT status, COALESCE(substr(error,1,120),'') FROM experiment_run WHERE id=%d" % run_id)
            if row and "PF-13" in row[0]:
                ok("故障态下派发被岛边界 PF-13 拦下（复现了「一次失败就再也跑不起来」）")
            else:
                bad("故障态下没看到 PF-13 拦截：%s" % (row[0] if row else "?"))
            st, j = clear(inst, "验收脚本：⑤ 端到端清故障")
            psql("UPDATE experiment_run SET status='queued', error=NULL, finished_at=NULL, "
                 "pause_kind=NULL, pause_reason=NULL, control_state='NONE' WHERE id=%d" % run_id)
            call("POST", "/api/experiment-runs/%d/dispatch" % run_id)
            time.sleep(20)
            row = psql("SELECT status FROM experiment_run WHERE id=%d" % run_id)
            if row and row[0] == "running":
                ok("清故障后重新派发即跑起来（死锁解除）")
            else:
                bad("清故障后仍没跑起来：%s" % (row[0] if row else "?"))

    print("\n" + ("✔ 全部通过：%d 项" % PASS if not FAIL else "✘ %d 项失败 / 共 %d 项" % (FAIL, PASS + FAIL)))
    sys.exit(1 if FAIL else 0)


if __name__ == "__main__":
    main()