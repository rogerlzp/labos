package com.labos.boot;

import com.labos.kernel.persistence.DbConfig;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Spring 事务桥接自测：证明「框架真的在管事务」，而不是只加了个依赖。
 *
 * <p>跑法：
 * <pre>
 * cd labos-kernel
 * java -Dlabos.kernel.http.enabled=false \
 *      -cp "out:lib/postgresql-42.7.3.jar:lib/spring/*" \
 *      com.labos.boot.SpringTxSelfTest
 * </pre>
 */
public final class SpringTxSelfTest {

    private static int pass;
    private static int fail;
    private static final String PREFIX = "SPRING-TX-" + System.currentTimeMillis() + "-";

    public static void main(String[] args) throws Exception {
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(LabosApplication.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .properties("labos.kernel.http.enabled=false")
                .run();
        try {
            TransactionTemplate tx = ctx.getBean(TransactionTemplate.class);
            DbConfig db = ctx.getBean(DbConfig.class);

            check("DataSource 已由 Spring 容器绑定", DbConfig.boundDataSource() != null,
                    DbConfig.boundDataSource());

            // ① 桥接硬证据：事务内多次 db.open() 必须是同一个物理连接
            tx.execute(status -> {
                try {
                    int p1 = backendPid(db);
                    int p2 = backendPid(db);
                    check("事务内两次 db.open() 是同一物理连接", p1 > 0 && p1 == p2,
                            p1 + " / " + p2);
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
                return null;
            });

            // ② 抛异常 → 整个事务回滚（两条 INSERT 都消失）
            try {
                tx.execute(status -> {
                    insert(db, PREFIX + "rollback-a");
                    insert(db, PREFIX + "rollback-b");
                    throw new IllegalStateException("故意失败");
                });
                check("事务抛异常应向外传播", false, "没抛");
            } catch (IllegalStateException expected) {
                check("事务抛异常应向外传播", true, expected.getMessage());
            }
            check("回滚后一条都不剩", countRows(db, PREFIX + "rollback-%") == 0,
                    countRows(db, PREFIX + "rollback-%"));

            // ③ 正常返回 → 一起提交
            tx.execute(status -> {
                insert(db, PREFIX + "commit-a");
                insert(db, PREFIX + "commit-b");
                return null;
            });
            check("提交后两条都在", countRows(db, PREFIX + "commit-%") == 2,
                    countRows(db, PREFIX + "commit-%"));

            // ④ 遗留显式事务代码：setAutoCommit(false)/commit() 不许提前提交
            try {
                tx.execute(status -> {
                    insert(db, PREFIX + "legacy-a");
                    try (Connection c = db.open()) {
                        c.setAutoCommit(false);
                        try (PreparedStatement ps = c.prepareStatement(
                                "SELECT 1")) {
                            try (ResultSet rs = ps.executeQuery()) {
                                rs.next();
                            }
                        }
                        c.commit();       // 桥接应吞掉，交给 Spring 统一提交
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                    insert(db, PREFIX + "legacy-b");
                    status.setRollbackOnly();   // 模拟外层判定失败
                    return null;
                });
            } catch (RuntimeException ignore) {
                // 某些传播配置下回滚会抛，忽略
            }
            check("遗留 commit() 未提前提交（rollback-only 仍生效）",
                    countRows(db, PREFIX + "legacy-%") == 0,
                    countRows(db, PREFIX + "legacy-%"));

            // ⑤ 遗留代码 rollback() 后吞掉异常，也必须整体回滚
            try {
                tx.execute(status -> {
                    insert(db, PREFIX + "swallow-a");
                    try (Connection c = db.open()) {
                        c.rollback();     // 桥接应标记 rollback-only
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                    return null;        // 不抛异常
                });
            } catch (RuntimeException ignore) {
                // UnexpectedRollbackException 属预期
            }
            check("遗留 rollback() 吞异常后仍不提交",
                    countRows(db, PREFIX + "swallow-%") == 0,
                    countRows(db, PREFIX + "swallow-%"));

            cleanup(db);
        } finally {
            // ⑥ 解绑后回落到原有手写连接池：证明旧启动方式零回归
            DbConfig.bindDataSource(null);
            try (Connection c = DbConfig.fromEnv().open()) {
                check("解绑 Spring 后原连接池仍可用", c != null && !c.isClosed(), "ok");
            }
            ctx.close();
        }
        System.out.printf("%n通过 %d, 失败 %d%n", pass, fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void insert(DbConfig db, String code) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO workflow (tenant_id, code, name, category, version, status)
                     VALUES (1, ?, '事务自测', 'choreography', 1, 1)
                     """)) {
            ps.setString(1, code);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static int backendPid(DbConfig db) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT pg_backend_pid()");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    private static int countRows(DbConfig db, String like) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM workflow WHERE tenant_id=1 AND code LIKE ?")) {
            ps.setString(1, like);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        } catch (SQLException e) {
            return -1;
        }
    }

    private static void cleanup(DbConfig db) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM workflow WHERE tenant_id=1 AND code LIKE ?")) {
            ps.setString(1, PREFIX + "%");
            ps.executeUpdate();
        } catch (SQLException ignore) {
            // 尽力而为
        }
    }

    private static void check(String name, boolean ok, Object actual) {
        if (ok) {
            pass++;
            System.out.println("  ✓ " + name + " " + actual);
        } else {
            fail++;
            System.out.println("  ✗ " + name + " " + actual);
        }
    }

    private SpringTxSelfTest() {
    }
}
