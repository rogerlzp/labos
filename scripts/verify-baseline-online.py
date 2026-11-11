#!/usr/bin/env python3
# ============================================================================
# 基线用例 · 上线/下线联动：设备能不能被"下线"，下线后中控认不认。
#
# 为什么这条必须进基线：
#   在线状态改成「tcp client → Redis → 中控读」之后，"设备在不在"变成了一条
#   **跨进程的链**：管理台下线的动作 ⇒ tcp client 进程停 ⇒ Redis key 清 ⇒
#   内核读到没有 ⇒ 判离线。任何一环断了，现象都是"点了下线，设备还在线"。
#   这条用例把整条链走一遍。
#
# 断言：
#   ① 下线后 Redis 里那条 key 立刻消失（不是等 TTL）
#   ② 下线后内核把 device_state.online 写成 false（心跳读 Redis 得来的）
#   ③ 下线期间跑用到这台设备的流程**不会假成功**（必须失败/卡住，不是 completed）
#   ④ 重新上线后 key 回来、online 恢复 true、流程又能跑通
#
# 用法：python3 scripts/verify-baseline-online.py
# ============================================================================
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

BASE = os.environ.get("LABOS_API", "http://127.0.0.1:18080")
MANAGER = os.environ.get("LABOS_MANAGER_URL", "http://127.0.0.1:8600")
USER = os.environ.get("LABOS_USER", "admin")
PWD = os.environ.get("LABOS_PASS", "admin123")
ISLAND = os.environ.get("LABOS_BASELINE_ISLAND", "LAB-BASE-01")

PASS, FAIL = 0, 0


def ok(msg):
    global PASS
    PASS += 1
    print("  \033[32m✓\033[0m " + msg)


def bad(msg, detail=""):
    global FAIL
    FAIL += 1
    print("  \033[31m✗\033[0m " + msg + (("（%s）" % detail) if detail else ""))


def api(path, method="GET", body=None, token=None, raw=None, timeout=60, base=None):
    req = urllib.request.Request((base or BASE) + path, method=method)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = raw.encode() if raw is not None else (
        json.dumps(body).encode() if body is not None else None)
    if raw is not None:
        req.add_header("Content-Type", "application/xml")
    elif data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, data=data, timeout=timeout) as r:
            txt = r.read().decode()
            try:
                return json.loads(txt)
            except json.JSONDecodeError:
                return txt
    except urllib.error.HTTPError as e:
        raise RuntimeError("%s %s → HTTP %d: %s"
                           % (method, path, e.code, e.read().decode()[:200]))


