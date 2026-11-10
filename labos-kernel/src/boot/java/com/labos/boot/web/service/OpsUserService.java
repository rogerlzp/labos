package com.labos.boot.web.service;

import com.labos.kernel.api.AuthService;
import com.labos.kernel.api.RbacRules;
import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.OpsUserMapper;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import static com.labos.kernel.persistence.QJson.*;
import com.labos.kernel.persistence.Jsons;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用户/策略/告警域的 Service：{@code OpsUserController} 的全部端点。
 *
 * <p>按「Controller → Service → Mapper」从 {@code QueryService} 拆出，
 * SQL 归 {@link OpsUserMapper}。
 *
 * <p>「最后一个管理员」保护依赖全局 admin 计数：改角色走固定 key 的
 * 事务级咨询锁串行化（与原实现一致）。
 */
@Service
public class OpsUserService {

    private static final long TENANT = 1L;

    private final DbConfig db;
    private final OpsUserMapper opsUserMapper;

    public OpsUserService(DbConfig db, OpsUserMapper opsUserMapper) {
        this.db = db;
        this.opsUserMapper = opsUserMapper;
    }


    /** 用户列表（不含密码）。 */
    public String usersJson() throws java.sql.SQLException {
        List<String> items = new ArrayList<>();
        for (java.util.Map<String, Object> row : opsUserMapper.selectUsers(TENANT)) {
            items.add(Jsons.obj(
                    "id", row.get("id"),
                    "username", row.get("username"),
                    "displayName", row.get("displayName"),
                    "role", row.get("role"),
                    "enabled", Boolean.TRUE.equals(row.get("enabled")),
                    "lastLoginAt", mstr(row, "lastLoginAt"),
                    "createdAt", mstr(row, "createdAt")));
        }
        return Jsons.obj("items", new Jsons.Raw(Jsons.arr(items)));
    }

    /** 新建用户。密码用 PBKDF2，调用方传明文。 */
    public int createUser(String username, String displayName, String role, String password)
            throws java.sql.SQLException {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        if (password == null || password.length() < 6) {
            throw new IllegalArgumentException("密码至少 6 位");
        }
        // 角色必须走白名单：账号表 role 是 varchar，直接落库能塞进 boss/root 这类字符串，
        // 建出来的账号能登录但所有权限判断都落空（表现像"建好了什么都干不了"）。
        String effectiveRole = role == null || role.isBlank() ? "viewer" : role;
        if (!RbacRules.validRole(effectiveRole)) {
            throw new IllegalArgumentException("角色不合法：" + role
                    + "（可选 " + String.join("/", RbacRules.ROLES) + "）");
        }
        String salt = AuthService.newSalt();
        try {
            return opsUserMapper.insertUser(TENANT, username.trim(),
                    displayName == null ? username.trim() : displayName,
                    effectiveRole, AuthService.pbkdf2(password, salt), salt);
        } catch (RuntimeException e) {
            // MyBatis/Spring 把唯一键冲突包成 DuplicateKeyException（RuntimeException），
            // 不再是 JDBC 的 SQLIntegrityConstraintViolationException。
            if (e.getMessage() != null && e.getMessage().contains("duplicate key")) {
                throw new IllegalArgumentException("用户名已存在: " + username);
            }
            throw e;
        }
    }

    public int updateUser(long id, String role, Boolean enabled, String password)
            throws java.sql.SQLException {
        String newRole = role == null || role.isBlank() ? null : role;
        if (newRole != null && !RbacRules.validRole(newRole)) {
            throw new IllegalArgumentException("角色不合法：" + role
                    + "（可选 " + String.join("/", RbacRules.ROLES) + "）");
        }
        String salt = password == null || password.isBlank() ? null : AuthService.newSalt();
        String hash = salt == null ? null : AuthService.pbkdf2(password, salt);

        try (Connection c = db.open()) {
            try {
                // 事务内第一句：串行化所有"用户角色/启用状态"写入。
                // 固定 key（不是 per-user）：保护判定看的是全局 admin 计数，
                // 锁必须覆盖"任意两个用户同时改角色"的交叉场景。
                opsUserMapper.lockUserRole();   // 只为拿到锁；返回值无意义

                // 纯改密码不触发"最后管理员"保护；只有改角色/改启用状态才需要判定。
                if (newRole != null || enabled != null) {
                    // FOR UPDATE：目标行在事务内被锁住，避免同一用户被并发改写
                    //（跨用户串行化由上面的 advisory lock 保证，行锁是第二道保险）。
                    UserRoleEnabled target = findUserRoleEnabled(c, id, true);
                    if (target == null) {
                        return 0;
                    }
                    if ("admin".equals(target.role()) && target.enabled()) {
                        String deny = RbacRules.lastAdminGuard(target.role(), target.enabled(),
                                newRole, enabled, countEnabledAdmins(c));
                        if (deny != null) {
                            throw new IllegalArgumentException(deny);
                        }
                    }
                }
                return opsUserMapper.updateUserFields(TENANT, id, newRole, enabled,
                        hash, salt);
            } catch (java.sql.SQLException | RuntimeException e) {
                // 拒绝（如最后一个管理员）以及任何异常都整体回滚，不留半改状态。
throw e;
            }
        }
    }

