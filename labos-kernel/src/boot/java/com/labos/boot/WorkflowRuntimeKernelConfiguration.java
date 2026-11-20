package com.labos.boot;

import com.labos.kernel.persistence.GraphMapper;
import com.labos.kernel.workflow.runtime.bridge.GatewayDeviceTaskBridge;
import com.labos.kernel.workflow.runtime.definition.LegacyKernelWorkflowDefinitionSource;
import com.labos.kernel.workflow.runtime.definition.WorkflowDefinitionSource;
import com.labos.kernel.workflow.runtime.definition.WorkflowRunFactory;
import com.labos.kernel.workflow.runtime.dispatch.LocalTaskDispatchService;
import com.labos.kernel.workflow.runtime.graph.BpmnGraphCompiler;
import com.labos.kernel.workflow.runtime.host.EmbeddedWorkflowRuntimeHost;
import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Spring Boot composition root for the embedded, single-process Workflow Runtime.
 *
 * <p>All Runtime JDBC work uses the exact Kernel DataSource and transaction manager.
 * No Runtime-owned pool or transaction manager is created here.
 */
@Configuration
public class WorkflowRuntimeKernelConfiguration {

    @Bean
    public WorkflowRuntimeRepository workflowRuntimeRepository(
            DataSource dataSource, PlatformTransactionManager transactionManager) {
        return new WorkflowRuntimeRepository(dataSource, transactionManager);
    }

    @Bean
    public BpmnGraphCompiler bpmnGraphCompiler() {
        return new BpmnGraphCompiler();
    }

    @Bean
    public WorkflowDefinitionSource workflowDefinitionSource(
            GraphMapper graphMapper, WorkflowRuntimeRepository repository,
            PlatformTransactionManager transactionManager) {
        LegacyKernelWorkflowDefinitionSource adapter = new LegacyKernelWorkflowDefinitionSource(
                repository::findTenantKey,
                (legacyTenantId, workflowId) -> {
                    Map<String, Object> header =
                            graphMapper.selectRuntimeWorkflowHeader(legacyTenantId, workflowId);
                    if (header == null || header.isEmpty()) return null;

                    String bpmnXml = graphMapper.selectGraphXml(legacyTenantId, workflowId);
                    if (bpmnXml == null || bpmnXml.isBlank())
                        throw new IllegalStateException("Workflow has no persisted BPMN XML: " + workflowId);

                    Object versionValue = header.get("version");
                    if (versionValue == null || versionValue.toString().isBlank())
                        throw new IllegalStateException("Workflow definition version is missing: " + workflowId);

                    List<Map<String, Object>> rows =
                            graphMapper.selectRuntimeWorkflowNodeMetadata(legacyTenantId, workflowId);
                    Map<String, WorkflowDefinitionSource.NodeMetadata> metadata = new LinkedHashMap<>();
                    for (Map<String, Object> row : rows) {
                        Object keyValue = row.get("nodeKey");
                        if (keyValue == null || keyValue.toString().isBlank())
                            throw new IllegalStateException("workflow_node row has no node_key: workflow " + workflowId);
                        String key = keyValue.toString();
                        Object idValue = row.get("id");
                        Long nodeId = idValue instanceof Number n ? n.longValue()
                                : idValue == null ? null : Long.valueOf(idValue.toString());
                        Object capabilityValue = row.get("capabilityCode");
                        String capability = capabilityValue == null ? null : capabilityValue.toString();
                        if (metadata.putIfAbsent(key,
                                new WorkflowDefinitionSource.NodeMetadata(capability, nodeId)) != null)
                            throw new IllegalStateException("Duplicate workflow_node key in metadata: " + key);
                    }
                    Object updatedAt = header.get("updatedAt");
                    if (updatedAt == null)
                        throw new IllegalStateException("Workflow updated_at is missing: " + workflowId);
                    // workflow.version is a business label and is not advanced by every graph save.
                    // Pair it with updated_at so the compiled definition revision is content-edit aware.
                    String definitionVersion = versionValue + "@" + updatedAt;
                    return new LegacyKernelWorkflowDefinitionSource.LegacyDefinition(
                            definitionVersion, bpmnXml, metadata);
                });

        // Header, BPMN XML and workflow_node metadata must describe one committed revision.
        // Force a dedicated repeatable-read transaction even if the caller already has a
        // lower-isolation transaction; otherwise three READ COMMITTED statements can mix revisions.
        TransactionTemplate definitionRead = new TransactionTemplate(transactionManager);
        definitionRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        definitionRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        definitionRead.setReadOnly(true);
        return (runtimeTenantId, workflowId) ->
                definitionRead.execute(status -> adapter.load(runtimeTenantId, workflowId));
    }

    @Bean
    public WorkflowRunFactory workflowRunFactory(
            WorkflowDefinitionSource definitions,
            BpmnGraphCompiler compiler,
            WorkflowRuntimeRepository repository) {
        return new WorkflowRunFactory(definitions, compiler, repository);
    }

