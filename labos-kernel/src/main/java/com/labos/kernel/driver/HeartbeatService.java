package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 设备心跳：定期探活，把在线/离线写进 device_state。
 *
 * <p><b>为什么心跳由中控发起</b>：这套协议里中控是客户端、驱动是服务端。
 * 服务端没法主动推给一个没监听端口的客户端，所以只能由中控轮询。
 *
 * <p><b>探活方式分两级</b>：
 * <ol>
 *   <li><b>TCP 连通性</b>（默认）：连得上就算在线。所有驱动都支持，零成本。</li>
 *   <li><b>方法探测</b>（可选）：配了 {@code device_method.heartbeat=true} 的，
 *       发一次那个方法，收到正常回包才算在线。能发现"端口通但设备卡死"。</li>
 * </ol>
 *
 * <p><b>和 status 的区别</b>：
 * <ul>
 *   <li>{@code online} —— 能不能连上（心跳）</li>
 *   <li>{@code status} —— 在干什么（idle / running / error）</li>
 * </ul>
 * 一台设备可以"在线但空闲"，也可以"在线但正在跑"，还可以"离线但上次状态是 running"
 * （后者说明跑到一半断线了，要人工看）。
 */
public final class HeartbeatService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(HeartbeatService.class);

    private final DbConfig db;
    private final long tenantId;
    private final long intervalMs;
    private final int timeoutMs;
    /** 离线超过多久算告警（默认 30 秒）。 */
    private final long offlineAlertMs;

    private volatile boolean running;
    private Thread worker;

    /** 最近一次探测结果，给接口用（不查库也能拿到）。 */
    private volatile Map<String, Boolean> lastResults = Map.of();
    private volatile long lastRunAt;

    public HeartbeatService(DbConfig db, long tenantId) {
        this(db, tenantId,
                // 现场按设备调：廉价设备 10 秒探一次没问题，
                // 但有些设备被频繁探测会掉线（串口/CAN 尤其）。
                envLong("LABOS_HEARTBEAT_INTERVAL_MS", 10_000L),
                1_500);
    }

    /** 读一个正整数环境变量，非法或未设就用默认值（不让配置错误把服务起崩）。 */
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

    public HeartbeatService(DbConfig db, long tenantId, long intervalMs, int timeoutMs) {
        this.db = db;
        this.tenantId = tenantId;
        this.intervalMs = intervalMs;
        this.timeoutMs = timeoutMs;
        // 离线阈值：默认 3 个探测周期（10s 间隔 → 30s 判离线）。
        // 若写死 30 秒，改探测间隔后阈值不跟着变，会出现
        // "探测 60 秒一次、30 秒判离线" —— 每台设备每次探测前都被判一次离线。
        this.offlineAlertMs = envLong("LABOS_OFFLINE_ALERT_MS", Math.max(intervalMs * 3, 30_000L));
    }

    // ---------------------------------------------------------------- 生命周期

    public void start() {
        if (running) {
            return;
        }
        running = true;
        worker = new Thread(this::loop, "heartbeat");
        worker.setDaemon(true);
        worker.start();
        log.info("[心跳] 已启动，间隔 " + (RuntimeSettings.heartbeatIntervalMs() / 1000)
                + " 秒（页面可改，改完下一个周期生效）");
    }

    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
    }

    private void loop() {
        // 启动后等 3 秒再第一次探测，让服务和驱动先就绪
        sleep(3_000);
        while (running) {
            try {
                int online = probeOnce();
                if (online >= 0) {
                    log.info("[心跳] " + online + " 个驱动在线");
                }

                // P0-4 修复（评审 20261102-03）：paused_fault 的自动恢复
                // 改动前只有实现、**没有任何调用者** —— 设备恢复了流程也不会继续，
                // 表现是"设备好了但实验一直停着，要人手动点继续"。
                // 心跳每轮顺带扫一次：所有设备在线 → 把 paused_fault 的执行放回 running。
                try {
                    int resumed = new com.labos.kernel.workflow.PauseManager(db, tenantId)
                            .autoResumeFaulted();
                    if (resumed > 0) {
                        log.info("[心跳] 设备已恢复，自动继续 " + resumed + " 个执行");
                    }
                } catch (RuntimeException e) {
                    log.error("[心跳] 自动恢复失败：" + e.getMessage());
                }
            } catch (Exception e) {
                log.error("[心跳] 探测失败：" + e.getMessage());
            }
            sleep(RuntimeSettings.heartbeatIntervalMs());
        }
    }

    // ---------------------------------------------------------------- 探测

    /**
     * 探测一轮：按 driver 分组，每个端点连一次。
     *
     * <p>为什么按 driver 而不是按设备：同一个端点下的设备共享一条 TCP 通路，
     * 端点通就是全都通。逐台连会放大到 N 倍连接数，对现场驱动是负担。
     *
     * @return 在线驱动数；-1 表示本轮跳过（比如库不可用）
     */
    public int probeOnce() throws SQLException {
        Map<String, DriverProbe> drivers = loadDrivers();
        if (drivers.isEmpty()) {
            return -1;
        }

        Map<String, Boolean> results = new LinkedHashMap<>();
        int onlineCount = 0;
        long now = System.currentTimeMillis();

        for (Map.Entry<String, DriverProbe> e : drivers.entrySet()) {
            DriverProbe d = e.getValue();
            long t0 = System.nanoTime();
            boolean ok = tcpProbe(d.host, d.port);
            int latency = (int) ((System.nanoTime() - t0) / 1_000_000);

            results.put(e.getKey(), ok);
            if (ok) {
                onlineCount++;
            }
            writeDriverHealth(d.id, ok, latency, ok ? "ok" : "connect failed");
            // 这个驱动名下的所有设备，统一置为同一个在线状态
            writeDeviceOnline(d.id, ok, now);
        }

        lastResults = results;
        lastRunAt = now;

        // 顺手清孤儿锁：current_run_id 指向已结束的 run，但锁还挂着。
        // 不清的话那台设备永久不可用，直到重启。
        try {
            int cleaned = new DeviceLock(db, tenantId).releaseOrphans();
            if (cleaned > 0) {
                log.info("[心跳] 清理 " + cleaned + " 个孤儿设备锁"
                        + "（对应的 run 已结束）");
            }
        } catch (RuntimeException ignore) {
            // 清理失败不影响探测
        }

        // 探测完顺手检查"离线超时"，超阈值的开告警，恢复的关告警
        try {
            checkOfflineAlerts();
        } catch (SQLException e) {
            log.error("[心跳] 离线告警检查失败：" + e.getMessage());
        }
        return onlineCount;
    }

    // ---------------------------------------------------------------- 离线告警

    /**
     * 离线告警：设备离线超过 {@code offlineAlertMs} 就开一条，恢复后自动关闭。
     *
     * <p>用 {@code alert_state} 的 {@code fingerprint} 做幂等键：
     * 同一台设备只会有**一条**未关闭的告警，不会每 10 秒推一次。
     *
     * <p><b>为什么用 last_heartbeat 而不是另记时间</b>：
     * 这个字段只在探测成功时更新，离线后它停在最后一次成功的时间点，
     * 所以 {@code now() - last_heartbeat} 天然就是"离线了多久"。
     */
    private void checkOfflineAlerts() throws SQLException {
        // ① 开告警：离线超阈值且没有未关闭的告警
        // ⚠️ 这里改动前是 INSERT ... WHERE NOT EXISTS(open 告警)。看起来对，其实会撞主键：
        // alert_state 的唯一键是 (tenant_id, fingerprint)，一个设备只留一行；
        // 上一轮离线关掉后那行还在（status=closed），下次再离线 NOT EXISTS 不成立 ⇒
        // 走 INSERT ⇒ duplicate key，**每次心跳刷一条 ERROR**。
        // 正确做法是 upsert：有行就重新打开，没有才插。
        String openSql = """
                INSERT INTO alert_state (tenant_id, fingerprint, status, updated_at)
                SELECT ?, 'DEVICE-OFFLINE|' || r.id, 'open', now()
                FROM device_state ds
                JOIN resource_node r ON r.id = ds.resource_id
                WHERE ds.tenant_id = ?
                  AND ds.online = false
                  AND ds.last_heartbeat IS NOT NULL
                  AND ds.last_heartbeat < now() - (? || ' milliseconds')::interval
                ON CONFLICT (tenant_id, fingerprint)
                DO UPDATE SET status = 'open', closed_at = NULL, updated_at = now()
                """;
        // ② 关告警：已恢复在线，且有未关闭的告警
        String closeSql = """
                UPDATE alert_state a
                SET status = 'closed', closed_at = now(), updated_at = now()
                WHERE a.tenant_id = ?
                  AND a.status = 'open'
                  AND a.fingerprint LIKE 'DEVICE-OFFLINE|%'
                  AND EXISTS (
                        SELECT 1 FROM device_state ds
                        JOIN resource_node r ON r.id = ds.resource_id
                        WHERE ds.tenant_id = a.tenant_id
                          AND a.fingerprint = 'DEVICE-OFFLINE|' || r.id
                          AND ds.online = true)
                """;
        try (Connection c = db.open()) {
            try (PreparedStatement ps = c.prepareStatement(openSql)) {
                ps.setLong(1, tenantId);
                ps.setLong(2, tenantId);
                ps.setString(3, String.valueOf(RuntimeSettings.offlineAlertMs()));
                int n = ps.executeUpdate();
                if (n > 0) {
                    log.info("[心跳] 新开 " + n + " 条离线告警（离线超过 "
                            + (RuntimeSettings.offlineAlertMs() / 1000) + " 秒）");
                }
            }
            try (PreparedStatement ps = c.prepareStatement(closeSql)) {
                ps.setLong(1, tenantId);
                int n = ps.executeUpdate();
                if (n > 0) {
                    log.info("[心跳] 关闭 " + n + " 条离线告警（设备已恢复）");
                }
            }
        }
    }

    /** TCP 连一下就算通。不做协议层握手——心跳要足够轻。 */
    private boolean tcpProbe(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 读写库

    private Map<String, DriverProbe> loadDrivers() throws SQLException {
        Map<String, DriverProbe> out = new LinkedHashMap<>();
        String sql = """
                SELECT id, code, endpoint FROM driver
                WHERE tenant_id = ? AND status = 1
                  AND endpoint IS NOT NULL AND endpoint <> ''
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String ep = rs.getString("endpoint");
                    String hp = ep.startsWith("tcp://") ? ep.substring(6) : ep;
                    int idx = hp.lastIndexOf(':');
                    if (idx <= 0) {
                        continue;
                    }
                    out.put(rs.getString("code"), new DriverProbe(
                            rs.getLong("id"),
                            hp.substring(0, idx),
                            Integer.parseInt(hp.substring(idx + 1))));
                }
            }
        }
        return out;
    }

    private void writeDriverHealth(long driverId, boolean healthy, int latency, String msg) {
        String sql = """
                INSERT INTO driver_health (driver_id, checked_at, healthy, latency_ms, message)
                VALUES (?, now(), ?, ?, ?)
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, driverId);
            ps.setBoolean(2, healthy);
            ps.setInt(3, latency);
            ps.setString(4, msg);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("[心跳] 写 driver_health 失败：" + e.getMessage());
        }
    }

    private void writeDeviceOnline(long driverId, boolean online, long now) {
        // 用 UPSERT 而不是 UPDATE：设备可能还没有 device_state 记录
        // （从没执行过），纯 UPDATE 会静默影响 0 行，表现为"模拟器在线但显示离线"。
        String sql = """
                INSERT INTO device_state
                  (tenant_id, resource_id, status, online, last_heartbeat, updated_at)
                SELECT ?, di.resource_id, 'idle', ?,
                       CASE WHEN ? THEN to_timestamp(? / 1000.0) ELSE NULL END, now()
                FROM device_instance di
                WHERE di.driver_id = ? AND di.deleted_at IS NULL
                ON CONFLICT (tenant_id, resource_id) DO UPDATE
                SET online = EXCLUDED.online,
                    last_heartbeat = CASE WHEN EXCLUDED.online
                                          THEN EXCLUDED.last_heartbeat
                                          ELSE device_state.last_heartbeat END,
                    updated_at = now()
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setBoolean(2, online);
            ps.setBoolean(3, online);
            ps.setLong(4, now);
            ps.setLong(5, driverId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("[心跳] 写 device_state.online 失败：" + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 状态上报

    /**
     * 标记设备"正在执行某方法"。
     *
     * <p>驱动收到 {@code method_status=1} 时调用。前端看到的就是"运行中"。
     */
    public void markRunning(long deviceInstanceId, String method, Long runId) {
        try {
            com.labos.kernel.persistence.Mappers.get(
                    com.labos.kernel.persistence.DeviceStateMapper.class)
                    .markRunning(tenantId, deviceInstanceId, method, runId);
        } catch (RuntimeException e) {
            log.error("[心跳] 标记运行中失败：{}", e.getMessage());
        }
    }

    /** 标记设备空闲（执行完成或失败后）。 */
    public void markIdle(long deviceInstanceId, String lastError) {
        try {
            com.labos.kernel.persistence.Mappers.get(
                    com.labos.kernel.persistence.DeviceStateMapper.class)
                    .markIdle(tenantId, deviceInstanceId,
                            lastError == null ? "idle" : "error", lastError);
        } catch (RuntimeException e) {
            log.error("[心跳] 标记空闲失败：{}", e.getMessage());
        }
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private void updateDeviceState(long deviceInstanceId, String sql, Binder binder) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("[心跳] 更新设备状态失败：" + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 查询

    /** 最近一轮探测结果（driverCode → 在线）。 */
    public Map<String, Boolean> lastResults() {
        return lastResults;
    }

    public long lastRunAt() {
        return lastRunAt;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record DriverProbe(long id, String host, int port) {
    }
}
