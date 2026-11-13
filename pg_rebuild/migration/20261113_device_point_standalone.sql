-- =============================================================================
-- 培养箱 / 耗材堆栈：网格之外的「单独位置」+ 位置标识（2026-11-13）
-- 设计：docs/architecture/位置与耗材管理-设计_20261113.md §9
--
-- 背景：网格点位用 device_point.grid_row/grid_col 表达「几行几列」。
-- 但培养箱/堆栈还需要一个**不属于网格**的位置：机械臂只在这里放/取板，
-- 它同时是进口和出口（{设备}-IN 装载口）。此前它只是「一条没有 grid 值的数据」，
-- 页面按网格渲染时会被整个漏掉；也没有独立于 resource_node.code 的位置标识
-- （code 是系统唯一编码，现场贴的标签不该改它）。
--
-- 本迁移：
--   · position_label  位置标识（人可读，可编辑；NULL = 页面回退显示 code）
--   · is_standalone   是否网格之外的单独位置（进出口/装载口）
--   · 把已有装载口回填为单独位置，并清掉误写的 grid_row/grid_col
--
-- 幂等：ADD COLUMN IF NOT EXISTS；回填只补 NULL 的 label，不覆盖现场改名。
-- ⚠️ 基线 90_all_in_one.sql 没有 device_point 建表语句（只有 template），
--    重建库时按顺序先跑 20261113_device_point.sql / _attrs.sql，再跑本文件。
-- =============================================================================
BEGIN;

ALTER TABLE device_point
  ADD COLUMN IF NOT EXISTS position_label varchar(64),
  ADD COLUMN IF NOT EXISTS is_standalone boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN device_point.position_label IS
  '位置标识：现场贴在设备上的名称（如「X229 进出口」）。空 = 页面回退用 resource_node.code；与系统编码互相独立，改它不影响任何关联';
COMMENT ON COLUMN device_point.is_standalone IS
  '网格之外的单独位置：培养箱/耗材堆栈的进口/出口装载口（不占 grid_row/grid_col，机械臂在此放/取板）';

-- 已有装载口回填（三台：ID-X229 / ID-Hotel12H-1 / ID-Hotel12H-2）。
-- 判据用 code 后缀 / attr.source / note 三者取或，避免某台设备漏标。
UPDATE device_point dp
   SET is_standalone = true,
       position_label = COALESCE(dp.position_label, r.code),
       grid_row = NULL,
       grid_col = NULL,
       updated_at = now()
  FROM resource_node r
 WHERE r.id = dp.point_resource_id
   AND r.tenant_id = dp.tenant_id
   AND dp.point_role <> 'internal'
   AND (r.code LIKE '%-IN'
        OR r.attr->>'source' = 'load-port'
        OR COALESCE(dp.note, '') LIKE '%装载口%');

COMMIT;

-- 核对（只读）
SELECT d.code AS device_code, r.code AS point_code,
       dp.position_label, dp.is_standalone,
       dp.point_role, dp.is_entry, dp.is_exit,
       dp.grid_row, dp.grid_col
  FROM device_point dp
  JOIN resource_node r ON r.id = dp.point_resource_id
  LEFT JOIN resource_node d ON d.id = r.parent_id
 WHERE dp.is_standalone
 ORDER BY d.code, r.code;
