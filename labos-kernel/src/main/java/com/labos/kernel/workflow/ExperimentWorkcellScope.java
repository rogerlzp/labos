package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 实验的 Workcell（= island）影响闭包（N-10 第一切片）。
 *
 * <p>ADR-FAULT-SCOPE-002：同一实验内任一所属 Workcell 故障/扣留 ⇒ 整个实验停。
 * 当前库没有 Experiment↔Workcell 绑定表，Workcell 归属只能从「实验引用的
 * workflow 节点资源 → resource_node 祖先链 → kind=island 根」推导；
 * 再兜底 {@code experiment.island_id}（单值兼容语义）。
 *
 * <p><b>边界</b>：跨 Workcell 实验的显式绑定/Handoff 模型仍未实现（ADR-ISLAND-002），
 * 本类只提供「执行/恢复/停止时按所有可推导 Workcell 判覆盖」的第一切片。
 */
public final class ExperimentWorkcellScope {

    /** 延迟取 Mapper 代理（new 出来的工具类，无构造注入；见 Mappers）。 */
    private static com.labos.kernel.persistence.ExperimentMapper mappers() {
        return com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.ExperimentMapper.class);
    }

    private ExperimentWorkcellScope() {
    }

    /**
     * 推导单个 Workflow 的权威 Workcell（island 根 id）；推导不出返回 null。
     *
     * <p>从 workflow_node.resource_id 沿 resource_node.parent_id 上溯到 kind=island。
     */
    public static Long workcellOfWorkflow(DbConfig db, long tenantId, long workflowId)
            throws SQLException {
        return mappers().selectWorkflowIslandId(workflowId);
    }

    /**
     * 推导实验涉及的全部 Workcell（island 根 id）。
     *
     * <p>来源：① experiment_process 引用的 workflow 的节点资源，沿 resource_node
     * parent_id 上溯到 kind=island；② 兜底 experiment.island_id。
     *
     * @return 可能为空集合（无绑定/无资源节点时），调用方按「只匹配 experiment/site 级」处理
     */
    public static Set<Long> islandsOf(DbConfig db, long tenantId, long experimentId)
            throws SQLException {
        Set<Long> islands = new LinkedHashSet<>();
        // ① 显式绑定优先（N-10 / ADR-ISLAND-002）
        for (Long id : mappers().selectExperimentWorkcellIds(tenantId, experimentId)) {
            if (id != null && id > 0) {
                islands.add(id);
            }
        }
        if (!islands.isEmpty()) {
            return islands;
        }
        {
            Long island = mappers().selectExperimentIslandId(tenantId, experimentId);
            if (island != null && island > 0) {
                islands.add(island);
            }
        }
        for (Long id : mappers().selectExperimentIslandClosure(tenantId, experimentId)) {
            if (id != null && id > 0) {
                islands.add(id);
            }
        }
        return islands;
    }
}
