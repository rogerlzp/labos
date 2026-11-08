-- ============================================================================
-- L-02：控制状态与生命周期分离（ADR-PAUSE-008）
--
-- 背景：experiment_run.status 现在同时承载「生命周期」与「暂停过程」两件事，
--   paused 还被复用为「等待人工」。把中间态（PAUSE_REQUESTED / PAUSING /
--   RESUMING）塞进同一列，会让 status 变成随时间累积的多义字段，而它的读方
--   （页面、聚合器、门禁）远多于新的 control_state（S-04B §3.1）。
--
-- 决策（ADR-PAUSE-008，方案 B）：
--   · status 只表达生命周期，取值不变：queued/running/paused/completed/failed/aborted/cancelled
--   · 新增 control_state 表达暂停控制过程：NONE/PAUSE_REQUESTED/PAUSING/RESUMING
--   · status 列长扩容：WAITING_INTERVENTION（20 字符）现有 varchar(16) 放不下
--
-- 语义边界（本批次只授予「状态表达能力」，不改行为）：
--   本分片不改变 status 何时变为 paused（legacy 立即投影保持），故 PR-S04-A /
--   4.17 / 4.18 判据不退化；「请求已接受但未收敛时不得显示 PAUSED」的行为切换
--   属 PR-S04-B。
--
-- 幂等：类型扩容与 ADD COLUMN IF NOT EXISTS 均可重复执行。
-- ============================================================================

-- 1) 生命周期列扩容（varchar(16) -> varchar(32)）
ALTER TABLE experiment_run ALTER COLUMN status TYPE varchar(32);

-- 2) 控制状态列（默认 NONE；取值由 PR-S04-B 接线）
ALTER TABLE experiment_run ADD COLUMN IF NOT EXISTS control_state varchar(24) NOT NULL DEFAULT 'NONE';

COMMENT ON COLUMN experiment_run.control_state IS
    '暂停控制过程（与 status 生命周期分离）：NONE=无 / PAUSE_REQUESTED=请求已受理 / PAUSING=等待在途动作收敛 / RESUMING=恢复中。默认 NONE；取值由 PR-S04-B 接线。';

COMMENT ON COLUMN experiment_run.status IS
    '实验生命周期：queued=待调度 / running=执行中 / paused=已暂停（含等待人工）/ completed=完成 / failed=失败（可续跑）/ aborted=人工停止（终态）/ cancelled=排队取消（终态）。列长 32 以容纳 WAITING_INTERVENTION 等新码；暂停控制过程见 control_state（ADR-PAUSE-008）。';
