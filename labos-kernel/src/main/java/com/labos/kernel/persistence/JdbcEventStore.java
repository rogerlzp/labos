package com.labos.kernel.persistence;

import com.labos.kernel.engine.ExecutionEvent;
import com.labos.kernel.engine.ExecutionListener;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 把执行事件写入 {@code run_event}（按月分区表）。
 *
 * <p>两条重要设计：
 * <ol>
 *   <li><b>批量提交</b>：1000 个节点会产生数千条事件，逐条提交撑不住
 *       （Java 侧缓冲攒批，{@link #flush} 时经 {@link RunMapper#insertRunEvents}
 *       多行 INSERT 一次落库 —— 与原 PreparedStatement.addBatch 语义等价）</li>
 *   <li><b>自己吞异常并计数</b>：事件落库失败绝不能让实验停摆，
 *       但也必须留下痕迹（{@link #failedCount()}）供运维发现</li>
 * </ol>
 *
 * <p>收编说明（阶段3）：原先持有 db.open() 的专用 Connection + 预编译语句，
 * 非事务路径下每次 executeBatch 即自动提交。现在无事务包裹时 mapper 调用
 * 同样是逐语句自动提交，行为一致；不再占用一条长连接。
 */
public final class JdbcEventStore implements ExecutionListener, AutoCloseable {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(JdbcEventStore.class);
    /** 兼容原有 System.err.printf 的格式化输出。 */
    private static void errorf(String format, Object... args) {
        log.error(String.format(format, args));
    }

    private final long tenantId;
    private final long runId;
    private final String traceId;
    private final int batchSize;

    private final List<Map<String, Object>> pending = new ArrayList<>();
    private int written = 0;
    private int failed = 0;

    public JdbcEventStore(DbConfig db, long tenantId, long runId, String traceId, int batchSize)
            throws SQLException {
        this.tenantId = tenantId;
        this.runId = runId;
        this.traceId = traceId;
        this.batchSize = Math.max(1, batchSize);
    }

    @Override
    public void onEvent(ExecutionEvent e) {
        try {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("eventId", UUID.randomUUID());
            row.put("tenantId", tenantId);
            row.put("occurredAt",
                    java.time.OffsetDateTime.ofInstant(e.at(), java.time.ZoneId.systemDefault()));
            row.put("runId", runId);
            row.put("eventType", e.type().name());
            row.put("traceId", traceId);
            row.put("payloadJson", Jsons.obj(
                    "seq", e.seq(),
                    "nodeId", e.nodeId(),
                    "nodeKey", e.nodeKey(),
                    "detail", e.detail()));
            pending.add(row);
            if (pending.size() >= batchSize) {
                flush();
            }
        } catch (RuntimeException ex) {
            failed++;
            errorf("[WARN] 事件落库失败（已跳过）: %s | %s%n", e, ex.toString());
        }
    }

    public synchronized void flush() {
        if (pending.isEmpty()) {
            return;
        }
        int n = pending.size();
        try {
            Mappers.get(RunMapper.class).insertRunEvents(pending);
            written += n;
        } catch (RuntimeException ex) {
            failed += n;
            errorf("[WARN] 批量落库失败 %d 条: %s%n", n, ex.toString());
        } finally {
            pending.clear();
        }
    }

    public int written() {
        return written;
    }

    public int failedCount() {
        return failed;
    }

    @Override
    public void close() {
        flush();
    }
}
