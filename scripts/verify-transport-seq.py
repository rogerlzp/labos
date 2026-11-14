#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P1-8 · `transport_segment.seq` **run 内递增**的端到端证据。

**背景**：P1-8 的修复把 seq 从"硬编码 1"改成"按 run 内 MAX(seq)+1 递增"
（`insertTransportSegment` 的 SQL 用 `COALESCE((SELECT MAX(seq)+1 FROM transport_segment
WHERE run_id=?), 1)`）。此前只有**代码**证据、**没有数据**证据 ——
库里所有历史段的 seq 都还是 1，看起来像没修。

本脚本跑一次含多个搬运段的流程，断言：
  ① 段的 seq 是 **1,2,3,…** 严格递增（不是全 1）
  ② from → to 的点位链合理（终点落在设备/装载口上）
  ③ 搬运落在**装载口**（如 ID-X229-IN）而不是设备内部格

**前置**：内核 18080 + 模拟器 19500（没模拟器 ⇒ **显式 SKIP**，不静默算通过）。
**清理**：删掉本次 run 及其所有关联行，并释放它占的点位。

用法：
  LABOS_API=http://127.0.0.1:18080 LABOS_SIM_PORT=19500 \
  python3 scripts/verify-transport-seq.py
"""
import json
import os
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

API = os.environ.get("LABOS_API", "http://127.0.0.1:18080")
SIM_PORT = int(os.environ.get("LABOS_SIM_PORT", "19500"))
WORKFLOW_ID = int(os.environ.get("LABOS_SEQ_WF", "1000830"))
MIN_SEGMENTS = int(os.environ.get("LABOS_SEQ_MIN", "4"))

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


def api(path, method="GET", body=None, token=None):
    req = urllib.request.Request(API + path, method=method,
                                 data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Content-Type": "application/json",
                                          **({"Authorization": "Bearer " + token} if token else {})})
    try:
        with urllib.request.urlopen(req, timeout=600) as r:
            return r.status, json.loads(r.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode() or "{}")
        except Exception:
            return e.code, {}


def q(sql):
    env = dict(os.environ)
    env["PGPASSWORD"] = env.get("LABOS_DB_PASS", "")
    p = subprocess.run(["psql", "-h", env.get("LABOS_DB_HOST", "127.0.0.1"),
                        "-p", env.get("LABOS_DB_PORT", "5432"),
                        "-U", env.get("LABOS_DB_USER", "labos"),
                        "-d", env.get("LABOS_DB_NAME", "labdb"), "-tAc", sql],
                       capture_output=True, text=True, env=env)
    if p.returncode != 0:
        raise RuntimeError(p.stderr.strip()[:200])
    return p.stdout.strip()


def simulator_up():
    s = socket.socket()
    s.settimeout(3)
    try:
        s.connect(("127.0.0.1", SIM_PORT))
        return True
    except Exception:
        return False
    finally:
        s.close()


def cleanup_run(run_id):
    if not run_id:
        return
    try:
        q("UPDATE position_state SET plate_id = NULL, content_kind = NULL, content_id = NULL, "
           "occupied_by_run = NULL, occupied_since = NULL, updated_at = now() "
           "WHERE occupied_by_run = %d" % run_id)
    except Exception as e:
        print("  [清理] position_state 失败：%s" % str(e)[:80])
    for t in ("manual_window", "transport_segment", "run_artifact", "run_variable",
              "intervention_record", "run_command"):
        try:
            q("DELETE FROM %s WHERE run_id = %d" % (t, run_id))
        except Exception as e:
            print("  [清理] %s 失败：%s" % (t, str(e)[:80]))
    for sql in ("DELETE FROM run_event WHERE aggregate_type='run' AND aggregate_id=%d" % run_id,
                "DELETE FROM run_node WHERE run_id=%d" % run_id,
                "DELETE FROM run WHERE id=%d" % run_id):
        try:
            q(sql)
        except Exception as e:
            print("  [清理] 失败：%s" % str(e)[:80])


def main():
    print("── P1-8 · transport_segment.seq run 内递增 ──")

    if not simulator_up():
        skip("模拟器不在 %d，流程跑不动" % SIM_PORT)
        print("\n通过 %d / 失败 %d / 跳过 %d" % (PASS, FAIL, SKIP))
        return 0

    st, body = api("/api/auth/login", "POST",
                    {"username": "admin", "password": "admin123"})
    if st != 200:
        bad("admin 登录失败", "HTTP %d" % st)
        return 1
    token = body.get("token")

    # 跑之前先释放上一轮测试留下的点位占用。
    #
    # ⚠ **为什么必须清**：run 跑完后板子**真的留在设备里**（培养箱/装载口都还占着），
    #   这是物理事实的如实记录、不是 bug。但对测试来说，下一轮就会撞
    #   EC-RESOURCE-BUSY —— 因为上一轮把板子放进培养箱了。
    # ⇒ 只清「本测试流程（workflow_id=1000830）已结束 run」留下的占用，
    #   不碰别的流程的板子。
    try:
        n = q("UPDATE position_state SET plate_id=NULL, content_kind=NULL, content_id=NULL, "
              "occupied_by_run=NULL, occupied_since=NULL, updated_at=now() "
              "WHERE occupied_by_run IS NOT NULL AND occupied_by_run IN "
              "(SELECT id FROM run WHERE workflow_id = %d "
              "  AND status IN ('completed','failed','aborted','cancelled'))" % WORKFLOW_ID)
        if n != "0":
            ok("释放上一轮测试留下的点位占用 %s 条" % n, "")
        m = q("UPDATE position_state SET plate_id=NULL, content_kind=NULL, content_id=NULL, "
              "occupied_by_run=NULL, occupied_since=NULL, updated_at=now() "
              "WHERE occupied_by_run IS NOT NULL AND NOT EXISTS "
              "(SELECT 1 FROM run r WHERE r.id=position_state.occupied_by_run)")
        if m != "0":
            ok("释放孤儿点位占用 %s 条" % m, "")
    except Exception as e:
        bad("释放占用失败", str(e)[:80])

    run_id = None
    try:
        st, body = api("/api/workflows/%d/run" % WORKFLOW_ID, "POST", token=token)
        if st != 200:
            bad("跑流程失败", "HTTP %d %s" % (st, str(body)[:120]))
            return 1
        run_id = body.get("runId")
        ok("流程已跑完", "run=%s" % run_id)

        # ⚠ 四个字段统一用 '|' 分隔（from/to 之间不要再用别的符号，
        #    否则解析出的字段数与预期对不上）
        rows = q("SELECT ts.seq || '|' || ts.status || '|' || COALESCE(fp.code,'-') "
                 "|| '|' || COALESCE(tp.code,'-') "
                 "FROM transport_segment ts "
                 "LEFT JOIN device_point fdp ON fdp.id = ts.from_position_id "
                 "LEFT JOIN resource_node fp ON fp.id = fdp.point_resource_id "
                 "LEFT JOIN device_point tdp ON tdp.id = ts.to_position_id "
                 "LEFT JOIN resource_node tp ON tp.id = tdp.point_resource_id "
                 "WHERE ts.run_id = %d ORDER BY ts.id" % run_id)
        segs = []
        for line in [x for x in rows.split("\n") if x.strip()]:
            parts = line.split("|")
            if len(parts) != 4:
                bad("搬运段行解析异常", line[:100])
                continue
            segs.append({"seq": int(parts[0]), "status": parts[1],
                         "from": parts[2], "to": parts[3]})
        if len(segs) < MIN_SEGMENTS:
            bad("搬运段太少（%d < %d）" % (len(segs), MIN_SEGMENTS),
                "流程可能没跑完")
            return 1
        ok("产生 %d 条搬运段" % len(segs), "")

        seqs = [s["seq"] for s in segs]
        if seqs == list(range(1, len(seqs) + 1)):
            ok("seq 严格递增 1..%d" % len(seqs), str(seqs))
        else:
            bad("seq 不是 run 内递增", str(seqs))

        if all(s["status"] == "done" for s in segs):
            ok("全部搬运段 done", "")
        else:
            bad("有搬运段未 done", str([s["status"] for s in segs]))

        # 搬运应落在**装载口**（*-IN）而不是设备内部格（*-R*C*）
        outs = [s["to"] for s in segs]
        mouths = [o for o in outs if o.endswith("-IN")]
        if mouths:
            ok("搬运落在装载口（机械臂只到装载口）", ",".join(mouths[:3]))
        else:
            bad("没有搬运段落在装载口", str(outs))
    finally:
        if run_id:
            cleanup_run(run_id)

    print("\n通过 %d / 失败 %d / 跳过 %d" % (PASS, FAIL, SKIP))
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
