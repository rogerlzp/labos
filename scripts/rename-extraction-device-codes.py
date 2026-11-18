#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""提取线岛：设备编号改回**客户现场编号**（去掉建第二个岛时加的 -02- 标记）。

**为什么需要（2026-11-18）**
建第二个岛时，device_instance.code 的唯一约束 UNIQUE (tenant_id, code) **不按岛隔离**
=> 客户真设备编号（P_AUTO-1..4 / DISP1..6 / ARM1 / STACK1）已被老岛占用
=> 新岛只能用 P_AUTO-02-1 这类带标记的编号。
老岛已于同日整岛 purge 删除 => **现场编号空出来了** => 改回去，
客户演示时界面上的编号与《说明书》一致。

**改什么（全部限定在提取线岛的子树内）**
  设备实例 / 设备节点 : P_AUTO-02-1  -> P_AUTO-1
  点位节点（编码）    : P_AUTO-02-1-EXT -> P_AUTO-1-EXT；STACK-02-1-S09 -> STACK1-S09
  驱动               : DRV-SIM-P_AUTO-02-1 -> DRV-SIM-P_AUTO-1
  设备池             : POOL-EXT-02 -> POOL-EXT（另删 3 个脚本重跑留下的同义池）
  板条码（仿真物料） : SIM-PLATE-MAG-02-1 -> SIM-PLATE-MAG-1
  流程里的编码引用   : 节点参数 sourcePosition / device / into 与流程图 JSON

⚠ **不做全局字符串替换**：-02- 在**别的岛**也存在（质粒岛的 PL-RACK-COLD-02-* 等
存储位），全局替换会把它们改坏 => 只改本岛子树与本岛流程。

⚠ **jsonb 列（params / graph）不能用 CASE**：jsonb 不接受裸字符串，
   而且 CASE 比的是**整个对象**（永远不等）=> 走「文本子串替换 → 转回 jsonb」，
   且只替换**设备码**（点位编码是设备码 + 后缀 ⇒ 替设备码即连带替掉点位码与变量名）。

⚠ **岛号 / 流程号不动**：LAB-EXT-02 / EXT-WORKFLOW-02 是平台内部编号，与设备编号无关。

用法：
    python3 scripts/rename-extraction-device-codes.py            # dry-run（默认，只打印计划）
    python3 scripts/rename-extraction-device-codes.py --apply    # 真改（一个事务）

改完必须做的（脚本会提示）：
    ① 重启模拟器与侧车（它们按 device-code 认领 => 编号变了认领不到 => 设备离线）：
       先停旧编号那批：pkill -f "device-code P_AUTO-02-"
       再：LABOS_ISLAND_CODE=LAB-EXT-02 LABOS_DEV_TAG= \
           python3 scripts/bring-extraction-station-online.py
    ② 跑一次流程验证编码引用没断（流程参数里是编码字符串，改错了第一段搬运就会炸）。
