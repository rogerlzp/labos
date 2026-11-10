package com.labos.kernel.persistence;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

import java.util.List;

/** 实验域的清场/校验类数据访问，语句见 {@code mapper/ExperimentMapper.xml}。 */
public interface ExperimentMapper {

    /** 实验是否存在（handoffs / workcells 两个写入口共用）。 */
    boolean experimentExists(long tenantId, long experimentId);

    /** 实验绑定的 workcell（handoff 校验起点/终点是否在绑定内）。 */
    List<Long> selectExperimentWorkcellIds(long tenantId, long experimentId);

    /** 变量逐行插入（调用方先 delete 再 insert，同一事务内）。 */
    int insertExperimentVariable(long tenantId, long experimentId, String name,
                                 String type, String defaultValue, String description);

    /** 新增一条实验流程，返回 id（用于把画布边翻译成 process 间的边）。 */
    Long insertExperimentProcess(long tenantId, long experimentId, long workflowId, int seq);

    /** 新增一条流程偏序边。 */
    int insertExperimentProcessEdge(long tenantId, long experimentId,
                                    long fromId, long toId);

    /** 确认工位交接（只允许 configured/unknown -> confirmed）。 */
    int confirmHandoffStatus(long tenantId, long experimentId,
                             long fromWorkcellId, long toWorkcellId);

    /** 交接确认的介入记录，返回 id。 */
    Long insertHandoffIntervention(long tenantId, String operator,
                                   String fromLocation, String toLocation, String note);

    /** N-08 联动：交接确认同时落一条已决策人工窗口，返回 id。 */
    Long insertHandoffWindow(long tenantId, String windowKey, String decisionReason,
                             String decidedBy, String note);

    /** 把介入记录与人工窗口回写到交接行。 */
    int linkHandoffRecords(long tenantId, long experimentId, long fromWorkcellId,
                           long toWorkcellId, long recordId, long windowId);

    /** 实验所属岛（未显式绑定 workcell 时的回落）。 */
    Long selectExperimentIslandId(long tenantId, long experimentId);

    /**
     * 单个 workflow 的权威 Workcell（island 根 id）：节点资源沿
     * resource_node.parent_id 递归上溯，取 kind='island' 的第一个；推导不出返回 null。
     */
    Long selectWorkflowIslandId(long workflowId);

    /**
     * 实验涉及的全部 Workcell 闭包：experiment_process 引用的 workflow 的
     * 节点资源递归上溯到 kind='island'（DISTINCT）。可能为空集合。
     */
    java.util.List<Long> selectExperimentIslandClosure(long tenantId, long experimentId);

    int deleteExperimentHandoffs(long tenantId, long experimentId);

    int deleteExperimentWorkcells(long tenantId, long experimentId);

    int deleteExperimentVariables(long tenantId, long experimentId);

    int deleteExperimentProcesses(long tenantId, long experimentId);

    /** 新增一条工位交接（重复则忽略）。 */
    int insertExperimentHandoff(long tenantId, long experimentId,
                                long fromWorkcellId, long toWorkcellId);

    /** 新增一条实验工位绑定（重复则忽略）。 */
    int insertExperimentWorkcell(long tenantId, long experimentId, long workcellId);

    /** 保存实验编排图（按 experiment_id 覆盖）。 */
    int upsertExperimentGraph(long tenantId, long experimentId, String graphJson);

    /** 实验流程的循环次数覆盖值（null = 用默认）。 */
    int updateExperimentProcessLoopCount(long tenantId, long experimentId, int seq,
                                         String loopCount);

    /**
     * 人工插队：数组顺序即执行顺序，priority 从 100 递减。
     *
     * <p>原来按 index 逐条 batch update；现在用 CASE 一次写完，
     * 「顺序 → priority」的换算在 SQL 里一眼可见。
     */
    int reorderExperimentPriorities(long tenantId, @Param("ids") List<Long> ids);

    /** 批量取实验状态（调序前校验）。id IN 用 foreach，替换手写 ANY(?) + createArrayOf。 */
    List<Map<String, Object>> selectExperimentStatuses(long tenantId, List<Long> ids);

    /** 新建实验，返回 id。 */
    Long insertExperiment(long tenantId, String experimentNo, String name,
                          Integer priority, Long islandId);


    /** 这批 workflow id 里哪些真的存在（编排校验用）。 */
    List<Long> selectExistingWorkflowIds(long tenantId, List<Long> ids);


    int bumpExperimentVersion(long tenantId, long experimentId);

}
