package com.labos.boot.web;

import com.labos.boot.web.service.DeviceService;
import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.DeviceMapper;
import com.labos.kernel.persistence.DeviceStateMapper;
import com.labos.kernel.persistence.Mappers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;

/**
 * 2026-11-17 · 四个平台缺口的连库判别性自测。
 *
 * <p>四个缺口都是从零建岛实测踩出来的（详见 docs/开发日志.md）：
 * <ol>
 *   <li><b>设备没有彻底删除</b>：删除只有软删，软删行仍占编号 ⇒ 重建同名必 409；
 *       且平台根本没有物理删除路径。</li>
 *   <li><b>失败 run 的预置板不释放</b>：内核按流程 sourcePosition 预置板并把归属设成本 run，
 *       run 终结时不释放 ⇒ 下一轮认领不到 ⇒ EC-POSITION-OCCUPIED（同一岛连跑两次必撞）。</li>
 *   <li><b>writeError 吞真因</b>：唯一约束冲突一律报成「templateNo 可能重复」，
 *       真实约束名被丢掉，现场只能靠猜。</li>
 *   <li><b>同角色多块板表达不了</b>：UNIQUE(template_id, role_code) ⇒ 一模板一角色一行。</li>
 * </ol>
 */
public final class PlatformGapDbSelfTest {

    private static final long TENANT = 1L;
    // 负数 id ⇒ 不与现场数据撞（仓库既有自测的同一手法）
    private static final long DEV_RES = -999301L;
    private static final long DEV_INST = -999302L;
    private static final long DEV_INST2 = -999303L;
    private static final long POS_RES = -999311L;
    private static final long POS2_RES = -999312L;
    private static final long POS3_RES = -999313L;
    private static final long RUN_REF = -999321L;
    private static final long RUN_DONE = -999322L;
    private static final long RUN_RUNNING = -999323L;
    private static final long RUN_NEW = -999324L;
    private static final long NODE_REF = -999331L;
    private static final long TPL_ID = -999341L;
    private static final String DEV_CODE = "PGAP-DEV-1";
    private static final String PLATE = "PGAP-PLATE-1";
    private static final String PLATE2 = "PGAP-PLATE-2";
    private static final String PLATE3 = "PGAP-PLATE-3";
    // 缺口 5（整岛 purge）的一次性小岛：岛 → 设备 → 点位节点 → 点位 → 实例 → 流程 → run → 绑定 → 板
    private static final long ISL_RES = -999351L;
    private static final long ISL_DEV = -999352L;
    private static final long ISL_PNT = -999353L;
    private static final long ISL_PNT2 = -999361L;
    private static final long ISL_INST = -999354L;
    private static final long ISL_POINT = -999355L;
    private static final long ISL_WF = -999356L;
    private static final long ISL_WFN = -999357L;
    private static final long ISL_RUN = -999358L;
    private static final long ISL_RN = -999359L;
    private static final long ISL_BIND = -999360L;
    private static final String ISL_PLATE = "PGAP-PLATE-ISL-1";
    private static final String ISL_PLATE2 = "PGAP-PLATE-ISL-2";

