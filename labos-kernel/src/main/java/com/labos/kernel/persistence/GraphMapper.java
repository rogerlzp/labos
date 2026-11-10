package com.labos.kernel.persistence;

/**
 * 流程图相关 SQL，语句见 {@code mapper/GraphMapper.xml}。
 *
 * <p>这里收的是「保存流程图」这条写路径上最容易出半成品的几条：
 * 备份上一版、清旧边、读当前 XML。以前它们散在 {@code WorkflowService}
 * 的多个 {@code try (Connection c = db.open())} 里，备份甚至发生在正式
 * 保存的事务之外 —— 备份成功但保存失败，历史里就多一条"上一版"，
 * 实际图并没换。现在统一由 {@code @Transactional} 管，SQL 也集中到 XML。
 */
public interface GraphMapper {

    // ---- 执行器的图读取（RealWorkflowRunner 的静态方法用 Mappers.get 取）----

    /** 流程头（id/code/name/category）；不存在返回 null。 */
    java.util.Map<String, Object> selectWorkflowHeader(long tenantId, long workflowId);

    /** 全部流程头（批量验收 runAll 用）。 */
    java.util.List<java.util.Map<String, Object>> selectWorkflowHeaders(long tenantId);

    /** 单个流程的边。**原实现按 workflow_id 过滤、不带 tenant**，沿用。 */
    java.util.List<java.util.Map<String, Object>> selectEdges(long workflowId);

    /** 全库边（按 workflow 分组前的原始行）。**同样不带 tenant**，沿用。 */
    java.util.List<java.util.Map<String, Object>> selectAllEdges();

    /** 每个流程的 BPMN XML（id + xml）。 */
    java.util.List<java.util.Map<String, Object>> selectGraphXmlRows(long tenantId);

    /** 现有节点 + 已标能力（saveGraph 增量 upsert 的比较基准）。 */
    java.util.List<java.util.Map<String, Object>> selectNodeCaps(long workflowId);

    /** 删掉本次图中不存在的节点（keep 为本次节点 key 全集）。 */
    int deleteNodesNotIn(long workflowId, java.util.List<String> keep);

    /** 重建一条边（重复即忽略）。 */
    int insertEdge(long tenantId, long workflowId, String fromKey, String toKey);

    /** 保存原始 XML 到 graph->>'xml'。 */
    int updateWorkflowGraph(long tenantId, long workflowId, String xml);

    /** 把当前图存一份到历史表（存在才写，无图时 0 行）。 */
    int backupGraph(long tenantId, long workflowId);

    /** 清掉上一版边，随后按新图重建。 */
    int deleteEdges(long workflowId);

    /** 读当前图 XML。 */
    String selectGraphXml(long tenantId, long workflowId);
}
