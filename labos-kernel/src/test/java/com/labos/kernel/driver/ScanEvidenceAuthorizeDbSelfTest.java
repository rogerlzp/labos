package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;

/**
 * P2 阶段 B · 抓板闸门接线（authorizeAndConsume）连库判别性自测。
 *
 * <p>验证「查证据 → 纯判定 → 原子消费」：正确证据放行并消费一次；
 * 反例：复用/点位不符/不存在/码不符 一律拒绝。
 */
public final class ScanEvidenceAuthorizeDbSelfTest {

    private static final long TENANT = 1L;
    private static final long RUN = -998301L;
    private static final long SRC_POS = -998311L;
    private static final String SCAN = "SELF-AUTH-1";

    private static DbConfig db;
    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        cleanup();

        try {
            ScanEvidence.record(db, TENANT, RUN, 5L, SRC_POS, "PLATE-A", "PLATE-A",
                    "2026-11-15T12:00:00Z", SCAN, null, "cmd-1");

            section("1. 正确证据放行并消费");
            GrabScanGate.Verdict v = ScanEvidence.authorizeAndConsume(db, TENANT, SCAN,
                    RUN, SRC_POS, "PLATE-A", 99L, Duration.ofMinutes(30));
            eq("放行", true, v.allow());

            section("2. 反例：已消费 → 拒绝（防复用）");
            GrabScanGate.Verdict reused = ScanEvidence.authorizeAndConsume(db, TENANT, SCAN,
                    RUN, SRC_POS, "PLATE-A", 100L, Duration.ofMinutes(30));
            eq("已消费拒绝", false, reused.allow());
            eq("错误码 EC-SCAN-EVIDENCE-CONSUMED", "EC-SCAN-EVIDENCE-CONSUMED",
                    reused.errorCode());

            section("3. 反例：证据点位 ≠ 本次抓取源位 → 拒绝");
            ScanEvidence.record(db, TENANT, RUN, 5L, SRC_POS + 1, "PLATE-B", "PLATE-B",
                    "2026-11-15T12:00:00Z", "SELF-AUTH-2", null, "cmd-2");
            GrabScanGate.Verdict wrongPos = ScanEvidence.authorizeAndConsume(db, TENANT,
                    "SELF-AUTH-2", RUN, SRC_POS, "PLATE-B", 101L, Duration.ofMinutes(30));
            eq("点位不符拒绝", false, wrongPos.allow());
            eq("错误码 EC-SCAN-EVIDENCE-MISSING", "EC-SCAN-EVIDENCE-MISSING",
                    wrongPos.errorCode());

            section("4. 反例：scanId 不存在 → 拒绝");
            GrabScanGate.Verdict missing = ScanEvidence.authorizeAndConsume(db, TENANT,
                    "SELF-AUTH-NOPE", RUN, SRC_POS, "PLATE-A", 102L, Duration.ofMinutes(30));
            eq("不存在拒绝", false, missing.allow());
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
                     "DELETE FROM position_scan_evidence WHERE tenant_id = ? AND scan_id LIKE 'SELF-AUTH-%'")) {
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

    private ScanEvidenceAuthorizeDbSelfTest() {
    }
}
