-- ============================================================================
-- P-11：run_status 补 'cancelled'（词表有、库不认）
--
-- 背景（2026-11-09 核实）：`StatusVocabulary` 的 run scope 里定义了
-- `cancelled`（"已取消"，终态），并把它映射到 experiment 的 `cancelled`；
-- 但 `run.status` 用的是 PG 枚举 `run_status`，里面**没有**这个值
-- ⇒ 任何按词表写 `run.status='cancelled'` 的代码都会在运行期报
-- `invalid input value for enum run_status: "cancelled"`。
--
-- 处理方向与 L-02 一致（为容纳新码扩列长）：**让库跟上词表**，而不是把
-- 已经对外承诺的状态从词表里删掉。
--
-- 说明：
--   · `ALTER TYPE ... ADD VALUE` 不可回滚（PG 限制），但它是纯增量：
--     加一个枚举值不会影响任何既有行/查询；回滚方式是不再使用该值。
--   · 当前**没有**写入路径会写 run.status='cancelled'，本条只为消除
--     "词表与库不一致"这颗雷，不改变任何行为。
--
-- 幂等。
-- ============================================================================

ALTER TYPE run_status ADD VALUE IF NOT EXISTS 'cancelled';

COMMENT ON TYPE run_status IS
    'run 生命周期：draft/pending/planned/queued/running/paused/completed/failed/aborted/skipped/quarantined/cancelled。'
    '与 StatusVocabulary 的 run scope 双向一致（P-11 核实，见 StatusVocabularyDbSelfTest）。';
