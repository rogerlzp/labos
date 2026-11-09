package com.labos.kernel.persistence;

import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;

/**
 * Mapper 的静态入口，供**非 Spring Bean** 的类使用。
 *
 * <p>背景：大批遗留类是 {@code new} 出来的工具/存储类
 * （{@code PauseManager}、{@code DriverStepExecutor}、{@code JdbcManualWindowStore}、
 * {@code PreflightChecker} 等），没有构造注入的机会。它们要么整体 Bean 化
 * （改动面大、动到已验证的执行链），要么拿到 Mapper 才能把 SQL 收进 XML。
 *
 * <p>这里选择后者：容器启动时 {@code bind(sqlSessionFactory)}，之后
 * {@code Mappers.get(XxxMapper.class)} 返回 MyBatis 的 mapper 代理。
 * 在 Spring 事务内（{@code @Transactional} 或 {@code Tx.inTransaction}）调用会
 * **加入当前事务**，与 {@code db.open()} 拿到的连接是同一条；不在事务内时
 * 每次调用自动开/关 session，语义与原来的「短连接 + autocommit」一致。
 *
 * <p>未 bind 时 {@link #get} 直接抛异常——不做「静默回落裸 JDBC」，
 * 那种兜底会让漏绑的环境悄悄跑旧路径，查不出来。
 */
public final class Mappers {

    private static volatile SqlSessionTemplate template;

    private Mappers() {
    }

    public static void bind(SqlSessionFactory factory) {
        template = new SqlSessionTemplate(factory);
    }

    public static <T> T get(Class<T> type) {
        SqlSessionTemplate t = template;
        if (t == null) {
            synchronized (Mappers.class) {
                if (template == null) {
                    template = new SqlSessionTemplate(standaloneFactory());
                }
                t = template;
            }
        }
        return t.getMapper(type);
    }

    /**
     * 无容器环境（纯 JVM 自测 / 独立工具入口）的自动初始化。
     *
     * <p>必须用 {@link DbConfig#standaloneDataSource()} **同一个** DataSource 实例：
     * MyBatis 的 Spring 事务同步按 DataSource 对象做 key，换一个实例就会
     * 认不出事务绑定连接，Mapper 会另开 autocommit 连接（2026-11-09 踩过，
     * StartupSettle/Batch/Pause 三个自测集体判红）。
     */
    private static SqlSessionFactory standaloneFactory() {
        try {
            org.mybatis.spring.SqlSessionFactoryBean f =
                    new org.mybatis.spring.SqlSessionFactoryBean();
            f.setDataSource(DbConfig.standaloneDataSource());
            f.setMapperLocations(new org.springframework.core.io.support
                    .PathMatchingResourcePatternResolver().getResources("classpath:mapper/*.xml"));
            return f.getObject();
        } catch (Exception e) {
            throw new IllegalStateException("Mappers 自动初始化失败："
                    + e.getMessage(), e);
        }
    }
}
