package com.labos.boot;

import com.labos.kernel.persistence.GraphMapper;
import com.labos.kernel.workflow.runtime.definition.WorkflowDefinitionSource;
import com.labos.kernel.workflow.runtime.definition.WorkflowRunFactory;
import com.labos.kernel.workflow.runtime.graph.BpmnGraphCompiler;
import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.io.Resources;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.io.InputStream;
import java.lang.reflect.Proxy;

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
            require(context.getBeansOfType(com.labos.kernel.workflow.runtime.host.RuntimeHost.class).isEmpty(),
                    "RuntimeHost must not start before execution adapters/coordinator are implemented");
        }

        Configuration mybatis = new Configuration();
        try (InputStream xml = Resources.getResourceAsStream("mapper/GraphMapper.xml")) {
            new XMLMapperBuilder(xml, mybatis, "mapper/GraphMapper.xml", mybatis.getSqlFragments()).parse();
        }
        require(mybatis.hasStatement("com.labos.kernel.persistence.GraphMapper.selectWorkflowHeader"),
                "tenant-scoped workflow header statement missing");
        require(mybatis.hasStatement("com.labos.kernel.persistence.GraphMapper.selectGraphXml"),
                "tenant-scoped BPMN XML statement missing");
        require(mybatis.hasStatement("com.labos.kernel.persistence.GraphMapper.selectRuntimeWorkflowNodeMetadata"),
                "tenant-scoped Runtime metadata statement missing");

        System.out.println("✓ Workflow Runtime Kernel Spring composition and MyBatis binding 10 / 0");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private WorkflowRuntimeKernelConfigurationSelfTest() {}
}
