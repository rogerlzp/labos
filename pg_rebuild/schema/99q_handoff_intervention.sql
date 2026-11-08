-- ============================================================================
-- N-08/N-10：Handoff 确认与人工介入记录强关联（2026-11-09）
-- confirmed 的 Handoff 必须带 intervention_record_id；执行门禁同时校验。
-- ============================================================================

ALTER TABLE experiment_handoff
    ADD COLUMN IF NOT EXISTS intervention_record_id BIGINT;
