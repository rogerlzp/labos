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
 * <p>幂等：已经有 {@code BPMNEdge} 且坐标是真值的流程直接跳过。
 *
 * <p><b>为什么还要判"退化边"</b>：有一批流程（测试脚本
 * {@code scripts/verify-flow-matrix.py} 早年生成的那批）虽然写了 {@code BPMNEdge}，
 * 但 waypoint 一律是占位值 {@code (0,0)→(1,1)} —— 渲染出来是一条 1px 的线，
 * 肉眼就是"线全没了"。只判"有没有 BPMNEdge"修不了这种，得连坐标一起看
 * （2026-11-06 用户在设计器里指出）。
 *
 * <pre>
 * java -cp out:lib/postgresql-42.7.3.jar com.labos.kernel.workflow.GraphDiRepair [--dry-run]
 * </pre>
 */
public final class GraphDiRepair {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(GraphDiRepair.class);
    /** 兼容原有 System.out.printf 的格式化输出。 */
    private static void printf(String format, Object... args) {
        log.info(String.format(format, args));
    }

    private static final Pattern SHAPE = Pattern.compile(
            "<bpmndi:BPMNShape id=\"([^\"]+)\" bpmnElement=\"([^\"]+)\">\\s*"
                    + "<dc:Bounds x=\"([0-9.]+)\" y=\"([0-9.]+)\" "
                    + "width=\"([0-9.]+)\" height=\"([0-9.]+)\"");
    private static final Pattern FLOW = Pattern.compile(
            "<bpmn:sequenceFlow id=\"([^\"]+)\" sourceRef=\"([^\"]+)\" targetRef=\"([^\"]+)\"");
    private static final Pattern EDGE_BLOCK = Pattern.compile(
            "\\s*<bpmndi:BPMNEdge\\b.*?</bpmndi:BPMNEdge>", Pattern.DOTALL);
    private static final Pattern WAYPOINT = Pattern.compile(
            "<di:waypoint\\s+x=\"(-?[0-9.]+)\"\\s+y=\"(-?[0-9.]+)\"");

    /** 退化判定阈值（px）：一条线的所有 waypoint 全部落在这么小的框里 = 画不出线。 */
    private static final double DEGENERATE_SPAN = 2.0;

    public static void main(String[] args) throws Exception {
        boolean dry = args.length > 0 && "--dry-run".equals(args[0]);
        long tenant = 1L;

        int scanned = 0;
        int fixed = 0;
        int skipped = 0;

        for (java.util.Map<String, Object> row : mapper().selectRepairableWorkflowXmls(tenant)) {
            scanned++;
            long id = ((Number) row.get("id")).longValue();
            String code = String.valueOf(row.get("code"));
            String xml = (String) row.get("xml");
            String patched = repair(xml);
            if (patched == null) {
                skipped++;
                continue;
            }
            fixed++;
            printf("%s #%d %s：补 %d 条连线%s%n", dry ? "[dry]" : "✓",
                    id, code, count(patched), dry ? "（未写库）" : "");
            if (!dry) {
                mapper().updateWorkflowGraph(tenant, id, patched);
            }
        }
        printf("%n扫描 %d 个流程：修补 %d，跳过 %d（已有连线或无图）%n",
                scanned, fixed, skipped);
    }

    /** 延迟取 Mapper（本类是独立 CLI，见 Mappers）。 */
    private static com.labos.kernel.persistence.GraphMapper mapper() {
        return com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.GraphMapper.class);
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
        if (xml == null || xml.isBlank()) {
            return null;
        }
        boolean hasEdges = xml.contains("<bpmndi:BPMNEdge");
        if (hasEdges && !hasDegenerateEdge(xml)) {
            return null;               // 已有真坐标连线：不动（幂等）
        }
        // 占位边先整段摘掉再重算，否则会留下两份同 id 的 BPMNEdge
        String body = hasEdges ? EDGE_BLOCK.matcher(xml).replaceAll("") : xml;

        Map<String, double[]> bounds = new LinkedHashMap<>();
        Matcher sh = SHAPE.matcher(body);
        while (sh.find()) {
            bounds.put(sh.group(2), new double[]{
                    Double.parseDouble(sh.group(3)), Double.parseDouble(sh.group(4)),
                    Double.parseDouble(sh.group(5)), Double.parseDouble(sh.group(6))});
        }
        if (bounds.isEmpty()) {
            return null;
        }
        StringBuilder edges = new StringBuilder();
        Matcher fl = FLOW.matcher(body);
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
        int at = body.indexOf("</bpmndi:BPMNPlane>");
        if (at < 0) {
            return null;
        }
        return body.substring(0, at) + edges + body.substring(at);
    }

    /**
     * 是否存在"画不出来"的边：某条边的 waypoint 全部挤在 {@link #DEGENERATE_SPAN} 像素内。
     *
     * <p>只要有一条就算 —— 占位坐标是**生成器**的产物（一次生成就是整批），
     * 而设计器/内核写出来的边坐标都是真的；所以"有一条退化"就等于"这批是占位值"。
     */
    static boolean hasDegenerateEdge(String xml) {
        Matcher eb = EDGE_BLOCK.matcher(xml);
        while (eb.find()) {
            Matcher wp = WAYPOINT.matcher(eb.group());
            double minX = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double minY = Double.MAX_VALUE;
            double maxY = -Double.MAX_VALUE;
            int n = 0;
            while (wp.find()) {
                double x = Double.parseDouble(wp.group(1));
                double y = Double.parseDouble(wp.group(2));
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                n++;
            }
            if (n >= 2 && maxX - minX < DEGENERATE_SPAN && maxY - minY < DEGENERATE_SPAN) {
                return true;
            }
        }
        return false;
    }

    private static String fmt(double v) {
        return String.valueOf(Math.round(v));
    }

    private GraphDiRepair() {
    }
}
