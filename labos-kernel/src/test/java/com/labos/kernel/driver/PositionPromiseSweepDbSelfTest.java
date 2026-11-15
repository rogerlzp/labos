package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.workflow.PauseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;

/**
 * P2 B-3（2026-11-15）· 永久承诺生命周期 连库判别性自测。
 *
 * <p>验证三件事：
 * <ol>
 *   <li><b>孤儿承诺可被发现</b>：run 已不存在/已终态时，{@code selectOrphanedPromises}
 *       命中永久承诺（reserved_until IS NULL），不命中 TTL 预约；</li>
 *   <li><b>暂停清理不删永久承诺</b>（本次修的 bug）：改动前
 *       {@code releasePositionReservations} 把 plate_id IS NULL 的行全删——
 *       证据确认路径 REPORTED 未确认的目标位承诺在 run 任意一次暂停时都会被删，
 *       别的 run 就能往物理有板的格子里放板。修后只清 TTL 预约；</li>
 *   <li><b>人工盘点写占用消费承诺</b>：locatePlate 报具体位置 id →
 *       putPlateOnPosition 写占用 + 清承诺 → 孤儿查询不再命中。</li>
 * </ol>
 */
public final class PositionPromiseSweepDbSelfTest {

    private static final long TENANT = 1L;
    private static final long POS_P = -999121L;        // 永久承诺位（孤儿）＝ resource_node.id
    private static final long POS_Q = -999122L;        // TTL 预约位（对照）＝ resource_node.id
    private static final long RUN_ORPHAN = -999141L;   // 无 run 行 = 孤儿
    private static final long RUN_TTL = -999142L;      // 无 run 行 = 孤儿（对照用）
    private static final String PLATE = "PLATE-SWEEP-1";

    private static DbConfig db;
    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        cleanup();

        try {
            section("0. 种子：resource_node 两行 + 永久承诺 + TTL 预约");
            insertResource(POS_P, "SWEEP-POS-P");
            insertResource(POS_Q, "SWEEP-POS-Q");
            insertPromise(POS_P, RUN_ORPHAN, null);                        // 永久承诺
            insertPromise(POS_Q, RUN_TTL, "now() + interval '10 minutes'"); // TTL 预约

            section("1. 孤儿承诺查询：命中永久承诺、不命中 TTL 预约");
            eq("孤儿查询命中 POS_P", true, hasOrphan(POS_P));
            eq("孤儿查询不命中 POS_Q（TTL 非永久）", false, hasOrphan(POS_Q));

            section("2. 回归：pause 清理只删 TTL 预约，不删永久承诺（本次修的 bug）");
            new PauseManager(db, TENANT).releaseResources(RUN_TTL);
            eq("TTL 预约被 pause 清理删除", false, rowExists(POS_Q));
            new PauseManager(db, TENANT).releaseResources(RUN_ORPHAN);
            eq("永久承诺不被 pause 清理删除（改动前这里会删）", true, rowExists(POS_P));

            section("3. reportOrphanedPromises 计数");
            PositionLock lock = new PositionLock(db, TENANT);
            int orphaned = lock.reportOrphanedPromises();
            eq("至少报出 1 个孤儿承诺（POS_P）", true, orphaned >= 1);

            section("4. 人工盘点按位写占用 → 消费承诺");
            boolean ok = new PauseManager(db, TENANT).locatePlate(
                    PLATE, String.valueOf(POS_P), "tester", RUN_ORPHAN);
            eq("locatePlate 成功", true, ok);
            eq("位置账本已写占用（plate_id=板号）", PLATE, plateOn(POS_P));
            eq("承诺已消费（reserved_by_run 清空）", true, promiseCleared(POS_P));
            eq("孤儿查询不再命中 POS_P", false, hasOrphan(POS_P));

            section("5. 反例：同一块板盘点到第二个位置 → uk_pos_plate 拦截，账本不动");
            boolean ok2 = new PauseManager(db, TENANT).locatePlate(
                    PLATE, String.valueOf(POS_Q), "tester", RUN_ORPHAN);
            eq("locatePlate 成功（板状态仍更新，写账本失败只告警）", true, ok2);
            eq("第二位置没有写入（uk_pos_plate 拒绝同板第二位）", true,
                    plateOn(POS_Q) == null);
            eq("POS_P 占用不被误改（仍是这块板）", PLATE, plateOn(POS_P));
        } finally {
            cleanup();
        }

