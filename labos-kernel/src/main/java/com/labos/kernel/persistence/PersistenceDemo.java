package com.labos.kernel.persistence;

import com.labos.kernel.engine.EngineDemo;
import com.labos.kernel.engine.NodeProgress;
import com.labos.kernel.engine.NodeStatus;
import com.labos.kernel.engine.RecoverableNode;
import com.labos.kernel.engine.RunProgress;
import com.labos.kernel.engine.RunStateMachine;
import com.labos.kernel.engine.SimulatedBus;
import com.labos.kernel.engine.Sleeper;
import com.labos.kernel.recovery.RecoveryDecider;
import com.labos.kernel.recovery.RecoveryPolicy;
import com.labos.kernel.recovery.RecoveryPolicyResolver;
import com.labos.kernel.recovery.RecoveryScope;
import com.labos.kernel.recovery.RecoveryStrategy;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 端到端演示：策略从数据库加载 → 执行 → 事件落库 → 转人工落库 → 查询验证。
 */
public final class PersistenceDemo {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(PersistenceDemo.class);
    /** 兼容原有 System.out.printf 的格式化输出。 */
    private static void printf(String format, Object... args) {
        log.info(String.format(format, args));
    }

    private static final long TENANT = 1L;

    public static void main(String[] args) throws Exception {
        DbConfig db = DbConfig.fromEnv();
        log.info("目标库: " + db.url());
        log.info("");

        // ---------- 1. 从数据库加载策略 ----------
        JdbcRecoveryPolicyRepository repo = new JdbcRecoveryPolicyRepository(db, TENANT);
        List<RecoveryPolicy> policies = repo.loadAll();

        log.info("从 recovery_policy 加载 " + policies.size() + " 条策略：");
        printf("  %-14s %-10s %-10s %-6s %-6s %s%n",
                "错误码", "层级", "策略", "次数", "优先级", "退避");
        log.info("  " + "-".repeat(72));
        for (RecoveryPolicy p : policies) {
            printf("  %-14s %-10s %-10s %-6d %-6d %s%n",
                    p.errorCode() == null ? "*" : p.errorCode(),
                    p.scope() == null ? "*" : p.scope(),
                    p.strategy(), p.maxAttempts(), p.priority(),
                    p.backoff() + "/" + p.baseDelay().toMillis() + "ms");
        }
        log.info("");

        // ---------- 2. 建一条 run ----------
        long runId = createRun("DEMO");
        log.info("已创建 run id=" + runId + "（run_no 前缀 DEMO-）");
        log.info("");

        // ---------- 3. 执行，事件实时落库 ----------
        RecoveryDecider decider = new RecoveryDecider(new RecoveryPolicyResolver(policies));

        List<RecoverableNode> nodes = List.of(
                RecoverableNode.of(1, "Transfer", "TRANSFER", 200383L, List.of(200383L, 200387L)),
                RecoverableNode.of(2, "Scan", "SCAN", 200392L, List.of(200392L)),
                RecoverableNode.of(3, "Centrifuge", "CENTRIFUGE", 200401L, List.of(200401L)),
                RecoverableNode.of(4, "Read", "READ_PLATE", 200388L, List.of(200388L)));

        SimulatedBus bus = new SimulatedBus()
                .dead(200383L, "TRANSFER", "EC-08-0001", RecoveryScope.NODE)      // -> REASSIGN -> 成功
                .dead(200392L, "SCAN", "EC-08-1005", RecoveryScope.NODE)          // -> SKIP  (留痕但不告警)
                .dead(200401L, "CENTRIFUGE", "EC-05-1006", RecoveryScope.COMMAND); // -> RETRY 3 次 -> MANUAL

        RunProgress progress;
        int eventsWritten;
        int eventsFailed;
        List<Long> slept = new ArrayList<>();

        try (JdbcEventStore store = new JdbcEventStore(db, TENANT, runId, "demo-trace", 50)) {
            RunStateMachine sm = new RunStateMachine(decider, store,
                    Sleeper.recording(slept), java.time.Clock.systemUTC());
            progress = sm.executeAll(nodes, bus);
            store.flush();
            eventsWritten = store.written();
            eventsFailed = store.failedCount();
        }

        log.info("执行结果: " + progress.summary());
        log.info("事件落库: 成功 " + eventsWritten + " 条，失败 " + eventsFailed + " 条");
        log.info("");

        // ---------- 4. SKIPPED / MANUAL / ABORTED 三种结局都留痕 ----------
        JdbcErrorOccurrenceStore errStore = new JdbcErrorOccurrenceStore(db, TENANT, repo);
        int skipped = 0, manual = 0, aborted = 0;
        for (NodeProgress np : progress.all()) {
            if (!JdbcErrorOccurrenceStore.shouldRecord(np.status())) {
                continue;
            }
            String severity = switch (np.status()) {
                case SKIPPED -> "info";     // 留痕但不告警
                case ABORTED -> "error";
                default -> "warn";
            };
            errStore.record(new Occurrence(
                    runId, np.nodeId(), np.currentResourceId(),
                    np.lastErrorCode() == null ? "EC-UNKNOWN" : np.lastErrorCode(),
                    RecoveryScope.NODE, np.attempt(),
                    RecoveryStrategy.MANUAL, np.status().name(),
                    np.lastReason(),
                    Jsons.obj("failedResources", np.failedResourceIds().toString(),
                            "attempt", np.attempt()),
                    severity));
            switch (np.status()) {
                case SKIPPED -> skipped++;
                case MANUAL -> manual++;
                case ABORTED -> aborted++;
                default -> { }
            }
        }
        printf("错误留痕: 跳过 %d 条(info) / 转人工 %d 条(warn) / 终止 %d 条(error)%n",
                skipped, manual, aborted);
        log.info("  ↑ 跳过的也留痕，但不告警——这是对「91% 直接 Ignore、什么都不留」的修正");
        log.info("");

        // ---------- 5. 查询验证 ----------
        verify(runId);
    }

