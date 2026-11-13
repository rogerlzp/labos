-- 点位属性扩展（v2）：培养箱/堆栈/中转位的排、列、高度、可放板型。
-- 幂等：IF NOT EXISTS，可反复执行。
ALTER TABLE device_point
  ADD COLUMN IF NOT EXISTS grid_row smallint,
  ADD COLUMN IF NOT EXISTS grid_col smallint,
  ADD COLUMN IF NOT EXISTS max_height_mm numeric,
  ADD COLUMN IF NOT EXISTS plate_types varchar(32)[];

COMMENT ON COLUMN device_point.grid_row IS '点位所在排（1 起，培养箱的层 / 堆栈的层）';
COMMENT ON COLUMN device_point.grid_col IS '点位所在列（1 起，同一层第几列）';
COMMENT ON COLUMN device_point.max_height_mm IS '该点位最高能放多高的板（mm，null=不限制）';
COMMENT ON COLUMN device_point.plate_types IS '能放的板类型集合（如 {pcr96,deep96}；空=不限制）';
