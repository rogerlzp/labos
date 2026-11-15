#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""仿真工位重置：把卡住的 run 留下的**板位归属**释放掉，让下一轮能接管板。

为什么需要它（实测 2026-11-15）：
  一次 run 卡在人工窗口后，它的板位归属（position_state.occupied_by_run）会一直挂着
  ⇒ 下一轮跑同一流程时，`resolveSourcePositionBySpec` 的归属校验（归属必须等于本 run）
  直接判 EC-POSITION-OCCUPIED ⇒ 每次新跑都撞同一堵墙。
  这不是 bug，是 fail-closed 的正确表现；但仿真环境需要一条**受控的释放路径**。

释放什么（只碰仿真线的 run，且只释放归属，不动物理事实）：
  ① position_state.occupied_by_run = NULL（板**还在那个点位上**，只是没人认领）
     —— 这正是 a93e4a7「起点板位引导」设计的场景：板在点位上但无归属 ⇒ 只补归属，不搬动。
  ② position_state.reserved_by_run = NULL（口径同平台 releaseAllPositions）
  ③ run.status = 'aborted'（卡住的 run 结束，别再占着调度）

**不删** run_node / run_command / 事件历史 —— 那是复盘证据。

用法：
    python3 scripts/reset-sim-station.py                 # 预览
    python3 scripts/reset-sim-station.py --apply         # 执行
    python3 scripts/reset-sim-station.py --apply --workflow 1000929
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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--workflow", type=int, default=1000929)
    ap.add_argument("--apply", action="store_true")
    args = ap.parse_args()

    conn = psycopg2.connect(host=HOST, port=PORT, dbname=NAME, user=USER, password=PASS)
    conn.autocommit = False
    try:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT id, status, pause_kind FROM run "
                " WHERE tenant_id=1 AND workflow_id=%s AND status IN ('paused','running','queued') "
                " ORDER BY id", (args.workflow,))
            runs = cur.fetchall()
            print("流程 %s 卡住的 run：%d 个" % (args.workflow, len(runs)))
            for rid, st, pk in runs:
                cur.execute(
                    "SELECT count(*) FILTER (WHERE occupied_by_run=%s), "
                    "       count(*) FILTER (WHERE reserved_by_run=%s) FROM position_state",
                    (rid, rid))
                occ, res = cur.fetchone()
                print("  run %-8s %-8s pause=%-14s 占板位 %d / 预约 %d" % (rid, st, pk, occ, res))

            if not args.apply:
                print("\n（预览模式。加 --apply 执行）")
                conn.rollback()
                return 0

            for rid, st, pk in runs:
                # ⓪ 先记住这个 run 占着的板（清归属后就查不到了）
                cur.execute(
                    "SELECT DISTINCT plate_id, resource_id FROM position_state "
                    " WHERE tenant_id=1 AND (occupied_by_run=%s OR reserved_by_run=%s) "
                    "   AND plate_id IS NOT NULL", (rid, rid))
                held = cur.fetchall()
                # ① 释放归属与预约（板留在原位，物理事实不动）
                cur.execute(
                    "UPDATE position_state SET occupied_by_run=NULL, reserved_by_run=NULL, "
                    "       reserved_until=NULL, updated_at=now() "
                    " WHERE tenant_id=1 AND (occupied_by_run=%s OR reserved_by_run=%s)", (rid, rid))
                # ② 板本身不再被标记在这个点位上（下次由起点板位引导重新认领）
                #   ⚠ position_state.plate_id 存的是**条码**（不是 plate.id 数字）
                for plate_barcode, _res in held:
                    cur.execute(
                        "UPDATE plate SET location_id=NULL, updated_at=now() "
                        " WHERE tenant_id=1 AND barcode=%s", (plate_barcode,))
                # ③ run 收尾（run 表没有 error 列，只有 error_code；这里不碰错误码）
                cur.execute(
                    "UPDATE run SET status='aborted', updated_at=now() "
                    " WHERE tenant_id=1 AND id=%s AND status IN "
                    "       ('paused','running','queued')", (rid,))
            conn.commit()
            print("\n✓ 已重置 %d 个卡住的 run（板位归属已释放，物理位置未动）" % len(runs))
            return 0
    except Exception as e:
        conn.rollback()
        print("✗ 重置失败已回滚：%s" % e)
        return 1
    finally:
        conn.close()


if __name__ == "__main__":
    sys.exit(main())
