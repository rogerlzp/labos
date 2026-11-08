package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 跨 Workcell 实验 Handoff 门禁（N-10 第一切片，ADR-ISLAND-002/004）。
 *
 * <p>规则：实验的 Workflow 链推导出 Workcell 序列后，每对相邻且不同的 Workcell
 * 必须配置 Handoff（experiment_handoff），状态不能是 UNKNOWN；缺失/UNKNOWN 阻断执行。
 *
 * <p>当前执行器是串行 Workflow 段；本门禁在实验开跑前一次性校验。运行时人工确认
 * （manual_window）仍属 N-08/L-01 后续。
 */
public final class HandoffGate {

    private HandoffGate() {
    }

    /** 纯函数：给定 Workcell 链 + 已配置对 + UNKNOWN 对，返回缺失/不可用的边。 */
    public static List<String> missing(List<Long> chain, Set<String> configured,
                                       Set<String> unknown) {
        List<String> missing = new ArrayList<>();
        for (int i = 0; i + 1 < chain.size(); i++) {
            long a = chain.get(i);
            long b = chain.get(i + 1);
            String pair = a + "->" + b;
            if (!configured.contains(pair)) {
                missing.add(pair + "（未配置）");
            } else if (unknown.contains(pair)) {
                missing.add(pair + "（运行时确认 UNKNOWN）");
            }
        }
        return missing;
    }

    /**
     * 校验一次实验执行的 Handoff 配置。
     *
     * @throws IllegalStateException 缺失/UNKNOWN（调用方 fail-closed，不让实验开跑）
     */
    public static void check(DbConfig db, long tenantId, long experimentId,
                             List<ExperimentExecutor.Step> steps) throws SQLException {
        List<Long> chain = workcellChain(db, tenantId, steps);
        if (chain.size() < 2) {
            return;   // 单 Workcell / 无法推导 → 不适用
        }
        Set<String> configured = new LinkedHashSet<>();
        Set<String> unknown = new LinkedHashSet<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT from_workcell_id, to_workcell_id, status, intervention_record_id,
                            manual_window_id
                       FROM experiment_handoff
                      WHERE tenant_id = ? AND experiment_id = ?
                     """)) {
            ps.setLong(1, tenantId);
            ps.setLong(2, experimentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String pair = rs.getLong(1) + "->" + rs.getLong(2);
                    // ADR-ISLAND-004 / N-08：配置≠可执行；confirmed 且有人工介入记录才放行
                    long rec = rs.getLong(4);
                    long win = rs.getLong(5);
                    boolean hasRecord = !rs.wasNull() && rec > 0;
                    boolean hasWindow = !rs.wasNull() && win > 0;
                    if ("confirmed".equalsIgnoreCase(rs.getString(3)) && hasRecord && hasWindow) {
                        configured.add(pair);
                    } else if ("unknown".equalsIgnoreCase(rs.getString(3))) {
                        unknown.add(pair);
                    }
                }
            }
        }
        List<String> missing = missing(chain, configured, unknown);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("跨 Workcell Handoff 未确认/缺失，禁止执行实验 #"
                    + experimentId + "：" + String.join("、", missing));
        }
    }

    /** 按步骤顺序推导 Workcell 链（连续重复去掉；推导不出 Workcell 的步骤跳过）。 */
    static List<Long> workcellChain(DbConfig db, long tenantId,
                                    List<ExperimentExecutor.Step> steps) throws SQLException {
        List<Long> chain = new ArrayList<>();
        for (ExperimentExecutor.Step st : steps) {
            Long wc = ExperimentWorkcellScope.workcellOfWorkflow(
                    db, tenantId, st.workflowId());
            if (wc == null) {
                continue;
            }
            if (chain.isEmpty() || !chain.get(chain.size() - 1).equals(wc)) {
                chain.add(wc);
            }
        }
        return chain;
    }
}
