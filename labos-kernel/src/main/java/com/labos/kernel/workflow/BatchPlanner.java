package com.labos.kernel.workflow;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批次切分 / 批变量注入 / 状态聚合（S3 分批执行的<b>纯逻辑</b>部分）。
 *
 * <p>不碰数据库、不碰执行器，所以这几条规则可以脱离库单测 —— 它们是
 * 评审里最容易"口径漂移"的地方（实施清单 §4.5 / §8.4 要求逐条可验）。
 *
 * <h3>口径（实施清单 §8 · D1/D2/D5/D6）</h3>
 * <ol>
 *   <li><b>切批</b>：{@code batchSplit} 为 NULL 或 {@code <= 0} = 不分批（返回空列表，
 *       调用方走非分批路径）；否则按入参顺序每 {@code batchSplit} 个切一批，
 *       <b>余数单独成一批</b>（96/24 = 4 批；100/24 = 5 批，最后一批 4 个）。</li>
 *   <li><b>样品快照</b>：入参顺序即快照顺序。调用方按
 *       {@code experiment_sample.sample_id} 升序传入，切出来的批次及其样品顺序
 *       就固定了；重跑只用快照，不再读 {@code experiment_sample}（样品集合不漂移）。</li>
 *   <li><b>空样品集合</b>：返回空列表 —— 没有样品就没有“批”。
 *       注意这与“不分批”（{@code batchSplit} 为空）是<b>两种不同情况</b>：
 *       前者是配置错误（配了分批却没有样品），由调用方
 *       {@link BatchOrchestrator} 明确报错，<b>不许</b>默默按不分批跑一遍；
 *       后者才是非分批路径。</li>
 *   <li><b>批变量</b>：{@code batchNo}（数字串）、{@code batchSamples}（条码逗号串）
 *       追加进本次 run 的变量；<b>不覆盖用户显式传入的同名变量</b>（D5）。</li>
 *   <li><b>状态聚合</b>：批次状态 -> 实验状态，见
 *       {@link #aggregateExperimentStatus(Collection)}（D6）。</li>
 * </ol>
 */
public final class BatchPlanner {

    /** 批次变量名：流程参数里用 {@code ${batchNo}} / {@code ${batchSamples}} 引用。 */
    public static final String VAR_BATCH_NO = "batchNo";
    public static final String VAR_BATCH_SAMPLES = "batchSamples";

    /** 样品快照：实验样品 id + 条码。 */
    public record Sample(long sampleId, String barcode) {
    }

    /** 一个逻辑批次：批次号从 1 开始，携带该批的样品快照。 */
    public record Batch(int batchNo, List<Sample> samples) {

        public Batch {
            samples = List.copyOf(samples);
        }

        public int sampleCount() {
            return samples.size();
        }
    }

    private BatchPlanner() {
    }

    /**
     * 按每批容量切批。
     *
     * @param samples    样品列表，<b>顺序即快照顺序</b>（调用方已按 sample_id 升序）
     * @param batchSplit 每批样品数；NULL / {@code <= 0} = 不分批
     * @return 批次列表（batchNo 从 1 递增）；不分批 / 空集合时返回空列表
     */
    public static List<Batch> split(List<Sample> samples, Integer batchSplit) {
        if (samples == null || samples.isEmpty()) {
            return List.of();
        }
        if (batchSplit == null || batchSplit <= 0) {
            return List.of();
        }
        List<Batch> batches = new ArrayList<>();
        int batchNo = 1;
        for (int start = 0; start < samples.size(); start += batchSplit) {
            int end = Math.min(samples.size(), start + batchSplit);
            batches.add(new Batch(batchNo++, samples.subList(start, end)));
        }
        return List.copyOf(batches);
    }

    /**
     * 归一化每批样品数（D13）：{@code null} / {@code <= 0} -> {@code null}（= 不分批），
     * 正数原值返回。
     *
     * <p>放在纯逻辑层是为了让"配置入口"（{@code PUT /batch-split}）与"读开关"
     * （{@link BatchOrchestrator#batchSplit}）用<b>同一条口径</b>：
     * 0 视为关闭分批，负数一律当没配 —— 否则会出现"写进库 0 / 读出来 null"
     * 两个口径，页面显示和实际执行对不上。
     */
    public static Integer normalizeSplit(Integer batchSplit) {
        if (batchSplit == null || batchSplit <= 0) {
            return null;
        }
        return batchSplit;
    }

    /**
     * 批变量注入（D5）：{@code batchNo} + {@code batchSamples}，用户显式变量优先。
     *
     * <p>“不覆盖用户显式传入的同名变量”：用户执行时传了 {@code batchNo=99}，
     * 就用 99 —— 我们只补默认上下文，不抢用户已明确表达的值。
     *
     * @param barcodesCsv   该批样品条码逗号串（快照顺序）；null 当空串
     * @param userOverrides 用户执行时传入的变量覆盖，可空
     */
    public static Map<String, String> batchVariables(int batchNo, String barcodesCsv,
                                                     Map<String, String> userOverrides) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put(VAR_BATCH_NO, Integer.toString(batchNo));
        vars.put(VAR_BATCH_SAMPLES, barcodesCsv == null ? "" : barcodesCsv);
        if (userOverrides != null) {
            // 与 ExperimentExecutor 的覆盖语义一致：null 值不算“显式传入”（执行器也会忽略）。
            userOverrides.forEach((k, v) -> {
                if (k != null && v != null) {
                    vars.put(k, v);
                }
            });
        }
        return vars;
    }

    /**
     * 状态聚合（D6）：批次状态集合 -> 实验状态。
     *
     * <p>规则按优先级：
     * <ol>
     *   <li>有 {@code running/queued} 批 -> {@code running}</li>
     *   <li>否则有 {@code paused} 批 -> {@code paused}</li>
     *   <li>否则全部 {@code completed} -> {@code finished}</li>
     *   <li>否则有 {@code failed/aborted/cancelled} 批 -> {@code failed}</li>
     *   <li>其余（只剩 pending 等未决状态）-> {@code null}，调用方<b>保持原状态不动</b></li>
     * </ol>
     *
     * <p>空集合 / null 也返回 {@code null}：没有批次就不是“全部完成”，不能聚合出 finished。
     *
     * @return 实验表要写的状态；{@code null} = 没有决定性状态，不要写
     */
    public static String aggregateExperimentStatus(Collection<String> batchStatuses) {
        if (batchStatuses == null || batchStatuses.isEmpty()) {
            return null;
        }
        boolean running = false;
        boolean paused = false;
        boolean failed = false;
        boolean allCompleted = true;
        for (String raw : batchStatuses) {
            String st = raw == null ? "pending" : raw;
            switch (st) {
                case "running", "queued" -> running = true;
                case "paused" -> paused = true;
                case "failed", "aborted", "cancelled" -> failed = true;
                default -> {
                    // completed 不置位；pending 之类“未决”状态让 allCompleted 落空
                }
            }
            if (!"completed".equals(st)) {
                allCompleted = false;
            }
        }
        if (running) {
            return "running";
        }
        if (paused) {
            return "paused";
        }
        if (allCompleted) {
            return "finished";
        }
        if (failed) {
            return "failed";
        }
        return null;
    }
}
