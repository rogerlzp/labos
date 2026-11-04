package com.labos.kernel.recovery;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 真实场景演示：用生产日志里的实例，对比老系统与新引擎的行为。
 *
 * <pre>
 *   javac -d out $(find src -name '*.java')
 *   java -cp out com.labos.kernel.recovery.RecoveryDemo
 * </pre>
 */
public final class RecoveryDemo {

    public static void main(String[] args) {
        RecoveryPolicy commTimeout = new RecoveryPolicy(
                "EC-05-1006", RecoveryScope.COMMAND, RecoveryStrategy.RETRY,
                3, Backoff.EXPONENTIAL, Duration.ofMillis(500), 10, true);
        RecoveryPolicy execFail = new RecoveryPolicy(
                "EC-08-0001", RecoveryScope.NODE, RecoveryStrategy.REASSIGN,
                2, Backoff.FIXED, Duration.ZERO, 20, true);
        RecoveryPolicy globalDefault = new RecoveryPolicy(
                null, null, RecoveryStrategy.RETRY,
                1, Backoff.FIXED, Duration.ofSeconds(1), 99, true);

        RecoveryDecider decider = new RecoveryDecider(
                new RecoveryPolicyResolver(List.of(commTimeout, execFail, globalDefault)));

        scenario1(decider);
        scenario2(decider);
        scenario3(decider);
    }

    /** 场景一：生产日志里那台离线设备 id0044。 */
    private static void scenario1(RecoveryDecider decider) {
        title("场景一：设备 id0044 离线（生产日志实测：全天 5,928 次 TrySyncRecords，11,856 条 ERROR）");

        System.out.println("  模拟连续 10 次失败，引擎逐次决策：\n");
        System.out.printf("  %-6s %-8s %-10s %s%n", "次数", "动作", "等待", "原因");
        System.out.println("  " + "-".repeat(96));

        boolean stopped = false;
        int retried = 0;
        int alerts = 0;

        for (int i = 0; i < 10; i++) {
            if (stopped) {
                System.out.printf("  %-6d %-8s %-10s %s%n", i + 1, "—", "—",
                        "（引擎已判定 MANUAL，调用方不再重试）");
                continue;
            }
            ErrorContext ctx = ErrorContext.builder("EC-05-1006", RecoveryScope.COMMAND)
                    .attempt(retried)
                    .currentResourceId(200383L)
                    .capabilityCode("TRANSFER")
                    .build();
            RecoveryDecision d = decider.decide(ctx);

            System.out.printf("  %-6d %-8s %-10s %s%n", i + 1, d.action(),
                    d.delayMillis() > 0 ? d.delayMillis() + "ms" : "—", d.reason());

            if (d.action() == RecoveryAction.RETRY) {
                retried++;
            } else {
                stopped = true;
                if (d.action() == RecoveryAction.MANUAL) {
                    alerts++;
                }
            }
        }

        System.out.println();
        System.out.println("  老系统：5,928 次重试 → 11,856 条 ERROR 日志 → 真人从日志海洋里捞问题");
        System.out.printf("  新引擎：%d 次重试 + %d 次告警升级 → 日志量下降 %.2f%%%n",
                retried, alerts, (1 - ((double) retried + alerts) / 11856) * 100);
        System.out.println("  关键：不是「重试得更聪明」，而是「知道什么时候该停」。");
        System.out.println();
    }

    /** 场景二：设备执行失败，自动换机。 */
    private static void scenario2(RecoveryDecider decider) {
        title("场景二：机械臂执行失败（EC-08-0001），同能力有 3 台设备可换");

        long[] candidates = {200383L, 200387L, 200390L};
        Set<Long> candidateSet = new java.util.LinkedHashSet<>();
        for (long c : candidates) candidateSet.add(c);
        Set<Long> failed = new java.util.LinkedHashSet<>();
        Long current = 200383L;
        int attempt = 0;

        System.out.printf("  %-6s %-10s %-12s %s%n", "轮次", "动作", "目标设备", "原因");
        System.out.println("  " + "-".repeat(96));

        for (int i = 0; i < 4; i++) {
            ErrorContext ctx = ErrorContext.builder("EC-08-0001", RecoveryScope.NODE)
                    .attempt(attempt)
                    .currentResourceId(current)
                    .capabilityCode("TRANSFER")
                    .candidateResourceIds(candidateSet)
                    .failedResourceIds(failed)
                    .build();
            RecoveryDecision d = decider.decide(ctx);

            System.out.printf("  %-6d %-10s %-12s %s%n", i + 1, d.action(),
                    d.targetResourceId() == null ? "—" : String.valueOf(d.targetResourceId()),
                    d.reason());

            if (d.action() == RecoveryAction.REASSIGN) {
                failed.add(current);
                current = d.targetResourceId();
                attempt++;
            } else {
                break;
            }
        }
        System.out.println();
        System.out.println("  老系统：节点写死 equipment_instance_id，设备坏了只能停下来等人。");
        System.out.println("  新引擎：工艺只声明「需要 TRANSFER 能力」，坏了自动换同能力设备继续跑。");
        System.out.println();
    }

    /** 场景三：无替代资源时，明确交人而不是瞎试。 */
    private static void scenario3(RecoveryDecider decider) {
        title("场景三：唯一天平的称量失败，没有替代设备");

        ErrorContext ctx = ErrorContext.builder("EC-08-0001", RecoveryScope.NODE)
                .attempt(0)
                .currentResourceId(200383L)
                .capabilityCode("TRANSFER")
                .candidateResourceIds(Set.of(200383L))
                .failedResourceIds(Set.of(200383L))
                .build();
        RecoveryDecision d = decider.decide(ctx);

        System.out.printf("  动作: %s%n", d.action());
        System.out.printf("  原因: %s%n", d.reason());
        System.out.println();
        System.out.println("  关键：不猜、不乱试、不刷日志，一次把话说清楚，直接进人工队列。");
        System.out.println();
    }

    private static void title(String s) {
        System.out.println("=".repeat(100));
        System.out.println("  " + s);
        System.out.println("=".repeat(100));
    }

    private RecoveryDemo() {
    }
}
