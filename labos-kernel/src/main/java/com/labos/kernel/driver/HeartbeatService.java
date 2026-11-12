package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备心跳：定期判定在线/离线，把结果写进 device_state。
 *
 * <p><b>在线状态的唯一权威是 Redis</b>（2026-11-11 架构调整）。
 *
 * <p>现场设备由独立的 <b>tcp client</b>（sidecar）持有**到设备的长连接**，
 * 并把「这条长连接现在活着吗」写进 Redis；中控只<b>读</b>这个状态。
 * 中控自己**不开短连接去探设备** —— 原因有三：
 * <ol>
 *   <li>短连接探测会让设备侧看到「接入→断开」反复出现，那不是"在线"，
 *       反而给现场制造噪音；</li>
 *   <li>长连接本来就是 tcp client 的职责，中控再开一条等于把同一件事做两遍，
 *       两个来源还可能互相矛盾；</li>
 *   <li>Redis 里没有这条记录（或读不到）⇒ 一律判<b>离线</b>，
 *       不做直连回退 —— 在线状态只有一个来源，才谈得上"权威"。</li>
 * </ol>
 *
 * <p><b>设备口径（三端统一：模拟端 / 中控 / tcp client）</b>：
 * 一台设备由 <b>设备ID</b>（{@code device_instance.id}）+ <b>设备IP</b>
 * （{@code device_instance.ip}）+ 端口 三元组唯一确定。
 * Redis key = {@code labos:link:<设备ID>:<设备IP>:<端口>}，每台设备一条。
 * ⇒ 心跳判定粒度也必须是**设备级**：一个 driver 名下挂多台设备时，
 * 不能把一台设备的在线状态套到同 driver 的其它设备上。
 *
 * <p><b>和 status 的区别</b>：
 * <ul>
 *   <li>{@code online} —— 长连接在不在（心跳）</li>
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

    /**
     * Redis 客户端（懒建）。
     *
     * <p>用途：**设备在线状态的权威来源** —— 现场设备由独立的
     * **tcp client**（sidecar）保持**长连接**，把「连上了 / 断了」写进 Redis；
     * 中控只从 Redis 读状态，**不自己开短连接去探设备**。
     */
    private volatile com.labos.kernel.redis.RedisClient redis;

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
                    log.info("[心跳] " + online + " 台设备在线");
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
     * 探测一轮：**逐设备**读 Redis 里的长连接状态。
     *
     * <p>为什么逐设备：Redis key 里带设备ID ⇒ 每台设备一条 ⇒ 判定粒度必须一致。
     * 一个 driver 名下挂多台设备时，端点通不代表每台设备的长连接都活着。
     *
     * @return 在线设备数；-1 表示本轮跳过（比如库不可用）
     */
    public int probeOnce() throws SQLException {
        List<Map<String, Object>> targets = loadLinkTargets();
        if (targets.isEmpty()) {
            return -1;
        }

        Map<String, Boolean> results = new LinkedHashMap<>();      // driverCode → 通路是否可用
        Map<Long, DriverStat> driverStats = new LinkedHashMap<>(); // driverId → 聚合
        int onlineCount = 0;
        long now = System.currentTimeMillis();

        for (Map<String, Object> t : targets) {
            long deviceId = num(t.get("deviceId"));
            long resourceId = num(t.get("resourceId"));
            long driverId = num(t.get("driverId"));
            String driverCode = str(t.get("driverCode"));
            String deviceCode = str(t.get("deviceCode"));
            // 设备IP 优先取 device_instance.ip（设备清单表里的那一列）；
            // 万一没填，退回驱动 endpoint 的 host —— 两者本应同源。
            String host = resolveHost(str(t.get("ip")), str(t.get("endpoint")));
            int port = portOf(str(t.get("endpoint")));

            long t0 = System.nanoTime();
            boolean ok = linkStateFromRedis(deviceId, host, port);
            int latency = (int) ((System.nanoTime() - t0) / 1_000_000);

            if (ok) {
                onlineCount++;
            }
            writeDeviceOnline(resourceId, ok, now);

            DriverStat st = driverStats.computeIfAbsent(driverId,
                    k -> new DriverStat(driverId, driverCode));
            st.total++;
            if (ok) {
                st.online++;
            }
            st.latency = Math.max(st.latency, latency);
        }

        // driver_health 仍按 driver 写一行：driver 是"到设备的通路"，
        // 名下有任一设备在线 ⇒ 通路可用（延续旧口径"端点通就是全都通"）。
        for (DriverStat st : driverStats.values()) {
            boolean healthy = st.online > 0;
            writeDriverHealth(st.driverId, healthy, st.latency,
                    st.online + "/" + st.total + " 在线");
            results.put(st.driverCode, healthy);
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
        // ①② 语句本体在 mapper/HeartbeatMapper.xml（upsert 幂等键语义注释在那里）
        int opened = mapper().upsertOfflineAlerts(tenantId,
                String.valueOf(RuntimeSettings.offlineAlertMs()));
        if (opened > 0) {
            log.info("[心跳] 新开 " + opened + " 条离线告警（离线超过 "
                    + (RuntimeSettings.offlineAlertMs() / 1000) + " 秒）");
        }
        int closed = mapper().closeRecoveredAlerts(tenantId);
        if (closed > 0) {
            log.info("[心跳] 关闭 " + closed + " 条离线告警（设备已恢复）");
        }
    }

    // ---------------------------------------------------------------- Redis

    /**
     * 从 Redis 读 tcp client 报上来的**长连接状态**。
     *
     * <p>**唯一权威**：Redis 里没有这条记录、或值不认识、或 Redis 读不到
     * ⇒ 一律判**离线**（不做直连回退）—— 在线状态只有一个来源，
     * 就是 tcp client 维护的那条长连接。
     *
     * @return {@code true}=在线 / {@code false}=离线
     */
    private boolean linkStateFromRedis(long deviceId, String host, int port) {
        try {
            com.labos.kernel.redis.RedisClient rc = redisClient();
            if (rc == null) {
                return false;
            }
            String v = rc.get(linkKey(deviceId, host, port));
            if (v == null || v.isBlank()) {
                return false;   // 没有这条记录 ⇒ 没人报上来 ⇒ 离线
            }
            String t = v.trim();
            return "online".equalsIgnoreCase(t) || "1".equals(t)
                    || "true".equalsIgnoreCase(t) || "up".equalsIgnoreCase(t);
        } catch (Exception e) {
            return false;   // Redis 读不到 ⇒ 离线（不猜）
        }
    }

    /**
     * Redis 里放长连接状态的 key（sidecar / tcp client 与内核共用这一约定）。
     *
     * <p>格式：{@code labos:link:<设备ID>:<设备IP>:<端口>}。
     * 带设备ID 是为了让判定粒度到设备级（一个 driver 下挂多台设备时互不串台）。
     */
    public static String linkKey(long deviceId, String host, int port) {
        return "labos:link:" + deviceId + ":" + host + ":" + port;
    }

    /** 懒建 Redis 客户端；失败则置空（下次心跳再试）。 */
    private com.labos.kernel.redis.RedisClient redisClient() {
        com.labos.kernel.redis.RedisClient rc = redis;
        if (rc != null) {
            return rc;
        }
        synchronized (this) {
            if (redis == null) {
                try {
                    redis = com.labos.kernel.redis.RedisClient.fromEnv();
                    log.info("[心跳] 已接入 Redis：设备在线状态**完全以 Redis 为准**"
                            + "（key = labos:link:<设备ID>:<设备IP>:<端口>，由 tcp client 写）");
                } catch (RuntimeException e) {
                    // 必须打完整堆栈：Lettuce/Netty 的初始化失败只留一行 message
                    // （如 "failed to create a child event loop"）时，真正原因在 cause 里，
                    // 只打 getMessage() 会让这类故障无法定位（2026-11-12 实测踩到）。
                    log.warn("[心跳] Redis 不可用，设备一律判离线", e);
                    redis = null;
                }
            }
            return redis;
        }
    }

    // ---------------------------------------------------------------- 读写库

    /** 在线判定的目标：逐设备（设备ID / 设备IP / 端口 / 所属驱动）。 */
    private List<Map<String, Object>> loadLinkTargets() throws SQLException {
        return mapper().selectLinkTargets(tenantId);
    }

    private void writeDriverHealth(long driverId, boolean healthy, int latency, String msg) {
        try {
            mapper().insertDriverHealth(driverId, healthy, latency, msg);
        } catch (RuntimeException e) {
            // mapper 不声明 SQLException，SQL 异常已被 MyBatis 包装
            log.error("[心跳] 写 driver_health 失败：" + e.getMessage());
        }
    }

    private void writeDeviceOnline(long resourceId, boolean online, long now) {
        // UPSERT 语义（不建行的设备先插 idle）见 mapper/HeartbeatMapper.xml 注释
        try {
            mapper().upsertDeviceOnline(tenantId, resourceId, online, now);
        } catch (RuntimeException e) {
            log.error("[心跳] 写 device_state.online 失败：" + e.getMessage());
        }
    }

    private com.labos.kernel.persistence.HeartbeatMapper mapper() {
        return com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.HeartbeatMapper.class);
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

    // ---------------------------------------------------------------- 查询

    /** 最近一轮探测结果（driverCode → 通路是否可用）。 */
    public Map<String, Boolean> lastResults() {
        return lastResults;
    }

    public long lastRunAt() {
        return lastRunAt;
    }

    // ---------------------------------------------------------------- 小工具

    /** 设备IP：优先用清单里的 {@code device_instance.ip}，没填就退回 endpoint 的 host。 */
    static String resolveHost(String ip, String endpoint) {
        if (ip != null && !ip.isBlank()) {
            return ip.trim();
        }
        String hp = endpoint == null ? "" : endpoint.trim();
        if (hp.startsWith("tcp://")) {
            hp = hp.substring(6);
        }
        int idx = hp.lastIndexOf(':');
        return idx > 0 ? hp.substring(0, idx) : hp;
    }

    /** 从 {@code tcp://host:port} 里取端口；取不到返回 0（调用方据此跳过）。 */
    static int portOf(String endpoint) {
        if (endpoint == null) {
            return 0;
        }
        String hp = endpoint.trim();
        if (hp.startsWith("tcp://")) {
            hp = hp.substring(6);
        }
        int idx = hp.lastIndexOf(':');
        if (idx <= 0) {
            return 0;
        }
        try {
            return Integer.parseInt(hp.substring(idx + 1).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 一个 driver 名下设备的在线聚合（写 driver_health 用）。 */
    private static final class DriverStat {
        final long driverId;
        final String driverCode;
        int total;
        int online;
        int latency;

        DriverStat(long driverId, String driverCode) {
            this.driverId = driverId;
            this.driverCode = driverCode;
        }
    }
}
