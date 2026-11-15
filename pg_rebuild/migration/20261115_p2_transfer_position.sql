-- P2 阶段 B · 搬运事务记录精确源/目标点位（2026-11-15）
-- 证据确认路径需要知道「从哪个位置取、放到哪个位置」，才能在不靠设备运动回包
-- 的情况下原子写位置并 CONFIRMED。故在 transfer_transaction 上补精确点位列。

ALTER TABLE transfer_transaction
    ADD COLUMN IF NOT EXISTS source_position_id BIGINT;
ALTER TABLE transfer_transaction
    ADD COLUMN IF NOT EXISTS target_position_id BIGINT;

COMMENT ON COLUMN transfer_transaction.source_position_id IS
  '取板前的精确位置资源 id（动作前解析并固定，动作后源位已空，无法事后重查）。';
COMMENT ON COLUMN transfer_transaction.target_position_id IS
  '目标精确位置资源 id（动作前原子预留；确认前保持预留，确认后才写占用）。';
