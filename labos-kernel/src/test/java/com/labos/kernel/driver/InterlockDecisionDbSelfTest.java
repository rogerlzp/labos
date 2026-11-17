package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.DeviceFactMapper;
import com.labos.kernel.persistence.Mappers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * P2 阶段 A · 互锁决策快照 连库判别性自测。
 *
 * <p>验证：{@link InterlockDecision#record} 落库后能读回完整事实快照与决策；
 * 反例：{@code db=null} / {@code rec=null} 时 no-op 不崩。
 *
 * <p>2026-11-17 补（步骤 5~7 节）：<b>守卫接线</b>——此前 record 实现存在但生产零调用方，
 * 表永远 0 行。新增断言钉住四个守卫的最终判定都会落一条，且
 * {@code SKIP}（没判：无事实源/未携带上下文）与 {@code ALLOW}（判过且放行）必须可区分。
 */
public final class InterlockDecisionDbSelfTest {

    private static final long TENANT = 1L;
    private static final long RUN = -998001L;
    private static final long NODE = -998002L;
    /**
     * 守卫接线用 runId —— 必须是<b>正数</b>：四个守卫第一道判断都是
     * {@code if (db == null || runId <= 0 || ...) return null;}（上下文不全 ⇒ 不判定也不落审计）。
     * 本仓其它连库自测习惯用负 id 避让真实数据，照抄会得到「全被跳过、一行不落」的假绿。
     * 取 999 万级（真实 run.max(id) 约 100 万）并在 cleanup 里删净。
     */
    private static final long RUN_L1 = 9994101L;
    private static final long RUN_L3 = 9994102L;
    private static final long RUN_L5 = 9994103L;
    private static final long DEV = 9994201L;

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

        section("5. 接线 L1：无 turntable 事实 → SKIP（「没判」≠「放行」）");
        eq("放行（返回 null）", null, TurntableGuard.checkBeforeTransfer(db, TENANT, RUN_L1, DEV));
        eq("落 1 条", 1, countOf(RUN_L1));
        Map<String, Object> d1 = lastOf(RUN_L1);
        eq("interlock_code", InterlockDecision.L1_TURNTABLE, d1.get("interlockCode"));
        eq("decision", InterlockDecision.SKIP, d1.get("decision"));
        eq("resource_id", DEV, d1.get("resourceId"));

        section("6. 接线 L1：停止+新鲜 → ALLOW；停止但过期 → REJECT");
        upsertFact("stopped", "v1", OffsetDateTime.now());
        eq("放行", null, TurntableGuard.checkBeforeTransfer(db, TENANT, RUN_L1, DEV));
        Map<String, Object> d2 = lastOf(RUN_L1);
        eq("decision", InterlockDecision.ALLOW, d2.get("decision"));
        eq("fact_type", "turntable", d2.get("factType"));
        eq("fact_value", "stopped", d2.get("factValue"));

        upsertFact("stopped", "v2", OffsetDateTime.now().minusMinutes(10));
        DriverResult r3 = TurntableGuard.checkBeforeTransfer(db, TENANT, RUN_L1, DEV);
        eq("拒绝", false, r3 == null ? Boolean.TRUE : r3.ok());
        Map<String, Object> d3 = lastOf(RUN_L1);
        eq("decision", InterlockDecision.REJECT, d3.get("decision"));
        eq("error_code", "EC-STATE-STALE", d3.get("errorCode"));

        section("7. 接线 L3/L5：未配置分区 → REJECT；无上下文 → SKIP / 有上下文无投影 → REJECT");
        RailZoneGuard.Acquire a = RailZoneGuard.acquire(db, TENANT, RUN_L3, "selftest->nope");
        eq("L3 拒绝", false, a.ok());
        Map<String, Object> d4 = lastOf(RUN_L3);
        eq("interlock_code", InterlockDecision.L3_RAIL_ZONE, d4.get("interlockCode"));
        eq("decision", InterlockDecision.REJECT, d4.get("decision"));
        eq("error_code", "EC-INTERLOCK-RAIL", d4.get("errorCode"));

        eq("L5 无上下文放行", null, WaitingInteractionGuard.checkBeforeTransfer(
                db, TENANT, RUN_L5, DEV, "s1", "step1", null));
        eq("L5 SKIP", InterlockDecision.SKIP, lastOf(RUN_L5).get("decision"));
        DriverResult r5 = WaitingInteractionGuard.checkBeforeTransfer(
                db, TENANT, RUN_L5, DEV, "s1", "step1", "ix-1");
        eq("L5 有上下文拒绝", false, r5 == null ? Boolean.TRUE : r5.ok());
        Map<String, Object> d5 = lastOf(RUN_L5);
        eq("decision", InterlockDecision.REJECT, d5.get("decision"));
        eq("error_code", "EC-INTERLOCK-NO-WAIT", d5.get("errorCode"));
        eq("interaction_id", "ix-1", d5.get("interactionId"));

        cleanup();

        System.out.println();
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void cleanup() {
        // 1) record 直调的行（带 command_id）
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM interlock_decision WHERE tenant_id = ? AND command_id LIKE 'cmd-interlock-%'")) {
            ps.setLong(1, TENANT);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.out.println("  [清理] 失败：" + e.getMessage());
        }
        // 2) 守卫接线的行 command_id 为 null，只能按 run_id 删（RUN_L1 注释：正数专用 id）
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM interlock_decision WHERE tenant_id = ? AND run_id IN (?,?,?)")) {
            ps.setLong(1, TENANT);
            ps.setLong(2, RUN_L1);
            ps.setLong(3, RUN_L3);
            ps.setLong(4, RUN_L5);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.out.println("  [清理] 守卫接线行失败：" + e.getMessage());
        }
        // 3) L1 事实
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM device_fact WHERE tenant_id = ? AND device_resource_id = ?")) {
            ps.setLong(1, TENANT);
            ps.setLong(2, DEV);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.out.println("  [清理] device_fact 失败：" + e.getMessage());
        }
    }

    private static void upsertFact(String value, String version, OffsetDateTime observedAt) {
        Mappers.get(DeviceFactMapper.class).upsertFact(TENANT, DEV, "turntable", value,
                version, observedAt.toString());
    }

    private static int countOf(long runId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM interlock_decision WHERE tenant_id = ? AND run_id = ?")) {
            ps.setLong(1, TENANT);
            ps.setLong(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static Map<String, Object> lastOf(long runId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT interlock_code, decision, error_code, fact_type, fact_value,"
                             + " run_id, resource_id, interaction_id"
                             + " FROM interlock_decision WHERE tenant_id = ? AND run_id = ?"
                             + " ORDER BY id DESC LIMIT 1")) {
            ps.setLong(1, TENANT);
            ps.setLong(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                Map<String, Object> m = new LinkedHashMap<>();
                if (rs.next()) {
                    m.put("interlockCode", rs.getString(1));
                    m.put("decision", rs.getString(2));
                    m.put("errorCode", rs.getString(3));
                    m.put("factType", rs.getString(4));
                    m.put("factValue", rs.getString(5));
                    m.put("runId", rs.getLong(6));
                    m.put("resourceId", rs.getObject(7));
                    m.put("interactionId", rs.getString(8));
                }
                return m;
            }
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
