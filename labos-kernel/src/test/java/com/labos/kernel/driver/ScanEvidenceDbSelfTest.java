package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;

/**
 * P2 阶段 B · 位置级扫码证据 连库判别性自测。
 *
 * <p>验证：登记幂等（同 scan_id 重复上报只一行）；原子消费（第一次成功、第二次失败）；
 * 反例：不存在/已消费的 scan_id 不能消费（防复用旧扫码）。
 */
public final class ScanEvidenceDbSelfTest {

    private static final long TENANT = 1L;
    private static final long RUN = -998201L;
    private static final String SCAN = "SELF-SCAN-1";

    private static DbConfig db;
    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        cleanup();

        try {
            section("1. 登记幂等");
            ScanEvidence.record(db, TENANT, RUN, 5L, 100L, "PLATE-1", "PLATE-1",
                    "2026-11-15T12:00:00Z", SCAN, null, "cmd-1");
            ScanEvidence.record(db, TENANT, RUN, 5L, 100L, "PLATE-1", "PLATE-1",
                    "2026-11-15T12:00:00Z", SCAN, null, "cmd-1");
            eq("同 scan_id 重复上报只一行", 1L, count(SCAN));

            section("2. 原子消费（第一次成功）");
            eq("第一次消费成功", true, ScanEvidence.consume(db, TENANT, SCAN, 99L, RUN));

            section("3. 反例：已消费的 scan_id 不能再次消费（防复用）");
            eq("第二次消费失败", false, ScanEvidence.consume(db, TENANT, SCAN, 100L, RUN));

            section("4. 反例：不存在的 scan_id 不能消费");
            eq("不存在 → false", false, ScanEvidence.consume(db, TENANT, "SELF-SCAN-NOPE", 1L, RUN));
        } finally {
            cleanup();
        }

        System.out.println();
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static long count(String scanId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM position_scan_evidence WHERE tenant_id = ? AND scan_id = ?")) {
            ps.setLong(1, TENANT);
            ps.setString(2, scanId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void cleanup() {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM position_scan_evidence WHERE tenant_id = ? AND scan_id LIKE 'SELF-SCAN-%'")) {
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

    private ScanEvidenceDbSelfTest() {
    }
}
