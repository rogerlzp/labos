-- P3：客户 §3 的 8 阶段编码（S0..S9）
--
-- 为什么单独一列而不是塞 params：
--   甘特 / 运行看板 / 模板层 / 验收统计都要**按阶段聚合**，塞进 jsonb 每次都要
--   `params->>'stage_code'` 全表算，且没法建有效索引。
--
-- 阶段编码取客户文档 §3 自己的编号：
--   S1 磁套加载 / S2 裂解液+蛋白酶K分液 / S3 磁珠分装 / S4 磁吸+W1
--   S5 W2 / S6 W3 / S7 洗脱 / S8 批次收尾
--   S0 启动校验、S9 连续循环/耗材预警（客户 §2 / §3 阶段9，非 §3 的 8 阶段）
--
-- 空值 = 未分阶段（存量 136 条流程保持原样，不阻塞）。

ALTER TABLE workflow_node ADD COLUMN IF NOT EXISTS stage_code text;

COMMENT ON COLUMN workflow_node.stage_code IS
  '客户§3阶段编码 S1..S8（S0启动校验/S9循环预警）；NULL=未分阶段';

-- 按阶段聚合（甘特/看板/模板统计）走这个索引
CREATE INDEX IF NOT EXISTS idx_workflow_node_stage
  ON workflow_node (workflow_id, stage_code);
