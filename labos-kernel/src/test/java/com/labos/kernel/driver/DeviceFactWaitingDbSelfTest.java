package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.DeviceFactMapper;
import com.labos.kernel.persistence.Mappers;
import com.labos.kernel.persistence.WaitingInteractionMapper;

import java.util.Map;
import java.util.Objects;

/**
 * P2 阶段 C · 设备事实（状态版本）+ 有效等待投影 连库判别性自测。
 */
public final class DeviceFactWaitingDbSelfTest {

    private static final long TENANT = 1L;
    private static final long DEV = 9991001L;
    private static final String SESSION = "sess-c";
    private static final String STEP = "step-c";
    private static final String IX = "ix-c";

    private static DbConfig db;
    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        cleanup();

        try {
            section("1. device_fact 覆盖写 + 状态版本可读回");
            Mappers.get(DeviceFactMapper.class).upsertFact(TENANT, DEV, "turntable",
                    "moving", "evt-1", "2026-11-15T12:00:00Z");
            Mappers.get(DeviceFactMapper.class).upsertFact(TENANT, DEV, "turntable",
                    "stopped", "evt-2", "2026-11-15T12:00:05Z");
            Map<String, Object> f = Mappers.get(DeviceFactMapper.class)
                    .selectFact(TENANT, DEV, "turntable");
            eq("最新值 stopped", "stopped", f.get("factValue"));
            eq("最新版本 evt-2", "evt-2", f.get("stateVersion"));

            section("2. 有效等待投影：WAITING + 当前会话/步骤命中");
            Mappers.get(WaitingInteractionMapper.class).upsertWaiting(TENANT, DEV,
                    SESSION, STEP, IX, "WAITING", "evt-3");
            Map<String, Object> w = Mappers.get(WaitingInteractionMapper.class)
                    .selectEffective(TENANT, DEV, SESSION, STEP);
            eq("查得有效等待", true, w != null);
            eq("interaction_id 命中", IX, w == null ? null : w.get("interactionId"));

            section("3. 反例：消费后不再有效");
            long id = w == null ? 0L : ((Number) w.get("id")).longValue();
            eq("消费成功", 1, Mappers.get(WaitingInteractionMapper.class).consumeWaiting(TENANT, id));
            Map<String, Object> after = Mappers.get(WaitingInteractionMapper.class)
                    .selectEffective(TENANT, DEV, SESSION, STEP);
            eq("消费后查不到有效等待", null, after);

            section("4. 反例：旧会话/旧步骤查不到有效等待");
            eq("旧会话 → null", null, Mappers.get(WaitingInteractionMapper.class)
                    .selectEffective(TENANT, DEV, "sess-old", STEP));
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
        for (String sql : new String[]{
                "DELETE FROM device_fact WHERE tenant_id = ? AND device_resource_id = ?",
                "DELETE FROM waiting_interaction WHERE tenant_id = ? AND device_resource_id = ?"}) {
            try (java.sql.Connection c = db.open();
                 java.sql.PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, TENANT);
                ps.setLong(2, DEV);
                ps.executeUpdate();
            } catch (Exception e) {
                System.out.println("  [清理] 失败：" + e.getMessage());
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

    private DeviceFactWaitingDbSelfTest() {
    }
}
