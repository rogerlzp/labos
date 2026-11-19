-- ============================================================================
-- K-01a：准入唯一索引（2026-11-19）
--
-- 目的：为「同一 workflow 只允许一个活跃 run」「同一 batch 只允许一个活跃
--       attempt」提供数据库级兜底。准入统一（RunAdmission）把检查放到事务外
--       之后，行锁/advisory lock 都随事务边界失效，只有唯一索引能覆盖
--       「准入通过 → 插入 run」之间的窗口。
--
-- 前置：K-01a 已定稿（见 docs/并行执行-K-01a-唯一约束方案-20261119.md §7）
--       paused     算活跃（业务 2026-11-19 确认）
--       quarantined 算活跃（业务 2026-11-19 确认）
--       batch 的 pending 保留（防御性；代码当前只写 queued/running）
--
-- 执行方式：
--     psql -v ON_ERROR_STOP=1 -U <user> -d <db> -f 20261119_k01a_active_unique.sql
--
-- ⚠ 本文件**不能**包在 BEGIN/COMMIT 里：
--    CREATE INDEX CONCURRENTLY 不允许在事务块中执行。
--    文件末尾的验证用 DO 块，那是独立语句，不影响前面的 CONCURRENTLY。
--
-- 回滚：
--     DROP INDEX CONCURRENTLY IF EXISTS uk_run_active_workflow;
--     DROP INDEX CONCURRENTLY IF EXISTS uk_batch_run_active;
--   删索引即恢复原行为，不改任何数据。
-- ============================================================================

\set ON_ERROR_STOP on

-- ============================================================================
-- 步骤 0-a：清理上次 CONCURRENTLY 失败留下的**无效索引**
--
-- CREATE INDEX CONCURRENTLY 中途失败会留下 indisvalid = false 的索引。
-- 此时 `IF NOT EXISTS` 会跳过重建，导致「以为建好了其实没用」。
-- 这里先按名精确删掉无效索引（\gexec 让 DROP 成为独立语句，可 CONCURRENTLY）。
-- ============================================================================
SELECT format('DROP INDEX CONCURRENTLY IF EXISTS %I', c.relname)
  FROM pg_index i
  JOIN pg_class  c ON c.oid = i.indexrelid
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE c.relname IN ('uk_run_active_workflow', 'uk_batch_run_active')
   AND n.nspname = current_schema()
   AND NOT i.indisvalid
\gexec

-- ============================================================================
-- 步骤 0-b：前置检查——有冲突数据就直接中止，不建索引
--
-- 建唯一索引时若已存在重复，会直接失败并留下一堆中间状态。
-- 先查清楚，让人工处置，比建到一半失败好。
-- ============================================================================
DO $$
DECLARE
    bad_workflow int;
    bad_batch    int;
BEGIN
    -- ① workflow 级：同一租户同一 workflow 有多个活跃 run
    SELECT count(*) INTO bad_workflow
      FROM (
        SELECT 1
          FROM run
         WHERE workflow_id IS NOT NULL
           AND deleted_at IS NULL
           AND status::text IN ('queued', 'running', 'paused', 'quarantined')
         GROUP BY tenant_id, workflow_id
        HAVING count(*) > 1
      ) t;

    IF bad_workflow > 0 THEN
        RAISE EXCEPTION
            '发现 % 组 workflow 级冲突：同一 workflow 有多条活跃 run。'
            '请先查详情（见文件末尾注释）并人工处置，再重跑本迁移。',
            bad_workflow
            USING HINT = '通常是把多余的 run 置为 aborted，保留真正在跑的那一条';
    END IF;

    -- ② batch 级：同一租户同一 batch 有多个活跃 attempt
    SELECT count(*) INTO bad_batch
      FROM (
        SELECT 1
          FROM experiment_batch_run
         WHERE status IN ('pending', 'queued', 'running', 'paused', 'quarantined')
         GROUP BY tenant_id, batch_id
        HAVING count(*) > 1
      ) t;

    IF bad_batch > 0 THEN
        RAISE EXCEPTION
            '发现 % 组 batch 级冲突：同一批次有多条活跃 attempt。'
            '请先查详情（见文件末尾注释）并人工处置，再重跑本迁移。',
            bad_batch
            USING HINT = '通常是把多余的 attempt 置为 failed/aborted，保留真正在跑的那一条';
    END IF;

    RAISE NOTICE '前置检查通过：无冲突数据，开始建索引';
