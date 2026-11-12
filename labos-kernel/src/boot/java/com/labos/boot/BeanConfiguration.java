package com.labos.boot;

import com.labos.kernel.api.ApiServer;
import com.labos.kernel.api.AuthService;
import com.labos.kernel.driver.HeartbeatService;
import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.WorkflowMapper;
import com.labos.kernel.persistence.RunMapper;
import com.labos.kernel.persistence.GraphMapper;
import com.labos.kernel.persistence.ExperimentMapper;
import com.labos.kernel.persistence.QueryMapper;
import com.labos.kernel.workflow.ExperimentScheduler;
import com.labos.kernel.workflow.WorkflowService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * A 批：所有 Service 由容器创建，不再在 {@code ApiServer} 构造函数里 {@code new}。
 *
 * <p>这是 {@code @Transactional} 能生效的前提：调用方拿到的是 Spring AOP 代理对象，
 * 而不是裸实例。{@code WorkflowService} / {@code QueryService} 的 {@code final}
 * 已去掉（CGLIB 不能代理 final 类）。
 *
 * <p>同类内部 {@code this.xxx()} 自调用仍不经过代理 —— 写路径集中在
 * {@code createWorkflow} / {@code saveGraph} / {@code updateNodeBinding} /
 * {@code saveExperimentGraph} 四个入口，事务在入口开启，内部调用共享同一连接。
 */
@Configuration
public class BeanConfiguration {

    private static final long TENANT = 1L;

    @Bean
    public DbConfig dbConfig(DataSource ds) {
        DbConfig.bind(ds);
        return DbConfig.fromEnv();
    }


    @Bean
    public AuthService authService(DbConfig db) {
        return new AuthService(db, TENANT);
    }

    @Bean
    public WorkflowService workflowService(DbConfig db, WorkflowMapper workflowMapper,
                                       RunMapper runMapper, GraphMapper graphMapper,
                                       QueryMapper queryMapper) {
        int driverPort = Integer.parseInt(
                System.getenv().getOrDefault("LABOS_DRIVER_PORT", "19301"));
        WorkflowService ws = new WorkflowService(db, TENANT, driverPort);
        ws.setWorkflowMapper(workflowMapper);
        ws.setRunMapper(runMapper);
        ws.setGraphMapper(graphMapper);
        ws.setQueryMapper(queryMapper);
        return ws;
    }

    @Bean
    public ExperimentScheduler experimentScheduler(DbConfig db, WorkflowService workflows) {
        return new ExperimentScheduler(db, TENANT, workflows);
    }

    @Bean
    public HeartbeatService heartbeatService(DbConfig db) {
        return new HeartbeatService(db, TENANT);
    }

    /**
     * §3.5 / §8.2 第 4 项（评审 R13-07）：物料效果补偿 Worker。
     *
     * <p>此前 MaterialWorker 类写好了却没有任何 new/start，T2 失败后 PENDING
     * 效果只能靠人工端点处理。这里在容器启动时拉起后台线程，容器停止时调 stop()。
     * 依赖 mappersBinder：MaterialEffectService 构造时要用 Mappers.get(MaterialMapper.class)。
     */
    @Bean(destroyMethod = "stop")
    @org.springframework.context.annotation.DependsOn("mappersBinder")
    public com.labos.kernel.material.MaterialWorker materialWorker(DbConfig db) {
        com.labos.kernel.material.MaterialWorker worker =
                new com.labos.kernel.material.MaterialWorker(
                        new com.labos.kernel.material.MaterialEffectService(TENANT, db), 2_000);
        if (!"false".equalsIgnoreCase(
                System.getProperty("labos.material.worker.enabled", "true"))) {
            Thread t = new Thread(worker, "labos-material-worker");
            t.setDaemon(true);
            t.start();
        }
        return worker;
    }

    /** 非 Bean 工具类通过 {@code Mappers.get(...)} 拿 Mapper；容器就绪时绑定一次。 */
    @Bean
    public Boolean mappersBinder(org.apache.ibatis.session.SqlSessionFactory factory) {
        com.labos.kernel.persistence.Mappers.bind(factory);
        return Boolean.TRUE;
    }

    @Bean
    public ApiServer apiServer(DbConfig db, AuthService auth,
                               WorkflowService workflows, ExperimentScheduler scheduler,
                               HeartbeatService heartbeat) {
        int port = Integer.parseInt(
                System.getenv().getOrDefault("LABOS_API_PORT", "18080"));
        return new ApiServer(port, db, auth, workflows, scheduler, heartbeat);
    }
}
