package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * R4 · 超时先 QueryStatus、后落终态的连库判别性自测。
 *
 * <p>覆盖设备明确完成、明确失败、仍在执行、查不到、无查询能力、非陈旧六类；
 * 同时断言查询确实发生、没有借机重发命令，防止「只把批量 UNKNOWN 换个名字」的假实现。
 */
public final class CommandQueryReconcileDbSelfTest {

    private static int pass;
    private static int fail;
    private static DbConfig db;
    private static final long TENANT = 1L;
    private static final String PREFIX = "SELF-QUERY-" + System.currentTimeMillis() + "-";
    private static final long DEVICE = 990101L;

    public static void main(String[] args) throws Exception {
        com.labos.kernel.support.TestSpringSupport.db();
        db = DbConfig.fromEnv();
        cleanup();
        try {
            completedIsAcked();
            failedIsFailed();
            inProgressStaysOpen();
            notFoundBecomesUnknown();
            unavailableQueryBecomesUnknown();
            freshCommandIsNotQueried();
            virtualDriverQueryMatchesExecutionCache();
        } finally {
            cleanup();
        }
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void completedIsAcked() throws Exception {
        section("1. 查询确认完成 → ACKED/DONE");
        QueryStub stub = new QueryStub();
        String commandId = PREFIX + "cmd-done";
        stub.answer(commandId, CommandQueryResult.completed("设备回报 completed"));
        long id = staleEntry("done", commandId);

        CommandLedger.reconcileWithQuery(db, TENANT, 10, registry(stub));
        eq("state=ACKED", "ACKED", stateOf(id));
        eq("phase=DONE", "DONE", phaseOf(id));
        yes("确实按 commandId 查询了设备", stub.queries.contains(commandId));
        yes("成功证据来自 QueryStatus", errorOf(id).contains("QueryStatus=COMPLETED"));
        yes("没有重发物理命令", stub.executeCalls == 0);
    }

    private static void failedIsFailed() throws Exception {
        section("2. 查询确认失败 → FAILED/DONE");
        QueryStub stub = new QueryStub();
        String commandId = PREFIX + "cmd-failed";
        stub.answer(commandId, CommandQueryResult.failed("设备回报 failed"));
        long id = staleEntry("failed", commandId);

        CommandLedger.reconcileWithQuery(db, TENANT, 10, registry(stub));
        eq("state=FAILED", "FAILED", stateOf(id));
        eq("phase=DONE", "DONE", phaseOf(id));
        yes("失败证据来自 QueryStatus", errorOf(id).contains("QueryStatus=FAILED"));
        yes("失败不是 UNKNOWN", !"UNKNOWN".equals(stateOf(id)));
    }

    private static void inProgressStaysOpen() throws Exception {
        section("3. 设备仍在执行 → 保持在途，不 UNKNOWN");
        QueryStub stub = new QueryStub();
        String commandId = PREFIX + "cmd-running";
        stub.answer(commandId, CommandQueryResult.inProgress("设备回报 executing"));
        long id = staleEntry("running", commandId);

        CommandLedger.reconcileWithQuery(db, TENANT, 10, registry(stub));
        eq("state 仍 DISPATCH_STARTED", "DISPATCH_STARTED", stateOf(id));
        eq("phase 推进 EXECUTING", "EXECUTING", phaseOf(id));
        yes("没有误落 UNKNOWN", !"UNKNOWN".equals(stateOf(id)));
        yes("没有重发物理命令", stub.executeCalls == 0);
    }

    private static void notFoundBecomesUnknown() throws Exception {
        section("4. 查询后仍无法确认 → UNKNOWN");
        QueryStub stub = new QueryStub();
        String commandId = PREFIX + "cmd-not-found";
        stub.answer(commandId, CommandQueryResult.notFound("设备无此命令记录"));
        long id = staleEntry("not-found", commandId);

        CommandLedger.reconcileWithQuery(db, TENANT, 10, registry(stub));
        eq("state=UNKNOWN", "UNKNOWN", stateOf(id));
        eq("phase=UNKNOWN", "UNKNOWN", phaseOf(id));
        yes("先查询过才 UNKNOWN", stub.queries.contains(commandId));
        yes("保留查询证据", errorOf(id).contains("QueryStatus=NOT_FOUND"));
    }

    private static void unavailableQueryBecomesUnknown() throws Exception {
        section("5. 无查询能力/无响应 → 查询尝试后 UNKNOWN");
        String commandId = PREFIX + "cmd-no-registry";
        long id = staleEntry("no-registry", commandId);
        CommandLedger.reconcileWithQuery(db, TENANT, 10, null);
        eq("state=UNKNOWN", "UNKNOWN", stateOf(id));
        yes("明确记录无法查询而非设备未动作", errorOf(id).contains("无驱动注册表"));
    }

    private static void freshCommandIsNotQueried() throws Exception {
        section("6. 非陈旧命令不查询、不误标");
        QueryStub stub = new QueryStub();
        String commandId = PREFIX + "cmd-fresh";
        stub.answer(commandId, CommandQueryResult.completed("不应被读取"));
        CommandLedger.Entry e = CommandLedger.begin(db, TENANT, PREFIX + "fresh",
                -997101L, -997102L, 1, 1, DEVICE, "Pipette", commandId, "trace-fresh");

        CommandLedger.reconcileWithQuery(db, TENANT, 10, registry(stub));
        eq("state 仍 DISPATCH_STARTED", "DISPATCH_STARTED", stateOf(e.id()));
        eq("phase 仍 PENDING", "PENDING", phaseOf(e.id()));
        yes("未陈旧就没有发起查询", !stub.queries.contains(commandId));
    }

    private static void virtualDriverQueryMatchesExecutionCache() {
        section("7. VirtualDriver 查询与真实执行缓存一致");
        VirtualDriver driver = new VirtualDriver("SELF-VIRTUAL-QUERY", "memory://virtual",
                "memory", Map.of(DEVICE, Set.of("TEST")));
        String commandId = PREFIX + "cmd-virtual";
        driver.execute(new DriverCommand(commandId, DEVICE, "Pipette", Map.of(), 1000, "trace-v"));
        eq("已执行命令查询为 COMPLETED", "COMPLETED",
                driver.queryStatus(commandId).state().name());
        eq("未知命令查询为 NOT_FOUND（账本随后落 UNKNOWN）", "NOT_FOUND",
                driver.queryStatus(PREFIX + "missing").state().name());
        driver.setHealthy(false);
        eq("驱动不健康时查询为 UNKNOWN", "UNKNOWN",
                driver.queryStatus(commandId).state().name());
    }

    // ================================================================ 造数 / 查询

    private static long staleEntry(String tag, String commandId) throws Exception {
        CommandLedger.Entry e = CommandLedger.begin(db, TENANT, PREFIX + tag,
                -997101L, -997102L, 1, 1, DEVICE, "Pipette", commandId, "trace-" + tag);
        exec("UPDATE command_ledger SET created_at = now() - interval '1 hour' WHERE id = "
                + e.id());
        return e.id();
    }

    private static DriverRegistry registry(QueryStub stub) {
        DriverRegistry registry = new DriverRegistry();
        registry.register(stub);
        return registry;
    }

    private static String stateOf(long id) throws Exception {
        return one("SELECT state FROM command_ledger WHERE id = " + id);
    }

    private static String phaseOf(long id) throws Exception {
        return one("SELECT phase FROM command_ledger WHERE id = " + id);
    }

    private static String errorOf(long id) throws Exception {
        String value = one("SELECT coalesce(error_message, '') FROM command_ledger WHERE id = " + id);
        return value == null ? "" : value;
    }

    private static String one(String sql) throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static void exec(String sql) throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    private static void cleanup() {
        try {
            exec("DELETE FROM command_outbox WHERE tenant_id = 1 AND ledger_id IN ("
                    + "SELECT id FROM command_ledger WHERE tenant_id = 1 "
                    + "AND idempotency_key LIKE '" + PREFIX + "%')");
            exec("DELETE FROM command_ledger WHERE tenant_id = 1 "
                    + "AND idempotency_key LIKE '" + PREFIX + "%'");
        } catch (Exception ignored) {
            // 清理失败不影响结论
        }
    }

    // ================================================================ 查询桩

    private static final class QueryStub implements DeviceDriver {
        private final DriverDescriptor descriptor;
        private final Map<String, CommandQueryResult> answers = new LinkedHashMap<>();
        private final List<String> queries = new ArrayList<>();
        private int executeCalls;

        private QueryStub() {
            Map<Long, Set<String>> devices = Map.of(DEVICE, Set.of("TEST"));
            this.descriptor = new DriverDescriptor("SELF-QUERY", "memory://query", "memory",
                    "stub", Set.of("TEST"), devices, devices.keySet(), Map.of());
        }

        private void answer(String commandId, CommandQueryResult result) {
            answers.put(commandId, result);
        }

        @Override
        public DriverDescriptor describe() {
            return descriptor;
        }

        @Override
        public DriverResult execute(DriverCommand command) {
            executeCalls++;
            return DriverResult.rejected(command.commandId(), "EC-99-0002", "对账不允许重发");
        }

        @Override
        public CommandQueryResult queryStatus(String commandId) {
            queries.add(commandId);
            return answers.getOrDefault(commandId,
                    CommandQueryResult.unknown("查询桩无预设答案"));
        }

        @Override
        public boolean healthy() {
            return true;
        }
    }

    // ================================================================ 输出

    private static void section(String title) {
        System.out.println("── " + title + " ──");
    }

    private static void yes(String name, boolean ok) {
        check(name, ok, ok);
    }

    private static void eq(String name, String expect, String actual) {
        check(name, expect == null ? actual == null : expect.equals(actual), actual);
    }

    private static void check(String name, boolean ok, Object actual) {
        if (ok) {
            pass++;
            System.out.println("  ✓ " + name);
        } else {
            fail++;
            System.out.println("  ✗ " + name + "（实际：" + actual + "）");
        }
    }

    private CommandQueryReconcileDbSelfTest() {
    }
}
