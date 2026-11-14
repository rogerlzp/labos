package com.labos.kernel.api;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.Jsons;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 最小鉴权：登录 + 内存 token + 审计。
 *
 * <p>为什么现在必须做：`POST /api/workflows/{id}/run` 会真实驱动设备，
 * 而之前全站无鉴权——内网任意网页一个表单提交就能开盖取板。
 *
 * <p>设计取舍：
 * <ul>
 *   <li><b>token 放内存</b>：进程重启要重新登录。对中控台可接受（本来就要值守交接），
 *       换来的是不用维护 token 表、不用考虑撤销。</li>
 *   <li><b>PBKDF2 + 每人独立 salt</b>：JDK 自带，不引第三方。</li>
 *   <li><b>审计复用 {@code audit_log}</b>：结构已经够用（before/after/ip/result），
 *       只是从来没写过数据。</li>
 * </ul>
 */
public final class AuthService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(AuthService.class);

    /** 登录会话。 */
    public record Session(String token, long userId, String username,
                          String displayName, String role, Instant expiresAt) {

        public boolean canWrite() {
            // 口径单一真源：与 RbacRules.canWrite 一致（admin/operator/designer 可写）。
            // 改动前这里只认 admin/operator，designer 进得去 /schedule-plans 却全 403。
            return com.labos.kernel.api.RbacRules.canWrite(role);
        }

        public boolean isAdmin() {
            return "admin".equals(role);
        }
    }

    private static final Duration TTL = Duration.ofHours(12);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DbConfig db;
    private final long tenantId;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public AuthService(DbConfig db, long tenantId) {
        this.db = db;
        this.tenantId = tenantId;
    }

    /**
     * 首次启动时建一个管理员。
     *
     * <p>只在表为空时创建，避免覆盖已改过的密码。
     * 用户名/密码可用环境变量覆盖：{@code LABOS_ADMIN_USER} / {@code LABOS_ADMIN_PASS}。
     */
    public void ensureSeedUser() throws SQLException {
        if (com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.QueryMapper.class).countUsers(tenantId) > 0) {
            return;
        }

        String user = envOr("LABOS_ADMIN_USER", "admin");

        // P0-2 修复（评审 20261102-03）：改动前默认口令硬编码 admin123，
        // 且把明文打印到 stdout —— 日志、终端历史、截图都会泄漏。
        //
        // 现在：生产环境（LABOS_ENV=prod）**必须**显式设 LABOS_ADMIN_PASS；
        // 开发环境保留便利默认值，但**不打印明文**。
        String env = envOr("LABOS_ENV", "dev");
        String pass = envOr("LABOS_ADMIN_PASS", null);
        boolean prod = "prod".equalsIgnoreCase(env);
        if (pass == null || pass.isBlank()) {
            if (prod) {
                throw new IllegalStateException(
                        "生产环境必须设置 LABOS_ADMIN_PASS（不接受默认口令）");
            }
            pass = "admin123";
            log.info("  [鉴权] 开发模式使用默认口令；"
                    + "生产请设 LABOS_ENV=prod 且必须提供 LABOS_ADMIN_PASS");
        } else if (pass.length() < 8) {
            log.info("  [鉴权] 警告：LABOS_ADMIN_PASS 长度不足 8，建议加强");
        }
        String salt = newSalt();

        com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.QueryMapper.class)
                .insertSeedUser(tenantId, user, "管理员", pbkdf2(pass, salt), salt);
        // **不打印明文**：只提示账号，口令由部署方自己知道
        log.info("  [鉴权] 已创建初始管理员账号: " + user
                + "（口令不回显；请立即登录并修改）");
    }

    /** 登录：校验密码，发 token。失败返回 null（不区分"用户不存在"和"密码错"，避免枚举）。 */
    public Session login(String username, String password, String ip) throws SQLException {
        if (username == null || password == null) {
            return null;
        }
        Map<String, Object> row = com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.QueryMapper.class)
                .selectUserForLogin(tenantId, username);
        if (row == null || !Boolean.TRUE.equals(row.get("enabled"))) {
            audit(null, username, "auth.login", "user", null,
                    null, null, ip, "fail");
            return null;
        }
        String expected = (String) row.get("passwordHash");
        String actual = pbkdf2(password, (String) row.get("salt"));
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8))) {
            audit(null, username, "auth.login", "user", null,
                    null, null, ip, "fail");
            return null;
        }

        Session s = new Session(UUID.randomUUID().toString(),
                ((Number) row.get("id")).longValue(), (String) row.get("username"),
                (String) row.get("displayName"), (String) row.get("role"),
                Instant.now().plus(TTL));
        sessions.put(s.token(), s);

        com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.QueryMapper.class)
                .touchLastLogin(s.userId());
        audit(s, s.username(), "auth.login", "user", s.userId(),
                null, null, ip, "ok");
        return s;
    }

    /** 校验 token（过期即失效并移除）。 */
    public Session verify(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        Session s = sessions.get(token);
        if (s == null) {
            return null;
        }
        if (Instant.now().isAfter(s.expiresAt())) {
            sessions.remove(token);
            return null;
        }
        return s;
    }

    public void logout(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    /**
     * 写审计。复用 {@code audit_log}。
     *
     * <p>失败不影响主流程——审计写不进去不该阻止业务，但要打日志。
     */
    public void audit(Session who, String username, String action, String entityType,
                      Long entityId, String before, String after, String ip, String result) {
        try {
            com.labos.kernel.persistence.Mappers.get(
                    com.labos.kernel.persistence.QueryMapper.class)
                    .insertAuditLog(tenantId,
                            who == null ? null : who.userId(),
                            username == null ? "anonymous" : username,
                            action, entityType, entityId, before, after,
                            ip == null || ip.isBlank() ? null : ip,
                            result);
        } catch (RuntimeException e) {
            log.error("[WARN] 审计写入失败: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 内部

    private static String envOr(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v;
    }

    public static String newSalt() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return Base64.getEncoder().encodeToString(b);
    }

    /** PBKDF2-HMAC-SHA256，10000 轮，256 位。 */
    public static String pbkdf2(String password, String salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(),
                    salt.getBytes(StandardCharsets.UTF_8), 10_000, 256);
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return Base64.getEncoder().encodeToString(f.generateSecret(spec).getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException("密码哈希失败", e);
        }
    }

    /** 当前在线会话数（诊断用）。 */
    public int sessionCount() {
        return sessions.size();
    }

    /** 给审计用的 JSON 片段。 */
    public static String detail(Object... kv) {
        return Jsons.obj(kv);
    }
}
