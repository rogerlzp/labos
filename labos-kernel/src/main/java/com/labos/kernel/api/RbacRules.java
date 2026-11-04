package com.labos.kernel.api;

import java.util.List;

/**
 * 角色与权限规则（纯逻辑，不碰库）：登录取 token 之后，<b>谁能写、谁能管账号、
 * 以及"最后一个管理员"的保护判定</b>都在这里，避免散落在 controller / QueryService。
 *
 * <p>为什么需要这一层：
 * <ol>
 *   <li><b>角色字符串必须有白名单</b>：账号表 role 是 varchar，建号接口直接写库时
 *       可以塞 {@code boss}/{@code root} 这类字符串；能登录、但 {@code canWrite}
 *       判断全落空，表现是"账号建好了什么都干不了"，查起来毫无线索；</li>
 *   <li><b>必须有"最后一个管理员"保护</b>：允许把唯一 admin 停用或降级，系统就再也没人
 *       能管账号（也没有 admin 能改回来），只能进库手改——这是真正的锁死风险；</li>
 *   <li><b>前端下一轮要做角色感知</b>：权限口径必须只有一个出处，
 *       否则"按钮禁不禁用"与"请求 403"会各说各话。</li>
 * </ol>
 *
 * <p>与 {@link AuthService.Session#canWrite()} 的关系：Session 是"会话态"的便捷判断，
 * 本类是纯字符串规则；两者口径一致（admin/operator 可写、仅 admin 管账号），
 * 新增角色或调整权级只改本类。
 */
public final class RbacRules {

    /** 合法角色，顺序即展示权级（高 → 低）。 */
    public static final List<String> ROLES = List.of("admin", "operator", "viewer");

    private RbacRules() {
    }

    /** 角色是否在白名单内（null/空/未知一律 false）。 */
    public static boolean validRole(String role) {
        return role != null && ROLES.contains(role);
    }

    /** 能否执行写操作（执行实验、暂停/停止、改配置等）：admin 或 operator。 */
    public static boolean canWrite(String role) {
        return "admin".equals(role) || "operator".equals(role);
    }

    /** 能否管理账号（建人/改角色/停用）：仅 admin。 */
    public static boolean canManageUsers(String role) {
        return "admin".equals(role);
    }

    /** 角色展示文案；未知角色原样返回（不要显示空白，便于发现脏数据）。 */
    public static String roleLabel(String role) {
        if (role == null) {
            return null;
        }
        return switch (role) {
            case "admin" -> "管理员";
            case "operator" -> "操作员";
            case "viewer" -> "只读";
            default -> role;
        };
    }

    /**
     * "最后一个管理员"保护：把目标用户改成 {@code newRole} / {@code newEnabled} 是否危险。
     *
     * <p>只在**目标当前是启用中的 admin、且这次会把它变成非 admin 或停用**时检查；
     * 若系统中启用中的 admin 恰好只剩 1 个（就是它），拒绝并返回中文原因，否则返回
     * {@code null} 表示允许。
     *
     * <p>几个刻意口径：
     * <ul>
     *   <li>目标不是 admin / 本来就没启用：不归这条规则管（返回 null）；</li>
     *   <li>{@code newRole}/{@code newEnabled} 传 null = 该项不改，按当前值参与判定；</li>
     *   <li>其它 admin 还有 ≥2 个：允许（有人能管回来）；</li>
     *   <li>{@code enabledAdminCount<=0}：数据里没有"在用的管理员"可保护（脏数据/边界），
     *       放行而不是拦死——真正修复应该进库盘点，而不是让接口永远返回 400。</li>
     * </ul>
     *
     * @param targetRole        目标用户当前角色
     * @param targetEnabled     目标用户当前是否启用
     * @param newRole           这次要改成的新角色；null/空 = 不改
     * @param newEnabled        这次要改成的新启用状态；null = 不改
     * @param enabledAdminCount 当前启用中的 admin 总数（含目标用户）
     * @return null = 允许；否则为中文拒绝原因（直接给接口层当 400 文案）
     */
    public static String lastAdminGuard(String targetRole, boolean targetEnabled,
                                        String newRole, Boolean newEnabled,
                                        int enabledAdminCount) {
        boolean targetIsEnabledAdmin = "admin".equals(targetRole) && targetEnabled;
        if (!targetIsEnabledAdmin) {
            return null;
        }
        String resultingRole = newRole == null || newRole.isBlank() ? targetRole : newRole;
        boolean resultingEnabled = newEnabled == null ? targetEnabled : newEnabled;
        boolean stillEnabledAdmin = "admin".equals(resultingRole) && resultingEnabled;
        if (stillEnabledAdmin) {
            return null;
        }
        if (enabledAdminCount != 1) {
            return null;
        }
        if (!"admin".equals(resultingRole) && !resultingEnabled) {
            return "不能停用或降级最后一个管理员（否则将没人能管理用户）";
        }
        if (!"admin".equals(resultingRole)) {
            return "不能降级最后一个管理员（否则将没人能管理用户）";
        }
        return "不能停用最后一个管理员（否则将没人能管理用户）";
    }
}
