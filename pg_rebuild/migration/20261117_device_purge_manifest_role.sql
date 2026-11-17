-- =============================================================================
-- 2026-11-17 · 设备编号可重用 + 同角色多块板（两处唯一约束的语义修正）
--
-- 背景（都是 2026-11-17 从零建岛实测踩出来的，不是理论问题）：
--
-- ① 设备「删除」只有软删（`softDeleteDeviceInstance`，不删 `resource_node`），
--    而 `uk_device_instance` 是 **UNIQUE (tenant_id, code)** —— 不看 deleted_at。
--    ⇒ 软删的那行**仍然占着编号** ⇒ 「删掉一台设备，再用同一个编号建一台」必然 409。
--    ⇒ 现场表现：设备明明删了，重建就报「编号已被占用」，而界面上一台都没有。
--    ⇒ 修法：唯一性只对**活着的**实例生效（部分唯一索引 + deleted_at IS NULL）。
--       设备型号（device 表）本身与实例分开，不受影响。
--
-- ② `uk_manifest_item_role` = UNIQUE (template_id, role_code) ⇒ 一个模板里
--    **同一角色只能有一行** ⇒ 「4 台提取仪各一块磁套板」写不出 4 行。
--    而 manifest_placement 是**每个 manifest_item 一行** ⇒ 一行一角色 ⇒ 4 块板
--    只能配 1 个位置 ⇒ 其余 3 块板无处可放。
--    ⇒ 修法：放开这个唯一性（同角色可多行），让「一行 = 一块板 + 一个位置」成立。
--       物料清单的「数量」仍由 quantity 表达，两者并存不冲突。
--
-- 幂等：两条都可重复执行（先 DROP 再 CREATE）。
-- =============================================================================
\set ON_ERROR_STOP on

-- ---- ① device_instance：编号只对活着的实例唯一 -------------------------
ALTER TABLE device_instance DROP CONSTRAINT IF EXISTS uk_device_instance;
CREATE UNIQUE INDEX IF NOT EXISTS uk_device_instance
    ON device_instance (tenant_id, code) WHERE deleted_at IS NULL;

-- ---- ② manifest_item：同角色可多行 ------------------------------------
DROP INDEX IF EXISTS uk_manifest_item_role;
CREATE INDEX IF NOT EXISTS ix_manifest_item_role
    ON manifest_item (template_id, role_code);
