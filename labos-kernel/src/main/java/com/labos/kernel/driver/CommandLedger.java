package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;

import java.sql.SQLException;

/**
 * 设备命令账本（P-07 第一切片）。
 *
 * <p><b>解决什么</b>：设备指令是「有物理副作用」的调用。进程在
 * 「已下发、还没拿到回包」的窗口崩溃时，重启后无法知道机械臂到底动没动；
 * 如果直接重发，可能重复搬同一块板。
 *
 * <p>本账本把每次下发拆成两个可查事实：
 * <ol>
 *   <li>派发前 {@code INSERT ... DISPATCH_STARTED}（同一 idempotency_key 唯一）；</li>
 *   <li>回包后 {@code ACKED}/{@code FAILED}；启动时把陈旧的
 *       {@code DISPATCH_STARTED} 标成 {@code UNKNOWN}（物理结果未知）。</li>
 * </ol>
 *
 * <p><b>边界</b>：本切片只保证「单条指令账本 + 重启对账 + 同 key 禁止重复下发」；
 * 跨指令尝试/Outbox 传输层、UNKNOWN 的人工核对处置属 P-07.2。
 */
public final class CommandLedger {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(CommandLedger.class);

    /** 启动对账：超过该分钟数仍是 DISPATCH_STARTED 视为「派发中断」。 */
    public static final int STALE_MINUTES = 10;

    private CommandLedger() {
    }

    /** 账本一行。{@code created=false} 表示同 key 已存在（应阻断重复下发）。 */
    public record Entry(long id, String state, boolean created) {
    }

