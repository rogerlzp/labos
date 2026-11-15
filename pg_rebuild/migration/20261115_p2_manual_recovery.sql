-- P2 阶段 C · L4 物理故障需人工恢复（2026-11-15）
-- 搅拌故障/配对缺失导致的 paused_fault 不得由心跳自动恢复；
-- 必须重新取证（isRecovered）+ 人工确认后手动恢复。

ALTER TABLE run ADD COLUMN IF NOT EXISTS needs_manual_recovery BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN run.needs_manual_recovery IS
  'true = 该 run 的暂停是物理故障（如搅拌故障），禁止心跳自动恢复，必须人工确认 + 重新取证。';
