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
 * <p><b>HTTP 层已全部迁到注解式 Controller</b>：Tomcat 监听 18080，
 * 12 个 {@code @RestController}（{@code com.labos.boot.web.controller}）直接
 * 承接路由，业务编排放进 Service（{@code RunService} / {@code WorkflowWebService}），
 * SQL 收进 MyBatis XML。{@code ApiServer.route()} 与 {@code ServletHttpExchange}
 * 适配层已退役，JDK {@code com.sun.net.httpserver} 的服务职责已清零。
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
