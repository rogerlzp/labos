#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""物料生命周期（§4.5.2）卸载 · 判别性验收。

口径：实验结束时，lifecycle='process' 的物料自动卸载（清 position_state + 置 unloaded），
lifecycle='inventory' 的物料保持原位不动。

用例：
  ① 造一块 process 板 + 一块 inventory 板，都挂到同一个 run 的 manifest 并 loaded；
  ② 调 POST /api/experiment-runs/{runId}/unload-process-materials；
  ③ 断言：process 板 → placement=unloaded + position_state 清空；
          inventory 板 → placement 仍 loaded + position_state 不变。

用法：
  LABOS_DB_HOST=... LABOS_DB_NAME=... LABOS_DB_USER=... LABOS_DB_PASS=... \
  python3 scripts/verify-material-lifecycle.py
"""
import importlib.util
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_spec = importlib.util.spec_from_file_location(
    "core_scenarios", os.path.join(ROOT, "scripts", "core-scenarios.py"))
cs = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cs)
api = cs.api
q = cs.q

PASS, FAIL = 0, 0


def ok(msg, detail=""):
    global PASS
    PASS += 1
    print("  \033[32m✓\033[0m " + msg + (("（%s）" % detail) if detail else ""))


def bad(msg, detail=""):
    global FAIL
    FAIL += 1
    print("  \033[31m✗\033[0m " + msg + (("（%s）" % detail) if detail else ""))


def preclean():
    # 按本脚本前缀精确清 position_state，且必须放在删 plate / placement 之前
    #（旧版放在最后、且只按「所有 unloaded placement 的点位」通配删：inventory 板的
    #  placement 仍是 loaded ⇒ 每跑一次留 1 条 VERIFY-LC-INV 残留；通配条件还可能误删
    #  其它 run 仍占用的点位）。
    q("DELETE FROM position_state WHERE plate_id LIKE 'VERIFY-LC-%' "
      "OR content_id IN (SELECT id FROM plate WHERE barcode LIKE 'VERIFY-LC-%');")
    q("DELETE FROM manifest_placement WHERE run_id IN "
      "(SELECT id FROM experiment_run WHERE experiment_id IN "
      "(SELECT id FROM experiment WHERE experiment_no LIKE 'VERIFY-LC-%'));")
    q("DELETE FROM experiment_run WHERE experiment_id IN "
      "(SELECT id FROM experiment WHERE experiment_no LIKE 'VERIFY-LC-%');")
    q("DELETE FROM manifest_item WHERE template_id IN "
      "(SELECT id FROM experiment_template WHERE template_no LIKE 'VERIFY-LC-%');")
    q("DELETE FROM experiment WHERE experiment_no LIKE 'VERIFY-LC-%';")
    q("DELETE FROM experiment_template WHERE template_no LIKE 'VERIFY-LC-%';")
    q("DELETE FROM plate WHERE barcode LIKE 'VERIFY-LC-%';")


def main():
    token = api("/api/auth/login", "POST",
                {"username": "admin", "password": "admin123"})["token"]
    preclean()

    # 只挑空闲点位：写入用 ON CONFLICT DO UPDATE，若取到其它 run 正在占用的点位会覆盖生产数据。
    points = q(
        "SELECT dp.id, dp.point_resource_id FROM device_point dp "
        "LEFT JOIN position_state ps "
        "  ON ps.resource_id = dp.point_resource_id AND ps.tenant_id = 1 "
        "WHERE ps.resource_id IS NULL "
        "   OR (ps.plate_id IS NULL AND ps.content_kind IS NULL "
        "       AND ps.content_id IS NULL AND ps.occupied_by_run IS NULL) "
        "ORDER BY dp.id LIMIT 2")
    rows = [r.split("|") for r in points.split("\n") if r.strip()]
    if len(rows) < 2:
        bad("空闲设备点位不足 2 个，无法跑判别性用例")
        return 1
    pid1, pres1 = int(rows[0][0]), int(rows[0][1])
    pid2, pres2 = int(rows[1][0]), int(rows[1][1])

    # 两块板：process + inventory
    plate_proc = q("INSERT INTO plate (tenant_id, barcode, lifecycle) "
                   "VALUES (1, 'VERIFY-LC-PROC', 'process') RETURNING id")
    plate_inv = q("INSERT INTO plate (tenant_id, barcode, lifecycle) "
                  "VALUES (1, 'VERIFY-LC-INV', 'inventory') RETURNING id")
    p_proc = int(plate_proc.strip().split("\n")[0])
    p_inv = int(plate_inv.strip().split("\n")[0])

    # 模板 + 实验 + run + manifest_item + placement
    tid = int(q("INSERT INTO experiment_template (tenant_id, template_no, name, island_id) "
                "VALUES (1, 'VERIFY-LC-TPL', 'VERIFY-LC-TPL', 1000005) RETURNING id")
              .strip().split("\n")[0])
    eid = int(q("INSERT INTO experiment (tenant_id, experiment_no, name) "
                "VALUES (1, 'VERIFY-LC-EXP', 'VERIFY-LC-EXP') RETURNING id")
              .strip().split("\n")[0])
    run_id = int(q("INSERT INTO experiment_run (tenant_id, experiment_id) "
                   "VALUES (1, %d) RETURNING id" % eid).strip().split("\n")[0])
    mi_proc = int(q("INSERT INTO manifest_item (tenant_id, template_id, role_code, material_kind, material_ref) "
                    "VALUES (1, %d, 'PLATE-A', 'plate', %d) RETURNING id" % (tid, p_proc))
                  .strip().split("\n")[0])
    mi_inv = int(q("INSERT INTO manifest_item (tenant_id, template_id, role_code, material_kind, material_ref) "
                   "VALUES (1, %d, 'PLATE-B', 'plate', %d) RETURNING id" % (tid, p_inv))
                 .strip().split("\n")[0])
    q("INSERT INTO manifest_placement (tenant_id, run_id, manifest_item_id, position_id, status) "
      "VALUES (1, %d, %d, %d, 'loaded'), (1, %d, %d, %d, 'loaded')"
      % (run_id, mi_proc, pid1, run_id, mi_inv, pid2))
    q("INSERT INTO position_state (tenant_id, resource_id, plate_id, content_kind, content_id, occupied_by_run) "
      "VALUES (1, %d, 'VERIFY-LC-PROC', 'plate', %d, %d), (1, %d, 'VERIFY-LC-INV', 'plate', %d, %d) "
      "ON CONFLICT (tenant_id, resource_id) DO UPDATE SET plate_id=EXCLUDED.plate_id, "
      "content_kind=EXCLUDED.content_kind, content_id=EXCLUDED.content_id, occupied_by_run=EXCLUDED.occupied_by_run"
      % (pres1, p_proc, run_id, pres2, p_inv, run_id))

    print("── 用例：卸载 process 物料，inventory 保持原位 ──")
    r = api(f"/api/experiment-runs/{run_id}/unload-process-materials", "POST", token=token)
    if r.get("unloaded") == 1:
        ok("卸载返回 unloaded=1", str(r))
    else:
        bad("卸载返回非预期", str(r))

    # 断言 process 板卸载、inventory 板不动
    proc_status = q("SELECT status FROM manifest_placement WHERE manifest_item_id=%d" % mi_proc)
    inv_status = q("SELECT status FROM manifest_placement WHERE manifest_item_id=%d" % mi_inv)
    if proc_status.strip() == "unloaded":
        ok("process placement → unloaded")
    else:
        bad("process placement 未卸载", proc_status.strip())
    if inv_status.strip() == "loaded":
        ok("inventory placement 保持 loaded")
    else:
        bad("inventory placement 被误卸载", inv_status.strip())

    proc_pos = q("SELECT plate_id IS NULL AND content_kind IS NULL FROM position_state WHERE resource_id=%d" % pres1)
    inv_pos = q("SELECT plate_id IS NOT NULL FROM position_state WHERE resource_id=%d" % pres2)
    if proc_pos.strip() == "t":
        ok("process 板 position_state 已清空")
    else:
        bad("process 板 position_state 未清空", proc_pos.strip())
    if inv_pos.strip() == "t":
        ok("inventory 板 position_state 保持占用")
    else:
        bad("inventory 板 position_state 被误清", inv_pos.strip())

    preclean()
    print("\n通过 %d / 失败 %d" % (PASS, FAIL))
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