"""

import argparse
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from extraction_config import ISLAND_CODE, ext_codes, disp_codes, arm_code, stack_code  # noqa: E402

DB_HOST = os.environ.get("LABOS_DB_HOST", "192.168.124.2")
DB_PORT = os.environ.get("LABOS_DB_PORT", "5432")
DB_NAME = os.environ.get("LABOS_DB_NAME", "labdb")
DB_USER = os.environ.get("LABOS_DB_USER", "labos")
DB_PASS = os.environ.get("LABOS_DB_PASS", "Labos@Lab2026")

OLD_TAGS = ("-02-",)


def psql(sql):
    env = dict(os.environ, PGPASSWORD=DB_PASS)
    p = subprocess.run(
        ["psql", "-h", DB_HOST, "-p", DB_PORT, "-U", DB_USER, "-d", DB_NAME,
         "-A", "-F", "|", "-t", "-v", "ON_ERROR_STOP=1", "-c", sql],
        capture_output=True, text=True, env=env)
    if p.returncode != 0:
        sys.exit("✘ SQL 失败：\n" + p.stderr.strip())
    return [line.split("|") for line in p.stdout.strip().splitlines() if line]


def live_codes():
    """现场编号（不带标记）—— 用配置层生成，不另写一套编号规则。"""
    out = {}
    for i, c in enumerate(ext_codes(), start=1):
        out["P_AUTO-%d" % i] = c
    for i, c in enumerate(disp_codes(), start=1):
        out["DISP%d" % i] = c
    out["ARM1"] = arm_code()
    out["STACK1"] = stack_code()
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true", help="真改（默认 dry-run）")
    ap.add_argument("--island", default=ISLAND_CODE)
    args = ap.parse_args()

    isl = psql("SELECT id FROM resource_node WHERE code='%s' AND kind='island'" % args.island)
    if not isl:
        sys.exit("✘ 找不到岛 %s" % args.island)
    island_id = isl[0][0]

    nodes = psql("""
        WITH RECURSIVE sub AS (
            SELECT id, code, kind, parent_id FROM resource_node
             WHERE tenant_id=1 AND id=%s
            UNION
            SELECT r.id, r.code, r.kind, r.parent_id FROM resource_node r
              JOIN sub s ON r.parent_id=s.id WHERE r.tenant_id=1
        )
        SELECT id, code, kind, parent_id FROM sub ORDER BY id""" % island_id)

    site = live_codes()
    # 设备旧码 -> 现场码（按配置层的编号规则对齐，不另写一套）
    dev_map = {}
    for base_old, base_new in site.items():
        if base_old.startswith("P_AUTO-"):
            dev_map["P_AUTO-02-" + base_old.split("-")[-1]] = base_new
        else:
            dev_map[base_old[:-1] + "-02-" + base_old[-1]] = base_new

    mapping = {}      # 精确匹配的列（code / barcode）
    for nid, code, kind, parent_id in nodes:
        if kind == "device" and code in dev_map:
            mapping[code] = dev_map[code]
        elif kind == "transfer_point":
            for old, new in dev_map.items():
                if code.startswith(old + "-"):
                    mapping[code] = new + code[len(old):]
                    break

    # 驱动编号：DRV-SIM-<设备旧码> -> DRV-SIM-<设备新码>
    for old, new in dev_map.items():
        mapping["DRV-SIM-" + old] = "DRV-SIM-" + new

    # 设备池编号：去掉尾部的 -02
    for row in psql("SELECT code FROM device_pool WHERE tenant_id=1 AND island_id=%s" % island_id):
        code = row[0]
        if code.endswith("-02"):
            mapping[code] = code[:-3]

    # 板条码（仿真物料）**单独一套映射**：它是条码，不是编码
    plate_map = {}
    for row in psql("SELECT barcode FROM plate WHERE tenant_id=1 AND barcode LIKE '%-02-%'"):
        old = row[0]
        plate_map[old] = old.replace("-02-", "-", 1)
    # 流程变量里存的**模拟板条码**（扫码参数 ${magPlateN} 取的就是它）—— 漏了就扫码对不上
    for row in psql("SELECT id, default_value FROM workflow_variable"
                    " WHERE workflow_id IN (SELECT id FROM workflow WHERE island_id=%s)"
                    " AND default_value LIKE '%-02-%'" % island_id):
        old = row[1]
        if old not in plate_map:
            plate_map[old] = old.replace("-02-", "-", 1)

    if not mapping and not plate_map:
        print("✔ 没有需要改的编号（已是现场编号）")
        return

    print("══ 改名计划（%s）══" % ("执行" if args.apply else "dry-run，不改库"))
    for old, new in sorted(mapping.items()):
        print("   %-34s → %s" % (old, new))
    for old, new in sorted(plate_map.items()):
        print("   %-34s → %s  (板条码)" % (old, new))

    # 冲突检查：一次查完
    all_new = [n for _, n in sorted(mapping.items())] + [n for _, n in sorted(plate_map.items())]
    vals = ",".join("('%s')" % n.replace("'", "''") for n in all_new)
    conflicts = psql("""
        SELECT 'resource_node', x.c FROM (VALUES %s) AS x(c)
         WHERE EXISTS (SELECT 1 FROM resource_node WHERE tenant_id=1 AND code=x.c)
        UNION ALL
        SELECT 'device_instance', x.c FROM (VALUES %s) AS x(c)
         WHERE EXISTS (SELECT 1 FROM device_instance WHERE tenant_id=1 AND code=x.c AND deleted_at IS NULL)
        UNION ALL
        SELECT 'driver', x.c FROM (VALUES %s) AS x(c)
         WHERE EXISTS (SELECT 1 FROM driver WHERE tenant_id=1 AND code=x.c)
        UNION ALL
        SELECT 'device_pool', x.c FROM (VALUES %s) AS x(c)
         WHERE EXISTS (SELECT 1 FROM device_pool WHERE tenant_id=1 AND code=x.c)
        UNION ALL
        SELECT 'plate', x.c FROM (VALUES %s) AS x(c)
         WHERE EXISTS (SELECT 1 FROM plate WHERE tenant_id=1 AND barcode=x.c)
    """ % (vals, vals, vals, vals, vals))
    if conflicts:
        for tbl, code in conflicts:
            print("✘ 新编号已被占用（%s）：%s" % (tbl, code))
        sys.exit("✘ 改名中止（先处理占用）")

    if not args.apply:
        print("\n（dry-run 结束；要真改加 --apply）")
        return

    pairs = sorted(mapping.items(), key=lambda kv: -len(kv[0]))
    plate_pairs = sorted(plate_map.items(), key=lambda kv: -len(kv[0]))
    # jsonb 列只替换**设备码**（点位码是设备码 + 后缀 ⇒ 连带替掉）
    jsonb_pairs = sorted(dev_map.items(), key=lambda kv: -len(kv[0]))

    def q(v):
        return "'%s'" % v.replace("'", "''")

    def case_expr(col, ps):
        # ⚠ 必须有 ELSE：否则不在映射里的行会被写成 NULL
        return "CASE %s\n%s\n        ELSE %s END" % (col, "\n".join(
            "        WHEN %s THEN %s" % (q(o), q(n)) for o, n in ps), col)

    def jsonb_expr(col, ps):
        # 文本子串替换后再转回 jsonb（CASE 对 jsonb 不成立）
        # ⚠ 必须**嵌套**：replace(replace(x, a, b), c, d) —— 用 || 会把结果拼起来（JSON 直接坏掉）
        expr = "%s::text" % col
        for o, n in ps:
            expr = "replace(%s, %s, %s)" % (expr, q(o), q(n))
        return "(%s)::jsonb" % expr

    def text_expr(col, ps):
        # 纯文本列的嵌套替换（节点显示名用）
        expr = col
        for o, n in ps:
            expr = "replace(%s, %s, %s)" % (expr, q(o), q(n))
        return expr

    sub = ("WITH RECURSIVE sub AS (SELECT id FROM resource_node WHERE tenant_id=1 AND id=%s"
           " UNION SELECT r.id FROM resource_node r JOIN sub s ON r.parent_id=s.id"
           " WHERE r.tenant_id=1) SELECT id FROM sub")

    stmts = ["BEGIN"]
    stmts.append("UPDATE resource_node t SET code = %s WHERE t.tenant_id=1 AND t.id IN (%s)"
                 % (case_expr("t.code", pairs), sub % island_id))
    stmts.append("UPDATE resource_node t SET path = (SELECT p.path || '/' || t.code"
                 " FROM resource_node p WHERE p.id=t.parent_id)"
                 " WHERE t.tenant_id=1 AND t.id IN (%s) AND t.parent_id IS NOT NULL" % (sub % island_id))
    stmts.append("UPDATE device_instance di SET code = %s"
                 " WHERE di.tenant_id=1 AND di.deleted_at IS NULL AND di.resource_id IN"
                 " (SELECT id FROM resource_node WHERE tenant_id=1 AND parent_id=%s AND kind='device')"
                 % (case_expr("di.code", pairs), island_id))
    stmts.append("UPDATE driver d SET code = %s"
                 " WHERE d.tenant_id=1 AND d.id IN (SELECT DISTINCT di.driver_id"
                 " FROM device_instance di JOIN resource_node c ON c.id=di.resource_id"
                 " WHERE di.tenant_id=1 AND c.parent_id=%s AND c.kind='device')"
                 % (case_expr("d.code", pairs), island_id))
    # 设备池：先删脚本重跑留下的同义池（尾横杠那三个），其余去掉标记
    stmts.append("DELETE FROM device_pool_member WHERE pool_id IN"
                 " (SELECT id FROM device_pool WHERE tenant_id=1 AND island_id=%s AND code LIKE '%%-')" % island_id)
    stmts.append("DELETE FROM device_pool WHERE tenant_id=1 AND island_id=%s AND code LIKE '%%-'" % island_id)
    stmts.append("UPDATE device_pool p SET code = %s WHERE p.tenant_id=1 AND p.island_id=%s"
                 % (case_expr("p.code", pairs), island_id))
    # 流程：节点参数 + 流程图（jsonb ⇒ 文本替换）
    # ⚠ **节点显示名（workflow_node.name）也要改**：界面上节点标题就是它
    #   （2026-11-18 实测漏改 ⇒ 设计器里写"P_AUTO-02-1 洗脱"而设备叫"P_AUTO-1"）
    stmts.append("UPDATE workflow_node wn SET name = %s"
                 " WHERE wn.tenant_id=1 AND wn.workflow_id IN"
                 " (SELECT id FROM workflow WHERE tenant_id=1 AND island_id=%s)"
                 % (text_expr("wn.name", jsonb_pairs), island_id))
    stmts.append("UPDATE workflow_node wn SET params = %s"
                 " WHERE wn.tenant_id=1 AND wn.workflow_id IN"
                 " (SELECT id FROM workflow WHERE tenant_id=1 AND island_id=%s)"
                 % (jsonb_expr("wn.params", jsonb_pairs), island_id))
    stmts.append("UPDATE workflow w SET graph = %s WHERE w.tenant_id=1 AND w.island_id=%s"
                 % (jsonb_expr("w.graph", jsonb_pairs), island_id))
    # 板条码（仿真物料）+ 它的各处引用
    for tbl, col in (("plate", "barcode"), ("position_state", "plate_id"),
                      ("transfer_transaction", "plate_barcode"),
                      ("position_evidence", "plate_barcode"),
                      ("plate_location_audit", "barcode"),
                      ("plate_barcode_alias", "alias")):
        stmts.append("UPDATE %s t SET %s = %s WHERE t.tenant_id=1 AND t.%s LIKE '%%-02-%%'"
                     % (tbl, col, case_expr("t." + col, plate_pairs), col))
    stmts.append("COMMIT")

    env = dict(os.environ, PGPASSWORD=DB_PASS)
    p = subprocess.run(
        ["psql", "-h", DB_HOST, "-p", DB_PORT, "-U", DB_USER, "-d", DB_NAME,
         "-v", "ON_ERROR_STOP=1", "-q", "-c", ";\n".join(stmts) + ";"],
        capture_output=True, text=True, env=env)
    if p.returncode != 0:
        print(p.stdout[-2000:])
        sys.exit("✘ 改名事务失败（已回滚）：\n" + p.stderr.strip())
    print("\n✔ 改名完成（一个事务）")

    left = psql("SELECT count(*) FROM resource_node WHERE tenant_id=1 AND id IN (%s)"
                " AND code LIKE '%%-02-%%'" % (sub % island_id))
    print("   岛子树内残留 -02- 节点：%s" % left[0][0])
    left2 = psql("SELECT count(*) FROM workflow_node WHERE workflow_id IN"
                 " (SELECT id FROM workflow WHERE island_id=%s) AND params::text LIKE '%%-02-%%'" % island_id)
    print("   流程节点参数残留：%s" % left2[0][0])
    print("\n下一步：停旧 sidecar（pkill -f 'device-code P_AUTO-02-'），"
          "再用 LABOS_DEV_TAG= 跑 bring-extraction-station-online.py 重启模拟器与侧车。")


if __name__ == "__main__":
    main()
