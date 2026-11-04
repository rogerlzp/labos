package com.labos.kernel.driver;

import com.labos.kernel.device.DeviceErrorMapper;
import com.labos.kernel.device.DeviceErrorSql;
import com.labos.kernel.device.DeviceModel;
import com.labos.kernel.device.DeviceModels;
import com.labos.kernel.driver.tcp.TcpDriverAdapter;
import com.labos.kernel.recovery.Backoff;
import com.labos.kernel.recovery.ErrorContext;
import com.labos.kernel.recovery.RecoveryAction;
import com.labos.kernel.recovery.RecoveryDecider;
import com.labos.kernel.recovery.RecoveryDecision;
import com.labos.kernel.recovery.RecoveryPolicy;
import com.labos.kernel.recovery.RecoveryPolicyResolver;
import com.labos.kernel.recovery.RecoveryScope;
import com.labos.kernel.recovery.RecoveryStrategy;
import com.labos.kernel.sim.DeviceCore;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * **全链路自检：设备码 → 驱动 → 中控码 → 恢复动作**（不需要数据库）。
 *
 * <p>前面两个自检各自验证一半：
 * <ul>
 *   <li>{@code DeviceModelSelfTest}：映射表与决策引擎对不对；</li>
 *   <li>{@code SimulatorBehaviorSelfTest}：模拟器像不像真机。</li>
 * </ul>
 * 但真实系统里这两半是**接在一条线**上的：
 * <pre>
 *   S100 回 {error_code:0x8000000C}
 *     → TcpDriverAdapter 解析回包
 *     → DeviceErrorMapper 翻译成 EC-08-400C
 *     → RecoveryDecider 查策略
 *     → MANUAL（叫人换纸）
 * </pre>
 * 本自检就是把这根线真的连起来跑一遍：起模拟器、用真适配器发命令、
 * 把 {@link DriverResult} 喂给真的恢复引擎。
 *
 * <p>它能抓到一类特别隐蔽的 bug：某一层单独都对，但**接口对不上**
 * （比如参数被序列化成字符串、错误码被压成兜底码、scope 写成 command）。
 */
public final class DeviceErrorChainSelfTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        section("1. 适配器把设备码翻成中控码");
        chain();

        section("2. 真库策略（设了 LABOS_DB_* 才跑）");
        dbChain();

        System.out.println();
        System.out.printf("通过 %d, 失败 %d%n", pass, fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void chain() throws Exception {
        DeviceModel model = DeviceModels.of("s100");
        int port = freePort();
        DeviceCore core = new DeviceCore(port, "501", "SEAL1", "SEAL", 1,
                false, 0, "EC-05-0001", false, "sealer", model, null, 0);
        core.startServer("127.0.0.1", port);

        try {
            Map<Long, Set<String>> devCaps = new LinkedHashMap<>();
            devCaps.put(501L, Set.of("SEAL"));
            DriverDescriptor desc = new DriverDescriptor(
                    "DRV-S100", "tcp://127.0.0.1:" + port, "tcp", "sim",
                    Set.of("SEAL"), devCaps, new LinkedHashSet<>(List.of(501L)), Map.of());

            // 型号绑定：设备 501 是 S100（生产环境从 device.attr.device_model 读）
            DeviceErrorMapper mapper = new DeviceErrorMapper(Map.of(501L, "s100"), null);
            DeviceDriver driver = new TcpDriverAdapter(desc, "127.0.0.1", port, Map.of(), mapper);

            // 1) 正常命令：参数是**字符串**（真实驱动就是这么序列化的）
            Map<String, Object> moveHome = Map.of("value", "0");
            DriverResult ok = driver.execute(cmd("c1", "MoveTray", moveHome));
            ok("托盘回原点成功", ok.ok());

            // 2) 托盘移走再封膜 → 设备回 0x8000001A → 中控应看到 EC-08-401A
            driver.execute(cmd("c2", "MoveTray", Map.of("value", "10000")));
            DriverResult notHome = driver.execute(cmd("c3", "Seal", Map.of()));
            eq("设备 0x8000001A → 中控 EC-08-401A", "EC-08-401A", notHome.errorCode());
            ok("原始回包没丢", notHome.deviceRawResponse() != null
                    && notHome.deviceRawResponse().contains("0x8000001A"));
            ok("说明里带型号与建议", notHome.message().contains("s100")
                    && notHome.message().contains("建议"));

            // 3) 无纸（注入）→ EC-08-400C，并且**真机语义**：不清故障就一直报
            driver.execute(cmd("c4", "MoveTray", Map.of("value", "0")));
            core.injectNextFailure("0x8000000C");
            DriverResult noPaper = driver.execute(cmd("c5", "Seal", Map.of()));
            eq("设备 0x8000000C → 中控 EC-08-400C", "EC-08-400C", noPaper.errorCode());
            DriverResult stillNoPaper = driver.execute(cmd("c6", "Seal", Map.of()));
            eq("未清故障仍然报同一码", "EC-08-400C", stillNoPaper.errorCode());
            driver.execute(cmd("c7", "ClearFault", Map.of()));
            DriverResult afterClear = driver.execute(cmd("c8", "Seal", Map.of()));
            ok("清故障后可继续封膜", afterClear.ok());

            // 4) 未登记的设备码 → 不能静默放过
            core.injectNextFailure("0xDEADBEEF");
            DriverResult unknown = driver.execute(cmd("c9", "Seal", Map.of()));
            eq("未登记码 → EC-08-9001", DeviceErrorMapper.UNKNOWN_CODE, unknown.errorCode());
            ok("未登记码在说明里保留原始码", unknown.message().contains("0xDEADBEEF"));
            core.clearLatchedFault();

            // 5) 把结果喂给**真正的**恢复引擎：该叫人就叫人
            RecoveryDecider decider = decider();
            RecoveryAction paperAction = decider.decide(ErrorContext.of(
                    noPaper.errorCode(), RecoveryScope.NODE, 0)).action();
            eq("无纸 → 转人工（不是盲目重试）", RecoveryAction.MANUAL, paperAction);

            core.injectNextFailure("0x80000001");
            DriverResult retryable = driver.execute(cmd("c10", "Seal", Map.of()));
            eq("信号量溢出 → EC-08-4001", "EC-08-4001", retryable.errorCode());
            RecoveryAction retryAction = decider.decide(ErrorContext.of(
                    retryable.errorCode(), RecoveryScope.NODE, 0)).action();
            eq("瞬时故障 → 自动重试", RecoveryAction.RETRY, retryAction);

            core.injectNextFailure("0x80000008");
            DriverResult fatal = driver.execute(cmd("c11", "Seal", Map.of()));
            eq("收到错误指令 → EC-08-4008", "EC-08-4008", fatal.errorCode());
            RecoveryAction abortAction = decider.decide(ErrorContext.of(
                    fatal.errorCode(), RecoveryScope.NODE, 0)).action();
            eq("协议不匹配 → 终止（不要重试）", RecoveryAction.ABORT, abortAction);
        } finally {
            core.stopServer();
        }
    }

    /**
     * 连**真库**验证一遍：中控码在 {@code error_code} 里存在，且 {@code recovery_policy}
     * 查出来的策略与型号库的处置一致。
     *
     * <p>为什么必须连库：前面用的是"从型号库现生成的策略"，
     * 它证明不了种子 SQL 真被灌进库了、也证明不了 scope 写的是 {@code node}。
     * 这两件事错了，现象是"所有设备故障都转人工"，很难查。
     *
     * <p>没设 {@code LABOS_DB_HOST} 就跳过（纯逻辑测试不依赖数据库）。
     */
    private static void dbChain() {
        String host = System.getenv("LABOS_DB_HOST");
        if (host == null || host.isBlank()) {
            System.out.println("  ○ 没设 LABOS_DB_HOST，跳过（纯逻辑结论不受影响）");
            return;
        }
        try {
            var db = com.labos.kernel.persistence.DbConfig.fromEnv();
            var repo = new com.labos.kernel.persistence.JdbcRecoveryPolicyRepository(db, 1);
            var decider = new RecoveryDecider(new RecoveryPolicyResolver(repo.loadAll()));

            eq("库里能查到 无纸 → 转人工", RecoveryAction.MANUAL,
                    decider.decide(ErrorContext.of("EC-08-400C", RecoveryScope.NODE, 0)).action());
            eq("库里能查到 信号量溢出 → 重试", RecoveryAction.RETRY,
                    decider.decide(ErrorContext.of("EC-08-4001", RecoveryScope.NODE, 0)).action());
            eq("库里能查到 收到错误指令 → 终止", RecoveryAction.ABORT,
                    decider.decide(ErrorContext.of("EC-08-4008", RecoveryScope.NODE, 0)).action());
            eq("库里能查到 托盘不在原点 → 转人工", RecoveryAction.MANUAL,
                    decider.decide(ErrorContext.of("EC-08-401A", RecoveryScope.NODE, 0)).action());

            RecoveryDecision d = decider.decide(
                    ErrorContext.of("EC-08-400C", RecoveryScope.NODE, 0));
            ok("决策理由里带得出处置（告警能看懂）", d.reason() != null && !d.reason().isBlank());
        } catch (Exception e) {
            fail++;
            System.out.println("  ✗ 连库检查失败：" + e);
        }
    }

    /** 用型号库生成的真实策略装配决策器（与 95_seed_data.sql 同源）。 */
    private static RecoveryDecider decider() {
        List<RecoveryPolicy> policies = new ArrayList<>();
        for (DeviceErrorSql.Mapping m : DeviceErrorSql.allMappings()) {
            var def = m.def();
            DeviceErrorSql.Policy p = DeviceErrorSql.policyOf(def.disposition());
            policies.add(new RecoveryPolicy(def.kernelCode(), RecoveryScope.NODE,
                    RecoveryStrategy.valueOf(p.strategy().toUpperCase()),
                    p.maxAttempts(), Backoff.EXPONENTIAL, Duration.ofMillis(500),
                    p.priority(), true));
        }
        return new RecoveryDecider(new RecoveryPolicyResolver(policies));
    }

    private static DriverCommand cmd(String id, String method, Map<String, Object> params) {
        return new DriverCommand(id, 501L, method, params, 5_000, "selftest");
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("── " + title + " " + "─".repeat(Math.max(0, 56 - title.length())));
    }

    private static void ok(String what, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("  ✓ " + what);
        } else {
            fail++;
            System.out.println("  ✗ " + what);
        }
    }

    private static void eq(String what, Object want, Object got) {
        boolean same = want == null ? got == null : want.equals(got);
        if (same) {
            pass++;
            System.out.println("  ✓ " + what + (want == null ? "" : " = " + want));
        } else {
            fail++;
            System.out.println("  ✗ " + what + "：期望 " + want + "，实际 " + got);
        }
    }

    private DeviceErrorChainSelfTest() {
    }
}
