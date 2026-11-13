-- P2-1 · 工位层：resource_kind 枚举加 'workstation'
-- ⚠ PG 限制：ALTER TYPE ... ADD VALUE 不能在事务块内「使用」该新值，本脚本单独执行（不 BEGIN/COMMIT）。
--   且 ADD VALUE IF NOT EXISTS 需 PG 12+（当前 PG 17，OK）。
DO $$ BEGIN
  ALTER TYPE resource_kind ADD VALUE IF NOT EXISTS 'workstation';
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

COMMENT ON TABLE device_point IS '设备点位配置层；point_kind：nest=坑位/deck_in=台面内/deck_out=台面外/waste=废弃位；工位实体是 resource_node(kind=workstation)，经 point_resource_id 关联';
