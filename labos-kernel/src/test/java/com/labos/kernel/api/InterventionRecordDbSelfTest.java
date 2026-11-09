package com.labos.kernel.api;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;

/** L-01 人工介入记录落库自测（需要真实库）。 */
public final class InterventionRecordDbSelfTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        DbConfig db = DbConfig.fromEnv();
        long tenant = 1L;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM intervention_record WHERE tenant_id=1 "
                             + "AND plate_barcode='SELF-L01-PLATE'")) {
            ps.executeUpdate();
        }
        QueryService q = com.labos.kernel.support.TestSpringSupport.queryService();
        long id = q.recordIntervention(0L, -900001L, null, "selftest", "CONTINUE",
                "SELF-L01-PLATE", "SAMPLE-1", "A-01", "B-02", "resolved", "自测");
        check("人工介入记录可落库", id > 0, id);
        String json = q.interventionRecordsJson(20);
        check("列表能看到本次记录", json.contains("SELF-L01-PLATE"), json.substring(0, Math.min(200, json.length())));
        check("记录包含板/样本/交接点/结果",
                json.contains("SAMPLE-1") && json.contains("A-01")
                        && json.contains("B-02") && json.contains("resolved"), "fields");
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM intervention_record WHERE tenant_id=1 "
                             + "AND plate_barcode='SELF-L01-PLATE'")) {
            ps.executeUpdate();
        }
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
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
}
