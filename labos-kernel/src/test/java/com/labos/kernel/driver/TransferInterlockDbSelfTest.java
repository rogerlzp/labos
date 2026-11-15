package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.DeviceFactMapper;
import com.labos.kernel.persistence.Mappers;
import com.labos.kernel.persistence.WaitingInteractionMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;

/**
 * P2 阶段 C · L1 转盘 + L5 等待交互 运行时守卫 连库判别性自测。
 */
public final class TransferInterlockDbSelfTest {

    private static final long TENANT = 1L;
    private static final long RUN = 9992001L;
    private static final long DEV = 9992011L;
    private static final String SESSION = "sess-d";
    private static final String STEP = "step-d";
    private static final String IX = "ix-d";

    private static DbConfig db;
    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        cleanup();

        try {
            section("1. L1：无转盘事实 → 跳过（null）");
            eq("无事实 → null", null,
                    TurntableGuard.checkBeforeTransfer(db, TENANT, RUN, DEV));

            section("2. L1：转盘停止 + 新鲜 → 放行");
            Mappers.get(DeviceFactMapper.class).upsertFact(TENANT, DEV, "turntable",
                    "stopped", "evt-1", java.time.Instant.now().toString());
            eq("stopped → null", null,
                    TurntableGuard.checkBeforeTransfer(db, TENANT, RUN, DEV));

            section("3. L1：转盘状态无法识别 → 拒绝");
            Mappers.get(DeviceFactMapper.class).upsertFact(TENANT, DEV, "turntable",
                    "weird", "evt-2", java.time.Instant.now().toString());
            DriverResult r = TurntableGuard.checkBeforeTransfer(db, TENANT, RUN, DEV);
            eq("未知值 → 拒绝", true, r != null);
            eq("错误码 EC-STATE-UNKNOWN", "EC-STATE-UNKNOWN", r == null ? null : r.errorCode());

            section("4. L5：未携带 interactionId → 跳过");
            eq("无 interactionId → null", null,
                    WaitingInteractionGuard.checkBeforeTransfer(db, TENANT, RUN, DEV,
                            SESSION, STEP, null));

            section("5. L5：携带 interactionId 但无有效等待 → 拒绝");
            DriverResult noWait = WaitingInteractionGuard.checkBeforeTransfer(db, TENANT, RUN,
                    DEV, SESSION, STEP, IX);
            eq("无等待 → 拒绝", true, noWait != null);
            eq("错误码 EC-INTERLOCK-NO-WAIT", "EC-INTERLOCK-NO-WAIT",
                    noWait == null ? null : noWait.errorCode());

            section("6. L5：有效等待匹配 → 放行");
            Mappers.get(WaitingInteractionMapper.class).upsertWaiting(TENANT, DEV,
                    SESSION, STEP, IX, "WAITING", "evt-3");
            eq("匹配 → null", null,
                    WaitingInteractionGuard.checkBeforeTransfer(db, TENANT, RUN, DEV,
                            SESSION, STEP, IX));

            section("7. L5：interaction 不匹配 → 拒绝");
            DriverResult mismatch = WaitingInteractionGuard.checkBeforeTransfer(db, TENANT, RUN,
                    DEV, SESSION, STEP, "ix-other");
            eq("不匹配 → 拒绝", true, mismatch != null);
            eq("错误码 EC-INTERLOCK-NO-WAIT", "EC-INTERLOCK-NO-WAIT",
                    mismatch == null ? null : mismatch.errorCode());
        } finally {
            cleanup();
        }

        System.out.println();
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void cleanup() {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM device_fact WHERE tenant_id = ? AND device_resource_id = ?")) {
            ps.setLong(1, TENANT);
            ps.setLong(2, DEV);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.out.println("  [清理] fact 失败：" + e.getMessage());
        }
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM waiting_interaction WHERE tenant_id = ? AND device_resource_id = ?")) {
            ps.setLong(1, TENANT);
            ps.setLong(2, DEV);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.out.println("  [清理] waiting 失败：" + e.getMessage());
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

    private TransferInterlockDbSelfTest() {
    }
}
