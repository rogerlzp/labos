package com.labos.boot;

import com.labos.kernel.persistence.GraphMapper;
import com.labos.kernel.workflow.runtime.definition.LegacyKernelWorkflowDefinitionSource;
import com.labos.kernel.workflow.runtime.definition.WorkflowDefinitionSource;
import com.labos.kernel.workflow.runtime.definition.WorkflowRunFactory;
import com.labos.kernel.workflow.runtime.graph.BpmnGraphCompiler;
import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Embedded, single-process Workflow Runtime composition root. */
@Configuration
public class WorkflowRuntimeKernelConfiguration {
    @Bean
    public WorkflowRuntimeRepository workflowRuntimeRepository(
            DataSource dataSource, PlatformTransactionManager transactionManager) {
        return new WorkflowRuntimeRepository(dataSource, transactionManager);
    }

    @Bean public BpmnGraphCompiler bpmnGraphCompiler() { return new BpmnGraphCompiler(); }

    @Bean
    public WorkflowDefinitionSource workflowDefinitionSource(
            GraphMapper graphMapper, PlatformTransactionManager transactionManager) {
        LegacyKernelWorkflowDefinitionSource adapter = new LegacyKernelWorkflowDefinitionSource(
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
                    String definitionVersion = versionValue + "@" + updatedAt;
                    return new LegacyKernelWorkflowDefinitionSource.LegacyDefinition(
                            definitionVersion, bpmnXml, metadata);
                });
        TransactionTemplate definitionRead = new TransactionTemplate(transactionManager);
        definitionRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        definitionRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        definitionRead.setReadOnly(true);
        return (legacyTenantId, workflowId) ->
                definitionRead.execute(status -> adapter.load(legacyTenantId, workflowId));
    }

    @Bean
    public WorkflowRunFactory workflowRunFactory(
            WorkflowDefinitionSource definitions, BpmnGraphCompiler compiler,
            WorkflowRuntimeRepository repository) {
        return new WorkflowRunFactory(definitions, compiler, repository);
    }

    @Bean
    public WorkflowRuntimeKernelFacade workflowRuntimeKernelFacade(WorkflowRunFactory runs) {
        return new WorkflowRuntimeKernelFacade(runs);
    }
}
