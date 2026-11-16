#!/usr/bin/env python3
"""
qPCR 96→384 worklist 仿真器（i8 独立八通道）。

从 stdin 读一份 JSON：
{
  "sourceBarcode": "Sample_96_01",
  "targetBarcode": "qPCR_384_01",
  "sampleCount": 20,
  "masterMixVol": 15,
  "sampleVol": 5,
  "transfers": [
    {"sampleIdx":1,"sourceWell":"A1","targetWell":"A1","targetName":"GAPDH","dye":"VIC", ...}
  ]
}

输出 JSON 报告到 stdout：
{
  "mode":"qpcr-sim", "status":"PASS|FAIL",
  "channels":8, "batchSize":8, "batches":N,
  "commands": [...],            # 实际下发的 PyLabRobot 命令（审计用）
  "volumes_src": {...}, "volumes_dst": {...},
  "comparisons": [{"targetWell","expected","simulated","delta","result"}],
  "summary": {"match":N,"mismatch":N,"total":N}
}

为什么要独立脚本而不是改 run_simulator.py：
  - run_simulator.py 是通用设备契约仿真（单通道、96 板），已被多条验收链依赖；
  - qPCR 是**列并行**（i8 一次 8 孔）+ 384 板，物理模型不同，混进去会让现有
    自测/差分校验的板位假设失效。
  两者都基于 PyLabRobot，同一套 volume_tracker，报告口径一致。

i8 批次策略：把 transfers 按 (targetWell 列号, replicate) 分组。
  同一列内的孔按行排序，每 8 个一批 → 一次 aspirate(8 wells) + dispense(8 wells)，
  正好对应 Hamilton i8 独立八通道按列并行。源孔按 sampleIdx 映射到 96 板。
"""

from __future__ import annotations

import json
import sys
from collections import defaultdict
from datetime import datetime

from pylabrobot.liquid_handling import LiquidHandler
from pylabrobot.liquid_handling.backends import LiquidHandlerChatterboxBackend
from pylabrobot.resources import (
    STARDeck,
    Coordinate,
    biorad_384_wellplate_50uL_Vb,
    cor_96_wellplate_360uL_Fb,
    hamilton_96_tiprack_300uL,
)
from pylabrobot.resources.tip_tracker import set_tip_tracking
from pylabrobot.resources.volume_tracker import set_volume_tracking

CHANNELS = 8
VOLUME_TOLERANCE = 0.001  # µL，与 plate_well numeric(12,3) 对齐
SRC_INITIAL_UL = 200.0    # 每个源孔预置体积（足够覆盖 sampleVol × 重复）


def _col_of(well: str) -> int:
    """A1→1, P24→24。"""
    digits = "".join(ch for ch in well if ch.isdigit())
    return int(digits) if digits else 0


def _row_of(well: str) -> int:
    letters = "".join(ch for ch in well if ch.isalpha()).upper()
    if not letters:
        return -1
    n = 0
    for ch in letters:
        n = n * 26 + (ord(ch) - ord("A") + 1)
    return n - 1


