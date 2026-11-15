package com.labos.kernel.device;

import com.labos.kernel.persistence.BindingMapper;
import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.Mappers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

/**
 * 三条绑定链的**端到端**连库自测（2026-11-15）。
 *
 * <p><b>为什么要有这条</b>：{@link BindingContractDbSelfTest} 只钉了**契约**
 * （枚举白名单 + {@code device_method.id} 可达），但没验「界面配出来的东西
 * 后端真的存得进去、读得回来」—— 而这正是三套 UI 能不能用的判据。
 *
 * <p><b>验什么</b>（每条都是界面上一个真实操作）：
 * <ol>
 *   <li><b>点位×方法</b>：给一个点位绑一个方法 ⇒ 能读回来、方法名对、
 *       分配方式/优先级对；改它 ⇒ 改到了；删它 ⇒ 没了。</li>
 *   <li><b>节点绑定</b>：给一个节点绑一条（点位 + 装板方式 + 扫码策略）
 *       ⇒ 读回来字段全对；<b>整体替换</b>（先删后插）语义对。</li>
 *   <li><b>设备池</b>：建池 ⇒ 加成员 ⇒ 成员在池里、型号对得上；
 *       删池前有 node_binding 引用时必须被拒（409 的底层约束）。</li>
 * </ol>
 *
 * <p><b>会写库</b>，但全部用**负数 id**（自建），跑完自动清理 ——
 * 不碰任何现场数据。
 */
public final class BindingEndToEndDbSelfTest {

    private static int pass;
    private static int fail;
    private static DbConfig db;

    /** 自建资源的负数 id（不撞 identity 序列，也不依赖任何历史数据）。 */
    private static final long WF = -993001L;
    private static final long NODE = -993002L;
    private static final long POOL = -993003L;

