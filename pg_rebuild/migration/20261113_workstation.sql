-- P2-1 · 工位层：resource_kind 枚举加 'workstation'
-- ⚠ PG 限制：ALTER TYPE ... ADD VALUE 不能在事务块内「使用」该新值，本脚本单独执行（不 BEGIN/COMMIT）。
--   且 ADD VALUE IF NOT EXISTS 需 PG 12+（当前 PG 17，OK）。
DO $$ BEGIN
  ALTER TYPE resource_kind ADD VALUE IF NOT EXISTS 'workstation';
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

COMMENT ON TABLE device_point IS '设备点位配置层；point_kind：nest=坑位/deck_in=台面内/deck_out=台面外/waste=废弃位；工位实体是 resource_node(kind=workstation)，经 point_resource_id 关联';

-- ─────────────────────────────────────────────────────────────────────────────
-- P1-5 · 工位种子（幂等）：ID-2011 液体工作站下 4 个工位 WS1..4。
--
-- P0-1 口径（2026-11-14 独立评审）：deck_in=台面内工位，机械臂够不到，
--   由工作站内部机构取放 ⇒ point_role='internal'、robot_reachable=false、
--   is_entry/is_exit=false（与既有 272 个 internal 点位同口径）。
--   机械臂只把板放到 deck_out（ID-2011-IN），不直接放进台面内工位。
-- ─────────────────────────────────────────────────────────────────────────────

-- ① 工位资源（resource_node，kind=workstation，挂到 ID-2011 下）
INSERT INTO resource_node (tenant_id, code, name, kind, parent_id, capacity, sort_order)
SELECT 1, v.code, v.name, 'workstation'::resource_kind, d.id, 1, v.slot
  FROM (VALUES ('ID-2011-WS1', '工位 1', 1),
               ('ID-2011-WS2', '工位 2', 2),
               ('ID-2011-WS3', '工位 3', 3),
               ('ID-2011-WS4', '工位 4', 4)) AS v(code, name, slot)
 CROSS JOIN resource_node d
 WHERE d.tenant_id = 1 AND d.code = 'ID-2011' AND d.kind = 'device' AND d.deleted_at IS NULL
   AND NOT EXISTS (SELECT 1 FROM resource_node w
                    WHERE w.tenant_id = 1 AND w.code = v.code);

-- ② 工位点位（device_point，point_kind=deck_in，机器人不可达）
INSERT INTO device_point (tenant_id, device_resource_id, point_resource_id,
                          point_role, robot_reachable, is_entry, is_exit,
                          workstation_slot, point_kind, is_standalone)
SELECT 1, d.id, w.id, 'internal', false, false, false,
       (substring(w.code FROM 'WS([0-9]+)$'))::int, 'deck_in', false
  FROM resource_node d
  JOIN resource_node w ON w.parent_id = d.id AND w.kind = 'workstation'
 WHERE d.tenant_id = 1 AND d.code = 'ID-2011' AND d.kind = 'device' AND d.deleted_at IS NULL
   AND NOT EXISTS (SELECT 1 FROM device_point dp WHERE dp.point_resource_id = w.id);
