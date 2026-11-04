package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 给已有流程的 BPMN 图**补连线（BPMNEdge）**。
 *
 * <p>背景：{@code WorkflowBuilder} 生成图时只写了 {@code <bpmn:sequenceFlow>}，
 * 没写 DI 里的 {@code BPMNEdge}（waypoint）。bpmn-js 只按 DI 画线，
 * 于是图上是"一排不相连的方块"，看不出先后顺序 —— 2026-11-03 用户指出。
 *
 * <p>新流程已经修好（{@link WorkflowBuilder#build} 会写 edge）；这个工具负责**存量数据**：
 * 解析每个图形的位置，按"源图右边缘中点 → 目标图左边缘中点"补一条两点折线。
 *
 * <p>幂等：已经有 {@code BPMNEdge} 的流程直接跳过。
 *
 * <pre>
 * java -cp out:lib/postgresql-42.7.3.jar com.labos.kernel.workflow.GraphDiRepair [--dry-run]
 * </pre>
 */
public final class GraphDiRepair {

    private static final Pattern SHAPE = Pattern.compile(
            "<bpmndi:BPMNShape id=\"([^\"]+)\" bpmnElement=\"([^\"]+)\">\\s*"
                    + "<dc:Bounds x=\"([0-9.]+)\" y=\"([0-9.]+)\" "
                    + "width=\"([0-9.]+)\" height=\"([0-9.]+)\"");
    private static final Pattern FLOW = Pattern.compile(
            "<bpmn:sequenceFlow id=\"([^\"]+)\" sourceRef=\"([^\"]+)\" targetRef=\"([^\"]+)\"");

    public static void main(String[] args) throws Exception {
        boolean dry = args.length > 0 && "--dry-run".equals(args[0]);
        DbConfig db = DbConfig.fromEnv();
        long tenant = 1L;

        int scanned = 0;
        int fixed = 0;
        int skipped = 0;

        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, code, graph->>'xml' AS xml FROM workflow "
                             + "WHERE tenant_id = ? AND deleted_at IS NULL AND graph->>'xml' IS NOT NULL")) {
            ps.setLong(1, tenant);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    scanned++;
                    long id = rs.getLong("id");
                    String code = rs.getString("code");
                    String xml = rs.getString("xml");
                    String patched = repair(xml);
                    if (patched == null) {
                        skipped++;
                        continue;
                    }
                    fixed++;
                    System.out.printf("%s #%d %s：补 %d 条连线%s%n", dry ? "[dry]" : "✓",
                            id, code, count(patched), dry ? "（未写库）" : "");
                    if (!dry) {
                        try (PreparedStatement up = c.prepareStatement(
                                "UPDATE workflow SET graph = jsonb_set(graph, '{xml}', to_jsonb(?::text)), "
                                        + "updated_at = now() WHERE tenant_id = ? AND id = ?")) {
                            up.setString(1, patched);
                            up.setLong(2, tenant);
                            up.setLong(3, id);
                            up.executeUpdate();
                        }
                    }
                }
            }
        }
        System.out.printf("%n扫描 %d 个流程：修补 %d，跳过 %d（已有连线或无图）%n",
                scanned, fixed, skipped);
    }

    private static int count(String xml) {
        int n = 0;
        int i = -1;
        while ((i = xml.indexOf("<bpmndi:BPMNEdge", i + 1)) >= 0) {
            n++;
        }
        return n;
    }

    /**
     * @return 修补后的 XML；不需要改就返回 {@code null}
     */
    static String repair(String xml) {
        if (xml == null || xml.isBlank() || xml.contains("<bpmndi:BPMNEdge")) {
            return null;
        }
        Map<String, double[]> bounds = new LinkedHashMap<>();
        Matcher sh = SHAPE.matcher(xml);
        while (sh.find()) {
            bounds.put(sh.group(2), new double[]{
                    Double.parseDouble(sh.group(3)), Double.parseDouble(sh.group(4)),
                    Double.parseDouble(sh.group(5)), Double.parseDouble(sh.group(6))});
        }
        if (bounds.isEmpty()) {
            return null;
        }
        StringBuilder edges = new StringBuilder();
        Matcher fl = FLOW.matcher(xml);
        while (fl.find()) {
            String flowId = fl.group(1);
            double[] s = bounds.get(fl.group(2));
            double[] t = bounds.get(fl.group(3));
            if (s == null || t == null) {
                continue;
            }
            // 源图右边缘中点 → 目标图左边缘中点（横向流水布局）
            double x1 = s[0] + s[2];
            double y1 = s[1] + s[3] / 2;
            double x2 = t[0];
            double y2 = t[1] + t[3] / 2;
            edges.append("      <bpmndi:BPMNEdge id=\"").append(flowId).append("_di\" bpmnElement=\"")
                    .append(flowId).append("\">")
                    .append("<di:waypoint x=\"").append(fmt(x1)).append("\" y=\"").append(fmt(y1)).append("\" />")
                    .append("<di:waypoint x=\"").append(fmt(x2)).append("\" y=\"").append(fmt(y2)).append("\" />")
                    .append("</bpmndi:BPMNEdge>\n");
        }
        if (edges.length() == 0) {
            return null;
        }
        int at = xml.indexOf("</bpmndi:BPMNPlane>");
        if (at < 0) {
            return null;
        }
        return xml.substring(0, at) + edges + xml.substring(at);
    }

    private static String fmt(double v) {
        return String.valueOf(Math.round(v));
    }

    private GraphDiRepair() {
    }
}
