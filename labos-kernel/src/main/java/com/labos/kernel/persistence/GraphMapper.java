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

    /**
     * 节点 upsert（saveGraph 用）。
     *
     * <p>为什么用 {@code INSERT ... ON CONFLICT (workflow_id, node_key) DO UPDATE}
     * 而不是「先 UPDATE 再 INSERT」：{@code uk_workflow_node} 已经是唯一约束，
     * 冲突裁决交给数据库，省掉「先查存在性、再分两条语句」的窗口。
     *
     * <p>三个**故意不覆盖**的列，与原两条语句语义一致：
     * <ul>
     *   <li>{@code capability_id}：老节点的能力由节点绑定写入，页面级保存不该改它；</li>
     *   <li>{@code estimate_sec}：只在新建时给 0，重存不动已学习的估值；</li>
     *   <li>{@code created_at}：不碰。</li>
     * </ul>
     *
     * <p>{@code params} 保留原「合并」语义：新节点写 {@code insertParams}
     * （含 origin=bpmn_editor），老节点用
     * {@code COALESCE(params,'{}') || updateParams} 只回写
     * protocol_name/method_params，不动节点设置里其它键。
     */
    int upsertNode(@org.apache.ibatis.annotations.Param("tenantId") long tenantId,
                   @org.apache.ibatis.annotations.Param("workflowId") long workflowId,
                   @org.apache.ibatis.annotations.Param("nodeKey") String nodeKey,
                   @org.apache.ibatis.annotations.Param("name") String name,
                   @org.apache.ibatis.annotations.Param("nodeType") String nodeType,
                   @org.apache.ibatis.annotations.Param("capabilityId") Long capabilityId,
                   @org.apache.ibatis.annotations.Param("insertParams") String insertParams,
                   @org.apache.ibatis.annotations.Param("updateParams") String updateParams,
                   @org.apache.ibatis.annotations.Param("resourceId") Long resourceId,
                   @org.apache.ibatis.annotations.Param("seq") Integer seq);

    // ---- 执行器的图读取（RealWorkflowRunner 的静态方法用 Mappers.get 取）----

    /** 流程头（id/code/name/category）；不存在返回 null。 */
    java.util.Map<String, Object> selectWorkflowHeader(long tenantId, long workflowId);

    /** 全部流程头（批量验收 runAll 用）。 */
    java.util.List<java.util.Map<String, Object>> selectWorkflowHeaders(long tenantId);

    /** 单个流程的边。**原实现按 workflow_id 过滤、不带 tenant**，沿用。 */
    java.util.List<java.util.Map<String, Object>> selectEdges(long workflowId);

    /** 全库边（按 workflow 分组前的原始行）。**同样不带 tenant**，沿用。 */
    java.util.List<java.util.Map<String, Object>> selectAllEdges();

    /**
     * 图修复工具用：只取未删且 XML 非空的流程。
     *
     * <p>区别于 {@link #selectGraphXmlRows}（那是执行器全量读，不筛 deleted_at）。
     */
    java.util.List<java.util.Map<String, Object>> selectRepairableWorkflowXmls(long tenantId);

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
