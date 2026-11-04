package com.labos.kernel.driver;

import com.labos.kernel.engine.StepContext;
import com.labos.kernel.engine.StepExecutor;
import com.labos.kernel.engine.StepFailure;
import com.labos.kernel.engine.StepSink;
import com.labos.kernel.recovery.RecoveryScope;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 把状态机接到真实驱动契约上，并支持**一个节点展开成多步**。
 *
 * <p>执行模型：
 * <pre>
 *   节点 READ_PLATE（7 步）
 *     step 1 OpenDoor   → 成功后 sink 记一笔
 *     step 2 Transfer   → ...
 *     ...
 *     step 3 失败 → 抛 StepFailure(stepSeq=3)，整个节点失败
 * </pre>
 *
 * <p><b>换设备语义</b>：状态机 REASSIGN 时会重新调用 {@link #execute}，
 * 这里从 step 1 重新开始——因为新设备的物理状态未知（可能已经开过门了）。
 *
 * <p><b>幂等键</b>：每步生成独立 commandId；状态机的 RETRY 是"业务重试"（要真执行），
 * 幂等键是给传输层重发用的，两者不混用。
 */
public final class DriverStepExecutor implements StepExecutor {

    private final DriverRegistry registry;
    private final long timeoutMs;
    private final String traceId;
    private final StepSink sink;

    /**
     * 搬运资源保护（门 + 通道）。
     *
     * <p>为 null 时走非保护路径。开关：{@code LABOS_TRANSFER_PLAN=1}。
     * 默认关 —— 改动搬运核心逻辑风险大，先让现场验证现有流程不受影响。
     */
    private final com.labos.kernel.persistence.DbConfig db;
    private final long tenantId;
    private final long runId;

    /**
     * 错误码 → 恢复作用域（{@code error_code.scope}）。
     *
     * <p><b>为什么不能再在抛出点硬编码</b>：库里 {@code EC-05-1006} 配的是
     * command 级 retry×5、{@code EC-06-0001} 配的是 run 级 abort，
     * 而代码里所有失败都抛 {@code NODE} —— 这些策略一条都匹配不上，
     * 全部落到通配 node/manual（一次网络抖动就转人工）。
     *
     * <p>作用域是**错误码的属性**，不是"抛的时候随手写"的东西。这里从库里读一次，
     * 读不到（新码还没登记）再按前缀兜底。
     */
    private volatile Map<String, com.labos.kernel.recovery.RecoveryScope> scopes;

    /**
     * 本节点第几次被 {@link #execute} 调用（从 1 开始）。
     *
     * <p>用来构造**稳定的命令幂等键**：同一次执行内的重试复用同一个 id，
     * 循环里第二次执行则换新 id。
     */
    private final java.util.concurrent.atomic.AtomicInteger execSeq =
            new java.util.concurrent.atomic.AtomicInteger();

    /** "nodeId:stepSeq" -> 设备原始回包 */
    private final Map<String, String> rawByStep = new LinkedHashMap<>();
    /** nodeId -> 最后一步的原始回包（兼容调用） */
    private final Map<Long, String> lastRawByNode = new LinkedHashMap<>();

    public DriverStepExecutor(DriverRegistry registry) {
        this(registry, 30_000L, "trace", StepSink.NOOP);
    }

    /** 带资源保护的构造（Transfer 会先抢门和通道）。 */
    public DriverStepExecutor(DriverRegistry registry,
                              com.labos.kernel.persistence.DbConfig db,
                              long tenantId, long runId) {
        this(registry, 30_000L, "trace", StepSink.NOOP, db, tenantId, runId);
    }

    /**
     * 生产装配用：**同时**要落库 sink 和资源保护 db。
     *
     * <p><b>P1-1 修复（评审 20261102-03）</b>：改动前没有这个构造，
     * {@code WorkflowService} 只能选 {@code (registry, sink)} —— 那条路径
     * {@code db = null}，于是 {@code executeWithPlan} 开头判
     * {@code protect = db != null && ...} 恒为 false，
     * <b>四层资源保护（门/通道/位置/机械臂）在生产路径从未生效过</b>，
     * 每次都直接 {@code driver.execute}。
     */
    public DriverStepExecutor(DriverRegistry registry, StepSink sink,
                              com.labos.kernel.persistence.DbConfig db,
                              long tenantId, long runId) {
        this(registry, 30_000L, "trace", sink, db, tenantId, runId);
    }

    private DriverStepExecutor(DriverRegistry registry, long timeoutMs, String traceId,
                               StepSink sink, com.labos.kernel.persistence.DbConfig db,
                               long tenantId, long runId) {
        this.registry = registry;
        this.timeoutMs = timeoutMs;
        this.traceId = traceId;
        this.sink = sink;
        this.db = db;
        this.tenantId = tenantId;
        this.runId = runId;
    }

    public DriverStepExecutor(DriverRegistry registry, StepSink sink) {
        this(registry, 30_000L, "trace", sink);
    }

    public DriverStepExecutor(DriverRegistry registry, long timeoutMs,
                              String traceId, StepSink sink) {
        this(registry, timeoutMs, traceId, sink, null, 1L, 0L);
        if (registry == null) {
            throw new IllegalArgumentException("registry 不能为空");
        }
    }

    @Override
    public void execute(StepContext ctx) throws StepFailure {
        long deviceId = ctx.resourceId();
        int exec = execSeq.incrementAndGet();

        // 逻辑节点（Delay / SetVariable / Increment ...）由内核自身执行，不调设备。
        // 它们用伪资源 0 占位，若走到驱动注册表会抛 EC-06-0001 并被误判为故障。
        if (deviceId == 0L || "LOGIC".equalsIgnoreCase(ctx.capabilityCode())) {
            List<String> steps = ctx.effectiveSteps();
            for (int i = 0; i < steps.size(); i++) {
                sink.onStepStart(ctx.nodeId(), i + 1, steps.get(i), 0L, ctx.attempt());
                sink.onStepDone(ctx.nodeId(), i + 1, steps.get(i), true,
                        null, null, 0L, ctx.attempt());
            }
            return;
        }

        DeviceDriver driver = registry.driverFor(deviceId).orElseThrow(() ->
                // EC-06-0001 库里配的是 run 级 abort（驱动没加载 = 部署问题，
                // 重试和换设备都没意义，整单停下比一台台试更快暴露问题）
                new StepFailure("EC-06-0001", scopeOf("EC-06-0001"),
                        "设备 " + deviceId + " 没有注册驱动"));

        List<String> steps = ctx.effectiveSteps();
        for (int i = 0; i < steps.size(); i++) {
            String method = steps.get(i);
            int stepSeq = i + 1;

            sink.onStepStart(ctx.nodeId(), stepSeq, method, deviceId, ctx.attempt());
            long t0 = System.currentTimeMillis();

            DriverResult result;
            try {
                result = executeWithPlan(driver, ctx, deviceId, method, stepSeq, exec);
            } catch (RuntimeException e) {
                long ms = System.currentTimeMillis() - t0;
                sink.onStepDone(ctx.nodeId(), stepSeq, method, false,
                        "EC-99-0001", null, ms, ctx.attempt());
                throw new StepFailure("EC-99-0001", scopeOf("EC-99-0001"),
                        "驱动异常: " + e.getMessage(), stepSeq, method);
            }

            long ms = System.currentTimeMillis() - t0;

            if (result.deviceRawResponse() != null) {
                rawByStep.put(ctx.nodeId() + ":" + stepSeq, result.deviceRawResponse());
                lastRawByNode.put(ctx.nodeId(), result.deviceRawResponse());
            }

            sink.onStepDone(ctx.nodeId(), stepSeq, method, result.ok(),
                    result.errorCode(), result.deviceRawResponse(), ms, ctx.attempt());

            if (!result.ok()) {
                // 作用域不能一律 NODE：
                // EC-05-xxx（通讯失败/超时）和 EC-99-xxx（发送失败）是**这条命令**没成，
                // 设备本身没问题，退避重发就能救回来。库里给它们配的是
                // scope=command 的 retry 策略（1006 重试 5 次、1007 重试 3 次），
                // 一律按 NODE 抛的话这些策略**永远匹配不上**，一次网络抖动就直接转人工。
                throw new StepFailure(result.errorCode(), scopeOf(result.errorCode()),
                        String.format("%s | device=%d | raw=%s",
                                result.message(), deviceId, result.deviceRawResponse()),
                        stepSeq, method);
            }
        }
    }

    /** 从库加载一次错误码作用域（懒加载，进程内缓存）。 */
    private Map<String, com.labos.kernel.recovery.RecoveryScope> loadScopes() {
        Map<String, com.labos.kernel.recovery.RecoveryScope> out = new java.util.HashMap<>();
        if (db == null) {
            return out;
        }
        try (java.sql.Connection c = db.open();
             java.sql.PreparedStatement ps = c.prepareStatement("""
                     SELECT code, scope FROM error_code
                      WHERE tenant_id = ? AND scope IS NOT NULL AND deleted_at IS NULL
                     """)) {
            ps.setLong(1, tenantId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String sc = rs.getString(2);
                    if (sc == null || sc.isBlank()) {
                        continue;
                    }
                    try {
                        out.put(rs.getString(1),
                                com.labos.kernel.recovery.RecoveryScope.valueOf(
                                        sc.trim().toUpperCase()));
                    } catch (IllegalArgumentException ignore) {
                        // 库里写了非法值就当没配，走前缀兜底
                    }
                }
            }
        } catch (java.sql.SQLException e) {
            System.err.println("[WARN] 加载错误码作用域失败，按前缀兜底: " + e.getMessage());
        }
        return out;
    }

    /**
     * 错误码的恢复作用域。**唯一出口** —— 别再在抛出点写死。
     */
    private com.labos.kernel.recovery.RecoveryScope scopeOf(String code) {
        Map<String, com.labos.kernel.recovery.RecoveryScope> m = scopes;
        if (m == null) {
            synchronized (this) {
                if (scopes == null) {
                    scopes = loadScopes();
                }
                m = scopes;
            }
        }
        com.labos.kernel.recovery.RecoveryScope hit = m == null ? null : m.get(code);
        if (hit != null) {
            return hit;
        }
        // 前缀兜底：库里没有这个码时的最后防线
        if (code == null || code.isBlank()) {
            return com.labos.kernel.recovery.RecoveryScope.NODE;
        }
        if (code.startsWith("EC-05-10") || code.startsWith("EC-99-")) {
            return com.labos.kernel.recovery.RecoveryScope.COMMAND;   // 通讯/发不出去
        }
        if (code.startsWith("EC-06-")) {
            return com.labos.kernel.recovery.RecoveryScope.DEVICE;    // 驱动/部署问题
        }
        return com.labos.kernel.recovery.RecoveryScope.NODE;
    }

    /** 业务参数 + 追踪字段。业务参数在前，追踪字段不会覆盖它们。 */
    /**
     * 执行一步。Transfer 且开了保护时，先抢门和通道。
     *
     * <p><b>顺序</b>：源门 → 源通道 → 目标门 → 目标通道。
     * 门排最前——门开了没抢到通道可以关回来，反过来板已在爪子上。
     *
     * <p><b>失败时</b>：{@link TransferPlan} 内部已回滚，这里只把异常转成 StepFailure。
     */
    private DriverResult executeWithPlan(DeviceDriver driver, StepContext ctx,
                                         long deviceId, String method,
                                         int stepSeq, int exec) throws StepFailure {
        boolean protect = db != null
                && System.getenv("LABOS_TRANSFER_PLAN") != null
                && method != null && method.startsWith("Transfer");
        if (!protect) {
            return driver.execute(buildCommand(ctx, deviceId, method, stepSeq, exec));
        }

        Long srcRes = resourceOfParam(ctx, "source");
        Long dstRes = resourceOfParam(ctx, "target");
        if (srcRes == null || dstRes == null) {
            return driver.execute(buildCommand(ctx, deviceId, method, stepSeq, exec));
        }

        TransferPlan plan = new TransferPlan(db, tenantId, runId);
        // 按设备找 driver：开源设备的门用源设备的驱动，开目标门用目标的。
        TransferPlan.AccessOpenerFactory factory = resNodeId -> m -> {
            Long instId = instanceOfResource(resNodeId);
            if (instId == null) {
                return DriverResult.failed("0", "EC-06-0001",
                        "设备没有实例：" + resNodeId, null, 0);
            }
            java.util.Optional<DeviceDriver> d = registry.driverFor(instId);
            if (d.isEmpty()) {
                return DriverResult.failed("0", "EC-06-0001",
                        "设备没有可用驱动：" + resNodeId, null, 0);
            }
            return d.get().execute(buildCommand(ctx, instId, m, stepSeq, exec));
        };
        try {
            plan.acquireAll(srcRes, null, dstRes, null, null, null, factory);
        } catch (TransferPlan.AcquireException e) {
            throw new StepFailure("EC-RESOURCE-BUSY", scopeOf("EC-RESOURCE-BUSY"),
                    e.getMessage(), 0, method);
        } catch (java.sql.SQLException e) {
            throw new StepFailure("EC-99-0002", scopeOf("EC-99-0002"),
                    "申请资源时数据库错误：" + e.getMessage(), 0, method);
        }

        try {
            DriverResult r = driver.execute(buildCommand(ctx, deviceId, method, stepSeq, exec));
            // P1-10：动作失败 → 板可能既不在源位也不在目标位，必须标 UNKNOWN。
            // 不标的话系统会以为板还在源位，下一个节点去取就是撞机。
            if (r.errorCode() != null) {
                markPlatePositionUnknown(ctx, srcRes, dstRes);
            }
            return r;
        } catch (RuntimeException e) {
            // 抛异常同理：板的位置已不可信
            markPlatePositionUnknown(ctx, srcRes, dstRes);
            throw e;
        } finally {
            plan.closeAccesses(srcRes, dstRes, factory);
            plan.close();
        }
    }

    /**
     * 搬运失败后把板标成位置未知。
     *
     * <p><b>P1-10 修复（评审 20261102-03）</b>：{@code PauseManager.markPlateUnknown}
     * 写好了但<b>没有任何调用者</b> —— M4 的板位三态（KNOWN/UNKNOWN/LOST）
     * 从来没有真正生效过：搬运失败时系统仍然认为板在源位，
     * 下一个节点照着去取，<b>机械臂伸进空位置</b>。
     *
     * <p>能到这里说明 {@code acquireAll} 已成功（门/通道/位置都拿到了），
     * 也就是"可能已经抓了板"——保守起见按 UNKNOWN 处理。
     *
     * <p>板号取 {@code params.plate}；取不到就只记日志不标（没有板号无法封锁）。
     */
    private void markPlatePositionUnknown(StepContext ctx, long srcRes, long dstRes) {
        if (db == null || runId <= 0) {
            return;
        }
        Object raw = ctx.params() == null ? null : ctx.params().get("plate");
        String barcode = raw == null ? null : String.valueOf(raw);
        if (barcode == null || barcode.isBlank()) {
            System.err.println("[搬运] 失败但拿不到板号（params.plate），无法标记位置未知");
            return;
        }
        try {
            int blocked = new com.labos.kernel.workflow.PauseManager(db, tenantId)
                    .markPlateUnknown(barcode, srcRes, dstRes, null, runId);
            System.err.println("[搬运] 板 " + barcode + " 位置未知，已封锁 "
                    + blocked + " 个可能位置（需人工盘点）");
        } catch (java.sql.SQLException e) {
            System.err.println("[搬运] 标记板位置未知失败：" + e.getMessage());
        }
    }

    /** resource_node.id → device_instance.id（两套 ID 的转换）。 */
    private Long instanceOfResource(long resourceId) {
        try (java.sql.Connection c = db.open();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "SELECT id FROM device_instance WHERE resource_id = ?"
                             + " AND deleted_at IS NULL ORDER BY id LIMIT 1")) {
            ps.setLong(1, resourceId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        } catch (java.sql.SQLException e) {
            return null;
        }
    }

    /** 把 params 里的 source/target（设备编号）转成 resource_node.id。 */
    private Long resourceOfParam(StepContext ctx, String key) {
        Object v = ctx.params() == null ? null : ctx.params().get(key);
        if (v == null) {
            return null;
        }
        String code = String.valueOf(v);
        try (java.sql.Connection c = db.open();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "SELECT id FROM resource_node WHERE tenant_id = ? AND code = ?")) {
            ps.setLong(1, tenantId);
            ps.setString(2, code);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        } catch (java.sql.SQLException e) {
            return null;
        }
    }

    private static Map<String, Object> mergeParams(StepContext ctx) {
        Map<String, Object> m = new java.util.LinkedHashMap<>(ctx.params());
        m.putIfAbsent("nodeId", ctx.nodeId());
        m.putIfAbsent("attempt", ctx.attempt());
        return m;
    }

    /**
     * 构造下发给设备的命令。
     *
     * <p><b>为什么命令 id 不是随机 UUID</b>（2026-11-02 修）：
     *
     * <p>改动前每次重试都生成新 UUID。而设备侧的重试策略是
     * {@code EC-05-1007(超时) → retry ×3}、{@code EC-05-1006(通讯失败) → retry ×5}。
     * 一旦设备<b>已经执行完、只是响应丢了</b>，中控超时重试时带的是<b>新 id</b>，
     * 设备会当成一条<b>全新命令再执行一次</b>——
     * 对 {@code Transfer} 就是<b>机械臂搬两次板</b>。这是最危险的一类重复执行。
     *
     * <p>现在用**稳定键**：
     * <pre>r{runId}-n{nodeId}-e{第几次execute}-s{步骤号}-d{设备}</pre>
     * <ul>
     *   <li>同一次执行内的重试 → id 不变 → 设备可据此去重（幂等）</li>
     *   <li>换了设备（REASSIGN）→ d 段变化 → 新 id（确实是另一个物理动作）</li>
     *   <li>循环里同一节点第二次执行 → e 段变化 → 新 id（不是重试，是真要再做一遍）</li>
     * </ul>
     *
     * <p><b>设备侧配合</b>：收到重复 id 时直接返回上次结果、不重复执行
     * （模拟器 {@code DeviceCore} 已实现；真实设备若不支持，
     * 应把动作类的超时策略配成 {@code manual}，宁可转人工也不要重复搬板）。
     */
    private DriverCommand buildCommand(StepContext ctx, long deviceId, String method,
                                       int stepSeq, int exec) {
        String id = "r" + runId
                + "-n" + ctx.nodeId()
                + "-e" + exec
                + "-s" + stepSeq
                + "-d" + deviceId;
        return new DriverCommand(
                id,
                deviceId,
                method,
                mergeParams(ctx),
                timeoutMs,
                traceId);
    }

    /** 某节点最后一步的设备原始回包（兼容调用）。 */
    public Optional<String> rawResponse(long nodeId) {
        return Optional.ofNullable(lastRawByNode.get(nodeId));
    }

    /** 某节点某一步的设备原始回包。 */
    public Optional<String> rawResponse(long nodeId, int stepSeq) {
        return Optional.ofNullable(rawByStep.get(nodeId + ":" + stepSeq));
    }

    public Map<Long, String> allRawResponses() {
        return Map.copyOf(lastRawByNode);
    }
}