    private static DbConfig db;
    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        cleanup();
        try {
            gap1_deviceNumberReusableAndPurge();
            gap2_occupancyReleaseAndClaim();
            gap3_errorKeepsRealCause();
            gap4_sameRoleManyRows();
            gap5_islandPurge();
        } finally {
            cleanup();
        }
        System.out.println();
        System.out.println("──────────────────────────────────────────");
        System.out.printf("通过 %d / 失败 %d%n", pass, fail);
        System.out.println("──────────────────────────────────────────");
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------ 缺口 1
    /** 设备编号软删后可重用 + purge 有护栏且能物理删干净。 */
    private static void gap1_deviceNumberReusableAndPurge() throws Exception {
        section("缺口 1 · 设备编号可重用 + 彻底删除（purge）");

        insertResourceNode(DEV_RES, DEV_CODE, "device");
        insertDeviceInstance(DEV_INST, DEV_CODE, DEV_RES);
        eq("活着的设备实例建好了", true, deviceInstanceExists(DEV_INST));

        // 软删后，同编号应当能再建（唯一性只看活着的实例）
        exec("UPDATE device_instance SET deleted_at = now() WHERE id = ?", DEV_INST);
        boolean rebuilt = true;
        try {
            insertDeviceInstance(DEV_INST2, DEV_CODE, DEV_RES);
        } catch (SQLException e) {
            rebuilt = false;
            System.out.println("      ↳ " + e.getMessage().replace('\n', ' '));
        }
        eq("软删后同编号可以再建（改动前必然 409）", true, rebuilt);

        // purge：有历史引用 ⇒ 拒绝
        insertRunNode(RUN_REF, DEV_RES);
        DeviceService svc = new DeviceService(db, Mappers.get(DeviceMapper.class));
        boolean refused = false;
        String why = "";
        try {
            svc.purgeDevice(DEV_RES);
        } catch (IllegalArgumentException e) {
            refused = true;
            why = e.getMessage();
        }
        eq("有历史引用时 purge 被拒绝", true, refused);
        eq("拒绝原因说清了引用类型", true, why.contains("引用"));

        // 清掉历史引用 ⇒ purge 成功，物理删干净
        exec("DELETE FROM run_node WHERE id = ?", RUN_REF);
        int n = svc.purgeDevice(DEV_RES);
        eq("purge 删了若干行", true, n > 0);
        eq("设备实例已被物理删除", false, deviceInstanceExists(DEV_INST));
        eq("同名新实例也被删掉（purge 按 resource_id 全清）", false, deviceInstanceExists(DEV_INST2));
        eq("资源节点已被物理删除", false, resourceNodeExists(DEV_RES));
    }

    // ------------------------------------------------------------ 缺口 2
    /** run 终结释放归属 + 下一轮能接管已终结 run 留下的板。 */
    private static void gap2_occupancyReleaseAndClaim() throws Exception {
        section("缺口 2 · run 终结释放归属 + 接管已终结 run 的板");
        DeviceStateMapper dm = Mappers.get(DeviceStateMapper.class);

        insertResourceNode(POS_RES, "PGAP-POS-1", "transfer_point");
        insertPositionState(POS_RES, PLATE, RUN_DONE);
        int n = dm.releaseRunOccupancy(TENANT, RUN_DONE);
        eq("释放了 1 行归属", 1, n);
        eq("板仍在原位（物理事实保留）", PLATE, plateOn(POS_RES));
        eq("归属已清空", true, occupancyIsNull(POS_RES));

        // 已终结的 run 留下的板 ⇒ 本轮可以接管
        insertRun(RUN_DONE, "completed");
        insertResourceNode(POS2_RES, "PGAP-POS-2", "transfer_point");
        insertPositionState(POS2_RES, PLATE2, RUN_DONE);
        eq("能接管已终结 run 的板", 1, dm.claimPosition(TENANT, POS2_RES, RUN_NEW));
        eq("接管后归属是本 run", true, String.valueOf(RUN_NEW).equals(occupancyOf(POS2_RES)));

        // 仍在跑的 run 的板 ⇒ 不抢占
        insertRun(RUN_RUNNING, "running");
        insertResourceNode(POS3_RES, "PGAP-POS-3", "transfer_point");
        insertPositionState(POS3_RES, PLATE3, RUN_RUNNING);
        eq("不抢占在跑的 run 的板", 0, dm.claimPosition(TENANT, POS3_RES, RUN_NEW));
        eq("原归属没被动", true, String.valueOf(RUN_RUNNING).equals(occupancyOf(POS3_RES)));
    }

