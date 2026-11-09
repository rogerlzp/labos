package com.labos.boot.web;

import com.labos.kernel.api.ApiServer;
import com.labos.kernel.api.AuthService;
import com.labos.kernel.persistence.Jsons;
import org.springframework.stereotype.Component;

/**
 * 注解式 Controller 的审计入口。
 *
 * <p>逻辑与 {@code ApiServer.audited(...)} 同源：未开鉴权时直接跳过，
 * session 为 null 记成 anonymous，entityKey 包成 {@code {"key": ...}}。
 * 抽出来是因为 Controller 拿不到 {@code HttpExchange}，而审计只需要
 * 「谁、什么动作、对哪个实体、细节、IP」这五个事实。
 */
@Component
public class AuditRecorder {

    private final AuthService auth;

    public AuditRecorder(AuthService auth) {
        this.auth = auth;
    }

    public void record(AuthService.Session who, String action, String entityType,
                       Long entityId, String entityKey, String detail, String ip) {
        if (!ApiServer.AUTH_ENABLED) {
            return;
        }
        auth.audit(who, who == null ? "anonymous" : who.username(), action,
                entityType, entityId,
                entityKey == null ? null : Jsons.obj("key", entityKey),
                detail, ip, "ok");
    }
}
