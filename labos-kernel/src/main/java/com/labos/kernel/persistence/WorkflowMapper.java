package com.labos.kernel.persistence;

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
}
