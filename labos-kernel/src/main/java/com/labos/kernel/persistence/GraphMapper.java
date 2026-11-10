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

    /** 主链选择：流程节点行（params 显式 ::text，调用方自行解析 JSON）。 */
    java.util.List<java.util.Map<String, Object>> selectMainPathNodes(
            long tenantId, long workflowId);

    /** 主链选择：流程边（fromKey→toKey，按 id 升序）。 */
    java.util.List<java.util.Map<String, Object>> selectMainPathEdges(
            long tenantId, long workflowId);

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

    /** 单个流程的节点定义（含能力码 / protocol_name / 绑定实例，按 id 升序）。 */
    java.util.List<java.util.Map<String, Object>> selectWorkflowNodes(long workflowId);

    /**
     * 全库节点定义（批量验收避免按流程逐条查）。
     * 列与 {@link #selectWorkflowNodes} 相同，多 workflowId。
     */
    java.util.List<java.util.Map<String, Object>> selectAllWorkflowNodes();

    /**
     * 能力池：capability.code -&gt; resource_id 数组（array_agg，Map 里为 java.sql.Array）。
     */
    java.util.List<java.util.Map<String, Object>> selectCapabilityPools();

    /**
     * protocol_name -&gt; capability（DISTINCT + ORDER BY 保证可复现；
     * 同名方法跨能力冲突由调用方 putIfAbsent 取排序第一 + bound 优先级裁决）。
     */
    java.util.List<java.util.Map<String, Object>> selectProtocolCapabilities();

    /** 资源/实例 id -&gt; 能力码（resource_node 与 device_instance 两个 id 空间 UNION）。 */
    java.util.List<java.util.Map<String, Object>> selectResourceCapabilities();

    /** 库里的显式能力标注：task 节点的 (workflowId, nodeKey, capabilityCode)。 */
    java.util.List<java.util.Map<String, Object>> selectCapabilityAnnotations(long tenantId);

    // ---- WorkflowBuilder（搬运序列落成正式流程，全事务写集）----

    /**
     * 建 workflow 行（category 固定 choreography）；返回自增 id。
     *
     * <p>{@code islandId} = 搬运序列**派生出来的岛**（P-05：choreography build 要
     * "derive or require Island"）—— 传 null 表示这条搬运流程没有岛约束。
     */
    Long insertWorkflowRow(@org.apache.ibatis.annotations.Param("tenantId") long tenantId,
                           @org.apache.ibatis.annotations.Param("code") String code,
                           @org.apache.ibatis.annotations.Param("name") String name,
                           @org.apache.ibatis.annotations.Param("islandId") Long islandId);

    /**
     * 建节点行：capability_id 按 (tenant, capabilityCode) 子查询
     * （code 不存在时写入 NULL，与原语句一致）；返回自增 id。
     */
    Long insertWorkflowNodeRow(
            @org.apache.ibatis.annotations.Param("tenantId") long tenantId,
            @org.apache.ibatis.annotations.Param("workflowId") long workflowId,
            @org.apache.ibatis.annotations.Param("nodeKey") String nodeKey,
            @org.apache.ibatis.annotations.Param("name") String name,
            @org.apache.ibatis.annotations.Param("nodeType") String nodeType,
            @org.apache.ibatis.annotations.Param("capabilityCode") String capabilityCode,
            @org.apache.ibatis.annotations.Param("paramsJson") String paramsJson);

    /** 顺序边（edge_type='sequence'）；无幂等子句 —— 调用方事务内一次性建。 */
    int insertWorkflowEdgeRow(
            @org.apache.ibatis.annotations.Param("tenantId") long tenantId,
            @org.apache.ibatis.annotations.Param("workflowId") long workflowId,
            @org.apache.ibatis.annotations.Param("fromKey") String fromKey,
            @org.apache.ibatis.annotations.Param("toKey") String toKey);

    /** 回写 graph XML（jsonb_build_object 整包替换，区别于 saveGraph 的合并写）。 */
    int writeWorkflowGraphXml(
            @org.apache.ibatis.annotations.Param("tenantId") long tenantId,
            @org.apache.ibatis.annotations.Param("workflowId") long workflowId,
            @org.apache.ibatis.annotations.Param("xml") String xml);

    /** 资源绑定的设备能力码（device_instance -> device -> capability）；无返回 null。 */
    String selectCapabilityCodeOfResource(
            @org.apache.ibatis.annotations.Param("resourceId") long resourceId);

    // ---- BindingBackfill（设备绑定/顺序回填工具，阶段3第20批收编）----

    /**
     * 回填范围：未删流程的 (id, xml)，按 id 升序。
     *
     * <p>区别于 {@link #selectRepairableWorkflowXmls}（多 code 列、无 ORDER BY）与
     * {@link #selectGraphXmlRows}（不筛 deleted_at）：回填要的是确定性的全量顺序遍历。
     */
    java.util.List<java.util.Map<String, Object>> selectBackfillWorkflowXmls(long tenantId);

    /**
     * 单节点的现有绑定（resourceId/seq，Map 值，NULL 列为 null）；行不存在返回 null。
     * {@code resourceId} 为 null 即「XML 未绑定设备」。
     */
    java.util.Map<String, Object> selectNodeBinding(
            @org.apache.ibatis.annotations.Param("tenantId") long tenantId,
            @org.apache.ibatis.annotations.Param("workflowId") long workflowId,
            @org.apache.ibatis.annotations.Param("nodeKey") String nodeKey);

    /**
     * 仅当现有值与目标值不同才更新（{@code IS DISTINCT FROM} 幂等护栏），
     * 返回实际更新行数（0=本来一致）。
     * {@code resourceId} 可空（解绑），XML 里统一 {@code jdbcType=BIGINT}。
     */
    int updateNodeBinding(
            @org.apache.ibatis.annotations.Param("tenantId") long tenantId,
            @org.apache.ibatis.annotations.Param("workflowId") long workflowId,
            @org.apache.ibatis.annotations.Param("nodeKey") String nodeKey,
            @org.apache.ibatis.annotations.Param("resourceId") Long resourceId,
            @org.apache.ibatis.annotations.Param("seq") int seq);
}
