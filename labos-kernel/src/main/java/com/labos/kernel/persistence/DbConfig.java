package com.labos.kernel.persistence;

import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 数据源持有者：全仓取连接的唯一入口。
 *
 * <p><b>Spring 容器下</b>：{@link #bind(DataSource)} 由容器启动时注入 HikariDataSource，
 * 所有 {@link #open()} 走 {@link DataSourceUtils#getConnection} —— 在
 * {@code @Transactional} / {@code TransactionTemplate} 范围内返回**同一个**线程绑定连接，
 * commit / rollback 由 Spring 的 {@code DataSourceTransactionManager} 统一负责。
 *
 * <p><b>纯 JVM 自测</b>（不起容器）：用 Spring 自带的 {@code DriverManagerDataSource}
 * 直接建连，连不上立刻抛 —— 不引池、没有后台重连线程。这是自测入口，
 * 不是旧启动方式的兼容层。
 *
 * <p>2026-11-09 A 批：删掉了手写 8 连接池、反射探测、「无 Spring 时回落旧池」
 * 的兼容分支，以及 {@code ApiServer.main} 旧启动入口。事务内
 * {@code setAutoCommit(false)/commit()} 仍由连接代理拦下，因为 46 处遗留显式
 * 事务尚未全部收编（C 批用 MyBatis 重写后即可删除该代理）。
 */
public final class DbConfig {

    private final DataSource dataSource;
    private final String url;
    private final String user;
    private final String password;

    /** 容器注入的数据源，所有 DbConfig 实例共享。 */
    private static volatile DataSource shared;

    public DbConfig(String host, int port, String database, String user, String password) {
        this(String.format("jdbc:postgresql://%s:%d/%s", host, port, database),
                user, password);
    }

    public DbConfig(String jdbcUrl, String user, String password) {
        this.url = jdbcUrl;
        this.user = user;
        this.password = password;
        this.dataSource = null;
    }

    private DbConfig(DataSource ds, String jdbcUrl) {
        this.dataSource = ds;
        this.url = jdbcUrl;
        this.user = null;
        this.password = null;
    }

    /** 由 Spring 容器在启动时调用。 */
    public static void bind(DataSource ds) {
        shared = ds;
    }

    public static DataSource bound() {
        return shared;
    }

    private DataSource resolveDataSource() {
        if (dataSource != null) {
            return dataSource;
        }
        if (shared != null) {
            return shared;
        }
        // 纯 JVM 自测：每次直连，不共享、不缓存、不重试。
        return new DriverManagerDataSource(
                url,
                user != null ? user : env("LABOS_DB_USER", "labos"),
                password != null ? password : env("LABOS_DB_PASS", ""));
    }

    /**
     * 取连接。事务内返回 Spring 绑定的同一个连接；{@code close()} 只是归还，
     * 绝不真关（真关会让事务半途失去连接）。
     */
    public Connection open() throws SQLException {
        DataSource ds = resolveDataSource();
        try {
            Connection real = DataSourceUtils.getConnection(ds);
            return wrap(real, ds);
        } catch (org.springframework.jdbc.CannotGetJdbcConnectionException e) {
            // Spring 会把 SQLException 包成 unchecked 的 CannotGetJdbcConnectionException。
            // 全仓遗留代码（含 fail-closed 的安全闸门）都 catch SQLException，
            // 不解包就会漏过异常、把「读不到」误判成放行。
            if (e.getCause() instanceof SQLException se) {
                throw se;
            }
            throw new SQLException(e);
        }
    }

    private static Connection wrap(Connection real, DataSource ds) {
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                DbConfig.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("close".equals(name)) {
                        DataSourceUtils.releaseConnection(real, ds);
                        return null;
                    }
                    if (DataSourceUtils.isConnectionTransactional(real, ds)) {
                        // 遗留代码里的手写事务边界：交给 Spring，不许提前提交。
                        if ("setAutoCommit".equals(name) || "commit".equals(name)) {
                            return null;
                        }
                        if ("rollback".equals(name)) {
                            markRollbackOnly();
                            return null;
                        }
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /** 遗留代码 catch 住异常却调了 rollback()：标记只回滚，避免提交半截写入。 */
    private static void markRollbackOnly() {
        try {
            TransactionStatus st = TransactionAspectSupport.currentTransactionStatus();
            st.setRollbackOnly();
        } catch (RuntimeException ignore) {
            // 不在 AOP 事务里（例如 TransactionTemplate）：由 TransactionTemplate
            // 的包装器把 status 放进 CURRENT_TX，见 DataSourceConfiguration。
            if (CURRENT_TX.get() instanceof TransactionStatus tx) {
                tx.setRollbackOnly();
            }
        }
    }

    /** TransactionTemplate 场景下没有 AOP 的 currentTransactionStatus，显式暴露。 */
    private static final ThreadLocal<Object> CURRENT_TX = new ThreadLocal<>();

    public static void bindCurrentTransactionStatus(Object status) {
        CURRENT_TX.set(status);
    }

    public static void clearCurrentTransactionStatus() {
        CURRENT_TX.remove();
    }

    /** 从环境变量读取配置（默认值只放本地开发级别的信息）。 */
    public static DbConfig fromEnv() {
        String host = env("LABOS_DB_HOST", "127.0.0.1");
        int port = Integer.parseInt(env("LABOS_DB_PORT", "5432"));
        String db = env("LABOS_DB_NAME", "labdb");
        return new DbConfig(host, port, db,
                env("LABOS_DB_USER", "labos"), env("LABOS_DB_PASS", ""));
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v;
    }

    public String url() {
        return url;
    }

    @Override
    public String toString() {
        return url;
    }
}
