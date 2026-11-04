package com.labos.kernel.workflow;

import java.util.List;
import java.util.Map;

/**
 * 实验编排偏序求序自测（S2-A 迁移边界）。
 *
 * <p>锁住两件事：
 * <ol>
 *   <li>没有边（老数据 / 线性实验）时，求序结果必须与按 {@code seq} 升序完全一致；</li>
 *   <li>有边时按前驱 -> 后继推进，同层按 {@code seq} 稳定排序；有环要明确拒绝而不是猜。</li>
 * </ol>
 *
 * <p>当前口径是 <b>S2-A：只承诺偏序、物理串行</b>，不承诺墙钟并发。
 */
public final class ExperimentOrderSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        section("1. 线性 / 无边（老数据）");
        order("无边：按 seq 升序（与改动前一致）",
                List.of(1L, 2L, 3L),
                Map.of(),
                Map.of(1L, 1, 2L, 2, 3L, 3),
                List.of(1L, 2L, 3L));

        order("线性链 A->B->C 按链序",
                List.of(1L, 2L, 3L),
                Map.of(1L, List.of(2L), 2L, List.of(3L)),
                Map.of(1L, 1, 2L, 2, 3L, 3),
                List.of(1L, 2L, 3L));

        section("2. 偏序（同层按 seq）");
        order("菱形：A->B, A->C, B->D, C->D",
                List.of(1L, 2L, 3L, 4L),
                Map.of(1L, List.of(2L, 3L), 2L, List.of(4L), 3L, List.of(4L)),
                Map.of(1L, 1, 2L, 2, 3L, 3, 4L, 4),
                List.of(1L, 2L, 3L, 4L));

        order("同层竞争按 seq：id=11(seq=2) 与 id=22(seq=1) 各自可执行 -> 先 22",
                List.of(11L, 22L),
                Map.of(),
                Map.of(11L, 2, 22L, 1),
                List.of(22L, 11L));

        section("3. 必须拒绝的编排");
        throwsOn("存在环 A->B->A",
                List.of(1L, 2L),
                Map.of(1L, List.of(2L), 2L, List.of(1L)),
                Map.of(1L, 1, 2L, 2),
                "存在环");

        throwsOn("边指向不存在的流程",
                List.of(1L),
                Map.of(1L, List.of(99L)),
                Map.of(1L, 1),
                "不存在的流程");

        System.out.printf("%n通过 %d, 失败 %d%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void order(String name, List<Long> ids, Map<Long, List<Long>> succ,
                              Map<Long, Integer> seqOrder, List<Long> expect) {
        try {
            List<Long> got = ExperimentExecutor.topologicalOrder(ids, succ, seqOrder);
            if (expect.equals(got)) {
                passed++;
                System.out.printf("  ✓ %-28s %s%n", name, got);
            } else {
                failed++;
                System.out.printf("  ✗ %-28s 期望 %s，实际 %s%n", name, expect, got);
            }
        } catch (RuntimeException e) {
            failed++;
            System.out.printf("  ✗ %-28s 不应抛异常：%s%n", name, firstLine(e.getMessage()));
        }
    }

    private static void throwsOn(String name, List<Long> ids, Map<Long, List<Long>> succ,
                                 Map<Long, Integer> seqOrder, String expectedPart) {
        try {
            List<Long> got = ExperimentExecutor.topologicalOrder(ids, succ, seqOrder);
            failed++;
            System.out.printf("  ✗ %-28s 应拒绝，却返回 %s%n", name, got);
        } catch (IllegalArgumentException e) {
            String msg = firstLine(e.getMessage());
            if (msg.contains(expectedPart)) {
                passed++;
                System.out.printf("  ✓ %-28s 拒绝：%s%n", name, msg);
            } else {
                failed++;
                System.out.printf("  ✗ %-28s 拒绝原因不符：%s%n", name, msg);
            }
        }
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int i = s.indexOf('\n');
        return i >= 0 ? s.substring(0, i) : s;
    }

    private static void section(String title) {
        System.out.println("\n── " + title + " ──");
    }

    private ExperimentOrderSelfTest() {
    }
}
