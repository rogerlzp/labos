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
    // R8 步骤 4：已迁到 ExperimentProjectorMapper.applyPausedRunManual。
    // int settleManual(long tenantId, long runId);

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

    /**
     * 读这条 run 的**运行期变量表**（{@code run.input->'vars}），一行一个变量。
     *
     * <p>用 {@code jsonb_each_text} 拆开返回，而不是把整个 jsonb 当成一个字段读回来
     * —— 后者拿到的类型随驱动而变（PGobject / String），还得在 Java 里二次解析。</p>
     */
    List<Map<String, Object>> selectRunVars(long tenantId, long runId);

    /**
     * 改**一个**运行期变量（{@code run.input->vars->name}）。
     *
     * <p>为什么是"改一个"而不是整表覆盖：整表覆盖会把别人刚写的值冲掉
     * （运行中 `__setVar` / `__wait` 都在动这张表）。</p>
     */
    int setRunVar(@Param("tenantId") long tenantId, @Param("runId") long runId,
                  @Param("name") String name, @Param("value") String value);

    /** 这条 run 属于哪条流程（用来查它声明了哪些变量）；没有流程级 run 返回 null。 */
    Long selectRunWorkflowId(long tenantId, long runId);

    /** 这条 run 现在什么状态（改变量前要判它还能不能改）。 */
    String selectRunStatus(long tenantId, long runId);

    /** 续跑前置读：workflow_id 与值守模式（attendance 为 NULL 按 'attended'）。 */
    Map<String, Object> selectRunResumeInfo(long tenantId, long runId);

    /** 续跑：本轮已完成的顶层节点 key（level=0 + completed，不再执行）。 */
    /**
     * 把一个节点标成「**人工确认跳过**」：status='skipped' + input.skip_confirmed=true。
     *
     * <p>语义：这一步用户明确决定「不做，继续往下」。续跑时按"已完成"处理
     * （不重做、不阻断下游），但状态留痕 ≠ completed，界面上能看出它是被跳过的。</p>
     */
    int markNodeSkippedConfirmed(@Param("tenantId") long tenantId,
                                 @Param("runId") long runId,
                                 @Param("nodeKey") String nodeKey);

    List<String> selectCompletedNodeKeys(long tenantId, long runId);

    /** 续跑：先删未完成业务节点的步骤行（level=1，否则重跑时 duplicate key）。 */
    int deleteIncompleteStepRows(long tenantId, long runId);

    /** 续跑：再删未完成的顶层业务节点（步骤行已先删，FK 级联不再被依赖）。 */
    int deleteIncompleteTopNodes(long tenantId, long runId);

    /** 续跑守卫（§3.6 / §8.2 第 7 项）：将被删的未完成节点里有设备派发证据的行数。 */
    int countResumeGuardHits(long tenantId, long runId);

    /** 续跑：run 置 running，清暂停三字段，记 resumed_at。 */
    int markResumed(long tenantId, long runId);

    // ---- 执行写入（WorkflowService 执行体）----

    /**
     * 新建 run（异步执行的载体），返回自增 id。
     *
     * @param varsJson 运行期变量表（{@code {"name":"value"}}）；为空则写空表。
     *                 <p>为什么要在这里就写进去：这条 run 的变量表是
     *                 {@code run.input->'vars'}，而 {@code __setVar} / {@code __expr}
     *                 / 条件判断 / 设备参数里的 {@code ${x}} 全都从它取值 ——
     *                 变量表建好时就有值，才谈得上"声明了初始值就能用"。</p>
     */
    Long insertRun(long tenantId, String runNo, long workflowId, String attendance,
                   String varsJson);

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

    /**
     * 命令台账（实时写路径，带 device_id）。
     *
     * @param sentAt **命令真正下发到设备的时刻**（不是落库时刻）——
     *                命令台账是在命令**执行完**之后才写的，若 sent_at 用落库时刻
     *                （now()），它会与 finished_at 同毫秒 ⇒ 事后算不出
     *                「发出 → 设备响应」这段时间差（2026-11-11 实测发现：
     *                库里所有命令的 sent_at == finished_at）。
     */
    int insertRunCommand(long tenantId, long runId, long runNodeId, int commandSeq,
                         String method, String payloadJson, String status, int attempt,
                         String resultJson, String rawResponse, String errorCode,
                         long deviceId, java.sql.Timestamp sentAt);

    /**
     * 命令级台账：这条 run 下发过哪些命令、参数、发出/完成时刻、耗时、错误码。
     *
     * <p>给界面用：只看节点表看不出"这一步到底给设备发了什么、设备什么时候回的"。
     */
    java.util.List<java.util.Map<String, Object>> selectRunCommands(long tenantId, long runId);

    /**
     * **内置逻辑节点**（`__sleep` / `__wait` / `__setVar` / `__expr`）的命令台账行。
     *
     * <p>为什么需要单独一条：内置节点**不碰设备**，从来不经过
     * {@code StepSink} ⇒ 台账里一条都没有 ⇒ 现场看到「延时节点显示正在运行中，
     * 但命令台账里什么都没有」，完全看不出这个节点在做什么。
     *
     * <p>这些行：{@code device_id} 为空（没碰设备）、{@code method} 是内置方法名、
     * {@code payload} 里带节点参数、{@code result} 里带一句**人话**描述
     * （"等待 gate = open" / "延时 60 秒"）。
     *
     * @param nodeKey 业务节点 key（顶层节点行）
     * @param payloadJson 节点参数（{@code var/value/timeout/sec/expr}）
     * @param note 一句人话描述
     * @param status {@code running} / {@code completed} / {@code failed}
     */
    int insertBuiltinCommand(long tenantId, long runId, String nodeKey,
                              String method, String payloadJson, String note,
                              String status, java.sql.Timestamp sentAt);

    /**
     * 更新内置节点台账行的**等待进度**（"已等 12 秒"）。
     *
     * <p>为什么要有：长等待（`__wait` 可能等几十分钟）期间界面要能看出
     * 「它在等、等了多久」，否则就是一块死板。</p>
     */
    int updateBuiltinCommandNote(long tenantId, long runId, String nodeKey, String note);

    /**
     * 内置逻辑节点的一句人话说明（"等待 gate = open（已等 12 秒）"）。
     *
     * <p>给界面用：光看节点名（`__wait`）根本不知道它在等什么；
     * 这句说明就是"这个节点在做什么"的人话版本。</p>
     */
    List<Map<String, Object>> selectBuiltinNotes(long tenantId, long runId);

    /**
     * 按 run_node.id 更新该行状态（只用于 level=0 的业务节点汇总行）。
     *
     * <p>用途：让**顺序执行路径**（{@code sm.executeAll}，没有
     * {@code nodeStateRecorder} 回调）也能实时显示"这个节点正在跑"。
     * 否则运行中的流程图全是 pending，直到整单结束才一次性回填
     * （2026-11-11 实测：顺序路径下 level=0 三行一直是 pending，
     * 16 秒后整单结束才一次性变 completed/manual）。
     */
    int updateRunNodeStatus(@Param("tenantId") long tenantId,
                            @Param("nodeId") long nodeId,
                            @Param("status") String status);

    /**
     * 更新 running 节点的执行阶段；阶段为空表示收尾完成。
     *
     * <p>WAITING_RESOURCE 只表示等待锁，尚未下发；DISPATCHING 从取得锁后、
     * 调用驱动前开始；FINALIZING 表示已收到最终回包。等待与设备执行耗时在
     * 阶段跃迁时累计，终态清阶段但保留耗时。</p>
     */
    int updateNodeExecutionPhase(@Param("tenantId") long tenantId,
                                 @Param("nodeId") long nodeId,
                                 @Param("phase") String phase,
                                 @Param("blockedByRunId") Long blockedByRunId,
                                 @Param("blockedReason") String blockedReason);

    /**
     * 节点最新一次执行的命令结果（result + 设备原始回包，按命令 id 倒序取一条）。
     *
     * <p>命令挂在步骤节点（level=1）上，业务节点（level=0）是它的 parent ——
     * 两处 node_key 都要匹配，只按一边查永远查不到。
     */
    Map<String, Object> selectLatestNodeOutput(long tenantId, long runId, String nodeKey);

    /** 节点边界暂停：paused + 扣留类型/原因/边界节点（已有值不覆盖）。 */
    // R8 步骤 4：已迁到 ExperimentProjectorMapper.applyPausedRunAtBoundary。
    // int pauseRunAtBoundary(long tenantId, long runId, String kind, String reason,
    //                        String pendingKey);

    /** 边界到达后补 pause_node_key（仅当 paused 且仍为空）。 */
    int fillPauseNodeKey(long tenantId, long runId, String pendingKey);

    // ---- StartupRecovery（启动崩溃收尾） ----

    /**
     * 被中断的 run 扫描（running 立即收；queued/pending 超 1 小时才收；
     * 命令账本 UNKNOWN/DISPATCH_STARTED 的留给隔离）。
     *
     * <p>ids 非 null 时限定 run id（自测用；调用方已保证非空）。
     * 返回 id/runNo/total/done/bad。
     */
    List<Map<String, Object>> selectInterruptedRuns(@Param("tenantId") long tenantId,
                                                      @Param("ids") java.util.Collection<Long> ids);

    /** 释放孤儿设备锁（current_run_id 指向的 run 已不存在）。 */
    int releaseOrphanDeviceLocks(long tenantId);

    /** 收尾一条 run：failed + 中断说明 jsonb；仅当还在 running/queued/pending。 */
    int settleInterruptedRun(long tenantId, long runId, String inputJson);

    /** 释放该 run 占着的设备锁（口径与 DeviceLock.release 一致）。 */
    int releaseRunDeviceLocks(long tenantId, long runId);

    /** 命令账本 UNKNOWN/DISPATCH_STARTED 的 run（id/runNo，按 id 升序）。 */
    List<Map<String, Object>> selectUncertainRuns(@Param("tenantId") long tenantId,
                                                  @Param("ids") java.util.Collection<Long> ids);

    /** 隔离一条 run：quarantined + 隔离说明 jsonb（幂等抬错误码）。 */
    int quarantineRun(long tenantId, long runId, String inputJson);

    /** run -> 实验 id（经 experiment_run_step 反查）；无归属返回 null。 */
    Long selectExperimentIdOfRun(long tenantId, long runId);

    /**
     * 实验运行（experiment_run）-> 它派出的流程运行（run）id。
     * 实验级恢复要知道"该续跑哪个 workflow run" ⇒ 用它反查。
     */
    Long selectWorkflowRunIdOfExperimentRun(long tenantId, long experimentRunId);

    /** 人工确认隔离：failed + 确认说明 jsonb；仅当还是 quarantined。 */
    int confirmQuarantine(long tenantId, long runId, String inputJson);

    /** 隔离确认（§3.6 / §8.2 第 14 项）：quarantined → paused(manual_window)，可续跑。 */
    // R8 步骤 4：已迁到 ExperimentProjectorMapper.applyPausedRunMaterialSafety。
    // int materialSafetyConfirm(long tenantId, long runId, String note);

    /** 近 7 天被中断且可恢复的 run（id/runNo/total/done/bad，按 id 倒序 50 条）。 */
    List<Map<String, Object>> selectRecoverableRuns(long tenantId);

    /** 忽略一条可恢复记录（recoverable=false，仅 failed 且可恢复的）。 */
    int dismissRecoverable(long tenantId, long runId, String inputJson);

    /** 遗留 running 的 experiment_run（id/experimentId/experimentNo/batchNo）。 */
    List<Map<String, Object>> selectOrphanExperimentRuns(
            @Param("tenantId") long tenantId,
            @Param("ids") java.util.Collection<Long> onlyExperimentIds);

    /** 收尾一条遗留 running 的 experiment_run：failed + 固定 error 文案。 */
    int settleOrphanExperimentRun(long tenantId, long runId, String error);

    // ---- ExperimentFlowRunner（逐步执行的实验编排，run/run_node/run_command 落库）----

    /** 点位坐标（x/y/z 为 BigDecimal，可能为 null）；不存在返回 null。 */
    Map<String, Object> selectRobotPoint(@Param("tenantId") long tenantId,
                                         @Param("code") String code);

    /** 搬运预检：资源点位上有没有板（hasPlate 为 Boolean）；资源不存在返回 null。 */
    Map<String, Object> selectPlatePresence(@Param("tenantId") long tenantId,
                                            @Param("code") String code);

    /** resource_node.id -> device_instance.id（未删除实例）；没有返回 null。 */
    Long selectInstanceIdOfResource(@Param("tenantId") long tenantId,
                                    @Param("resourceId") long resourceId);

    /** 逐步执行的实验 run（run_type='experiment'，input 记名称/步数/变量），返回自增 id。 */
    Long insertExperimentFlowRun(@Param("tenantId") long tenantId,
                                 @Param("runNo") String runNo,
                                 @Param("inputJson") String inputJson);

    /** 落一个步骤节点（node_type='task'、level=0、status='running'），返回自增 id。 */
    Long insertFlowNode(@Param("tenantId") long tenantId,
                        @Param("runId") long runId,
                        @Param("nodeKey") String nodeKey,
                        @Param("name") String name,
                        @Param("seq") int seq,
                        @Param("resourceId") long resourceId);

    /** 步骤收尾：status + actual_end + error_code（可空）+ input 合并耗时/错误码补丁。 */
    int updateFlowNodeFinished(@Param("tenantId") long tenantId,
                               @Param("nodeId") long nodeId,
                               @Param("status") String status,
                               @Param("errorCode") String errorCode,
                               @Param("inputPatchJson") String inputPatchJson);

    /** 命令台账（逐步执行路径）：payload/result 为 jsonb 文本，rawResponse 可空。 */
    int insertFlowRunCommand(@Param("tenantId") long tenantId,
                             @Param("runId") long runId,
                             @Param("nodeId") long nodeId,
                             @Param("method") String method,
                             @Param("payloadJson") String payloadJson,
                             @Param("status") String status,
                             @Param("resultJson") String resultJson,
                             @Param("rawResponse") String rawResponse,
                             @Param("errorCode") String errorCode,
                             @Param("deviceId") long deviceId);

    /** run 收尾：status（::run_status）+ progress + output jsonb + error_code（可空）。 */
    int updateFlowRunFinished(@Param("tenantId") long tenantId,
                              @Param("runId") long runId,
                              @Param("status") String status,
                              @Param("progress") java.math.BigDecimal progress,
                              @Param("outputJson") String outputJson,
                              @Param("errorCode") String errorCode);

    // ---- PersistenceDemo（端到端演示的建 run + 落库验证查询，阶段3第22批收编）----

    /**
     * 演示建 run：无 workflow_id（演示 run 不挂在任何流程下）。
     * 与 {@link #insertRun} 语义不同（那个必须有 workflowId/attendance），不能混用。
     */
    Long insertDemoRun(long tenantId, String runNo);

    /** 演示验证：该 run 的事件类型分布（eventType/n，按条数降序）。 */
    List<Map<String, Object>> selectDemoEventTypeCounts(long runId);

    /** 演示验证：该 run 的事件明细前 8 条（occurredAt/eventType/payload 文本）。 */
    List<Map<String, Object>> selectDemoEventDetails(long runId);

    /**
     * 执行事件批量落库 run_event（JdbcEventStore.flush 用）。
     * items 每行：eventId(UUID)/tenantId/occurredAt(OffsetDateTime)/runId/
     * eventType/traceId/payloadJson。
     */
    int insertRunEvents(@Param("items") List<Map<String, Object>> items);

    /** WorkflowResumeRecovery：重启后待续跑的 workflow run id（按 id 升序）。 */
    List<Long> selectQueuedWorkflowRunIds(long tenantId);

}
