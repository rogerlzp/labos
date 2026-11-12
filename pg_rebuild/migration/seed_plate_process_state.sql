-- plate_process_state 推荐集 seed（2026-11-12 产品确认：不含 sealed，sealed 归 seal_state）
-- 口径（设计稿 §2.2）：process_state = 整板在工艺中的阶段/处置状态，不表达孔级内容；
-- seal_state（none/sealed）是 process_state 之下的细分维度，默认 none。
\set ON_ERROR_STOP on
BEGIN;
INSERT INTO plate_process_state (tenant_id, code, display_name, sort_order, enabled) VALUES
  (1, 'raw',          '未处理',   10, true),
  (1, 'ready',        '已就绪',   20, true),
  (1, 'in_progress',  '处理中',   30, true),
  (1, 'completed',    '已完成',   90, true),
  (1, 'discarded',    '已废弃',   99, true)
ON CONFLICT (tenant_id, code) DO NOTHING;
COMMIT;
