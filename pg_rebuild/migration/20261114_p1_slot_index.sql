-- P1 · 耗材栈槽位稳定编码（slot_index）
-- 60 槽耗材栈的槽位寻址：slot_index 是稳定 1..N 编码（与 grid_row/grid_col/order_in_device 正交，
-- 用于「从第 N 槽取哪块板」的精确表达，不随插入顺序漂移）。

ALTER TABLE device_point ADD COLUMN IF NOT EXISTS slot_index integer;

COMMENT ON COLUMN device_point.slot_index IS
  '耗材栈槽位稳定编码（1..N）；精确点位转运用「第 N 槽」而不是「第一个空位」';
