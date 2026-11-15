package com.labos.kernel.api;

import com.labos.boot.web.ApiAuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * 路由级权限收口的**纯逻辑**自测（2026-11-15）。
 *
 * <p><b>为什么测这一层</b>：这轮修了一个真越权 ——
 * 「审计日志」在左侧菜单里标了 {@code roles: ['admin']}，
 * 但**菜单隐藏 ≠ 权限收口**：直接敲 URL 任何登录用户都能读全量审计
 * （审计里有操作人账号、IP、改前改后的值 —— 那是运营与合规数据）。
 *
 * <p>这类 bug 的特点是**静默**：界面正常、接口 200、没人报错。
 * 所以必须用断言钉住"非 admin 调审计 → 403"这件事。
 *
 * <p>不连库、不起服务：用**动态代理**造假 request/response，
 * 直接跑拦截器那个 private 的 {@code check}。
 */
public final class RbacRouteGuardSelfTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        System.out.println("════ 路由级权限收口 · 纯逻辑自测 ════");
        System.out.println();

        section("1. 审计日志只给 admin");
        eq("operator 读审计 → 403", 403, guard("/api/audit", "GET", "operator"));
        eq("viewer 读审计 → 403", 403, guard("/api/audit", "GET", "viewer"));
        eq("designer 读审计 → 403", 403, guard("/api/audit", "GET", "designer"));
        eq("admin 读审计 → 放行", 200, guard("/api/audit", "GET", "admin"));

        section("2. 账号管理只给 admin（原本就有，别改坏）");
        eq("operator 读账号 → 403", 403, guard("/api/users", "GET", "operator"));
        eq("admin 读账号 → 放行", 200, guard("/api/users", "GET", "admin"));

        section("3. 写权限：operator/designer 可写，viewer 不可");
        eq("operator 写流程 → 放行", 200, guard("/api/workflows/1/run", "POST", "operator"));
        eq("designer 写流程 → 放行", 200, guard("/api/workflows/1/run", "POST", "designer"));
        eq("viewer 写流程 → 403", 403, guard("/api/workflows/1/run", "POST", "viewer"));

        section("4. 设备事件入站 POST 走独立令牌（不能被这次改动挡住）");
        eq("设备事件 POST（无会话）→ 放行", 200,
                guardNoSession("/api/device-events", "POST"));

        section("5. 审计规则不能误伤别的路径");
        eq("非审计路径（/api/alerts）不被审计规则拦", 200,
                guard("/api/alerts", "GET", "operator"));
        eq("非审计路径（/api/topology）不被审计规则拦", 200,
                guard("/api/topology", "GET", "viewer"));

        System.out.println();
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    /**
     * 跑一次拦截器判定，返回 HTTP 状态码（200 = 放行）。
     *
     * <p>用反射调 private 的 {@code check}：它是 private，但**行为**才是被测对象
     * （不能为了可测就把它改成 public —— 那样等于为测试放宽了封装）。
     */
    private static int guard(String path, String method, String role) {
        return invoke(path, method, role);
    }

    /** 无会话的调用（设备事件入站那种）。 */
    private static int guardNoSession(String path, String method) {
        return invoke(path, method, null);
    }

    private static int invoke(String path, String method, String role) {
        try {
            ApiAuthInterceptor interceptor = new ApiAuthInterceptor(fakeAuth(role));
            Object req = fakeRequest(path, method, role);
            int[] status = {200};
            Object resp = fakeResponse(status);
            Method check = ApiAuthInterceptor.class.getDeclaredMethod(
                    "check", HttpServletRequest.class, HttpServletResponse.class);
            check.setAccessible(true);
            boolean allowed = (boolean) check.invoke(interceptor, req, resp);
            return allowed ? 200 : status[0];
        } catch (Exception e) {
            throw new IllegalStateException("判定异常: " + e, e);
        }
    }

    /** 假 request：只要 path / method / Authorization 头 / setAttribute。 */
    private static Object fakeRequest(String path, String method, String role) {
        return Proxy.newProxyInstance(
                RbacRouteGuardSelfTest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (p, m, args) -> {
                    switch (m.getName()) {
                        case "getRequestURI": return path;
                        case "getMethod": return method;
                        case "getHeader":
                            return "Authorization".equalsIgnoreCase((String) args[0]) && role != null
                                    ? "Bearer tok" : null;
                        case "getQueryString": return null;
                        case "getRemoteAddr": return "127.0.0.1";
                        case "setAttribute": return null;
                        case "getAttribute": return null;
                        case "toString": return "FakeRequest[" + path + "]";
                        case "hashCode": return path.hashCode();
                        case "equals": return p == args[0];
                        default:
                            // 其余方法本自测用不到：返回类型的零值，不抛异常
                            if (m.getReturnType() == boolean.class) return false;
                            if (m.getReturnType() == int.class) return 0;
                            if (m.getReturnType() == long.class) return 0L;
                            return null;
                    }
                });
    }

    /** 假 response：把状态码写回 status[0]。 */
    private static Object fakeResponse(int[] status) {
        return Proxy.newProxyInstance(
                RbacRouteGuardSelfTest.class.getClassLoader(),
                new Class<?>[]{HttpServletResponse.class},
                (p, m, args) -> {
                    switch (m.getName()) {
                        case "setStatus": status[0] = (Integer) args[0]; return null;
                        case "getStatus": return status[0];
                        case "getWriter": {
                            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                            java.io.PrintWriter w = new java.io.PrintWriter(
                                    new java.io.OutputStreamWriter(buf, StandardCharsets.UTF_8), true);
                            return w;
                        }
                        case "toString": return "FakeResponse";
                        case "hashCode": return 1;
                        case "equals": return p == args[0];
                        default:
                            if (m.getReturnType() == boolean.class) return false;
                            if (m.getReturnType() == int.class) return 0;
                            if (m.getReturnType() == long.class) return 0L;
                            return null;
                    }
                });
    }

    /**
     * 造一个只会认我们这个 token 的 AuthService。
     *
     * <p>AuthService 是 final ⇒ 不能继承；它也没有"注入会话"的入口
     * ⇒ 只能用**反射替换**它内部的 sessions map。
     */
    private static AuthService fakeAuth(String role) {
        try {
            AuthService auth = new AuthService(null, 1L);
            java.lang.reflect.Field f = AuthService.class.getDeclaredField("sessions");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<String, AuthService.Session> sessions =
                    (java.util.Map<String, AuthService.Session>) f.get(auth);
            if (role != null) {
                sessions.put("tok", new AuthService.Session("tok", 1L, "u", "U", role,
                        Instant.now().plusSeconds(3600)));
            }
            return auth;
        } catch (Exception e) {
            throw new IllegalStateException("造 AuthSession 失败: " + e, e);
        }
    }

    private static void section(String s) {
        System.out.println();
        System.out.println("── " + s);
    }

    private static void eq(String what, Object expected, Object actual) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (same) {
            pass++;
            System.out.println("  ✔ " + what);
        } else {
            fail++;
            System.out.println("  ✘ " + what + "（期望 " + expected + "，实际 " + actual + "）");
        }
    }
}
