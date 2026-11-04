package com.labos.kernel.workflow;

import com.labos.kernel.alert.Alert;
import com.labos.kernel.alert.AlertKind;
import com.labos.kernel.engine.RecoverableNode;
import com.labos.kernel.engine.RunProgress;
import com.labos.kernel.engine.RunStateMachine;
import com.labos.kernel.engine.SimulatedBus;
import com.labos.kernel.engine.Sleeper;
import com.labos.kernel.recovery.Backoff;
import com.labos.kernel.recovery.RecoveryDecider;
import com.labos.kernel.recovery.RecoveryPolicy;
import com.labos.kernel.recovery.RecoveryPolicyResolver;
import com.labos.kernel.recovery.RecoveryScope;
import com.labos.kernel.recovery.RecoveryStrategy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 验证「执行 → 告警聚合」这段接线是否真的生效。
 *
 * <p>{@link WorkflowService#collectAlerts} 是纯函数（只依赖 {@link RunProgress}），
 * 所以可以脱离数据库直接测。
 */
public final class WorkflowAlertTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        System.out.println("════ 执行 → 告警聚合接线测试 ════");
        System.out.println();

        caseAllSuccess();
        caseManualProducesWarn();
        caseSkipProducesInfoOnly();
        caseSameFingerprintAggregated();
        caseRetrySucceededNoAlert();

        System.out.println();
        System.out.printf("通过 %d, 失败 %d%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 用例

    /** 全部成功 → 不应产生任何告警。 */
    private static void caseAllSuccess() {
        check("全部成功 → 0 条告警", () -> {
            RunProgress p = run(List.of(
                    RecoverableNode.of(1, "A", "TRANSFER", 100L, List.of(100L)),
                    RecoverableNode.of(2, "B", "INCUBATE", 200L, List.of(200L))),
                    bus -> { });
            List<Alert> alerts = WorkflowService.collectAlerts(p, fixedNow());
            eq("告警数", 0, alerts.size());
        });
    }

    /** 一个节点转人工 → 应产生 1 条 warn 告警且需推送。 */
    private static void caseManualProducesWarn() {
        check("转人工 → 1 条 warn 告警（需推送）", () -> {
            RunProgress p = run(List.of(
                    RecoverableNode.of(1, "A", "CENTRIFUGE", 300L, List.of(300L))),
                    bus -> bus.dead(300L, "CENTRIFUGE", "EC-08-0001", RecoveryScope.NODE));

            List<Alert> alerts = WorkflowService.collectAlerts(p, fixedNow());
            List<Alert> push = alerts.stream().filter(Alert::shouldNotify).toList();
            eq("需推送数", 1, push.size());
            eq("级别", "warn", push.get(0).severity());
            eq("指纹", "EC-08-0001|300", push.get(0).fingerprint());
            eq("含摘要总数", 2, alerts.size());
        });
    }

    /** 一个节点跳过 → 应留痕（info）但不推送。 */
    private static void caseSkipProducesInfoOnly() {
        check("跳过 → 1 条 info 留痕（不推送）", () -> {
            RunProgress p = run(List.of(
                    RecoverableNode.of(1, "Scan", "SCAN", 400L, List.of(400L))),
                    bus -> bus.dead(400L, "SCAN", "EC-08-1005", RecoveryScope.NODE));

            List<Alert> alerts = WorkflowService.collectAlerts(p, fixedNow());
            eq("含摘要总数", 2, alerts.size());
            eq("需推送数", 0, alerts.stream().filter(Alert::shouldNotify).count());
            Alert opened = alerts.stream().filter(a -> a.kind() == AlertKind.OPENED)
                    .findFirst().orElseThrow();
            eq("级别", "info", opened.severity());
        });
    }

    /** 同一节点多次失败（重试）→ 聚合为 1 条，count 反映次数。 */
    private static void caseSameFingerprintAggregated() {
        check("重试 3 次耗尽 → 1 条 warn 告警", () -> {
            RunProgress p = run(List.of(
                    RecoverableNode.of(1, "A", "WASH", 500L, List.of(500L))),
                    bus -> bus.dead(500L, "WASH", "EC-05-1006", RecoveryScope.COMMAND));

            List<Alert> alerts = WorkflowService.collectAlerts(p, fixedNow());
            List<Alert> pushable = alerts.stream().filter(Alert::shouldNotify).toList();
            eq("需推送告警数", 1, pushable.size());
            // count 语义 = 同指纹下受影响的节点数（不是重试次数）
            eq("count", 1, pushable.get(0).count());
            eq("类型", AlertKind.OPENED, pushable.get(0).kind());
            eq("级别", "warn", pushable.get(0).severity());
        });
    }

    /** 重试后最终成功 → 不应该产生任何告警（重要：否则告警会被自动恢复的噪音淹没）。 */
    private static void caseRetrySucceededNoAlert() {
        check("重试后成功 → 0 条告警", () -> {
            RunProgress p = run(List.of(
                    RecoverableNode.of(1, "A", "WASH", 600L, List.of(600L))),
                    bus -> bus.fail(600L, "WASH", "EC-05-1006", 2, RecoveryScope.COMMAND));

            List<Alert> alerts = WorkflowService.collectAlerts(p, fixedNow());
            eq("告警数", 0, alerts.size());
        });
    }

    // ---------------------------------------------------------------- 基础设施

    private static Instant fixedNow() {
        return Instant.parse("2026-10-29T10:00:00Z");
    }

    private static RunProgress run(List<RecoverableNode> nodes, Consumer<SimulatedBus> faults) {
        SimulatedBus bus = new SimulatedBus();
        faults.accept(bus);
        RunStateMachine sm = new RunStateMachine(decider(), e -> { },
                Sleeper.recording(new ArrayList<>()), Clock.systemUTC());
        return sm.executeAll(nodes, bus);
    }

    private static RecoveryDecider decider() {
        return new RecoveryDecider(new RecoveryPolicyResolver(List.of(
                new RecoveryPolicy("EC-05-1006", RecoveryScope.COMMAND, RecoveryStrategy.RETRY,
                        3, Backoff.EXPONENTIAL, Duration.ZERO, 10, true),
                new RecoveryPolicy("EC-08-0001", RecoveryScope.NODE, RecoveryStrategy.REASSIGN,
                        2, Backoff.FIXED, Duration.ZERO, 20, true),
                new RecoveryPolicy("EC-08-1005", RecoveryScope.NODE, RecoveryStrategy.SKIP,
                        1, Backoff.FIXED, Duration.ZERO, 30, true),
                new RecoveryPolicy(null, null, RecoveryStrategy.MANUAL,
                        0, Backoff.FIXED, Duration.ZERO, 99, true))));
    }

    private static void check(String name, Runnable body) {
        try {
            body.run();
            passed++;
            System.out.printf("  ✔ %s%n", name);
        } catch (AssertionError e) {
            failed++;
            System.out.printf("  ✘ %s%n     %s%n", name, e.getMessage());
        }
    }

    private static void eq(String what, Object expected, Object actual) {
        if (!String.valueOf(expected).equals(String.valueOf(actual))) {
            throw new AssertionError(what + " 期望 " + expected + " 实际 " + actual);
        }
    }

    private static void checkTrue(String what, boolean cond) {
        if (!cond) {
            throw new AssertionError(what + " 不成立");
        }
    }

    private WorkflowAlertTest() {
    }
}
