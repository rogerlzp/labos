package com.labos.kernel.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * 数据库连接配置。
 *
 * <p>只用 {@code java.sql} 接口，编译期不需要驱动 jar；
 * 运行期通过 {@code -cp ...:lib/postgresql-*.jar} 提供。
 */
public final class DbConfig {

    private final String url;
    private final String user;
    private final String password;

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
