package com.labos.kernel.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * 数据库连接配置。
 *
 * <p>只用 {@code java.sql} 接口，编译期不需要驱动 jar；
 * 运行期通过 {@code -cp ...:lib/postgresql-*.jar} 提供。
 *
 * <p><b>Spring 事务桥接（2026-11-09）</b>：全仓 396 处 {@code db.open()} 是唯一取连接入口。
 * 当 Spring Boot 容器启动时，{@link #bindDataSource} 注入 DataSource，本方法改走
 * {@code DataSourceUtils.getConnection}，让所有遗留裸 JDBC 代码自动加入 Spring 声明式事务
 * （{@code @Transactional} / {@code TransactionTemplate}）的线程绑定连接，
 * commit/rollback 由 {@code DataSourceTransactionManager} 统一负责。
 * 容器不存在时（DB 自测、旧启动方式）自动回落原有连接池，行为零变化。
 * 这里用反射而不是直接 import，是为了让编译期与旧 classpath 都不依赖 spring jar。
 */
public final class DbConfig {

    private final String url;
    private final String user;
    private final String password;

    // ---- Spring 事务桥接（静态：所有 DbConfig 实例共享容器的数据源）----
    private static volatile javax.sql.DataSource springDataSource;
    private static volatile java.lang.reflect.Method mGetConnection;
    private static volatile java.lang.reflect.Method mReleaseConnection;
    private static volatile java.lang.reflect.Method mIsTransactional;
    private static volatile java.lang.reflect.Method mCurrentTxStatus;
    private static volatile java.lang.reflect.Method mSetRollbackOnly;
    private static volatile boolean springProbed;
    /** 编程式事务（TransactionTemplate）里没有 AOP 的 TransactionAspectSupport，
     *  由 Spring 侧把当前 TransactionStatus 显式暴露到这里。 */
    private static final ThreadLocal<Object> CURRENT_TX_STATUS = new ThreadLocal<>();

    public static void bindCurrentTransactionStatus(Object status) {
        CURRENT_TX_STATUS.set(status);
    }

    public static void clearCurrentTransactionStatus() {
        CURRENT_TX_STATUS.remove();
    }

    public DbConfig(String host, int port, String database, String user, String password) {
        this.url = String.format("jdbc:postgresql://%s:%d/%s", host, port, database);
        this.user = user;
        this.password = password;
    }

    public DbConfig(String jdbcUrl, String user, String password) {
        this.url = jdbcUrl;
        this.user = user;
        this.password = password;
    }

    // ========================================================================
    // 轻量连接池（2026-11-02 性能优化）
    //
    // 问题：open() 改动前是裸 DriverManager.getConnection，**每次调用都新建连接**。
    // 实测单次建连+查询 73.5ms；一次 2 节点的流程会开 ~86 次连接 →
    // 总耗时 6.3 秒，而命令本身 0.000 秒。**93% 的时间花在反复建连上。**
    //
    // 方案：进程内空闲连接队列 + 代理包装。
    // 对外 open() 签名不变（全仓 223 个调用点零改动）；
    // 返回的 Connection.close() 是"归还"而不是"真关"。
    //
    // 为什么不用 HikariCP：项目是裸 JDK + 单个 postgresql jar，没有依赖管理，
    // 引入 Hikari 还得带 slf4j。这个池只解决"别每次都建连"，够用。
    // ========================================================================
    private final java.util.Deque<Connection> idle =
            new java.util.concurrent.ConcurrentLinkedDeque<>();
    private final java.util.concurrent.atomic.AtomicInteger total =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 池上限。执行是设备级串行，8 足够覆盖并发实验 + 心跳 + API。 */
    private static final int MAX_POOL = 8;

    public Connection open() throws SQLException {
        if (springReady()) {
            try {
                Connection real = (Connection) mGetConnection.invoke(null, springDataSource);
                return wrapSpring(real);
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable t = e.getCause();
                throw t instanceof SQLException se ? se : new SQLException(t);
            } catch (ReflectiveOperationException e) {
                throw new SQLException("Spring 事务桥接取连接失败", e);
            }
        }
        Connection c;
        while ((c = idle.pollFirst()) != null) {
            try {
                if (c.isValid(1)) {
                    return wrap(c, true);
                }
            } catch (SQLException ignore) {
                // 失效，丢弃
            }
            closeQuietly(c);
            total.decrementAndGet();
        }
        if (total.get() < MAX_POOL) {
            Connection real = DriverManager.getConnection(url, user, password);
            total.incrementAndGet();
            return wrap(real, true);
        }
        // 超限降级：直连，不占池名额，close 时真关（避免 total 计数错乱）
        return wrap(DriverManager.getConnection(url, user, password), false);
    }

    /** 由 Spring Boot 容器在启动时调用；传 null 可解除绑定（回到原有连接池）。 */
    public static void bindDataSource(javax.sql.DataSource ds) {
        springDataSource = ds;
        springProbed = false;
    }

    public static javax.sql.DataSource boundDataSource() {
        return springDataSource;
    }

    /** 探测 Spring JDBC 是否在运行期 classpath 上（只做一次）。 */
    private static boolean springReady() {
        if (springDataSource == null) {
            return false;
        }
        if (!springProbed) {
            synchronized (DbConfig.class) {
                if (!springProbed) {
                    try {
                        Class<?> u = Class.forName(
                                "org.springframework.jdbc.datasource.DataSourceUtils");
                        mGetConnection = u.getMethod("getConnection",
                                javax.sql.DataSource.class);
                        mReleaseConnection = u.getMethod("releaseConnection",
                                Connection.class, javax.sql.DataSource.class);
                        mIsTransactional = u.getMethod("isConnectionTransactional",
                                Connection.class, javax.sql.DataSource.class);
                        try {
                            Class<?> tas = Class.forName(
                                    "org.springframework.transaction.interceptor.TransactionAspectSupport");
                            mCurrentTxStatus = tas.getMethod("currentTransactionStatus");
                            mSetRollbackOnly = Class.forName(
                                    "org.springframework.transaction.TransactionStatus")
                                    .getMethod("setRollbackOnly");
                        } catch (Exception ignore) {
                            // spring-tx 不在时，rollback() 只能 no-op
                        }
                    } catch (Exception e) {
                        mGetConnection = null;   // 没有 spring-jdbc：回落原池
                    }
                    springProbed = true;
                }
            }
        }
        return mGetConnection != null;
    }

    private boolean inSpringTx(Connection real) {
        try {
            return Boolean.TRUE.equals(
                    mIsTransactional.invoke(null, real, springDataSource));
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    /** 标记当前 Spring 事务只回滚：即使遗留代码吞掉异常，也不会提交半截写入。 */
    private void markRollbackOnly() {
        Object status = CURRENT_TX_STATUS.get();          // TransactionTemplate 路径
        if (status == null && mCurrentTxStatus != null) { // @Transactional 的 AOP 路径
            try {
                status = mCurrentTxStatus.invoke(null);
            } catch (Exception ignore) {
                status = null;
            }
        }
        if (status == null || mSetRollbackOnly == null) {
            return;
        }
        try {
            mSetRollbackOnly.invoke(status);
        } catch (Exception ignore) {
            // 不在事务里（无绑定的 status）时忽略
        }
    }

    /**
     * 包装 Spring 绑定连接：
     * <ul>
     *   <li>{@code close()} → {@code DataSourceUtils.releaseConnection}（事务内不真关）；</li>
     *   <li>事务内的 {@code setAutoCommit(false)} / {@code commit()} → no-op，
     *       由 Spring 统一提交，避免遗留代码提前提交半截事务；</li>
     *   <li>事务内的 {@code rollback()} → 标记 rollback-only，
     *       避免遗留代码 catch 住异常不抛却把写入提交了。</li>
     * </ul>
     */
    private Connection wrapSpring(Connection real) {
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                DbConfig.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("close".equals(name)) {
                        try {
                            mReleaseConnection.invoke(null, real, springDataSource);
                        } catch (ReflectiveOperationException e) {
                            closeQuietly(real);
                        }
                        return null;
                    }
                    if (inSpringTx(real)) {
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

    /**
     * 包装真实连接：{@code close()} 变"归还"。
     *
     * @param pooled true=归还到池；false=直接关（降级连接）
     */
    private Connection wrap(Connection real, boolean pooled) {
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                DbConfig.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName())) {
                        if (pooled) {
                            release(real);
                        } else {
                            closeQuietly(real);
                        }
                        return null;
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /** 归还连接：先回滚未提交事务、恢复 autoCommit，再放回池。 */
    private void release(Connection real) {
        try {
            if (real.isClosed() || !real.isValid(1)) {
                closeQuietly(real);
                total.decrementAndGet();
                return;
            }
            // 借出去的连接可能留着未提交事务或 autoCommit=false，
            // 直接复用会让下一个使用者看到脏状态。
            if (!real.getAutoCommit()) {
                try {
                    real.rollback();
                } catch (SQLException ignore) {
                    // 回滚失败说明连接已坏，下面会判失效
                }
                real.setAutoCommit(true);
            }
            if (idle.size() < MAX_POOL) {
                idle.offerFirst(real);
            } else {
                closeQuietly(real);
                total.decrementAndGet();
            }
        } catch (SQLException e) {
            closeQuietly(real);
            total.decrementAndGet();
        }
    }

    private static void closeQuietly(Connection c) {
        try {
            if (c != null) {
                c.close();
            }
        } catch (SQLException ignore) {
            // 关不上没别的办法
        }
    }

    /**
     * 从环境变量读取配置。
     *
     * <p><b>默认值只放"本地开发"级别的信息</b>：地址 127.0.0.1、库名 labdb、
     * 用户名 labos、密码空。
     * 内网地址、真实账号、真实密码一律不写进代码——仓库要能公开，
     * 而部署环境各不相同，写死的值既泄密又不通用。
     *
     * <p>现场部署必须显式设置 {@code LABOS_DB_HOST/PORT/NAME/USER/PASS}。
     */
    public static DbConfig fromEnv() {
        String host = env("LABOS_DB_HOST", "127.0.0.1");
        int port = Integer.parseInt(env("LABOS_DB_PORT", "5432"));
        String db = env("LABOS_DB_NAME", "labdb");
        String user = env("LABOS_DB_USER", "labos");
        String pass = env("LABOS_DB_PASS", "");
        return new DbConfig(host, port, db, user, pass);
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
