-- ============================================================================
-- B-04：中止结果结构化（command_ledger.abort_state / abort_detail / abort_at）
--
-- 背景（2026-11-09）：
--   B-02 修掉了「取消本地 Future 就当作设备已停」，但只修到「不发假信号」为止：
--   中止请求究竟走到哪一步，仍然只活在一行 stderr 日志里。真实链路有五种状态，
--   二元的"停/没停"根本表达不出来：
--
--     UNSUPPORTED         驱动/协议没有中止能力（TCP 只有命令帧，没有停止帧）
--     REQUESTED           停止指令已发出，设备还没回话
--     DEVICE_ACKNOWLEDGED 设备回了话，但没有"动作已停"的证据
--     PHYSICALLY_STOPPED  设备侧确认动作已停止  ← 只有这一种算"停了"
--     UNKNOWN             发了，结果不可知（超时/断链）
--
--   这一列与 state **分开存**：state 说明"命令怎么结束的"，abort_state 说明
--   "我们为让它停下来做了什么、设备认不认"。混在一起，就又会退化成
--   「本地取消冒充设备已停」——而代价是恢复时把物理动作重放一遍。
--
-- 为什么落这张表（而不是 run_command.result）：
--   command_ledger 是**派发前**就写下的一行，中止恰好发生在"已下发、未回包"这个
--   窗口里，只有这一行当时存在。run_command 是步结束才写的，事后才补。
--
-- 全部幂等。
-- ============================================================================

ALTER TABLE command_ledger ADD COLUMN IF NOT EXISTS abort_state  VARCHAR(24);
ALTER TABLE command_ledger ADD COLUMN IF NOT EXISTS abort_detail VARCHAR(200);
ALTER TABLE command_ledger ADD COLUMN IF NOT EXISTS abort_at     TIMESTAMPTZ;

COMMENT ON COLUMN command_ledger.abort_state IS
    '中止请求结果（B-04）：UNSUPPORTED=没有中止能力 / REQUESTED=已发出未确认 / '
    'DEVICE_ACKNOWLEDGED=设备已回话但未证动作停 / PHYSICALLY_STOPPED=设备确认已停（唯一可判 aborted）/ '
    'UNKNOWN=发出但结果不可知。NULL=从未请求过中止';
COMMENT ON COLUMN command_ledger.abort_detail IS '中止结果的人读说明（驱动给出的原因）';
COMMENT ON COLUMN command_ledger.abort_at IS '最近一次中止请求的时间';

-- 已发出中止但没拿到设备确认的行 = 人工核验入口。
-- 这类命令既不能当"停成功了"（会漏掉还在动的设备），
-- 也不能当"没停"就重发（会重复执行物理动作）。
CREATE INDEX IF NOT EXISTS ix_command_ledger_abort_unconfirmed
    ON command_ledger (tenant_id, abort_at DESC)
    WHERE abort_state IS NOT NULL AND abort_state <> 'PHYSICALLY_STOPPED';
