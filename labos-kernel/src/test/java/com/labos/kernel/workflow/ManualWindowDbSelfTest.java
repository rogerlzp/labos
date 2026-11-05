package com.labos.kernel.workflow;

import com.labos.kernel.engine.RunProgress;
import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.JdbcManualWindowStore;
import com.labos.kernel.recovery.ManualWindow;

import java.util.List;

/**
 * 有人值守窗口 落库自测（需要真实库）。
 *
 * <p>压住五条会静默出错的路径：同一 run 重复开窗、两人同时决策、
 * 决策后窗口不再出现、超时只标记不改 run、以及
 * 「settleRun 转人工 → 真的落窗口且默认项来自策略」这条端到端接线。
 *
 * <p><b>测试隔离</b>：只统计 {@code selftest-} 前缀的窗口。库里可能同时有
 * 流程矩阵/真实执行产生的 pending 窗口，断言绝对条数会被它们干扰
 * （2026-11-05 首次跑就踩到）。用合成 windowKey 与负 runId，finally 清干净；
 * 不依赖 run 表真实行（manual_window.run_id 故意没有外键）。
 */
public final class ManualWindowDbSelfTest {

    private static int pass;
    private static int fail;
    private static final long TENANT = 1L;
    private static final String PREFIX = "selftest-manual-window-";

    public static void main(String[] args) throws Exception {
        DbConfig db = DbConfig.fromEnv();
        JdbcManualWindowStore store = new JdbcManualWindowStore(db, TENANT);
        cleanup(store);
        try {
            run("首次开窗成功", () -> check(store.open(
                    -991001L, PREFIX + "a", "EC-05-1006", "n1", "RETRY",
                    "RETRY", ManualWindow.allowedActions(), "通讯超时，转人工", 30)));
            run("同一 windowKey 重复开窗被幂等挡住", () -> check(!store.open(
                    -991001L, PREFIX + "a", "EC-05-1006", "n1", "RETRY",
                    "RETRY", ManualWindow.allowedActions(), "通讯超时，转人工", 30)));
            run("自建窗口恰 1 条且默认项/状态/动作集正确", () -> {
                List<JdbcManualWindowStore.Window> list = selftestPending(store);
                eq("条数", 1L, (long) list.size());
                var w = list.get(0);
                eq("默认项", "RETRY", w.defaultAction());
                eq("状态", "pending", w.status());
                eq("动作集", 5, w.allowedActions().split(",").length);
                check("原因非空", w.decisionReason() != null && !w.decisionReason().isBlank());
                check("有超时时间", w.timeoutAt() != null);
            });
            run("byId 能取回且 runId 正确", () -> {
                long id = keyOf(store, PREFIX + "a");
                var w = store.byId(id);
                check("非空", w != null);
                eq("runId", -991001L, w.runId());
                eq("windowKey", PREFIX + "a", w.windowKey());
            });
            run("不同 windowKey 可以再开一扇", () -> check(store.open(
                    -991002L, PREFIX + "b", "EC-08-0001", "n2", "REASSIGN",
                    "REASSIGN", ManualWindow.allowedActions(), "无替代设备，转人工", 30)));
            run("自建窗口返回 2 条", () -> eq("条数", 2L, (long) selftestPending(store).size()));
            run("第一次决策生效", () -> {
                long id = keyOf(store, PREFIX + "a");
                check("更新 1 行", store.decide(id, "CONTINUE", "selftest", "继续跑"));
                eq("状态", "decided", store.byId(id).status());
                eq("动作", "CONTINUE", store.byId(id).decidedAction());
            });
            run("第二次决策被幂等挡住", () -> check(!store.decide(
                    keyOf(store, PREFIX + "a"), "ABORT", "selftest", "重复点")));
            run("已处置窗口不再出现在 pending", () -> {
                var list = selftestPending(store);
                eq("条数", 1L, (long) list.size());
                eq("剩下的是 b", PREFIX + "b", list.get(0).windowKey());
            });
            run("超时只标 timed_out，不产生可决策窗口", () -> {
                check("造超时样本", store.open(
                        -991003L, PREFIX + "c", "EC-UNKNOWN", "n3", null,
                        "MANUAL", ManualWindow.allowedActions(), "超时样本", -1));
                int n = store.expireOverdue();
                check("至少标记 1 条", n >= 1);
                eq("c 的状态", "timed_out",
                        store.byWindowKey(PREFIX + "c").status());
                check("pending 不含 c", selftestPending(store).stream()
                        .noneMatch(w -> (PREFIX + "c").equals(w.windowKey())));
            });
            run("settleRun 接线：attended + MANUAL 落窗口，默认项来自策略", () -> {
                WorkflowService svc = new WorkflowService(db, TENANT, 19301);
                RunProgress p = new RunProgress();
                p.manual(123L, "接线实证：通讯超时", "EC-05-1006");
                svc.openManualWindow(-991004L, p);
                var w = store.byWindowKey(
                        ManualWindow.windowKey(-991004L, "EC-05-1006", "123"));
                check("窗口已落库", w != null);
                eq("默认项来自 @command 策略", "RETRY", w.defaultAction());
                eq("策略已记录", "RETRY", w.strategy());
                eq("状态", "pending", w.status());
            });
        } finally {
            cleanup(store);
        }
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    /** 只看自测自己造的窗口，避免被库里真实/流程矩阵窗口干扰。 */
    private static List<JdbcManualWindowStore.Window> selftestPending(
            JdbcManualWindowStore store) throws Exception {
        return store.pending(100).stream()
                .filter(w -> w.windowKey() != null && w.windowKey().startsWith(PREFIX))
                .toList();
    }

    private static long keyOf(JdbcManualWindowStore store, String key) throws Exception {
        var w = store.byWindowKey(key);
        if (w == null) {
            throw new AssertionError("找不到窗口 " + key);
        }
        return w.id();
    }

    private static void cleanup(JdbcManualWindowStore store) throws Exception {
        store.deleteByKeyPrefix(PREFIX);
        // 接线实证的 windowKey 由 ManualWindow.windowKey 生成（以 runId 开头），
        // 不带 selftest- 前缀，必须单独清掉，否则下次跑会撞幂等键。
        store.deleteByKeyPrefix("-991004");
    }

    private interface Body {
        void run() throws Exception;
    }

    private static void run(String name, Body t) {
        try {
            t.run();
            pass++;
            System.out.println("  ✔ " + name);
        } catch (AssertionError | RuntimeException e) {
            fail++;
            System.out.println("  ✘ " + name + " — " + e);
        } catch (Exception e) {
            fail++;
            System.out.println("  ✘ " + name + " — 异常: " + e);
        }
    }

    private static void check(boolean cond) {
        if (!cond) {
            throw new AssertionError("断言失败");
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

    private ManualWindowDbSelfTest() {
    }
}
