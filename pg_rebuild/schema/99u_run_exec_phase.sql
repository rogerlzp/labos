-- ============================================================================
-- P-09：run.exec_phase —— 执行阶段标记（PREPARED / DISPATCH_STARTED / SETTLED）
--
-- 背景（整改计划 Batch 0.1，2026-11-07 提出、2026-11-09 落实）：
--   B-01 之后「执行期异常不再退回重跑」，但**崩溃**仍然回答不了一个问题：
--   重启后看到一条 status='running' 的 run，它到底
--     ① 一条设备命令都没发过（可以安全地当没发生、清理或重跑），还是
--     ② 已经派发过命令（板可能已经被搬走，只能隔离 + 人工核对）？
--   以前只能去数 run_command 反推，而 run_command 是**步结束才落库**的 ——
--   崩在「已下发、未回包」那个窗口时，数出来是 0，反推结果为「没动过」，
--   恰好是最危险的那种误判。
--
--   exec_phase 在**派发前**就升级，所以它对上面那个窗口是诚实的：
--     PREPARED          建单完成，还没派发过任何设备命令
--     DISPATCH_STARTED  已经派发过（物理结果可能未知）
--     SETTLED           执行体已收尾（completed/failed/aborted 等终态）
--
--   paused 的 run **不进** SETTLED：执行体是退出了，但这一单没走完，
--   恢复时还要接着跑，语义上不能叫 settled。
--
-- 历史行回填（列有 DEFAULT，新加时会把老行都填成 PREPARED，必须纠正）：
--   终态行 → SETTLED；有过 run_command → DISPATCH_STARTED；其余 → PREPARED。
--   只动 exec_phase='PREPARED' 的行，可重复执行且收敛。
-- ============================================================================

ALTER TABLE run ADD COLUMN IF NOT EXISTS exec_phase VARCHAR(24) NOT NULL DEFAULT 'PREPARED';

COMMENT ON COLUMN run.exec_phase IS
    'P-09 执行阶段：PREPARED=未派发过设备命令 / DISPATCH_STARTED=已派发过（物理结果可能未知）/ '
    'SETTLED=执行体已收尾。用于崩溃后区分「可以安全重来」与「只能隔离核对」，'
    '与 status 正交（paused 停在 DISPATCH_STARTED，不算 settled）';

UPDATE run r
   SET exec_phase = CASE
           WHEN r.status IN ('completed', 'failed', 'aborted', 'skipped', 'cancelled') THEN 'SETTLED'
           WHEN EXISTS (SELECT 1 FROM run_command c WHERE c.run_id = r.id) THEN 'DISPATCH_STARTED'
           ELSE 'PREPARED'
       END
 WHERE r.exec_phase = 'PREPARED';
