#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""为网格类设备（培养箱/堆栈）生成 cols×rows 个点位（批量 SQL，秒级）。

培养箱：2 列 × 15 层；耗材堆栈：10 列 × 12 层。
每个点位：resource_node(code = 设备-R{r}C{c}) + device_point(grid_row/grid_col/
max_height_mm/plate_types) + position_state 空行。

幂等：ON CONFLICT 更新；只新增不删。

用法：
  python3 scripts/gen-grid-points.py --device ID-X229 --cols 2 --rows 15 \
      --kind incubator --plate-types pcr96,deep96 --max-height 45
  python3 scripts/gen-grid-points.py --device ID-Hotel12H-1 --cols 10 --rows 12 \
      --kind storage --max-stack 6 --dry-run
"""
import argparse
import os
import subprocess
import sys

TENANT = 1


def q(sql, dry=False):
    env = dict(os.environ)
    env["PGPASSWORD"] = os.environ.get("LABOS_DB_PASS", "REDACTED_DB_PASSWORD")
    cmd = ["psql", "-h", os.environ.get("LABOS_DB_HOST", "192.0.2.2"),
           "-p", os.environ.get("LABOS_DB_PORT", "5432"),
           "-U", os.environ.get("LABOS_DB_USER", "labos"),
           "-d", os.environ.get("LABOS_DB_NAME", "labdb"),
           "-q", "-tA", "-v", "ON_ERROR_STOP=1", "-c", sql]
    if dry and not sql.lstrip().upper().startswith("SELECT"):
        print("  [dry] " + " ".join(sql.split())[:160])
        return ""
    r = subprocess.run(cmd, env=env, capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError("SQL 失败：%s\n%s" % (sql[:160], r.stderr.strip()[:300]))
    return r.stdout.strip()


def esc(s):
    return s.replace("'", "''")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--device", required=True)
    ap.add_argument("--cols", type=int, required=True)
    ap.add_argument("--rows", type=int, required=True)
    ap.add_argument("--kind", default="storage", choices=["incubator", "storage", "transfer_point"])
    ap.add_argument("--plate-types", default="", help="逗号分隔，如 pcr96,deep96")
    ap.add_argument("--max-height", type=float, default=None, help="mm")
    ap.add_argument("--max-stack", type=int, default=1)
    ap.add_argument("--role", default="internal", choices=["entry", "exit", "both", "internal"],
                   help="存储格默认 internal（设备内部机构搬，机械臂不可达）")
    ap.add_argument("--load-port", action="store_true",
                    help="同时生成一个网格之外的单独位置（{设备}-IN，both + 可达，带位置标识）")
    ap.add_argument("--load-port-label", default="",
                    help="单独位置的位置标识（默认用 {设备}-IN；现场标签可自定义）")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    dev_id = q(f"SELECT id FROM resource_node WHERE tenant_id={TENANT} "
               f"AND code='{esc(args.device)}' AND kind='device'", args.dry_run)
    if not dev_id:
        print("找不到设备: " + args.device)
        return 1
    dev_id = int(dev_id.splitlines()[0])

    total = args.cols * args.rows
    print(f"设备 {args.device}：{args.cols} 列 × {args.rows} 层 = {total} 个点位 "
          f"(kind={args.kind} plateTypes={args.plate_types or '不限'} "
          f"maxHeight={args.max_height or '不限'} maxStack={args.max_stack})")
    if args.dry_run:
        print("(dry-run，未写库)")
        return 0

    entry = "true" if args.role in ("both", "entry") else "false"
    exit_ = "true" if args.role in ("both", "exit") else "false"
    reach = "true" if args.role != "internal" else "false"
    dev = esc(args.device)
    maxh = args.max_height if args.max_height is not None else "NULL"
    ptypes = ("'{%s}'::varchar[]" % args.plate_types) if args.plate_types else "NULL"

    # 1) resource_node 批量
    q(f"INSERT INTO resource_node (tenant_id,code,name,parent_id,kind,location,attr,sort_order) "
      f"SELECT {TENANT}, '{dev}-R'||r||'C'||c, '{dev}-R'||r||'C'||c, {dev_id}, '{args.kind}', "
      f"jsonb_build_object('zone','{dev}','point','{dev}-R'||r||'C'||c), "
      f"'{{}}'::jsonb, r*1000+c "
      f"FROM generate_series(1,{args.rows}) r, generate_series(1,{args.cols}) c "
      f"ON CONFLICT (tenant_id,code) DO UPDATE SET parent_id=EXCLUDED.parent_id, "
      f"kind=EXCLUDED.kind, location=EXCLUDED.location, updated_at=now()")
    # 2) device_point 批量（按 code 关联）
    q(f"INSERT INTO device_point (tenant_id,device_resource_id,point_resource_id,point_role,"
      f"robot_reachable,is_entry,is_exit,order_in_device,max_stack,max_height_mm,plate_types,"
      f"grid_row,grid_col,is_standalone,note) "
      f"SELECT {TENANT},{dev_id},rn.id,'{args.role}',{reach},{entry},{exit_},"
      f"rn.sort_order,{args.max_stack},{maxh},{ptypes},"
      f"(rn.sort_order/1000)::int, (rn.sort_order%1000)::int, false, '网格生成' "
      f"FROM resource_node rn "
      f"WHERE rn.tenant_id={TENANT} AND rn.parent_id={dev_id} "
      f"AND rn.code LIKE '{dev}-R%' AND rn.kind='{args.kind}' "
      f"ON CONFLICT (tenant_id,point_resource_id) DO UPDATE SET "
      f"point_role=EXCLUDED.point_role, robot_reachable=EXCLUDED.robot_reachable, "
      f"is_entry=EXCLUDED.is_entry, is_exit=EXCLUDED.is_exit, "
      f"order_in_device=EXCLUDED.order_in_device, max_stack=EXCLUDED.max_stack, "
      f"max_height_mm=EXCLUDED.max_height_mm, plate_types=EXCLUDED.plate_types, "
      f"grid_row=EXCLUDED.grid_row, grid_col=EXCLUDED.grid_col, "
      f"is_standalone=false, note=EXCLUDED.note, updated_at=now()")
    # 3) position_state 批量
    q(f"INSERT INTO position_state (tenant_id,resource_id,plate_id,updated_at) "
      f"SELECT {TENANT},rn.id,NULL,now() FROM resource_node rn "
      f"WHERE rn.tenant_id={TENANT} AND rn.parent_id={dev_id} "
      f"AND rn.code LIKE '{dev}-R%' AND rn.kind='{args.kind}' "
      f"ON CONFLICT (tenant_id,resource_id) DO NOTHING")

    # 4) 可选：网格之外的单独位置（进口/出口装载口）+ 位置标识
    if args.load_port:
        label = esc(args.load_port_label or f"{args.device}-IN")
        q(f"INSERT INTO resource_node (tenant_id,code,name,parent_id,kind,location,attr,sort_order) "
          f"SELECT {TENANT},'{dev}-IN','{dev}-IN',{dev_id},'{args.kind}',"
          f"jsonb_build_object('zone','{dev}','point','{dev}-IN'),"
          f"'{{\"source\":\"load-port\"}}'::jsonb,0 "
          f"ON CONFLICT (tenant_id,code) DO NOTHING")
        q(f"INSERT INTO device_point (tenant_id,device_resource_id,point_resource_id,point_role,"
          f"robot_reachable,is_entry,is_exit,order_in_device,is_standalone,position_label,"
          f"grid_row,grid_col,note) "
          f"SELECT {TENANT},{dev_id},rn.id,'both',true,true,true,0,true,'{label}',"
          f"NULL,NULL,'进口/出口（网格外的单独位置，机械臂装载口）' "
          f"FROM resource_node rn WHERE rn.tenant_id={TENANT} AND rn.code='{dev}-IN' "
          f"ON CONFLICT (tenant_id,point_resource_id) DO UPDATE SET "
          f"point_role='both',robot_reachable=true,is_entry=true,is_exit=true,"
          f"order_in_device=0,is_standalone=true,"
          f"position_label=COALESCE(device_point.position_label, EXCLUDED.position_label),"
          f"grid_row=NULL,grid_col=NULL,"
          f"note='进口/出口（网格外的单独位置，机械臂装载口）',updated_at=now()")
        q(f"INSERT INTO position_state (tenant_id,resource_id,plate_id,updated_at) "
          f"SELECT {TENANT},rn.id,NULL,now() FROM resource_node rn "
          f"WHERE rn.tenant_id={TENANT} AND rn.code='{dev}-IN' "
          f"ON CONFLICT (tenant_id,resource_id) DO NOTHING")
        print(f"  单独位置 {dev}-IN 已建（进口+出口 / 标识 {label} / both + 可达）")
    print(f"完成：{total} 个存储格（内部）+ "
          f"{'1 个单独位置（进出口）' if args.load_port else '无单独位置'}（幂等）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
