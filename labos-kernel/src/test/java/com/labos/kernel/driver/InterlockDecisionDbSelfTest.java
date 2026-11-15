package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/**
 * P2 阶段 A · 互锁决策快照 连库判别性自测。
 *
 * <p>验证：{@link InterlockDecision#record} 落库后能读回完整事实快照与决策；
 * 反例：{@code db=null} / {@code rec=null} 时 no-op 不崩。
 */
public final class InterlockDecisionDbSelfTest {

    private static final long TENANT = 1L;
    private static final long RUN = -998001L;
    private static final long NODE = -998002L;

    private static DbConfig db;
    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();

        section("1. record 完整快照（REJECT）");
        InterlockDecision.record(db, TENANT, RUN, NODE, "cmd-interlock-1", 200387L,
                new InterlockDecision.Rec("L4", InterlockDecision.REJECT)
                        .fact("stirrer_status", "error")
                        .times("2026-11-15T10:00:00Z", "2026-11-15T10:00:01Z")
                        .version("evt-42")
                        .session("sess-7", "step-3", "ix-9")
                        .reason("搅拌单元故障")
                        .error("EC-INTERLOCK-STIRRER"));

        section("2. record ALLOW（放行决策也要留痕）");
        InterlockDecision.record(db, TENANT, RUN, NODE, "cmd-interlock-2", 200387L,
                new InterlockDecision.Rec("L4", InterlockDecision.ALLOW)
                        .fact("stirrer_status", "idle")
                        .version("evt-50")
                        .session("sess-7", "step-4", "ix-10")
                        .reason("搅拌单元可用"));

        section("3. 读回核对（决策、错误码、会话、版本）");
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT decision, error_code, device_session_id, workflow_step_id, "
                             + "interaction_id, state_version, fact_type, fact_value "
                             + "FROM interlock_decision "
                             + "WHERE tenant_id = ? AND command_id = ? "
                             + "ORDER BY id DESC LIMIT 1")) {
            ps.setLong(1, TENANT);
            ps.setString(2, "cmd-interlock-1");
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    eq("读回 REJECT 行", "存在", "缺失");
                } else {
                    eq("decision", "REJECT", rs.getString("decision"));
                    eq("error_code", "EC-INTERLOCK-STIRRER", rs.getString("error_code"));
                    eq("device_session_id", "sess-7", rs.getString("device_session_id"));
                    eq("workflow_step_id", "step-3", rs.getString("workflow_step_id"));
                    eq("interaction_id", "ix-9", rs.getString("interaction_id"));
                    eq("state_version", "evt-42", rs.getString("state_version"));
                    eq("fact_type", "stirrer_status", rs.getString("fact_type"));
                    eq("fact_value", "error", rs.getString("fact_value"));
                }
            }
        }

        section("4. 反例：db=null / rec=null no-op");
        InterlockDecision.record(null, TENANT, RUN, NODE, "cmd-interlock-x", null,
                new InterlockDecision.Rec("L4", InterlockDecision.REJECT));
        InterlockDecision.record(db, TENANT, RUN, NODE, "cmd-interlock-y", null, null);
        eq("no-op 不抛异常", true, true);

        cleanup();

        System.out.println();
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void cleanup() {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM interlock_decision WHERE tenant_id = ? AND command_id LIKE 'cmd-interlock-%'")) {
            ps.setLong(1, TENANT);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.out.println("  [清理] 失败：" + e.getMessage());
        }
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("── " + title + " ──");
    }

    private static void eq(String name, Object expect, Object got) {
        if (Objects.equals(expect, got)) {
            pass++;
            System.out.println("  ✓ " + name + (got == null ? "" : "（" + got + "）"));
        } else {
            fail++;
            System.out.println("  ✗ " + name + " 期望 " + expect + "，实际 " + got);
        }
    }

    private InterlockDecisionDbSelfTest() {
    }
}
