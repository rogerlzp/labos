#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""撤销「模拟物理布局」：只删本脚本生成的东西，绝不碰真实数据。

删什么（全部带 SIM 标记，可精确识别）：
  ① resource_node 里 code LIKE 'SIM-%' 的虚拟资源（轨道分区 + 虚拟搅拌单元）
  ② rail_zone / transfer_rail_zone 里指向这些资源的行
  ③ stirrer_dispenser_pair 里搅拌单元是这些资源的行
  ④ device_state 里 resource_id 是这些资源的行
  ⑤ robot_point 里 name LIKE 'SIM %' 且 robot_resource_id 是本岛机械臂的行
  ⑥ workflow_variable 里 name='mainPlate' 且 default_value 是模拟板号的行
  ⑦ plate 里 barcode 是模拟板号的行

**不删**：任何不是本脚本生成的数据（包括现场真实布局、基座岛的真实配置）。

用法：
    python3 scripts/clean-sim-layout.py            # 先看要删什么，确认后执行
    python3 scripts/clean-sim-layout.py --apply    # 真删
    python3 scripts/clean-sim-layout.py --apply --keep-plate   # 保留模拟板（只清布局）
"""
import argparse
import os
import sys

import psycopg2

HOST = os.environ.get("LABOS_DB_HOST", "192.168.124.2")
PORT = os.environ.get("LABOS_DB_PORT", "5432")
NAME = os.environ.get("LABOS_DB_NAME", "labdb")
USER = os.environ.get("LABOS_DB_USER", "labos")
PASS = os.environ.get("LABOS_DB_PASS", os.environ.get("LABOS_DB_PASSWORD", ""))
SIM_PREFIX = "SIM-"
PLATE_PREFIX = "SIM-PLATE-"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true", help="真删（默认只预览）")
    ap.add_argument("--keep-plate", action="store_true", help="保留模拟板与流程变量")
    args = ap.parse_args()

    conn = psycopg2.connect(host=HOST, port=PORT, dbname=NAME, user=USER, password=PASS)
    conn.autocommit = False
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT id, code FROM resource_node WHERE tenant_id=1 AND code LIKE %s",
                        (SIM_PREFIX + "%",))
            sim_res = cur.fetchall()
            ids = [r[0] for r in sim_res]
            print("SIM 虚拟资源 %d 个：%s"
                  % (len(ids), ", ".join(r[1] for r in sim_res) or "（无）"))
            if not ids:
                print("没有可清理的模拟布局。")
                conn.rollback()
                return 0

            # 统计各表将被删的行数
            plan = []
            cur.execute("SELECT count(*) FROM rail_zone WHERE resource_id = ANY(%s)", (ids,))
            plan.append(("rail_zone", cur.fetchone()[0]))
            cur.execute("SELECT count(*) FROM transfer_rail_zone WHERE zone_resource_id = ANY(%s)", (ids,))
            plan.append(("transfer_rail_zone", cur.fetchone()[0]))
            cur.execute("SELECT count(*) FROM stirrer_dispenser_pair WHERE stirrer_resource_id = ANY(%s)", (ids,))
            plan.append(("stirrer_dispenser_pair", cur.fetchone()[0]))
            cur.execute("SELECT count(*) FROM device_state WHERE resource_id = ANY(%s)", (ids,))
            plan.append(("device_state", cur.fetchone()[0]))
            cur.execute("SELECT count(*) FROM robot_point WHERE name LIKE 'SIM %' "
                        "AND robot_resource_id IN (SELECT id FROM resource_node WHERE code='ARM1')")
            plan.append(("robot_point", cur.fetchone()[0]))
            if not args.keep_plate:
                cur.execute("SELECT count(*) FROM plate WHERE barcode LIKE %s", (PLATE_PREFIX + "%",))
                plan.append(("plate", cur.fetchone()[0]))
                cur.execute("SELECT count(*) FROM workflow_variable WHERE name='mainPlate' "
                            "AND default_value LIKE %s", (PLATE_PREFIX + "%",))
                plan.append(("workflow_variable", cur.fetchone()[0]))
            print("\n将要删除：")
            for t, n in plan:
                print("  %-26s %d 行" % (t, n))

            if not args.apply:
                print("\n（预览模式。要真删请加 --apply）")
                conn.rollback()
                return 0

            cur.execute("DELETE FROM transfer_rail_zone WHERE zone_resource_id = ANY(%s)", (ids,))
            cur.execute("DELETE FROM rail_zone WHERE resource_id = ANY(%s)", (ids,))
            cur.execute("DELETE FROM stirrer_dispenser_pair WHERE stirrer_resource_id = ANY(%s)", (ids,))
            cur.execute("DELETE FROM device_state WHERE resource_id = ANY(%s)", (ids,))
            cur.execute("DELETE FROM robot_point WHERE name LIKE 'SIM %' "
                        "AND robot_resource_id IN (SELECT id FROM resource_node WHERE code='ARM1')")
            cur.execute("DELETE FROM resource_node WHERE id = ANY(%s)", (ids,))
            if not args.keep_plate:
                cur.execute("DELETE FROM workflow_variable WHERE name='mainPlate' "
                            "AND default_value LIKE %s", (PLATE_PREFIX + "%",))
                cur.execute("DELETE FROM plate WHERE barcode LIKE %s", (PLATE_PREFIX + "%",))
            conn.commit()
            print("\n✓ 模拟布局已撤销（真实数据未动）")
            return 0
    except Exception as e:
        conn.rollback()
        print("✗ 清理失败已回滚：%s" % e)
        return 1
    finally:
        conn.close()


if __name__ == "__main__":
    sys.exit(main())
