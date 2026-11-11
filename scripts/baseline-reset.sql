-- ============================================================================
-- 测试基线重置 · 第二步：清空旧的岛 / 流程 / 实验 / 运行流水
--
-- 前置：已执行 scripts/baseline-backup.sql（结构已进 legacy_snapshot）。
--       运行流水（run / run_node / run_command / run_event）按约定**不备份**，
--       直接删 —— 量巨大（12 万 + 11.7 万行）且事后排查价值低于存储成本。
--
-- 保留什么（这是"设备清单"，新基线还要用）：
--   device / device_instance / device_method / capability / resource_capability
--   driver / driver_capability / device_state / device_port / device_access
--   tenant / 用户 / 权限 / recovery_policy / error_code / duration_model
--   capability_recipe / consumable / reagent / notification / dict_item / file_blob
--
-- 用法：psql -f scripts/baseline-reset.sql
-- ============================================================================
\set ON_ERROR_STOP on

BEGIN;

-- ---------------------------------------------------------------------------
-- ① 实验族（experiment 的从表；CASCADE 由外键兜底，这里显式删是为了顺序清晰）
--     注意：experiment.run_id → run 是 NO ACTION ⇒ 实验必须先于 run 删。
-- ---------------------------------------------------------------------------
DELETE FROM experiment_run_step;
DELETE FROM experiment_process_edge;
DELETE FROM experiment_batch_run;
DELETE FROM experiment_batch;
DELETE FROM experiment_graph;
DELETE FROM experiment_handoff;
DELETE FROM experiment_process;
DELETE FROM experiment_sample;
DELETE FROM experiment_variable;
DELETE FROM experiment_workcell;
DELETE FROM experiment_run;
DELETE FROM experiment;

-- ---------------------------------------------------------------------------
-- ② 运行族（不备份，直接删）
-- ---------------------------------------------------------------------------
DELETE FROM error_occurrence;      -- 引用 run_node / run，先清掉免得挡路
DELETE FROM run_node;               -- run_command / run_variable / run_artifact CASCADE
DELETE FROM run_command;
DELETE FROM schedule_item;
DELETE FROM run_result;
DELETE FROM run_event;              -- 分区表 ⇒ 级联清掉所有月份分区
DELETE FROM run;

-- ---------------------------------------------------------------------------
-- ③ 流程族
--     workflow_edge / workflow_node 对 workflow 是 NO ACTION ⇒ 从下往上删。
-- ---------------------------------------------------------------------------
DELETE FROM workflow_edge;
DELETE FROM workflow_node;
DELETE FROM workflow_graph_history;
DELETE FROM workflow;

-- ---------------------------------------------------------------------------
-- ④ 先清"引用资源节点"的附属表
--     resource_booking / plate / device_access 都引用 resource_node（岛节点），
--     是 NO ACTION 外键 ⇒ 必须在删岛**之前**清掉，否则删岛会撞外键。
-- ---------------------------------------------------------------------------
DELETE FROM resource_booking;
DELETE FROM plate_well;
DELETE FROM plate;
DELETE FROM position_state;
DELETE FROM device_access;
DELETE FROM schedule_item;

-- ---------------------------------------------------------------------------
-- ⑤ 岛与资源树
--     只删**岛节点**本身（kind='island'）及其附属表。
--     岛下的设备节点**保留**：resource_node.parent_id 的外键是 SET NULL ⇒
--     删岛后设备自动回到"设备池"（parent_id = NULL），正好能拖进新岛。
-- ---------------------------------------------------------------------------
DELETE FROM island_flow;
DELETE FROM robot_point;
DELETE FROM resource_node WHERE kind = 'island';

-- ---------------------------------------------------------------------------
-- ⑥ 执行期附属（板位 / 扣留 / 告警 / 台账 / 审计）
--     runtime_hold 必须清空：调度器有全局闸门，任何一条未释放的扣留会让
--     **所有岛**的新实验都派发不出去。
-- ---------------------------------------------------------------------------
DELETE FROM runtime_hold;
DELETE FROM pause_event;
DELETE FROM pause_request;
DELETE FROM manual_window;
DELETE FROM command_ledger;
DELETE FROM command_outbox;
DELETE FROM intervention_record;
DELETE FROM position_evidence;
DELETE FROM transfer_transaction;
DELETE FROM plate_location_audit;
DELETE FROM alert_state;
DELETE FROM audit_log;
DELETE FROM driver_health;          -- 心跳探测流水，46 万行，纯日志
DELETE FROM device_log;             -- 分区表

-- ---------------------------------------------------------------------------
-- ⑥ 项目与样本（实验的父级；实验已清空 ⇒ 一并清掉）
-- ---------------------------------------------------------------------------
DELETE FROM sample;
DELETE FROM project;

COMMIT;

-- ---------------------------------------------------------------------------
-- 重置后自检：这些应该是 0
-- ---------------------------------------------------------------------------
SELECT 'workflow'            AS t, count(*) FROM workflow
UNION ALL SELECT 'workflow_node',      count(*) FROM workflow_node
UNION ALL SELECT 'workflow_edge',      count(*) FROM workflow_edge
UNION ALL SELECT 'experiment',         count(*) FROM experiment
UNION ALL SELECT 'run',                count(*) FROM run
UNION ALL SELECT 'run_node',           count(*) FROM run_node
UNION ALL SELECT 'run_command',        count(*) FROM run_command
UNION ALL SELECT 'run_event',          count(*) FROM run_event
UNION ALL SELECT 'island',             count(*) FROM resource_node WHERE kind='island'
UNION ALL SELECT 'runtime_hold',       count(*) FROM runtime_hold
UNION ALL SELECT 'device(保留)',        count(*) FROM device_instance WHERE deleted_at IS NULL
UNION ALL SELECT 'device_node(保留)',   count(*) FROM resource_node WHERE kind='device' AND deleted_at IS NULL
UNION ALL SELECT 'driver(保留)',        count(*) FROM driver WHERE deleted_at IS NULL;
