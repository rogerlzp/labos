package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.workflow.RealWorkflowRunner.CapabilitySource;
import com.labos.kernel.workflow.RealWorkflowRunner.NodeDef;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点编译器：把一个业务节点展开成若干子步骤。
 *
 * <p>三层模型的中间一层：
 * <pre>
 *   workflow_node（READ_PLATE，可折叠）
 *     └ step 1..N（每步 1:1 对应一条设备命令）
 * </pre>
 *
 * <p>模板来自 {@code capability_recipe}（从 11 万条历史命令挖掘），
 * 没有模板的能力退化为单步（用节点的 protocol_name）。
 */
public final class RecipeCompiler {

    /** 一个子步骤。 */
    public record Step(int seq, String method) {
    }

    /** 一个节点的编译结果。 */
    public record NodePlan(
            long nodeId,
            String nodeKey,
            String name,
            String capability,
            String capabilitySource,
            List<Step> steps,
            int sampleCount,
            boolean fromRecipe) {

        public int stepCount() {
            return steps.size();
        }
    }

    private final Map<String, List<String>> recipes;
    private final Map<String, Integer> samples;

    private RecipeCompiler(Map<String, List<String>> recipes, Map<String, Integer> samples) {
        this.recipes = recipes;
        this.samples = samples;
    }

    /** 从数据库加载模板。 */
    public static RecipeCompiler load(DbConfig db, long tenantId) throws SQLException {
        Map<String, List<String>> recipes = new LinkedHashMap<>();
        Map<String, Integer> samples = new LinkedHashMap<>();

        String sql = """
                SELECT c.code, r.seq, r.method, r.sample_count
                FROM capability_recipe r
                JOIN capability c ON c.id = r.capability_id
                WHERE r.tenant_id = ?
                ORDER BY c.code, r.seq
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String cap = rs.getString(1);
                    recipes.computeIfAbsent(cap, k -> new ArrayList<>()).add(rs.getString(3));
                    samples.merge(cap, rs.getInt(4), Math::max);
                }
            }
        }
        return new RecipeCompiler(recipes, samples);
    }

    /** 编译单个节点。 */
    public NodePlan compile(NodeDef node, Map<String, String> protocolCap) {
        return compile(node, protocolCap, Map.of());
    }

    /** 编译单个节点（带设备能力表：绑定设备的能力优先）。 */
    public NodePlan compile(NodeDef node, Map<String, String> protocolCap,
                            Map<Long, String> capabilityByResource) {
        CapabilitySource cs = RealWorkflowRunner.resolveCapability(
                node, protocolCap, capabilityByResource);
        String cap = cs.capability();

        // ★ 已指定具体方法 → 单步（存量流程 已落库的节点都是这种，不能再展开）
        if (node.protocolName() != null && !node.protocolName().isBlank()) {
            return new NodePlan(node.id(), node.key(), node.name(), cap, cs.source(),
                    List.of(new Step(1, node.protocolName())), 0, false);
        }

        // 逻辑节点不展开
        if (RealWorkflowRunner.LOGIC.equals(cap)) {
            return new NodePlan(node.id(), node.key(), node.name(), cap, cs.source(),
                    List.of(new Step(1, cap)), 0, false);
        }

        // 未指定方法（新建节点）→ 按能力模板展开
        List<String> methods = recipes.get(cap);
        if (methods == null || methods.isEmpty()) {
            // 无模板：单步，用协议名（缺失则能力码兜底）
            String m = (node.protocolName() == null || node.protocolName().isBlank())
                    ? cap : node.protocolName();
            return new NodePlan(node.id(), node.key(), node.name(), cap, cs.source(),
                    List.of(new Step(1, m)), 0, false);
        }

        List<Step> steps = new ArrayList<>(methods.size());
        for (int i = 0; i < methods.size(); i++) {
            steps.add(new Step(i + 1, methods.get(i)));
        }
        return new NodePlan(node.id(), node.key(), node.name(), cap, cs.source(),
                steps, samples.getOrDefault(cap, 0), true);
    }

    /** 编译整批节点。 */
    public List<NodePlan> compileAll(List<NodeDef> nodes, Map<String, String> protocolCap) {
        return compileAll(nodes, protocolCap, Map.of());
    }

    /** 编译整批节点（带设备能力表）。 */
    public List<NodePlan> compileAll(List<NodeDef> nodes, Map<String, String> protocolCap,
                                     Map<Long, String> capabilityByResource) {
        List<NodePlan> out = new ArrayList<>(nodes.size());
        for (NodeDef n : nodes) {
            if (!"task".equals(n.type())) {
                continue;
            }
            out.add(compile(n, protocolCap));
        }
        return out;
    }

    public Map<String, List<String>> allRecipes() {
        return Map.copyOf(recipes);
    }
}
