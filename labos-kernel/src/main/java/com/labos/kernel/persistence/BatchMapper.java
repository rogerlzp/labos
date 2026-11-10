package com.labos.kernel.persistence;

import java.util.List;
import java.util.Map;

/**
 * 分批执行编排的数据访问（原 {@code BatchOrchestrator} 里的裸 SQL）。
 *
 * <p>调用方 {@code BatchOrchestrator} 是 new 出来的工具类，拿不到构造注入，
 * 用 {@link Mappers#get} 取本接口的代理 —— 在 {@code Tx.inTransaction}
 * 或 {@code @Transactional} 内会复用同一条绑定连接。
 *
 * <p>返回 {@code Map} 的查询统一 camelCase 引号别名（pgjdbc 默认小写折叠），
 * 调用方按 camelCase key 取值；时间戳列在 Map 里是 {@code java.sql.Timestamp}。
 */
public interface BatchMapper {

    // ---- 读 ----

    /** 实验的批次开关原值；行不存在 / NULL / {@code <= 0} 由调用方归一成 null。 */
    Integer selectBatchSplit(long tenantId, long experimentId);

    /** 批次 run 的批次上下文（batchRunId/batchId/batchNo/barcodes）；非批次 run 无行。 */
    Map<String, Object> selectBatchContext(long tenantId, long experimentRunId);

    /** run 的 variables 摊平（jsonb_each_text），key/value 两列。 */
    List<Map<String, Object>> selectRunVariables(long tenantId, long runId);

    /** run → 批次尝试归属（batchRunId/batchId/experimentId）；无归属时无行。 */
    Map<String, Object> selectBatchRef(long tenantId, long experimentRunId);

    /** 自动续队候选批（experimentId/batchNo），按 batch_no、experiment_id 升序。 */
    List<Map<String, Object>> selectPausedBatchCandidates(long tenantId);

    /** 实验的全部批次 id/batchNo（派生态由调用方按读侧自愈口径过滤）。 */
    List<Map<String, Object>> selectBatchIdNo(long tenantId, long experimentId);

    /** 同实验未结束（queued/running/paused）的批次尝试（runId/batchRunId/batchNo/attemptNo/status）。 */
    Map<String, Object> selectActiveAttempt(long tenantId, long experimentId);

    /** 派生批状态：任一尝试 completed ⇒ completed；否则最新尝试状态（run 优先）。 */
    String selectDerivedBatchStatus(long tenantId, long batchId);

    /** 实验所属岛；行缺失或 island_id 为 NULL 时返回 null（调用方归一成 0）。 */
    Long selectIslandIdOfExperiment(long tenantId, long experimentId);

    /** 实验样品（sampleId/barcode），按 sample_id 升序，同一样品多角色只算一次。 */
    List<Map<String, Object>> selectSamples(long tenantId, long experimentId);

    /** 批次视图：批次 + 所有尝试平铺（loadBatches 用，键见 XML 注释）。 */
    List<Map<String, Object>> selectBatchViewRows(long tenantId, long experimentId);

    /** 锁实验行；返回 0 行时调用方抛"实验不存在"。 */
    Integer lockExperiment(long tenantId, long experimentId);

    /** 锁实验行并读 batch_split；返回 0 行时调用方抛"实验不存在"，NULL/{@code <= 0} 由调用方归一。 */
    Integer lockExperimentBatchSplit(long tenantId, long experimentId);

    /** 锁批次行（D12 attempt_no 串行化点）；返回 0 行时调用方抛"批次不存在"。 */
    Integer lockBatch(long tenantId, long batchId);

    /** 实验是否已有批次行。 */
    boolean hasBatches(long tenantId, long experimentId);

    // ---- 写 ----

    /** 幂等建批（ON CONFLICT DO NOTHING）；plan 逐条循环调用。 */
    int insertBatch(long tenantId, long experimentId, int batchNo, int sampleCount,
                    String samplesJson);

    /** 建一条 queued 的 experiment_run（带 variables）；返回 run id。 */
    long insertQueuedRunWithVars(long tenantId, long experimentId, String varsJson);

    /** 建一条 queued 的 experiment_run（不带 variables）；返回 run id。 */
    long insertQueuedRunPlain(long tenantId, long experimentId);

    /** 建一条 queued 的批次尝试（started_at 留空，D8 回写时 COALESCE 补）。 */
    long insertQueuedBatchRun(long tenantId, long batchId, long runId, int attemptNo);

    /** 建一条 running 的批次尝试（同步路径，开跑即落 started_at）。 */
    long insertBatchRunRunning(long tenantId, long batchId, long runId, int attemptNo);

    /** 按 experiment_run 当前状态回写 attempt 行（终态落 finished_at，非终态清掉）。 */
    int updateAttemptFromRun(long tenantId, long batchRunId);

    /** 更新批次状态。 */
    int updateBatchStatus(long tenantId, long batchId, String status);

    /** 下一 attempt_no（COALESCE(MAX,0)+1）；须在批次行锁事务内调用。 */
    int nextAttemptNo(long tenantId, long batchId);

    /** 落 attempt 终态；terminal=false 时 finished_at 保持原值。 */
    int finishBatchRun(long tenantId, long batchRunId, String status, String error,
                       boolean terminal);

    /** 设置/清空 experiment.batch_split（null 关闭分批）。 */
    int updateExperimentBatchSplit(long tenantId, long experimentId, Integer batchSplit);

    /** 执行器 settle 前抛异常的兜底落库（与 ExperimentScheduler.markStatus 同口径）。 */
    int markRunFailed(long tenantId, long runId, String error);
}