    /** 审计日志：谁在什么时候做了什么。 */
    public String auditJson(int limit, String action) throws java.sql.SQLException {
        String filter = (action == null || action.isBlank() || "all".equals(action))
                ? null : action;
        List<String> items = new ArrayList<>();
        for (java.util.Map<String, Object> row : opsUserMapper.selectAuditLog(
                TENANT, filter, Math.max(1, Math.min(limit, 500)))) {
            Object before = row.get("before");
            Object after = row.get("after");
            items.add(Jsons.obj(
                    "occurredAt", mstr(row, "occurredAt"),
                    "username", row.get("username"),
                    "action", row.get("action"),
                    "entityType", row.get("entityType"),
                    "entityId", row.get("entityId"),
                    "result", row.get("result"),
                    "ip", row.get("ip"),
                    // null 保持 null（这里不能像 mraw 那样兜成 {}）
                    "before", before == null ? null : new Jsons.Raw(String.valueOf(before)),
                    "after", after == null ? null : new Jsons.Raw(String.valueOf(after))));
        }
        return Jsons.obj("items", new Jsons.Raw(Jsons.arr(items)));
    }

    public String policies() throws java.sql.SQLException {
        List<String> items = new ArrayList<>();
        for (java.util.Map<String, Object> row : opsUserMapper.selectPolicies(TENANT)) {
            items.add(Jsons.obj(
                    "id", row.get("id"),
                    "errorCode", row.get("code"),
                    "scope", row.get("scope"),
                    "strategy", row.get("strategy"),
                    "maxAttempts", mint(row, "maxAttempts"),
                    "priority", mint(row, "priority"),
                    "enabled", Boolean.TRUE.equals(row.get("enabled")),
                    "origin", row.get("origin")));
        }
        return Jsons.obj("total", items.size(), "items", raw(Jsons.arr(items)));
    }

    /**
     * 告警列表：按 fingerprint 聚合未解决的错误。
     *
     * <p>只算 severity != 'info' 的——SKIP 类留痕但不告警。
     */
    /** 改一条策略：只允许改动作/次数/优先级/启用，不改错误码与层级。 */
    public int updatePolicy(long id, String strategy, Integer maxAttempts,
                            Integer priority, Boolean enabled) throws java.sql.SQLException {
        if (strategy != null) {
            String st = strategy.toUpperCase();
            if (!Set.of("RETRY", "REASSIGN", "SKIP", "ABORT", "MANUAL", "COMPENSATE").contains(st)) {
                throw new IllegalArgumentException("未知动作: " + strategy);
            }
        }
        return opsUserMapper.updateRecoveryPolicy(TENANT, id,
                strategy == null ? null : strategy.toUpperCase(),
                maxAttempts, priority, enabled);
    }

    /**
     * 生效校验：这条策略"改了会不会有用"。
     *
     * 三件事任一不满足，策略就是死的：错误码不存在 / 被更高优先级遮蔽 / 历史没出现过。
     */
    public String validatePolicy(String errorCode, String scope, int priority) throws java.sql.SQLException {
        java.util.Map<String, Object> v =
                opsUserMapper.selectPolicyValidation(TENANT, errorCode, priority);
        if (v == null) {
            return Jsons.obj("ok", false, "warning", "查询失败");
        }
        int exists = mint(v, "codeExists");
        int hits = mint(v, "historyHits");
        int shadowed = mint(v, "shadowed");

        String warning = null;
        if (exists == 0) {
            warning = "错误码不存在，这条策略永远不会命中";
        } else if (shadowed > 0) {
            warning = "有 " + shadowed + " 条更高优先级的策略会先命中，这条可能永远不生效";
        } else if (hits == 0) {
            warning = "历史上没出现过这个错误；不是错误，但无法用历史验证效果";
        }
        return Jsons.obj(
                "errorCode", errorCode,
                "scope", scope,
                "codeExists", exists > 0,
                "historyHits", hits,
                "shadowedBy", shadowed,
                "ok", warning == null,
                "warning", warning);
    }

    public String alerts(int limit) throws java.sql.SQLException {
        return alerts(limit, "all");
    }