async def run(payload: dict) -> dict:
    transfers = payload.get("transfers") or []
    sample_vol = float(payload.get("sampleVol", 5))
    mix_vol = float(payload.get("masterMixVol", 15))
    sample_count = int(payload.get("sampleCount", 0))
    if not transfers:
        return {"mode": "qpcr-sim", "status": "FAIL", "error": "transfers 为空"}

    set_volume_tracking(True)
    set_tip_tracking(True)

    deck = STARDeck()
    lh = LiquidHandler(backend=LiquidHandlerChatterboxBackend(num_channels=CHANNELS), deck=deck)
    await lh.setup()

    src = cor_96_wellplate_360uL_Fb(name="src_96")
    dst = biorad_384_wellplate_50uL_Vb(name="dst_384")
    tips = hamilton_96_tiprack_300uL(name="tips_300ul")
    deck.assign_child_resource(src, location=Coordinate(100, 100, 0))
    deck.assign_child_resource(dst, location=Coordinate(250, 100, 0))
    deck.assign_child_resource(tips, location=Coordinate(400, 100, 0))

    used_src = {t.get("sourceWell", "").upper() for t in transfers}
    for w in used_src:
        try:
            src.get_well(w).set_volume(SRC_INITIAL_UL)
        except Exception:
            pass

    # 按 (列, 重复) 分组 → i8 每批 8 孔（同一列的 A-H 或 I-P）
    groups: dict[tuple[int, int], list[dict]] = defaultdict(list)
    for t in transfers:
        col = _col_of(t["targetWell"])
        rep = int(t.get("replicate", 1))
        groups[(col, rep)].append(t)

    # i8 一次装 8 个枪头，贯穿所有列批次，最后统一退枪头（与 Hamilton i8 实际行为一致）
    tip_spots = [tips.get_item(f"A{i + 1}") for i in range(CHANNELS)]
    await lh.pick_up_tips(tip_spots)

    batches: list[dict] = []
    commands: list[dict] = []
    for (col, rep), items in sorted(groups.items()):
        items.sort(key=lambda t: _row_of(t["targetWell"]))
        for offset in range(0, len(items), CHANNELS):
            chunk = items[offset:offset + CHANNELS]
            channels = list(range(len(chunk)))  # i8：按实际孔数占用通道 0..n-1
            src_wells = [src.get_well(t["sourceWell"].upper()) for t in chunk]
            dst_wells = [dst.get_well(t["targetWell"].upper()) for t in chunk]
            vols = [sample_vol for _ in chunk]

            await lh.aspirate(src_wells, vols=vols, use_channels=channels)
            commands.append({
                "batch": len(batches) + 1, "column": col, "replicate": rep,
                "method": "Aspirate", "channels": len(channels),
                "wells": [t["sourceWell"] for t in chunk], "volume": sample_vol,
            })
            await lh.dispense(dst_wells, vols=vols, use_channels=channels)
            commands.append({
                "batch": len(batches) + 1, "column": col, "replicate": rep,
                "method": "Dispense", "channels": len(channels),
                "wells": [t["targetWell"] for t in chunk], "volume": sample_vol,
            })
            batches.append({
                "batch": len(batches) + 1, "column": col, "replicate": rep,
                "wells": [t["targetWell"] for t in chunk], "channels": len(channels),
            })

    await lh.drop_tips(tip_spots)

    # 逐孔比对：期望 = sampleVol（MasterMix 是预分装，不在这段 worklist 里吸分）
    comparisons = []
    for t in transfers:
        well = t["targetWell"].upper()
        sim = round(float(dst.get_well(well).tracker.volume), 4)
        expected = round(sample_vol, 4)
        delta = round(sim - expected, 4)
        comparisons.append({
            "targetWell": well,
            "sourceWell": t["sourceWell"],
            "targetName": t.get("targetName", ""),
            "expected": expected,
            "simulated": sim,
            "delta": delta,
            "result": "match" if abs(delta) <= VOLUME_TOLERANCE else "mismatch",
        })

    match = sum(1 for c in comparisons if c["result"] == "match")
    mismatch = len(comparisons) - match
    volumes_src = {w: round(float(src.get_well(w).tracker.volume), 4) for w in sorted(used_src)}
    volumes_dst = {
        c["targetWell"]: c["simulated"]
        for c in comparisons
    }
    await lh.stop()

    return {
        "mode": "qpcr-sim",
        "status": "PASS" if mismatch == 0 else "FAIL",
        "at": datetime.now().isoformat(timespec="seconds"),
        "device_profile": "liquid_handler/i8",
        "channels": CHANNELS,
        "batchSize": CHANNELS,
        "batches": len(batches),
        "sampleCount": sample_count,
        "masterMixVol": mix_vol,
        "sampleVol": sample_vol,
        "sourceBarcode": payload.get("sourceBarcode", ""),
        "targetBarcode": payload.get("targetBarcode", ""),
        "commands": commands,
        "batchesDetail": batches,
        "volumes_src": volumes_src,
        "volumes_dst": volumes_dst,
        "comparisons": comparisons,
        "summary": {"match": match, "mismatch": mismatch, "total": len(comparisons)},
    }


