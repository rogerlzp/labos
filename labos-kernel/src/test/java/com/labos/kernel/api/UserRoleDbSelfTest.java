package com.labos.kernel.api;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 权限收口 落库自测（需要真实库）。
 *
 * <p>纯逻辑 {@link RbacRulesSelfTest} 证明规则本身对；这里证明**规则真的接到写入口上**：
 * <ol>
 *   <li>建号接口不再能把 {@code boss}/{@code root} 这类脏角色写进库
 *       （抛 IllegalArgumentException → ApiServer 映射 400，而不是落库或 500）；</li>
 *   <li>改号接口同样校验角色，且**拒绝时不得执行 UPDATE**（角色保持原样）；</li>
 *   <li>{@link QueryService#countEnabledAdmins()} 口径可用，且"最后管理员"保护按
 *       <b>剩余启用 admin 数</b>判定——有别的 admin 时降级某个 admin 必须允许
 *       （这条最容易被写成"目标是 admin 就拒"，那样多管理员场景就永远改不了人）；</li>
 *   <li>用户不存在时保持语义不变：返回 0，不抛异常。</li>
 * </ol>
 *
 * <p>所有账号用 {@code selftest_rbac_} 前缀，finally 里物理删除；不碰真实账号。
 */
public final class UserRoleDbSelfTest {

    private static final long TENANT = 1L;
    private static int pass;
    private static int fail;
    private static DbConfig db;
    private static QueryService query;

    public static void main(String[] args) throws Exception {
        String host = System.getenv("LABOS_DB_HOST");
        if (host == null || host.isBlank()) {
            System.out.println("  ○ 未设置 LABOS_DB_HOST，跳过（连库验证必须显式指定目标库）");
            System.out.println("通过 0, 失败 0");
            return;
        }
        db = DbConfig.fromEnv();
        query = com.labos.kernel.support.TestSpringSupport.queryService();
        cleanup();
        try {
            runChecks();
        } finally {
            cleanup();
        }
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void runChecks() throws Exception {
        String suffix = Long.toString(System.nanoTime() % 1_000_000_000L, 36);
        String prefix = "selftest_rbac_" + suffix + "_";
        String viewerName = prefix + "viewer";
        String operatorName = prefix + "operator";
        String bossName = prefix + "boss";

        // ---- 1/2. 合法角色可以建 ----
        int nv = query.createUser(viewerName, "自测 viewer", "viewer", "Viewer#123");
        long viewerId = idOf(viewerName);
        check("建 viewer 成功且可查到 id", nv == 1 && viewerId > 0,
                "created=" + nv + ", id=" + viewerId);

        int no = query.createUser(operatorName, "自测 operator", "operator", "Operator#123");
        long operatorId = idOf(operatorName);
        check("建 operator 成功且可查到 id", no == 1 && operatorId > 0,
                "created=" + no + ", id=" + operatorId);

        // ---- 3. 列表接口能看到新账号（账号管理页可核） ----
        String users = query.usersJson();
        check("usersJson 能查到两个新用户", users.contains(viewerName) && users.contains(operatorName),
                "usersJson 长度 " + users.length());

        // ---- 4. 建号：脏角色必须被白名单拒绝，且不落库 ----
        String badCreate = mustReject(() ->
                query.createUser(bossName, "自测 boss", "boss", "Boss#123"));
        check("建 role=boss 抛 IllegalArgumentException 且提示角色不合法",
                badCreate != null && badCreate.contains("角色不合法"), badCreate);
        check("拒绝后库里没有 boss 账号", idOf(bossName) == 0, "");

        // ---- 5. 改角色：合法角色生效 ----
        int changed = query.updateUser(viewerId, "operator", null, null);
        QueryService.UserRoleEnabled afterChange = query.findUserRoleEnabled(viewerId);
        check("改角色 viewer→operator 成功", changed == 1
                        && afterChange != null && "operator".equals(afterChange.role()),
                afterChange);

        // ---- 6. 改角色：脏角色被拒，且拒绝时不得执行 UPDATE ----
        String badUpdate = mustReject(() -> query.updateUser(operatorId, "boss", null, null));
        QueryService.UserRoleEnabled afterReject = query.findUserRoleEnabled(operatorId);
        check("改 role=boss 被拒且库中角色保持 operator",
                badUpdate != null && badUpdate.contains("角色不合法")
                        && afterReject != null && "operator".equals(afterReject.role()),
                badUpdate);

        // ---- 7. 管理员计数口径 ----
        int admins = query.countEnabledAdmins();
        check("countEnabledAdmins() >= 1（真实 admin 存在）", admins >= 1, admins);

        // ---- 8. 保护按"剩余启用 admin 数"：多 admin 时降级某个 admin 必须允许 ----
        String tempAdminName = prefix + "admin";
        int na = query.createUser(tempAdminName, "自测管理员", "admin", "Admin#1234");
        long tempAdminId = idOf(tempAdminName);
        int adminsBeforeDemote = query.countEnabledAdmins();
        int demoted = query.updateUser(tempAdminId, "viewer", null, null);
        QueryService.UserRoleEnabled tempAfter = query.findUserRoleEnabled(tempAdminId);
        check("多 admin 时降级某个 admin 允许（不是见 admin 就拒）",
                na == 1 && adminsBeforeDemote >= 2 && demoted == 1
                        && tempAfter != null && "viewer".equals(tempAfter.role()),
                "admins=" + adminsBeforeDemote + ", demoted=" + demoted);

        // ---- 9. 不存在的用户：返回 0，不抛异常 ----
        long ghostId = -990001L;
        int gRole = query.updateUser(ghostId, "viewer", null, null);
        int gDisable = query.updateUser(ghostId, null, Boolean.FALSE, null);
        check("停用/降级不存在的用户返回 0", gRole == 0 && gDisable == 0,
                gRole + "/" + gDisable);
    }

    // ------------------------------------------------------------ 辅助

    /** 建号/改号这类"必须被拒绝"的调用；返回异常消息，未被拒绝返回 null。 */
    private static String mustReject(ThrowingRunnable r) {
        try {
            r.run();
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        } catch (Exception e) {
            return "WRONG_EXCEPTION:" + e.getClass().getSimpleName() + ":" + e.getMessage();
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static long idOf(String username) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id FROM sys_user WHERE tenant_id = ? AND username = ?")) {
            ps.setLong(1, TENANT);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    /** 删掉自测造的行（物理删除：账号表不软删，别让自测账号长期留在现场）。 */
    private static void cleanup() throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM sys_user WHERE tenant_id = ? AND username LIKE 'selftest_rbac_%'")) {
            ps.setLong(1, TENANT);
            ps.executeUpdate();
        }
    }

    private static void check(String name, boolean ok, Object actual) {
        if (ok) {
            pass++;
            System.out.printf("  ✓ %-46s %s%n", name, actual);
        } else {
            fail++;
            System.out.printf("  ✗ %-46s 实际 %s%n", name, actual);
        }
    }

    private UserRoleDbSelfTest() {
    }
}
