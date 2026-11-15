package com.labos.boot.web.service;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.DeviceMapper;
import com.labos.kernel.persistence.Mappers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 「建岛 → 拖设备 → 造点位 → 登记驱动」这条交付链的**连库**判别性自测（2026-11-15）。
 *
 * <p><b>为什么要有</b>：这四步是交付的第一道坎，之前每一处都是空白或半成品：
 * <ul>
 *   <li>driver 登记<b>全仓没有一条 {@code INSERT INTO driver} 的 Java 路径</b> ⇒ 只能手写 SQL；</li>
 *   <li>批量造层位只有 {@code scripts/gen-grid-points.py}（要 DB 口令 + shell）⇒ 界面里做不到；</li>
 *   <li>岛<b>建完不能改</b>（没有 PUT）⇒ 改个名只能删了重建，而删岛要求"岛上没有设备"；</li>
 *   <li>deploy 出来的实例 {@code ip=NULL}、{@code driver_id} 不写 ⇒ 现场很容易漏 ⇒ 设备点不动。</li>
 * </ul>
 *
 * <p>本自测**只做纯逻辑 + 连库**，跑完自动清理（不留测试设备/点位/驱动）。
 */
public final class DeviceSetupDbSelfTest {

    private static final long TENANT = 1L;
    private static final String ISLAND_CODE = "VERIFY-SETUP-ISLAND";
    private static final String DRIVER_CODE = "VERIFY-SETUP-DRV";
    private static final String DEVICE_CODE = "VERIFY-SETUP-DEV";

    private static DbConfig db;
    private static DeviceService svc;
    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        svc = new DeviceService(db, Mappers.get(DeviceMapper.class));
        System.out.println("════ 设备接入（建岛/拖设备/造点位/登记驱动）连库自测 ════");
        System.out.println();

