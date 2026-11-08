-- ============================================================================
-- N-08：Handoff 确认与人工窗口直接联动（2026-11-09）
-- confirmed Handoff 必须带一个已决策的 manual_window 记录 + L-01 介入记录。
-- ============================================================================

ALTER TABLE experiment_handoff
    ADD COLUMN IF NOT EXISTS manual_window_id BIGINT;
