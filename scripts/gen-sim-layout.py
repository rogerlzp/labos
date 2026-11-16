#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成「模拟物理布局」：把**只有现场才知道**的两块数据在模拟环境里造出来。

现场给不了的两块：
  ① 轨道分区（rail_zone / transfer_rail_zone / rail_zone_exemption）——物理布局知识
  ② 机械臂标定（robot_point 的 x/y/z/fill_status）——现场示教知识

本脚本在**模拟环境**里把这两块造出来，让 L3 不再 fail-closed、让机械臂有坐标可查，
从而能在仿真里跑通整条线；到了现场，换成真实值即可（见 docs/模拟布局与现场数据替换-20261115.md）。

设计原则（别改）：
  1. **一切从库里真实资源推导**，不手编设备/点位：岛 → 设备 → device_point → 点位资源 id。
  2. **路径键口径必须与运行期一致**：DriverStepExecutor 用 `sourcePos->targetPos`，
     两个都是**点位的 resource_node.id**（selectPositionResourceBySpec 取 point_resource_id）。
     ⇒ 配置必须覆盖"运行时可能选出的所有点位对"，否则 L3 fail-closed 照样拦。
  3. **分区由几何推导，不是拍脑袋**：按 x 坐标分带（轨道段），路径的分区集 =
     源→目标经过的所有轨道段（完整运动包络）；跨排再加换排道岔互斥点。
  4. **模拟数据必须可识别、可撤销**：资源码一律 `SIM-` 前缀；清理脚本只删这些。

用法：
    python3 scripts/gen-sim-layout.py                 # 生成
    python3 scripts/gen-sim-layout.py --dry-run       # 只看要生成什么，不写库
    python3 scripts/gen-sim-layout.py --snapshot-out scripts/sim-layout-snapshot.json
