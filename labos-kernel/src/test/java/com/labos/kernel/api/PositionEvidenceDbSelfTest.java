package com.labos.kernel.api;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;

/** P-08 物理位置证据记录自测（需要真实库）。 */
public final class PositionEvidenceDbSelfTest {

    private static int pass;
    private static int fail;
    private static final String PLATE = "SELF-POS-EV";

    public static void main(String[] args) throws Exception {
        DbConfig db = DbConfig.fromEnv();
        cleanup(db);
        QueryService q = new QueryService(db, 1L);
        long id = q.recordPositionEvidence(PLATE, null, "MANUAL", -997001L,
                "tester", "人工盘点：ID1183-L3");
        check("位置证据可落库", id > 0, id);
        String json = q.positionEvidenceJson(PLATE, 20);
        check("按板号可查到证据", json.contains(PLATE), json.substring(0, Math.min(200, json.length())));
        check("证据含类型/操作人/备注",
                json.contains("MANUAL") && json.contains("tester")
                        && json.contains("ID1183-L3"), "fields");
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
