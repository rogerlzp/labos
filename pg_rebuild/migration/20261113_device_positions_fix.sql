-- 点位模型修正（2026-11-13）：
-- 1. 机械臂（TRANSFER 设备）不要有点位 → 删除 ID-X231/ID-XRobot_A10LR-1 的 transfer_point。
-- 2. 培养箱/堆栈的存储格改为 internal（机械臂不可达，设备内部机构搬），
--    并补一个「进口/入口」装载口（both + robot_reachable）。
-- 幂等：可反复执行。

BEGIN;

-- 备份本次要改的点位配置（可回滚）
CREATE TABLE IF NOT EXISTS device_point_bak_20261113_fix AS
SELECT * FROM device_point WHERE point_resource_id IN (
  SELECT id FROM resource_node WHERE code IN
  ('ID-X231-TP1','ID-X231-TP2','ID-XRobot_A10LR-1-TP1')
) OR device_resource_id IN (200229, 504827, 504828);

-- 1. 删机械臂点位
DELETE FROM position_state WHERE resource_id IN (
  SELECT id FROM resource_node WHERE code IN
  ('ID-X231-TP1','ID-X231-TP2','ID-XRobot_A10LR-1-TP1'));
DELETE FROM device_point WHERE point_resource_id IN (
  SELECT id FROM resource_node WHERE code IN
  ('ID-X231-TP1','ID-X231-TP2','ID-XRobot_A10LR-1-TP1'));
DELETE FROM resource_node WHERE code IN
  ('ID-X231-TP1','ID-X231-TP2','ID-XRobot_A10LR-1-TP1')
  AND attr->>'source' = 'init-device-points';

-- 2. 培养箱/堆栈的存储格（grid_row 非空）→ internal（不可达）
UPDATE device_point
   SET point_role = 'internal', robot_reachable = false,
       is_entry = false, is_exit = false, updated_at = now()
 WHERE device_resource_id IN (200229, 504827, 504828)
   AND grid_row IS NOT NULL;

-- 3. 每个培养箱/堆栈补一个「进口/入口」装载口（both + 可达）
INSERT INTO resource_node (tenant_id, code, name, parent_id, kind, location, attr, sort_order)
SELECT 1, d.code || '-IN', d.code || '-IN', d.id, 'storage',
       jsonb_build_object('zone', d.code, 'point', d.code || '-IN'),
       '{"source":"load-port"}'::jsonb, 0
  FROM resource_node d
 WHERE d.tenant_id = 1 AND d.code IN ('ID-X229','ID-Hotel12H-1','ID-Hotel12H-2')
ON CONFLICT (tenant_id, code) DO NOTHING;

INSERT INTO device_point (tenant_id, device_resource_id, point_resource_id, point_role,
                          robot_reachable, is_entry, is_exit, order_in_device, note)
SELECT 1, d.id, p.id, 'both', true, true, true, 0, '进口/入口（机械臂装载口）'
  FROM resource_node d
  JOIN resource_node p ON p.parent_id = d.id AND p.code = d.code || '-IN'
 WHERE d.tenant_id = 1 AND d.code IN ('ID-X229','ID-Hotel12H-1','ID-Hotel12H-2')
ON CONFLICT (tenant_id, point_resource_id) DO UPDATE SET
  point_role='both', robot_reachable=true, is_entry=true, is_exit=true,
  order_in_device=0, note='进口/入口（机械臂装载口）', updated_at=now();

INSERT INTO position_state (tenant_id, resource_id, plate_id, updated_at)
SELECT 1, p.id, NULL, now()
  FROM resource_node d
  JOIN resource_node p ON p.parent_id = d.id AND p.code = d.code || '-IN'
 WHERE d.tenant_id = 1 AND d.code IN ('ID-X229','ID-Hotel12H-1','ID-Hotel12H-2')
ON CONFLICT (tenant_id, resource_id) DO NOTHING;

COMMIT;
