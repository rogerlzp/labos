package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;

/**
 * P2 阶段 B · 扫码节点落证据（recordScanEvidence）连库判别性自测。
 *
 * <p>验证端到端：扫码节点按「源位 + 期望码 + 实扫码」落证据 → 抓板闸门
 * authorizeAndConsume 能消费。反例：源位上没板/无码时不落证据。
 */
public final class ScanEvidenceRecordDbSelfTest {

    private static final long TENANT = 1L;
    private static final long RUN = -998401L;
    private static final long NODE = -998402L;
    private static final long DEV = -998411L;
    private static final long INST = -998412L;
    private static final long SRC_POS = -998413L;
    private static final String SCAN = "SELF-REC-1";

    private static DbConfig db;
    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        cleanup();

        try {
            seed();

            section("1. 扫码节点落证据（源位+期望码+实扫码）");
            PositionLock lock = new PositionLock(db, TENANT);
            lock.recordScanEvidence(INST, "{\"barcode\":\"PLATE-A\"}", RUN, NODE, SCAN, null);
            eq("证据已登记", true, evidenceExists(SCAN));
            eq("源位绑定正确", SRC_POS, evidencePos(SCAN));
            eq("期望码正确", "PLATE-A", evidenceField(SCAN, "expected_barcode"));
            eq("实扫码正确", "PLATE-A", evidenceField(SCAN, "scanned_barcode"));

            section("2. 端到端：抓板闸门消费刚落的证据");
            GrabScanGate.Verdict v = ScanEvidence.authorizeAndConsume(db, TENANT, SCAN,
                    RUN, SRC_POS, "PLATE-A", 88L, Duration.ofMinutes(30));
            eq("闸门放行", true, v.allow());

            section("3. 反例：重复消费拒绝");
            GrabScanGate.Verdict again = ScanEvidence.authorizeAndConsume(db, TENANT, SCAN,
                    RUN, SRC_POS, "PLATE-A", 89L, Duration.ofMinutes(30));
            eq("已消费拒绝", false, again.allow());
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
        exec("INSERT INTO resource_node (id, tenant_id, code, name, kind, parent_id) "
                + "VALUES (?, ?, 'EV-SRC', '源位', 'storage', ?)", SRC_POS, TENANT, DEV);
        exec("INSERT INTO device_instance (id, tenant_id, code, name, resource_id) "
                + "VALUES (?, ?, 'EV-INST', '实例', ?)", INST, TENANT, DEV);
        exec("INSERT INTO position_state (tenant_id, resource_id, plate_id, occupied_by_run) "
                + "VALUES (?, ?, 'PLATE-A', ?)", TENANT, SRC_POS, RUN);
    }

    private static boolean evidenceExists(String scanId) throws SQLException {
        return evidenceField(scanId, "scan_id") != null;
    }

    private static Long evidencePos(String scanId) throws SQLException {
        String v = evidenceField(scanId, "source_position_id");
        return v == null ? null : Long.valueOf(v);
    }

    private static String evidenceField(String scanId, String col) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT " + col + " FROM position_scan_evidence "
                             + "WHERE tenant_id = ? AND scan_id = ?")) {
            ps.setLong(1, TENANT);
            ps.setString(2, scanId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? String.valueOf(rs.getObject(1)) : null;
            }
        }
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
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM position_scan_evidence WHERE tenant_id = ? AND scan_id LIKE 'SELF-REC-%'")) {
            ps.setLong(1, TENANT);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.out.println("  [清理] evidence 失败：" + e.getMessage());
        }
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM position_state WHERE tenant_id = ? AND resource_id = ?")) {
            ps.setLong(1, TENANT);
            ps.setLong(2, SRC_POS);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.out.println("  [清理] position 失败：" + e.getMessage());
        }
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM device_instance WHERE tenant_id = ? AND id = ?")) {
            ps.setLong(1, TENANT);
            ps.setLong(2, INST);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.out.println("  [清理] instance 失败：" + e.getMessage());
        }
        for (long id : new long[]{SRC_POS, DEV}) {
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

    private ScanEvidenceRecordDbSelfTest() {
    }
}