def read_db_volumes(barcode: str) -> dict[str, float]:
    """从 labdb.plate_well_design 读**设计态**逐孔体积。

    为什么不是运行态 plate_well：Q10 拆表后运行态只由物料投影 T2 写（实际发生了什么），
    qPCR 布局是实验设计（打算做什么），唯一写入方是 QpcrLayoutController → plate_well_design。
    仿真 vs 设计的对照才有意义；仿真 vs 空运行态只会得到 NO_DATA。
    复用 run_simulator.py 的 psql 套路，不引入 psycopg2。
    """
    import os
    import subprocess

    host = os.environ.get("LABOS_DB_HOST", "127.0.0.1")
    port = os.environ.get("LABOS_DB_PORT", "5432")
    name = os.environ.get("LABOS_DB_NAME", "labdb")
    user = os.environ.get("LABOS_DB_USER", "labos")
    sql = (
        "SELECT COALESCE(json_agg(row_to_json(t))::text, '[]') FROM ("
        "SELECT w.well_label, w.volume FROM plate p "
        "JOIN plate_well_design w ON w.plate_id=p.id "
        f"WHERE p.barcode='{barcode.replace(chr(39), chr(39)*2)}' AND w.volume IS NOT NULL) t"
    )
    env = dict(os.environ, PGPASSWORD=os.environ.get("LABOS_DB_PASS", ""))
    out = subprocess.check_output([
        "psql", "-h", host, "-p", port, "-U", user, "-d", name, "-tAc", sql,
    ], env=env, text=True).strip()
    rows = json.loads(out or "[]")
    return {r["well_label"]: float(r["volume"]) for r in rows}


def build_twin_report(sim: dict, db_vols: dict[str, float]) -> dict:
    """把 i8 仿真报告 + plate_well 落库组 TwinDiff 报告（形状与 run_simulator.py 一致）。"""
    rows = []
    match = mismatch = missing_sim = missing_twin = 0
    for well, sim_ul in sim["volumes_dst"].items():
        if well not in db_vols:
            rows.append({"plate": sim["targetBarcode"], "well": well,
                         "sim_ul": sim_ul, "twin_ul": None, "delta_ul": None,
                         "result": "missing_in_twin"})
            missing_twin += 1
            continue
        twin_ul = db_vols[well]
        delta = round(sim_ul - twin_ul, 4)
        ok = abs(delta) <= VOLUME_TOLERANCE
        rows.append({"plate": sim["targetBarcode"], "well": well,
                     "sim_ul": sim_ul, "twin_ul": twin_ul, "delta_ul": delta,
                     "result": "match" if ok else "mismatch"})
        if ok: match += 1
        else: mismatch += 1
    for well in db_vols:
        if well not in sim["volumes_dst"]:
            rows.append({"plate": sim["targetBarcode"], "well": well,
                         "sim_ul": None, "twin_ul": db_vols[well], "delta_ul": None,
                         "result": "missing_in_sim"})
            missing_sim += 1
    status = "PASS" if match and not (mismatch or missing_sim or missing_twin) else "FAIL"
    return {
        "mode": "compare-twin",
        "run_id": f"qpcr-i8-{datetime.now().strftime('%Y%m%d%H%M%S')}",
        "job_id": sim.get("targetBarcode", ""),
        "status": status,
        "job_status": sim.get("status", ""),
        "twin_source": {"kind": "db", "scope": "design",
                        "barcodes": [sim.get("targetBarcode", "")]},
        "simulator": {"pylabrobot": "qpcr_sim/i8"},
        "ledger_applied_commands": 0,
        "comparisons": {
            "ledger": {"status": "SKIP", "summary": {}, "rows": [],
                       "note": "qPCR i8 仿真直接用 volume_tracker，ledger 侧不适用"},
            "twin": {
                "status": status,
                "summary": {"match": match, "mismatch": mismatch,
                            "missing_in_sim": missing_sim, "missing_in_twin": missing_twin},
                "rows": rows,
                "tolerance_ul": VOLUME_TOLERANCE,
            },
        },
    }


