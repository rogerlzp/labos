package com.labos.boot;

import com.labos.kernel.persistence.GraphMapper;
import com.labos.kernel.workflow.runtime.definition.WorkflowDefinitionSource;
import com.labos.kernel.workflow.runtime.definition.WorkflowRunFactory;
import com.labos.kernel.workflow.runtime.graph.BpmnGraphCompiler;
import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.io.Resources;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.Map;

/**
 * Spring composition + MyBatis XML binding smoke test for the embedded Runtime.
 * Does not connect to PostgreSQL and does not start RuntimeHost.
 */
public final class WorkflowRuntimeKernelConfigurationSelfTest {
    public static void main(String[] args) throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:postgresql://127.0.0.1:5432/not-used", "unused", "unused");
        PlatformTransactionManager tm = new DataSourceTransactionManager(ds);
        GraphMapper mapper = (GraphMapper) Proxy.newProxyInstance(
                GraphMapper.class.getClassLoader(), new Class<?>[]{GraphMapper.class},
                (proxy, method, arguments) -> {
                    throw new AssertionError("Mapper must not be called during bean composition: " + method.getName());
                });

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean("dataSource", DataSource.class, () -> ds);
            context.registerBean("transactionManager", PlatformTransactionManager.class, () -> tm);
            context.registerBean("graphMapper", GraphMapper.class, () -> mapper);
            context.register(WorkflowRuntimeKernelConfiguration.class);
            context.refresh();

