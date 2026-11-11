-- ============================================================================
-- 测试基线重置 · 第一步：把要保留的东西备份进 legacy_snapshot
--
-- 为什么备份：
--   「删除所有岛屿和流程」是不可逆的。岛与流程是**业务结构**（设计得出来、
--   看得出意图、可以照着重建），而运行历史（run_node 12 万行 / run_command
--   11.7 万行）是**执行流水**（量巨大、事后靠它排查的价值低于它的存储成本）。
--   ⇒ 备份结构，不备份流水（用户已确认）。
--
-- 备份表结构：一张通用表（entity = 原表名，row_id = 原主键，payload = 整行 JSON）
--   —— 不用「每个表建一张影子表」，那样加表就得改脚本；
--   整行 JSON 保留所有列（含以后加的列），恢复时按 entity 过滤即可。
--
-- 用法：psql -f scripts/baseline-backup.sql
-- ============================================================================
\set ON_ERROR_STOP on

BEGIN;

CREATE TABLE IF NOT EXISTS legacy_snapshot (
    id           bigserial PRIMARY KEY,
    entity       varchar(64) NOT NULL,          -- 原表名
    row_id       bigint,                        -- 原行主键（可空：复合主键表）
    payload      jsonb        NOT NULL,         -- 整行快照
    backed_up_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_legacy_snapshot_entity
    ON legacy_snapshot(entity, row_id);

-- ---------------------------------------------------------------------------
-- ① 流程族（岛上的流程定义：节点 / 连线 / 版本历史）
-- ---------------------------------------------------------------------------
INSERT INTO legacy_snapshot(entity, row_id, payload)
SELECT 'workflow', id, to_jsonb(t) FROM workflow t;

INSERT INTO legacy_snapshot(entity, row_id, payload)
SELECT 'workflow_node', id, to_jsonb(t) FROM workflow_node t;

INSERT INTO legacy_snapshot(entity, row_id, payload)
SELECT 'workflow_edge', id, to_jsonb(t) FROM workflow_edge t;

INSERT INTO legacy_snapshot(entity, row_id, payload)
SELECT 'workflow_graph_history', id, to_jsonb(t) FROM workflow_graph_history t;

-- ---------------------------------------------------------------------------
-- ② 实验族（实验定义 + 实验与流程的编排关系）
-- ---------------------------------------------------------------------------
INSERT INTO legacy_snapshot(entity, row_id, payload)
SELECT 'experiment', id, to_jsonb(t) FROM experiment t;

INSERT INTO legacy_snapshot(entity, row_id, payload)
SELECT 'experiment_process', id, to_jsonb(t) FROM experiment_process t;

-- 复合主键表（没有 id 列）：row_id 留空，整行信息都在 payload 里。
INSERT INTO legacy_snapshot(entity, row_id, payload)
SELECT 'experiment_process_edge', NULL, to_jsonb(t) FROM experiment_process_edge t;

INSERT INTO legacy_snapshot(entity, row_id, payload)
SELECT 'experiment_graph', id, to_jsonb(t) FROM experiment_graph t;

INSERT INTO legacy_snapshot(entity, row_id, payload)
SELECT 'experiment_variable', id, to_jsonb(t) FROM experiment_variable t;

INSERT INTO legacy_snapshot(entity, row_id, payload)
SELECT 'experiment_run', id, to_jsonb(t) FROM experiment_run t;

INSERT INTO legacy_snapshot(entity, row_id, payload)
SELECT 'experiment_run_step', id, to_jsonb(t) FROM experiment_run_step t;

INSERT INTO legacy_snapshot(entity, row_id, payload)
SELECT 'experiment_batch', id, to_jsonb(t) FROM experiment_batch t;

INSERT INTO legacy_snapshot(entity, row_id, payload)
SELECT 'experiment_batch_run', id, to_jsonb(t) FROM experiment_batch_run t;

INSERT INTO legacy_snapshot(entity, row_id, payload)
SELECT 'experiment_handoff', id, to_jsonb(t) FROM experiment_handoff t;

INSERT INTO legacy_snapshot(entity, row_id, payload)
SELECT 'experiment_sample', NULL, to_jsonb(t) FROM experiment_sample t;

INSERT INTO legacy_snapshot(entity, row_id, payload)
SELECT 'experiment_workcell', id, to_jsonb(t) FROM experiment_workcell t;

-- ---------------------------------------------------------------------------
-- ③ 岛与资源树
--    只备份**岛**及其后代（岛下的设备节点、机器人点位等）。
--    设备本体（device_instance / device / capability）不备份也不删 ——
--    那是「设备清单」，新基线还要用（只是把不要的停用）。
-- ---------------------------------------------------------------------------
INSERT INTO legacy_snapshot(entity, row_id, payload)
SELECT 'resource_node', t.id, to_jsonb(t)
  FROM resource_node t
 WHERE t.kind = 'island'
    OR t.id IN (SELECT c.id FROM resource_node c
                 JOIN resource_node p ON p.id = c.parent_id
                WHERE p.kind = 'island' AND c.deleted_at IS NULL)
    OR t.id IN (SELECT g.id FROM resource_node g
                 JOIN resource_node p ON p.id = g.parent_id
                 JOIN resource_node c ON c.id = p.parent_id
                WHERE c.kind = 'island' AND g.deleted_at IS NULL);

INSERT INTO legacy_snapshot(entity, row_id, payload)
SELECT 'island_flow', id, to_jsonb(t) FROM island_flow t;

INSERT INTO legacy_snapshot(entity, row_id, payload)
SELECT 'robot_point', id, to_jsonb(t) FROM robot_point t;

-- ---------------------------------------------------------------------------
-- ④ 恢复说明也存进去（谁在什么时候做的重置）
-- ---------------------------------------------------------------------------
INSERT INTO legacy_snapshot(entity, row_id, payload)
VALUES ('__meta__', NULL,
        jsonb_build_object('note', '测试基线重置前的结构备份',
                           'scope', '流程族 + 实验族 + 岛与资源树',
                           'not_backed_up', 'run / run_node / run_command / run_event（执行流水，量大且事后价值低）',
                           'at', now()));

COMMIT;

-- ---------------------------------------------------------------------------
-- 备份结果自检：每个 entity 备份了多少行
-- ---------------------------------------------------------------------------
SELECT entity, count(*) AS backed_up_rows
  FROM legacy_snapshot
 GROUP BY entity
 ORDER BY 2 DESC, 1;
