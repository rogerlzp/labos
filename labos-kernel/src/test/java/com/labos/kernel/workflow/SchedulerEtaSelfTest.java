package com.labos.kernel.workflow;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 实验预计等待 纯逻辑自测（设计文档 §7.2，不连库）。
 *
 * <p>锁住 {@link SchedulerEta#estimateWait} 的边界，这些边界错了比"估不准"更严重：
 * <ul>
 *   <li><b>不适用必须是 null 而不是 0</b>：返回 0 会让值班以为"马上开始"，
 *       实际它可能压根不在调度队列里（已完成/未提交）；</li>
 *   <li><b>运行中必须是 0</b>：前端据此显示「运行中」而不是一个等待数；</li>
 *   <li><b>负数是脏数据</b>（时钟回拨 / estimate_sec 被改成负数）：按 0 钳制，
 *       负数等待时间没有任何解释力；</li>
 *   <li><b>null 容器当空</b>：调用方从快照里取，可能拿到 null，不能 NPE。</li>
 * </ul>
 *
 * <p>公式：{@code max(0, 岛持有者剩余) + Σ(max(0, 同岛前面实验预计耗时))}。
 */
public final class SchedulerEtaSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        section("1. 运行中 -> 0（等待信息不再适用）");
        waitCase("运行中、有队列位次：0", true, 3, 900, List.of(600L), 0L);
        waitCase("运行中、无队列位次：0", true, null, 0, null, 0L);

        section("2. 不在调度队列 -> null（不适用，不是 0）");
        waitCase("不在队列：null", false, null, 900, List.of(600L), null);
        waitCase("不在队列、手上也没活：null", false, null, 0, null, null);

        section("3. 队列中但无阻塞 -> 0");
        waitCase("队首、岛空：0", false, 1, 0, List.of(), 0L);
        waitCase("队首、ahead=null：0", false, 1, 0, null, 0L);
        waitCase("队首、ahead 全 0：0", false, 1, 0, List.of(0L, 0L), 0L);

        section("4. 持有者剩余 + 前面实验耗时求和");
        waitCase("持有者 900 + 前面 1 个 900", false, 2, 900, List.of(900L), 1800L);
        waitCase("持有者 300 + 前面 120+780", false, 3, 300, List.of(120L, 780L), 1200L);
        waitCase("持有者 0 + 前面 3 个", false, 4, 0, List.of(60L, 60L, 60L), 180L);
        waitCase("无持有者 + 前面 1 个", false, 2, 0, List.of(4635L), 4635L);

        section("5. 负数钳 0（脏数据不许算出负等待）");
        waitCase("持有者 -100、ahead -50：0", false, 1, -100, List.of(-50L), 0L);
        waitCase("持有者 50、ahead -100：50", false, 2, 50, List.of(-100L), 50L);
        waitCase("持有者 -100、ahead 70：70", false, 2, -100, List.of(70L), 70L);

        section("6. 容器 null/空/含 null 元素");
        waitCase("ahead=null 只剩持有者", false, 2, 120, null, 120L);
        waitCase("ahead=[] 只剩持有者", false, 2, 120, List.of(), 120L);
        waitCase("ahead 含 null 元素按 0", false, 2, 120, Arrays.asList(100L, null, 50L), 270L);

        System.out.printf("%n通过 %d, 失败 %d%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void waitCase(String name, boolean running, Integer queuePosition,
                                 long holderRemain, List<Long> ahead, Long expect) {
        Long got = SchedulerEta.estimateWait(running, queuePosition, holderRemain, ahead);
        if (Objects.equals(expect, got)) {
            passed++;
            System.out.printf("  ✓ %-38s %s%n", name, got);
        } else {
            failed++;
            System.out.printf("  ✗ %-38s 期望 %s，实际 %s%n", name, expect, got);
        }
    }

    private static void section(String title) {
        System.out.println("\n── " + title + " ──");
    }

    private SchedulerEtaSelfTest() {
    }
}
