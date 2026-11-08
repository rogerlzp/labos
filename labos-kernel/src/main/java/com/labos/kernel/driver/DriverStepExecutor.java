package com.labos.kernel.driver;

import com.labos.kernel.engine.StepContext;
import com.labos.kernel.workflow.RuntimeHold;
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
     * 本次执行解析出来的超时（毫秒）。
     *
     * <p>key = {@code resourceId|capabilityCode|method}。
     * 为什么要缓存：一次执行里同一个设备的同一个方法可能被多步复用，
     * 每步都查一次库是白白的三条 SQL；而且**同一次执行里超时必须一致** ——
     * 中途变了会让排障时对不上账。
     */
    private final Map<String, Long> timeoutCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** 解析出来的超时来源（与 {@link #timeoutCache} 同 key），只用于日志。 */
    private final Map<String, String> timeoutSource = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 「可中断方法」判定缓存：key = {@code deviceInstanceId|method}。
     *
     * <p>默认全是 false（{@code device_method.interruptible} 默认 false），
     * 所以**没有现场清单之前，走的还是老路径（阻塞等待），行为一字不变**。
     */
    private static final Map<String, Boolean> INTERRUPTIBLE_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 设备方法的「可中断」标志被产品写入口改了之后，清掉缓存让下一次执行立刻生效。
     *
     * <p>缓存原来是实例字段、无失效入口：API 改库后要等进程重启才生效。静态 cache +
     * 显式失效后，设备库配置改完下一次下发就按新值走。
     */
    public static void invalidateInterruptible(long deviceId, String method) {
        if (method != null) {
            INTERRUPTIBLE_CACHE.remove(deviceId + "|" + method);
        }
    }

    /** 轮询「该不该停」的间隔（毫秒）。 */
    private static final long INTERRUPT_POLL_MS = 200L;

    /**
     * 可中断等待用的线程池。
     *
     * <p>只有**声明了可中断**的方法才会用到它；其余方法仍是调用线程直接阻塞等待
     * （和改动前一样，不引入线程切换）。
     */
    private static final java.util.concurrent.ExecutorService INTERRUPT_POOL =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "labos-interrupt-wait");
                t.setDaemon(true);
                return t;
            });

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
            return executeCommand(driver, ctx,
                    buildCommand(ctx, deviceId, method, stepSeq, exec), workflowIdOfRun());
        }

        Long srcRes = resourceOfParam(ctx, "source");
        Long dstRes = resourceOfParam(ctx, "target");
        if (srcRes == null || dstRes == null) {
            return executeCommand(driver, ctx,
                    buildCommand(ctx, deviceId, method, stepSeq, exec), workflowIdOfRun());
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
            return executeCommand(d.get(), ctx,
                    buildCommand(ctx, instId, m, stepSeq, exec), workflowIdOfRun());
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
    /**
     * 解析这一步该等设备多久（毫秒）。
     *
     * <p><b>改前是写死的 30 秒</b>（{@link #timeoutMs}），
     * 而 {@code device_method.timeout_sec} / {@code workflow_node.timeout_sec}
     * 两张表里存了值没人读 ⇒ <b>任何超过 30 秒的真机方法都被判超时</b>
     * （离心 5 分钟、PCR 几十分钟、孵育 30 分钟全必挂），与暂停无关，是纯 bug。
     *
     * <p>取值优先级见 {@link MethodTimeoutPolicy}：
     * 设备方法级 → 设备能力级 → 流程节点级 → 能力默认 → 兜底 30 秒。
     *
     * <p>拿不到库（{@code db == null}，影子执行等场景）时退回原固定值，
     * 保证**老路径行为不变**。
     */
    private long resolveTimeoutMs(StepContext ctx, String method) {
        if (db == null) {
            return timeoutMs;
        }
        String cap = ctx.capabilityCode();
        String key = ctx.resourceId() + "|" + (cap == null ? "" : cap) + "|" + method;
        Long cached = timeoutCache.get(key);
        if (cached != null) {
            return cached;
        }
        long methodSec = 0L;
        long libSec = 0L;
        long nodeSec = 0L;
        String effectiveCap = cap;
        try {
            // ⚠ ctx.resourceId() 其实是 **device_instance.id**，
            //   不是 resource_node.id —— 证据：DriverRegistry.driverFor(long deviceInstanceId)
            //   与 candidates() 返回的都是实例 id（机械臂是 214，不是 200214）。
            //   搞错 id 空间 ⇒ 查表全部落空 ⇒ 静默退回 30 秒兜底（2026-11-06 实踩）。
            long instanceId = ctx.resourceId();
            methodSec = deviceMethodTimeoutSec(instanceId, method);
            libSec = deviceLibraryTimeoutSec(instanceId);
            // 能力默认要用**设备的真实能力**，不是节点上启发式猜出来的那个 ——
            // 节点名「机械臂搬运」会被猜成 WASH（resolveCapability 第 6 级），
            // 拿猜出来的能力去查设备的方法库与默认表，两头都对不上。
            String devCap = deviceCapabilityCode(instanceId);
            if (devCap != null && !devCap.isBlank()) {
                effectiveCap = devCap;
            }
            nodeSec = workflowNodeTimeoutSec(ctx.nodeId());
        } catch (RuntimeException e) {
            // 查库失败不该把执行搞挂 —— 退回兜底值继续跑
            System.err.println("[超时] 解析失败，退回兜底 " + timeoutMs + "ms：" + e.getMessage());
        }
        MethodTimeoutPolicy.Resolved r = MethodTimeoutPolicy.resolve(
                methodSec, libSec, nodeSec, effectiveCap, timeoutMs);
        timeoutCache.put(key, r.timeoutMs());
        timeoutSource.put(key, r.source());
        if (r.timeoutMs() > 60_000L) {
            System.out.println("[超时] " + ctx.nodeKey() + " " + method
                    + " → " + (r.timeoutMs() / 1000L) + " 秒（来源 " + r.source()
                    + "：设备方法=" + methodSec + "s 设备库上限=" + libSec
                    + "s 节点=" + nodeSec + "s 能力=" + effectiveCap + "）");
        }
        return r.timeoutMs();
    }

    /**
     * 这个方法是否声明了「执行中可被打断」。
     *
     * <p>判据：{@code device_method.interruptible}（device_instance + 方法名精确匹配）。
     * 查不到 / 没配 ⇒ **false**（安全默认：宁可不停，也不要把搬板的机械臂停掉）。
     */
    private boolean isInterruptible(long instanceId, String method) {
        if (db == null || method == null || method.isBlank()) {
            return false;
        }
        String key = instanceId + "|" + method;
        Boolean cached = INTERRUPTIBLE_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        boolean v = false;
        try (java.sql.Connection c = db.open();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "SELECT interruptible FROM device_method "
                             + "WHERE tenant_id = ? AND device_id = ? AND method = ? LIMIT 1")) {
            ps.setLong(1, tenantId);
            ps.setLong(2, instanceId);
            ps.setString(3, method);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    v = rs.getBoolean(1);
                }
            }
        } catch (java.sql.SQLException e) {
            v = false;
        }
        INTERRUPTIBLE_CACHE.put(key, v);
        return v;
    }

    /**
     * 这条 run 现在是不是「该停」。
     *
     * <p>两个来源：
     * <ol>
     *   <li><b>workflow 级</b>：{@code run.status = 'paused'}（页面上的暂停按钮）。</li>
     *   <li><b>实验级</b>：我的流程正被某个生效中的<b>现场扣留</b>覆盖
     *       （{@code runtime_hold}：实验/岛/现场三级作用域）——
     *       实验级暂停落的就是扣留，靠 {@link RuntimeHold#coveringForWorkflow}
     *       反查"正在跑这条流程的实验"。</li>
     * </ol>
     */
    private boolean pauseRequested(long workflowId) {
        if (runId > 0) {
            String st = q1s("SELECT status::text FROM run WHERE tenant_id = ? AND id = ?",
                    tenantId, runId);
            if ("paused".equals(st)) {
                return true;
            }
        }
        // 实验级：反查正在跑我这条流程的实验，再看它有没有被扣留盖住。
        // 判据落在"步骤行还在跑"（experiment_run_step.status='running'），
        // **不能**按 experiment_run.status='running' 过滤 —— 实验级暂停会先把它
        // 置 paused，那样就永远查不到（见 RuntimeHold.coveringForWorkflow）。
        // fail-closed（P-03 / ADR-PAUSE-005）：读不到扣留时 coverageForWorkflow 返回
        // UNKNOWN，blocks() 为 true ⇒ 按「要停」处理，不带着未知现场状态继续跑物理动作。
        return new RuntimeHold(db, tenantId).coverageForWorkflow(workflowId).blocks();
    }

    /** 本次执行所属的流程 id（从 run 行取；查不到返回 0）。 */
    private long workflowIdOfRun() {
        if (runId <= 0) {
            return 0L;
        }
        Long v = q1("SELECT workflow_id FROM run WHERE tenant_id = ? AND id = ?",
                tenantId, runId);
        return v == null ? 0L : v;
    }

    /** 一条 SQL 取一个字符串值（查不到返回 null）。 */
    private String q1s(String sql, Object... args) {
        try (java.sql.Connection c = db.open();
             java.sql.PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (java.sql.SQLException e) {
            return null;
        }
    }

    /** 一条 SQL 取一个值（查不到返回 null）。 */
    private Long q1(String sql, Object... args) {
        try (java.sql.Connection c = db.open();
             java.sql.PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        } catch (java.sql.SQLException e) {
            return null;
        }
    }

    /**
     * 下发一条设备命令。
     *
     * <p><b>方法没声明可中断</b> ⇒ 老路径：调用线程直接阻塞等回包（行为不变）。
     *
     * <p><b>方法声明了可中断</b> ⇒ 放到工作线程里等，主线程每 200ms 查一次
     * 「是否该停」（workflow 级暂停 / 实验级扣留）：
     * 命中就调 {@code driver.abort(commandId)}
     * （{@code TcpPersistentDriver.abort} 会把等待中的 future 摘掉，
     * 让调用方立刻拿到失败）⇒ 这一步被中断，节点记 aborted，
     * 恢复时因为它不是 completed，会**重跑这一步**。
     */
    /**
     * P-07 第一切片：派发前落命令账本（同一 commandId 唯一），回包后写 ACKED/FAILED。
     *
     * <p>账本写失败按 DB 错误处理（fail-closed）：没有账本就不下发物理指令 ——
     * 否则崩溃后无法判断「到底动没动」。
     */
    private DriverResult executeCommand(DeviceDriver driver, StepContext ctx,
                                        DriverCommand cmd, long workflowId) {
        long ledgerId = 0L;
        if (db != null && runId > 0 && cmd.commandId() != null) {
            try {
                CommandLedger.Entry e = CommandLedger.begin(db, tenantId,
                        cmd.commandId(), runId, ctx.nodeId(), ctx.attempt(), 0,
                        cmd.deviceInstanceId(), cmd.method(), cmd.commandId(), traceId);
                if (!e.created()) {
                    // fail-closed：不抛检查异常（TransferPlan 的回调接口不声明 throws），
                    // 用明确错误码返回，由上层按错误码作用域处理。
                    return DriverResult.failed(cmd.commandId(), "EC-99-0006",
                            "命令账本已有 " + e.state()
                                    + " 记录，禁止重复下发（需人工确认设备位置）",
                            null, 0);
                }
                ledgerId = e.id();
            } catch (java.sql.SQLException ex) {
                return DriverResult.failed(cmd.commandId(), "EC-99-0002",
                        "命令账本写入失败：" + ex.getMessage(), null, 0);
            }
        }
        try {
            DriverResult r = doExecuteCommand(driver, ctx, cmd, workflowId);
            if (ledgerId > 0L) {
                try {
                    CommandLedger.finish(db, ledgerId, r.errorCode() == null,
                            r.errorCode(), r.message(), cmd.commandId());
                } catch (java.sql.SQLException ex) {
                    System.err.println("[命令账本] 回写失败：" + ex.getMessage());
                }
            }
            return r;
        } catch (RuntimeException ex) {
            if (ledgerId > 0L) {
                try {
                    CommandLedger.finish(db, ledgerId, false, "EC-99-0001",
                            ex.getMessage(), cmd.commandId());
                } catch (java.sql.SQLException ignored) {
                    // 回写失败不覆盖原始异常
                }
            }
            throw ex;
        }
    }

    private DriverResult doExecuteCommand(DeviceDriver driver, StepContext ctx,
                                          DriverCommand cmd, long workflowId) {
        if (!isInterruptible(cmd.deviceInstanceId(), cmd.method())) {
            return driver.execute(cmd);
        }
        java.util.concurrent.Future<DriverResult> f =
                INTERRUPT_POOL.submit(() -> driver.execute(cmd));
        try {
            while (true) {
                if (f.isDone()) {
                    return f.get();
                }
                if (pauseRequested(workflowId)) {
                    driver.abort(cmd.commandId());
                    try {
                        return f.get(5, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (java.util.concurrent.TimeoutException te) {
                        f.cancel(true);
                        return DriverResult.failed("0", "EC-99-0005",
                                "设备命令被暂停中止（方法声明可中断）", null, 0);
                    }
                }
                Thread.sleep(INTERRUPT_POLL_MS);
            }
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            return DriverResult.failed("0", "EC-99-0001",
                    "驱动异常：" + cause, null, 0);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            f.cancel(true);
            return DriverResult.failed("0", "EC-99-0005", "等待被中断", null, 0);
        }
    }

    /** 设备方法级超时（秒）：该设备该方法名登记的值，没有则 0。 */
    private long deviceMethodTimeoutSec(long instanceId, String method) {
        if (method == null || method.isBlank()) {
            return 0L;
        }
        try (java.sql.Connection c = db.open();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "SELECT COALESCE(MAX(timeout_sec), 0) FROM device_method "
                             + "WHERE tenant_id = ? AND device_id = ? AND method = ?")) {
            ps.setLong(1, tenantId);
            ps.setLong(2, instanceId);
            ps.setString(3, method);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (java.sql.SQLException e) {
            return 0L;
        }
    }

    /**
     * 设备方法库上限（秒）：该设备 {@code device_method} 里所有方法的最大值。
     *
     * <p>为什么要有这一级：设备库常常只登记了部分方法 ——
     * 演示岛的 ID1731 只有一行方法名「液体」，而流程实际下发的是 {@code Home}，
     * 精确匹配必然落空 ⇒ 没有这一级就又退回 30 秒，长工艺照样被误杀。
     *
     * <p>为什么**不按能力过滤**：节点上的能力码是按名字**启发式猜**出来的
     * （{@code resolveCapability} 第 6 级：节点名「机械臂搬运」会被猜成 WASH），
     * 拿它去过滤设备自己的方法库必然匹配不上 ⇒ 宁可取该设备全部方法的上限。
     */
    private long deviceLibraryTimeoutSec(long instanceId) {
        try (java.sql.Connection c = db.open();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "SELECT COALESCE(MAX(timeout_sec), 0) FROM device_method "
                             + "WHERE tenant_id = ? AND device_id = ?")) {
            ps.setLong(1, tenantId);
            ps.setLong(2, instanceId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (java.sql.SQLException e) {
            return 0L;
        }
    }

    /**
     * 设备的真实能力编码（resource_capability）；查不到返回 null。
     *
     * @param instanceId device_instance.id（不是 resource_node.id）
     *
     * <p>⚠ {@code resource_capability} 表**没有 tenant_id 列** ——
     * 带上它会直接抛 SQLException（"column rc.tenant_id does not exist"），
     * 而异常被这里吞掉 ⇒ 静默返回 null ⇒ 能力回落成节点上猜出来的那个。
     */
    private String deviceCapabilityCode(long instanceId) {
        try (java.sql.Connection c = db.open();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "SELECT c.code FROM resource_capability rc "
                             + "JOIN capability c ON c.id = rc.capability_id "
                             + "WHERE rc.resource_id = "
                             + "(SELECT resource_id FROM device_instance "
                             + " WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL) "
                             + "LIMIT 1")) {
            ps.setLong(1, tenantId);
            ps.setLong(2, instanceId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (java.sql.SQLException e) {
            return null;
        }
    }

    /** 流程节点级超时（秒）：workflow_node.timeout_sec，没有则 0。 */
    private long workflowNodeTimeoutSec(long nodeId) {
        if (nodeId <= 0L) {
            return 0L;
        }
        try (java.sql.Connection c = db.open();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "SELECT COALESCE(MAX(timeout_sec), 0) FROM workflow_node "
                             + "WHERE tenant_id = ? AND id = ?")) {
            ps.setLong(1, tenantId);
            ps.setLong(2, nodeId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (java.sql.SQLException e) {
            return 0L;
        }
    }

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
                resolveTimeoutMs(ctx, method),
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