            require(context.getBeansOfType(DataSource.class).size() == 1,
                    "Kernel composition must expose exactly one DataSource");
            require(context.getBeansOfType(PlatformTransactionManager.class).size() == 1,
                    "Kernel composition must expose exactly one PlatformTransactionManager");
            require(context.getBean(WorkflowRuntimeRepository.class) != null,
                    "WorkflowRuntimeRepository bean missing");
            require(context.getBean(BpmnGraphCompiler.class) != null,
                    "BpmnGraphCompiler bean missing");
            require(context.getBean(WorkflowDefinitionSource.class) != null,
                    "WorkflowDefinitionSource bean missing");
            require(context.getBean(WorkflowRunFactory.class) != null,
                    "WorkflowRunFactory bean missing");
            require(context.getBean(WorkflowRuntimeKernelConfiguration.WorkflowRuntimeTenantResolver.class) != null,
                    "WorkflowRuntimeTenantResolver bean missing");
            require(context.getBean(WorkflowRuntimeKernelFacade.class) != null,
                    "WorkflowRuntimeKernelFacade bean missing");
            WorkflowRuntimeHostProperties props = context.getBean(WorkflowRuntimeHostProperties.class);
            require(!props.enabled(), "Embedded RuntimeHost feature flag must default to false");
            require(context.getBean(WorkflowRuntimeKernelFacade.class).hostHealth().isEmpty(),
                    "Facade hostHealth() must be empty when RuntimeHost is disabled");
            require(context.getBeansOfType(com.labos.kernel.workflow.runtime.host.RuntimeHost.class).isEmpty(),
                    "RuntimeHost must not start while labos.workflow.runtime.host.enabled=false");
            require(context.getBeansOfType(com.labos.kernel.workflow.runtime.host.EmbeddedWorkflowRuntimeHost.class).isEmpty(),
                    "EmbeddedWorkflowRuntimeHost must not start while feature flag is disabled");
        }

        // Verify malformed endpoint & numeric gateway properties are ignored when host.enabled=false.
        try (AnnotationConfigApplicationContext disabledCtx = new AnnotationConfigApplicationContext()) {
            disabledCtx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("disabled-bad-ep", Map.of(
                    WorkflowRuntimeHostProperties.PROP_ENABLED, "false",
                    WorkflowRuntimeHostProperties.PROP_ENDPOINTS, "broken-endpoint-ignored-when-disabled",
                    WorkflowRuntimeHostProperties.PROP_HB_TIMEOUT_MS, "not-a-number")));
            disabledCtx.registerBean("dataSource", DataSource.class, () -> ds);
            disabledCtx.registerBean("transactionManager", PlatformTransactionManager.class, () -> tm);
            disabledCtx.registerBean("graphMapper", GraphMapper.class, () -> mapper);
            disabledCtx.register(WorkflowRuntimeKernelConfiguration.class);
            disabledCtx.refresh();
            require(!disabledCtx.getBean(WorkflowRuntimeHostProperties.class).enabled(),
                    "Explicitly disabled host must keep enabled=false");
            require(disabledCtx.getBeansOfType(com.labos.kernel.workflow.runtime.host.EmbeddedWorkflowRuntimeHost.class).isEmpty(),
                    "Disabled host must not instantiate EmbeddedWorkflowRuntimeHost");
        }

        // Verify fail-fast when labos.workflow.runtime.host.enabled=true lacks TaskHandlerResolver bean (ChatGPT P2).
        boolean failedFastMissingHandler = false;
        try (AnnotationConfigApplicationContext enabledCtx = new AnnotationConfigApplicationContext()) {
            enabledCtx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("enabled-no-handler", Map.of(
                    WorkflowRuntimeHostProperties.PROP_ENABLED, "true")));
            enabledCtx.registerBean("dataSource", DataSource.class, () -> ds);
            enabledCtx.registerBean("transactionManager", PlatformTransactionManager.class, () -> tm);
            enabledCtx.registerBean("graphMapper", GraphMapper.class, () -> mapper);
            enabledCtx.register(WorkflowRuntimeKernelConfiguration.class);
            enabledCtx.refresh();
        } catch (RuntimeException expected) {
            String fullMsg = expected.toString()
                    + (expected.getCause() == null ? "" : " | " + expected.getCause());
            failedFastMissingHandler = fullMsg.contains("TaskHandlerResolver");
        }
        require(failedFastMissingHandler,
                "Enabling RuntimeHost without a TaskHandlerResolver bean must fail fast during startup");

        // Verify endpoint parsing & strict validation on WorkflowRuntimeHostProperties (including wire codec schemes).
        var parsed = WorkflowRuntimeHostProperties.parseEndpoints(
                "dev-a=127.0.0.1:9101, dev-b=ndjson://192.168.1.50:9102, pipettor=labsep://127.0.0.1:19500");
        require(parsed.size() == 3, "Expected 3 parsed LAN device endpoints");
        require("dev-a".equals(parsed.get(0).deviceId()) && parsed.get(0).port() == 9101
                        && "ndjson".equals(parsed.get(0).wireCodec().protocolName()),
                "First parsed endpoint mismatch");
        require("dev-b".equals(parsed.get(1).deviceId()) && "192.168.1.50".equals(parsed.get(1).host())
                        && "ndjson".equals(parsed.get(1).wireCodec().protocolName()),
                "Second parsed endpoint mismatch");
        require("pipettor".equals(parsed.get(2).deviceId()) && parsed.get(2).port() == 19500
                        && "labsep".equals(parsed.get(2).wireCodec().protocolName()),
                "Third parsed labsep endpoint mismatch");
        boolean rejectedBad = false;
        try {
            WorkflowRuntimeHostProperties.parseEndpoints("broken-endpoint");
        } catch (IllegalArgumentException expected) {
            rejectedBad = true;
        }
        require(rejectedBad, "Malformed endpoint string must be rejected");
        boolean rejectedUnknownScheme = false;
        try {
            WorkflowRuntimeHostProperties.parseEndpoints("dev-x=unknown://127.0.0.1:9101");
        } catch (IllegalArgumentException expected) {
            rejectedUnknownScheme = true;
        }
        require(rejectedUnknownScheme, "Unknown wire codec scheme must be rejected");

        Configuration mybatis = new Configuration();
        try (InputStream xml = Resources.getResourceAsStream("mapper/GraphMapper.xml")) {
            new XMLMapperBuilder(xml, mybatis, "mapper/GraphMapper.xml", mybatis.getSqlFragments()).parse();
        }
        require(mybatis.hasStatement("com.labos.kernel.persistence.GraphMapper.selectRuntimeWorkflowHeader"),
                "Runtime-only tenant-scoped workflow header statement missing");
        require(mybatis.hasStatement("com.labos.kernel.persistence.GraphMapper.selectGraphXml"),
                "tenant-scoped BPMN XML statement missing");
        require(mybatis.hasStatement("com.labos.kernel.persistence.GraphMapper.selectRuntimeWorkflowNodeMetadata"),
                "tenant-scoped Runtime metadata statement missing");

        assertBoundSql(mybatis, "com.labos.kernel.persistence.GraphMapper.selectRuntimeWorkflowHeader",
                mapperParams(mybatis, "selectRuntimeWorkflowHeader"), 2,
                "tenant_id", "id", "deleted_at");
        assertBoundSql(mybatis, "com.labos.kernel.persistence.GraphMapper.selectGraphXml",
                mapperParams(mybatis, "selectGraphXml"), 2,
                "tenant_id", "id", "deleted_at");
        assertBoundSql(mybatis, "com.labos.kernel.persistence.GraphMapper.selectRuntimeWorkflowNodeMetadata",
                mapperParams(mybatis, "selectRuntimeWorkflowNodeMetadata"), 2,
                "tenant_id", "workflow_id", "c.tenant_id", "n.tenant_id");

        System.out.println("Workflow Runtime Kernel Spring composition and MyBatis binding：通过 25, 失败 0");
    }



    private static Object mapperParams(Configuration configuration, String methodName) throws Exception {
        var method = GraphMapper.class.getMethod(methodName, long.class, long.class);
        var resolver = new ParamNameResolver(configuration, method);
        return resolver.getNamedParams(new Object[]{7L, 9L});
    }

    private static void assertBoundSql(Configuration configuration, String statementId,
                                        Object parameters, int expectedParameterCount,
                                        String... requiredSqlTokens) {
        var bound = configuration.getMappedStatement(statementId).getBoundSql(parameters);
        require(bound.getParameterMappings().size() == expectedParameterCount,
                statementId + " parameter mapping count mismatch");
        String sql = bound.getSql().toLowerCase().replaceAll("\\s+", " ");
        for (String token : requiredSqlTokens) {
            require(sql.contains(token.toLowerCase()),
                    statementId + " SQL missing required token: " + token);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private WorkflowRuntimeKernelConfigurationSelfTest() {}
}
