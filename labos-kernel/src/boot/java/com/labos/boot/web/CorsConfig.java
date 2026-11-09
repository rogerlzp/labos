package com.labos.boot.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring MVC 接管 18080 后的 CORS 预检配置。
 *
 * <p>不配的话 {@code DefaultCorsProcessor} 会在进入 Controller 之前把 OPTIONS
 * 预检直接拒成 {@code 403 Invalid CORS request}，绕过 {@code ApiServer.cors()}
 * 里的白名单。这里的白名单与 {@code ApiServer.trustedOrigin()} **同口径**：
 * 默认只信本机任意端口，{@code LABOS_CORS_ORIGINS} 环境变量可覆盖。
 *
 * <p>普通请求的 CORS 响应头仍由 {@code cors(ex)} 生成；适配层在回写响应头时
 * 对 {@code Access-Control-*} 用 {@code setHeader} 覆盖，避免出现重复的
 * {@code Access-Control-Allow-Origin}（浏览器会因此拒绝响应）。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String allow = System.getenv("LABOS_CORS_ORIGINS");
        List<String> origins = new ArrayList<>();
        if (allow != null && !allow.isBlank()) {
            for (String one : allow.split(",")) {
                if (!one.trim().isEmpty()) {
                    origins.add(one.trim());
                }
            }
        } else {
            origins.add("http://localhost:*");
            origins.add("http://127.0.0.1:*");
            origins.add("http://[::1]:*");
        }
        registry.addMapping("/**")
                .allowedOriginPatterns(origins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
