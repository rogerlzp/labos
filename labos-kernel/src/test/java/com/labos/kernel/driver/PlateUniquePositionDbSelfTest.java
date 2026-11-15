package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;

/**
 * P2 阶段 B · L2「一板一位置」数据库硬约束 连库判别性自测。
 *
 * <p>验证 {@code uk_pos_plate} 唯一索引真的会拦：同一块板写进两个位置 → 违反约束；
 * 不同板写两个位置 → 成功；板为空的多个位置 → 不受影响。
 */
public final class PlateUniquePositionDbSelfTest {

    private static final long TENANT = 1L;
    private static final long POS_A = -999001L;
    private static final long POS_B = -999002L;
    private static final long POS_C = -999003L;

    private static DbConfig db;
    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        cleanup();

        try {
            section("1. uk_pos_plate 唯一索引存在");
            boolean exists = false;
            try (Connection c = db.open();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT 1 FROM pg_indexes WHERE indexname = 'uk_pos_plate' AND tablename = 'position_state'");
                 var rs = ps.executeQuery()) {
                exists = rs.next();
            }
            eq("pg_indexes 有 uk_pos_plate", true, exists);

            section("2. 反例：同一块板写进两个位置 → 违反唯一约束");
            insertPos(POS_A, "PLATE-L2-1");
            boolean violated = false;
            try {
                insertPos(POS_B, "PLATE-L2-1");
            } catch (SQLException e) {
                // 违反 uk_pos_plate（或主键）都算拦下；本测试专门验证同板两位。
                violated = e.getMessage() != null;
            }
            eq("同板两位被数据库拒绝", true, violated);
            cleanupRow(POS_B);

            section("3. 不同板写两个位置 → 成功");
            // POS_A 在反例段已写入 PLATE-L2-1；这里只补 POS_B 的另一块板。
            insertPos(POS_B, "PLATE-L2-2");
            eq("不同板两位共存", true, true);

            section("4. 板为空的多个位置 → 不受影响");
            insertPos(POS_C, null);
            eq("空板位置可写", true, true);
        } finally {
            cleanup();
        }

        System.out.println();
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void insertPos(long resourceId, String plateId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO position_state (tenant_id, resource_id, plate_id) "
                             + "VALUES (?, ?, ?)")) {
            ps.setLong(1, TENANT);
            ps.setLong(2, resourceId);
            ps.setString(3, plateId);
            ps.executeUpdate();
        }
    }

    private static void cleanupRow(long resourceId) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM position_state WHERE tenant_id = ? AND resource_id = ?")) {
            ps.setLong(1, TENANT);
            ps.setLong(2, resourceId);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.out.println("  [清理] " + resourceId + " 失败：" + e.getMessage());
        }
    }

    private static void cleanup() {
        cleanupRow(POS_A);
        cleanupRow(POS_B);
        cleanupRow(POS_C);
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

    private PlateUniquePositionDbSelfTest() {
    }
}
