-- ============================================================================
-- R4：设备命令的可观测阶段（command_ledger.phase）
--
-- 背景：command_ledger 原来只有 state（DISPATCH_STARTED/ACKED/FAILED/UNKNOWN），
-- 那是「账本终态」，回答不了「命令发出去之后到底走到哪一步」。
-- 设计要求（docs/runtime-consistency/02 §1）要
--   PENDING → SENT → RECEIVED → EXECUTING → DONE，异常落 UNKNOWN。
--
-- 现状约束：驱动是**同步阻塞**调用
--   CommandLedger.begin → driver.execute() → CommandLedger.finish
-- 拿不到设备 ACK / 「开始执行」回调，所以真实可达的是
--   PENDING（已入账）→ SENT（已下发）→ DONE（回包）
-- RECEIVED / EXECUTING 先落枚举与索引，等驱动支持流式回包后再推进
-- （届时只改 DriverStepExecutor 的推进点，不动表）。
--
-- 单调性：只允许序号递增（array_position 比较），倒退/乱序 UPDATE 命中 0 行；
-- DONE / UNKNOWN 是终态，之后不再接受推进。
-- ============================================================================

ALTER TABLE command_ledger
    ADD COLUMN IF NOT EXISTS phase varchar(16) NOT NULL DEFAULT 'PENDING';

COMMENT ON COLUMN command_ledger.phase IS
    '命令阶段（R4）：PENDING→SENT→RECEIVED→EXECUTING→DONE，异常 UNKNOWN；只进不退，DONE/UNKNOWN 为终态。当前同步驱动只能观测 PENDING/SENT/DONE，RECEIVED/EXECUTING 预留（驱动无 ACK 回调）';

-- 未终态的命令是「在途」，启动对账要扫这一批
CREATE INDEX IF NOT EXISTS ix_command_ledger_phase_open
    ON command_ledger (tenant_id, phase)
    WHERE phase NOT IN ('DONE', 'UNKNOWN');

-- 回填存量：按 state 反推一次（幂等，只动还是默认值的行）
UPDATE command_ledger
   SET phase = CASE
                 WHEN state IN ('ACKED', 'FAILED') THEN 'DONE'
                 WHEN state = 'UNKNOWN'            THEN 'UNKNOWN'
                 ELSE 'SENT'                       -- DISPATCH_STARTED：已下发未回包
               END
 WHERE phase = 'PENDING';
