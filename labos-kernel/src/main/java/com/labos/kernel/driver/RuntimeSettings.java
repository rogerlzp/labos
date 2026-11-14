package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.Mappers;
import com.labos.kernel.persistence.SysSettingMapper;

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

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(RuntimeSettings.class);

    /** 心跳探测间隔（毫秒）。 */
    private static volatile long heartbeatIntervalMs =
            envLong("LABOS_HEARTBEAT_INTERVAL_MS", 10_000L);

    /** 判离线阈值（毫秒）。默认 3 个探测周期，且不低于 30 秒。 */
    private static volatile long offlineAlertMs =
            envLong("LABOS_OFFLINE_ALERT_MS", Math.max(heartbeatIntervalMs * 3, 30_000L));

    /** 陈旧暂停自动 dismiss 天数（0=关，只 warn）。 */
    private static volatile int stalePauseAutoDismissDays =
            (int) envLong("LABOS_AUTO_DISMISS_STALE_PAUSE_DAYS", 0L);

    private RuntimeSettings() {
    }

    public static long heartbeatIntervalMs() {
        return heartbeatIntervalMs;
    }

    public static long offlineAlertMs() {
        return offlineAlertMs;
    }

    public static int stalePauseAutoDismissDays() {
        return stalePauseAutoDismissDays;
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
        try {
            Long iv = readLong(tenantId, "heartbeat.interval_ms");
            Long off = readLong(tenantId, "heartbeat.offline_ms");
            if (iv != null || off != null) {
                updateHeartbeat(iv == null ? heartbeatIntervalMs : iv,
                        off == null ? offlineAlertMs : off);
                log.info("[设置] 心跳参数来自库：间隔 " + (heartbeatIntervalMs / 1000)
                        + "s、离线阈值 " + (offlineAlertMs / 1000) + "s");
            }
            Long auto = readLong(tenantId, "stale_pause.auto_dismiss_days");
            if (auto != null) {
                stalePauseAutoDismissDays = Math.max(0, auto.intValue());
                log.info("[设置] 陈旧暂停自动 dismiss：{} 天（0=关）", stalePauseAutoDismissDays);
            }
        } catch (RuntimeException e) {
            log.error("[WARN] 读取 sys_setting 失败，沿用环境变量: " + e.getMessage());
        }
    }

    /** 落库（幂等 upsert）。 */
    public static void saveHeartbeat(DbConfig db, long tenantId, long intervalMs, long offlineMs)
            throws SQLException {
        long[] eff = updateHeartbeat(intervalMs, offlineMs);
        upsert(tenantId, "heartbeat.interval_ms", eff[0]);
        upsert(tenantId, "heartbeat.offline_ms", eff[1]);
    }

    /** 陈旧暂停自动 dismiss 天数落库 + 立即生效（0=关）。 */
    public static void saveStalePauseAutoDismiss(DbConfig db, long tenantId, int days)
            throws SQLException {
        int eff = Math.max(0, days);
        stalePauseAutoDismissDays = eff;
        upsert(tenantId, "stale_pause.auto_dismiss_days", eff);
    }

    /**
     * 写一条设置：**先 UPDATE，没中了 INSERT**（SQL 在 SysSettingMapper，
     * 阶段3第25批收编；两段式原因见接口注释）。
     */
    private static void upsert(long tenantId, String key, long value) {
        SysSettingMapper m = Mappers.get(SysSettingMapper.class);
        if (m.updateSetting(tenantId, key, value) > 0) {
            return;
        }
        m.insertSetting(tenantId, key, value);
    }

    private static Long readLong(long tenantId, String key) {
        String v = Mappers.get(SysSettingMapper.class).selectSettingValue(tenantId, key);
        if (v == null) {
            return null;
        }
        try {
            // jsonb 里存的是数字（可能是 10000 或 "10000"）
            return Long.parseLong(v.replace("\"", "").trim());
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
