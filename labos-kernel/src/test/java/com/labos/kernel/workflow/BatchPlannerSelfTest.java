package com.labos.kernel.workflow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 批次切分 / 状态聚合自测（纯逻辑，不连库）。
 *
 * <p>对应实施清单 §4.5 / §8.4 必须逐条覆盖的规则：
 * <ol>
 *   <li>96 样品 / batch_split=24 -> 4 批；余数单独成批；</li>
 *   <li>batch_split 为 null / {@code <= 0} -> 不分批（返回空计划，调用方走老逻辑）；</li>
 *   <li>空样品集合 -> 空计划（由编排层报配置错误，不静默降级）；</li>
 *   <li>样品顺序 = 快照顺序（sample_id 升序由调用方保证）；</li>
 *   <li>D5 批变量注入不覆盖用户显式变量；</li>
 *   <li>D6 状态聚合四条优先级 + "没有决定性状态返回 null"。</li>
 * </ol>
 */
public final class BatchPlannerSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        section("1. 切批：容量 + 余数");
        splitSizes("96 样品 / 每批 24 -> 4 批", samples(96), 24,
                List.of(24, 24, 24, 24));
        splitSizes("100 样品 / 每批 24 -> 5 批（最后一批 4 个）", samples(100), 24,
                List.of(24, 24, 24, 24, 4));
        splitSizes("24 样品 / 每批 24 -> 1 批", samples(24), 24, List.of(24));
        splitSizes("23 样品 / 每批 24 -> 1 批（不够一整批也成批）", samples(23), 24, List.of(23));
        splitSizes("5 样品 / 每批 2 -> 3 批（余数 1）", samples(5), 2, List.of(2, 2, 1));

        section("2. 切批：batchNo 连续、样品顺序不漂移");
        batchNos("batchNo 从 1 连续递增", samples(100), 24, List.of(1, 2, 3, 4, 5));
        order("第一批 = 前 24 个样品（升序）", samples(96), 24, 1, 1, 24);
        order("最后一批从 73 开始（96/24=4）", samples(96), 24, 4, 73, 96);
        order("余数批是输入尾部那一段", samples(100), 24, 5, 97, 100);
        total("所有批样品数之和 = 输入数", samples(100), 24);
        barcodes("快照保留条码与顺序", samples(3), 2);

        section("3. 不分批 / 空集合");
        splitEmpty("batchSplit=null -> 不分批", samples(10), null);
        splitEmpty("batchSplit=0 -> 不分批", samples(10), 0);
        splitEmpty("batchSplit=-3 -> 不分批", samples(10), -3);
        splitEmpty("空样品集合 -> 空计划（配置错误由编排层报）", List.of(), 24);
        splitEmpty("null 样品集合 -> 空计划", null, 24);
        check("空集合 + null batchSplit 也是空计划",
                BatchPlanner.split(List.of(), null).isEmpty(),
                BatchPlanner.split(List.of(), null));

        section("4. D5 批变量注入");
        Map<String, String> v1 = BatchPlanner.batchVariables(2, "BC01,BC02", null);
        check("注入 batchNo", "2".equals(v1.get("batchNo")), v1);
        check("注入 batchSamples（逗号串）", "BC01,BC02".equals(v1.get("batchSamples")), v1);
        check("条码串为 null 时注入空串", "".equals(
                BatchPlanner.batchVariables(1, null, null).get("batchSamples")), null);
        Map<String, String> user = Map.of("batchNo", "99", "foo", "bar");
        Map<String, String> v2 = BatchPlanner.batchVariables(3, "BC09", user);
        check("用户显式 batchNo 不被覆盖", "99".equals(v2.get("batchNo")), v2);
        check("用户其它变量保留", "bar".equals(v2.get("foo")), v2);
        check("用户没覆盖的 batchSamples 仍然注入", "BC09".equals(v2.get("batchSamples")), v2);
        check("用户变量为 null 值不算显式传入", "4".equals(
                BatchPlanner.batchVariables(4, "B", null).get("batchNo")), null);

        section("5. D6 状态聚合");
        aggregate("全部 completed -> finished", List.of("completed", "completed"), "finished");
        aggregate("有 running -> running", List.of("completed", "running"), "running");
        aggregate("有 queued -> running", List.of("queued", "pending"), "running");
        aggregate("running 优先于 paused", List.of("paused", "running"), "running");
        aggregate("paused 优先于 failed", List.of("failed", "paused", "completed"), "paused");
        aggregate("completed + failed -> failed", List.of("completed", "failed"), "failed");
        aggregate("aborted 视为失败", List.of("aborted", "completed"), "failed");
        aggregate("只有 pending -> 不下结论（null）", List.of("pending", "pending"), null);
        aggregate("completed + pending -> 不下结论（null）",
                List.of("completed", "pending"), null);
        aggregate("空集合 -> null（不是 finished）", List.of(), null);
        aggregate("null -> null", null, null);

        section("6. D13 normalizeSplit：null / <=0 -> null，>0 原值");
        normalize("null -> null", null, null);
        normalize("0 -> null（0 视为关闭分批）", 0, null);
        normalize("-1 -> null（负数当没配）", -1, null);
        normalize("24 -> 24（正数原值）", 24, 24);

        System.out.printf("%n通过 %d, 失败 %d%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 造数

    /** 样品 id 从 1 开始，条码 BC01..（保持升序，模拟按 sample_id 升序传入）。 */
    private static List<BatchPlanner.Sample> samples(int n) {
        List<BatchPlanner.Sample> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            out.add(new BatchPlanner.Sample(i, String.format("BC%03d", i)));
        }
        return out;
    }

    // ---------------------------------------------------------------- 断言

    private static void splitSizes(String name, List<BatchPlanner.Sample> input,
                                   Integer split, List<Integer> expect) {
        List<BatchPlanner.Batch> batches = BatchPlanner.split(input, split);
        List<Integer> got = batches.stream().map(BatchPlanner.Batch::sampleCount).toList();
        check(name, expect.equals(got), got);
    }

    private static void splitEmpty(String name, List<BatchPlanner.Sample> input, Integer split) {
        List<BatchPlanner.Batch> got = BatchPlanner.split(input, split);
        check(name, got.isEmpty(), got.size());
    }

    private static void batchNos(String name, List<BatchPlanner.Sample> input, Integer split,
                                 List<Integer> expect) {
        List<Integer> got = BatchPlanner.split(input, split).stream()
                .map(BatchPlanner.Batch::batchNo).toList();
        check(name, expect.equals(got), got);
    }

    private static void order(String name, List<BatchPlanner.Sample> input, Integer split,
                              int batchNo, long firstId, long lastId) {
        List<BatchPlanner.Batch> batches = BatchPlanner.split(input, split);
        BatchPlanner.Batch b = batches.get(batchNo - 1);
        long f = b.samples().get(0).sampleId();
        long l = b.samples().get(b.samples().size() - 1).sampleId();
        check(name, f == firstId && l == lastId, "[" + f + ".." + l + "]");
    }

    private static void total(String name, List<BatchPlanner.Sample> input, Integer split) {
        int sum = BatchPlanner.split(input, split).stream()
                .mapToInt(BatchPlanner.Batch::sampleCount).sum();
        check(name, sum == input.size(), sum + "/" + input.size());
    }

    private static void barcodes(String name, List<BatchPlanner.Sample> input, Integer split) {
        BatchPlanner.Batch b = BatchPlanner.split(input, split).get(0);
        List<String> got = b.samples().stream().map(BatchPlanner.Sample::barcode).toList();
        check(name, Arrays.asList("BC001", "BC002").equals(got), got);
    }

    private static void normalize(String name, Integer input, Integer expect) {
        Integer got = BatchPlanner.normalizeSplit(input);
        check(name, Objects.equals(expect, got), got);
    }

    private static void aggregate(String name, List<String> statuses, String expect) {
        String got = BatchPlanner.aggregateExperimentStatus(statuses);
        check(name, Objects.equals(expect, got), got);
    }

    private static void check(String name, boolean ok, Object actual) {
        if (ok) {
            passed++;
            System.out.printf("  ✓ %-40s %s%n", name, actual);
        } else {
            failed++;
            System.out.printf("  ✗ %-40s 实际 %s%n", name, actual);
        }
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("── " + title + " ──");
    }

    private BatchPlannerSelfTest() {
    }
}
