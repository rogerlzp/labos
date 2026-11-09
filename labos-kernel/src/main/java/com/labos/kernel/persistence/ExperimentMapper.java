package com.labos.kernel.persistence;

import java.util.List;

/** 实验域的清场/校验类数据访问，语句见 {@code mapper/ExperimentMapper.xml}。 */
public interface ExperimentMapper {

    /** 实验是否存在（handoffs / workcells 两个写入口共用）。 */
    boolean experimentExists(long tenantId, long experimentId);

    /** 实验绑定的 workcell（handoff 校验起点/终点是否在绑定内）。 */
    List<Long> selectExperimentWorkcellIds(long tenantId, long experimentId);

    int deleteExperimentHandoffs(long tenantId, long experimentId);

    int deleteExperimentWorkcells(long tenantId, long experimentId);

    int deleteExperimentVariables(long tenantId, long experimentId);

    int deleteExperimentProcesses(long tenantId, long experimentId);
}
