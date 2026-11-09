package com.labos.kernel.persistence;

import com.labos.kernel.engine.ExecutionEvent;
import com.labos.kernel.engine.ExecutionListener;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * 把执行事件写入 {@code run_event}（按月分区表）。
 *
 * <p>两条重要设计：
 * <ol>
 *   <li><b>批量提交</b>：1000 个节点会产生数千条事件，逐条提交撑不住</li>
 *   <li><b>自己吞异常并计数</b>：事件落库失败绝不能让实验停摆，
 *       但也必须留下痕迹（{@link #failedCount()}）供运维发现</li>
 * </ol>
 */
public final class JdbcEventStore implements ExecutionListener, AutoCloseable {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(JdbcEventStore.class);
    /** 兼容原有 System.err.printf 的格式化输出。 */
    private static void errorf(String format, Object... args) {
        log.error(String.format(format, args));
    }

    private static final String INSERT = """
            INSERT INTO run_event
              (event_id, tenant_id, occurred_at, aggregate_type, aggregate_id,
               event_type, severity, actor, trace_id, payload)
            VALUES (?, ?, ?, 'run', ?, ?, 0, 'engine', ?, ?::jsonb)
            """;

    private final Connection conn;
    private final PreparedStatement ps;
    private final long tenantId;
    private final long runId;
    private final String traceId;
    private final int batchSize;

    private int pending = 0;
    private int written = 0;
    private int failed = 0;

    public JdbcEventStore(DbConfig db, long tenantId, long runId, String traceId, int batchSize)
            throws SQLException {
        this.conn = db.open();
        // 不再显式 setAutoCommit(true)：若在 Spring 事务里创建本对象，
        // db.open() 返回的是线程绑定的事务连接，改它的 autocommit 会直接破坏事务。
        // 非事务路径（执行体后台线程）本来就默认 autocommit=true，删掉无行为变化。
        this.ps = conn.prepareStatement(INSERT);
        this.tenantId = tenantId;
        this.runId = runId;
        this.traceId = traceId;
        this.batchSize = Math.max(1, batchSize);
    }

    @Override
    public void onEvent(ExecutionEvent e) {
        try {
            ps.setObject(1, UUID.randomUUID());
            ps.setLong(2, tenantId);
            ps.setObject(3, java.time.OffsetDateTime.ofInstant(e.at(), java.time.ZoneId.systemDefault()));
            ps.setLong(4, runId);
            ps.setString(5, e.type().name());
            ps.setString(6, traceId);
            ps.setString(7, Jsons.obj(
                    "seq", e.seq(),
                    "nodeId", e.nodeId(),
                    "nodeKey", e.nodeKey(),
                    "detail", e.detail()));
            ps.addBatch();
            if (++pending >= batchSize) {
                flush();
            }
        } catch (SQLException ex) {
            failed++;
            errorf("[WARN] 事件落库失败（已跳过）: %s | %s%n", e, ex.toString());
        }
    }

    public synchronized void flush() {
        if (pending == 0) {
            return;
        }
        try {
            ps.executeBatch();
            written += pending;
        } catch (SQLException ex) {
            failed += pending;
            errorf("[WARN] 批量落库失败 %d 条: %s%n", pending, ex.toString());
        } finally {
            pending = 0;
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
        try {
            ps.close();
        } catch (SQLException ignored) {
            // 关闭失败无需处理
        }
        try {
            conn.close();
        } catch (SQLException ignored) {
            // 关闭失败无需处理
        }
    }
}
