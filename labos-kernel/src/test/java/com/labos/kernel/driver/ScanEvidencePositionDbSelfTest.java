package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.DeviceStateMapper;
import com.labos.kernel.persistence.Mappers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;

/**
 * 扫码证据接线 · 连库判别性自测（2026-11-15）。
 *
 * <p><b>这次接线解决什么</b>：抓板前的强制扫码闸门要求「扫码证据的源位」与
 * 「搬运节点的源位」是**同一个 id**（{@code authorizeAndConsume} 按
 * run + 源位 + 未消费 匹配）。但扫码设备是**机械臂**（ARM1 没有 device_point ⇒
 * 手上没有板位）⇒ 原来的「按扫描设备上的板位」取源位永远取不到 ⇒ 证据落不下来
 * ⇒ 搬运被 {@code EC-SCAN-EVIDENCE-MISSING} 拒绝（实测 run 1001662）。
 *
 * <p>⇒ 改为：扫码节点在<strong>参数</strong>里声明「扫哪个点位」
 * （{@code method_params.sourcePosition} = 点位码），走
 * {@code selectPositionResourceByPointCode}（<strong>全局</strong>解析，
 * 因为被扫点位属于别的设备）⇒ 证据源位与搬运源位一致。
 *
 * <p><b>本自测锁住的不变量</b>：
 * <ol>
 *   <li>全局解析（点位码）与设备内解析（设备 + spec）必须得到<strong>同一个</strong>
 *       point_resource_id —— 否则扫码节点与搬运节点会对不上"同一个点位"；</li>
 *   <li>证据落在该位置上 ⇒ 抓板闸门放行并<strong>消费</strong>它；</li>
 *   <li>反例：证据源位是<strong>别的</strong>点位 ⇒ 闸门拒绝（证明"源位必须一致"，
 *       不是"有证据就放行"）。</li>
 * </ol>
 */
public final class ScanEvidencePositionDbSelfTest {

    private static final long TENANT = 1L;
    private static final long RUN = 998601L;
    private static final long NODE = -998602L;
    private static final long DEV = 998611L;
    private static final long INST = 998612L;
    private static final long SRC_POS = 998613L;     // 点位资源 id（= point_resource_id）
    private static final long SRC_DP = 998614L;      // device_point.id
    private static final long OTHER_POS = 998615L;   // 另一个点位（反例用）
    private static final long OTHER_DP = 998616L;
    private static final String POINT_CODE = "SELF-SCAN-PT";
    private static final String OTHER_CODE = "SELF-SCAN-PT-2";
    private static final String PLATE = "PLATE-SCAN-SELF";
    private static final String PLATE2 = "PLATE-SCAN-SELF-2";
    private static final String SCAN = "SELF-SCAN-POS-1";