        cleanup();
        try {
            section("1. 登记驱动（原来只能手写 SQL）");
            long drvId = svc.createDriver(DRIVER_CODE, "验证用驱动", "tcp",
                    "tcp://127.0.0.1:19999", "验证", Boolean.FALSE);
            ok("驱动建出来了", drvId > 0);
            eq("驱动能在清单里查到", 1, count("SELECT count(*) FROM driver WHERE id = ?", drvId));
            eq("协议落库正确", "tcp",
                    str("SELECT protocol::text FROM driver WHERE id = ?", drvId));
            // 重复编号要报错，别静默插两条
            try {
                svc.createDriver(DRIVER_CODE, "重复", "tcp", "tcp://x:1", null, null);
                bad("重复驱动编号没有被拒绝");
            } catch (IllegalArgumentException e) {
                ok("重复驱动编号被拒绝", e.getMessage() != null && e.getMessage().contains("已存在"));
            }
            // 缺端点要报错
            try {
                svc.createDriver("VERIFY-SETUP-DRV2", "缺端点", "tcp", "", null, null);
                bad("缺端点没有被拒绝");
            } catch (IllegalArgumentException e) {
                ok("缺端点被拒绝", true);
            }

            section("2. 建岛 + 改岛（原来岛建完不能改）");
            long islandId = svc.createIsland(ISLAND_CODE, "验证用岛");
            ok("岛建出来了", islandId > 0);
            int up = svc.updateIsland(islandId, "验证用岛（改名）", "{\"site\":\"A3\",\"x\":12,\"y\":8}", 24);
            ok("岛改得动（原来只能删了重建）", up == 1);
            eq("名字改到了", "验证用岛（改名）",
                    str("SELECT name FROM resource_node WHERE id = ?", islandId));
            eq("物理坐标写进去了", "A3",
                    str("SELECT location->>'site' FROM resource_node WHERE id = ?", islandId));
            eq("容量写进去了", 24,
                    intOf("SELECT capacity FROM resource_node WHERE id = ?", islandId));

            section("3. 拖设备（deploy）+ 部署时直接带 IP 与驱动");
            // deploy 走型号 ⇒ 需要一个型号；这里直接造一条设备型号
            long modelId = ensureModel("VERIFY-SETUP-MODEL", "验证用型号");
            long rid = svc.deployModel(modelId, islandId, "192.168.77.21", drvId);
            ok("设备部署出来了", rid > 0);
            eq("IP 在部署时就写进去了（原来只能事后补）", "192.168.77.21",
                    str("SELECT di.ip FROM device_instance di JOIN resource_node r"
                            + " ON r.id = di.resource_id WHERE r.id = ?", rid));
            eq("驱动也在部署时就绑上了", drvId,
                    longOf("SELECT di.driver_id FROM device_instance di JOIN resource_node r"
                            + " ON r.id = di.resource_id WHERE r.id = ?", rid));

            section("4. 批量造网格点位（原来只有脚本）");
            String devCode = str("SELECT code FROM resource_node WHERE id = ?", rid);
            int n = svc.createGridPoints(rid, devCode, 3, 2, "transfer_point",
                    1, 45.0, "pcr96,deep96", true, true, true);
            eq("造了 6 个点位（3 列 × 2 行）", 6, n);
            eq("点位行数对上了", 6, intOf("SELECT count(*) FROM device_point dp"
                    + " JOIN resource_node p ON p.id = dp.point_resource_id"
                    + " WHERE dp.device_resource_id = ?", rid));
            eq("点位编号按 设备-R{行}C{列}", 1,
                    intOf("SELECT count(*) FROM device_point dp JOIN resource_node p"
                            + " ON p.id = dp.point_resource_id"
                            + " WHERE dp.device_resource_id = ? AND p.code = ?",
                            rid, devCode + "-R1C1"));
            eq("点位 kind 落对了", 1,
                    intOf("SELECT count(*) FROM device_point dp JOIN resource_node p"
                            + " ON p.id = dp.point_resource_id"
                            + " WHERE dp.device_resource_id = ? AND dp.point_kind = 'transfer_point'", rid));
            eq("网格行列落对了", 2,
                    intOf("SELECT count(*) FROM device_point dp JOIN resource_node p"
                            + " ON p.id = dp.point_resource_id"
                            + " WHERE dp.device_resource_id = ? AND dp.grid_row = 2", rid));
            // 幂等：再跑一次不新增
            int n2 = svc.createGridPoints(rid, devCode, 3, 2, "transfer_point",
                    1, 45.0, "pcr96,deep96", true, true, true);
            eq("幂等：再跑一次不新增点位", 6,
                    intOf("SELECT count(*) FROM device_point dp JOIN resource_node p"
                            + " ON p.id = dp.point_resource_id"
                            + " WHERE dp.device_resource_id = ?", rid));
            ok("返回的写入数仍是 6（复用更新，不是新建）", n2 == 6);

            section("5. 危险动作要有护栏");
            try {
                svc.createGridPoints(rid, devCode, 3, 2, "workstation", 1, null, null,
                        true, true, true);
                bad("点位 kind 不在机械臂可见的三类里，没有被拒绝");
            } catch (IllegalArgumentException e) {
                ok("非法点位 kind 被拒绝（机械臂看不见的 kind 不许建）",
                        e.getMessage() != null && e.getMessage().contains("transfer_point"));
            }
            try {
                svc.createGridPoints(rid, devCode, 0, 2, "storage", 1, null, null,
                        true, true, true);
                bad("列数为 0 没有被拒绝");
            } catch (IllegalArgumentException e) {
                ok("列数/行数越界被拒绝", true);
            }
            try {
                svc.deleteDriver(drvId);
                bad("还在被设备引用的驱动，删除没有被拒绝");
            } catch (IllegalArgumentException e) {
                ok("被设备引用的驱动不许删（否则设备变孤儿）",
                        e.getMessage() != null && e.getMessage().contains("在用"));
            }
        } finally {
            cleanup();
        }

