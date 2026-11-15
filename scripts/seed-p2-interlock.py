#!/usr/bin/env python3
"""P2 互锁配置种子脚本（客户填好 JSON 后运行）。

职责：
  1. 校验配置（分区资源/配对设备必须已存在于 resource_node，或自动建虚拟分区资源）
  2. 幂等落库（rail_zone / transfer_rail_zone / rail_zone_exemption / stirrer_dispenser_pair）
  3. 落库后跑一次完整性预检

用法：
  source /tmp/labos-env.txt
  python3 scripts/seed-p2-interlock.py scripts/p2-interlock-config.example.json

注意：
  - device_fact(turntable) 和 waiting_interaction 是运行时数据（设备/侧车上报），
    不由本脚本落库；本脚本只校验「哪些设备需要上报转盘事实」有没有在配置里标注。
  - path_key 用「源资源id->目标资源id」；精确点位优先（sourcePos->targetPos），
    退设备级（srcDev->dstDev）。
"""
import json
import os
import sys

import psycopg2

HOST = os.environ.get("LABOS_DB_HOST", "192.168.124.2")
PORT = os.environ.get("LABOS_DB_PORT", "5432")
NAME = os.environ.get("LABOS_DB_NAME", "labdb")
USER = os.environ.get("LABOS_DB_USER", "labos")
PASS = os.environ.get("LABOS_DB_PASS", os.environ.get("LABOS_DB_PASSWORD", ""))


def main(path):
    cfg = json.load(open(path, encoding="utf-8"))
    tenant = cfg["tenant_id"]
    conn = psycopg2.connect(host=HOST, port=PORT, dbname=NAME, user=USER, password=PASS)
    conn.autocommit = False
    errors = 0
    try:
        with conn.cursor() as cur:
            # 1. 轨道分区（虚拟互斥资源：不存在则自动建 resource_node kind=virtual）
            for z in cfg.get("rail_zones", []):
                code, rid = z["code"], z["resource_id"]
                name = z.get("name") or code
                cur.execute(
                    "INSERT INTO resource_node (id, tenant_id, code, name, kind) "
                    "VALUES (%s, %s, %s, %s, 'virtual') "
                    "ON CONFLICT (id) DO NOTHING", (rid, tenant, code, name))
                cur.execute(
                    "INSERT INTO rail_zone (tenant_id, zone_code, resource_id) "
                    "VALUES (%s, %s, %s) ON CONFLICT (tenant_id, zone_code) DO NOTHING",
                    (tenant, code, rid))
            zone_ids = {z["resource_id"] for z in cfg.get("rail_zones", [])}

            # 2. 路径→分区集
            for t in cfg.get("transfer_rail_zones", []):
                path_key, zones = t["path_key"], t["zones"]
                unknown = [x for x in zones if x not in zone_ids]
                if unknown:
                    print(f"  ✗ {path_key} 引用未定义的分区资源 {unknown}")
                    errors += 1
                    continue
                for seq, rid in enumerate(zones, 1):
                    cur.execute(
                        "INSERT INTO transfer_rail_zone (tenant_id, path_key, zone_resource_id, seq) "
                        "VALUES (%s, %s, %s, %s) "
                        "ON CONFLICT (tenant_id, path_key, zone_resource_id) DO NOTHING",
                        (tenant, path_key, rid, seq))

            # 3. 路径豁免
            for e in cfg.get("rail_zone_exemptions", []):
                cur.execute(
                    "INSERT INTO rail_zone_exemption (tenant_id, path_key, reason, operator) "
                    "VALUES (%s, %s, %s, %s) "
                    "ON CONFLICT (tenant_id, path_key) DO NOTHING",
                    (tenant, e["path_key"], e.get("reason"), e.get("operator")))

            # 4. 搅拌—分液配对（校验配对设备存在）
            for p in cfg.get("stirrer_dispenser_pairs", []):
                s, d = p["stirrer_resource_id"], p["dispenser_resource_id"]
                for rid, label in ((s, "搅拌"), (d, "分液器")):
                    cur.execute("SELECT 1 FROM resource_node WHERE tenant_id=%s AND id=%s",
                                (tenant, rid))
                    if cur.fetchone() is None:
                        print(f"  ✗ {label} resource {rid} 不在 resource_node")
                        errors += 1
                if errors:
                    continue
                cur.execute(
                    "INSERT INTO stirrer_dispenser_pair "
                    "(tenant_id, stirrer_resource_id, dispenser_resource_id) "
                    "VALUES (%s, %s, %s) "
                    "ON CONFLICT (tenant_id, dispenser_resource_id) DO NOTHING",
                    (tenant, s, d))

        if errors:
            conn.rollback()
            print(f"校验失败 {errors} 项，未落库。")
            sys.exit(1)
        conn.commit()
        print("种子数据已落库（幂等）。")
    except Exception as e:  # noqa: BLE001
        conn.rollback()
        print(f"落库失败：{e}")
        sys.exit(1)
    finally:
        conn.close()

    # 5. 落库后预检
    print()
    os.system(f"python3 {os.path.join(os.path.dirname(__file__), 'verify-p2-interlock-config.py')}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    main(sys.argv[1])