"""
import argparse
import json
import os
import sys

import psycopg2

HOST = os.environ.get("LABOS_DB_HOST", "192.168.124.2")
PORT = os.environ.get("LABOS_DB_PORT", "5432")
NAME = os.environ.get("LABOS_DB_NAME", "labdb")
USER = os.environ.get("LABOS_DB_USER", "labos")
PASS = os.environ.get("LABOS_DB_PASS", os.environ.get("LABOS_DB_PASSWORD", ""))

ISLAND_CODE = "LAB-EXT-01"
ARM_CODE = "ARM1"
SIM_PREFIX = "SIM-"
BAND_MM = 1000.0

# 合成平面布局（毫米）：x = 沿主轨道方向，y = 排向。
# 这是**模拟假设**，不是现场真值——现场用真实坐标覆盖即可（见替换手册）。
# 未列出的设备按 x = 1000*(序号+1), y = 0 顺延。
DEVICE_LAYOUT = {
    "STACK1": (0.0, 0.0),
    "P_AUTO-1": (1000.0, 0.0),
    "P_AUTO-2": (2000.0, 0.0),
    "P_AUTO-3": (3000.0, 0.0),
    "P_AUTO-4": (4000.0, 0.0),
    "D9608-1": (5000.0, 0.0),
    "DISP1": (500.0, 1500.0),
    "DISP2": (1500.0, 1500.0),
    "DISP3": (2500.0, 1500.0),
    "DISP4": (3500.0, 1500.0),
    "DISP5": (4500.0, 1500.0),
    "DISP6": (5500.0, 1500.0),
}
# 耗材栈是垂直堆叠：60 个槽位在**同一个平面位置**，只有 z 不同（层高 15mm）。
STACK_LAYER_MM = 15.0


def log(msg):
    print(msg)


def load(cur):
    """一次性把岛上的真实资源拉出来（不手编任何 id）。"""
    cur.execute("SELECT id FROM resource_node WHERE tenant_id=1 AND code=%s AND kind='island'",
                (ISLAND_CODE,))
    row = cur.fetchone()
    if not row:
        raise SystemExit("找不到岛 %s" % ISLAND_CODE)
    island_id = row[0]

    cur.execute("SELECT id, code, name FROM resource_node "
                "WHERE tenant_id=1 AND parent_id=%s AND kind='device' ORDER BY code", (island_id,))
    devices = [{"id": r[0], "code": r[1], "name": r[2]} for r in cur.fetchall()]

    # 机械臂：岛内的机械臂设备（优先 ARM_CODE，其次取唯一一台）
    arm = next((d for d in devices if d["code"] == ARM_CODE), None)
    if arm is None:
        raise SystemExit("岛上找不到机械臂 %s" % ARM_CODE)

    # 每台设备的点位：device_point → point_resource_id（运行期用的就是它）
    cur.execute(
        "SELECT dp.device_resource_id, dp.point_resource_id, rn.code, dp.position_label, "
        "       dp.slot_index, dp.point_kind, dp.robot_reachable "
        "  FROM device_point dp JOIN resource_node rn ON rn.id = dp.point_resource_id "
        " WHERE dp.tenant_id=1 AND dp.device_resource_id = ANY(%s) "
        " ORDER BY dp.device_resource_id, dp.id", ([d["id"] for d in devices],))
    points = []
    for r in cur.fetchall():
        points.append({"device_resource_id": r[0], "point_resource_id": r[1], "code": r[2],
                       "label": r[3], "slot_index": r[4], "point_kind": r[5],
                       "robot_reachable": r[6]})
    return island_id, devices, arm, points


def assign_coords(devices, points):
    """给设备排布 + 点位坐标（确定性）。"""
    layout = dict(DEVICE_LAYOUT)
    n = 0
    for d in devices:
        if d["code"] not in layout:
            layout[d["code"]] = (BAND_MM * (n + 1), 0.0)
            n += 1
    dev_xy = {d["id"]: layout[d["code"]] for d in devices}
    dev_code = {d["id"]: d["code"] for d in devices}
    for p in points:
        x, y = dev_xy[p["device_resource_id"]]
        z = 0.0
        if dev_code[p["device_resource_id"]] == "STACK1" and p["slot_index"] is not None:
            z = float(p["slot_index"]) * STACK_LAYER_MM
        p["x"], p["y"], p["z"] = x, y, z
    return dev_xy, dev_code


def build_zones(dev_xy):
    """按 x 坐标分带 → 轨道分区；跨排再加一个换排道岔互斥点。"""
    bands = sorted({int(x // BAND_MM) for (x, _) in dev_xy.values()})
    zones = []
    for b in bands:
        zones.append({"code": SIM_PREFIX + "Z%d" % b,
                      "name": "模拟轨道段 %d（x∈[%d,%d)）" % (b, int(b * BAND_MM), int((b + 1) * BAND_MM)),
                      "band": b})
    zones.append({"code": SIM_PREFIX + "ZCROSS",
                  "name": "模拟换排道岔互斥点（跨排搬运必须独占）",
                  "band": None})
    return zones


def zone_set(p1, p2, zone_by_band):
    """一条搬运路径的完整运动包络 = 经过的所有轨道段（+ 跨排时的道岔点）。"""
    b1 = int(p1["x"] // BAND_MM)
    b2 = int(p2["x"] // BAND_MM)
    lo, hi = min(b1, b2), max(b1, b2)
    codes = [zone_by_band[b]["code"] for b in range(lo, hi + 1) if b in zone_by_band]
    if abs(p1["y"] - p2["y"]) > 1e-6:
        codes.append(SIM_PREFIX + "ZCROSS")
    return sorted(set(codes))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--all-pairs", action="store_true",
                    help="覆盖岛内所有设备对（默认只覆盖流程里真实出现的设备对）")
    ap.add_argument("--snapshot-out", default="scripts/sim-layout-snapshot.json")
    ap.add_argument("--plate-barcode", default="SIM-PLATE-EXT-001",
                    help="模拟主物料板号（现场换成操作员扫码得到的真实板号）")
    args = ap.parse_args()

    conn = psycopg2.connect(host=HOST, port=PORT, dbname=NAME, user=USER, password=PASS)
    conn.autocommit = False
    created = {"resource_node": 0, "robot_point": 0, "rail_zone": 0,
               "transfer_rail_zone": 0, "stirrer_dispenser_pair": 0, "device_state": 0}
    snapshot = {"island": ISLAND_CODE, "generated_at": None}
    try:
        with conn.cursor() as cur:
            island_id, devices, arm, points = load(cur)
            dev_xy, dev_code = assign_coords(devices, points)
            log("岛 %s（id=%s）设备 %d 台，机械臂 %s（resource=%s），点位 %d 个"
                % (ISLAND_CODE, island_id, len(devices), arm["code"], arm["id"], len(points)))

            # 只给**机械臂可达**的点位建标定（INT 这类 deck_in 点机械臂够不着）
            # ⚠ psycopg2 把 boolean 读成 Python True/False，psql 才显示成 t/f ⇒ 两种都要认
            reach = [p for p in points if p["robot_reachable"] in (True, "t", "true", "T")]
            log("机械臂可达点位 %d / %d" % (len(reach), len(points)))

            zones = build_zones(dev_xy)
            zone_by_band = {z["band"]: z for z in zones if z["band"] is not None}

            # 路径覆盖：**流程里真实出现的设备对**（默认），或全部设备对（--all-pairs）。
            # 为什么按流程推导而不是全量：运行期 pathKey = 源点->目标点，两个端点都由
            #   「搬运节点的 source/target 设备」决定 ⇒ 流程没连的设备对根本不会出现。
            #   全量会多出几千行没人用的配置（还拖慢落库）。
            by_dev = {}
            for p in reach:
                by_dev.setdefault(p["device_resource_id"], []).append(p)

            pairs = []
            if args.all_pairs:
                for a in sorted(by_dev):
                    for b in sorted(by_dev):
                        if a >= b:
                            continue
                        for p1 in by_dev[a]:
                            for p2 in by_dev[b]:
                                pairs.append((p1, p2))
            else:
                # 从岛内流程的 Transfer 节点取 source/target 设备码 → 设备对
                cur.execute(
                    "SELECT n.params->'method_params'->>'source', n.params->'method_params'->>'target' "
                    "  FROM workflow_node n JOIN workflow w ON w.id = n.workflow_id "
                    " WHERE w.island_id = %s AND n.params->>'protocol_name' LIKE 'Transfer%%'",
                    (island_id,))
                workflow_pairs = [(r[0], r[1]) for r in cur.fetchall() if r[0] and r[1]]
                id_of_code = {d["code"]: d["id"] for d in devices}
                # 设备对 = 流程里出现过的 (源设备, 目标设备)
                seen_pairs = set()
                for src_code, dst_code in workflow_pairs:
                    a, b = id_of_code.get(src_code), id_of_code.get(dst_code)
                    if a in by_dev and b in by_dev:
                        seen_pairs.add((a, b))
                for a, b in sorted(seen_pairs):
                    for p1 in by_dev[a]:
                        for p2 in by_dev[b]:
                            pairs.append((p1, p2))
            log("路径（点位对）%d 条，来自设备对 %s"
                % (len(pairs),
                   sorted((dev_code[a], dev_code[b]) for a, b in seen_pairs)
                   if not args.all_pairs else "全部"))

            # 搅拌—分液配对：分液器 DISP* ↔ 虚拟搅拌单元
            dispensers = [d for d in devices if d["code"].startswith("DISP")]

            if args.dry_run:
                log("\n--dry-run：不写库。将生成：")
                log("  轨道分区 %d 个：%s" % (len(zones), ", ".join(z["code"] for z in zones)))
                log("  机械臂标定 %d 条（robot_resource_id=%s）" % (len(reach), arm["id"]))
                log("  路径分区集 %d 条" % len(pairs))
                log("  搅拌—分液配对 %d 条（虚拟搅拌单元）" % len(dispensers))
                conn.rollback()
                return 0

            # ---- 1. 虚拟资源（轨道分区 + 搅拌单元）----
            zone_res = {}
            for z in zones:
                cur.execute(
                    "INSERT INTO resource_node (tenant_id, parent_id, code, name, kind) "
                    "VALUES (1, %s, %s, %s, 'virtual') "
                    "ON CONFLICT DO NOTHING RETURNING id",
                    (island_id, z["code"], z["name"]))
                rid = cur.fetchone()
                if rid:
                    created["resource_node"] += 1
                    zone_res[z["code"]] = rid[0]
                else:
                    cur.execute("SELECT id FROM resource_node WHERE tenant_id=1 AND code=%s",
                                (z["code"],))
                    r = cur.fetchone()
                    if not r:
                        raise SystemExit("资源 %s 落库后仍查不到" % z["code"])
                    zone_res[z["code"]] = r[0]

            stirrer_res = {}
            for d in dispensers:
                code = SIM_PREFIX + d["code"] + "-STIR-UNIT"
                cur.execute(
                    "INSERT INTO resource_node (tenant_id, parent_id, code, name, kind) "
                    "VALUES (1, %s, %s, %s, 'virtual') "
                    "ON CONFLICT DO NOTHING RETURNING id",
                    (island_id, code, "模拟搅拌单元（配 %s）" % d["code"]))
                rid = cur.fetchone()
                if rid:
                    created["resource_node"] += 1
                    stirrer_res[d["id"]] = rid[0]
                else:
                    cur.execute("SELECT id FROM resource_node WHERE tenant_id=1 AND code=%s", (code,))
                    stirrer_res[d["id"]] = cur.fetchone()[0]
                # 状态行：idle + 新鲜（L4 闸门要求 10s 内的新鲜状态）
                cur.execute(
                    "INSERT INTO device_state (tenant_id, resource_id, status, has_plate, "
                    "       ready_give, ready_take, updated_at) "
                    "VALUES (1, %s, 'idle', false, false, true, now()) "
                    "ON CONFLICT (tenant_id, resource_id) DO UPDATE "
                    "SET status='idle', updated_at=now(), last_error=NULL", (stirrer_res[d["id"]],))
                created["device_state"] += 1

            # ---- 2. 轨道分区表 ----
            for z in zones:
                cur.execute(
                    "INSERT INTO rail_zone (tenant_id, zone_code, resource_id) "
                    "VALUES (1, %s, %s) ON CONFLICT (tenant_id, zone_code) DO UPDATE "
                    "SET resource_id=EXCLUDED.resource_id", (z["code"], zone_res[z["code"]]))
                created["rail_zone"] += 1

            # ---- 3. 路径 → 分区集（批量插：逐条 insert 在跨网延迟下要几分钟）----
            path_rows = []
            for p1, p2 in pairs:
                path_key = "%s->%s" % (p1["point_resource_id"], p2["point_resource_id"])
                for i, zc in enumerate(zone_set(p1, p2, zone_by_band)):
                    path_rows.append((1, path_key, zone_res[zc], i))
            cur.executemany(
                "INSERT INTO transfer_rail_zone (tenant_id, path_key, zone_resource_id, seq) "
                "VALUES (%s, %s, %s, %s) "
                "ON CONFLICT (tenant_id, path_key, zone_resource_id) DO UPDATE SET seq=EXCLUDED.seq",
                path_rows)
            created["transfer_rail_zone"] += len(path_rows)

            # ---- 4. 搅拌—分液配对 ----
            for d in dispensers:
                cur.execute(
                    "INSERT INTO stirrer_dispenser_pair (tenant_id, stirrer_resource_id, "
                    "       dispenser_resource_id) VALUES (1, %s, %s) "
                    "ON CONFLICT (tenant_id, dispenser_resource_id) DO UPDATE "
                    "SET stirrer_resource_id=EXCLUDED.stirrer_resource_id",
                    (stirrer_res[d["id"]], d["id"]))
                created["stirrer_dispenser_pair"] += 1

            # ---- 5. 模拟物料板 + 流程变量 ----
            # 为什么要有**多块**板：客户工艺是「4 台提取仪各一块磁套板」
            # ⇒ 4 条并行链各搬一块，各自在栈上的不同槽位（STACK1-S09..S12）、
            # 板号也各不相同 ⇒ 流程里每条链用自己的板变量（magPlate1..N）。
            # 板数从库里的**磁套槽数**推导（不手编）：N = 磁套槽的个数。
            cur.execute(
                "SELECT count(*) FROM device_point dp "
                "  JOIN resource_node dev ON dev.id = dp.device_resource_id "
                " WHERE dp.tenant_id=1 AND dev.parent_id=%s "
                "   AND upper(coalesce(dp.position_label,''))='MAGNET'", (island_id,))
            magnet_slots = cur.fetchone()[0]
            # 板数 = min(磁套槽数, 提取仪台数)：每台提取仪一块磁套板
            cur.execute(
                "SELECT count(*) FROM resource_node "
                " WHERE tenant_id=1 AND parent_id=%s AND kind='device' AND code LIKE 'P_AUTO%%'",
                (island_id,))
            extractor_count = cur.fetchone()[0]
            magnet_count = min(magnet_slots, extractor_count)
            if magnet_count < 1:
                raise SystemExit("耗材栈没有 MAGNET 槽位或岛上没有提取仪（无法推导磁套板数量）")
            log("  磁套槽 %d 个 · 提取仪 %d 台 ⇒ 造 %d 块模拟磁套板"
                % (magnet_slots, extractor_count, magnet_count))

            plate_rows = []
            for i in range(1, magnet_count + 1):
                barcode = "SIM-PLATE-MAG-%d" % i
                plate_rows.append((barcode, "magnet96"))
            # 批次主物料板（8 阶段补全时用；当前流程不引用它 ⇒ 不会被预置）
            plate_rows.append((args.plate_barcode, "magnet96"))
            cur.executemany(
                "INSERT INTO plate (id, tenant_id, barcode, plate_type, rows, cols, status, "
                "       identity_status, lifecycle, created_at, updated_at) "
                "VALUES (nextval('plate_id_seq'), 1, %s, %s, 8, 12, 'available', "
                "        'CONFIRMED', 'process', now(), now()) "
                "ON CONFLICT (tenant_id, barcode) DO NOTHING", plate_rows)
            created["plate"] = len(plate_rows)

            cur.execute("SELECT id, code FROM workflow WHERE tenant_id=1 AND island_id=%s",
                        (island_id,))
            for wf_id, wf_code in cur.fetchall():
                # 批次主物料
                cur.execute(
                    "INSERT INTO workflow_variable (tenant_id, workflow_id, name, type, "
                    "       default_value, description) "
                    "VALUES (1, %s, 'mainPlate', 'string', %s, %s) "
                    "ON CONFLICT (workflow_id, name) DO UPDATE "
                    "SET default_value=EXCLUDED.default_value, updated_at=now()",
                    (wf_id, args.plate_barcode,
                     "模拟主物料板号（现场换成操作员扫码得到的真实板号）"))
                created["workflow_variable"] = created.get("workflow_variable", 0) + 1
                # 每条并行链一块磁套板
                for i in range(1, magnet_count + 1):
                    bc = "SIM-PLATE-MAG-%d" % i
                    cur.execute(
                        "INSERT INTO workflow_variable (tenant_id, workflow_id, name, type, "
                        "       default_value, description) "
                        "VALUES (1, %s, %s, 'string', %s, %s) "
                        "ON CONFLICT (workflow_id, name) DO UPDATE "
                        "SET default_value=EXCLUDED.default_value, updated_at=now()",
                        (wf_id, "magPlate%d" % i, bc,
                         "第 %d 台提取仪的磁套板（现场换成操作员扫码得到的真实板号）" % i))
                    created["workflow_variable"] = created.get("workflow_variable", 0) + 1
                log("  流程 %s（%s）变量：mainPlate + magPlate1..%d"
                    % (wf_code, wf_id, magnet_count))

            # ---- 6. 机械臂标定 ----
            for p in reach:
                # code = 点位资源码（运行期 selectRobotPoint 按 code 查，必须与流程里写的 source/target 一致）
                cur.execute(
                    "INSERT INTO robot_point (tenant_id, island_id, robot_resource_id, code, name, "
                    "       x, y, z, device_resource_id, action, synced_at, fill_status, "
                    "       point_type, approach_axis, max_stack, order_in_device) "
                    "VALUES (1, %s, %s, %s, %s, %s, %s, %s, %s, 'pick_place', now(), 'ok', "
                    "        %s, 'front', 1, %s) "
                    "ON CONFLICT (tenant_id, robot_resource_id, code) DO UPDATE SET "
                    "  x=EXCLUDED.x, y=EXCLUDED.y, z=EXCLUDED.z, "
                    "  device_resource_id=EXCLUDED.device_resource_id, synced_at=now(), "
                    "  fill_status='ok', point_type=EXCLUDED.point_type, "
                    "  order_in_device=EXCLUDED.order_in_device",
                    (island_id, arm["id"], p["code"], "SIM " + p["code"],
                     p["x"], p["y"], p["z"], p["device_resource_id"],
                     "place" if dev_code[p["device_resource_id"]] == "STACK1" else "pick",
                     p["slot_index"]))
                created["robot_point"] += 1

            snapshot.update({
                "island_id": island_id,
                "arm": {"code": arm["code"], "resource_id": arm["id"]},
                "zones": [{"code": z["code"], "resource_id": zone_res[z["code"]],
                           "name": z["name"]} for z in zones],
                "devices": [{"code": d["code"], "resource_id": d["id"],
                             "x": dev_xy[d["id"]][0], "y": dev_xy[d["id"]][1],
                             "stirrer_resource_id": stirrer_res.get(d["id"])} for d in devices],
                "points": [{"code": p["code"], "resource_id": p["point_resource_id"],
                            "device": dev_code[p["device_resource_id"]],
                            "x": p["x"], "y": p["y"], "z": p["z"],
                            "robot_reachable": p["robot_reachable"]} for p in points],
                "path_count": len(pairs),
            })
            conn.commit()
    except Exception as e:
        conn.rollback()
        print("✗ 失败已回滚：%s" % e)
        return 1
    finally:
        conn.close()

    snapshot["generated_at"] = __import__("datetime").datetime.now().isoformat(timespec="seconds")
    snapshot["counts"] = created
    try:
        with open(args.snapshot_out, "w", encoding="utf-8") as f:
            json.dump(snapshot, f, ensure_ascii=False, indent=2)
        log("\n✓ 快照已写 %s" % args.snapshot_out)
    except OSError as e:
        log("（快照写入失败：%s）" % e)

    log("\n✓ 模拟布局已生成：%s" % created)
    log("  撤销：python3 scripts/clean-sim-layout.py")
    return 0


if __name__ == "__main__":
    sys.exit(main())
