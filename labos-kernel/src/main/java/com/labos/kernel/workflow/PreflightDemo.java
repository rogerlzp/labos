package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.util.List;
import java.util.Map;

/**
 * 全量预检：全量流程逐个干跑，统计"能不能跑"。
 *
 * <pre>
 *   java -cp "out:lib/postgresql-42.7.3.jar" com.labos.kernel.workflow.PreflightDemo
 *   java ... PreflightDemo 423              # 看单个流程的明细
 * </pre>
 */
public final class PreflightDemo {

    public static void main(String[] args) throws Exception {
        DbConfig db = DbConfig.fromEnv();
        PreflightChecker checker = new PreflightChecker(db, 1L);

        if (args.length > 0) {
            detail(checker, Long.parseLong(args[0]));
            return;
        }

        long t0 = System.currentTimeMillis();
        Map<String, Object> all = checker.checkAll();
        long ms = System.currentTimeMillis() - t0;

        System.out.println("=".repeat(84));
        System.out.println("  全量预检：全量流程能不能跑");
        System.out.println("=".repeat(84));
        System.out.printf("  流程总数 %s，耗时 %d ms%n%n", all.get("total"), ms);

        int total = (int) all.get("total");
        pct("可执行", (int) all.get("ok"), total);
        pct("可执行但有告警", (int) all.get("warned"), total);
        pct("阻塞（有 ERROR）", (int) all.get("blocked"), total);
        System.out.println();

        @SuppressWarnings("unchecked")
        Map<String, Integer> byCode = (Map<String, Integer>) all.get("byCode");
        if (!byCode.isEmpty()) {
            System.out.println("  ── 问题分布 ──");
            byCode.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .forEach(e -> System.out.printf("  %-8s %-42s %5d 次%n",
                            e.getKey(), describe(e.getKey()), e.getValue()));
            System.out.println();
        }

        @SuppressWarnings("unchecked")
        List<String> warnSamples = (List<String>) all.get("warnSamples");
        if (!warnSamples.isEmpty()) {
            System.out.println("  ── 告警样例 ──");
            warnSamples.forEach(x -> System.out.println("  " + x));
            System.out.println();
        }

        @SuppressWarnings("unchecked")
        List<Long> blockedIds = (List<Long>) all.get("blockedIds");
        if (!blockedIds.isEmpty()) {
            System.out.println("  ── 阻塞流程样例（前 5）──");
            for (long id : blockedIds.subList(0, Math.min(5, blockedIds.size()))) {
                PreflightChecker.Report r = checker.check(id);
                System.out.printf("%n  #%d %s（任务 %d，可达 %d）%n",
                        id, r.workflowName(), r.taskCount(), r.reachableCount());
                for (PreflightChecker.Issue i : r.issues()) {
                    System.out.printf("    [%s] %s %s%n", i.level(), i.code(), i.message());
                }
            }
        }
    }

    private static void detail(PreflightChecker checker, long workflowId) throws Exception {
        PreflightChecker.Report r = checker.check(workflowId);
        System.out.println("=".repeat(84));
        System.out.printf("  流程 #%d %s%n", workflowId, r.workflowName());
        System.out.println("=".repeat(84));
        System.out.printf("  任务节点 %d，可达 %d，异或默认边 %d%n",
                r.taskCount(), r.reachableCount(), r.defaultEdges());
        System.out.printf("  ERROR %d，WARN %d，阻塞=%s%n%n",
                r.errors(), r.warnings(), r.blocking());

        if (r.issues().isEmpty()) {
            System.out.println("  ✔ 没有问题");
            return;
        }
        for (PreflightChecker.Issue i : r.issues()) {
            System.out.printf("  [%s] %-6s %s%n", i.level(), i.code(), i.message());
        }
    }

    private static String describe(String code) {
        return switch (code) {
            case "PF-01" -> "节点无能力标注";
            case "PF-02" -> "能力无可用设备";
            case "PF-03" -> "节点永远不会被执行";
            case "PF-04" -> "异或缺条件且无默认边";
            case "PF-05" -> "并行汇合点入边到不齐";
            case "PF-06" -> "缺少开始事件";
            case "PF-07" -> "流程没有任务节点";
            case "PF-08" -> "流程没有 BPMN 图 / 解析失败";
            default -> code;
        };
    }

    private static void pct(String label, int n, int total) {
        System.out.printf("  %-16s %6d 个  %5.1f%%%n", label, n,
                total == 0 ? 0 : n * 100.0 / total);
    }

    private PreflightDemo() {
    }
}
