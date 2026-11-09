package com.labos.boot.web;

import com.labos.kernel.api.ApiServer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Spring MVC 的统一入口：**接管 18080 的全部路径**，把请求适配成
 * {@code HttpExchange} 后交给现有 {@code ApiServer.route()}。
 *
 * <p>为什么不立刻拆成几十个 {@code @RestController}：现有 9 个 {@code *Api}
 * 共 40+ 端点，鉴权、CORS、静态资源、错误码全在 {@code route()} 里统一处理；
 * 一次性重写需要一整套 e2e 才能确认无回归。先让传输层迁到 Spring MVC，
 * 路由函数保持不变，后续按模块逐个拆成注解式 Controller。
 *
 * <p>覆盖所有 HTTP 方法（含 OPTIONS/CORS 预检），与 JDK HttpServer 的
 * {@code createContext("/", ...)} 行为一致。
 */
@RestController
public class LegacyRouteController {

    private final ApiServer api;

    public LegacyRouteController(ApiServer api) {
        this.api = api;
    }

    @RequestMapping(value = "/**", method = {
            RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
            RequestMethod.DELETE, RequestMethod.PATCH, RequestMethod.HEAD,
            RequestMethod.OPTIONS
    })
    public void handle(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        api.route(new ServletHttpExchange(req, resp));
    }

    /** 根路径（部分容器不会把 "/" 匹配进 /**）。 */
    @RequestMapping(value = "/", method = {
            RequestMethod.GET, RequestMethod.POST, RequestMethod.HEAD,
            RequestMethod.OPTIONS
    })
    public void root(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        handle(req, resp);
    }
}
