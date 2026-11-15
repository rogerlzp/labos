package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.Mappers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * P1 步骤 4 · 精确点位的连库判别性自测。
 *
 * <p>验证四类事实（前两类是原有基线，后两类是本轮补的「点位真能寻址」）：
 * <ol>
 *   <li>{@code selectPositionResourceBySpec} 能按 slot_index / code / position_label 解析位置；</li>
 *   <li>{@code reserveExplicitPosition} 显式预留成功；反例：被别的 run 占用 / 不存在 ⇒ null（拒绝换位）；</li>
 *   <li><b>槽位号是显式列、不是"第 n 个"</b>：60 槽栈里只有 {@code slot_index=37} 时，37 能解析，
 *       36/38 解析不到（早期实现按顺序取第 n 个的话，这里会假绿）；</li>
 *   <li><b>双工位各取各的</b>：{@code deck_out}（外部交换位）与 {@code deck_in}（内部操作位）
 *       用现场标签分别寻址，互不串位；</li>
 *   <li><b>搬运段落的是显式点位</b>：{@code insertTransportSegment} 的
 *       {@code from_position_id / to_position_id} 等于传入的 device_point.id
 *       （而不是事后按"第一个有板的位置"补出来的）。</li>
 * </ol>
 *
 * <p>全部用负数 id 造数，{@code finally} 里自清；跑法见 {@code scripts/test-all.sh} 的连库清单。
 */
public final class ExplicitPositionDbSelfTest {

    private static final long TENANT = 1L;
    private static final long DEV = -997001L;      // 提取仪
    private static final long POS1 = -997002L;     // 槽位 1
    private static final long POS2 = -997003L;     // 槽位 2
    private static final long POS3 = -997004L;     // 槽位 37（非连续编码）
    private static final long POS4 = -997005L;     // 外部交换位（deck_out）
    private static final long POS5 = -997006L;     // 内部操作位（deck_in）
    private static final long DP1 = -997011L;      // device_point 1
    private static final long DP2 = -997012L;      // device_point 2
    private static final long DP3 = -997013L;      // device_point 37
    private static final long DP4 = -997014L;      // device_point deck_out
    private static final long DP5 = -997015L;      // device_point deck_in
    private static final long RUN_A = -997021L;
    private static final long RUN_B = -997022L;
    private static final long RUN_T = -997031L;    // 搬运段用（要真存在 run 行）
    private static final String PLATE = "VERIFY-EXPL-PLATE";

