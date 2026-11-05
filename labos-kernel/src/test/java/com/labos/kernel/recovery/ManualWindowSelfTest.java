package com.labos.kernel.recovery;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 零依赖自测：不装 Maven/JUnit 也能用 javac 直接跑。
 *
 * <p>覆盖台账 6.5 的纯逻辑口径：什么时候开窗、默认项从哪来、
 * 人能改选什么、超时怎么降级、幂等键与告警级别。
 *
 * <pre>
 *   javac -d out $(find src -name '*.java')
 *   java -cp out com.labos.kernel.recovery.ManualWindowSelfTest
 * </pre>
 */
public final class ManualWindowSelfTest {

    private static int passed = 0;
    private static int failed = 0;

    private static RecoveryPolicy policy(RecoveryStrategy s, boolean enabled) {
        return new RecoveryPolicy("EC-05-1006", RecoveryScope.COMMAND, s,
                3, Backoff.FIXED, Duration.ZERO, 10, enabled);
    }

    public static void main(String[] args) {
        Instant t0 = Instant.parse("2026-11-05T00:00:00Z");
        Instant later = t0.plusSeconds(1800);
        RecoveryDecision manual = RecoveryDecision.manual("策略[EC-05-1006@COMMAND] 重试已耗尽，交人工");
        RecoveryDecision retry = RecoveryDecision.retry(500L, "第 1/3 次重试");

        // ---- 开窗触发条件（§6 / §13.8）
        run("有人值守 + MANUAL -> 开窗", () ->
                check("应开窗", ManualWindow.shouldOpen("attended", manual)));
        run("无人值守 + MANUAL -> 不弹窗口（只记 error 告警）", () ->
                check("不应开窗", !ManualWindow.shouldOpen("unattended", manual)));
        run("有人值守 + RETRY -> 不弹窗口（走自动重试）", () ->
                check("不应开窗", !ManualWindow.shouldOpen("attended", retry)));
        run("有人值守 + ABORT -> 不弹窗口", () ->
                check("不应开窗", !ManualWindow.shouldOpen("attended", RecoveryDecision.abort("终止"))));
        run("有人值守 + SKIP -> 不弹窗口", () ->
                check("不应开窗", !ManualWindow.shouldOpen("attended", RecoveryDecision.skip("跳过"))));
        run("decision 为空 -> 拒绝", () ->
                check("应抛 IAE", throwsIae(() -> ManualWindow.shouldOpen("attended", null))));

        // ---- 值守值归一化：保守回落 attended
        run("空值守值 -> attended", () ->
                eq("归一化", "attended", ManualWindow.normalizeAttendance(null)));
        run("带空白/大小写 -> unattended", () ->
                eq("归一化", "unattended", ManualWindow.normalizeAttendance("  UNATTENDED ")));
        run("未知值守值 -> 保守 attended（不静默跳过人）", () ->
                eq("归一化", "attended", ManualWindow.normalizeAttendance("who-knows")));

        // ---- 默认项来自 recovery_policy（§2.2）
        run("策略 RETRY -> 默认项 RETRY", () ->
                eq("默认项", "RETRY", ManualWindow.defaultActionOf(policy(RecoveryStrategy.RETRY, true))));
        run("策略 REASSIGN -> 默认项 REASSIGN", () ->
                eq("默认项", "REASSIGN", ManualWindow.defaultActionOf(policy(RecoveryStrategy.REASSIGN, true))));
        run("策略 SKIP -> 默认项 SKIP", () ->
                eq("默认项", "SKIP", ManualWindow.defaultActionOf(policy(RecoveryStrategy.SKIP, true))));
        run("策略 ABORT -> 默认项 ABORT", () ->
                eq("默认项", "ABORT", ManualWindow.defaultActionOf(policy(RecoveryStrategy.ABORT, true))));
        run("策略 MANUAL -> 默认项 MANUAL", () ->
                eq("默认项", "MANUAL", ManualWindow.defaultActionOf(policy(RecoveryStrategy.MANUAL, true))));
        run("COMPENSATE -> 降级 MANUAL（台账 6.7）", () ->
                eq("默认项", "MANUAL", ManualWindow.defaultActionOf(policy(RecoveryStrategy.COMPENSATE, true))));
        run("无匹配策略 -> 默认项 MANUAL", () ->
                eq("默认项", "MANUAL", ManualWindow.defaultActionOf(null)));
        run("禁用策略 -> 默认项 MANUAL", () ->
                eq("默认项", "MANUAL", ManualWindow.defaultActionOf(policy(RecoveryStrategy.RETRY, false))));
        run("null strategy -> MANUAL", () ->
                eq("映射", "MANUAL", ManualWindow.toAction(null)));

        // ---- 可改选动作与白名单（§6）
        run("动作集合恰为 5 项且顺序固定", () ->
                eq("集合", List.of("CONTINUE", "RETRY", "SKIP", "ABORT", "MANUAL"),
                        ManualWindow.allowedActions()));
        run("动作集合不可变", () ->
                check("不可变", throwsUoe(() -> ManualWindow.allowedActions().add("HACK"))));
        run("小写/带空格动作 -> 归一化", () ->
                eq("归一化", "RETRY", ManualWindow.normalizeAction(" retry ")));
        run("非法动作 -> 拒绝", () ->
                check("应抛 IAE", throwsIae(() -> ManualWindow.normalizeAction("DELETE_ALL"))));
        run("null 动作 -> 拒绝", () ->
                check("应抛 IAE", throwsIae(() -> ManualWindow.normalizeAction(null))));

        // ---- 可决定性 / 超时（幂等保护）
        run("pending 且未超时 -> 可决定", () ->
                check("可决定", ManualWindow.isDecidable(ManualWindow.Status.PENDING, t0, later)));
        run("pending 无超时配置 -> 可决定", () ->
                check("可决定", ManualWindow.isDecidable(ManualWindow.Status.PENDING, t0, null)));
        run("已超时 -> 不可决定", () ->
                check("不可决定", !ManualWindow.isDecidable(ManualWindow.Status.PENDING, later, t0)));
        run("边界时刻（now == timeoutAt）-> 判超时", () ->
                check("边界", ManualWindow.isExpired(t0, t0)));
        run("已决定 -> 不可决定（防重复回写）", () ->
                check("不可决定", !ManualWindow.isDecidable(ManualWindow.Status.DECIDED, t0, later)));
        run("timed_out / expired -> 不可决定", () -> {
            check("timed_out", !ManualWindow.isDecidable(ManualWindow.Status.TIMED_OUT, t0, later));
            check("expired", !ManualWindow.isDecidable(ManualWindow.Status.EXPIRED, t0, later));
        });
        run("超时降级 = 保持暂停（不自动替人决定）", () ->
                eq("降级动作", "MANUAL", ManualWindow.timeoutAction()));

        // ---- 告警级别不因弹窗改变（§13.8）
        run("attended -> warn / unattended -> error", () -> {
            eq("attended", "warn", ManualWindow.alertLevel("attended"));
            eq("unattended", "error", ManualWindow.alertLevel("unattended"));
        });

        // ---- 幂等键 / 展示快照
        run("同一 (runId,错误码,节点) 键稳定且可区分 runId", () -> {
            String a = ManualWindow.windowKey(42L, "EC-05-1006", "n1");
            eq("稳定", a, ManualWindow.windowKey(42L, "EC-05-1006", "n1"));
            check("可区分", !a.equals(ManualWindow.windowKey(43L, "EC-05-1006", "n1")));
        });
        run("windowKey 容忍 null 字段", () ->
                eq("键", "42||", ManualWindow.windowKey(42L, null, null)));
        run("View 带策略默认项 + 决策原因 + 值守标记", () -> {
            ManualWindow.View v = ManualWindow.view("attended", policy(RecoveryStrategy.RETRY, true), manual);
            eq("默认项", "RETRY", v.defaultAction());
            eq("原因", manual.reason(), v.decisionReason());
            check("值守", v.attended());
            eq("动作数", 5, v.allowedActions().size());
        });
        run("View 拒绝空原因（窗口必须能解释为什么停着）", () ->
                check("应抛 IAE", throwsIae(() -> new ManualWindow.View(
                        "MANUAL", ManualWindow.allowedActions(), " ", true))));

        System.out.printf("%n================ 结果: 通过 %d, 失败 %d ================%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

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

    private static boolean throwsUoe(Runnable r) {
        try {
            r.run();
            return false;
        } catch (UnsupportedOperationException e) {
            return true;
        }
    }

    private ManualWindowSelfTest() {
    }
}
