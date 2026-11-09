package com.labos.boot.web;

import com.labos.kernel.api.ApiServer;
import com.labos.kernel.api.AuthService;
import com.labos.kernel.persistence.Jsons;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 注解式 {@code @RestController} 的鉴权：与 {@code ApiServer.route()} 里的规则完全一致。
 *
 * <p><b>只拦注解式 Controller</b>（{@link HandlerMethod}），不拦
 * {@code LegacyRouteController} 那条旧链路 —— 旧链路在 route() 内部自己鉴权，
 * 两边规则相同但不会重复执行，迁移期不会把已验证的 40+ 端点改出 401。
 *
 * <p>这是把 {@code *Api} 逐个拆成 Spring MVC Controller 的前置设施：
 * 每拆一个，只要加 {@code @GetMapping/@PostMapping}，鉴权自动由这里接管。
 */
@Component
public class ApiAuthInterceptor implements HandlerInterceptor {

    private final AuthService auth;

    public ApiAuthInterceptor(AuthService auth) {
        this.auth = auth;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp,
                             Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod)) {
            return true;                       // 静态资源等
        }
        if (!handler.getClass().getName().isEmpty()
                && ((HandlerMethod) handler).getBeanType().getPackageName()
                        .equals("com.labos.boot.web.controller")) {
            return check(req, resp);
        }
        return true;                           // LegacyRouteController 自己鉴权
    }

    private boolean check(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String path = req.getRequestURI();
        if (!ApiServer.AUTH_ENABLED || ApiServer.isPublic(path)) {
            return true;
        }
        String h = req.getHeader("Authorization");
        String token = h == null ? null
                : (h.startsWith("Bearer ") ? h.substring(7).trim() : h.trim());
        AuthService.Session s0 = token == null ? null : auth.verify(token);
        if (s0 == null) {
            reject(resp, 401, "未登录或会话已过期");
            return false;
        }
        if (!"GET".equals(req.getMethod()) && !s0.canWrite()) {
            reject(resp, 403, "当前账号（" + s0.role() + "）无写权限");
            return false;
        }
        if (path.startsWith("/api/users") && !s0.isAdmin()) {
            reject(resp, 403, "仅管理员可管理账号");
            return false;
        }
        return true;
    }

    private static void reject(HttpServletResponse resp, int code, String msg) throws Exception {
        resp.setStatus(code);
        resp.setContentType("application/json; charset=utf-8");
        resp.getWriter().write(Jsons.obj("error", msg));
    }
}
