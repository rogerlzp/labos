package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * 设备命令账本单测（P-07 第一切片，需要真实库）。
 *
 * <p>验证四条不能商量的规则：
 * <ol>
 *   <li>同 idempotency_key 只落一行，第二次 begin 返回 created=false（禁止重复下发）；</li>
 *   <li>finish 后状态 ACKED / FAILED，且重放 begin 看到终态；</li>
 *   <li>陈旧 DISPATCH_STARTED 启动对账标 UNKNOWN；</li>
 *   <li>非陈旧 DISPATCH_STARTED 不被误标（不能把正在跑的命令当成崩溃残留）。</li>
 * </ol>
 */
public final class CommandLedgerDbSelfTest {

    private static int pass;
    private static int fail;
    private static DbConfig db;
    private static final long TENANT = 1L;
    private static final String PREFIX = "SELF-CL-" + System.currentTimeMillis() + "-";

    public static void main(String[] args) throws Exception {
        // 触发自测容器：绑定 Mappers（非 Bean 工具类通过 Mappers.get 取 Mapper），
        // 并让 DbConfig 与容器共用同一个 DataSource。
        com.labos.kernel.support.TestSpringSupport.db();
        db = DbConfig.fromEnv();
        cleanup();
        try {
            testBeginDuplicateAndAck();
            testFailureState();
            testAbortRecord();
            testReconcileStale();
            testCrashPointMatrix();
        } finally {
            cleanup();
        }
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void testBeginDuplicateAndAck() throws Exception {
        String key = PREFIX + "ack";
        CommandLedger.Entry first = begin(key, "cmd-ack");
        check("派发前账本创建成功", first.created() && "DISPATCH_STARTED".equals(first.state()),
                first.state());
        check("账本同事务写入 Outbox（PENDING）",
                CommandOutbox.pendingForLedger(db, TENANT, first.id()) == 1,
                CommandOutbox.pendingForLedger(db, TENANT, first.id()));
        int dispatched = CommandOutbox.dispatchPending(db, TENANT, 20);
        check("命令 Outbox 派发成功（" + dispatched + " 条）", dispatched >= 1, dispatched);
        check("派发后本账本 Outbox 已 DELIVERED",
                CommandOutbox.pendingForLedger(db, TENANT, first.id()) == 0,
                CommandOutbox.pendingForLedger(db, TENANT, first.id()));

        long outboxId = outboxIdFor(first.id());
        check("可定位 Outbox 事件", outboxId > 0, outboxId);
        check("已派发事件可重放（仅外部投递，不重发物理命令）",
                CommandOutbox.replay(db, TENANT, outboxId), true);
        check("重放后回到 PENDING",
                CommandOutbox.pendingForLedger(db, TENANT, first.id()) == 1,
                CommandOutbox.pendingForLedger(db, TENANT, first.id()));
        CommandOutbox.dispatchPending(db, TENANT, 20);
        check("重放后可再次派发（幂等标记）",
                CommandOutbox.pendingForLedger(db, TENANT, first.id()) == 0,
                CommandOutbox.pendingForLedger(db, TENANT, first.id()));

        CommandLedger.Entry second = begin(key, "cmd-ack");
        check("同一 commandId 不重复落账（只 1 行）",
                !second.created() && second.id() == first.id(), second);
        check("库里确实只有 1 行", rowCount(key) == 1, rowCount(key));

        CommandLedger.finish(db, first.id(), true, null, null, "cmd-ack");
        check("回包成功后状态 ACKED", "ACKED".equals(CommandLedger.stateOf(db, TENANT, key)),
                CommandLedger.stateOf(db, TENANT, key));
        CommandLedger.Entry replay = begin(key, "cmd-ack");
        check("重放 begin 看到终态 ACKED 并拒绝再发",
                !replay.created() && "ACKED".equals(replay.state()), replay.state());
    }

    private static void testFailureState() throws Exception {
        String key = PREFIX + "fail";
        CommandLedger.Entry e = begin(key, "cmd-fail");
        CommandLedger.finish(db, e.id(), false, "EC-99-0005", "设备命令被暂停中止", "cmd-fail");
        check("失败状态落 FAILED", "FAILED".equals(CommandLedger.stateOf(db, TENANT, key)),
                CommandLedger.stateOf(db, TENANT, key));
        check("失败错误码可追溯", "EC-99-0005".equals(errorCodeOf(key)), errorCodeOf(key));
    }

    /**
     * B-04：中止结果必须落成可查的结构化状态，而不是一行日志。
     *
     * <p>这一列的存在意义就是事后能回答「设备到底认没认」——
     * 只有 PHYSICALLY_STOPPED 允许把命令判成 aborted。
     */
    private static void testAbortRecord() throws Exception {
        String key = PREFIX + "abort";
        CommandLedger.Entry e = begin(key, "cmd-abort");

        CommandLedger.recordAbort(db, e.id(), AbortResult.requested("已发出停止请求，未确认"));
        check("中止请求落账 REQUESTED", "REQUESTED".equals(abortStateOf(e.id())),
                abortStateOf(e.id()));
        check("中止请求带时间戳", abortAtOf(e.id()) != null, abortAtOf(e.id()));

        CommandLedger.recordAbort(db, e.id(), AbortResult.stopped("设备确认动作已停止"));
        check("设备确认后落账 PHYSICALLY_STOPPED",
                "PHYSICALLY_STOPPED".equals(abortStateOf(e.id())), abortStateOf(e.id()));

        // 旁路写：记不上不能抛，也不能把已有记录清掉
        CommandLedger.recordAbort(db, 0L, AbortResult.stopped("no ledger"));
        CommandLedger.recordAbort(db, e.id(), null);
        check("空账本号/null 结果是 no-op（不抛、不清旧值）",
                "PHYSICALLY_STOPPED".equals(abortStateOf(e.id())), abortStateOf(e.id()));

        // 未确认的中止必须能被 Index 条件捞出来（人工核验入口）
        CommandLedger.Entry e2 = begin(PREFIX + "abort2", "cmd-abort2");
        CommandLedger.recordAbort(db, e2.id(), AbortResult.unknown("断链，结果不可知"));
        check("未确认的中止可被人工核验查询命中",
                "UNKNOWN".equals(abortStateOf(e2.id())), abortStateOf(e2.id()));

        // 核验队列只收「没确认停」的：设备已确认的行不能混进来，
        // 否则人工会去复核一条本来就没问题的命令。
        java.util.List<CommandLedger.AbortPending> pending =
                CommandLedger.listUnconfirmedAbort(db, TENANT, 200);
        boolean hasUnconfirmed = pending.stream().anyMatch(x -> x.id() == e2.id());
        boolean hasConfirmed = pending.stream().anyMatch(x -> x.id() == e.id());
        check("核验队列含未确认的中止", hasUnconfirmed, e2.id());
        check("核验队列不含设备已确认停止的行", !hasConfirmed, e.id());
    }

    private static String abortStateOf(long ledgerId) throws Exception {
        return one("SELECT coalesce(abort_state,'-') FROM command_ledger WHERE id = " + ledgerId);
    }

    private static String abortAtOf(long ledgerId) throws Exception {
        return one("SELECT abort_at::text FROM command_ledger WHERE id = " + ledgerId);
    }

    private static String one(String sql) throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static void testReconcileStale() throws Exception {
        String staleKey = PREFIX + "stale";
        CommandLedger.Entry stale = begin(staleKey, "cmd-stale");
        exec("UPDATE command_ledger SET created_at = now() - interval '1 hour', "
                + "updated_at = now() - interval '1 hour' WHERE id = " + stale.id());
        int marked = CommandLedger.reconcileOnStartup(db, TENANT, CommandLedger.STALE_MINUTES);
        check("启动对账把陈旧派发标 UNKNOWN（本次 " + marked + " 条）", marked >= 1, marked);
        check("陈旧行状态 UNKNOWN", "UNKNOWN".equals(CommandLedger.stateOf(db, TENANT, staleKey)),
                CommandLedger.stateOf(db, TENANT, staleKey));

        // P-07.2：UNKNOWN 人工处置入口
        boolean listed = false;
        for (CommandLedger.Unknown u : CommandLedger.listUnknown(db, TENANT, 200)) {
            if (staleKey.equals(u.key())) {
                listed = true;
                check("UNKNOWN 列表能看到待核对命令", true, true);
                int resolved = CommandLedger.resolve(db, TENANT, u.id(), false,
                        "人工确认：设备未动作", 1L);
                check("UNKNOWN 可按人工确认落 FAILED", resolved == 1, resolved);
                check("处置后状态 FAILED",
                        "FAILED".equals(CommandLedger.stateOf(db, TENANT, staleKey)),
                        CommandLedger.stateOf(db, TENANT, staleKey));
                check("处置留痕 resolved_at 非空", resolvedAtOf(staleKey) != null,
                        resolvedAtOf(staleKey));
                check("重复处置被拒（只允许从 UNKNOWN 转移）",
                        CommandLedger.resolve(db, TENANT, u.id(), true, "再次处置", 1L) == 0, 0);
            }
        }
        check("UNKNOWN 列表能看到待核对命令", listed, listed);

        String freshKey = PREFIX + "fresh";
        begin(freshKey, "cmd-fresh");
        int markedAgain = CommandLedger.reconcileOnStartup(db, TENANT, CommandLedger.STALE_MINUTES);
        check("非陈旧 DISPATCH_STARTED 不被误标（本次 " + markedAgain + " 条）",
                "DISPATCH_STARTED".equals(CommandLedger.stateOf(db, TENANT, freshKey)),
                CommandLedger.stateOf(db, TENANT, freshKey));
    }

    /** P-07.3：四个崩溃点的最小回归矩阵（不真 kill -9，按账本状态模拟）。 */
    private static void testCrashPointMatrix() throws Exception {
        // ① 派发前崩溃：还没 begin ⇒ 没有账本行
        String before = PREFIX + "cp-before";
        check("崩溃点①派发前：无账本行", rowCount(before) == 0, rowCount(before));
        CommandLedger.Entry e1 = begin(before, "cmd-cp-before");
        check("派发前 begin 后落 DISPATCH_STARTED",
                e1.created() && "DISPATCH_STARTED".equals(CommandLedger.stateOf(db, TENANT, before)),
                CommandLedger.stateOf(db, TENANT, before));

        // ② 派发中崩溃：DISPATCH_STARTED 陈旧 ⇒ 启动对账标 UNKNOWN
        String mid = PREFIX + "cp-mid";
        begin(mid, "cmd-cp-mid");
        exec("UPDATE command_ledger SET created_at = now() - interval '1 hour' "
                + "WHERE tenant_id = 1 AND idempotency_key = '" + mid + "'");
        CommandLedger.reconcileOnStartup(db, TENANT, CommandLedger.STALE_MINUTES);
        check("崩溃点②派发中：陈旧派发标 UNKNOWN",
                "UNKNOWN".equals(CommandLedger.stateOf(db, TENANT, mid)),
                CommandLedger.stateOf(db, TENANT, mid));

        // ③ 回包后/落库前崩溃：UNKNOWN 人工确认已执行 ⇒ ACKED
        CommandLedger.Unknown u = findUnknown(mid);
        check("崩溃点③：UNKNOWN 可被人工确认", u != null, u);
        if (u != null) {
            int n = CommandLedger.resolve(db, TENANT, u.id(), true,
                    "人工确认：设备已执行", 1L);
            check("崩溃点③：确认后状态 ACKED", n == 1
                    && "ACKED".equals(CommandLedger.stateOf(db, TENANT, mid)),
                    CommandLedger.stateOf(db, TENANT, mid));
        }

        // ④ 重复下发：同 key 第二次 begin 被拒
        String dup = PREFIX + "cp-dup";
        begin(dup, "cmd-cp-dup");
        CommandLedger.Entry again = begin(dup, "cmd-cp-dup");
        check("崩溃点④重复下发：begin 拒绝新派发", !again.created(), again);
    }

    private static long outboxIdFor(long ledgerId) throws Exception {
        try (java.sql.Connection c = db.open();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "SELECT id FROM command_outbox WHERE tenant_id = 1 AND ledger_id = ? "
                             + "ORDER BY id DESC LIMIT 1")) {
            ps.setLong(1, ledgerId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private static CommandLedger.Unknown findUnknown(String key) throws Exception {
        for (CommandLedger.Unknown u : CommandLedger.listUnknown(db, TENANT, 200)) {
            if (key.equals(u.key())) {
                return u;
            }
        }
        return null;
    }

    private static CommandLedger.Entry begin(String key, String commandId) throws Exception {
        return CommandLedger.begin(db, TENANT, key, 999999L, 42L, 1, 1,
                200214L, "Home", commandId, "trace-selftest");
    }

    private static int rowCount(String key) throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM command_ledger "
                             + "WHERE tenant_id = ? AND idempotency_key = ?")) {
            ps.setLong(1, TENANT);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static String resolvedAtOf(String key) throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT resolved_at::text FROM command_ledger "
                             + "WHERE tenant_id = ? AND idempotency_key = ?")) {
            ps.setLong(1, TENANT);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static String errorCodeOf(String key) throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT error_code FROM command_ledger "
                             + "WHERE tenant_id = ? AND idempotency_key = ?")) {
            ps.setLong(1, TENANT);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
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
            exec("DELETE FROM command_ledger "
                    + "WHERE tenant_id = 1 AND idempotency_key LIKE '" + PREFIX + "%'");
        } catch (Exception ignored) {
            // 清理失败不影响结论
        }
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
}
