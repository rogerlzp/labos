package com.labos.kernel.device;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.DeviceStateMapper;
import com.labos.kernel.persistence.Mappers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * 实例号 → 型号行号换算的**连库契约自测**（2026-11-16）。
 *
 * <p><b>测什么（按事故现场 1:1 复现）</b>：
 * <ol>
 *   <li><b>撞号可复现</b>：造一个实例，其 {@code device_instance.id} 恰好等于
 *       另一个型号的 {@code device.id} —— 这正是提取线的形态
 *       （DISP3 实例号 1000039 撞上 {@code GANTRY_ROBOT_GENERIC} 的型号行 1000039）。
 *       断言「拿实例号直接查 {@code device_method}」会拿到<b>别人型号</b>的时限
 *       ⇒ 证明缺陷机理成立（这条是**回归护栏**，不是期望行为）。</li>
 *   <li><b>换算后正确</b>：经 {@link DeviceIdentityResolver} 后拿到自己型号的值，
 *       与"撞上来的那个"不同。</li>
 *   <li><b>落空不再静默</b>：型号未登记（{@code device_model_id} 为空）/ 实例不存在
 *       ⇒ 返回 {@code ModelId(0)}，且 {@link DeviceIdentityResolver#unresolved()} 增长。</li>
 *   <li><b>缓存生效</b>：同一实例第二次换算不再查库（{@code lookups()} 不增长）。</li>
 * </ol>
 *
 * <p>只造**自造数据**（负数 id / ZZTEST 前缀），跑完清理 ⇒ 不碰现场数据。
 */
public final class DeviceModelIdResolutionDbSelfTest {

    private static int pass;
    private static int fail;
    private static DbConfig db;
    private static DeviceStateMapper mapper;

    private static final long TENANT = 1L;

    /** 自造岛 / 设备资源节点。 */
    private static final long TEST_ISLAND = -997001L;
    private static final long TEST_NODE = -997002L;

    /**
     * 型号 A：超时故意很小（7 秒，复刻「轨道机械臂 7 秒」）。
     * 它的 <b>行号</b>同时被下面那个实例当作 <b>实例号</b> 使用。
     */
    private static final long MODEL_A = -997101L;
    /** 型号 B：真实设备所属型号，超时 120 秒（复刻「分液 8 秒被判 7 秒超时」）。 */
    private static final long MODEL_B = -997102L;
    /** 实例号 = 型号 A 的行号 ⇒ 撞号。它属于型号 B。 */
    private static final long INSTANCE_COLLIDING = MODEL_A;
    /** 型号未登记的实例（device_model_id 为空）。 */
    private static final long INSTANCE_NO_MODEL = -997103L;
    /** 不存在的实例。 */
    private static final long INSTANCE_MISSING = -997104L;

    private static final String METHOD = "Run";

    public static void main(String[] args) throws Exception {
        System.out.println("════ 设备型号解析 · 撞号复现 · 连库自测 ════");
        System.out.println();
        db = DbConfig.fromEnv();
        mapper = Mappers.get(DeviceStateMapper.class);

        cleanup();
        try {
            seed();
            testCollisionReproduced();
            testResolvedPath();
            testUnresolved();
            testCache();
            testInstanceScopeOverride();
        } finally {
            cleanup();
        }
        System.out.println();
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 1. 撞号

    private static void testCollisionReproduced() {
        section("1. 撞号可复现：实例号恰好等于另一型号的行号");
        eq("型号 A 的 Run 超时是 7 秒（事故里的「机械臂 7 秒」）",
                7L, mapper.selectMethodTimeoutSec(TENANT, MODEL_A, METHOD));
        eq("实例所属型号 B 的 Run 超时是 120 秒",
                120L, mapper.selectMethodTimeoutSec(TENANT, MODEL_B, METHOD));

        // 缺陷形态：把实例号直接当 device_model_id 查 ⇒ 命中型号 A。
        long wrong = mapper.selectMethodTimeoutSec(TENANT, INSTANCE_COLLIDING, METHOD);
        eq("直接拿实例号查 ⇒ 拿到**别人的** 7 秒（缺陷机理成立）", 7L, wrong);
        ne("直接拿实例号查 ≠ 自己型号的 120 秒（这就是 EC-05-1007 的来源）",
                120L, wrong);
    }

    // ---------------------------------------------------------------- 2. 换算

    private static void testResolvedPath() {
        section("2. 经 DeviceIdentityResolver 换算后正确");
        DeviceIds.ModelId model = new DeviceIdentityResolver(mapper, TENANT)
                .modelIdOf(DeviceIds.ofInstance(INSTANCE_COLLIDING));
        eq("实例 " + INSTANCE_COLLIDING + " 的型号 = 型号 B", MODEL_B, model.value());
        ne("换算结果 ≠ 实例号本身（不是把实例号原样当型号）",
                INSTANCE_COLLIDING, model.value());

        long right = mapper.selectMethodTimeoutSec(TENANT, model.value(), METHOD);
        eq("按换算后的型号查 ⇒ 自己型号的 120 秒", 120L, right);
    }

    // ---------------------------------------------------------------- 3. 落空

    private static void testUnresolved() {
        section("3. 解析不到型号：返回 0 且计数（不再静默吞成超时 0 秒）");
        DeviceIdentityResolver resolver = new DeviceIdentityResolver(mapper, TENANT);
        DeviceIdentityResolver.resetStats();

        DeviceIds.ModelId none = resolver.modelIdOf(DeviceIds.ofInstance(INSTANCE_NO_MODEL));
        check("型号未登记（device_model_id 为空）⇒ ModelId(0)", !none.known());
        eq("unresolved 计数 +1", 1L, DeviceIdentityResolver.unresolved());

        DeviceIds.ModelId missing = resolver.modelIdOf(DeviceIds.ofInstance(INSTANCE_MISSING));
        check("实例不存在 ⇒ ModelId(0)", !missing.known());
        eq("unresolved 计数 +2", 2L, DeviceIdentityResolver.unresolved());

        eq("解析不到的型号不会去查 device_method（传 0 就是 0 行）",
                0L, mapper.selectMethodTimeoutSec(TENANT, 0L, METHOD));
    }

    // ---------------------------------------------------------------- 4. 缓存

    private static void testCache() {
        section("4. 换算缓存：同一实例只查一次库");
        DeviceIdentityResolver resolver = new DeviceIdentityResolver(mapper, TENANT);
        DeviceIdentityResolver.resetStats();

        resolver.modelIdOf(DeviceIds.ofInstance(INSTANCE_COLLIDING));
        long afterFirst = DeviceIdentityResolver.lookups();
        resolver.modelIdOf(DeviceIds.ofInstance(INSTANCE_COLLIDING));
        long afterSecond = DeviceIdentityResolver.lookups();

        eq("第一次换算查库一次", 1L, afterFirst);
        eq("第二次命中缓存（不再查库）", afterFirst, afterSecond);

        resolver.invalidate(INSTANCE_COLLIDING);
        resolver.modelIdOf(DeviceIds.ofInstance(INSTANCE_COLLIDING));
        eq("invalidate 后重新查库", afterSecond + 1, DeviceIdentityResolver.lookups());
    }

    // ---------------------------------------------------------------- 5. 实例级覆盖

    /**
     * 实例级覆盖（{@code scope='instance'}，2026-11-17）。
     *
     * <p>锁两件事：
     * <ol>
     *   <li><b>能写进去</b>：同一型号同一方法可以同时有 model 行与 instance 行
     *       （唯一键已按 scope 拆成两个部分索引）。在此之前这是插不进去的 ——
     *       {@code UNIQUE(device_model_id, method)} 会直接挡住。</li>
     *   <li><b>不串味</b>：型号级查询**不能**捡到实例级行。否则一台设备的专属配置
     *       会静默变成全型号默认 —— 那等于把 BUG-20261116-02 换个方向重演。</li>
     * </ol>
     */
    private static void testInstanceScopeOverride() throws SQLException {
        section("5. 实例级覆盖（scope='instance'）");
        exec("INSERT INTO device_method (tenant_id, device_model_id, device_instance_id, method, "
                + "name, scope, param_schema, estimate_sec, timeout_sec) VALUES ("
                + TENANT + ", " + MODEL_B + ", " + INSTANCE_COLLIDING + ", '" + METHOD + "', "
                + "'ZZTEST 实例级 Run', 'instance', '{}'::jsonb, 9, 999)");
        exec("INSERT INTO device_method (tenant_id, device_model_id, device_instance_id, method, "
                + "name, scope, param_schema, estimate_sec, timeout_sec) VALUES ("
                + TENANT + ", " + MODEL_B + ", " + INSTANCE_COLLIDING + ", 'InstanceOnly', "
                + "'ZZTEST 仅实例有', 'instance', '{}'::jsonb, 5, 300)");

        eq("实例级 Run 写进去了（999）",
                999L, mapper.selectInstanceMethodTimeoutSec(TENANT, INSTANCE_COLLIDING, METHOD));
        eq("实例级库上限 = 该实例所有实例级行的最大值（999）",
                999L, mapper.selectInstanceLibraryTimeoutSec(TENANT, INSTANCE_COLLIDING));

        // 关键：型号级视角不接受影响
        eq("型号级 Run 仍是自己的 120（没被实例级 999 污染）",
                120L, mapper.selectMethodTimeoutSec(TENANT, MODEL_B, METHOD));
        eq("型号级库上限仍是 120（没被实例级 300 污染）",
                120L, mapper.selectLibraryTimeoutSec(TENANT, MODEL_B));
        eq("型号级查不到只有实例级的那个方法（0）",
                0L, mapper.selectMethodTimeoutSec(TENANT, MODEL_B, "InstanceOnly"));

        // 别的实例看不到这台设备的覆盖
        eq("同一型号另一台实例看不到 InstanceOnly",
                0L, mapper.selectInstanceMethodTimeoutSec(TENANT, INSTANCE_NO_MODEL, "InstanceOnly"));

        // 可中断：实例级优先于型号级
        exec("UPDATE device_method SET interruptible = true "
                + " WHERE scope = 'instance' AND device_instance_id = " + INSTANCE_COLLIDING
                + " AND method = '" + METHOD + "'");
        check("实例级 interruptible = true 读得到",
                Boolean.TRUE.equals(mapper.selectInstanceMethodInterruptible(
                        TENANT, INSTANCE_COLLIDING, METHOD)));
        Boolean modelLevel = mapper.selectMethodInterruptible(TENANT, MODEL_B, METHOD);
        check("型号级 interruptible 不受实例级影响（仍为 false/空）",
                modelLevel == null || !modelLevel);
    }

    // ---------------------------------------------------------------- 造数/清理

    private static void seed() throws SQLException {
        exec("INSERT INTO resource_node (id, tenant_id, parent_id, code, name, kind, path, sort_order) "
                + "VALUES (" + TEST_ISLAND + ", " + TENANT + ", NULL, 'ZZTEST-IDSP-ISLAND', "
                + "'ZZTEST id空间自测岛', 'island', 'ZZTEST-IDSP-ISLAND', 100)");
        exec("INSERT INTO resource_node (id, tenant_id, parent_id, code, name, kind, path, sort_order) "
                + "VALUES (" + TEST_NODE + ", " + TENANT + ", " + TEST_ISLAND + ", "
                + "'ZZTEST-IDSP-DEV', 'ZZTEST id空间自测设备', 'device', "
                + "'ZZTEST-IDSP-ISLAND/ZZTEST-IDSP-DEV', 100)");

        // 两个型号（device 表 = 型号定义）
        exec("INSERT INTO device (id, tenant_id, code, name, model, vendor, device_type) "
                + "VALUES (" + MODEL_A + ", " + TENANT + ", 'ZZTEST-IDSP-MODEL-A', "
                + "'ZZTEST 型号A(7s 撞号源)', 'ZZTEST-A', 'ZZTEST', 'Robots')");
        exec("INSERT INTO device (id, tenant_id, code, name, model, vendor, device_type) "
                + "VALUES (" + MODEL_B + ", " + TENANT + ", 'ZZTEST-IDSP-MODEL-B', "
                + "'ZZTEST 型号B(120s 真实型号)', 'ZZTEST-B', 'ZZTEST', 'LiquidHandlers')");

        // 方法库挂在**型号行**上（device_model_id）
        exec("INSERT INTO device_method (tenant_id, device_model_id, method, name, "
                + "capability_id, param_schema, estimate_sec, timeout_sec) "
                + "VALUES (" + TENANT + ", " + MODEL_A + ", '" + METHOD + "', "
                + "'ZZTEST 型号A-Run', NULL, '{}'::jsonb, 1, 7)");
        exec("INSERT INTO device_method (tenant_id, device_model_id, method, name, "
                + "capability_id, param_schema, estimate_sec, timeout_sec) "
                + "VALUES (" + TENANT + ", " + MODEL_B + ", '" + METHOD + "', "
                + "'ZZTEST 型号B-Run', NULL, '{}'::jsonb, 8, 120)");

        // 撞号实例：id 故意 = 型号 A 的行号，但它的型号是 B
        exec("INSERT INTO device_instance (id, tenant_id, code, name, device_model_id, "
                + "resource_id, status) VALUES (" + INSTANCE_COLLIDING + ", " + TENANT + ", "
                + "'ZZTEST-IDSP-DEV', 'ZZTEST 撞号实例', " + MODEL_B + ", " + TEST_NODE
                + ", 'available')");
        // 型号未登记的实例
        exec("INSERT INTO device_instance (id, tenant_id, code, name, device_model_id, "
                + "resource_id, status) VALUES (" + INSTANCE_NO_MODEL + ", " + TENANT + ", "
                + "'ZZTEST-IDSP-NOMODEL', 'ZZTEST 型号未登记实例', NULL, NULL, 'available')");
    }

    private static void cleanup() throws SQLException {
        execQuiet("DELETE FROM device_method WHERE scope = 'instance' AND device_instance_id IN ("
                + INSTANCE_COLLIDING + ", " + INSTANCE_NO_MODEL + ")");
        execQuiet("DELETE FROM device_method WHERE device_model_id IN ("
                + MODEL_A + ", " + MODEL_B + ")");
        execQuiet("DELETE FROM device_instance WHERE id IN ("
                + INSTANCE_COLLIDING + ", " + INSTANCE_NO_MODEL + ", " + INSTANCE_MISSING + ")");
        execQuiet("DELETE FROM device WHERE id IN (" + MODEL_A + ", " + MODEL_B + ")");
        execQuiet("DELETE FROM resource_node WHERE id IN (" + TEST_NODE + ", " + TEST_ISLAND + ")");
        DeviceIdentityResolver.resetStats();
    }

    private static void exec(String sql) throws SQLException {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    /** 清理阶段：表可能还没迁移（旧库），失败不该盖住真正的断言结果。 */
    private static void execQuiet(String sql) {
        try {
            exec(sql);
        } catch (SQLException ignore) {
            // 自造数据不存在 / 列名尚未迁移 —— 清理尽力而为
        }
    }

    // ---------------------------------------------------------------- 断言

    private static void section(String title) {
        System.out.println("── " + title + " ──");
    }

    private static void check(String label, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  ✓ " + label);
        } else {
            fail++;
            System.out.println("  ✗ " + label);
        }
    }

    private static void eq(String label, long expect, long actual) {
        if (expect == actual) {
            pass++;
            System.out.println("  ✓ " + label + "（" + actual + "）");
        } else {
            fail++;
            System.out.println("  ✗ " + label + " 期望 " + expect + " 实际 " + actual);
        }
    }

    private static void ne(String label, long unexpected, long actual) {
        if (unexpected != actual) {
            pass++;
            System.out.println("  ✓ " + label + "（实际 " + actual + "）");
        } else {
            fail++;
            System.out.println("  ✗ " + label + " 实际值不应等于 " + unexpected);
        }
    }
}