    // ---------------------------------------------------------------- DB

    /**
     * 演示建 run（阶段3第22批：SQL 收编进 RunMapper/OpsMapper；无容器入口，
     * {@link Mappers#get} 自动以 {@link DbConfig#standaloneDataSource()} 初始化）。
     */
    private static long createRun(String prefix) {
        String runNo = prefix + "-" + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
        return Mappers.get(RunMapper.class).insertDemoRun(TENANT, runNo);
    }

    private static void verify(long runId) {
        log.info("=".repeat(80));
        log.info("  数据库验证（run_id = " + runId + "）");
        log.info("=".repeat(80));
        RunMapper runMapper = Mappers.get(RunMapper.class);

        // 事件分布
        log.info("\n[run_event] 事件类型分布：");
        int total = 0;
        for (Map<String, Object> row : runMapper.selectDemoEventTypeCounts(runId)) {
            int n = ((Number) row.get("n")).intValue();
            total += n;
            printf("  %-20s %d%n", row.get("eventType"), n);
        }
        log.info("  " + "-".repeat(28));
        printf("  %-20s %d%n", "合计", total);

        // 事件明细（前 8 条）
        log.info("\n[run_event] 事件明细（前 8 条）：");
        for (Map<String, Object> row : runMapper.selectDemoEventDetails(runId)) {
            printf("  %-20s %-22s %s%n",
                    String.valueOf(row.get("occurredAt")).substring(11, 19),
                    row.get("eventType"),
                    truncate((String) row.get("payload"), 90));
        }

        // 错误记录
        log.info("\n[error_occurrence] 转人工记录：");
        List<Map<String, Object>> occurrences =
                Mappers.get(OpsMapper.class).selectErrorOccurrencesByRun(runId);
        for (Map<String, Object> row : occurrences) {
            printf("  node=%d %-22s attempt=%d outcome=%s severity=%s%n",
                    ((Number) row.get("runNodeId")).longValue(),
                    row.get("fingerprint"),
                    ((Number) row.get("attempt")).intValue(),
                    row.get("outcome"), row.get("severity"));
            log.info("      原因: " + truncate((String) row.get("message"), 100));
        }
        if (occurrences.isEmpty()) {
            log.info("  （无）");
        }
    }

    private static String truncate(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    private PersistenceDemo() {
    }
}
