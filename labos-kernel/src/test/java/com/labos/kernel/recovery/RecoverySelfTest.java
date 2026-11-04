package com.labos.kernel.recovery;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 零依赖自测：不装 Maven/JUnit 也能用 javac 直接跑。
 *
 * <pre>
 *   javac -d out $(find src -name '*.java')
 *   java -cp out com.labos.kernel.recovery.RecoverySelfTest
 * </pre>
 */
public final class RecoverySelfTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        // ---- 策略库：模拟真实配置
        RecoveryPolicy retryTimeout = new RecoveryPolicy(
                "EC-05-1006", RecoveryScope.COMMAND, RecoveryStrategy.RETRY,
                3, Backoff.EXPONENTIAL, Duration.ofMillis(500), 10, true);

        RecoveryPolicy reassignExecFail = new RecoveryPolicy(
                "EC-08-0001", RecoveryScope.NODE, RecoveryStrategy.REASSIGN,
                2, Backoff.FIXED, Duration.ZERO, 20, true);

        RecoveryPolicy skipWarn = new RecoveryPolicy(
                "EC-08-1005", RecoveryScope.NODE, RecoveryStrategy.SKIP,
                1, Backoff.FIXED, Duration.ZERO, 30, true);

        RecoveryPolicy globalRetry = new RecoveryPolicy(
                null, null, RecoveryStrategy.RETRY,
                1, Backoff.FIXED, Duration.ofMillis(1000), 99, true);

        RecoveryPolicy disabledPolicy = new RecoveryPolicy(
                "EC-99-9999", null, RecoveryStrategy.ABORT,
                0, Backoff.FIXED, Duration.ZERO, 1, false);

        RecoveryDecider decider = new RecoveryDecider(
                new RecoveryPolicyResolver(List.of(
                        retryTimeout, reassignExecFail, skipWarn, globalRetry, disabledPolicy)));

        // ============ 1. 重试类 ============
        run("通讯超时 第1次 -> RETRY, 退避 500ms", () -> {
            RecoveryDecision d = decider.decide(ErrorContext.of("EC-05-1006", RecoveryScope.COMMAND, 0));
            eq("动作", RecoveryAction.RETRY, d.action());
            eq("退避", 500L, d.delayMillis());
            check("原因非空", d.reason().contains("第 1/3 次重试"));
        });

        run("通讯超时 第2次 -> RETRY, 退避 1000ms(指数)", () -> {
            RecoveryDecision d = decider.decide(ErrorContext.of("EC-05-1006", RecoveryScope.COMMAND, 1));
            eq("动作", RecoveryAction.RETRY, d.action());
            eq("退避", 1000L, d.delayMillis());
        });

        run("通讯超时 第3次 -> RETRY, 退避 2000ms", () -> {
            RecoveryDecision d = decider.decide(ErrorContext.of("EC-05-1006", RecoveryScope.COMMAND, 2));
            eq("退避", 2000L, d.delayMillis());
        });

        run("通讯超时 耗尽(maxAttempts=3) -> MANUAL", () -> {
            RecoveryDecision d = decider.decide(ErrorContext.of("EC-05-1006", RecoveryScope.COMMAND, 3));
            eq("动作", RecoveryAction.MANUAL, d.action());
            check("原因说明耗尽", d.reason().contains("已耗尽"));
            check("终止", d.isTerminal());
        });

        // ============ 2. 换机类 ============
        run("执行失败 有替代设备 -> REASSIGN", () -> {
            ErrorContext ctx = ErrorContext.builder("EC-08-0001", RecoveryScope.NODE)
                    .attempt(0)
                    .currentResourceId(200383L)
                    .capabilityCode("TRANSFER")
                    .candidateResourceIds(Set.of(200383L, 200387L, 200390L))
                    .build();
            RecoveryDecision d = decider.decide(ctx);
            eq("动作", RecoveryAction.REASSIGN, d.action());
            eq("目标(排除当前, 取最小)", 200387L, d.targetResourceId());
        });

        run("执行失败 排除已失败设备 -> 选下一个", () -> {
            ErrorContext ctx = ErrorContext.builder("EC-08-0001", RecoveryScope.NODE)
                    .attempt(1)
                    .currentResourceId(200387L)
                    .failedResourceIds(Set.of(200383L, 200387L))
                    .candidateResourceIds(Set.of(200383L, 200387L, 200390L))
                    .build();
            RecoveryDecision d = decider.decide(ctx);
            eq("目标", 200390L, d.targetResourceId());
        });

        run("执行失败 无替代设备 -> MANUAL(附候选与已失败)", () -> {
            ErrorContext ctx = ErrorContext.builder("EC-08-0001", RecoveryScope.NODE)
                    .attempt(0)
                    .currentResourceId(200383L)
                    .failedResourceIds(Set.of(200383L))
                    .candidateResourceIds(Set.of(200383L))
                    .build();
            RecoveryDecision d = decider.decide(ctx);
            eq("动作", RecoveryAction.MANUAL, d.action());
            check("原因含候选", d.reason().contains("候选"));
        });

        run("执行失败 换机次数耗尽 -> MANUAL", () -> {
            ErrorContext ctx = ErrorContext.builder("EC-08-0001", RecoveryScope.NODE)
                    .attempt(2)
                    .candidateResourceIds(Set.of(200387L))
                    .build();
            RecoveryDecision d = decider.decide(ctx);
            eq("动作", RecoveryAction.MANUAL, d.action());
        });

        // ============ 3. 跳过 / 兜底 / 禁用 ============
        run("设备状态异常 -> SKIP", () -> {
            RecoveryDecision d = decider.decide(ErrorContext.of("EC-08-1005", RecoveryScope.NODE, 0));
            eq("动作", RecoveryAction.SKIP, d.action());
        });

        run("未知错误码 走全局兜底 -> RETRY", () -> {
            RecoveryDecision d = decider.decide(ErrorContext.of("EC-UNKNOWN", RecoveryScope.COMMAND, 0));
            eq("动作", RecoveryAction.RETRY, d.action());
            eq("兜底退避", 1000L, d.delayMillis());
        });

        run("禁用的策略不参与匹配", () -> {
            RecoveryDecision d = decider.decide(ErrorContext.of("EC-99-9999", RecoveryScope.RUN, 0));
            // 该策略 enabled=false，应落到全局兜底 RETRY，而非 ABORT
            eq("动作", RecoveryAction.RETRY, d.action());
        });

        // ============ 4. 策略优先级 ============
        run("精确匹配优先于通配", () -> {
            RecoveryDecider d2 = new RecoveryDecider(new RecoveryPolicyResolver(List.of(
                    new RecoveryPolicy(null, null, RecoveryStrategy.ABORT,
                            1, Backoff.FIXED, Duration.ZERO, 1, true),
                    new RecoveryPolicy("EC-05-1006", RecoveryScope.COMMAND, RecoveryStrategy.RETRY,
                            2, Backoff.FIXED, Duration.ofMillis(100), 99, true))));
            RecoveryDecision d = d2.decide(ErrorContext.of("EC-05-1006", RecoveryScope.COMMAND, 0));
            eq("走了精确策略", RecoveryAction.RETRY, d.action());
        });

        run("同精度按 priority 数字小者优先", () -> {
            RecoveryDecider d2 = new RecoveryDecider(new RecoveryPolicyResolver(List.of(
                    new RecoveryPolicy("EC-X", null, RecoveryStrategy.ABORT,
                            1, Backoff.FIXED, Duration.ZERO, 50, true),
                    new RecoveryPolicy("EC-X", null, RecoveryStrategy.SKIP,
                            1, Backoff.FIXED, Duration.ZERO, 10, true))));
            RecoveryDecision d = d2.decide(ErrorContext.of("EC-X", RecoveryScope.NODE, 0));
            eq("priority=10 胜出", RecoveryAction.SKIP, d.action());
        });

        // ============ 5. 确定性与边界 ============
        run("相同输入必须产出相同决策（可回放）", () -> {
            ErrorContext ctx = ErrorContext.builder("EC-08-0001", RecoveryScope.NODE)
                    .attempt(0).currentResourceId(1L)
                    .candidateResourceIds(new java.util.LinkedHashSet<>(List.of(9L, 3L, 5L)))
                    .build();
            RecoveryDecision a = decider.decide(ctx);
            RecoveryDecision b = decider.decide(ctx);
            eq("动作一致", a.action(), b.action());
            eq("目标一致", a.targetResourceId(), b.targetResourceId());
        });

        run("指数退避有上限(5分钟)", () -> {
            long v = BackoffCalculator.compute(Backoff.EXPONENTIAL,
                    Duration.ofMillis(500), 25, 0.0, null);
            eq("上限", BackoffCalculator.MAX_DELAY_MS, v);
        });

        run("抖动不越界", () -> {
            java.util.Random r = new java.util.Random(42);
            for (int i = 0; i < 200; i++) {
                long v = BackoffCalculator.compute(Backoff.EXPONENTIAL,
                        Duration.ofMillis(500), 3, 0.2, r);
                check("下界", v >= 0);
                check("上界", v <= BackoffCalculator.MAX_DELAY_MS);
            }
        });

        run("非法输入被拒绝", () -> {
            check("空错误码", throwsIae(() -> ErrorContext.of("", RecoveryScope.RUN, 0)));
            check("负 attempt", throwsIae(() -> ErrorContext.of("E", RecoveryScope.RUN, -1)));
            check("空 reason", throwsIae(() -> new RecoveryDecision(RecoveryAction.RETRY, 0, null, " ")));
            check("REASSIGN 必须有目标", throwsIae(() -> RecoveryDecision.reassign(null, "x")));
        });

        System.out.printf("%n================ 结果: 通过 %d, 失败 %d ================%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- harness

    private static void run(String name, Runnable t) {
        try {
            t.run();
            passed++;
            System.out.printf("  ✔ %s%n", name);
        } catch (AssertionError e) {
            failed++;
            System.out.printf("  ✘ %s%n      %s%n", name, e.getMessage());
        } catch (RuntimeException e) {
            failed++;
            System.out.printf("  ✘ %s%n      异常: %s%n", name, e);
        }
    }

    private static void check(String desc, boolean cond) {
        if (!cond) {
            throw new AssertionError(desc + " 断言失败");
        }
    }

    private static void eq(String desc, Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(desc + " 期望=" + expected + " 实际=" + actual);
        }
    }

    private static boolean throwsIae(Runnable r) {
        try {
            r.run();
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private RecoverySelfTest() {
    }
}