    // ------------------------------------------------------------ 缺口 3
    /** 错误响应不再吞真因：点名约束 + 带原始异常。 */
    private static void gap3_errorKeepsRealCause() {
        section("缺口 3 · 错误响应带真因（约束名 + 原始异常）");
        String pgMsg = "ERROR: duplicate key value violates unique constraint "
                + "\"uk_manifest_item_role\"";
        var r = ApiErrors.writeError(new RuntimeException(pgMsg), "templateNo 可能重复");
        String body = String.valueOf(r.getBody());
        eq("状态码 409", 409, r.getStatusCode().value());
        eq("不再回那句误导的 hint", false, body.contains("templateNo 可能重复"));
        eq("文案点名真正的约束", true, body.contains("uk_manifest_item_role"));
        eq("带原始异常 detail", true, body.contains("\"detail\""));
        eq("带约束名字段", true, body.contains("\"constraint\":\"uk_manifest_item_role\""));

        // 认不出来的约束 ⇒ 仍给 hint，但把约束名带上去
        var r2 = ApiErrors.writeError(
                new RuntimeException("duplicate key value violates unique constraint \"uk_zzz\""),
                "编号可能重复");
        String b2 = String.valueOf(r2.getBody());
        eq("未知约束：保留 hint 并点名", true, b2.contains("编号可能重复") && b2.contains("uk_zzz"));

        // 普通异常 ⇒ 500 且带 detail
        var r3 = ApiErrors.writeError(new RuntimeException("连接超时"));
        eq("普通异常 500", 500, r3.getStatusCode().value());
        eq("普通异常也带 detail", true, String.valueOf(r3.getBody()).contains("连接超时"));
    }

    // ------------------------------------------------------------ 缺口 4
    /** 同一模板里同一角色可以有多行（每行一块板、各配各的位置）。 */
    private static void gap4_sameRoleManyRows() throws Exception {
        section("缺口 4 · 同角色多块板可以表达");
        insertTemplate(TPL_ID);
        insertManifestItem(TPL_ID, 1, "magPlate");
        insertManifestItem(TPL_ID, 2, "magPlate");
        eq("同角色插了两行", 2, countManifestItems(TPL_ID, "magPlate"));
    }

