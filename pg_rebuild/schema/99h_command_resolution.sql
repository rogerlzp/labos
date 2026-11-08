-- ============================================================================
-- P-07.2：UNKNOWN 命令的人工核对处置（2026-11-08）
-- 账本行被启动对账标 UNKNOWN 后，必须由人确认物理结果，再落 ACKED/FAILED。
-- ============================================================================

ALTER TABLE command_ledger
    ADD COLUMN IF NOT EXISTS resolved_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS resolved_by BIGINT;
