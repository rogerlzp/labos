package com.labos.kernel.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 调度队列排序自测（纯逻辑，不连库）。
 *
 * <p>钉住规格 §14.6 的一句话：<b>`QUEUE_ORDER` = EDD → priority DESC → FIFO</b>。
 *
 * <p>为什么单独立一个类：这段排序原先是 {@code ExperimentScheduler} 里的私有常量，
 * 只被"快照位次"和"重启 rehydrate"间接覆盖 —— 排序写错了，页面上的
 * "还要等几个"就是假的，但没有一条断言直接看着它。这里直接把
 * {@code QUEUE_ORDER} 拿出来排序，和 {@code dispatch()} 用的是同一个比较器。
 *
 * <p>格式与其它自检一致：`通过 N, 失败 M`，失败则退出码 1。
 */
public final class SchedulerOrderSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        System.out.println("════ 调度队列排序（EDD → priority → FIFO）════");
        System.out.println();

        section("1. 第一关键字：EDD（截止早的先跑，与提交顺序无关）");
        eq("09:00 先于 10:00（即便 10:00 先提交）", "1,2",
                order(q(1, "2026-11-05T09:00:00", 0, 200), q(2, "2026-11-05T10:00:00", 0, 100)));

        section("2. 无截止（null）排最后");
        eq("有截止的在前、null 在后", "1,2",
                order(q(1, "2026-11-05T09:00:00", 0, 1), q(2, null, 0, 2)));
        eq("null 之间再比优先级", "3,4",
                order(q(4, null, 1, 400), q(3, null, 9, 300)));

        section("3. 第二关键字：同截止比 priority DESC");
        eq("priority 5 先于 1", "1,2",
                order(q(1, "2026-11-05T09:00:00", 5, 100), q(2, "2026-11-05T09:00:00", 1, 100)));

        section("4. 第三关键字：同截止同优先级比 FIFO（提交早的先跑）");
        eq("submit 100 先于 200", "1,2",
                order(q(1, "2026-11-05T09:00:00", 3, 100), q(2, "2026-11-05T09:00:00", 3, 200)));

        section("5. 三级综合：一条队列按 EDD → priority → FIFO 排定");
        // 1: 截止最晚；2/3: 同截止，2 优先级高；4: 截止最早
        eq("4(最早截止) → 2(高优先) → 3(低优先) → 1(最晚截止)", "4,2,3,1",
                order(q(4, "2026-11-05T08:00:00", 0, 900),
                        q(2, "2026-11-05T09:00:00", 5, 500),
                        q(3, "2026-11-05T09:00:00", 1, 100),
                        q(1, "2026-11-05T10:00:00", 99, 1)));

        section("6. 边界：空队列 / 单元素 / 三级全同（保持稳定）");
        eq("空队列排序不抛异常", "", order());
        eq("单元素原样", "7", order(q(7, "2026-11-05T09:00:00", 1, 1)));
        eq("三级全同按原顺序（稳定排序）", "5,6",
                order(q(5, "2026-11-05T09:00:00", 1, 1), q(6, "2026-11-05T09:00:00", 1, 1)));

        System.out.printf("%n通过 %d, 失败 %d%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 工具

    /** 造一条排队项：id 同时当 run/experiment id，islandId 固定 1（排序不看它）。 */
    private static ExperimentScheduler.Queued q(long id, String deadline, int priority, long submittedAt) {
        return new ExperimentScheduler.Queued(id, id, 1L, priority, deadline, submittedAt);
    }

    /** 按 QUEUE_ORDER 排序后返回 experimentRunId 序列（如 `"1,2,3"`）。 */
    private static String order(ExperimentScheduler.Queued... items) {
        List<ExperimentScheduler.Queued> list = new ArrayList<>(List.of(items));
        list.sort(ExperimentScheduler.QUEUE_ORDER);
        return list.stream().map(x -> String.valueOf(x.experimentRunId()))
                .collect(Collectors.joining(","));
    }

    private static void section(String title) {
        System.out.println("── " + title);
    }

    private static void eq(String what, String expected, String actual) {
        if (expected.equals(actual)) {
            passed++;
            System.out.printf("  ✓ %-46s = %s%n", what, actual);
        } else {
            failed++;
            System.out.printf("  ✗ %-46s 期望 %s，实际 %s%n", what, expected, actual);
        }
    }

    private SchedulerOrderSelfTest() {
    }
}
