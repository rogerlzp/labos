-- ============================================================================
-- P-01：workflow.island_id —— Workflow 的权威执行边界
--
-- 依据 ADR-ISLAND-001/003/005：
--   · 一个物理 Workflow 必须有且仅有一个权威 Workcell（岛）；
--   · 执行上下文取 Workflow 的权威归属，不取 experiment.island_id；
--   · workflow.island_id 是**权威执行边界**，experiment.island_id 只是
--     内部区分键 / 岛名展示，两者不得混用读写路径；
--   · 岛本身不是独立实体，就是 resource_node 里 kind='island' 的节点
--     （id 如 100027=CMS、100033=Pre-NGS），设备节点的 parent_id 指向它。
--
-- 2026-11-09 现场决策：历史 994 条 workflow.attr.island_id 是另一套旧编号
-- （27/31/33…，对不上资源树），已按用户指示**清除该脏键**（流程本身保留），
-- 不做 +100000 之类的推断回填。因此：
--   · 新列允许 NULL —— 存量流程未分配权威岛，不装作已知；
--   · 不回填、不推断；后续由 P-02 的 IslandBoundaryValidator 判 UNKNOWN；
--   · FK 指向 resource_node(id)，写入方必须给真实存在的岛节点。
--
-- 幂等。
-- ============================================================================

ALTER TABLE workflow
    ADD COLUMN IF NOT EXISTS island_id bigint REFERENCES resource_node(id);

CREATE INDEX IF NOT EXISTS ix_workflow_island
    ON workflow (tenant_id, island_id);

COMMENT ON COLUMN workflow.island_id IS
    'P-01 权威执行边界：指向 resource_node(kind=''island'') 的 id。与 experiment.island_id（仅分区/展示）不得混用。'
    'NULL=未分配（P-02 校验器按 UNKNOWN 处理）；存量流程一律 NULL，不推断不回填';
