package com.labos.kernel.persistence;

import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

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
 * <p>2026-11-09：手写 8 连接池、反射探测、无容器回落旧池、
 * {@code ApiServer.main} 旧启动入口均已删除。**46 处显式事务已全部收编**
 * （Bean 方法走 {@code @Transactional}，静态工具类走 {@link Tx}），
 * 因此连接代理只剩 {@code close() → releaseConnection} 一件事，
 * 不再拦截 {@code setAutoCommit/commit/rollback}。
 */
public final class DbConfig {

    private final DataSource dataSource;
    private final String url;
    private final String user;
    private final String password;

    /** 容器注入的数据源，所有 DbConfig 实例共享。 */
    private static volatile DataSource shared;

    /** 无容器时（纯 JVM 自测）全进程共用的直连数据源单例。 */
    private static volatile DataSource standalone;

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

    /**
     * 无容器时的直连数据源。
     *
     * <p><b>必须是单例</b>：Spring 的 {@code DataSourceUtils} 以 DataSource 对象为 key
     * 绑定事务连接。若这里每次 new 一个、而 {@code Tx} 的事务管理器拿着另一个，
     * 两边 key 不同 ⇒ 连接不进事务，所有 {@code Tx.inTransaction} 静默退化成
     * autocommit（2026-11-09 批量迁移时实际踩到，StartupSettle/Batch/Pause
     * 三个自测集体判红）。
     */
    public static DataSource standaloneDataSource() {
        if (shared != null) {
            return shared;
        }
        synchronized (DbConfig.class) {
            if (standalone == null) {
                standalone = new DriverManagerDataSource(
                        fromEnv().url(),
                        env("LABOS_DB_USER", "labos"),
                        env("LABOS_DB_PASS", ""));
            }
            return standalone;
        }
    }

    private DataSource resolveDataSource() {
        if (dataSource != null) {
            return dataSource;
        }
        if (shared != null) {
            return shared;
        }
        // 显式给自定义 url 的实例（如故意连不上的 fail-closed 探针）各用各的，
        // 绝不能复用真实库单例，否则"读不到库"的用例会假绿。
        if (!url.equals(fromEnv().url())) {
            return new DriverManagerDataSource(url, user, password);
        }
        return standaloneDataSource();
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
                    try {
                        return method.invoke(real, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
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