    // ------------------------------------------------------------ 缺口 5
    /**
     * 整岛彻底删除（2026-11-18 新增能力）。
     *
     * <p>判别性：① 设备在线 ⇒ 拒绝；② 有在飞的 run ⇒ 拒绝；
     * ③ 条件齐了 ⇒ 岛/设备/点位/实例/流程/run/run_node/绑定/板 全删干净；
     * ④ <b>全局时长表 duration_model 一点没动</b>（它是全局方法级表，不是岛的数据）。
     */
    private static void gap5_islandPurge() throws Exception {
        section("缺口 5 · 整岛彻底删除（purge island）");
        DeviceMapper mapper = Mappers.get(DeviceMapper.class);
        DeviceService svc = new DeviceService(db, mapper);
        long durBefore = queryLong("SELECT count(*) FROM duration_model");

        // 造一座一次性的小岛
        insertResourceNode(ISL_RES, "PGAP-ISL-1", "island");
        insertResourceNode(ISL_DEV, "PGAP-ISL-DEV-1", "device");
        exec("UPDATE resource_node SET parent_id = ? WHERE id = ?", ISL_RES, ISL_DEV);
        insertResourceNode(ISL_PNT, "PGAP-ISL-PT-1", "transfer_point");
        exec("UPDATE resource_node SET parent_id = ? WHERE id = ?", ISL_DEV, ISL_PNT);
        // ⚠ 点位节点也可能**直接挂在岛下**（不是挂在设备下）——
        //   2026-11-18 实测踩到：清理范围只覆盖「设备的子节点」⇒ 岛下节点的
        //   position_state / position_evidence 变成孤儿行（当时漏了 24 行）。
        insertResourceNode(ISL_PNT2, "PGAP-ISL-PT-2", "transfer_point");
        exec("UPDATE resource_node SET parent_id = ? WHERE id = ?", ISL_RES, ISL_PNT2);
        insertPositionState(ISL_PNT2, ISL_PLATE2, RUN_REF);
        insertDeviceInstance(ISL_INST, "PGAP-ISL-INST-1", ISL_DEV);
        // point_role 只能是 entry/exit/both/internal（ck_device_point_role）；
        // 非 internal 必须 is_entry 或 is_exit 为真（ck_device_point_flags）
        exec("INSERT INTO device_point (id, tenant_id, device_resource_id, point_resource_id, "
                + "point_role, is_entry, is_exit) VALUES (?, ?, ?, ?, 'both', true, true)",
                ISL_POINT, TENANT, ISL_DEV, ISL_PNT);
        exec("INSERT INTO workflow (id, tenant_id, code, name, island_id, version, status) "
                + "VALUES (?, ?, 'PGAP-WF-ISL-1', 'PGAP-WF-ISL-1', ?, 1, 1)", ISL_WF, TENANT, ISL_RES);
        exec("INSERT INTO workflow_node (id, tenant_id, workflow_id, node_key, name, node_type) "
                + "VALUES (?, ?, ?, 'PGAP-N1', 'PGAP-N1', 'task')", ISL_WFN, TENANT, ISL_WF);
        exec("INSERT INTO node_binding (id, tenant_id, node_id, seq, position_id, device_instance_id) "
                + "VALUES (?, ?, ?, 1, ?, ?)", ISL_BIND, TENANT, ISL_WFN, ISL_POINT, ISL_INST);
        // 先造一个**已终态**的 run（这样第一条护栏命中的是「设备在线」而不是「在飞」）
        exec("INSERT INTO run (id, tenant_id, run_no, run_type, status, workflow_id) "
                + "VALUES (?, ?, 'PGAP-RUN-ISL-1', 'experiment', 'completed'::run_status, ?)",
                ISL_RUN, TENANT, ISL_WF);
        exec("INSERT INTO run_node (id, tenant_id, run_id, node_key, resource_id) "
                + "VALUES (?, ?, ?, 'PGAP-N1', ?)", ISL_RN, TENANT, ISL_RUN, ISL_DEV);
        exec("INSERT INTO plate (tenant_id, barcode, plate_type, status, location_id) "
                + "VALUES (?, ?, 'magnet96', 'available', ?)", TENANT, ISL_PLATE, ISL_PNT);
        exec("INSERT INTO device_state (tenant_id, resource_id, status, online) "
                + "VALUES (?, ?, 'available', true)", TENANT, ISL_DEV);

        // 护栏 ①：设备还在线 ⇒ 拒绝
        boolean refusedOnline = false;
        String whyOnline = "";
        try {
            svc.purgeIsland(ISL_RES);
        } catch (IllegalArgumentException e) {
            refusedOnline = true;
            whyOnline = e.getMessage();
        }
        eq("设备在线时拒绝删岛", true, refusedOnline);
        eq("拒绝原因说清了是「在线」", true, whyOnline.contains("在线"));
        eq("拒绝时岛还在（没误删）", true, resourceNodeExists(ISL_RES));

        // 护栏 ②：还有在飞的 run ⇒ 拒绝
        exec("UPDATE device_state SET online = false WHERE resource_id = ?", ISL_DEV);
        exec("UPDATE run SET status = 'running'::run_status WHERE id = ?", ISL_RUN);
        boolean refusedRun = false;
        String whyRun = "";
        try {
            svc.purgeIsland(ISL_RES);
        } catch (IllegalArgumentException e) {
            refusedRun = true;
            whyRun = e.getMessage();
        }
        eq("有在飞的 run 时拒绝删岛", true, refusedRun);
        eq("拒绝原因说清了是「在飞」", true, whyRun.contains("在飞"));
        eq("拒绝时流程还在（没误删）", true,
                queryLong("SELECT count(*) FROM workflow WHERE id = ?", ISL_WF) == 1);

        // 条件齐了 ⇒ 删干净
        exec("UPDATE run SET status = 'completed'::run_status WHERE id = ?", ISL_RUN);
        String out = svc.purgeIsland(ISL_RES);
        eq("返回体标明已删", true, out.contains("\"purged\":true"));
        eq("岛节点已删", false, resourceNodeExists(ISL_RES));
        eq("设备节点已删", false, resourceNodeExists(ISL_DEV));
        eq("点位节点已删", false, resourceNodeExists(ISL_PNT));
        eq("设备实例已删", false, deviceInstanceExists(ISL_INST));
        eq("点位已删", 0, queryLong("SELECT count(*) FROM device_point WHERE id = ?", ISL_POINT));
        eq("流程已删", 0, queryLong("SELECT count(*) FROM workflow WHERE id = ?", ISL_WF));
        eq("流程节点已删", 0, queryLong("SELECT count(*) FROM workflow_node WHERE id = ?", ISL_WFN));
        eq("run 已删", 0, queryLong("SELECT count(*) FROM run WHERE id = ?", ISL_RUN));
        eq("run_node 已删", 0, queryLong("SELECT count(*) FROM run_node WHERE id = ?", ISL_RN));
        eq("绑定已删", 0, queryLong("SELECT count(*) FROM node_binding WHERE id = ?", ISL_BIND));
        eq("板已删", 0, queryLong("SELECT count(*) FROM plate WHERE barcode = ?", ISL_PLATE));
        eq("岛下点位节点也删了", false, resourceNodeExists(ISL_PNT2));
        eq("岛下点位节点的位置账本没留孤儿", 0,
                queryLong("SELECT count(*) FROM position_state WHERE resource_id = ?", ISL_PNT2));

        // 复核：范围查询必须全 0（服务内部也做了这道复核）
        Map<String, Object> after = mapper.selectIslandPurgeScope(TENANT, ISL_RES);
        eq("复核：设备数 0", 0, numOf(after, "devices"));
        eq("复核：流程数 0", 0, numOf(after, "workflows"));
        eq("复核：run 数 0", 0, numOf(after, "runs"));
        eq("复核：板数 0", 0, numOf(after, "plates"));

        // 全局表不许动
        eq("全局时长表没被动", durBefore, queryLong("SELECT count(*) FROM duration_model"));
    }

