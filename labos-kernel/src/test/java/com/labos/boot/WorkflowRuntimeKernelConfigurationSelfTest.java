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
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Spring composition + MyBatis XML binding smoke test for the embedded Runtime.
 * Does not connect to PostgreSQL and does not start RuntimeHost.
 */
public final class WorkflowRuntimeKernelConfigurationSelfTest {
    private static final AtomicInteger PASSED = new AtomicInteger(0);

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
            require(props.scopedKernelTenantId() == null, "scopedKernelTenantId must default to null");
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
                    WorkflowRuntimeHostProperties.PROP_HB_TIMEOUT_MS, "not-a-number",
                    WorkflowRuntimeHostProperties.PROP_SCOPED_KERNEL_TENANT_ID, "not-a-number")));
            disabledCtx.registerBean("dataSource", DataSource.class, () -> ds);
            disabledCtx.registerBean("transactionManager", PlatformTransactionManager.class, () -> tm);
            disabledCtx.registerBean("graphMapper", GraphMapper.class, () -> mapper);
            disabledCtx.register(WorkflowRuntimeKernelConfiguration.class);
            disabledCtx.refresh();
            var disabledProps = disabledCtx.getBean(WorkflowRuntimeHostProperties.class);
            require(!disabledProps.enabled(),
                    "Explicitly disabled host must keep enabled=false");
            require(disabledProps.scopedKernelTenantId() == null,
                    "Disabled host must ignore malformed scoped-kernel-tenant-id");
            require(disabledCtx.getBeansOfType(com.labos.kernel.workflow.runtime.host.EmbeddedWorkflowRuntimeHost.class).isEmpty(),
                    "Disabled host must not instantiate EmbeddedWorkflowRuntimeHost");
        }

        // Verify optional scoped-kernel-tenant-id parsing, validation, and Config propagation when enabled.
        StandardEnvironment scopedEnv = new StandardEnvironment();
        scopedEnv.getPropertySources().addFirst(new MapPropertySource("scoped-valid", Map.of(
                WorkflowRuntimeHostProperties.PROP_ENABLED, "true",
                WorkflowRuntimeHostProperties.PROP_SCOPED_KERNEL_TENANT_ID, "1")));
        WorkflowRuntimeHostProperties scopedProps = WorkflowRuntimeHostProperties.fromEnvironment(scopedEnv);
        require(Long.valueOf(1L).equals(scopedProps.scopedKernelTenantId()),
                "scopedKernelTenantId must parse positive integer when enabled");
        UUID sampleTenantUuid = UUID.randomUUID();
        require(sampleTenantUuid.equals(scopedProps.toHostConfig(List.of(), sampleTenantUuid).scopedTenantId()),
                "toHostConfig(endpoints, scopedTenantId) must propagate scopedTenantId");
        require(scopedProps.toHostConfig(List.of()).scopedTenantId() == null,
                "toHostConfig(endpoints) must default scopedTenantId to null");
        for (String badScopedTenant : List.of("0", "-5", "abc")) {
            StandardEnvironment badEnv = new StandardEnvironment();
            badEnv.getPropertySources().addFirst(new MapPropertySource("scoped-bad-" + badScopedTenant, Map.of(
                    WorkflowRuntimeHostProperties.PROP_ENABLED, "true",
                    WorkflowRuntimeHostProperties.PROP_SCOPED_KERNEL_TENANT_ID, badScopedTenant)));
            boolean rejected = false;
            try {
                WorkflowRuntimeHostProperties.fromEnvironment(badEnv);
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            require(rejected, "Invalid scoped-kernel-tenant-id '" + badScopedTenant + "' must be rejected when enabled");
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

        // Verify bridge resourceId->deviceId map parsing.
        var resMap = WorkflowRuntimeHostProperties.parseResourceDeviceMap("206=pipettor, 207=centrifuge");
        require(resMap.size() == 2 && "pipettor".equals(resMap.get(206L)) && "centrifuge".equals(resMap.get(207L)),
                "Resource-to-device map parsing mismatch");
        boolean rejectedBadResMap = false;
        try {
            WorkflowRuntimeHostProperties.parseResourceDeviceMap("not-a-number=pipettor");
        } catch (IllegalArgumentException expected) {
            rejectedBadResMap = true;
        }
        require(rejectedBadResMap, "Non-numeric resourceId in bridge resource-map must be rejected");

        // Verify DeviceMapper.selectDeviceRegistry row conversion & merge precedence.
        var discovered = WorkflowRuntimeHostProperties.fromDeviceRegistryRows(java.util.List.of(
                Map.of("deviceId", 206L, "resourceId", 501L, "ip", "", "endpoint", "tcp://127.0.0.1:19500"),
                Map.of("deviceId", 207L, "resourceId", 502L, "ip", "192.168.10.22", "endpoint", "tcp://127.0.0.1:19501")));
        require(discovered.endpoints().size() == 2, "Expected 2 registry-discovered endpoints");
        require("206".equals(discovered.endpoints().get(0).deviceId())
                        && "127.0.0.1".equals(discovered.endpoints().get(0).host())
                        && discovered.endpoints().get(0).port() == 19500
                        && "labsep".equals(discovered.endpoints().get(0).wireCodec().protocolName()),
                "Registry endpoint #1 conversion mismatch");
        require("207".equals(discovered.endpoints().get(1).deviceId())
                        && "192.168.10.22".equals(discovered.endpoints().get(1).host())
                        && discovered.endpoints().get(1).port() == 19501,
                "Registry endpoint #2 must prefer explicit device_instance.ip over endpoint host");
        require("206".equals(discovered.resourceToDeviceMap().get(501L))
                        && "207".equals(discovered.resourceToDeviceMap().get(502L)),
                "Registry resourceId->deviceId map mismatch");
        var mergedEps = WorkflowRuntimeHostProperties.mergeEndpoints(
                discovered.endpoints(),
                WorkflowRuntimeHostProperties.parseEndpoints("206=ndjson://127.0.0.1:19999"));
        require(mergedEps.size() == 2 && mergedEps.get(0).port() == 19999
                        && "ndjson".equals(mergedEps.get(0).wireCodec().protocolName()),
                "Explicit gateway.endpoints must override registry endpoint for same deviceId");

        // Verify WorkflowRuntimeController + ApiAuthInterceptor RBAC wiring when host.enabled=false.
        try (AnnotationConfigApplicationContext webCtx = new AnnotationConfigApplicationContext()) {
            webCtx.registerBean("dataSource", DataSource.class, () -> ds);
            webCtx.registerBean("transactionManager", PlatformTransactionManager.class, () -> tm);
            webCtx.registerBean("graphMapper", GraphMapper.class, () -> mapper);
            webCtx.register(WorkflowRuntimeKernelConfiguration.class);
            webCtx.refresh();
            var facade = webCtx.getBean(WorkflowRuntimeKernelFacade.class);
            var controller = new com.labos.boot.web.controller.WorkflowRuntimeController(
                    facade, new com.labos.boot.web.AuditRecorder(null));
            var healthResp = controller.health();
            require(healthResp.getStatusCode().value() == 200
                            && healthResp.getBody() != null
                            && healthResp.getBody().contains("\"enabled\":false"),
                    "GET /api/runtime/health must return 200 with enabled=false when host disabled");
            var autoStartResp = controller.createRun("{\"workflowId\":1,\"autoStart\":true}", null, null);
            require(autoStartResp.getStatusCode().value() == 503,
                    "POST /api/runtime/runs with autoStart=true must return 503 when host disabled");
            var badUuidResp = controller.getRun("not-a-uuid");
            require(badUuidResp.getStatusCode().value() == 400,
                    "GET /api/runtime/runs/{badUuid} must return 400");
            var badReconcileResp = controller.reconcileUnknownCommand("not-a-uuid", "{}", null);
            require(badReconcileResp.getStatusCode().value() == 400,
                    "POST /api/runtime/commands/{badUuid}/reconcile must return 400");
        }
        try {
            var reqOp = com.labos.boot.web.ApiAuthInterceptor.class
                    .getDeclaredMethod("requiresOperate", String.class, String.class);
            reqOp.setAccessible(true);
            require((Boolean) reqOp.invoke(null, "POST", "/api/runtime/runs"),
                    "POST /api/runtime/runs must require operate role");
            require((Boolean) reqOp.invoke(null, "POST", "/api/runtime/commands/1/reconcile"),
                    "POST /api/runtime/commands/*/reconcile must require operate role");
            require(!(Boolean) reqOp.invoke(null, "GET", "/api/runtime/health"),
                    "GET /api/runtime/health must not require write operate role");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to verify ApiAuthInterceptor RBAC for /api/runtime", e);
        }

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

        System.out.println("Workflow Runtime Kernel Spring composition and MyBatis binding：通过 "
                + PASSED.get() + ", 失败 0");
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
        PASSED.incrementAndGet();
    }

    private WorkflowRuntimeKernelConfigurationSelfTest() {}
}