    private static com.labos.kernel.persistence.CommandMapper mapper() {
        return com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.CommandMapper.class);
    }

    /**
     * 派发前登记。同一 {@code idempotencyKey} 已存在时不覆盖，返回现有行供调用方
     * fail-closed 处理（{@link #created()} == false）。
     */
    public static Entry begin(DbConfig db, long tenantId, String idempotencyKey,
                              long runId, long nodeId, int attempt, int stepSeq,
                              long deviceId, String method, String commandId,
                              String traceId) throws SQLException {
        // c 形参保留（SqlWork 签名）：mapper 在 Tx 事务内经 DataSource 同步复用绑定连接
        return com.labos.kernel.persistence.Tx.inTransaction(db, c -> {
            Long ledgerId = mapper().insertDispatchBegin(
                    tenantId, idempotencyKey, runId, nodeId, attempt, stepSeq,
                    deviceId, method, commandId, traceId);
            if (ledgerId == null) {
                // 已存在：读回状态，不写 Outbox（避免重复事件）
                java.util.Map<String, Object> existing =
                        mapper().selectLedgerEntry(tenantId, idempotencyKey);
                long id = existing == null ? 0L
                        : ((Number) existing.get("id")).longValue();
                String state = existing == null ? "UNKNOWN"
                        : (String) existing.get("state");
                com.labos.kernel.persistence.Tx.rollbackOnly();
                return new Entry(id, state, false);
            }
            // P-07.4：与账本同事务写 Outbox（账本在 ⇒ 事件在）
            mapper().insertOutboxEvent(tenantId, ledgerId, runId, commandId, deviceId,
                    method, "{\"deviceId\":" + deviceId
                            + ",\"method\":\"" + (method == null ? "" : method)
                            + "\"}");
            return new Entry(ledgerId, "DISPATCH_STARTED", true);
        });
    }

    /** 回包后落终态。 */
    /**
     * 记录一次中止请求的结果（B-04）。
     *
     * <p>为什么要落库：中止是**执行安全**里最容易自欺的一步。把
     * {@code abort_state} 与命令本身的 {@code state} 分开存，事后才能回答
     * 「这条命令到底是被设备确认停掉的，还是只是我们不等了」。
     *
     * <p>旁路写：记不上只打日志，不阻断调用方。宁可少一条审计行，
     * 也不能因为审计失败而把一条已经在跑的物理动作重发一遍。
     */
    public static void recordAbort(DbConfig db, long ledgerId, AbortResult result) {
        if (db == null || ledgerId <= 0L || result == null) {
            return;
        }
        try {
            mapper().updateAbortResult(ledgerId, result.state().name(),
                    trunc(result.detail(), 200));
        } catch (RuntimeException e) {
            // 旁路写 fail-open：mapper 不声明 SQLException，SQL 异常已被
            // MyBatis 包装成 RuntimeException
            log.warn("[中止结果未记账] ledger={} state={} reason={}",
                    ledgerId, result.state(), e.getMessage());
        }
    }

    public static void finish(DbConfig db, long ledgerId, boolean ok,
                              String errorCode, String errorMessage,
                              String commandId) throws SQLException {
        mapper().finishLedger(ledgerId, ok ? "ACKED" : "FAILED", errorCode,
                trunc(errorMessage, 250), commandId);
        // R4：回包即终态 —— phase 推到 DONE。
        // 只进不退：若已经是 UNKNOWN（启动对账先标过），这句命中 0 行，无副作用。
        mapper().advancePhase(ledgerId, PHASE_DONE);
    }

    // ================================================================ R4：命令阶段
    //
    // 设计要求（runtime-consistency/02 §1）：PENDING → SENT → RECEIVED → EXECUTING → DONE，
    // 异常落 UNKNOWN。当前驱动是**同步阻塞**调用（begin → driver.execute() → finish），
    // 拿不到设备 ACK / 「开始执行」回调，所以真实可达的是 PENDING/SENT/DONE；
    // RECEIVED/EXECUTING 先常量预留，等驱动支持流式回包再在 DriverStepExecutor 补推进点。

    public static final String PHASE_PENDING = "PENDING";
    public static final String PHASE_SENT = "SENT";
    /** 预留：当前同步驱动拿不到设备 ACK，暂不可达。 */
    public static final String PHASE_RECEIVED = "RECEIVED";
    /** 预留：当前同步驱动拿不到「开始执行」回调，暂不可达。 */
    public static final String PHASE_EXECUTING = "EXECUTING";
    public static final String PHASE_DONE = "DONE";
    public static final String PHASE_UNKNOWN = "UNKNOWN";

    /**
     * R4：推进命令阶段（**只进不退**）。
     *
     * <p>守卫在 SQL 里（{@code array_position} 比序号 + 终态封闭），
     * 倒退 / 乱序 / 已终态 / 未知值都返回 false。调用方无须先读当前阶段，并发安全。
     */
    public static boolean advancePhase(DbConfig db, long ledgerId, String phase)
            throws SQLException {
        return ledgerId > 0L && mapper().advancePhase(ledgerId, phase) > 0;
    }

    /** R4 超时对账结果。stillRunning 不是终态，不能计入 unknown。 */
    public record ReconcileSummary(int confirmedCompleted, int confirmedFailed,
                                   int stillRunning, int unknown) {
        public int settled() {
            return confirmedCompleted + confirmedFailed;
        }
    }

    /**
     * 兼容旧调用：没有注册表时逐条尝试查询（会得到「无法查询」），仍无法确认才落 UNKNOWN。
     *
     * @return 查询后仍 UNKNOWN 的条数
     */
    public static int reconcileOnStartup(DbConfig db, long tenantId, int staleMinutes)
            throws SQLException {
        return reconcileWithQuery(db, tenantId, staleMinutes, null).unknown();
    }

    /**
     * R4 超时对账：**逐条先查询设备真实状态，再决定账本终态**。
     *
     * <ul>
     *   <li>设备明确完成 / 失败 → ACKED / FAILED + phase=DONE；</li>
     *   <li>设备明确仍在执行 → 保留 DISPATCH_STARTED，phase 推进到 EXECUTING；</li>
     *   <li>无此命令、无响应、无驱动、查询不支持或查询异常 → UNKNOWN，转人工，
     *       不自动重发。</li>
     * </ul>
     *
     * <p>所有写库都有 {@code state='DISPATCH_STARTED'} 护栏，正常回包若在对账期间先到，
     * 对账结果不会覆盖它。NOT_FOUND 不等于「设备没动过」，同样落 UNKNOWN。
     */
    public static ReconcileSummary reconcileWithQuery(DbConfig db, long tenantId,
                                                      int staleMinutes,
                                                      DriverRegistry registry)
            throws SQLException {
        int completed = 0;
        int failed = 0;
        int running = 0;
        int unknown = 0;
        for (java.util.Map<String, Object> row
                : mapper().selectStaleDispatches(tenantId, Math.max(0, staleMinutes))) {
            long ledgerId = ((Number) row.get("id")).longValue();
            Number device = (Number) row.get("deviceId");
            long deviceId = device == null ? 0L : device.longValue();
            String commandId = (String) row.get("commandId");
            CommandQueryResult query = queryDevice(registry, deviceId, commandId);

            switch (query.state()) {
                case COMPLETED -> {
                    int n = mapper().completeQueriedLedger(ledgerId, "ACKED", null,
                            queryEvidence(query));
                    if (n > 0) {
                        mapper().advancePhase(ledgerId, PHASE_DONE);
                        completed++;
                    }
                }
                case FAILED -> {
                    int n = mapper().completeQueriedLedger(ledgerId, "FAILED", "EC-99-0001",
                            queryEvidence(query));
                    if (n > 0) {
                        mapper().advancePhase(ledgerId, PHASE_DONE);
                        failed++;
                    }
                }
                case IN_PROGRESS -> {
                    // 设备明确还在动：绝不 UNKNOWN、绝不重发；仅补齐可观测阶段。
                    mapper().advancePhase(ledgerId, PHASE_EXECUTING);
                    running++;
                    log.info("[命令对账] ledger={} command={} 设备仍在执行，保持在途",
                            ledgerId, commandId);
                }
                case NOT_FOUND, UNKNOWN -> {
                    int n = mapper().markQueriedUnknown(ledgerId,
                            trunc("查询后仍无法确认，需人工对账；" + queryEvidence(query), 250));
                    if (n > 0) {
                        unknown++;
                        log.warn("[命令对账] ledger={} command={} 查询后仍 UNKNOWN：{}",
                                ledgerId, commandId, query.detail());
                    }
                }
            }
        }
        return new ReconcileSummary(completed, failed, running, unknown);
    }

    private static CommandQueryResult queryDevice(DriverRegistry registry,
                                                  long deviceId, String commandId) {
        if (commandId == null || commandId.isBlank()) {
            return CommandQueryResult.unknown("账本缺 command_id，无法按命令查询");
        }
        if (registry == null) {
            return CommandQueryResult.unknown("无驱动注册表，无法查询设备");
        }
        try {
            DeviceDriver driver = registry.driverFor(deviceId).orElse(null);
            if (driver == null) {
                return CommandQueryResult.unknown("找不到设备驱动 device=" + deviceId);
            }
            if (!driver.healthy()) {
                return CommandQueryResult.unknown("设备驱动不健康，查询无响应 device=" + deviceId);
            }
            CommandQueryResult result = driver.queryStatus(commandId);
            return result == null
                    ? CommandQueryResult.unknown("驱动查询返回空结果")
                    : result;
        } catch (RuntimeException e) {
            return CommandQueryResult.unknown("QueryStatus 异常：" + e.getMessage());
        }
    }

    private static String queryEvidence(CommandQueryResult query) {
        String detail = query.detail() == null || query.detail().isBlank()
                ? "无设备详情" : query.detail();
        // error_message 是 varchar(256)：设备原始回包可能很长，先截断再落证据。
        return trunc("QueryStatus=" + query.state() + "；" + detail, 240);
    }

    /** UNKNOWN 账本行（人工核对处置入口用）。 */
    public record Unknown(long id, String key, long runId, Long nodeId, String method,
                          String commandId, String traceId, String errorCode,
                          String errorMessage, String createdAt) {
    }

    /** 列出当前 UNKNOWN（物理结果未知）的命令，最多 limit 条。 */
    public static java.util.List<Unknown> listUnknown(DbConfig db, long tenantId, int limit)
            throws SQLException {
        int cap = Math.max(1, Math.min(limit, 200));
        java.util.List<Unknown> rows = new java.util.ArrayList<>();
        for (java.util.Map<String, Object> m : mapper().selectUnknownList(tenantId, cap)) {
            Number node = (Number) m.get("nodeId");
            rows.add(new Unknown(((Number) m.get("id")).longValue(), (String) m.get("key"),
                    ((Number) m.get("runId")).longValue(),
                    node == null ? null : node.longValue(),
                    (String) m.get("method"), (String) m.get("commandId"),
                    (String) m.get("traceId"), (String) m.get("errorCode"),
                    (String) m.get("errorMessage"), (String) m.get("createdAt")));
        }
        return rows;
    }

    /**
     * 「已请求中止、但设备没确认停」的一行（B-04 的人工核验入口）。
     *
     * <p>这类命令既不能当作"停成功了"（会漏掉还在动的设备），也不能当作"没停"
     * 就直接重发（会重复执行物理动作）——只能人来看一眼。
     */
    public record AbortPending(long id, String key, long runId, Long nodeId,
                               String method, String commandId, String state,
                               String abortState, String abortDetail, String abortAt) {
    }

    /**
     * 列出中止未被设备确认的账本行（{@code abort_state} 存在且不是 PHYSICALLY_STOPPED）。
     *
     * <p>走 {@code ix_command_ledger_abort_unconfirmed} 部分索引。
     */
    public static java.util.List<AbortPending> listUnconfirmedAbort(DbConfig db, long tenantId,
                                                                   int limit) throws SQLException {
        int cap = Math.max(1, Math.min(limit, 200));
        java.util.List<AbortPending> rows = new java.util.ArrayList<>();
        for (java.util.Map<String, Object> m
                : mapper().selectUnconfirmedAborts(tenantId, cap)) {
            Number node = (Number) m.get("nodeId");
            rows.add(new AbortPending(((Number) m.get("id")).longValue(),
                    (String) m.get("key"), ((Number) m.get("runId")).longValue(),
                    node == null ? null : node.longValue(),
                    (String) m.get("method"), (String) m.get("commandId"),
                    (String) m.get("state"), (String) m.get("abortState"),
                    (String) m.get("abortDetail"), (String) m.get("abortAt")));
        }
        return rows;
    }

    /**
     * 人工核对处置：把 UNKNOWN 行落成 ACKED（confirm-done）或 FAILED（confirm-failed）。
     *
     * <p>只允许从 UNKNOWN 转移（并发二次处置返回 0）；带 resolved_at/resolved_by 留痕。
     *
     * @return 受影响行数（0 = 不存在或已不是 UNKNOWN）
     */
    public static int resolve(DbConfig db, long tenantId, long ledgerId, boolean done,
                              String note, Long by) throws SQLException {
        return mapper().resolveLedger(tenantId, ledgerId, done ? "ACKED" : "FAILED",
                by, trunc(note == null ? "人工核对确认" : note, 250));
    }

    /** 查询某条 run 的命令账本状态（自测/排障）。 */
    public static String stateOf(DbConfig db, long tenantId, String key) throws SQLException {
        return com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.CommandMapper.class)
                .selectLedgerState(tenantId, key);
    }

    private static String trunc(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
