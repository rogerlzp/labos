#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""整岛导出 / 导入验收（真 API + 真库，跑完自清理临时岛）。

验四件事：
  ① 导出：岛 + 设备 + 点位 + 点位方法 + 示教，**全部业务码，没有本环境 id**；
  ② dryRun：只统计不写库（先看"会建什么"）；
  ③ 同岛重导：按 code 幂等（设备跳过、点位 upsert、授权不重复）；
  ④ 新岛最小包：真建岛 + 建设备 + 建点位，点位可查；负例 kind 不对 → 400。

⚠ 同岛导入 82 个点位 + 122 条授权 + 78 个示教 ≈ 20s（远程库每条语句 ~70ms）。
   不要用 30s 超时的 HTTP 客户端跑它。

用法：
    LABOS_DB_HOST=... LABOS_DB_USER=... LABOS_DB_PASS=... LABOS_DB_NAME=... \\
      python3 scripts/verify-island-io.py
"""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

API = os.environ.get("LABOS_API", "http://127.0.0.1:18080").rstrip("/")
USER = os.environ.get("LABOS_USER", "admin")
PWD = os.environ.get("LABOS_PASS", "admin123")
ISLAND = int(os.environ.get("LABOS_ISLAND", "1000505"))
TIMEOUT = int(os.environ.get("LABOS_TIMEOUT", "180"))

DB = {
    "host": os.environ.get("LABOS_DB_HOST", ""),
    "port": os.environ.get("LABOS_DB_PORT", "5432"),
    "user": os.environ.get("LABOS_DB_USER", ""),
    "name": os.environ.get("LABOS_DB_NAME", ""),
    "pass": os.environ.get("LABOS_DB_PASS", ""),
}


def call(path, method="GET", body=None, token=None):
    req = urllib.request.Request(API + path, method=method)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, data=data, timeout=TIMEOUT) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read() or b"{}")
        except Exception:
            return e.code, {}


def check(cond, msg, detail=""):
    print(("  ✔ " if cond else "  ✘ ") + msg + ("" if cond else f" — {detail}"))
    if not cond:
        raise AssertionError(msg)


def psql(sql):
    if not (DB["host"] and DB["user"] and DB["name"]):
        return None
    env = dict(os.environ)
    env["PGPASSWORD"] = DB["pass"]
    p = subprocess.run(["psql", "-h", DB["host"], "-p", DB["port"], "-U", DB["user"],
                        "-d", DB["name"], "-tAc", sql],
                       env=env, capture_output=True, text=True)
    return p.stdout.strip() if p.returncode == 0 else None


def cleanup_temp_island(prefix):
    """删掉本次导入出来的临时岛（按外键顺序；只删这个前缀，绝不碰真实岛）。"""
    if psql("SELECT 1") is None:
        print("  ⚠ 未提供 LABOS_DB_*，跳过临时岛清理；请手工删 code like '{}%'".format(prefix))
        return
    sql = f"""
    DELETE FROM position_state WHERE resource_id IN (
      SELECT id FROM resource_node WHERE code LIKE '{prefix}%' AND kind <> 'device');
    DELETE FROM device_point WHERE point_resource_id IN (
      SELECT id FROM resource_node WHERE code LIKE '{prefix}%' AND kind <> 'device')
      OR device_resource_id IN (
      SELECT id FROM resource_node WHERE code LIKE '{prefix}%' AND kind = 'device');
    DELETE FROM device_instance WHERE resource_id IN (
      SELECT id FROM resource_node WHERE code LIKE '{prefix}%' AND kind = 'device');
    DELETE FROM device WHERE code LIKE '{prefix}%';
    DELETE FROM robot_point WHERE island_id IN (
      SELECT id FROM resource_node WHERE code LIKE '{prefix}%' AND kind = 'island');
    DELETE FROM resource_node WHERE code LIKE '{prefix}%';
    SELECT count(*) FROM resource_node WHERE code LIKE '{prefix}%';
    """
    out = psql(sql)
    # psql -tAc 跑多语句时会把每条的结果都打出来（DELETE 0 / ... / 0）——
    # 取最后一行才是最终 count。
    left = (out or "").strip().splitlines()[-1].strip() if out else "?"
    check(left == "0", "临时岛已清理", out)


def main():
    token = call("/api/auth/login", "POST", {"username": USER, "password": PWD})[1]["token"]

    # ---- ① 导出 ----
    t0 = time.time()
    st, exp = call(f"/api/islands/{ISLAND}/export", token=token)
    check(st == 200 and exp.get("kind") == "labos.island", "导出成功且带 kind", exp)
    print(f"   导出耗时 {time.time() - t0:.1f}s：devices={len(exp['devices'])} "
          f"points={len(exp['points'])} methods={len(exp['pointMethods'])} "
          f"robotPoints={len(exp['robotPoints'])}")
    check(len(exp["devices"]) > 0, "设备非空")
    check(len(exp["points"]) > 0, "点位非空")
    check(len(exp["pointMethods"]) > 0, "点位方法非空")
    check(len(exp["robotPoints"]) > 0, "示教点非空")
    blob = json.dumps(exp, ensure_ascii=False)
    for bad in ('"resourceId"', '"pointId"', '"islandId"', '"methodId"'):
        check(bad not in blob, f"导出不含本环境字段 {bad}")

    # ---- ② dryRun ----
    st, dry = call("/api/islands/import?dryRun=1", "POST", exp, token=token)
    check(st == 200 and dry.get("dryRun"), "dryRun 成功", dry)
    check(dry["devicesSkipped"] == len(exp["devices"]), "dryRun：设备全部已存在（同岛）", dry)
    check(dry["pointsUpserted"] == len(exp["points"]), "dryRun：点位数量一致", dry)

    # ---- ③ 同岛重导（幂等） ----
    t0 = time.time()
    st, imp = call("/api/islands/import", "POST", exp, token=token)
    print(f"   同岛导入耗时 {time.time() - t0:.1f}s")
    check(st == 200 and not imp.get("problems"), "同岛重导无 problems", imp.get("problems"))
    check(imp["devicesSkipped"] == len(exp["devices"]), "设备按 code 跳过", imp)
    check(imp["pointsUpserted"] == len(exp["points"]), "点位 upsert", imp)

    # ---- ④ 新岛最小包 ----
    ts = int(time.time())
    island_code = f"LAB-TMP-{ts}"
    dev_code = f"TMPDEV-{ts}"
    mini = {
        "kind": "labos.island", "version": 1,
        "island": {"code": island_code, "name": "临时导入岛"},
        "devices": [{"code": dev_code, "name": "临时设备", "deviceType": "MISC",
                     "capability": "WASH", "ip": "127.0.0.1"}],
        "points": [
            {"deviceCode": dev_code, "pointCode": f"{dev_code}-P1", "nodeKind": "storage",
             "pointKind": "nest", "pointRole": "both", "robotReachable": True,
             "positionLabel": "P1", "orderInDevice": 1},
            {"deviceCode": dev_code, "pointCode": f"{dev_code}-P2", "nodeKind": "storage",
             "pointKind": "nest", "pointRole": "both", "robotReachable": True,
             "positionLabel": "P2", "orderInDevice": 2},
        ],
        "pointMethods": [], "robotPoints": [],
    }
    try:
        st, m = call("/api/islands/import", "POST", mini, token=token)
        check(st == 200 and not m.get("problems"), "新岛导入成功", m)
        check(m["devicesCreated"] == 1 and m["pointsUpserted"] == 2,
              "新建设备 1 / 点位 2", m)
        pos = call(f"/api/positions?islandId={m['islandId']}", token=token)[1].get("items", [])
        codes = {p["code"] for p in pos}
        check(codes == {f"{dev_code}-P1", f"{dev_code}-P2"}, "新岛点位可查", codes)

        # 负例：kind 不对
        st, r = call("/api/islands/import", "POST", {"kind": "nope"}, token=token)
        check(st == 400, "非整岛包 → 400", r)
        print("\nPASS 整岛导出 / 导入")
    finally:
        cleanup_temp_island("LAB-TMP-")
        cleanup_temp_island("TMPDEV-")


if __name__ == "__main__":
    main()