        System.out.println();
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 种子/清理

    private static void insertResource(long id, String code) throws SQLException {
        // ON CONFLICT DO UPDATE：残留的旧种子行（可能是别的 kind）必须被覆盖，
        // 否则 selectPositionKind 返回 null，盘点写占用被跳过（首跑踩过）。
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO resource_node (tenant_id, id, code, name, kind) "
                             + "VALUES (?, ?, ?, ?, 'transfer_point') "
                             + "ON CONFLICT (id) DO UPDATE "
                             + "SET tenant_id = EXCLUDED.tenant_id, code = EXCLUDED.code, "
                             + "name = EXCLUDED.name, kind = EXCLUDED.kind")) {
            ps.setLong(1, TENANT);
            ps.setLong(2, id);
            ps.setString(3, code);
            ps.setString(4, "自测-" + code);
            ps.executeUpdate();
        }
    }

    /** reservedUntilSql：null = 永久承诺（reserved_until NULL）；否则为 SQL 表达式。 */
    private static void insertPromise(long resourceId, long runId,
                                      String reservedUntilSql) throws SQLException {
        String sql = "INSERT INTO position_state (tenant_id, resource_id, reserved_until, "
                + "reserved_by_run) VALUES (?, ?, "
                + (reservedUntilSql == null ? "NULL" : reservedUntilSql) + ", ?)";
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, TENANT);
            ps.setLong(2, resourceId);
            ps.setLong(3, runId);
            ps.executeUpdate();
        }
    }

    private static boolean rowExists(long resourceId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM position_state WHERE tenant_id = ? AND resource_id = ?")) {
            ps.setLong(1, TENANT);
            ps.setLong(2, resourceId);
            try (var rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean hasOrphan(long resourceId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM position_state WHERE tenant_id = ? AND resource_id = ? "
                             + "AND reserved_until IS NULL AND reserved_by_run IS NOT NULL "
                             + "AND NOT EXISTS (SELECT 1 FROM run r WHERE r.tenant_id = "
                             + "position_state.tenant_id AND r.id = position_state.reserved_by_run "
                             + "AND r.status::text IN ('pending','queued','running','paused'))")) {
            ps.setLong(1, TENANT);
            ps.setLong(2, resourceId);
            try (var rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static String plateOn(long resourceId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT plate_id FROM position_state "
                             + "WHERE tenant_id = ? AND resource_id = ?")) {
            ps.setLong(1, TENANT);
            ps.setLong(2, resourceId);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static boolean promiseCleared(long resourceId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM position_state WHERE tenant_id = ? AND resource_id = ? "
                             + "AND reserved_by_run IS NULL")) {
            ps.setLong(1, TENANT);
            ps.setLong(2, resourceId);
            try (var rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void cleanup() {
        exec("DELETE FROM position_state WHERE tenant_id = ? AND resource_id IN (?, ?)",
                ps -> {
                    ps.setLong(1, TENANT);
                    ps.setLong(2, POS_P);
                    ps.setLong(3, POS_Q);
                });
        exec("DELETE FROM resource_node WHERE tenant_id = ? AND id IN (?, ?)",
                ps -> {
                    ps.setLong(1, TENANT);
                    ps.setLong(2, POS_P);
                    ps.setLong(3, POS_Q);
                });
        exec("DELETE FROM position_evidence WHERE tenant_id = ? AND plate_barcode = ?",
                ps -> {
                    ps.setLong(1, TENANT);
                    ps.setString(2, PLATE);
                });
    }

    private interface Bind {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private static void exec(String sql, Bind bind) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            bind.bind(ps);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.out.println("  [清理] 失败：" + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 断言

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

    private PositionPromiseSweepDbSelfTest() {
    }
}
