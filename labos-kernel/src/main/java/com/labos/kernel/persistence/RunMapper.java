package com.labos.kernel.persistence;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * C 批：run 表的状态收尾 SQL，语句见 {@code mapper/RunMapper.xml}。
 *
 * <p>这几条是执行链上最敏感的写入（决定一次运行是 completed / failed /
 * paused），放进 XML 后能直接看清「改了哪些列、带了什么 WHERE 条件」，
 * 不用在 Java 里拼三段动态 SQL 字符串。
 */
public interface RunMapper {

    /** 执行期异常收尾：只改 status='running' 的行，不覆盖 paused。 */
    int markFailed(long tenantId, long runId, String errorJson);

    /** 正常/异常终止：completed 或 failed。 */
    int settleTerminal(long tenantId, long runId, String status);

    /** 转人工：paused + waiting_human（不写 actual_end）。 */
    int settleManual(long tenantId, long runId);

    /** P-09：执行体收尾，不再派发新命令。 */
    int markSettledPhase(long tenantId, long runId);

    /** 读当前状态（暂停边界判定等）。 */
    String selectStatus(long tenantId, long runId);

    // ---- 调度器读侧（ExperimentScheduler：续队/快照/兜底写状态）----

    /** experiment_run 当前状态（续队前以库为准）。 */
    String selectExperimentRunStatus(long tenantId, long runId);

    /**
     * 资源展示用：id -&gt; code/name（一次批量）。
     *
     * <p>2026-11-10 起调度粒度改成资源级，岛锁变成资源占用 —— 展示的对象从
     * 「岛」变成「设备/机械臂」，但它们都是 {@code resource_node} 的行，
     * 所以这条查询对两者通用（岛是 kind='island' 的资源节点）。
     */
    java.util.List<java.util.Map<String, Object>> selectResourceCodes(
            long tenantId, java.util.List<Long> ids);

    /** 锁的 run -> 实验 id / 实验编号（一次 JOIN 批量）。 */
    java.util.List<java.util.Map<String, Object>> selectRunExperiments(
            long tenantId, java.util.List<Long> runIds);

    /** 队列项实验编号（一次批量）。 */
    java.util.List<java.util.Map<String, Object>> selectExperimentNos(
            long tenantId, java.util.List<Long> expIds);

    /**
     * 兜底写状态（执行器 settle 前抛异常时用）。
     *
     * <p>{@code finished=true} 时补 finished_at；两者都带
     * {@code status NOT IN ('aborted','cancelled')} 守卫 —— 人工已终止的 run
     * 不能被迟到的兜底写回成 failed。
     */
    int markExperimentRunStatus(long tenantId, long runId, String status,
                                String error, @Param("finished") boolean finished);

    /** 切换运行中 run 的值守模式；已结束的 run 不受影响（0 行）。 */
    int updateAttendance(long tenantId, long runId, String attendance);

    /** 最近的 run 列表（含 loop_index=0 的节点数）。 */
    List<Map<String, Object>> selectRecentRuns(long tenantId, int limit);

    /** run 详情的事件列表（按 payload.seq，最多 100 条）。 */
    List<Map<String, Object>> selectRunEvents(long runId);

    /** run 详情头；不存在返回 null。 */
    Map<String, Object> selectRunDetail(long tenantId, long runId);

    /** run 详情的节点列表（含父节点/层位/循环序号排序）。 */
    List<Map<String, Object>> selectRunDetailNodes(long runId);

    /** 正在跑的 run 汇总（process-status 的库侧兜底）。 */
    List<Map<String, Object>> selectRunningProcessStatus(long tenantId);

    /** run 对应的 workflow_id 与当前状态（取不到时 null）。 */
    Map<String, Object> selectRunWorkflow(long tenantId, long runId);

    /** 一次执行里各节点的实际状态（只取父节点、loop 0）。 */
    List<Map<String, Object>> selectRunNodeStates(long tenantId, long runId);

    /** 流程计划节点（task 的 node_key -> name）。 */
    List<Map<String, Object>> selectWorkflowTaskNames(long tenantId, long workflowId);

    /** run 的实际开始时间（甘特图相对时间的基准）。 */
    java.sql.Timestamp selectRunActualStart(long tenantId, long runId);

    /** 甘特时间轴的 run_node 行（相对毫秒由 Java 侧换算）。 */
    List<Map<String, Object>> selectTimelineNodes(long runId);

    /** 命令 Outbox（state 为 null 时不过滤）。 */
    List<Map<String, Object>> selectCommandOutbox(long tenantId, String state, int limit);

    /** 搬运事务（status 为 null 时不过滤）。 */
    List<Map<String, Object>> selectTransferTransactions(long tenantId, String status, int limit);