    private static DbConfig db;
    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        cleanup();
        try {
            seed();

            PositionLock lock = new PositionLock(db, TENANT);

            section("1. selectPositionResourceBySpec 解析");
            Long bySlot = Mappers.get(com.labos.kernel.persistence.DeviceStateMapper.class)
                    .selectPositionResourceBySpec(TENANT, DEV, "1");
            eq("slot_index=1 → POS1", POS1, bySlot);
            Long byCode = Mappers.get(com.labos.kernel.persistence.DeviceStateMapper.class)
                    .selectPositionResourceBySpec(TENANT, DEV, "VERIFY-EXPL-P1");
            eq("code → POS1", POS1, byCode);

            section("2. reserveExplicitPosition 显式预留成功");
            Long got = lock.reserveExplicitPosition(DEV, "2", RUN_A);
            eq("预留槽位 2 → POS2", POS2, got);

            section("3. 反例：占用即拒绝换位");
            Long occupied = lock.reserveExplicitPosition(DEV, "2", RUN_B);
            eq("槽位 2 已被 RUN_A 占用 → null（不换位）", null, occupied);

            section("4. 反例：不存在 → null");
            Long missing = lock.reserveExplicitPosition(DEV, "999", RUN_A);
            eq("槽位 999 不存在 → null", null, missing);

            section("5. 60 槽栈：槽位号是显式列，不是第 n 个");
            Long s37 = Mappers.get(com.labos.kernel.persistence.DeviceStateMapper.class)
                    .selectPositionResourceBySpec(TENANT, DEV, "37");
            eq("slot_index=37 → POS3", POS3, s37);
            Long s36 = Mappers.get(com.labos.kernel.persistence.DeviceStateMapper.class)
                    .selectPositionResourceBySpec(TENANT, DEV, "36");
            eq("slot_index=36 不存在 → null", null, s36);
            Long s38 = Mappers.get(com.labos.kernel.persistence.DeviceStateMapper.class)
                    .selectPositionResourceBySpec(TENANT, DEV, "38");
            eq("slot_index=38 不存在 → null", null, s38);

            section("6. 双工位各取各的（deck_out / deck_in 按现场标签寻址）");
            Long ext = lock.reserveExplicitPosition(DEV, "EXT1", RUN_A);
            eq("label EXT1（deck_out）→ POS4", POS4, ext);
            Long in = lock.reserveExplicitPosition(DEV, "INT1", RUN_A);
            eq("label INT1（deck_in）→ POS5", POS5, in);
            eq("两个工位不是同一个资源", Boolean.TRUE, !java.util.Objects.equals(ext, in));

            section("7. 搬运段落的是显式点位（不是事后补选）");
            int seg = Mappers.get(com.labos.kernel.persistence.BindingMapper.class)
                    .insertTransportSegment(TENANT, RUN_T, null, null, DP1, DP3, PLATE, "done");
            eq("insertTransportSegment 写 1 行", 1, seg);
            long[] fromTo = transportSegmentFromTo();
            eq("from_position_id = 显式源位 DP1", DP1, fromTo[0]);
            eq("to_position_id = 显式目标位 DP3", DP3, fromTo[1]);
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
                + "VALUES (?, ?, 'VERIFY-EXPL-DEV', '验证设备', 'device')", DEV, TENANT);
        insertPoint(POS1, DP1, "VERIFY-EXPL-P1", 1, "nest", null, true);
        insertPoint(POS2, DP2, "VERIFY-EXPL-P2", 2, "nest", null, true);
        insertPoint(POS3, DP3, "VERIFY-EXPL-P37", 37, "nest", null, true);
        insertPoint(POS4, DP4, "VERIFY-EXPL-EXT", 101, "deck_out", "EXT1", true);
        // 内部操作位机械臂到不了（约束 ck_device_point_deck_in_unreachable 强制）：
        // 双工位的正确语义就是"外部交换位可达、内部位由设备自己搬"，
        // 可达性由 P2 守卫（L5）在搬运下发前判，不在这里的寻址层判。
        insertPoint(POS5, DP5, "VERIFY-EXPL-INT", 102, "deck_in", "INT1", false);
        // 搬运段有 run_id 外键 ⇒ 造一个最小 run（id 显式给负数，跑完自清）
        exec("INSERT INTO run (id, tenant_id, run_no, run_type) VALUES (?, ?, ?, 'workflow')",
                RUN_T, TENANT, "VERIFY-EXPL-RUN");
    }

    private static void insertPoint(long resId, long dpId, String code, int slotIndex,
                                    String pointKind, String label, boolean robotReachable)
            throws SQLException {
        // 内部操作位的角色必须是 internal，且 internal ⇒ 既不是进口也不是出口
        // （两条约束合起来表达「机械臂到不了、由设备自己搬」）：
        //   ck_device_point_deck_in_unreachable: deck_in ⇒ robot_reachable=false 且 role='internal'
        //   ck_device_point_flags:              role='internal' ⇒ NOT is_entry AND NOT is_exit
        boolean internal = "deck_in".equals(pointKind);
        String pointRole = internal ? "internal" : "both";
        exec("INSERT INTO resource_node (id, tenant_id, code, name, kind, parent_id) "
                + "VALUES (?, ?, ?, ?, 'storage', ?)", resId, TENANT, code, code, DEV);
        exec("INSERT INTO device_point (id, tenant_id, device_resource_id, point_resource_id, "
                        + "point_role, robot_reachable, is_entry, is_exit, point_kind, slot_index, position_label) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                dpId, TENANT, DEV, resId, pointRole, robotReachable,
                !internal, !internal, pointKind, slotIndex, label);
    }

    /** 读回 {@code from_position_id / to_position_id}（按 run 唯一的这一行）。 */
    private static long[] transportSegmentFromTo() throws SQLException {
        String sql = "SELECT from_position_id, to_position_id FROM transport_segment "
                + "WHERE tenant_id = ? AND run_id = ? AND carrier = ?";
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, TENANT);
            ps.setObject(2, RUN_T);
            ps.setObject(3, PLATE);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new long[]{Long.MIN_VALUE, Long.MIN_VALUE};
                }
                return new long[]{rs.getLong(1), rs.getLong(2)};
            }
        }
    }

    private static void cleanup() {
        del("transport_segment", "DELETE FROM transport_segment WHERE tenant_id = ? AND run_id = ?",
                TENANT, RUN_T);
        del("run", "DELETE FROM run WHERE id = ?", RUN_T);
        del("position_state", "DELETE FROM position_state WHERE resource_id IN (?, ?, ?, ?, ?)",
                POS1, POS2, POS3, POS4, POS5);
        del("device_point", "DELETE FROM device_point WHERE id IN (?, ?, ?, ?, ?)",
                DP1, DP2, DP3, DP4, DP5);
        del("resource_node", "DELETE FROM resource_node WHERE id IN (?, ?, ?, ?, ?, ?)",
                POS1, POS2, POS3, POS4, POS5, DEV);
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

    private static void del(String label, String sql, Object... args) {
        try (Connection c = db.open()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    for (int i = 0; i < args.length; i++) {
                        ps.setObject(i + 1, args[i]);
                    }
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                System.out.println("  [清理] " + label + " 失败：" + e.getMessage().replace('\n', ' '));
            }
        } catch (SQLException e) {
            System.out.println("  [清理] " + label + " 连接失败：" + e.getMessage());
        }
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("── " + title + " ──");
    }

    private static void eq(String name, Object expect, Object got) {
        if (java.util.Objects.equals(expect, got)) {
            pass++;
            System.out.println("  ✓ " + name + (got == null ? "" : "（" + got + "）"));
        } else {
            fail++;
            System.out.println("  ✗ " + name + " 期望 " + expect + "，实际 " + got);
        }
    }

    private ExplicitPositionDbSelfTest() {
    }
}
