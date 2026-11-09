package com.labos.kernel.support;

import com.labos.kernel.api.QueryService;
import com.labos.kernel.persistence.DbConfig;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

/**
 * DB 自测拿 Spring 代理的统一入口。
 *
 * <p>为什么需要：这些自测原来直接 {@code com.labos.kernel.support.TestSpringSupport.queryService()}，拿到的是裸对象，
 * 类上的 {@code @Transactional} 不经过 AOP 代理 → 完全不生效。所以过去每个写方法
 * 都得自己 {@code setAutoCommit(false)/commit()/rollback()} 兜住原子性。
 *
 * <p>现在自测也走容器：{@link #queryService()} 返回 CGLIB 代理，事务由
 * {@code DataSourceTransactionManager} 管，业务代码里的显式事务语句就可以安全删除。
 * 同时 {@code DbConfig.bind(ds)} 让自测里其余 {@code db.open()} 的裸 SQL 检查
 * 与 Spring 事务共用同一条连接（事务内可见未提交的写入）。
 *
 * <p>容器按 JVM 单例懒建：test-all.sh 每个自测类各起一个 JVM，各起一次，约 1 秒。
 */
public final class TestSpringSupport {

    private static volatile AnnotationConfigApplicationContext ctx;

    private TestSpringSupport() {
    }

    private static synchronized AnnotationConfigApplicationContext context() {
        if (ctx == null) {
            // Spring 启动日志会夹在自测输出里，压到 WARN 免得刷屏
            try {
                ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger("ROOT");
                root.setLevel(ch.qos.logback.classic.Level.WARN);
            } catch (RuntimeException ignore) {
                // 没 logback 时忽略
            }
            ctx = new AnnotationConfigApplicationContext(Config.class);
            // JVM 退出时关池，否则 6 个自测 JVM 的连接会叠在一起。
            Runtime.getRuntime().addShutdownHook(new Thread(ctx::close));
        }
        return ctx;
    }

    public static QueryService queryService() {
        return context().getBean(QueryService.class);
    }

    public static DbConfig db() {
        return context().getBean(DbConfig.class);
    }

    @Configuration
    @MapperScan("com.labos.kernel.persistence")
    @EnableTransactionManagement
    static class Config {

        /**
         * 自测不需要连接池：test-all.sh 每个类一个 JVM，6 个类各起一个池会在
         * 全量跑时瞬时抬高 PG 连接数，干扰 BatchDbSelfTest 这类并发断言。
         * DriverManagerDataSource 每次直连、无后台线程、无连接残留；
         * 事务内由 DataSourceUtils 绑定同一连接，语义与正式路径一致。
         */
        @Bean
        public DataSource dataSource() {
            return new DriverManagerDataSource(
                    String.format("jdbc:postgresql://%s:%s/%s",
                            env("LABOS_DB_HOST", "127.0.0.1"),
                            env("LABOS_DB_PORT", "5432"),
                            env("LABOS_DB_NAME", "labdb")),
                    env("LABOS_DB_USER", "labos"),
                    env("LABOS_DB_PASS", ""));
        }

        /**
         * Mapper 的 XML 在 out/mapper（classpath:mapper/*.xml）。
         * 必须用与事务管理器**同一个** DataSource bean，否则 DataSourceUtils
         * 认不出事务绑定连接（正式容器与 Tx 的教训同源）。
         */
        @Bean
        public org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory(
                DataSource ds) throws Exception {
            SqlSessionFactoryBean f = new SqlSessionFactoryBean();
            f.setDataSource(ds);
            f.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath:mapper/*.xml"));
            org.apache.ibatis.session.SqlSessionFactory sf = f.getObject();
            com.labos.kernel.persistence.Mappers.bind(sf);
            return sf;
        }

        @Bean
        public PlatformTransactionManager transactionManager(DataSource ds) {
            PlatformTransactionManager tm = new DataSourceTransactionManager(ds);
            com.labos.kernel.persistence.Tx.bind(tm);
            return tm;
        }

        @Bean
        public DbConfig dbConfig(DataSource ds) {
            DbConfig.bind(ds);
            return DbConfig.fromEnv();
        }

        @Bean
        public QueryService queryService(DbConfig db,
                                         com.labos.kernel.persistence.QueryMapper queryMapper,
                                         com.labos.kernel.persistence.ExperimentMapper experimentMapper) {
            QueryService qs = new QueryService(db, 1L);
            qs.setQueryMapper(queryMapper);
            qs.setExperimentMapper(experimentMapper);
            return qs;
        }
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v;
    }
}
