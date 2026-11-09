package com.labos.boot;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.api.QueryService;
import com.labos.kernel.workflow.WorkflowService;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * A 批验收：Spring 容器 + AOP 声明式事务真的生效（不是只加了个依赖）。
 *
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
    private static final String PREFIX = "SPRING-A-" + System.currentTimeMillis() + "-";

    public static void main(String[] args) throws Exception {
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(LabosApplication.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .properties("labos.kernel.http.enabled=false")
                .run();
        try {
            DbConfig db = ctx.getBean(DbConfig.class);
            TxProbeService probe = ctx.getBean(TxProbeService.class);

            check("DataSource 由 Spring 容器绑定", DbConfig.bound() != null, DbConfig.bound());

            // ① @Transactional 生效的前提：调用方拿到的必须是 AOP 代理，不是裸实例
            check("WorkflowService 是 AOP 代理",
                    AopUtils.isAopProxy(ctx.getBean(WorkflowService.class)),
                    ctx.getBean(WorkflowService.class).getClass().getSimpleName());
            check("QueryService 是 AOP 代理",
                    AopUtils.isAopProxy(ctx.getBean(QueryService.class)),
                    ctx.getBean(QueryService.class).getClass().getSimpleName());

            // ② 事务内连接复用（DataSourceUtils 桥接）
            int[] pids = probe.twoConnectionsPid();
            check("事务内两次 db.open() 是同一物理连接", pids[0] > 0 && pids[0] == pids[1],
                    pids[0] + " / " + pids[1]);

            // ③ RuntimeException 回滚
            try {
                probe.insertThenThrow(PREFIX + "rt-a", PREFIX + "rt-b", false);
                check("RuntimeException 应向外传播", false, "没抛");
            } catch (IllegalStateException expected) {
                check("RuntimeException 应向外传播", true, expected.getMessage());
            }
            check("RuntimeException → 两条全回滚", count(db, PREFIX + "rt-%") == 0,
                    count(db, PREFIX + "rt-%"));

            // ④ checked 异常也必须回滚（rollbackFor = Exception.class 的意义）
            try {
                probe.insertThenThrow(PREFIX + "ck-a", PREFIX + "ck-b", true);
                check("checked 异常应向外传播", false, "没抛");
            } catch (java.sql.SQLException expected) {
                check("checked 异常应向外传播", true, expected.getMessage());
            }
            check("SQLException → 两条全回滚（默认配置下不会回滚）",
                    count(db, PREFIX + "ck-%") == 0, count(db, PREFIX + "ck-%"));

            // ⑤ 正常返回提交
            probe.insertBoth(PREFIX + "ok-a", PREFIX + "ok-b");
            check("正常返回 → 两条都提交", count(db, PREFIX + "ok-%") == 2,
                    count(db, PREFIX + "ok-%"));

            // ⑥ C 批：MyBatis XML Mapper 与裸 JDBC 共用同一 Spring 事务连接
            int[] mix = probe.mapperAndJdbcSamePid(PREFIX + "xml-a");
            check("Mapper 写入 + JDBC 读取是同一物理连接",
                    mix[0] > 0 && mix[0] == mix[1], mix[0] + " / " + mix[1]);
            check("XML mapper 的 select 可用（countByCode）",
                    probe.countByCode(PREFIX + "xml-a") == 1,
                    probe.countByCode(PREFIX + "xml-a"));

            // ⑦ C 批：Mapper 的插入也受 @Transactional 回滚约束
            try {
                probe.mapperInsertThenThrow(PREFIX + "xml-rollback");
                check("Mapper 事务异常应向外传播", false, "没抛");
            } catch (IllegalStateException expected) {
                check("Mapper 事务异常应向外传播", true, expected.getMessage());
            }
            check("事务回滚后 Mapper 插入也不存在",
                    probe.countByCode(PREFIX + "xml-rollback") == 0,
                    probe.countByCode(PREFIX + "xml-rollback"));

            cleanup(db);
        } finally {
            ctx.close();
        }
        System.out.printf("%n通过 %d, 失败 %d%n", pass, fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static int count(DbConfig db, String like) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM workflow WHERE tenant_id=1 AND code LIKE ?")) {
            ps.setString(1, like);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        } catch (Exception e) {
            return -1;
        }
    }

    private static void cleanup(DbConfig db) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM workflow WHERE tenant_id=1 AND code LIKE ?")) {
            ps.setString(1, PREFIX + "%");
            ps.executeUpdate();
        } catch (Exception ignore) {
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
