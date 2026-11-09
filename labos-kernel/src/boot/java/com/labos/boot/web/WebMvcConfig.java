package com.labos.boot.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 注册注解式 Controller 的鉴权拦截器（旧链路在拦截器内被跳过）。 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ApiAuthInterceptor authInterceptor;

    public WebMvcConfig(ApiAuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor).addPathPatterns("/**");
    }
}