def q(sql):
    env = dict(os.environ)
    env.setdefault("PATH", "/opt/homebrew/opt/libpq/bin:/usr/bin:/bin")
    env.setdefault("PGPASSWORD", os.environ.get("LABOS_DB_PASS", ""))
    psql = os.environ.get("PSQL", "psql")
    out = subprocess.run(
        [psql, "-h", os.environ["LABOS_DB_HOST"], "-U", os.environ["LABOS_DB_USER"],
         "-d", os.environ["LABOS_DB_NAME"], "-tAc", sql],
        env=env, capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError("SQL 失败：%s\n%s" % (sql.strip()[:200], out.stderr.strip()[:300]))
    return out.stdout.strip()


def redis_has_key(key):
    out = subprocess.run(
        ["redis-cli", "-h", os.environ.get("LABOS_REDIS_HOST", "127.0.0.1"),
         "-p", os.environ.get("LABOS_REDIS_PORT", "6379"), "EXISTS", key],
        capture_output=True, text=True, timeout=10).stdout.strip()
    return out == "1"


def wait_key(key, want, budget=25):
    """等 Redis 里的 key 出现/消失。

    **必须等，不能立刻查**：tcp client 每 10 秒才刷新一次 key（TTL 30 秒），
    刚上线的设备要等它第一轮刷新才写进 Redis ——
    立刻查会得到"key 不存在"，把"上线成功"误判成"没上线"。
    """
    deadline = time.time() + budget
    while time.time() < deadline:
        if redis_has_key(key) == want:
            return True
        time.sleep(1)
    return False


def wait_online(rid, want_online, budget=40):
    """等内核把某台设备的 online 刷成期望值。"""
    deadline = time.time() + budget
    while time.time() < deadline:
        v = q("SELECT CASE WHEN ds.online THEN 't' ELSE 'f' END "
              "FROM device_instance di JOIN device_state ds "
              "ON ds.resource_id = di.resource_id AND ds.tenant_id = di.tenant_id "
              "WHERE di.id = %d" % rid)
        if v == ("t" if want_online else "f"):
            return True
        time.sleep(2)
    return False


def run_workflow_with_robot(token, robot_res):
    """跑一条只用机器人的流程；返回 run 状态。"""
    graph = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
  id="Def_on" targetNamespace="http://bpmn.io/schema/bpmn">
  <bpmn:process id="Proc_on" isExecutable="false">
    <bpmn:startEvent id="S" name="开始"/>
    <bpmn:task id="T1" name="归位" protocolName="Home" resource="%s"/>
    <bpmn:endEvent id="E" name="结束"/>
    <bpmn:sequenceFlow id="f1" sourceRef="S" targetRef="T1"/>
    <bpmn:sequenceFlow id="f2" sourceRef="T1" targetRef="E"/>
  </bpmn:process>
</bpmn:definitions>""" % robot_res
    wf = api("/api/workflows", "POST", {"name": "基线·上线联动检查"}, token)["id"]
    api("/api/workflows/%d/graph" % wf, "PUT", raw=graph, token=token)
    r = api("/api/workflows/%d/run?async=1" % wf, "POST", {}, token)
    rid = r.get("runId") or int(q("SELECT max(id) FROM run"))
    deadline = time.time() + 90
    while time.time() < deadline:
        st = q("SELECT status FROM run WHERE id=%d" % rid)
        if st in ("completed", "failed", "aborted", "paused"):
            return rid, st
        time.sleep(2)
    return rid, q("SELECT status FROM run WHERE id=%d" % rid)


def main():
    print("── 基线上线/下线联动 ──")
    token = api("/api/auth/login", "POST",
                {"username": USER, "password": PWD})["token"]

    dev = q("SELECT di.id || '|' || di.ip || '|' || dr.endpoint "
            "FROM device_instance di JOIN driver dr ON dr.id = di.driver_id "
            "JOIN resource_node isl ON isl.id = (SELECT parent_id FROM resource_node "
            "  WHERE id = di.resource_id) "
            "WHERE isl.code = '%s' AND di.code = 'X231' AND di.deleted_at IS NULL "
            "AND di.status = 'available'" % ISLAND)
    if not dev:
        bad("基线岛里找不到 X231")
        print("\n通过 %d / 失败 %d" % (PASS, FAIL))
        return 1
    did, dip, endpoint = dev.split("|", 2)
    did = int(did)          # 后面要塞进 SQL / URL，统一成整数
    port = endpoint.rsplit(":", 1)[-1]
    key = "labos:link:%s:%s:%s" % (did, dip, port)
    robot_res = q("SELECT resource_id FROM device_instance WHERE id=%s" % did)
    print("  目标设备：X231（设备ID %s）key=%s" % (did, key))

    # ---- ① 先确保设备是在线的 ----
    # 不先确保的话，"下线后 key 消失"这条断言会在设备本来就离线时**假通过**。
    try:
        api("/api/devices/%s/start" % did, "POST", {}, base=MANAGER)
    except RuntimeError:
        pass   # "已经在跑了" 也算成功
    if not wait_key(key, True, 25):
        bad("测试开始前设备就不在线（Redis 里没有 key）", key)
        print("\n通过 %d / 失败 %d" % (PASS, FAIL))
        return 1
    ok("测试开始前设备在线")
    if wait_online(did, True, 40):
        ok("内核已判在线")
    else:
        bad("内核没判在线")

    # ---- ② 下线 ----
    try:
        api("/api/devices/%s/stop" % did, "POST", {}, base=MANAGER)
        ok("管理台下线动作已发出")
    except RuntimeError as e:
        bad("管理台下线失败", str(e)[:150])

    if not wait_key(key, False, 10):
        bad("下线后 Redis key 还在（要点掉，不是等 TTL）", key)
    else:
        ok("下线后 Redis key 立即消失")

    if wait_online(did, False, 40):
        ok("内核已把这台设备判为离线")
    else:
        bad("内核没判离线（device_state.online 仍是 true）")

    # ---- ② 离线期间跑流程：不能假成功 ----
    rid, st = run_workflow_with_robot(token, robot_res)
    print("  离线期间跑流程：run #%d 状态=%s" % (rid, st))
    if st != "completed":
        ok("设备离线时流程没有假成功（状态=%s）" % st)
    else:
        bad("设备离线，流程却 completed —— 拿不到设备也当成功")

    # ---- ③ 重新上线 ----
    try:
        api("/api/devices/%s/start" % did, "POST", {}, base=MANAGER)
        ok("管理台上线动作已发出")
    except RuntimeError as e:
        bad("管理台上线失败", str(e)[:150])

    if wait_key(key, True, 25):
        ok("上线后 Redis key 回来了")
    else:
        bad("上线后 Redis key 没回来", key)

    if wait_online(did, True, 40):
        ok("内核已恢复判在线")
    else:
        bad("内核没恢复在线")

    rid, st = run_workflow_with_robot(token, robot_res)
    print("  恢复后跑流程：run #%d 状态=%s" % (rid, st))
    if st == "completed":
        ok("设备恢复后流程又能跑通")
    else:
        bad("设备恢复后流程还是跑不通", st)

    print("\n通过 %d / 失败 %d" % (PASS, FAIL))
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
