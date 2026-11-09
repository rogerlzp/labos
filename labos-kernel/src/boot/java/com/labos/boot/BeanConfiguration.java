package com.labos.boot;

import com.labos.kernel.api.ApiServer;
import com.labos.kernel.api.AuthService;
import com.labos.kernel.api.QueryService;
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
    public QueryService queryService(DbConfig db, QueryMapper queryMapper,
                                     ExperimentMapper experimentMapper) {
        QueryService qs = new QueryService(db, TENANT);
        qs.setQueryMapper(queryMapper);
        qs.setExperimentMapper(experimentMapper);
        return qs;
    }

    @Bean
    public AuthService authService(DbConfig db) {
        return new AuthService(db, TENANT);
    }

    @Bean
    public WorkflowService workflowService(DbConfig db, WorkflowMapper workflowMapper,
                                       RunMapper runMapper, GraphMapper graphMapper) {
        int driverPort = Integer.parseInt(
                System.getenv().getOrDefault("LABOS_DRIVER_PORT", "19301"));
        WorkflowService ws = new WorkflowService(db, TENANT, driverPort);
        ws.setWorkflowMapper(workflowMapper);
        ws.setRunMapper(runMapper);
        ws.setGraphMapper(graphMapper);
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

    /** 非 Bean 工具类通过 {@code Mappers.get(...)} 拿 Mapper；容器就绪时绑定一次。 */
    @Bean
    public Boolean mappersBinder(org.apache.ibatis.session.SqlSessionFactory factory) {
        com.labos.kernel.persistence.Mappers.bind(factory);
        return Boolean.TRUE;
    }

    @Bean
    public ApiServer apiServer(DbConfig db, QueryService query, AuthService auth,
                               WorkflowService workflows, ExperimentScheduler scheduler,
                               HeartbeatService heartbeat) {
        int port = Integer.parseInt(
                System.getenv().getOrDefault("LABOS_API_PORT", "18080"));
        return new ApiServer(port, db, query, auth, workflows, scheduler, heartbeat);
    }
}
