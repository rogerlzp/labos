package com.labos.kernel.api;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.RunMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;

/** P-08 物理位置证据记录自测（需要真实库）。 */
public final class PositionEvidenceDbSelfTest {

    private static int pass;
    private static int fail;
    private static final String PLATE = "SELF-POS-EV";

    public static void main(String[] args) throws Exception {
        DbConfig db = DbConfig.fromEnv();
        cleanup(db);
        // 按域直取 RunMapper：写路径已并入 RunService（{@code recordPositionEvidence}），
        // 本自测锁的是 SQL 落库与回读口径，不走 Web 层 Service。
        RunMapper m = com.labos.kernel.support.TestSpringSupport.runMapper();
        Long id = m.insertPositionEvidence(1L, PLATE, null, "MANUAL",
                -997001L, "tester", "人工盘点：ID1183-L3");
        check("位置证据可落库", id != null && id > 0, id);
        List<Map<String, Object>> rows = m.selectPositionEvidence(1L, PLATE, 20);
        check("按板号可查到证据", !rows.isEmpty()
                && rows.stream().anyMatch(r -> PLATE.equals(String.valueOf(r.get("plateBarcode")))),
                rows.isEmpty() ? "(空)" : String.valueOf(rows.get(0)));
        Map<String, Object> mine = rows.isEmpty() ? null : rows.get(0);
        check("证据含类型/操作人/备注", mine != null
                        && "MANUAL".equals(String.valueOf(mine.get("evidenceType")))
                        && "tester".equals(String.valueOf(mine.get("operator")))
                        && "人工盘点：ID1183-L3".equals(String.valueOf(mine.get("note"))),
                "fields");
        cleanup(db);
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void cleanup(DbConfig db) throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM position_evidence WHERE tenant_id=1 AND plate_barcode=?")) {
            ps.setString(1, PLATE);
            ps.executeUpdate();
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
