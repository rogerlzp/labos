package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.DeviceFactMapper;
import com.labos.kernel.persistence.Mappers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;

/**
 * P2 阶段 C/D · 锁内最终复核 连库判别性自测。
 *
 * <p>验证：检查通过后、下发前状态变了 → 最终复核拒绝（不再等待）。
 */
public final class FinalRecheckDbSelfTest {

    private static final long TENANT = 1L;
    private static final long RUN = 9995001L;
    private static final long DEV = 9995011L;
    private static final long STIRRER = 9995012L;
    private static final long DISPENSER = 9995013L;
    /** 有设备节点但**没有** stirrer_dispenser_pair 的分液器（客户现场落地前的常态）。 */
    private static final long UNPAIRED_DISPENSER = 9995014L;

    private static DbConfig db;
    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        cleanup();

        try {
            seed();

            section("1. L1 转盘停止 → 最终复核放行");
            eq("stopped → null", null, TurntableGuard.finalRecheck(db, TENANT, DEV));

            section("2. 检查后转盘变运动 → 最终复核拒绝（EC-INTERLOCK-TURNTABLE）");
            Mappers.get(DeviceFactMapper.class).upsertFact(TENANT, DEV, "turntable",
                    "moving", "evt-2", Instant.now().toString());
            DriverResult r = TurntableGuard.finalRecheck(db, TENANT, DEV);
            eq("moving → 拒绝", true, r != null);
            eq("错误码 EC-INTERLOCK-TURNTABLE", "EC-INTERLOCK-TURNTABLE",
                    r == null ? null : r.errorCode());

            section("3. L4 搅拌 idle → 最终复核放行");
            eq("idle → null", null, StirrerDispenserGuard.finalRecheck(db, TENANT, DISPENSER));

            section("4. 检查后搅拌 error → 最终复核拒绝（EC-INTERLOCK-STIRRER）");
            updateStirrer("error");
            DriverResult r4 = StirrerDispenserGuard.finalRecheck(db, TENANT, DISPENSER);
            eq("error → 拒绝", true, r4 != null);
            eq("错误码 EC-INTERLOCK-STIRRER", "EC-INTERLOCK-STIRRER",
                    r4 == null ? null : r4.errorCode());

            // 回归守卫（562c663）：finalRecheck 是**物理下发前**的通用复核，
            // 若「无配对 ⇒ 拒绝」，则 stirrer_dispenser_pair 为 0 行时凡是走到复核的命令
            // 全被拒（实测：提取仪 PickTipComb 直接 EC-STATE-UNKNOWN 转人工，平台发不出命令）。
            // 无配对 = 无事实源 = 非 L4 目标 ⇒ 必须跳过；配置缺失的 fail-closed 由
            // checkBeforeDispense 在分液方法上把关（StirrerDispenserGuardDbSelfTest §3 覆盖）。
            section("5. 无搅拌配对 → 最终复核跳过（不得拒绝，否则全平台停摆）");
            eq("无配对 → null", null,
                    StirrerDispenserGuard.finalRecheck(db, TENANT, UNPAIRED_DISPENSER));
        } finally {
            cleanup();
        }

        System.out.println();
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void seed() throws SQLException {
        exec("INSERT INTO resource_node (id, tenant_id, code, name, kind) "
                + "VALUES (?, ?, 'EV-DEV', '设备', 'device')", DEV, TENANT);
        exec("INSERT INTO resource_node (id, tenant_id, code, name, kind) "
                + "VALUES (?, ?, 'EV-STIRRER', '搅拌', 'device')", STIRRER, TENANT);
        exec("INSERT INTO resource_node (id, tenant_id, code, name, kind) "
                + "VALUES (?, ?, 'EV-DISPENSER', '分液器', 'device')", DISPENSER, TENANT);
        // 故意**不**写 stirrer_dispenser_pair：§5 用它验证「无配对 ⇒ 跳过」。
        exec("INSERT INTO resource_node (id, tenant_id, code, name, kind) "
                + "VALUES (?, ?, 'EV-DISPENSER-UNPAIRED', '未配对分液器', 'device')",
                UNPAIRED_DISPENSER, TENANT);
        exec("INSERT INTO stirrer_dispenser_pair (tenant_id, stirrer_resource_id, dispenser_resource_id) "
                + "VALUES (?, ?, ?)", TENANT, STIRRER, DISPENSER);
        updateStirrer("idle");
    }

    private static void updateStirrer(String status) throws SQLException {
        exec("INSERT INTO device_state (tenant_id, resource_id, status) VALUES (?, ?, ?) "
                        + "ON CONFLICT (tenant_id, resource_id) DO UPDATE SET status = EXCLUDED.status, updated_at = now()",
                TENANT, STIRRER, status);
    }

    private static void exec(String sql, Object... args) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    private static void cleanup() {
        for (String sql : new String[]{
                "DELETE FROM device_fact WHERE tenant_id = ? AND device_resource_id = ?",
                "DELETE FROM stirrer_dispenser_pair WHERE tenant_id = ? AND dispenser_resource_id = ?"}) {
            try (Connection c = db.open();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, TENANT);
                ps.setLong(2, sql.contains("fact") ? DEV : DISPENSER);
                ps.executeUpdate();
            } catch (SQLException e) {
                System.out.println("  [清理] 失败：" + e.getMessage());
            }
        }
        for (long id : new long[]{DEV, STIRRER, DISPENSER, UNPAIRED_DISPENSER}) {
            try (Connection c = db.open();
                 PreparedStatement ps = c.prepareStatement(
                         "DELETE FROM device_state WHERE tenant_id = ? AND resource_id = ?")) {
                ps.setLong(1, TENANT);
                ps.setLong(2, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                System.out.println("  [清理] state " + id + " 失败：" + e.getMessage());
            }
            try (Connection c = db.open();
                 PreparedStatement ps = c.prepareStatement(
                         "DELETE FROM resource_node WHERE tenant_id = ? AND id = ?")) {
                ps.setLong(1, TENANT);
                ps.setLong(2, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                System.out.println("  [清理] node " + id + " 失败：" + e.getMessage());
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

    private FinalRecheckDbSelfTest() {
    }
}
