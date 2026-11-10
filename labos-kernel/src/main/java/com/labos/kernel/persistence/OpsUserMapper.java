package com.labos.kernel.persistence;

import java.util.List;
import java.util.Map;

/**
 * 用户/策略/告警域的数据访问（{@code OpsUserService} 专用，语句见
 * {@code mapper/OpsUserMapper.xml}）。
 *
 * <p>原先这些 SQL 散在 {@code QueryService} 里、映射挂在 {@code QueryMapper}
 * 上；按「一个 Controller 域 → 一个 Service → 一个 Mapper」从
 * {@code OpsUserController} 一侧归位到这里。
 */
public interface OpsUserMapper {


    /** 用户清单。 */
    List<Map<String, Object>> selectUsers(long tenantId);

    /** 新建用户（密码 hash 与 salt 由调用方算好）。 */
    int insertUser(long tenantId, String username, String displayName, String role,
                   String passwordHash, String salt);

    /** 改用户（COALESCE：没传的字段不动）。 */
    int updateUserFields(long tenantId, long id, String role, Boolean enabled,
                         String passwordHash, String salt);

    /**
     * 串行化「用户角色/启用状态」写入的事务级咨询锁。
     *
     * <p>固定 key（不是 per-user）：保护判定看的是全局 admin 计数，
     * 锁必须覆盖「任意两个用户同时改角色」的交叉场景。
     */
    void lockUserRole();

    /** 审计日志（filter 为 null 时取全部）。 */
    List<Map<String, Object>> selectAuditLog(long tenantId, String filter, int limit);

    /** 恢复策略清单（带错误码，`*` 表示通配）。 */
    List<Map<String, Object>> selectPolicies(long tenantId);

    /** 更新恢复策略（COALESCE：没传的字段不动）。 */
    int updateRecoveryPolicy(long tenantId, long id, String strategy,
                             Integer maxAttempts, Integer priority, Boolean enabled);

    /** 策略校验：错误码是否存在 / 历史命中 / 被更高优先级覆盖。 */
    Map<String, Object> selectPolicyValidation(long tenantId, String errorCode, int priority);

    /** 告警聚合（按 fingerprint，排除 info 与已解决）。 */
    List<Map<String, Object>> selectAlerts(long tenantId, int limit);

    /** 设备离线告警（含离线秒数）。 */
    List<Map<String, Object>> selectDeviceAlerts(long tenantId, int limit);

    /** 告警处置（按 tenant+fingerprint 覆盖）。 */
    int upsertAlertState(long tenantId, String fingerprint, String status,
                         String assignee, String note);

    int countEnabledAdmins(long tenantId);

    /** role / enabled 两列；{@code forUpdate=true} 时加行锁。 */
    Map<String, Object> selectUserRoleEnabled(long tenantId, long userId, boolean forUpdate);

}