def push_report(report: dict) -> bool:
    """POST 到内核 /api/twin/diffs，沿用 run_simulator.py 的 urllib 登录套路。"""
    import os
    import urllib.error
    import urllib.request

    api = os.environ.get("LABOS_API") or "http://127.0.0.1:18080"
    user = os.environ.get("LABOS_USER")
    password = os.environ.get("LABOS_PASS")
    if not user or not password:
        print("  [ WARN ] 推送跳过：缺少 LABOS_USER / LABOS_PASS", file=sys.stderr)
        return False

    def call(path: str, payload: dict, token: str | None = None) -> dict:
        req = urllib.request.Request(
            api.rstrip("/") + path, data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"}, method="POST")
        if token:
            req.add_header("Authorization", "Bearer " + token)
        with urllib.request.urlopen(req, timeout=15) as response:
            return json.loads(response.read().decode("utf-8"))

    try:
        session = call("/api/auth/login", {"username": user, "password": password})
        result = call("/api/twin/diffs", report, session.get("token"))
        print(f"  [ PUSH ] {'重复(幂等)' if result.get('duplicate') else '已入库'} "
              f"id={result.get('id')} reportUid={result.get('reportUid')}", file=sys.stderr)
        return True
    except (urllib.error.URLError, ValueError, OSError) as exc:
        print(f"  [ WARN ] 推送失败：{type(exc).__name__}: {exc}", file=sys.stderr)
        return False


def main() -> None:
    import argparse
    import asyncio
    import contextlib
    from pathlib import Path

    ap = argparse.ArgumentParser(description="qPCR 96→384 i8 仿真 / 与 plate_well 差分")
    ap.add_argument("--compare-db", action="store_true",
                    help="仿真后读 plate_well 组 TwinDiff 报告（默认只输出仿真报告）")
    ap.add_argument("--push", action="store_true", help="把 TwinDiff 报告 POST 到内核")
    ap.add_argument("--out", help="同时把报告写到指定 JSON 文件")
    args = ap.parse_args()

    try:
        payload = json.load(sys.stdin)
    except Exception as e:
        print(json.dumps({"mode": "qpcr-sim", "status": "FAIL", "error": f"stdin JSON 非法: {e}"}))
        sys.exit(2)
    try:
        # PyLabRobot 把日志打到 stdout；重定向到 stderr，只让 JSON 落在 stdout
        with contextlib.redirect_stdout(sys.stderr):
            report = asyncio.run(run(payload))
    except Exception as e:
        print(json.dumps({
            "mode": "qpcr-sim", "status": "FAIL",
            "error": f"{type(e).__name__}: {e}",
        }))
        sys.exit(1)

    output = report
    if args.compare_db:
        barcode = payload.get("targetBarcode", "")
        if not barcode:
            print(json.dumps({"mode": "compare-twin", "status": "FAIL",
                              "error": "--compare-db 需要 stdin 提供 targetBarcode"}))
            sys.exit(2)
        try:
            db_vols = read_db_volumes(barcode)
        except Exception as e:
            print(json.dumps({"mode": "compare-twin", "status": "FAIL",
                              "error": f"读 plate_well 失败: {type(e).__name__}: {e}"}))
            sys.exit(1)
        output = build_twin_report(report, db_vols)
        if args.push:
            push_report(output)
    if args.out:
        Path(args.out).write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(output, ensure_ascii=False))


if __name__ == "__main__":
    main()
