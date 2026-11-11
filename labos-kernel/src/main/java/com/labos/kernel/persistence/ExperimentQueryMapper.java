package com.labos.kernel.persistence;

import java.util.List;
import java.util.Map;

/**
 * 实验查询域的数据访问（{@code ExperimentQueryService} 专用，语句见
 * {@code mapper/ExperimentQueryMapper.xml}）。
 *
 * <p>原先这些 SQL 散在 {@code QueryService} 里、映射挂在 {@code QueryMapper}
 * 上；按「一个 Controller 域 → 一个 Service → 一个 Mapper」从
 * {@code ExperimentQueryController} 一侧归位到这里。
 */
public interface ExperimentQueryMapper {


    /** 实验编排图；不存在返回 null。 */
    String selectExperimentGraph(long tenantId, long experimentId);

    /** 交接记录（status 为 null 时不过滤）。 */
    List<Map<String, Object>> selectHandoffs(long tenantId, String status, int limit);

    /** 实验的工位交接清单。 */
    List<Map<String, Object>> selectExperimentHandoffs(long tenantId, long experimentId);

    /** 实验绑定的工位（含名称）。 */
    List<Map<String, Object>> selectExperimentWorkcells(long tenantId, long experimentId);

    /** 实验编排计划（每条流程 × loop_count）。 */
    List<Map<String, Object>> selectExperimentProcessPlan(long tenantId, long experimentId);

    /** 实验执行的实际步骤行。 */
    List<Map<String, Object>> selectExperimentRunStepRows(long tenantId, long runId);

    /** 实验执行头（experiment_id / status / expanded_steps）；不存在返回 null。 */
    Map<String, Object> selectExperimentRunHeader(long tenantId, long runId);

    /** 某实验的执行记录（最近 20 条）。 */
    List<Map<String, Object>> selectExperimentRuns(long tenantId, long experimentId);

    /** 实验变量列表。 */
    List<Map<String, Object>> selectExperimentVariables(long tenantId, long experimentId);

    /**
     * 这个实验**用到的**流程级变量（编排里每条流程声明的变量，同名合并）。
     *
     * <p>为什么要单列：实验页以前只显示 {@code experiment_variable}（实验自己写的），
     * 于是流程里声明的变量在实验页上完全看不到，也没法在那里改值。</p>
     */
    List<Map<String, Object>> selectExperimentWorkflowVariables(long tenantId, long experimentId);

    /** 实验编排的流程列表（含流程名/码与任务节点数）。 */
    List<Map<String, Object>> selectExperimentProcesses(long tenantId, long experimentId);

    /** 实验列表页（岛、流程、绑定工位名、流程数，一次查全）。 */
    List<Map<String, Object>> selectExperiments(long tenantId, int limit);

}
