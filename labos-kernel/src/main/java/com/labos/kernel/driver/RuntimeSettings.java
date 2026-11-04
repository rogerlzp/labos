package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 运行期可调参数（心跳间隔 / 离线阈值）。
 *
 * <p>为什么要有这个东西：这两个值改动前是**环境变量 + final 字段**，改一次要重启 API。
 * 现场调"这台设备多久没响应算掉线"是日常动作，重启会打断正在跑的实验 ——
 * 所以改成：启动时从 {@code sys_setting} 读一次，页面上改完**立即生效**（下一个探测周期）。
 *
 * <p>存两处是有意的：
 * <ul>
 *   <li>{@code sys_setting}（持久化）—— 重启后还在；</li>
 *   <li>静态字段（内存）—— 心跳线程每个周期读，不用查库。</li>
 * </ul>
 */
public final class RuntimeSettings {

    /** 心跳探测间隔（毫秒）。 */
    private static volatile long heartbeatIntervalMs =
            envLong("LABOS_HEARTBEAT_INTERVAL_MS", 10_000L);

    /** 判离线阈值（毫秒）。默认 3 个探测周期，且不低于 30 秒。 */
    private static volatile long offlineAlertMs =
            envLong("LABOS_OFFLINE_ALERT_MS", Math.max(heartbeatIntervalMs * 3, 30_000L));

    private RuntimeSettings() {
    }

    public static long heartbeatIntervalMs() {
        return heartbeatIntervalMs;
    }

    public static long offlineAlertMs() {
        return offlineAlertMs;
    }

    /**
     * 改心跳参数。**离线阈值必须 ≥ 2 个探测周期**，否则每次探测前都会先被判一次离线
     * （历史上就是"改间隔没改阈值"踩过这个坑，这里直接兜住）。
     *
     * @return 实际生效的值（可能被夹紧过）
     */
    public static long[] updateHeartbeat(long intervalMs, long offlineMs) {
        long iv = Math.max(1_000L, Math.min(intervalMs, 600_000L));
        long off = offlineMs > 0 ? offlineMs : Math.max(iv * 3, 30_000L);
        off = Math.max(off, iv * 2);
        heartbeatIntervalMs = iv;
        offlineAlertMs = off;
        return new long[]{iv, off};
    }

    /** 启动时从库读一次（没有就用环境变量/默认值）。 */
    public static void loadFromDb(DbConfig db, long tenantId) {
        try (Connection c = db.open()) {
            Long iv = readLong(c, tenantId, "heartbeat.interval_ms");
            Long off = readLong(c, tenantId, "heartbeat.offline_ms");
            if (iv != null || off != null) {
                updateHeartbeat(iv == null ? heartbeatIntervalMs : iv,
                        off == null ? offlineAlertMs : off);
                System.out.println("[设置] 心跳参数来自库：间隔 " + (heartbeatIntervalMs / 1000)
                        + "s、离线阈值 " + (offlineAlertMs / 1000) + "s");
            }
        } catch (SQLException e) {
            System.err.println("[WARN] 读取 sys_setting 失败，沿用环境变量: " + e.getMessage());
        }
    }

    /** 落库（幂等 upsert）。 */
    public static void saveHeartbeat(DbConfig db, long tenantId, long intervalMs, long offlineMs)
            throws SQLException {
        long[] eff = updateHeartbeat(intervalMs, offlineMs);
        try (Connection c = db.open()) {
            upsert(c, tenantId, "heartbeat.interval_ms", eff[0]);
            upsert(c, tenantId, "heartbeat.offline_ms", eff[1]);
        }
    }

    /**
     * 写一条设置：**先 UPDATE，没中就 INSERT**。
     *
     * <p>不用 {@code ON CONFLICT} 是因为线上 `sys_setting` 没有
     * `(tenant_id, scope, setting_key)` 的唯一约束（历史表结构缺），
     * 直接 ON CONFLICT 会报 "no unique or exclusion constraint matching"，
     * 而给 sys_setting 补唯一索引要担心已有重复行 —— 这种两段式写法在哪都能跑。
     */
    private static void upsert(Connection c, long tenantId, String key, long value)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE sys_setting
                   SET value = to_jsonb(?::bigint), updated_at = now()
                 WHERE tenant_id = ? AND scope = 'global' AND setting_key = ?
                """)) {
            ps.setLong(1, value);
            ps.setLong(2, tenantId);
            ps.setString(3, key);
            if (ps.executeUpdate() > 0) {
                return;
            }
        }
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO sys_setting (tenant_id, scope, setting_key, value, description, updated_at)
                VALUES (?, 'global', ?, to_jsonb(?::bigint), '运行期可调参数（页面可改）', now())
                """)) {
            ps.setLong(1, tenantId);
            ps.setString(2, key);
            ps.setLong(3, value);
            ps.executeUpdate();
        }
    }

    private static Long readLong(Connection c, long tenantId, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT value FROM sys_setting
                 WHERE tenant_id = ? AND scope = 'global' AND setting_key = ?
                """)) {
            ps.setLong(1, tenantId);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String v = rs.getString(1);
                if (v == null) {
                    return null;
                }
                // jsonb 里存的是数字（可能是 10000 或 "10000"）
                v = v.replace("\"", "").trim();
                return Long.parseLong(v);
            }
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long envLong(String name, long dft) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            return dft;
        }
        try {
            long n = Long.parseLong(v.trim());
            return n > 0 ? n : dft;
        } catch (NumberFormatException e) {
            return dft;
        }
    }
}
