#!/usr/bin/env python3
"""P2 互锁配置完整性预检（上电/流程发布前跑一次）。

检查四类必需配置是否已落库，缺了打印「缺失」并以非 0 退出：
  1. rail_zone / transfer_rail_zone：轨道分区（路径→分区集，或 rail_zone_exemption 豁免）
  2. stirrer_dispenser_pair：搅拌—分液配对
  3. device_fact(turntable)：提取仪转盘事实（L1）
  4. device_fact / waiting_interaction：L5 等待交互投影（有交互节点时）

用法：
  source /tmp/labos-env.txt
  python3 scripts/verify-p2-interlock-config.py
"""
import os
import sys
import psycopg2

HOST = os.environ.get("LABOS_DB_HOST", "192.168.124.2")
PORT = os.environ.get("LABOS_DB_PORT", "5432")
NAME = os.environ.get("LABOS_DB_NAME", "labdb")
USER = os.environ.get("LABOS_DB_USER", "labos")
PASS = os.environ.get("LABOS_DB_PASS", os.environ.get("LABOS_DB_PASSWORD", ""))

CHECKS = [
    ("轨道分区 rail_zone", "SELECT count(*) FROM rail_zone"),
    ("路径→分区集 transfer_rail_zone", "SELECT count(*) FROM transfer_rail_zone"),
    ("分区豁免 rail_zone_exemption", "SELECT count(*) FROM rail_zone_exemption"),
    ("搅拌—分液配对 stirrer_dispenser_pair", "SELECT count(*) FROM stirrer_dispenser_pair"),
    ("设备事实 device_fact", "SELECT count(*) FROM device_fact"),
    ("有效等待投影 waiting_interaction", "SELECT count(*) FROM waiting_interaction"),
]

# 关键语义检查（不是简单 count>0）
SEMANTIC = [
    ("提取仪转盘事实(turntable)",
     "SELECT count(*) FROM device_fact WHERE fact_type='turntable'"),
]


def main():
    conn = psycopg2.connect(host=HOST, port=PORT, dbname=NAME, user=USER, password=PASS)
    conn.autocommit = True
    gaps = 0
    print("── P2 互锁配置完整性预检 ──")
    for label, sql in CHECKS + SEMANTIC:
        try:
            with conn.cursor() as cur:
                cur.execute(sql)
                n = cur.fetchone()[0]
            ok = n > 0
            print(f"  {'✓' if ok else '✗ 缺失'} {label}: {n}")
            if not ok:
                gaps += 1
        except Exception as e:  # noqa: BLE001
            print(f"  ✗ 查询失败 {label}: {e}")
            gaps += 1

    # 语义提示：有 transfer_rail_zone 配置才需要豁免兜底；全空时是「全线 fail-closed」
    print()
    if gaps:
        print(f"结论：{gaps} 项缺失。L1/L3/L4/L5 会 fail-closed（该拦的拦、不该拦的也拦）。")
        print("     请先落客户现场的分区映射/配对/事实，或对已验证路径落 rail_zone_exemption。")
    else:
        print("结论：基础配置已就绪（仍建议逐条核对真机分区覆盖完整运动包络）。")
    conn.close()
    sys.exit(1 if gaps else 0)


if __name__ == "__main__":
    main()
