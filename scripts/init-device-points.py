#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""点位初始化（P0）：把「设备↔点位」语义配置层与 position_state 空行补齐。

设计见 docs/architecture/点位初始化与物料身份-设计_20261113.md §3.4。
为什么需要：岛建好只 INSERT island/device 两类节点，层位靠 baseline-build.sql 手工造；
20 节点用到的读板/离心/封膜三台设备一个点位都没有 ⇒ M 方案选位必失败。

幂等：resource_node / device_point 用 ON CONFLICT DO UPDATE，
position_state 用 ON CONFLICT DO NOTHING，可反复跑。
只新增，不改老系统迁移的 2563 个点位；清理 AUTO- 占位前先建备份表。

用法：
  python3 scripts/init-device-points.py --dry-run           # 只看将执行的 SQL
  python3 scripts/init-device-points.py                     # 初始化
  python3 scripts/init-device-points.py --clear-stale-auto  # 初始化 + 清历史假板占位
  python3 scripts/init-device-points.py --rollback          # 删除本次新建的点位
"""
import argparse
import json
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TRACK = "/tmp/labos_init_points_20261113.txt"
TENANT = 1

# (设备 code, 点位 kind, 目标点位数, point_role, 说明)
# ID-X229 / ID-X231 的点位已由 baseline-build.sql 建好，这里只补配置层与空状态行。
DEVICES = [
    ("ID-1021", "storage", 2, "both", "读板：进出共用"),
    ("ID-ASPIN_2K-1", "storage", 2, "both", "离心替：进出共用"),
    ("ID-S100-1", "storage", 2, "both", "封膜：进出共用"),
    ("ID-X229", "incubator", 4, "both", "孵育：4 个层位，进出共用"),
    ("ID-X231", "transfer_point", 2, "both", "机械臂1 中转位"),
    ("ID-XRobot_A10LR-1", "transfer_point", 1, "both", "机械臂2 中转位"),
]
POINT_KINDS = ("incubator", "storage", "transfer_point")

_dry = False


def q(sql):
    env = dict(os.environ)
    env["PGPASSWORD"] = os.environ.get("LABOS_DB_PASS", "REDACTED_DB_PASSWORD")
    cmd = ["psql", "-h", os.environ.get("LABOS_DB_HOST", "192.0.2.2"),
           "-p", os.environ.get("LABOS_DB_PORT", "5432"),
           "-U", os.environ.get("LABOS_DB_USER", "labos"),
           "-d", os.environ.get("LABOS_DB_NAME", "labdb"),
           "-q", "-tA", "-v", "ON_ERROR_STOP=1", "-c", sql]
    # dry-run 时只读 SELECT 照常执行（点位现状要准），只跳过写操作。
    if _dry and not sql.lstrip().upper().startswith("SELECT"):
        print("  [dry-run] " + " ".join(sql.split()))
        return ""
    r = subprocess.run(cmd, env=env, capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError("SQL 失败：%s\n%s" % (sql[:200], r.stderr.strip()[:300]))
    return r.stdout.strip()


def esc(s):
    return s.replace("'", "''")


def point_code(dev_code, kind, n):
    return "%s-%s%d" % (dev_code, "TP" if kind == "transfer_point" else "P", n)


def existing_points(dev_id):
    sql = ("SELECT id||'|'||code||'|'||kind::text FROM resource_node "
           "WHERE tenant_id=%d AND parent_id=%d "
           "AND kind::text IN ('incubator','storage','transfer_point') "
           "ORDER BY id" % (TENANT, dev_id))
    out = []
    for ln in q(sql).splitlines():
        if ln.strip():
            p = ln.split("|")
            if len(p) == 3:
                out.append((int(p[0]), p[1], p[2]))
    return out


def upsert_point(dev_id, dev_code, kind, n, role, note):
    code = point_code(dev_code, kind, n)
    loc = ('{"zone":"%s","point":"%s","physical_point":""}' % (dev_code, code))
    attr = ('{"source":"init-device-points","role":"%s"}' % role)
    pid = q(
        "INSERT INTO resource_node "
        "(tenant_id,code,name,parent_id,kind,location,attr,sort_order) "
        "VALUES (%d,'%s','%s',%d,'%s','%s'::jsonb,'%s'::jsonb,%d) "
        "ON CONFLICT (tenant_id,code) DO UPDATE SET "
        "name=EXCLUDED.name, parent_id=EXCLUDED.parent_id, kind=EXCLUDED.kind, "
        "location=EXCLUDED.location, attr=EXCLUDED.attr, updated_at=now() "
        "RETURNING id" % (TENANT, esc(code), esc(code), dev_id, kind,
                          esc(loc), esc(attr), n))
    return code, pid


def upsert_config(dev_id, pid, n, role, note):
    # 只新增、不覆盖已有配置（与脚本开头「只新增，不改老系统点位」一致）。
    # v3 口径里同一台设备混有 internal 内部格与 both 装载口，而型号模板一行
    # 只有一个 role；这里的 DO UPDATE 会把 30/120 个内部格统一刷成模板 role，
    # 重跑一次就破坏 gen-grid-points.py 生成的网格配置。故改为 DO NOTHING。
    if role == "both":
        entry, exit_ = "true", "true"
    elif role == "entry":
        entry, exit_ = "true", "false"
    elif role == "exit":
        entry, exit_ = "false", "true"
    else:  # internal
        entry, exit_ = "false", "false"
    q("INSERT INTO device_point "
      "(tenant_id,device_resource_id,point_resource_id,point_role,robot_reachable,"
      "is_entry,is_exit,order_in_device,note) "
      "VALUES (%d,%d,%s,'%s',%s,%s,%s,%d,'%s') "
      "ON CONFLICT (tenant_id,point_resource_id) DO NOTHING"
      % (TENANT, dev_id, pid, role,
         "false" if role == "internal" else "true", entry, exit_, n, esc(note)))


def init_state(pid):
    q("INSERT INTO position_state (tenant_id,resource_id,plate_id,updated_at) "
      "VALUES (%d,%s,NULL,now()) "
      "ON CONFLICT (tenant_id,resource_id) DO NOTHING" % (TENANT, pid))


def clear_stale_auto():
    q("CREATE TABLE IF NOT EXISTS position_state_bak_20261113 AS "
      "SELECT * FROM position_state WHERE plate_id LIKE 'AUTO-%'")
    q("UPDATE position_state SET plate_id=NULL, occupied_since=NULL, "
      "occupied_by_run=NULL, reserved_until=NULL, reserved_by_run=NULL, "
      "updated_at=now() WHERE plate_id LIKE 'AUTO-%'")
    print("  已备份到 position_state_bak_20261113 并清空 AUTO- 占位")


def self_check():
    print("\n=== 自检 ===")
    dev_codes = ",".join("'%s'" % esc(d[0]) for d in DEVICES)
    rows = q("SELECT d.code||' | '||count(*)::text||' 点位 | 有配置 '||"
             "count(*) FILTER (WHERE dp.id IS NOT NULL)::text||' | 有状态行 '||"
             "count(*) FILTER (WHERE ps.resource_id IS NOT NULL)::text "
             "FROM resource_node d "
             "JOIN resource_node r ON r.parent_id=d.id AND r.tenant_id=d.tenant_id "
             "LEFT JOIN device_point dp ON dp.point_resource_id=r.id "
             "AND dp.tenant_id=r.tenant_id "
             "LEFT JOIN position_state ps ON ps.resource_id=r.id "
             "AND ps.tenant_id=r.tenant_id "
             "WHERE d.tenant_id=%d AND d.code IN (%s) "
             "AND r.kind::text IN ('incubator','storage','transfer_point') "
             "GROUP BY d.code ORDER BY d.code" % (TENANT, dev_codes))
    print(rows or "(空)")


def infer_kind(dev_code, prefix):
    """已有点位优先沿用其 kind；全新设备按点位前缀推断。"""
    v = q("SELECT kind::text FROM resource_node WHERE tenant_id=1 "
          "AND parent_id=(SELECT id FROM resource_node WHERE tenant_id=1 "
          "AND code='%s') AND kind::text IN "
          "('incubator','storage','transfer_point') ORDER BY id LIMIT 1"
          % esc(dev_code))
    if v:
        return v.splitlines()[0]
    return "transfer_point" if prefix.startswith("TP") else "storage"


def devices_from_template():
    """优先按 device_point_template 展开；PK 是 (tenant_id, device_model)，
    所以一台设备的多个点位定义放在 notes 的 JSON 数组里。无模板时回退内置清单。"""
    rows = q("SELECT device_model||'|'||coalesce(point_code_suffix,'P')||'|'||"
             "coalesce(notes,'')||'|'||point_role "
             "FROM device_point_template WHERE tenant_id=1 ORDER BY device_model")
    out = []
    for ln in rows.splitlines():
        p = ln.split("|", 3)
        if len(p) < 4:
            continue
        code, prefix, notes, role = p
        try:
            pts = json.loads(notes).get("points", [])
        except (ValueError, AttributeError):
            pts = []
        if not pts:
            continue
        out.append((code, infer_kind(code, prefix), len(pts), role,
                    "按型号模板展开"))
    return out


def rollback():
    if not os.path.exists(TRACK):
        print("没有 %s，无可回滚内容" % TRACK)
        return 0
    codes = [x.strip() for x in open(TRACK, encoding="utf-8") if x.strip()]
    if not codes:
        print("清单为空")
        return 0
    inlist = ",".join("'%s'" % esc(c) for c in codes)
    print("回滚 %d 个点位：%s" % (len(codes), ", ".join(codes)))
    q("DELETE FROM device_point WHERE tenant_id=%d AND point_resource_id IN "
      "(SELECT id FROM resource_node WHERE tenant_id=%d AND code IN (%s))"
      % (TENANT, TENANT, inlist))
    q("DELETE FROM position_state WHERE tenant_id=%d AND resource_id IN "
      "(SELECT id FROM resource_node WHERE tenant_id=%d AND code IN (%s))"
      % (TENANT, TENANT, inlist))
    q("DELETE FROM resource_node WHERE tenant_id=%d AND code IN (%s) "
      "AND attr->>'source'='init-device-points'" % (TENANT, inlist))
    print("已回滚（AUTO- 占位的清理请用 position_state_bak_20261113 手工恢复）")
    return 0


def main():
    global _dry
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--clear-stale-auto", action="store_true",
                    help="清掉历史 AUTO-<runId> 假板占位（先建备份表）")
    ap.add_argument("--rollback", action="store_true")
    args = ap.parse_args()
    _dry = args.dry_run
    if args.rollback:
        return rollback()

    created = []
    devices = devices_from_template() or DEVICES
    print("点位来源：%s（%d 台设备）" % (
        "device_point_template" if devices is not DEVICES else "内置清单",
        len(devices)))
    for dev_code, kind, target, role, note in devices:
        dev_id = q("SELECT id FROM resource_node WHERE tenant_id=%d AND code='%s'"
                   % (TENANT, esc(dev_code)))
        if not dev_id:
            print("  ⚠ 跳过：找不到设备 %s" % dev_code)
            continue
        dev_id = int(dev_id.splitlines()[0])
        have = existing_points(dev_id)
        print("%s (id=%d)：已有 %d 个点位，目标 %d" % (dev_code, dev_id, len(have), target))
        points = list(have)
        for n in range(len(have) + 1, target + 1):
            code, pid = upsert_point(dev_id, dev_code, kind, n, role, note)
            if pid:
                points.append((int(pid), code, kind))
                created.append(code)
                print("  新建点位 %s (id=%s)" % (code, pid))
        for i, (pid, code, _k) in enumerate(points, start=1):
            upsert_config(dev_id, pid, i, role, note)
            init_state(pid)

    if _dry:
        print("\n(dry-run，未写库)")
        return 0

    if created:
        with open(TRACK, "w", encoding="utf-8") as f:
            f.write("\n".join(created) + "\n")
        print("\n新建点位清单 → %s（--rollback 用）" % TRACK)

    if args.clear_stale_auto:
        clear_stale_auto()

    self_check()
    print("\n完成。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
