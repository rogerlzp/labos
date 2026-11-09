package com.labos.kernel.persistence;

import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 静态工具类使用 **Spring 事务** 的统一入口。
 *
 * <p>为什么需要：{@code CommandOutbox} / {@code PauseEventOutbox} / {@code CommandLedger}
 * 这类工具是 {@code static} 方法、调用方也不全是 Spring Bean，没法靠
 * {@code @Transactional} 的 AOP 代理覆盖。以前它们各自写
 * {@code setAutoCommit(false) → commit / catch → rollback}，事务边界散在 27 处。
 *
 * <p>这里不手写事务，而是用 Spring 的 {@link TransactionTemplate} +
 * {@link DataSourceTransactionManager}：在事务内 {@code db.open()} 会经
 * {@code DataSourceUtils} 拿到同一条线程绑定连接，提交/回滚/连接归还全由框架负责。
 * 业务侧只写 SQL，不再碰 {@code Connection} 的事务方法。
 *
 * <p>容器启动时由 {@code DataSourceConfiguration} 调 {@link #bind} 注入事务管理器；
 * 纯 JVM 自测没有容器时回落到 {@code DriverManagerDataSource} + 同一个
 * {@code DataSourceTransactionManager}（仍是 Spring 在管事务，只是连接不池化）。
 */
public final class Tx {

    private static volatile PlatformTransactionManager boundManager;
    private static volatile PlatformTransactionManager fallback;

    private Tx() {
    }

    /** 由 Spring 容器启动时调用。 */
    public static void bind(PlatformTransactionManager manager) {
        boundManager = manager;
    }

    private static PlatformTransactionManager manager() {
        PlatformTransactionManager m = boundManager;
        if (m != null) {
            return m;
        }
        synchronized (Tx.class) {
            if (fallback == null) {
                DataSource ds = DbConfig.bound();
                if (ds == null) {
                    ds = new DriverManagerDataSource(
                            DbConfig.fromEnv().url(),
                            env("LABOS_DB_USER", "labos"),
                            env("LABOS_DB_PASS", ""));
                }
                fallback = new DataSourceTransactionManager(ds);
            }
            return fallback;
        }
    }

    /** 一段需要原子执行的 SQL；拿到的连接已在 Spring 事务内。 */
    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection c) throws SQLException;
    }

    /**
     * 在 Spring 事务里执行 {@code work}。任何 {@link SQLException} 或
     * {@link RuntimeException} 都会让事务回滚，并把原异常原样抛给调用方。
     */
    public static <T> T inTransaction(DbConfig db, SqlWork<T> work) throws SQLException {
        try {
            return new TransactionTemplate(manager()).execute(status -> {
                // open() 必须在事务内调用：DataSourceUtils 会返回线程绑定连接，
                // close() 只是归还，不会中途把连接真关掉。
                try (Connection c = db.open()) {
                    return work.run(c);
                } catch (SQLException e) {
                    throw new UncheckedSql(e);
                }
            });
        } catch (UncheckedSql e) {
            throw e.cause;
        }
    }

    /** 把 checked 异常穿过 TransactionCallback（它不允许声明 checked）。 */
    private static final class UncheckedSql extends RuntimeException {
        private final SQLException cause;

        private UncheckedSql(SQLException cause) {
            super(cause);
            this.cause = cause;
        }
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v;
    }
}
