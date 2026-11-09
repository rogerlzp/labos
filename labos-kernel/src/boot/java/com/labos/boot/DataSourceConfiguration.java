package com.labos.boot;

import com.labos.kernel.persistence.DbConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

/**
 * Spring 接管数据源与事务。
 *
 * <p>A 批：手写 8 连接池已删除，HikariCP 由 Spring 管理生命周期，
 * 事务由 {@link DataSourceTransactionManager} 统一 commit / rollback。
 *
 * <p><b>桥接的关键</b>：{@code DbConfig.bind(ds)}（BeanConfiguration 调用）注入静态字段后，
 * 全仓 {@code db.open()} 统一走 {@code DataSourceUtils.getConnection}，
 * 在 {@code @Transactional} / {@code TransactionTemplate} 范围内拿到**同一个**
 * 线程绑定连接 —— 遗留裸 JDBC 代码无需改动就进入 Spring 事务。
 */
@Configuration
public class DataSourceConfiguration {

    @Bean(destroyMethod = "close")
    public DataSource dataSource() {
        HikariConfig c = new HikariConfig();
        c.setJdbcUrl(String.format("jdbc:postgresql://%s:%s/%s",
                env("LABOS_DB_HOST", "127.0.0.1"),
                env("LABOS_DB_PORT", "5432"),
                env("LABOS_DB_NAME", "labdb")));
        c.setUsername(env("LABOS_DB_USER", "labos"));
        c.setPassword(env("LABOS_DB_PASS", ""));
        c.setDriverClassName("org.postgresql.Driver");
        c.setMaximumPoolSize(16);
        c.setMinimumIdle(2);
        c.setPoolName("labos-spring-pool");
        // 遗留代码里仍有 try-with-resources 包着连接，事务内 close 由
        // DataSourceUtils.releaseConnection 兜住；这里只防连接泄漏。
        c.setLeakDetectionThreshold(60_000);
        return new HikariDataSource(c);
    }

    @Bean
    public PlatformTransactionManager transactionManager(DataSource ds) {
        return new DataSourceTransactionManager(ds);
    }

    /**
     * 把当前 {@code TransactionStatus} 暴露给桥接层：遗留裸 JDBC 代码里的
     * {@code rollback()} 需要据此标记 rollback-only（编程式事务下
     * {@code TransactionAspectSupport.currentTransactionStatus()} 不可用）。
     */
    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager tm) {
        return new TransactionTemplate(tm) {
            @Override
            public <T> T execute(
                    org.springframework.transaction.support.TransactionCallback<T> action) {
                return super.execute(status -> {
                    DbConfig.bindCurrentTransactionStatus(status);
                    try {
                        return action.doInTransaction(status);
                    } finally {
                        DbConfig.clearCurrentTransactionStatus();
                    }
                });
            }
        };
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v;
    }
}
