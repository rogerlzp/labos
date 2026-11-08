-- ============================================================================
-- P-06 V2 收尾：PAUSING 异步中间态（2026-11-08 第四切片）
--
-- 受理时写 control_state=PAUSE_REQUESTED + pause_requested_at=now()；
-- 调度器每 tick 把「已受理超过 5 秒、仍在跑」的 run 标成 PAUSING（可观测中间态），
-- 真正收敛（paused/NONE）仍由执行器在动作边界 settle 完成。
-- ============================================================================

ALTER TABLE experiment_run
    ADD COLUMN IF NOT EXISTS pause_requested_at TIMESTAMPTZ;

COMMENT ON COLUMN experiment_run.pause_requested_at IS
    '暂停受理时间（P-06 V2 PAUSING 中间态判据；settle/resume/converge 时清空）';

CREATE INDEX IF NOT EXISTS ix_experiment_run_pausing
    ON experiment_run (tenant_id, pause_requested_at)
    WHERE control_state = 'PAUSE_REQUESTED';
