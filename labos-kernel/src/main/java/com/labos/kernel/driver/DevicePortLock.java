package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 通道锁：设备的进/出口。
 *
 * <p><b>为什么不是简单的互斥</b>：有些通道一次能过两块板（宽传送带、多层托盘），
 * 用互斥会把并发砍掉一半。所以按 {@code capacity} 做**信号量**：
 * {@code capacity=1} 时等价互斥，{@code >1} 时允许同时 N 个。
 *
 * <p><b>和位置锁的区别</b>：
 * <table>
 *   <tr><th>锁</th><th>语义</th><th>为什么</th></tr>
 *   <tr><td>通道</td><td>信号量（1..N）</td><td>宽通道能同时过几块</td></tr>
 *   <tr><td>位置</td><td>互斥（0 或 1）</td><td>一个位置只能放一块板</td></tr>
 * </table>
 */
public final class DevicePortLock {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(DevicePortLock.class);

    private final DbConfig db;
    private final long tenantId;

    public DevicePortLock(DbConfig db, long tenantId) {
        this.db = db;
        this.tenantId = tenantId;
    }

    /**
     * 抢通道：占用数 +1（未满才成功）。
     *
     * <p>一条 UPDATE 完成"检查+占用"，原子性由数据库保证：
     * {@code WHERE in_use < max_capacity} 不满足时更新 0 行。
     *
     * @param portId {@code device_port.id}
     * @return true = 抢到
     */
    public boolean tryAcquire(long portId) {
        String sql = """
                UPDATE device_port_usage
                SET in_use = in_use + 1, updated_at = now()
                WHERE tenant_id = ? AND port_id = ? AND in_use < max_capacity
                RETURNING in_use
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, portId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;   // 死锁/锁等待时保守认为没抢到
        }
    }

    /** 释放通道：占用数 -1（不会减到负数）。 */
    public void release(long portId) {
        String sql = """
                UPDATE device_port_usage
                SET in_use = in_use - 1, updated_at = now()
                WHERE tenant_id = ? AND port_id = ? AND in_use > 0
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, portId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("[通道] 释放失败 port=" + portId + "：" + e.getMessage());
        }
    }

    /**
     * 按设备编号找到可用的通道 id。
     *
     * @param direction 需要的方向：{@code in} / {@code out} / {@code both}
     *                  （{@code both} 方向能当 in 也能当 out 用）
     * @return 通道 id；null = 没配通道或都满了
     */
    public Long findAvailablePort(long resourceId, String direction) {
        String sql = """
                SELECT p.id
                FROM device_port p
                JOIN device_port_usage u ON u.port_id = p.id AND u.tenant_id = p.tenant_id
                WHERE p.tenant_id = ? AND p.resource_id = ?
                  AND (p.direction = ? OR p.direction = 'both')
                  AND u.in_use < u.max_capacity
                ORDER BY p.capacity DESC, p.id
                LIMIT 1
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, resourceId);
            ps.setString(3, direction);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        } catch (SQLException e) {
            return null;
        }
    }

    /** 设备有没有配通道（没配说明是存量数据，跳过通道锁）。 */
    public boolean hasPorts(long resourceId) {
        try {
            return com.labos.kernel.persistence.Mappers.get(
                    com.labos.kernel.persistence.DeviceStateMapper.class)
                    .hasPorts(tenantId, resourceId);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 当前占用数（排查用）。 */
    public int inUse(long portId) {
        try {
            Integer v = com.labos.kernel.persistence.Mappers.get(
                    com.labos.kernel.persistence.DeviceStateMapper.class)
                    .selectPortInUse(tenantId, portId);
            return v == null ? -1 : v;
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
