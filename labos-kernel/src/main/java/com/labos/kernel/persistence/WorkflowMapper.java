package com.labos.kernel.persistence;

import org.apache.ibatis.annotations.Param;

/**
 * C 批：workflow 相关的 SQL 收口到这里，具体语句见
 * {@code src/boot/resources/mapper/WorkflowMapper.xml}。
 *
 * <p>现场决策用 MyBatis XML 而不是 JdbcTemplate：SQL 与 Java 分离后，
 * 一条语句长什么样、走哪些列、有没有 tenant 条件，可以直接读 XML，
 * 不用在几百行 Java 字符串里翻。
 *
 * <p>接口本身不依赖 MyBatis 注解，由 {@code @MapperScan} 注册；
 * 与 {@code @Transactional} 共用 Spring 绑定的同一个连接。
 */
public interface WorkflowMapper {

    /** 新建流程；自增 id 回填到 {@link WorkflowRow#getId()}。 */
    int insertWorkflow(WorkflowRow row);

    /** 权威岛校验：只有 resource_node 里 kind='island' 的 id 才算数。 */
    int countIsland(long tenantId, long islandId);

    /** 读流程的权威岛（P-01）。 */
    Long selectIslandId(long tenantId, long workflowId);

    /** 按编号查重（新建流程时给友好报错）。 */
    int countByCode(long tenantId, String code);

    /**
     * 流程列表：最近改动在前（NULLS LAST），带 task 数统计。
     *
     * <p>{@code category} 为 null/空时不过滤——不写成 "?::text IS NULL"：
     * JDBC 推断不出 NULL 的类型，条件永远为假。
     */
    java.util.List<java.util.Map<String, Object>> selectWorkflowList(
            @Param("tenantId") long tenantId,
            @Param("category") String category,
            @Param("limit") int limit);
}
