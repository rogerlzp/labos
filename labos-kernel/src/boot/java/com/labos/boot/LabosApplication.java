package com.labos.boot;

import org.springframework.boot.Banner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * LabOS 内核的 Spring Boot 启动入口。
 *
 * <p><b>HTTP 层已迁 Spring MVC</b>：Tomcat 监听 18080，
 * {@code LegacyRouteController} 把 Servlet 请求适配成 {@code HttpExchange}
 * 后交给现有 {@code ApiServer.route()} —— 40+ 端点的路由逻辑一行不改，
 * 但传输层、线程池、生命周期全部由 Spring 管理，JDK HttpServer 已删除。
 *
 * <p>DataSource 由 {@link DataSourceConfiguration} 显式提供，所以排除自动配置，
 * 避免 Spring Boot 再建一个 Hikari 池造成两套连接来源。
 */
@EnableTransactionManagement
@MapperScan("com.labos.kernel.persistence")
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class
})
public class LabosApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(LabosApplication.class)
                .bannerMode(Banner.Mode.OFF)
                .run(args);
    }
}