    private static DbConfig db;
    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        cleanup();
        try {
            seed();

            section("1. 两个解析口径必须指向同一个点位（接线的关键不变量）");
            DeviceStateMapper dm = Mappers.get(DeviceStateMapper.class);
            Long byPointCode = dm.selectPositionResourceByPointCode(TENANT, POINT_CODE);
            Long byDeviceSpec = dm.selectPositionResourceBySpec(TENANT, DEV, POINT_CODE);
            eq("按点位码全局解析得到源位", SRC_POS, byPointCode);
            eq("按设备内 spec 解析得到同一个源位", SRC_POS, byDeviceSpec);
            eq("两个口径一致（扫码节点与搬运节点才能对上同一个点位）", true,
                    byPointCode != null && byPointCode.equals(byDeviceSpec));

            section("2. 证据落在解析出的位置上 ⇒ 抓板闸门放行并消费");
            PositionLock lock = new PositionLock(db, TENANT);
            lock.recordScanEvidence(INST, "{\"barcode\":\"" + PLATE + "\"}",
                    RUN, NODE, SCAN, byPointCode);
            eq("证据已登记", true, evidenceExists(SCAN));
            eq("证据源位 = 解析出的点位", SRC_POS, evidencePos(SCAN));
            eq("期望码正确", PLATE, evidenceField(SCAN, "expected_barcode"));
            eq("实扫码正确", PLATE, evidenceField(SCAN, "scanned_barcode"));

            GrabScanGate.Verdict v = ScanEvidence.authorizeAndConsume(db, TENANT,
                    RUN, SRC_POS, PLATE, 71L, Duration.ofMinutes(30));
            eq("闸门放行", true, v.allow());
            eq("证据已被消费（防复用）", true, evidenceConsumed(SCAN));

            section("3. 反例：证据源位是别的点位 ⇒ 闸门拒绝");
            // 换一个 run + 另一个点位落证据，然后按**错误的**源位去要 ⇒ 必须拿不到
            lock.recordScanEvidence(INST, "{\"barcode\":\"" + PLATE2 + "\"}",
                    RUN + 1, NODE, SCAN + "-2", OTHER_POS);
            GrabScanGate.Verdict wrong = ScanEvidence.authorizeAndConsume(db, TENANT,
                    RUN + 1, SRC_POS, PLATE2, 72L, Duration.ofMinutes(30));
            eq("按别的源位要 ⇒ 拒绝", false, wrong.allow());
            eq("错误码 = 无未消费证据", "EC-SCAN-EVIDENCE-MISSING", wrong.errorCode());
            GrabScanGate.Verdict right = ScanEvidence.authorizeAndConsume(db, TENANT,
                    RUN + 1, OTHER_POS, PLATE2, 73L, Duration.ofMinutes(30));
            eq("按正确源位要 ⇒ 放行", true, right.allow());

            section("4. 反例：扫码节点没声明 sourcePosition ⇒ 回退旧口径（不改变旧行为）");
            // 用 RUN（它的板在 SRC_POS）⇒ 回退口径能取到源位
            lock.recordScanEvidence(INST, "{\"barcode\":\"" + PLATE + "\"}",
                    RUN, NODE, SCAN + "-3", null);
            eq("回退口径：源位取扫描设备上的板位", SRC_POS, evidencePos(SCAN + "-3"));
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
                + "VALUES (?, ?, 'EV-SCAN-DEV', '设备', 'device')", DEV, TENANT);
        exec("INSERT INTO device_instance (id, tenant_id, code, name, resource_id) "
                + "VALUES (?, ?, 'EV-SCAN-INST', '实例', ?)", INST, TENANT, DEV);
        // 两个点位（都是该设备上的可取板位）
        exec("INSERT INTO resource_node (id, tenant_id, code, name, kind, parent_id) "
                + "VALUES (?, ?, ?, ?, 'storage', ?)", SRC_POS, TENANT, POINT_CODE, POINT_CODE, DEV);
        exec("INSERT INTO device_point (id, tenant_id, device_resource_id, point_resource_id, "
                        + "point_role, robot_reachable, is_entry, is_exit, point_kind) "
                + "VALUES (?, ?, ?, ?, 'both', true, true, true, 'nest')",
                SRC_DP, TENANT, DEV, SRC_POS);
        exec("INSERT INTO resource_node (id, tenant_id, code, name, kind, parent_id) "
                + "VALUES (?, ?, ?, ?, 'storage', ?)", OTHER_POS, TENANT, OTHER_CODE, OTHER_CODE, DEV);
        exec("INSERT INTO device_point (id, tenant_id, device_resource_id, point_resource_id, "
                        + "point_role, robot_reachable, is_entry, is_exit, point_kind) "
                + "VALUES (?, ?, ?, ?, 'both', true, true, true, 'nest')",
                OTHER_DP, TENANT, DEV, OTHER_POS);
        // 两个点位各放一块板（⚠ position_state 上板号唯一 ⇒ 不能同一块板放两处）
        //   证据的期望码 = 该点位上那块板的条码
        exec("INSERT INTO position_state (tenant_id, resource_id, plate_id, occupied_by_run) "
                + "VALUES (?, ?, ?, ?)", TENANT, SRC_POS, PLATE, RUN);
        exec("INSERT INTO position_state (tenant_id, resource_id, plate_id, occupied_by_run) "
                + "VALUES (?, ?, ?, ?)", TENANT, OTHER_POS, PLATE2, RUN + 1);
    }

    private static boolean evidenceExists(String scanId) throws SQLException {
        return one("SELECT count(*) FROM position_scan_evidence WHERE tenant_id=? AND scan_id=?",
                TENANT, scanId) > 0;
    }

    private static Long evidencePos(String scanId) throws SQLException {
        return oneOrNull("SELECT source_position_id FROM position_scan_evidence "
                + " WHERE tenant_id=? AND scan_id=?", TENANT, scanId);
    }

    private static String evidenceField(String scanId, String col) throws SQLException {
        return oneStr("SELECT " + col + " FROM position_scan_evidence "
                + " WHERE tenant_id=? AND scan_id=?", TENANT, scanId);
    }

    private static boolean evidenceConsumed(String scanId) throws SQLException {
        return one("SELECT count(*) FROM position_scan_evidence "
                + " WHERE tenant_id=? AND scan_id=? AND consumed=true", TENANT, scanId) > 0;
    }

    private static void cleanup() throws SQLException {
        try (Connection c = db.open()) {
            exec(c, "DELETE FROM position_scan_evidence WHERE tenant_id=? AND scan_id LIKE ?",
                    TENANT, "SELF-SCAN-POS%");
            exec(c, "DELETE FROM position_state WHERE tenant_id=? AND plate_id IN (?, ?)",
                    TENANT, PLATE, PLATE2);
            exec(c, "DELETE FROM device_point WHERE tenant_id=? AND id IN (?, ?)",
                    TENANT, SRC_DP, OTHER_DP);
            // ⚠ 顺序：device_instance 先删（它外键指向 resource_node）
            exec(c, "DELETE FROM device_instance WHERE tenant_id=? AND id=?", TENANT, INST);
            exec(c, "DELETE FROM resource_node WHERE tenant_id=? AND id IN (?, ?, ?)",
                    TENANT, SRC_POS, OTHER_POS, DEV);
        }
    }

    private static void exec(String sql, Object... args) throws SQLException {
        try (Connection c = db.open()) {
            exec(c, sql, args);
        }
    }

    private static void exec(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    private static long one(String sql, Object... args) throws SQLException {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private static Long oneOrNull(String sql, Object... args) throws SQLException {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? (Long) rs.getObject(1) : null;
            }
        }
    }

    private static String oneStr(String sql, Object... args) throws SQLException {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static void section(String s) {
        System.out.println();
        System.out.println("── " + s);
    }

    private static void eq(String what, Object want, Object got) {
        boolean ok = want == null ? got == null : want.equals(got);
        if (ok) {
            pass++;
            System.out.println("  ✓ " + what + " = " + got);
        } else {
            fail++;
            System.out.println("  ✗ " + what + "：期望 " + want + "，实际 " + got);
        }
    }
}
