package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * R4 · 命令阶段（phase）连库自测。
 *
 * <p>验证四条：
 * <ol>
 *   <li>{@code begin} 后 phase = PENDING；</li>
 *   <li>只进不退：PENDING→SENT→RECEIVED→EXECUTING→DONE 逐步推进成功；</li>
 *   <li>倒退（EXECUTING→RECEIVED）与终态后推进（DONE→SENT、UNKNOWN→DONE）被拒；</li>
 *   <li>启动对账：陈旧未终态行 → state/phase 同时落 UNKNOWN。</li>
 * </ol>
 *
 * <p><b>为什么 RECEIVED/EXECUTING 也测</b>：当前同步驱动（begin → driver.execute() → finish）
 * 拿不到设备 ACK 与「开始执行」回调，这两态在生产**不可达**。但账本层必须先把守卫做对，
 * 等驱动支持流式回包时只补推进点、不改表与守卫 —— 所以这里用账本 API 直接验证单调性。
 */
public final class CommandPhaseDbSelfTest {

    private static int pass;
    private static int fail;
    private static DbConfig db;
    private static final long TENANT = 1L;
    private static final String PREFIX = "SELF-PHASE-" + System.currentTimeMillis() + "-";

    public static void main(String[] args) throws Exception {
        // 绑定 Mappers（非 Bean 工具类经 Mappers.get 取 mapper），与账本主自测同一套支撑
        com.labos.kernel.support.TestSpringSupport.db();
        db = DbConfig.fromEnv();
        cleanup();
        try {
            monotonicForward();
            guardRejectsBackwardAndTerminal();
            unknownIsTerminal();
            staleBecomesUnknown();
        } finally {
            cleanup();
        }
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ================================================================ 用例

    /** 1. 只进不退：五态顺序推进。 */
    private static void monotonicForward() throws Exception {
        section("1. 阶段单调推进");
        long id = newEntry("forward");
        eq("begin 后 phase=PENDING", "PENDING", phaseOf(id));

        yes("PENDING→SENT", CommandLedger.advancePhase(db, id, CommandLedger.PHASE_SENT));
        eq("phase=SENT", "SENT", phaseOf(id));
        yes("SENT→RECEIVED（预留态，守卫允许）",
                CommandLedger.advancePhase(db, id, CommandLedger.PHASE_RECEIVED));
        yes("RECEIVED→EXECUTING",
                CommandLedger.advancePhase(db, id, CommandLedger.PHASE_EXECUTING));
        yes("EXECUTING→DONE", CommandLedger.advancePhase(db, id, CommandLedger.PHASE_DONE));
        eq("phase=DONE", "DONE", phaseOf(id));
    }

    /** 2. 倒退 / 同值 / 终态后推进，全部被拒。 */
    private static void guardRejectsBackwardAndTerminal() throws Exception {
        section("2. 守卫：倒退与终态封闭");
        long id = newEntry("guard");
        CommandLedger.advancePhase(db, id, CommandLedger.PHASE_SENT);
        CommandLedger.advancePhase(db, id, CommandLedger.PHASE_EXECUTING);

        no("EXECUTING→RECEIVED（倒退）被拒",
                CommandLedger.advancePhase(db, id, CommandLedger.PHASE_RECEIVED));
        no("EXECUTING→SENT（倒退）被拒",
                CommandLedger.advancePhase(db, id, CommandLedger.PHASE_SENT));
        eq("phase 仍是 EXECUTING", "EXECUTING", phaseOf(id));

        yes("EXECUTING→DONE", CommandLedger.advancePhase(db, id, CommandLedger.PHASE_DONE));
        no("DONE→SENT（终态封闭）被拒",
                CommandLedger.advancePhase(db, id, CommandLedger.PHASE_SENT));
        no("DONE→DONE（同值不算推进）被拒",
                CommandLedger.advancePhase(db, id, CommandLedger.PHASE_DONE));
        eq("phase 仍是 DONE", "DONE", phaseOf(id));

        no("未知阶段值被拒（不写脏值）",
                CommandLedger.advancePhase(db, id, "BOGUS"));
        eq("phase 仍是 DONE", "DONE", phaseOf(id));
    }

    /** 3. UNKNOWN 也是终态。 */
    private static void unknownIsTerminal() throws Exception {
        section("3. UNKNOWN 终态封闭");
        long id = newEntry("unknown");
        CommandLedger.advancePhase(db, id, CommandLedger.PHASE_SENT);
        exec("UPDATE command_ledger SET phase = 'UNKNOWN' WHERE id = " + id);

        no("UNKNOWN→DONE 被拒", CommandLedger.advancePhase(db, id, CommandLedger.PHASE_DONE));
        eq("phase 仍是 UNKNOWN", "UNKNOWN", phaseOf(id));
    }

    /** 4. 启动对账：陈旧未终态行 → state/phase 同时 UNKNOWN。 */
    private static void staleBecomesUnknown() throws Exception {
        section("4. 启动对账：陈旧未终态标 UNKNOWN");
        long id = newEntry("stale");
        CommandLedger.advancePhase(db, id, CommandLedger.PHASE_SENT);
        // 把 created_at 做旧：账本判定「陈旧」看的是 created_at
        exec("UPDATE command_ledger SET created_at = now() - interval '20 minutes' "
                + "WHERE id = " + id);

        int n = CommandLedger.reconcileOnStartup(db, TENANT, CommandLedger.STALE_MINUTES);
        yes("reconcileOnStartup 标记 ≥1 条", n >= 1);
        eq("phase=UNKNOWN", "UNKNOWN", phaseOf(id));
        eq("state=UNKNOWN", "UNKNOWN", stateOf(id));
    }

    // ================================================================ 造数 / 查询

    private static long newEntry(String tag) throws Exception {
        String key = PREFIX + tag;
        CommandLedger.Entry e = CommandLedger.begin(db, TENANT, key,
                -997001L, -997002L, 1, 1, -997003L, "Pipette", "cmd-" + tag, "trace-" + tag);
        if (e == null || e.id() <= 0) {
            throw new IllegalStateException("账本行创建失败: " + key);
        }
        return e.id();
    }

    private static String phaseOf(long id) throws Exception {
        return one("SELECT phase FROM command_ledger WHERE id = " + id);
    }

    private static String stateOf(long id) throws Exception {
        return one("SELECT state FROM command_ledger WHERE id = " + id);
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

    // ================================================================ 输出

    private static void section(String title) {
        System.out.println("── " + title + " ──");
    }

    private static void yes(String name, boolean ok) {
        check(name, ok, ok);
    }

    private static void no(String name, boolean got) {
        check(name, !got, got);
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

    private CommandPhaseDbSelfTest() {
    }
}
