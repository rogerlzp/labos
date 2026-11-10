package com.labos.kernel.persistence;

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

    /** 实验所属岛（未显式绑定 workcell 时的回落）。 */
    Long selectExperimentIslandId(long tenantId, long experimentId);

    int deleteExperimentHandoffs(long tenantId, long experimentId);

    int deleteExperimentWorkcells(long tenantId, long experimentId);

    int deleteExperimentVariables(long tenantId, long experimentId);

    int deleteExperimentProcesses(long tenantId, long experimentId);
}
