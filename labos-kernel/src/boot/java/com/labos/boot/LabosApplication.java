package com.labos.boot;

import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * LabOS 内核的 Spring Boot 启动入口。
 *
 * <p><b>为什么 web 类型是 NONE</b>：现有 HTTP 层是 JDK 内置
 * {@code com.sun.net.httpserver.HttpServer}（{@code ApiServer}），40+ 个端点、
 * 全部 e2e 与门禁都按它写。本阶段引入 Spring 是为了拿到**容器 + DataSource +
 * 声明式事务**，不是重写路由；因此不启 Tomcat，由 {@link KernelRunner} 在容器
 * 就绪后把原 ApiServer 拉起来，所有端点与启动收尾逻辑一行不改。
 *
 * <p>DataSource 由 {@link DataSourceConfiguration} 显式提供，所以排除自动配置，
 * 避免 Spring Boot 再建一个 Hikari 池造成两套连接来源。
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class
})
public class LabosApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(LabosApplication.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .run(args);
    }
}
