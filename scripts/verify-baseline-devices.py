#!/usr/bin/env python3
# ============================================================================
# 基线设备三方对齐校验：**中控清单 / Redis / device_state 必须说同一件事**。
#
# 为什么这是独立的一段：
#   在线状态改成了「tcp client → Redis → 中控读」之后，链路上多了两环
#   （Redis key、设备ID 口径）。任何一环对不上，表现都是同一句
#   「设备明明在跑，页面显示离线」，但根因完全不同：
#     · Redis 里没有这条 key      ⇒ tcp client 没起来 / key 过期了
#     · key 的设备ID 对不上        ⇒ 三端口径没统一（写 key 的人认的不是同一台设备）
#     · key 的 IP/端口 对不上       ⇒ 清单里的设备IP 变了，tcp client 还按老的上报
#     · device_state 与 Redis 不一致 ⇒ 内核没刷新 / 设备被停用了
#   混在一起排查很慢，所以把它做成一条一条能定位的断言。
#
# 用法：python3 scripts/verify-baseline-devices.py
# ============================================================================
import json
import os
import subprocess
import sys
import urllib.request

BASE = os.environ.get("LABOS_API", "http://127.0.0.1:18080")
REDIS_HOST = os.environ.get("LABOS_REDIS_HOST", "127.0.0.1")
REDIS_PORT = os.environ.get("LABOS_REDIS_PORT", "6379")

PASS, FAIL = 0, 0


def ok(msg):
    global PASS
    PASS += 1
    print("  \033[32m✓\033[0m " + msg)


def bad(msg, detail=""):
    global FAIL
    FAIL += 1
    print("  \033[31m✗\033[0m " + msg + (("（%s）" % detail) if detail else ""))


def q(sql):
    """跑一条 SQL；**失败必须抛错**（空串会被当成"库里没有" ⇒ 假绿）。"""
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


def redis_keys():
    out = subprocess.run(
        ["redis-cli", "-h", REDIS_HOST, "-p", REDIS_PORT, "--scan", "--pattern", "labos:link:*"],
        capture_output=True, text=True, timeout=15).stdout
    return [k for k in out.split("\n") if k.strip()]


def registry():
    """中控的设备清单（免鉴权只读接口）—— 清单真源。"""
    with urllib.request.urlopen(BASE + "/api/devices/registry", timeout=15) as r:
        return json.loads(r.read().decode())


def main():
    print("── 基线设备三方对齐 ──")

    items = registry().get("items") or []
    if not items:
        bad("中控清单里没有设备", "清单接口返回空")
        print("\n通过 %d / 失败 %d" % (PASS, FAIL))
        return 1
    ok("中控清单有 %d 台设备" % len(items))

    # 期望的 Redis key（清单给出的设备ID + 设备IP + 端口）
    expect = {}
    for it in items:
        key = "labos:link:%s:%s:%s" % (it["deviceId"], it["ip"], it["port"])
        expect[key] = it
    ok("按清单算出 %d 个期望 Redis key" % len(expect))

    actual = set(redis_keys())

    # ① 每台设备都要有 key（缺 ⇒ 内核判它离线）
    missing = [k for k in expect if k not in actual]
    if missing:
        for k in missing:
            bad("设备 %s 没有 Redis 在线状态" % expect[k]["code"], k)
    else:
        ok("每台设备都有 Redis 在线状态")

    # ② 多余的 key（指向清单里没有的设备 ⇒ 脏数据 / 设备已停用但 tcp client 还在）
    extra = actual - set(expect)
    if extra:
        for k in sorted(extra):
            bad("有多余的 Redis key（清单里没有这台设备）", k)
    else:
        ok("没有多余的 Redis key")

    # ③ device_state.online 与 Redis 一致
    rows = q("""
        SELECT di.id || '|' || di.code || '|' ||
               CASE WHEN ds.online THEN 't' ELSE 'f' END || '|' ||
               coalesce(ds.last_heartbeat::text, '')
          FROM device_instance di
          LEFT JOIN device_state ds
            ON ds.resource_id = di.resource_id AND ds.tenant_id = di.tenant_id
         WHERE di.tenant_id = 1 AND di.deleted_at IS NULL AND di.status = 'available'
         ORDER BY di.id""").split("\n")
    online_cnt = 0
    mismatch = []
    for line in rows:
        if not line.strip():
            continue
        parts = line.split("|")
        did, code = parts[0], parts[1]
        online = parts[2] if len(parts) > 2 else ""
        if online == "t":
            online_cnt += 1
        # Redis 说有 key ⇒ device_state 应该 online
        # 注意 deviceId 的类型：清单里是 JSON **数字**，SQL 出来是**字符串** ⇒
        # 必须转成字符串再比（直接比永远 False ⇒ 断言形同虚设，还显示"在线 0 台"）。
        key = [k for k in expect if str(expect[k]["deviceId"]) == did]
        has_key = bool(key) and key[0] in actual
        if has_key and online != "t":
            mismatch.append("%s：Redis 在线但 device_state 离线" % code)
        if not has_key and online == "t":
            mismatch.append("%s：device_state 在线但 Redis 没记录" % code)
    if mismatch:
        for m in mismatch:
            bad(m)
    else:
        ok("device_state.online 与 Redis 完全一致")

    # ④ 在役设备必须都有设备IP（口径三要素之一，空了就没法拼 key）
    noip = [it["code"] for it in items if not it.get("ip")]
    if noip:
        for c in noip:
            bad("设备 %s 没填设备IP" % c)
    else:
        ok("每台设备都填了设备IP")

    print("\n通过 %d / 失败 %d（在役 %d 台，其中在线 %d 台）"
          % (PASS, FAIL, len(rows), online_cnt))
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
