package com.labos.kernel.api;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.Jsons;

import static com.labos.kernel.persistence.QJson.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 查询服务：把库里的事实转成 JSON。
 *
 * <p>刻意不做 ORM——查询是只读的、SQL 是稳定的，直接用 JDBC 更透明。
 */
public class QueryService {

    private final DbConfig db;
    private final long tenantId;

    public QueryService(DbConfig db, long tenantId) {
        this.db = db;
        this.tenantId = tenantId;
    }

    /** 读侧高频单查询（QueryMapper.xml）。由 BeanConfiguration 注入。 */
    private com.labos.kernel.persistence.QueryMapper queryMapper;

    public void setQueryMapper(com.labos.kernel.persistence.QueryMapper m) {
        this.queryMapper = m;
    }

    /** 实验域清场/校验（ExperimentMapper.xml）。由 BeanConfiguration 注入。 */
    private com.labos.kernel.persistence.ExperimentMapper experimentMapper;

    public void setExperimentMapper(com.labos.kernel.persistence.ExperimentMapper m) {
        this.experimentMapper = m;
    }

    /** 健康检查：能连库才算活着。 */
    public String health() throws SQLException {
        java.util.Map<String, Object> row = queryMapper.health();
        return Jsons.obj(
                "status", "ok",
                "database", row.get("db"),
                "version", shortVersion(String.valueOf(row.get("ver"))),
                "url", db.url(),
                "time", OffsetDateTime.now().toString());
    }

    /**
     * 整体替换实验变量（先删后插）。
     *
     * <p>变量数量少（通常 <10），整体替换比逐条 diff 简单可靠，
     * 也不会出现"删了一半"的中间态——全程一个事务。
     */
    /** Spring 声明式事务（checked 异常也回滚）。 */

    /**
     * 整体替换编排。
     *
     * <p>用「整体替换」而不是增量增删：编排是一个有序列表，
     * 前端拖完顺序后一次性提交最简单，也不会出现"删了一半"的中间状态。
     */
    /**
     * 人工插队：按给定顺序重排实验的优先级。
     *
     * <p>排程器（`ExperimentScheduler`）的排序是 **priority 从大到小**，其次按提交时间。
     * 所以这里把列表第一个给最高分：`priority = 100 - index`。
     *
     * <p>只允许重排**还没跑**的实验：已经开跑/跑完的单子插到前面没有意义，
     * 而且会让"历史记录里的顺序"和"现在的顺序"对不上。
     *
     * @return 实际更新的条数
     */
    /** Spring 声明式事务（checked 异常也回滚）。 */

    /** Spring 声明式事务（checked 异常也回滚）。 */

    /** Spring 声明式事务（checked 异常也回滚）。 */

    /**
     * 改用户：角色 / 启用 / 重置密码，三者都可为空（只改传了的那几项）。
     *
     * <p><b>三道闸门</b>：
     * <ol>
     *   <li>角色必须是 {@link RbacRules#ROLES} 白名单（未知角色直接 400，不落库）；</li>
     *   <li>不允许把"最后一个启用中的管理员"降级/停用 —— 否则系统再也没人能管账号
     *       （规则见 {@link RbacRules#lastAdminGuard}）；</li>
     *   <li>"读管理员数 → 写角色"必须在<b>同一事务 + 串行化锁</b>里完成，
     *       否则两个 admin 同时降级可能都读到 count=2 而同时通过（见下）。</li>
     * </ol>
     *
     * <p><b>为什么用 PG advisory lock 而不是行锁/表锁</b>：
     * <ul>
     *   <li>这是跨行、跨用户的约束检查（"全局还剩几个启用 admin"）：单行锁只能锁住
     *       目标这一行（或已存在的 admin 行），锁不住"并发停用另一个 admin"；
     *       锁整张 sys_user 又太重，会把普通登录/改密码也堵住；</li>
     *   <li>{@code pg_advisory_xact_lock(hashtext('labos:user-role'))} 用固定字符串取
     *       一把<b>事务级</b>互斥锁：所有走本方法的角色/启用写入天然串行，
     *       事务提交/回滚自动释放，不用手工 unlock，也不占业务表锁；</li>
     *   <li>单实例部署不涉及多节点分布式锁；将来多实例时 PG 仍是同一个库，
     *       这个 advisory lock 依然有效（它本来就是库级原语）。</li>
     * </ul>
     *
     * <p>纯改密码不触发"最后管理员"保护（保持行为不变）；用户不存在返回 0（保持）。
     */
    /** Spring 声明式事务（checked 异常也回滚）。 */

    /** 保存实验编排图（整体替换），并同步编译成 experiment_process（含偏序边）。 */
    /** A 批：Spring 声明式事务。checked 异常也要回滚（默认只回滚 RuntimeException）。 */

    /**
     * 错误码字典 + 它的**主恢复策略**（priority 最小的那条）。
     *
     * <p>作用域（scope）收口到库以后，这张表就是"出错之后怎么处理"的唯一真相；
     * 没有界面时改一个码要写三条 SQL，现场没人敢动。
     */
    /**
     * 新建一个实验。
     *
     * <p>为什么必须走代码而不是让人写 SQL：{@code experiment} + {@code experiment_process}
     * 是两张表，少写后者就会出现"实验建好了但没有任何流程、点执行什么也不跑"。
     * 之前测试用的 815 就是手工 SQL 建的，正是这个状态。
     *
     * @param workflowIds 可选：按顺序挂上的流程（写进 experiment_process.seq 1..N）
     */
    /** Spring 声明式事务（checked 异常也回滚）。 */

    /**
     * 改一个错误码：码本身的属性（scope/severity/title）+
     * 它的**主策略**（strategy/max_attempts/params/enabled）。
     *
     * <p>一次事务：码改了但策略没改，运行时就还是改动前的行为，排查会更难。
     */
    /** Spring 声明式事务（checked 异常也回滚）。 */

    /** Spring 声明式事务（checked 异常也回滚）。 */

    /**
     * 按型号部署一台新设备到岛。
     *
     * <p>编号自动递增（{@code Incubator_2C-20}），IP 留空由现场填——
     * 同一型号的多台设备 IP 一定不同，不能猜。
     */
    /** Spring 声明式事务（checked 异常也回滚）。 */

    /**
     * 删设备：软删实例 + 从资源树移除。
     *
     * <p>不硬删：历史 run_node 可能引用这台设备，硬删会断链。
     */
    /** Spring 声明式事务（checked 异常也回滚）。 */

    /**
     * 全量替换实验 Handoff 配置。
     *
     * @return 写入条数；-1 实验不存在；-2 起点/终点无效或不在显式 Workcell 绑定内
     */
    /** Spring 声明式事务（checked 异常也回滚）。 */

    /**
     * N-10：人工确认一条 Handoff（配置 → confirmed），并落 L-01 介入记录。
     *
     * @return 更新条数（0 = 不存在/已确认）
     */
    /** Spring 声明式事务（checked 异常也回滚）。 */

    /**
     * 全量替换实验 Workcell 绑定（N-10 显式绑定写入口）。
     *
     * @return 写入条数（空列表 = 清空绑定，回到资源树推导）
     */
    /** Spring 声明式事务（checked 异常也回滚）。 */

    // ---------------------------------------------------------------- 工具

    /** 极简 JSON 解析：只处理当前画布保存用到的对象/数组/标量。 */

}