    /** 板位证据（plateBarcode 为 null 时不过滤）。 */
    List<Map<String, Object>> selectPositionEvidence(long tenantId, String plateBarcode,
                                                     int limit);

    /** 隔离中的 run（含反查实验 id 的子查询）。 */
    List<Map<String, Object>> selectQuarantinedRuns(long tenantId, int limit);

    /** 人工介入记录。 */
    List<Map<String, Object>> selectInterventionRecords(long tenantId, int limit);

    /** 记一条人工介入记录，返回 id。 */
    Long insertIntervention(long tenantId, Long manualWindowId, Long runId,
                            Long experimentRunId, String operator, String action,
                            String plateBarcode, String sampleId, String fromLocation,
                            String toLocation, String result, String note);

    /** 记一条板位证据，返回 id。 */
    Long insertPositionEvidence(long tenantId, String plateBarcode, Long resourceId,
                                String type, Long runId, String operator, String note);


    /** 当前进行中的节点（含已下发步骤串）；无则返回 null。 */
    Map<String, Object> selectCurrentNode(long runId);

    // ---- 执行生命周期（WorkflowService 断点续跑路径）----

    /** 运行期变量合并进 run.input.vars（启动线程读 input 之前必须落库）。 */
    int mergeRunInputVars(long tenantId, long runId, String varsJson);

    /** 续跑前置读：workflow_id 与值守模式（attendance 为 NULL 按 'attended'）。 */
    Map<String, Object> selectRunResumeInfo(long tenantId, long runId);

    /** 续跑：本轮已完成的顶层节点 key（level=0 + completed，不再执行）。 */
    List<String> selectCompletedNodeKeys(long tenantId, long runId);

    /** 续跑：先删未完成业务节点的步骤行（level=1，否则重跑时 duplicate key）。 */
    int deleteIncompleteStepRows(long tenantId, long runId);

    /** 续跑：再删未完成的顶层业务节点（步骤行已先删，FK 级联不再被依赖）。 */
    int deleteIncompleteTopNodes(long tenantId, long runId);

    /** 续跑：run 置 running，清暂停三字段，记 resumed_at。 */
    int markResumed(long tenantId, long runId);

    // ---- 执行写入（WorkflowService 执行体）----

    /** 新建 run（异步执行的载体），返回自增 id。 */
    Long insertRun(long tenantId, String runNo, long workflowId, String attendance);

    /** 落一个顶层业务节点（level=0），返回自增 id。 */
    Long insertTopNode(long tenantId, long runId, String nodeKey, String name, int seq,
                       Long resourceId, String status, int attempt, String inputJson);

    /** 续跑时已有顶层节点的 key -> id（跳过重复插入用）。 */
    List<Map<String, Object>> selectTopNodeKeyIds(long tenantId, long runId);

    /** 节点执行中/结束的状态推进（actual_start 只补第一次，running 时不清 actual_end）。 */
    int updateNodeStateLive(long tenantId, long runId, String nodeKey, String status);

    /** 成功结束时把 pending/running 的顶层节点标成 bypassed（分支未选中）。 */
    int markBypassed(long tenantId, long runId);

    /** 批量收尾：节点的最终状态 / attempt / 资源 / error_code / 原因。 */
    int updateNodeResult(long tenantId, long runId, String nodeKey, String status,
                         int attempt, Long resourceId, String errorCode, String inputJson);

    /** 循环第 N 圈：从汇总行复制补一行（loop_index=N）。 */
    int insertNodeRound(long tenantId, long runId, String nodeKey, String status,
                        java.sql.Timestamp start, java.sql.Timestamp end, String inputJson,
                        String errorCode, int round);

    /** 本 run 步骤行（level=1）的最大数字序号（node_key=S&lt;n&gt;），解析失败按 0。 */
    int selectMaxStepSeq(long tenantId, long runId);

    /** 步骤行（level=1），返回自增 id；errorCode 超长由调用方截断。 */
    Long insertStepNode(long tenantId, long runId, String nodeKey, String name, int seq,
                        Long resourceId, String status, int attempt, long parentNodeId,
                        int stepSeq, String errorCode, String inputJson);

    /** 命令台账（实时写路径，带 device_id）。 */
    int insertRunCommand(long tenantId, long runId, long runNodeId, int commandSeq,
                         String method, String payloadJson, String status, int attempt,
                         String resultJson, String rawResponse, String errorCode,
                         long deviceId);

    /** 节点边界暂停：paused + 扣留类型/原因/边界节点（已有值不覆盖）。 */
    int pauseRunAtBoundary(long tenantId, long runId, String kind, String reason,
                           String pendingKey);

    /** 边界到达后补 pause_node_key（仅当 paused 且仍为空）。 */
    int fillPauseNodeKey(long tenantId, long runId, String pendingKey);

}
