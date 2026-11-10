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

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(PreflightDemo.class);
    /** 兼容原有 System.out.printf 的格式化输出。 */
    private static void printf(String format, Object... args) {
        log.info(String.format(format, args));
    }

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

        log.info("=".repeat(84));
        log.info("  全量预检：全量流程能不能跑");
        log.info("=".repeat(84));
        printf("  流程总数 %s，耗时 %d ms%n%n", all.get("total"), ms);

        int total = (int) all.get("total");
        pct("可执行", (int) all.get("ok"), total);
        pct("可执行但有告警", (int) all.get("warned"), total);
        pct("阻塞（有 ERROR）", (int) all.get("blocked"), total);
        log.info("");

        @SuppressWarnings("unchecked")
        Map<String, Integer> byCode = (Map<String, Integer>) all.get("byCode");
        if (!byCode.isEmpty()) {
            log.info("  ── 问题分布 ──");
            byCode.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .forEach(e -> printf("  %-8s %-42s %5d 次%n",
                            e.getKey(), describe(e.getKey()), e.getValue()));
            log.info("");
        }

        @SuppressWarnings("unchecked")
        List<String> warnSamples = (List<String>) all.get("warnSamples");
        if (!warnSamples.isEmpty()) {
            log.info("  ── 告警样例 ──");
            warnSamples.forEach(x -> log.info("  " + x));
            log.info("");
        }

        @SuppressWarnings("unchecked")
        List<Long> blockedIds = (List<Long>) all.get("blockedIds");
        if (!blockedIds.isEmpty()) {
            log.info("  ── 阻塞流程样例（前 5）──");
            for (long id : blockedIds.subList(0, Math.min(5, blockedIds.size()))) {
                PreflightChecker.Report r = checker.check(id);
                printf("%n  #%d %s（任务 %d，可达 %d）%n",
                        id, r.workflowName(), r.taskCount(), r.reachableCount());
                for (PreflightChecker.Issue i : r.issues()) {
                    printf("    [%s] %s %s%n", i.level(), i.code(), i.message());
                }
            }
        }
    }

    private static void detail(PreflightChecker checker, long workflowId) throws Exception {
        PreflightChecker.Report r = checker.check(workflowId);
        log.info("=".repeat(84));
        printf("  流程 #%d %s%n", workflowId, r.workflowName());
        log.info("=".repeat(84));
        printf("  任务节点 %d，可达 %d，异或默认边 %d%n",
                r.taskCount(), r.reachableCount(), r.defaultEdges());
        printf("  ERROR %d，WARN %d，阻塞=%s%n%n",
                r.errors(), r.warnings(), r.blocking());

        if (r.issues().isEmpty()) {
            log.info("  ✔ 没有问题");
            return;
        }
        for (PreflightChecker.Issue i : r.issues()) {
            printf("  [%s] %-6s %s%n", i.level(), i.code(), i.message());
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
            case "PF-07" -> "流程没有可执行节点";
            case "PF-08" -> "流程没有 BPMN 图 / 解析失败";
            case "PF-09" -> "边的条件表达式语法有问题";
            case "PF-10" -> "绑定的设备在资源树里找不到（绑定不生效）";
            case "PF-11" -> "绑定的 id 是设备实例号（口径不一致，绑定不生效）";
            case "PF-12" -> "绑定的设备不在流程所属的岛内（执行时会被拒绝）";
            case "PF-13" -> "岛内没有具备该能力的设备（执行时该节点转人工）";
            case "PF-14" -> "读设备资源关系失败，本次跳过绑定与岛边界检查";
            default -> code;
        };
    }

    private static void pct(String label, int n, int total) {
        printf("  %-16s %6d 个  %5.1f%%%n", label, n,
                total == 0 ? 0 : n * 100.0 / total);
    }

    private PreflightDemo() {
    }
}