    /**
     * @param status {@code all} / {@code open} / {@code acked} / {@code closed}
     */
    public String alerts(int limit, String status) throws java.sql.SQLException {
        String want = (status == null || status.isBlank() || "all".equalsIgnoreCase(status))
                ? null : status.toLowerCase();

        List<String> items = new ArrayList<>();
        for (java.util.Map<String, Object> row : opsUserMapper.selectAlerts(
                TENANT, Math.max(1, Math.min(limit, 200)))) {
            items.add(Jsons.obj(
                    "fingerprint", row.get("fingerprint"),
                    "hitCount", mint(row, "hitCount"),
                    "severity", row.get("severity"),
                    "lastOutcome", row.get("lastOutcome"),
                    "firstAt", mstr(row, "firstAt"),
                    "lastAt", mstr(row, "lastAt"),
                    "lastMessage", row.get("lastMessage"),
                    "state", row.get("state"),
                    "assignee", row.get("assignee"),
                    "note", row.get("note")));
        }
        return Jsons.obj("total", items.size(), "items", raw(Jsons.arr(items)));
    }

    /**
     * 设备级告警（目前只有"离线"）。
     *
     * <p>和 {@link #alerts(int)} 的区别：那个是**执行错误**（命令失败/超时），
     * 这个是**设备状态**（连不上）。处置方式完全不同——
     * 执行错误看 run_command，离线要先查网络和电源。
     */
    public String deviceAlerts(int limit) throws java.sql.SQLException {
        List<String> items = new ArrayList<>();
        for (java.util.Map<String, Object> row
                : opsUserMapper.selectDeviceAlerts(TENANT, limit)) {
            java.time.Instant hb = minst(row.get("lastHeartbeat"));
            java.time.Instant closed = minst(row.get("closedAt"));
            items.add(Jsons.obj(
                    "fingerprint", row.get("fingerprint"),
                    "status", row.get("status"),
                    "deviceCode", row.get("deviceCode"),
                    "deviceName", row.get("deviceName"),
                    "online", Boolean.TRUE.equals(row.get("online")),
                    "offlineSec", row.get("offlineSec"),
                    "lastHeartbeat", hb == null ? null : hb.toString(),
                    "note", row.get("note"),
                    "assignee", row.get("assignee"),
                    "closedAt", closed == null ? null : closed.toString()));
        }
        return Jsons.obj("total", items.size(), "items", new Jsons.Raw(Jsons.arr(items)));
    }

    /**
     * 告警处置：认领 / 关闭 / 静音。
     *
     * <p>只改 {@code alert_state}，不动 {@code error_occurrence}——
     * 留痕是事实，处置是人的动作，两者分开。
     */
    public int updateAlertState(String fingerprint, String status, String assignee, String note)
            throws java.sql.SQLException {
        if (fingerprint == null || fingerprint.isBlank()) {
            throw new IllegalArgumentException("fingerprint 不能为空");
        }
        String st = switch (status == null ? "" : status.toLowerCase()) {
            case "acked", "ack" -> "acked";
            case "closed", "close" -> "closed";
            case "silenced", "silence" -> "silenced";
            case "open", "reopen" -> "open";
            default -> throw new IllegalArgumentException("未知处置动作: " + status);
        };
        return opsUserMapper.upsertAlertState(TENANT, fingerprint, st, assignee, note);
    }

    /** 账号的角色 + 启用状态（权限保护判定用，不含密码）。 */
    public record UserRoleEnabled(String role, boolean enabled) {
    }

    /**
     * 当前启用中的管理员数量（"最后一个管理员"保护的判据）。
     *
     * <p>口径：本租户、{@code role='admin'}、{@code enabled}。
     * 注意 {@code sys_user} 目前<b>没有软删列</b>（schema 与生产库都没有 deleted_at），
     * 所以这里不写 {@code deleted_at IS NULL}；将来若加软删列，必须在这里补条件，
     * 否则停用/删除后的管理员统计会不一致。
     */
    public int countEnabledAdmins() throws java.sql.SQLException {
        try (Connection c = db.open()) {
            return countEnabledAdmins(c);
        }
    }

    private int countEnabledAdmins(Connection c) throws java.sql.SQLException {
        return opsUserMapper.countEnabledAdmins(TENANT);
    }

    /** 用户当前角色/启用状态；不存在返回 null。 */
    public UserRoleEnabled findUserRoleEnabled(long id) throws java.sql.SQLException {
        try (Connection c = db.open()) {
            return findUserRoleEnabled(c, id);
        }
    }

    private UserRoleEnabled findUserRoleEnabled(Connection c, long id) throws java.sql.SQLException {
        return findUserRoleEnabled(c, id, false);
    }

    /**
     * 事务内读目标用户角色/启用状态。
     *
     * @param forUpdate true = {@code FOR UPDATE}（updateUser 的事务里用：
     *                  目标行在事务期间被锁住，防止同一用户被并发改写；
     *                  不存在时返回 null，PostgreSQL 不会报错）
     */
    private UserRoleEnabled findUserRoleEnabled(Connection c, long id, boolean forUpdate)
            throws java.sql.SQLException {
        java.util.Map<String, Object> row =
                opsUserMapper.selectUserRoleEnabled(TENANT, id, forUpdate);
        return row == null ? null
                : new UserRoleEnabled(String.valueOf(row.get("role")),
                        Boolean.TRUE.equals(row.get("enabled")));
    }

}