END $$;

-- ============================================================================
-- 步骤 1：建索引（CONCURRENTLY，不阻塞读写）
--
-- 为什么用「部分唯一索引」而不是生成列：
--   状态集合将来若变化，只需改索引定义，不必 ALTER TABLE 加/改生成列
--   （后者会重写整表）。
--
-- 为什么 workflow 索引要加 `workflow_id IS NOT NULL`：
--   WorkflowRunGate 对 workflowId <= 0 直接 allow（WorkflowRunGate:98-100），
--   而 insertExperimentFlowRun 写的就是 workflow_id = NULL（RunMapper:1012）。
--   不加这个条件，那条路径会直接撞索引 → 功能挂掉。
-- ============================================================================

-- ⚠ 索引谓词里**不能**写 status::text：
--   enum → text 的 cast 是 STABLE 不是 IMMUTABLE，会报
--   "functions in index predicate must be marked IMMUTABLE"（实测于 PG 17.11）。
--   直接用字符串字面量与枚举比较即可（解析期完成转换，比较本身 IMMUTABLE）。
CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS uk_run_active_workflow
    ON run (tenant_id, workflow_id)
 WHERE workflow_id IS NOT NULL
   AND deleted_at IS NULL
   AND status IN ('queued', 'running', 'paused', 'quarantined');

CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS uk_batch_run_active
    ON experiment_batch_run (tenant_id, batch_id)
 WHERE status IN ('pending', 'queued', 'running', 'paused', 'quarantined');

-- ============================================================================
-- 步骤 2：验证两个索引都存在且有效
-- ============================================================================
DO $$
DECLARE
    n_ok      int;
    n_invalid int;
BEGIN
    SELECT count(*) INTO n_ok
      FROM pg_indexes
     WHERE schemaname = current_schema()
       AND indexname IN ('uk_run_active_workflow', 'uk_batch_run_active');

    IF n_ok < 2 THEN
        RAISE EXCEPTION '索引未全部创建：只找到 %/2 个', n_ok;
    END IF;

    SELECT count(*) INTO n_invalid
      FROM pg_index i
      JOIN pg_class  c ON c.oid = i.indexrelid
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE c.relname IN ('uk_run_active_workflow', 'uk_batch_run_active')
       AND n.nspname = current_schema()
       AND NOT i.indisvalid;

    IF n_invalid > 0 THEN
        RAISE EXCEPTION
            '存在 % 个无效索引（CONCURRENTLY 中途失败）。'
            '请重跑本文件（步骤 0-a 会自动清理），或手工 DROP INDEX CONCURRENTLY 后重试。',
            n_invalid;
    END IF;

    RAISE NOTICE 'K-01a 完成：uk_run_active_workflow / uk_batch_run_active 均已创建且有效';
END $$;

-- ============================================================================
-- 附：冲突排查 SQL（冲突时手工执行，本文件不自动跑）
--
-- ① workflow 级冲突详情
-- SELECT tenant_id, workflow_id, count(*) AS active_count,
--        array_agg(id ORDER BY id)          AS run_ids,
--        array_agg(run_no ORDER BY id)      AS run_nos,
--        array_agg(status::text ORDER BY id) AS statuses
--   FROM run
--  WHERE workflow_id IS NOT NULL AND deleted_at IS NULL
--    AND status::text IN ('queued','running','paused','quarantined')
--  GROUP BY tenant_id, workflow_id
-- HAVING count(*) > 1
--  ORDER BY active_count DESC;
--
-- ② batch 级冲突详情
-- SELECT tenant_id, batch_id, count(*) AS active_count,
--        array_agg(id ORDER BY id)           AS attempt_ids,
--        array_agg(experiment_run_id ORDER BY id) AS run_ids,
--        array_agg(status ORDER BY id)       AS statuses
--   FROM experiment_batch_run
--  WHERE status IN ('pending','queued','running','paused','quarantined')
--  GROUP BY tenant_id, batch_id
-- HAVING count(*) > 1
--  ORDER BY active_count DESC;
--
-- ③ 处置示例（确认哪条是真在跑之后，把其余置为终态）
-- UPDATE run SET status = 'aborted', error_code = 'EC-DUP-ACTIVE-CLEANUP',
--        updated_at = now()
--  WHERE id IN (<保留之外的 run id 列表>)
--    AND status::text IN ('queued','running','paused','quarantined');
-- ============================================================================