    // ------------------------------------------------------------ 工具
    private static void section(String t) {
        System.out.println();
        System.out.println("── " + t + " ──");
    }

    private static void ok(String msg) {
        System.out.println("  ✔ " + msg);
        pass++;
    }

    private static final long T0 = System.currentTimeMillis();

    private static void eq(String what, Object expected, Object actual) {
        // 数值要能跨类型比（Integer 2 vs Long 2 不该判失败）⇒ 用字符串比较
        boolean same = expected == null ? actual == null
                : String.valueOf(expected).equals(String.valueOf(actual));
        long ms = System.currentTimeMillis() - T0;
        if (same) {
            System.out.println("  ✔ [" + ms + "ms] " + what);
            pass++;
        } else {
            System.out.println("  ✘ [" + ms + "ms] " + what + "（期望 " + expected + "，实际 " + actual + "）");
            fail++;
        }
    }

    /** 复用一条连接：db.open() 每次新建连接（本机 ~5 秒）⇒ 一条断言开一次连接会慢到没法跑。 */
    private static Connection conn;

    private static Connection conn() throws SQLException {
        if (conn == null || conn.isClosed()) {
            conn = db.open();
        }
        return conn;
    }

    private static void exec(String sql, Object... args) throws SQLException {
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    private static long queryLong(String sql, Object... args) throws SQLException {
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private static String queryText(String sql, Object... args) throws SQLException {
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static void insertResourceNode(long id, String code, String kind) throws SQLException {
        // kind 是枚举列 resource_kind ⇒ 必须显式 cast（否则 varchar → enum 报类型错）
        exec("INSERT INTO resource_node (id, tenant_id, code, name, kind) "
                + "VALUES (?, ?, ?, ?, ?::resource_kind)", id, TENANT, code, code, kind);
    }

    private static void insertDeviceInstance(long id, String code, long res) throws SQLException {
        exec("INSERT INTO device_instance (id, tenant_id, code, name, resource_id) "
                + "VALUES (?, ?, ?, ?, ?)", id, TENANT, code, code, res);
    }

    private static void insertPositionState(long res, String plate, long run) throws SQLException {
        exec("INSERT INTO position_state (tenant_id, resource_id, plate_id, occupied_since, "
                + "occupied_by_run) VALUES (?, ?, ?, now(), ?)", TENANT, res, plate, run);
    }

    private static void insertRun(long id, String status) throws SQLException {
        // ON CONFLICT 幂等：上一轮若被强杀，本轮不必再清理
        // status 是枚举列 run_status ⇒ 必须显式 cast
        exec("INSERT INTO run (id, tenant_id, run_no, run_type, status) "
                + "VALUES (?, ?, ?, 'experiment', ?::run_status) ON CONFLICT (id) DO NOTHING",
                id, TENANT, "PGAP-RUN-" + id, status);
    }

    private static void insertRunNode(long id, long res) throws SQLException {
        // run_node 有 FK 到 run ⇒ 先把 run 行造出来（ON CONFLICT 幂等）
        exec("INSERT INTO run (id, tenant_id, run_no, run_type, status) "
                + "VALUES (?, ?, ?, 'experiment', 'completed') "
                + "ON CONFLICT (id) DO NOTHING", RUN_REF, TENANT, "PGAP-RUN-" + RUN_REF);
        exec("INSERT INTO run_node (id, tenant_id, run_id, node_key, resource_id) "
                + "VALUES (?, ?, ?, ?, ?)", id, TENANT, RUN_REF, "PGAP-NODE-" + id, res);
    }

    private static void insertTemplate(long id) throws SQLException {
        exec("INSERT INTO experiment_template (id, tenant_id, template_no, name, island_id) "
                + "VALUES (?, ?, ?, ?, ?)", id, TENANT, "PGAP-TPL-" + id, "PGAP-TPL-" + id, -999999L);
    }

    private static void insertManifestItem(long tpl, int seq, String role) throws SQLException {
        exec("INSERT INTO manifest_item (tenant_id, template_id, seq, role_code, material_kind, "
                + "quantity, required) VALUES (?, ?, ?, ?, 'plate', 1, true)",
                TENANT, tpl, seq, role);
    }

    private static boolean deviceInstanceExists(long id) throws SQLException {
        return queryLong("SELECT count(*) FROM device_instance WHERE id = ?", id) > 0;
    }

    private static boolean resourceNodeExists(long id) throws SQLException {
        return queryLong("SELECT count(*) FROM resource_node WHERE id = ?", id) > 0;
    }

    private static String plateOn(long res) throws SQLException {
        return queryText("SELECT plate_id FROM position_state WHERE tenant_id = ? AND resource_id = ?",
                TENANT, res);
    }

    private static String occupancyOf(long res) throws SQLException {
        return queryText("SELECT occupied_by_run::text FROM position_state "
                + "WHERE tenant_id = ? AND resource_id = ?", TENANT, res);
    }

    private static boolean occupancyIsNull(long res) throws SQLException {
        return occupancyOf(res) == null;
    }

    private static long countManifestItems(long tpl, String role) throws SQLException {
        return queryLong("SELECT count(*) FROM manifest_item WHERE template_id = ? AND role_code = ?",
                tpl, role);
    }

    /** 从 mapper 的 Map 结果里取计数。 */
    private static long numOf(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        if (v == null) {
            return 0L;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 清理专用：单条失败不挡后面的（否则一条失败 ⇒ 残留 ⇒ 下轮又撞）。 */
    private static void safe(String sql, Object... args) {
        try {
            exec(sql, args);
        } catch (SQLException e) {
            System.out.println("  ! 清理残留：" + e.getMessage().replace('\n', ' '));
        }
    }

    private static void cleanup() {
        // 顺序按依赖倒排：run_node -> 绑定/物料配置 -> 位置账本 -> 点位 -> 实例 -> 资源节点 -> run/模板
        // （device_instance.resource_id 也指向 resource_node => 实例要先删）
        try {
            safe("DELETE FROM run_node WHERE id = ?", RUN_REF);
            safe("DELETE FROM run_node WHERE run_id IN (?, ?, ?, ?)",
                    RUN_REF, RUN_DONE, RUN_RUNNING, RUN_NEW);
            for (long id : new long[] {DEV_RES, POS_RES, POS2_RES, POS3_RES, NODE_REF}) {
                safe("DELETE FROM node_binding WHERE position_id IN "
                        + "(SELECT id FROM device_point WHERE device_resource_id = ?)", id);
                safe("DELETE FROM node_binding WHERE device_instance_id IN "
                        + "(SELECT id FROM device_instance WHERE resource_id = ?)", id);
                safe("DELETE FROM manifest_placement WHERE position_id IN "
                        + "(SELECT id FROM device_point WHERE device_resource_id = ?)", id);
                safe("DELETE FROM position_state WHERE resource_id = ?", id);
                safe("DELETE FROM device_point WHERE device_resource_id = ?", id);
                safe("DELETE FROM device_instance WHERE resource_id = ?", id);
                safe("DELETE FROM resource_node WHERE id = ? OR parent_id = ?", id, id);
            }
            for (long id : new long[] {DEV_INST, DEV_INST2}) {
                safe("DELETE FROM device_instance WHERE id = ?", id);
            }
            for (long id : new long[] {RUN_REF, RUN_DONE, RUN_RUNNING, RUN_NEW}) {
                safe("DELETE FROM run WHERE id = ?", id);
            }
            safe("DELETE FROM manifest_item WHERE template_id = ?", TPL_ID);
            safe("DELETE FROM experiment_template WHERE id = ?", TPL_ID);
            // 缺口 5 的小岛（中途失败时残留）
            safe("DELETE FROM run_node WHERE id = ?", ISL_RN);
            safe("DELETE FROM run WHERE id = ?", ISL_RUN);
            safe("DELETE FROM node_binding WHERE id = ?", ISL_BIND);
            safe("DELETE FROM workflow_node WHERE id = ?", ISL_WFN);
            safe("DELETE FROM workflow WHERE id = ?", ISL_WF);
            safe("DELETE FROM plate_well WHERE plate_id IN (SELECT id FROM plate WHERE barcode = ?)",
                    ISL_PLATE);
            safe("DELETE FROM plate WHERE barcode = ?", ISL_PLATE);
            safe("DELETE FROM device_point WHERE device_resource_id = ?", ISL_DEV);
            safe("DELETE FROM device_state WHERE resource_id = ?", ISL_DEV);
            safe("DELETE FROM device_instance WHERE resource_id = ?", ISL_DEV);
            safe("DELETE FROM position_state WHERE resource_id = ?", ISL_PNT2);
            safe("DELETE FROM resource_node WHERE id = ? OR parent_id = ? OR id IN "
                    + "(SELECT id FROM resource_node WHERE parent_id = ?)", ISL_RES, ISL_RES, ISL_DEV);
            for (long id : new long[] {ISL_DEV, ISL_PNT, ISL_PNT2, ISL_RES}) {
                safe("DELETE FROM resource_node WHERE id = ? OR parent_id = ?", id, id);
            }
        } catch (Exception e) {
            // 清理失败**不许掩盖主体结果**（finally 抛异常会把真错吞掉）
            System.out.println("  ! 清理残留：" + e.getMessage().replace('\n', ' '));
        }
    }

}