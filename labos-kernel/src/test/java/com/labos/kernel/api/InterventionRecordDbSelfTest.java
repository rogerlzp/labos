package com.labos.kernel.api;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.RunMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;

/** L-01 人工介入记录落库自测（需要真实库）。 */
public final class InterventionRecordDbSelfTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        DbConfig db = DbConfig.fromEnv();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM intervention_record WHERE tenant_id=1 "
                             + "AND plate_barcode='SELF-L01-PLATE'")) {
            ps.executeUpdate();
        }
        // 按域直取 RunMapper：写路径已并入 RunService（{@code recordIntervention}），
        // 本自测锁的是 SQL 落库与回读口径，不走 Web 层 Service。
        RunMapper m = com.labos.kernel.support.TestSpringSupport.runMapper();
        Long id = m.insertIntervention(1L, null, -900001L, null, "selftest",
                "CONTINUE", "SELF-L01-PLATE", "SAMPLE-1", "A-01", "B-02",
                "resolved", "自测");
        check("人工介入记录可落库", id != null && id > 0, id);
        List<Map<String, Object>> rows = m.selectInterventionRecords(1L, 20);
        Map<String, Object> mine = null;
        for (Map<String, Object> r : rows) {
            if ("SELF-L01-PLATE".equals(String.valueOf(r.get("plateBarcode")))) {
                mine = r;
                break;
            }
        }
        check("列表能看到本次记录", mine != null,
                rows.isEmpty() ? "(空)" : String.valueOf(rows.get(0)));
        check("记录包含板/样本/交接点/结果", mine != null
                        && "SAMPLE-1".equals(String.valueOf(mine.get("sampleId")))
                        && "A-01".equals(String.valueOf(mine.get("fromLocation")))
                        && "B-02".equals(String.valueOf(mine.get("toLocation")))
                        && "resolved".equals(String.valueOf(mine.get("result"))),
                "fields");
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