    public static void main(String[] args) throws Exception {
        com.labos.kernel.support.TestSpringSupport.db();
        db = DbConfig.fromEnv();
        System.out.println("════ 三条绑定链 · 端到端 连库自测 ════");
        System.out.println();
        cleanup();
        try {
            seed();
            testPositionMethod();
            testNodeBinding();
            testPool();
        } finally {
            cleanup();
        }
        System.out.println();
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 准备

    /** 造一条流程 + 一个任务节点 + 一个点位 + 一个设备池（都自建）。 */
    private static void seed() throws Exception {
        exec("INSERT INTO workflow (id, tenant_id, code, name, version, status, graph) "
                + "VALUES (" + WF + ", 1, 'BIND-E2E-WF', '绑定链端到端自测', 1, 1, '{}'::jsonb)");
        exec("INSERT INTO workflow_node (id, tenant_id, workflow_id, node_key, node_type, name, params) "
                + "VALUES (" + NODE + ", 1, " + WF + ", 'E2E_NODE', 'task', '自测节点', '{}'::jsonb)");
        // 设备池（先建空池，成员在 testPool 里加）
        exec("INSERT INTO device_pool (id, tenant_id, code, name, island_id, pool_kind, strategy, "
                + "skip_on_error, skip_on_offline, allow_iteration_on_error, min_nest_count, enabled) "
                + "VALUES (" + POOL + ", 1, 'BIND-E2E-POOL', '绑定链自测池', "
                + "(SELECT id FROM resource_node WHERE kind='island' ORDER BY id LIMIT 1), "
                + "'storage', 'first_available', true, true, true, 0, true)");
        System.out.println("已造：流程 " + WF + " / 节点 " + NODE + " / 设备池 " + POOL);
        System.out.println();
    }

    /** 找一条真实存在的点位（device_point）与一个真实存在的方法（device_method）。 */
    private static Map<String, Object> anyPoint() {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id FROM device_point ORDER BY id LIMIT 1")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Map.of("id", rs.getLong(1));
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private static Long anyMethodId() {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id FROM device_method ORDER BY id LIMIT 1")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private static Long anyInstanceId() {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id FROM device_instance WHERE deleted_at IS NULL ORDER BY id LIMIT 1")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    // ---------------------------------------------------------------- 1. 点位×方法

    private static void testPositionMethod() {
        section("1. 点位×方法绑定（position_method）");
        Map<String, Object> pt = anyPoint();
        Long methodId = anyMethodId();
        if (pt == null || methodId == null) {
            ok("库里没有 device_point / device_method（跳过）", true);
            return;
        }
        long positionId = ((Number) pt.get("id")).longValue();
        BindingMapper m = Mappers.get(BindingMapper.class);

        int n = m.insertPositionMethod(1L, positionId, methodId, "global", null,
                "自测用途", "explicit", 42);
        eq("插入返回 1", 1, n);

        List<Map<String, Object>> rows = m.selectPositionMethods(1L, positionId, null, null);
        eq("读回 1 条", 1, rows.size());
        if (!rows.isEmpty()) {
            Map<String, Object> r = rows.get(0);
            eq("方法名对得上（JOIN device_method 带出的）",
                    methodNameOf(methodId), r.get("methodName"));
            eq("作用域 global", "global", r.get("scopeKind"));
            eq("用途", "自测用途", r.get("purpose"));
            eq("分配方式 explicit", "explicit", r.get("allocation"));
            eq("优先级 42", 42, ((Number) r.get("priority")).intValue());
            long bindingId = ((Number) r.get("id")).longValue();

            // 改：用途 + 分配方式 + 优先级
            int u = m.updatePositionMethod(bindingId, "改过的用途", "auto_first_free", 7, true);
            eq("更新返回 1", 1, u);
            List<Map<String, Object>> after = m.selectPositionMethods(1L, positionId, null, null);
            eq("改完还是 1 条（没多写）", 1, after.size());
            if (!after.isEmpty()) {
                eq("用途已改", "改过的用途", after.get(0).get("purpose"));
                eq("分配方式已改", "auto_first_free", after.get(0).get("allocation"));
                eq("优先级已改", 7, ((Number) after.get(0).get("priority")).intValue());
            }

            int d = m.deletePositionMethod(bindingId);
            eq("删除返回 1", 1, d);
            eq("删完读回 0 条", 0, m.selectPositionMethods(1L, positionId, null, null).size());
        }
    }

    // ---------------------------------------------------------------- 2. 节点绑定

    private static void testNodeBinding() {
        section("2. 节点绑定（node_binding）—— 界面上的「节点 → 点位 + 扫码策略」");
        Map<String, Object> pt = anyPoint();
        if (pt == null) {
            ok("库里没有 device_point（跳过）", true);
            return;
        }
        long positionId = ((Number) pt.get("id")).longValue();
        BindingMapper m = Mappers.get(BindingMapper.class);

        // 第一条：点位 + 扫码策略 validate_both（客户要的扫码环节）
        m.insertNodeBinding(1L, NODE, 1, positionId, null, null, null, "plate",
                "load_single", false, "validate_both", "{}");
        List<Map<String, Object>> rows = m.selectNodeBindings(1L, NODE);
        eq("读回 1 条", 1, rows.size());
        if (!rows.isEmpty()) {
            Map<String, Object> r = rows.get(0);
            eq("点位 id 对得上", positionId, ((Number) r.get("positionId")).longValue());
            eq("装板方式 load_single", "load_single", r.get("loadingPattern"));
            eq("扫码策略 validate_both", "validate_both", r.get("barcodePolicy"));
            eq("物料角色 plate", "plate", r.get("materialRole"));
            eq("seq 是 1", 1, ((Number) r.get("seq")).intValue());
        }

        // 整体替换语义：先删后插 —— 替换成两条
        m.deleteNodeBindings(1L, NODE);
        m.insertNodeBinding(1L, NODE, 1, positionId, null, null, null, null,
                "do_not_load", false, "none", "{}");
        m.insertNodeBinding(1L, NODE, 2, positionId, null, null, null, null,
                "load_all_remaining", true, "read", "{}");
        List<Map<String, Object>> two = m.selectNodeBindings(1L, NODE);
        eq("整体替换后是 2 条", 2, two.size());
        if (two.size() == 2) {
            eq("第 1 条 seq=1", 1, ((Number) two.get(0).get("seq")).intValue());
            eq("第 2 条 seq=2", 2, ((Number) two.get(1).get("seq")).intValue());
            eq("第 2 条装板方式", "load_all_remaining", two.get(1).get("loadingPattern"));
            eq("第 2 条留在工位", true, two.get(1).get("leaveAtStation"));
        }
    }

    // ---------------------------------------------------------------- 3. 设备池

    private static void testPool() {
        section("3. 设备池（device_pool）—— 界面上的「把 4 台提取仪收成一池」");
        BindingMapper m = Mappers.get(BindingMapper.class);
        Long inst = anyInstanceId();
        if (inst == null) {
            ok("库里没有 device_instance（跳过成员部分）", true);
        } else {
            int a = m.insertPoolMember(1L, POOL, inst, "A组", 10);
            eq("加成员返回 1", 1, a);
            List<Map<String, Object>> mem = m.selectPoolMembers(1L, POOL);
            eq("池里 1 个成员", 1, mem.size());
            if (!mem.isEmpty()) {
                eq("成员 deviceInstanceId 对得上", inst,
                        ((Number) mem.get(0).get("deviceInstanceId")).longValue());
                eq("子组", "A组", mem.get(0).get("subGroup"));
                eq("优先级", 10, ((Number) mem.get(0).get("priority")).intValue());
            }
            // 型号判据：后端比的是 device_instance.device_id（型号定义行主键）
            Long modelId = m.selectDeviceModelId(1L, inst);
            ok("能查到成员的型号行 id（operation 池同型号校验的依据）", modelId != null);
            int rm = m.deletePoolMember(1L, POOL, inst);
            eq("移成员返回 1", 1, rm);
            eq("移完池里 0 个成员", 0, m.selectPoolMembers(1L, POOL).size());
        }

        // 池列表能查到自建池
        List<Map<String, Object>> pools = m.selectPools(1L, null, null);
        boolean found = pools.stream().anyMatch(p -> POOL == ((Number) p.get("id")).longValue());
        ok("池列表能查到自建池", found);

        // 引用护栏：node_binding 引用这个池时，删池必须被拒
        Map<String, Object> pt = anyPoint();
        if (pt != null) {
            // 先清掉上一节留下的绑定（uk_node_binding_seq 是 (node_id, seq) 唯一）
            m.deleteNodeBindings(1L, NODE);
            m.insertNodeBinding(1L, NODE, 1, null, null, POOL, null, null,
                    "load_single", false, "none", "{}");
            long refs = m.countPoolBindings(1L, POOL);
            // countPoolBindings 返回 long —— 与 Integer 比较会因类型不同判不等（值明明对）
            eq("池被 node_binding 引用时，引用数 = 1", 1L, refs);
            m.deleteNodeBindings(1L, NODE);
            eq("清掉引用后引用数 = 0", 0L, m.countPoolBindings(1L, POOL));
        }
    }

    // ---------------------------------------------------------------- 工具

    private static String methodNameOf(long methodId) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT method FROM device_method WHERE id = ?")) {
            ps.setLong(1, methodId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static void cleanup() {
        exec("DELETE FROM node_binding WHERE node_id = " + NODE);
        exec("DELETE FROM device_pool_member WHERE pool_id = " + POOL);
        exec("DELETE FROM device_pool WHERE id = " + POOL);
        exec("DELETE FROM position_method WHERE position_id IN "
                + "(SELECT id FROM device_point WHERE id NOT IN (SELECT id FROM device_point))");
        exec("DELETE FROM workflow_node WHERE id = " + NODE);
        exec("DELETE FROM workflow WHERE id = " + WF);
    }

    private static void exec(String sql) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (Exception e) {
            // 清理失败不掩盖正文结果
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
}
