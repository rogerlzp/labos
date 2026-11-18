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
        // ---- 编排 / 运行 两类写权限（2026-11-17 接线）----
        //
        // <p>为什么必须分开：此前只有一刀切的 {@code canWrite}（admin/operator/designer），
        // 结果是 operator 能改工艺图、designer 能启动实验。这两件事的责任完全不同：
        // 改图改的是"以后每次都这么做"，启动改的是"这一批现在做"。
        // 角色规则早就写在 {@code RbacRules}，这里把它接到端点上。
        //
        // <p>只覆盖**意图明确**的端点，其余仍走 canWrite —— 一次改动把 60+ 端点
        // 全收紧，最容易误伤现场和自测脚本。
        // ---- 资产层 / 字典层：管理员专属（2026-11-18 接线）----
        //
        // <p>为什么：这些动作改的是**全实验室共享的资产**（岛、设备、驱动、设备池、
        // 字典字串表）⇒ 任何一个可写角色都能做，等于「值班纪律兜不住」。
        // <p>⚠ 用**精确路径 + 豁免**，不用前缀一刀切：
        //   ① /api/devices/*/points*（造点位）与 /api/islands/*/robot-points*（示教）
        //      属**编排资产** ⇒ designer 要能配 ⇒ 豁免；
        //   ② /api/policies/validate 是**只读校验**（前端在用）⇒ 豁免；
        //   ③ /api/material-roles（物料角色字典）已在 design 域 ⇒ 不动。
        // <p>读操作一律不受影响（下面只判写方法）。
        if (isAdminOnlyAssetWrite(req.getMethod(), path) && !s0.isAdmin()) {
            reject(resp, 403, "仅管理员可管理岛 / 设备 / 驱动 / 设备池与系统字典");
            return false;
        }
        if (requiresDesign(req.getMethod(), path) && !s0.canDesign()) {
            reject(resp, 403, "当前账号（" + s0.role() + "）不能修改工艺配置"
                    + "（需要 admin 或 designer）");
            return false;
        }
        if (requiresOperate(req.getMethod(), path) && !s0.canOperate()) {
            reject(resp, 403, "当前账号（" + s0.role() + "）不能执行/启动实验"
                    + "（需要 admin 或 operator）");
            return false;
        }
        return true;
    }

    /**
     * 编排类写操作：改的是**工艺资产**（图、绑定、点位方法、物料角色、示教、模板）。
     *
     * <p>先判 operate 再判 design 会互相打架的地方都在这里显式排除：
     * 起跑（run）属于 operate；模板实例化属于 operate。
     */
    static boolean requiresDesign(String method, String path) {
        if (method == null || "GET".equals(method) || "OPTIONS".equals(method)) {
            return false;
        }
        // 模板实例化 = 按模板开一批 → 运行侧，不是编排
        if (path.matches("^/api/experiment-templates/\\d+/instantiate$")) {
            return false;
        }
        // 起跑 = 运行侧
        if (path.matches("^/api/workflows/\\d+/run$")) {
            return false;
        }
        // 整岛导入 = 改现场配置（设备/点位/示教）⇒ **admin 域接管**（见 isAdminOnlyAssetWrite）
        //
        // 排程参数（schedule-plans）：前端只给 designer+admin ⇒ 后端对齐口径
        // （原来 operator 也能改 ⇒ 前端藏了、后端没藏 ⇒ 口径不一致）。
        if (path.startsWith("/api/schedule-plans")) {
            return true;
        }
        // 实验域·编排侧：建实验 / 改图 / 改流程清单 / 改工位 / 批次拆分 / 循环参数 / 全局排序
        if (isExperimentDesignWrite(path)) {
            return true;
        }
        return path.startsWith("/api/workflows")
                || path.startsWith("/api/experiment-templates")
                || path.startsWith("/api/positions")
                || path.startsWith("/api/material-roles")
                || (path.startsWith("/api/islands/") && path.contains("/robot-points"))
                || (path.startsWith("/api/devices/") && path.contains("/points"));
    }

    /**
     * 运行类写操作：改的是**这一批**（起停、配位置/核验、确认搬运、处理人工窗口）。
     */
    static boolean requiresOperate(String method, String path) {
        if (method == null || "GET".equals(method) || "OPTIONS".equals(method)) {
            return false;
        }
        // 实验域·运行侧：起跑 / 批次起跑 / 暂停停止恢复 / 实验变量 / 值班交接
        if (isExperimentOperateWrite(path)) {
            return true;
        }
        return path.matches("^/api/workflows/\\d+/run$")
                || path.matches("^/api/experiment-templates/\\d+/instantiate$")
                || path.startsWith("/api/runs")
                || path.startsWith("/api/experiment-runs")
                || path.startsWith("/api/transfers")
                || path.startsWith("/api/plates")
                || path.startsWith("/api/manual-windows")
                || path.startsWith("/api/paused");
    }

    /**
     * 管理员专属的写：资产层（岛/设备/驱动/设备池）+ 字典层（事件类型/业务状态/错误码/策略）。
     *
     * <p>⚠ 必须**精确路径 + 豁免**，不能用前缀一刀切 ——
     * 造点位（{@code /api/devices/{id}/points/...}）与示教
     * （{@code /api/islands/{id}/robot-points...}）属编排资产（designer 要能配），
     * {@code /api/policies/validate} 是只读校验（前端在用）。
     */
    static boolean isAdminOnlyAssetWrite(String method, String path) {
        if (method == null || "GET".equals(method) || "OPTIONS".equals(method)
                || "HEAD".equals(method)) {
            return false;
        }
        if (path.startsWith("/api/devices/") && path.contains("/points")) {
            return false;   // 造点位 = 编排资产
        }
        if (path.startsWith("/api/islands/") && path.contains("/robot-points")) {
            return false;   // 示教 = 编排资产
        }
        if (path.equals("/api/policies/validate")) {
            return false;   // 只读校验
        }
        return path.startsWith("/api/islands")
                || path.startsWith("/api/devices")
                || path.startsWith("/api/drivers")
                || path.startsWith("/api/pools")
                || path.startsWith("/api/device-event-types")
                || path.startsWith("/api/device-business-states")
                || path.startsWith("/api/error-codes")
                || path.startsWith("/api/policies");
    }

    /** 实验域·编排侧（改的是**工艺资产**：建实验、改图、改流程、改工位、批次拆分、循环参数）。 */
    static boolean isExperimentDesignWrite(String path) {
        return path.equals("/api/experiments")
                || path.equals("/api/experiments/reorder")
                || path.matches("^/api/experiments/\\d+/graph$")
                || path.matches("^/api/experiments/\\d+/processes(/.*)?$")
                || path.matches("^/api/experiments/\\d+/workcells$")
                || path.matches("^/api/experiments/\\d+/batch-split$");
    }

    /** 实验域·运行侧（改的是**这一批**：起跑、暂停/恢复、实验变量、值班交接）。 */
    static boolean isExperimentOperateWrite(String path) {
        return path.matches("^/api/experiments/\\d+/run$")
                || path.matches("^/api/experiments/\\d+/batches/\\d+/run$")
                || path.matches("^/api/experiments/runs/\\d+/(pause|stop|resume)$")
                || path.matches("^/api/experiments/\\d+/variables(/.*)?$")
                || path.matches("^/api/experiments/\\d+/handoffs(/.*)?$");
    }

    private static void reject(HttpServletResponse resp, int code, String msg) throws Exception {
        resp.setStatus(code);
        resp.setContentType("application/json; charset=utf-8");
        resp.getWriter().write(Jsons.obj("error", msg));
    }
}
