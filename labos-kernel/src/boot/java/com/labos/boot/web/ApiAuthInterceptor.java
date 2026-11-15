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
 * 注解式 {@code @RestController} 的鉴权：与迁移前 {@code ApiServer.route()} 里的规则完全一致。
 *
 * <p><b>只拦注解式 Controller</b>（{@link HandlerMethod}），不拦静态资源等
 * 非 Controller 的处理器。鉴权规则与原 route() 内部一致 —— 迁移期不改动已验证
 * 端点的行为，避免把 40+ 端点改出 401。
 *
 * <p>鉴权通过的 Session 放进 request attribute（{@link #SESSION_ATTR}），
 * Controller 用 {@code @RequestAttribute} 取。
 */
@Component
public class ApiAuthInterceptor implements HandlerInterceptor {

    /** 鉴权通过后放进 request 的 Session，Controller 用 @RequestAttribute 取。 */
    public static final String SESSION_ATTR = "labos.session";

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
        return true;                           // controller 包以外的 HandlerMethod（当前没有）
    }

    private boolean check(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String path = req.getRequestURI();
        // 只有设备事件 POST 使用独立令牌；GET 查询仍走普通登录会话。
        if ("/api/device-events".equals(path) && "POST".equals(req.getMethod())) {
            return true;
        }
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
        req.setAttribute(SESSION_ATTR, s0);
        if (path.startsWith("/api/users") && !s0.isAdmin()) {
            reject(resp, 403, "仅管理员可管理账号");
            return false;
        }
        // 审计日志收口 admin（2026-11-15 修的越权）。
        //
        // <p>为什么：审计里记着"谁在什么时候改了什么"—— 包含操作人账号、IP、
        // 改前改后的值。前端菜单虽然把 /audit 标成 admin-only，但**菜单隐藏 ≠ 权限收口**：
        // 直接敲 URL 任何登录用户都能读全量审计（这是运营与合规数据，不是普通业务数据）。
        //
        // <p>为什么用 equals 而不是 startsWith：将来若加 /api/audit/export 之类的新端点，
        // startsWith 会把它一并收进来；这里只精确收 /api/audit 这一个路径。
        if ("/api/audit".equals(path) && !s0.isAdmin()) {
            reject(resp, 403, "仅管理员可查看审计日志");
            return false;
        }
        // 全局运行治理参数（调度/自动清理）收口 admin：designer/operator 只配业务排程，
        // 不该改影响所有实验的全局开关（评审权限模型建议）。
        boolean adminOnlyGovernance = (path.startsWith("/api/settings/stale-pause")
                && "PUT".equals(req.getMethod()))
                || (path.startsWith("/api/scheduler/stale-check")
                && "POST".equals(req.getMethod()));
        if (adminOnlyGovernance && !s0.isAdmin()) {
            reject(resp, 403, "仅管理员可修改全局调度/自动清理参数");
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
