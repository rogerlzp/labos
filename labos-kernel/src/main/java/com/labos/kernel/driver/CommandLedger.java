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
    }

    /**
     * 启动对账：把陈旧的 DISPATCH_STARTED 标 UNKNOWN。
     *
     * @return 标记条数
     */
    public static int reconcileOnStartup(DbConfig db, long tenantId, int staleMinutes)
            throws SQLException {
        return mapper().markStaleUnknown(tenantId, Math.max(0, staleMinutes));
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