        System.out.println();
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ================================================================ 造数 / 清理

    /** 造一条设备型号（deploy 走型号 ⇒ 需要型号行）。 */
    private static long ensureModel(String code, String name) throws SQLException {
        try (Connection c = db.open(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(
                        "SELECT id FROM device WHERE tenant_id = " + TENANT + " AND code = '"
                                + code + "' AND deleted_at IS NULL")) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        }
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO device (tenant_id, code, name, device_type)"
                        + " VALUES (?, ?, ?, 'MISC') RETURNING id")) {
            ps.setLong(1, TENANT);
            ps.setString(2, code);
            ps.setString(3, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void cleanup() {
        // ⚠ 逐条独立执行：一条失败（外键/枚举）不该把后面的清理全跳过
        // ⇒ 之前一次失败就留下 2 个岛 + 4 个驱动的残留。
        String[] sqls = {
            "DELETE FROM position_state WHERE resource_id IN"
                    + " (SELECT id FROM resource_node WHERE code LIKE 'VERIFY-SETUP-DEV%')",
            "DELETE FROM device_point WHERE device_resource_id IN"
                    + " (SELECT id FROM resource_node WHERE code LIKE 'VERIFY-SETUP-DEV%')",
            "DELETE FROM device_point WHERE point_resource_id IN"
                    + " (SELECT id FROM resource_node WHERE code LIKE 'VERIFY-SETUP-DEV%')",
            "DELETE FROM device_state WHERE resource_id IN"
                    + " (SELECT id FROM resource_node WHERE code LIKE 'VERIFY-SETUP-DEV%')",
            "DELETE FROM resource_capability WHERE resource_id IN"
                    + " (SELECT id FROM resource_node WHERE code LIKE 'VERIFY-SETUP-DEV%')",
            "DELETE FROM device_instance WHERE code LIKE 'VERIFY-SETUP-DEV%'",
            "DELETE FROM resource_node WHERE code LIKE 'VERIFY-SETUP-DEV%'",
            "DELETE FROM device WHERE code = 'VERIFY-SETUP-MODEL'",
            "DELETE FROM resource_node WHERE code = '" + ISLAND_CODE + "'",
            "DELETE FROM driver WHERE code = '" + DRIVER_CODE + "'",
        };
        for (String sql : sqls) {
            try {
                exec(sql);
            } catch (RuntimeException ignore) {
                // 一条失败不影响其余清理
            }
        }
    }

    private static void exec(String sql) {
        try (Connection c = db.open(); Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("SQL 失败: " + sql + " :: " + e.getMessage(), e);
        }
    }

    private static int intOf(String sql, Object... args) {
        Object v = one(sql, args);
        return v == null ? 0 : ((Number) v).intValue();
    }

    private static long longOf(String sql, Object... args) {
        Object v = one(sql, args);
        return v == null ? 0L : ((Number) v).longValue();
    }

    private static String str(String sql, Object... args) {
        Object v = one(sql, args);
        return v == null ? null : String.valueOf(v);
    }

    private static int count(String sql, Object... args) {
        return intOf(sql, args);
    }

    private static Object one(String sql, Object... args) {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询失败: " + sql + " :: " + e.getMessage(), e);
        }
    }

    private static void section(String s) {
        System.out.println();
        System.out.println("── " + s);
    }

    private static void ok(String what, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("  ✔ " + what);
        } else {
            fail++;
            System.out.println("  ✘ " + what);
        }
    }

    private static void eq(String what, Object expected, Object actual) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (same) {
            pass++;
            System.out.println("  ✔ " + what);
        } else {
            fail++;
            System.out.println("  ✘ " + what + "（期望 " + expected + "，实际 " + actual + "）");
        }
    }

    private static void bad(String what) {
        fail++;
        System.out.println("  ✘ " + what);
    }
}
