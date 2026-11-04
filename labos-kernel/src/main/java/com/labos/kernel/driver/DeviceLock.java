package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;

/**
 * 设备级锁：一台设备同一时刻只服务一个执行。
 *
 * <p><b>为什么不用岛级锁</b>：岛是"设备的集合"，不是一个可执行单元。
 * 一个岛里有孵箱、离心机、读板机、机械臂，只有**机械臂**是真共享的
 * （同一时刻只能搬一块板）。岛级互斥会把"抢机械臂"放大成"抢整个岛"，
 * 让本来能并行的实验互相排队：
 *
 * <pre>
 *   实验 A：孵育 30 分钟（只用孵箱）
 *   实验 B：离心 5 分钟（只用离心机）
 *   岛锁 → B 等 30 分钟。这是纯浪费。
 * </pre>
 *
 * <p>设备锁的粒度正好：A 占孵箱、B 占离心机，互不影响；
 * 只有两边都要搬板时才争机械臂——这才是真实的物理约束。
 *
 * <p><b>机械臂不用特殊处理</b>：它就是一台设备，
 * 用同一把锁自然实现了"一次搬一块板"。
 *
 * <h3>和位置锁的分工</h3>
 * <table>
 *   <tr><th>锁</th><th>粒度</th><th>防什么</th></tr>
 *   <tr><td>设备锁（本类）</td><td>一台设备</td><td>两个实验同时命令同一台设备</td></tr>
 *   <tr><td>位置锁 {@link PositionLock}</td><td>一个层位</td><td>两块板放同一个位置</td></tr>
 * </table>
 */
public final class DeviceLock {

    private final DbConfig db;
    private final long tenantId;

    public DeviceLock(DbConfig db, long tenantId) {
        this.db = db;
        this.tenantId = tenantId;
    }

    /**
     * 抢设备。
     *
     * <p>用一条 UPDATE 做"检查+占用"，原子性由数据库保证：
     * 只有 {@code current_run_id} 为空、或已经是自己时才更新成功。
     *
     * @param resourceId {@code resource_node.id}（不是 device_instance.id）
     * @param runId      谁要占
     * @return true = 抢到
     */
    public boolean tryAcquire(long resourceId, long runId) {
        String sql = """
                UPDATE device_state
                SET current_run_id = ?, status = 'running', updated_at = now()
                WHERE tenant_id = ? AND resource_id = ?
                  AND (current_run_id IS NULL OR current_run_id = ?)
                RETURNING resource_id
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, runId);
            ps.setLong(2, tenantId);
            ps.setLong(3, resourceId);
            ps.setLong(4, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;   // 表锁/死锁时保守认为没抢到
        }
    }

    /**
     * 抢设备，抢不到就等一会儿再试。
     *
     * <p><b>为什么要重试</b>：设备被别的执行占着，不代表永远占着。
     * 长任务（孵育 30 分钟）会占很久，但短任务（扫码 3 秒）马上就好。
     * 直接失败会让调度变得很脆——明明等两秒就行的事，没必要报错。
     *
     * @param timeoutMs 最多等多久
     * @return true = 抢到
     */
    public boolean acquireWithWait(long resourceId, long runId, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (tryAcquire(resourceId, runId)) {
                return true;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * 释放设备。
     *
     * <p>只释放自己占的（{@code current_run_id = ?}），
     * 避免把别人抢到的锁误释放——那种 bug 很难查。
     */
    public void release(long resourceId, long runId) {
        String sql = """
                UPDATE device_state
                SET current_run_id = NULL, current_method = NULL,
                    status = CASE WHEN last_error IS NULL THEN 'idle' ELSE status END,
                    updated_at = now()
                WHERE tenant_id = ? AND resource_id = ? AND current_run_id = ?
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, resourceId);
            ps.setLong(3, runId);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[设备锁] 释放失败：" + e.getMessage());
        }
    }

    /** 谁占着这台设备；null = 空闲。 */
    public Long holder(long resourceId) {
        String sql = "SELECT current_run_id FROM device_state"
                + " WHERE tenant_id = ? AND resource_id = ?";
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, resourceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long v = rs.getLong(1);
                    return rs.wasNull() ? null : v;
                }
            }
        } catch (SQLException ignore) {
            // 查不到当空闲
        }
        return null;
    }

    /**
     * 清理孤儿锁：{@code current_run_id} 指向的 run 已经结束，但锁还挂着。
     *
     * <p><b>为什么会有孤儿锁</b>：
     * <ul>
     *   <li>执行线程被 kill -9，finally 跑不到</li>
     *   <li>走了不走 onStepDone 的代码路径（比如模拟总线）</li>
     *   <li>释放时 runId 对不上（两套 ID 转换出错）</li>
     * </ul>
     *
     * <p>孤儿锁的后果很严重：那台设备**永久不可用**，直到重启。
     * 实测已经出现过（两台 INCUBATE 挂着几小时前的 runId）。
     *
     * @return 清理了几条
     */
    public int releaseOrphans() {
        String sql = """
                UPDATE device_state ds
                SET current_run_id = NULL, status = 'idle', updated_at = now()
                WHERE ds.tenant_id = ?
                  AND ds.current_run_id IS NOT NULL
                  AND EXISTS (
                        SELECT 1 FROM run r
                        WHERE r.id = ds.current_run_id
                          AND r.status::text IN ('completed','failed','aborted','cancelled'))
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            return 0;
        }
    }

    /**
     * 清理超时锁：run 还在 running，但没动静超过阈值。
     *
     * <p>这种情况要么是真卡住了，要么是执行线程死了但 run 状态没更新。
     * 保守起见只清"超过 2 小时"的——正常的孵育不会超过这个数。
     *
     * @return 清理了几条
     */
    public int releaseStale(Duration threshold) {
        String sql = """
                UPDATE device_state ds
                SET current_run_id = NULL, status = 'idle',
                    last_error = '锁超时自动释放', updated_at = now()
                WHERE ds.tenant_id = ?
                  AND ds.current_run_id IS NOT NULL
                  AND ds.updated_at < now() - (? || ' seconds')::interval
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setString(2, String.valueOf(threshold.toSeconds()));
            return ps.executeUpdate();
        } catch (SQLException e) {
            return 0;
        }
    }
}
