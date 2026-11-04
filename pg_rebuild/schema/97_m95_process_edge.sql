-- ============================================================================
-- M9.5：实验编排统一 —— 流程之间偏序的迁移边界
--
-- 背景（为什么必须现在加）：
--   `experiment_process.seq` 只能表达**线性顺序**，而画布上已经能画分叉/并行。
--   S1 已把执行收口到 `experiment_process`（唯一事实来源），但"流程之间到底是什么
--   结构"仍然只能靠 seq 猜。要让执行器从"按 seq 逐个跑"改成"按依赖派发"，
--   必须先有一条**真被读的**依赖数据，而不是再加一个没人读的字段。
--
--   反面教材就在本表旁边：`experiment_process.run_after`（依赖的 seq）写了、
--   API 也返回了，但执行器从来没读过 —— "模型先行、执行没跟上"。
--   所以这张表的落地口径是：**加了就必须被读**（ExperimentExecutor 读它求偏序）。
--
-- 语义边界（S2-A）：
--   · 只表达**偏序**（谁必须在谁之前），不表达并发度、不表达资源。
--   · 执行器当前按拓扑序**物理串行**推进：一次只跑一个可执行节点，
--     不承诺墙钟并发。要真并发必须先接"三层资源 + 等待图"，否则会互相锁死。
--   · 边是"前驱 -> 后继"（from 完成后 to 才可派发）；汇聚 = 所有前驱都完成。
--   · 线性实验 = 一条链（n 个流程 = n-1 条边）。老库没有边的数据按 seq 线性处理。
--
-- 幂等：CREATE TABLE IF NOT EXISTS + 内联约束，可重复执行。
-- ============================================================================

CREATE TABLE IF NOT EXISTS experiment_process_edge (
    tenant_id     bigint      NOT NULL,
    experiment_id bigint      NOT NULL,
    from_id       bigint      NOT NULL REFERENCES experiment_process(id) ON DELETE CASCADE,
    to_id         bigint      NOT NULL REFERENCES experiment_process(id) ON DELETE CASCADE,
    created_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (from_id, to_id)
);

COMMENT ON TABLE experiment_process_edge IS
    '实验内流程之间的偏序（前驱 -> 后继）。from 完成后 to 才可派发；
     汇聚 = 所有前驱都完成。线性实验 = 一条链（n 个流程 n-1 条边）。
     S2-A 只承诺偏序，执行仍物理串行；真并发需另接三层资源 + 等待图。';
COMMENT ON COLUMN experiment_process_edge.from_id IS
    '前驱流程（experiment_process.id）。整体替换编排时随 process 行级联删除';
COMMENT ON COLUMN experiment_process_edge.to_id IS
    '后继流程（experiment_process.id）';

-- 按实验整体读取边（执行器求拓扑序）与整体替换（画布保存）都走这个索引。
CREATE INDEX IF NOT EXISTS ix_exp_proc_edge_exp
    ON experiment_process_edge (tenant_id, experiment_id);
