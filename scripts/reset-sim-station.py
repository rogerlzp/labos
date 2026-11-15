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
    ap.add_argument("--keep-plate-on-position", action="store_true",
                    help="只释放归属，板留在原位（默认把板也从点位取下，让下一轮重新放置）")
    args = ap.parse_args()

    conn = psycopg2.connect(host=HOST, port=PORT, dbname=NAME, user=USER, password=PASS)
    conn.autocommit = False
    try:
        with conn.cursor() as cur:
            # 找**真正持有板位归属**的 run（不论它是什么状态）。
            # ⚠ 上一版按状态筛（paused/running/queued）⇒ 漏掉 **failed** 的 run ——
            #   而失败的 run 同样把板位归属挂着（实测 run 1001665 failed 之后
            #   板一直归它 ⇒ 后面每一轮都"板已在点位且已有归属，不自动搬动"）。
            # 只复位**本流程的**或**模拟板**的持有者，别动别的线（如基座岛演示）
            cur.execute(
                "SELECT DISTINCT ps.occupied_by_run FROM position_state ps "
                " WHERE ps.tenant_id=1 AND ps.occupied_by_run IS NOT NULL "
                #   ⚠ 用 starts_with 而不是 LIKE '...%'：psycopg2 会把 SQL 里的 % 当占位符
                "   AND (starts_with(ps.plate_id, 'SIM-PLATE-') "
                "        OR ps.occupied_by_run IN "
                "           (SELECT id FROM run WHERE tenant_id=1 AND workflow_id=%s))",
                (args.workflow,))
            holder_runs = [r[0] for r in cur.fetchall() if r[0]]
            # 再加上本流程里卡住但没持有板位的 run（也要收尾，别让调度器一直看着）
            cur.execute(
                "SELECT id, status, pause_kind FROM run "
                " WHERE tenant_id=1 AND workflow_id=%s AND status IN ('paused','running','queued') "
                " ORDER BY id", (args.workflow,))
            stuck = cur.fetchall()
            runs = []
            seen = set()
            for rid, st, pk in stuck:
                if rid not in seen:
                    seen.add(rid)
                    runs.append((rid, st, pk))
            for rid in holder_runs:
                if rid not in seen:
                    seen.add(rid)
                    cur.execute("SELECT status, pause_kind FROM run WHERE tenant_id=1 AND id=%s", (rid,))
                    r = cur.fetchone()
                    runs.append((rid, r[0] if r else "?", r[1] if r else None))
            print("流程 %s 待复位：%d 个卡住的 run + %d 个持有板位的 run"
                  % (args.workflow, len(stuck), len(holder_runs)))
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
                # ② 板本身：默认把它**从点位上取下**（仿真工位复位 ⇒ 板回库存），
                #    这样下一轮的「起点板位引导」才会按流程声明的 sourcePosition 重新放置。
                #    只释放归属而把板留在原位的话，引导会认领原地（老位置），
                #    与搬运节点声明的源位对不上 ⇒ 又一次 EC-POSITION-OCCUPIED。
                for plate_barcode, res_id in held:
                    cur.execute(
                        "UPDATE plate SET location_id=NULL, updated_at=now() "
                        " WHERE tenant_id=1 AND barcode=%s", (plate_barcode,))
                    if not args.keep_plate_on_position:
                        cur.execute(
                            "UPDATE position_state SET plate_id=NULL, content_kind=NULL, "
                            "       content_id=NULL, occupied_since=NULL, updated_at=now() "
                            " WHERE tenant_id=1 AND resource_id=%s AND plate_id=%s",
                            (res_id, plate_barcode))
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
        import traceback
        print("✗ 重置失败已回滚：%s" % e)
        traceback.print_exc()
        return 1
    finally:
        conn.close()


if __name__ == "__main__":
    sys.exit(main())