    @Bean
    public WorkflowRuntimeTenantResolver workflowRuntimeTenantResolver(
            WorkflowRuntimeRepository repository) {
        return new WorkflowRuntimeTenantResolver(repository);
    }

    @Bean
    public WorkflowRuntimeHostProperties workflowRuntimeHostProperties(Environment environment) {
        return WorkflowRuntimeHostProperties.fromEnvironment(environment);
    }

    @Bean
    public WorkflowRuntimeKernelFacade workflowRuntimeKernelFacade(
            WorkflowRuntimeTenantResolver tenants,
            WorkflowRunFactory runs,
            WorkflowRuntimeRepository repository,
            ObjectProvider<EmbeddedWorkflowRuntimeHost> runtimeHostProvider) {
        return new WorkflowRuntimeKernelFacade(tenants, runs, repository, runtimeHostProvider);
    }

    /**
     * Opt-in single-process {@link EmbeddedWorkflowRuntimeHost} (including in-process TCP
     * {@code DeviceGateway}). Strictly disabled unless {@code labos.workflow.runtime.host.enabled=true}.
     */
    @Configuration
    @ConditionalOnProperty(name = WorkflowRuntimeHostProperties.PROP_ENABLED, havingValue = "true")
    public static class EmbeddedRuntimeHostConfiguration {

        @Bean
        @ConditionalOnProperty(name = WorkflowRuntimeHostProperties.PROP_BRIDGE_ENABLED, havingValue = "true")
        public GatewayDeviceTaskBridge gatewayDeviceTaskBridge(
                WorkflowRuntimeHostProperties properties,
                WorkflowRuntimeRepository repository,
                Environment environment) {
            long timeoutMs = Long.parseLong(
                    environment.getProperty(WorkflowRuntimeHostProperties.PROP_BRIDGE_COMMAND_TIMEOUT_MS, "300000").trim());
            Map<Long, String> resourceMap = WorkflowRuntimeHostProperties.parseResourceDeviceMap(
                    environment.getProperty(WorkflowRuntimeHostProperties.PROP_BRIDGE_RESOURCE_MAP, ""));
            return new GatewayDeviceTaskBridge(
                    repository, properties.deviceEndpoints(), resourceMap, Duration.ofMillis(timeoutMs));
        }

        @Bean
        @ConditionalOnProperty(name = WorkflowRuntimeHostProperties.PROP_BRIDGE_ENABLED, havingValue = "true")
        public LocalTaskDispatchService.TaskDispatchSpecResolver bridgeTaskDispatchSpecResolver(
                GatewayDeviceTaskBridge bridge) {
            return bridge.specResolver();
        }

        @Bean
        @ConditionalOnProperty(name = WorkflowRuntimeHostProperties.PROP_BRIDGE_ENABLED, havingValue = "true")
        public LocalTaskDispatchService.TaskHandlerResolver bridgeTaskHandlerResolver(
                GatewayDeviceTaskBridge bridge) {
            return bridge.handlerResolver();
        }

        @Bean(initMethod = "start", destroyMethod = "close")
        public EmbeddedWorkflowRuntimeHost embeddedWorkflowRuntimeHost(
                WorkflowRuntimeHostProperties properties,
                WorkflowRuntimeRepository repository,
                ObjectProvider<LocalTaskDispatchService.TaskDispatchSpecResolver> specResolverProvider,
                ObjectProvider<LocalTaskDispatchService.TaskHandlerResolver> handlerResolverProvider) {
            LocalTaskDispatchService.TaskHandlerResolver handlerResolver = handlerResolverProvider.getIfAvailable();
            if (handlerResolver == null) {
                throw new IllegalStateException(
                        WorkflowRuntimeHostProperties.PROP_ENABLED
                                + "=true requires a LocalTaskDispatchService.TaskHandlerResolver bean");
            }
            LocalTaskDispatchService.TaskDispatchSpecResolver specResolver = specResolverProvider.getIfAvailable();
            if (specResolver == null) {
                throw new IllegalStateException(
                        WorkflowRuntimeHostProperties.PROP_ENABLED
                                + "=true requires a LocalTaskDispatchService.TaskDispatchSpecResolver bean");
            }
            return new EmbeddedWorkflowRuntimeHost(
                    properties.toHostConfig(), repository, specResolver, handlerResolver);
        }
    }

    /**
     * Explicit, durable bridge from the legacy bigint tenant id to Runtime UUID.
     * The key is stable across restarts and must not be replaced with a random UUID.
     */
    public static final class WorkflowRuntimeTenantResolver {
        private final WorkflowRuntimeRepository repository;

        private WorkflowRuntimeTenantResolver(WorkflowRuntimeRepository repository) {
            this.repository = repository;
        }

        public java.util.UUID resolve(long kernelTenantId) {
            if (kernelTenantId <= 0) throw new IllegalArgumentException("Kernel tenant id must be positive");
            return repository.resolveTenantId(
                    LegacyKernelWorkflowDefinitionSource.TENANT_KEY_PREFIX + kernelTenantId,
                    "Kernel tenant " + kernelTenantId);
        }
    }
}
