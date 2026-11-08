package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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

    private ExperimentWorkcellScope() {
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
        try (Connection c = db.open()) {
            // ① 显式绑定优先（N-10 / ADR-ISLAND-002）
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT workcell_id FROM experiment_workcell "
                            + "WHERE tenant_id = ? AND experiment_id = ? ORDER BY workcell_id")) {
                ps.setLong(1, tenantId);
                ps.setLong(2, experimentId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long id = rs.getLong(1);
                        if (!rs.wasNull() && id > 0) {
                            islands.add(id);
                        }
                    }
                }
            }
            if (!islands.isEmpty()) {
                return islands;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT island_id FROM experiment WHERE tenant_id = ? AND id = ?")) {
                ps.setLong(1, tenantId);
                ps.setLong(2, experimentId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        long island = rs.getLong(1);
                        if (!rs.wasNull() && island > 0) {
                            islands.add(island);
                        }
                    }
                }
            }
            String sql = """
                    WITH RECURSIVE wf AS (
                        SELECT ep.workflow_id
                          FROM experiment_process ep
                         WHERE ep.tenant_id = ? AND ep.experiment_id = ?
                    ), res AS (
                        SELECT DISTINCT wn.resource_id AS id
                          FROM workflow_node wn
                          JOIN wf ON wf.workflow_id = wn.workflow_id
                         WHERE wn.resource_id IS NOT NULL
                    ), up AS (
                        SELECT rn.id, rn.parent_id, rn.kind::text AS kind
                          FROM resource_node rn
                          JOIN res ON res.id = rn.id
                        UNION ALL
                        SELECT p.id, p.parent_id, p.kind::text
                          FROM resource_node p
                          JOIN up ON p.id = up.parent_id
                    )
                    SELECT DISTINCT id FROM up WHERE kind = 'island'
                    """;
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, tenantId);
                ps.setLong(2, experimentId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long id = rs.getLong(1);
                        if (!rs.wasNull() && id > 0) {
                            islands.add(id);
                        }
                    }
                }
            }
        }
        return islands;
    }
}
