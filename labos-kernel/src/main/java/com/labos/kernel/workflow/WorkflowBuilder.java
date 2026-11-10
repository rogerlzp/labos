package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 把「搬运序列」落成一条正式流程。
 *
 * <p>输入是编排好的步骤（设备 + 方法），输出是一条可以直接执行的 workflow：
 * 每站一个节点，节点上写死设备方法与能力，边按顺序连。
 *
 * <p>为什么写死设备：搬运序列的语义就是"这块板子要经过这几台具体设备"，
 * 不像普通流程那样按能力随便挑。所以节点的 {@code params.resource_id}
 * 存了指定设备，执行时会优先用它。
 */
public final class WorkflowBuilder {

    /** 一步：资源树 id + 方法。 */
    public record Step(long resourceId, String method) {
    }

    private final DbConfig db;
    private final long tenantId;

    public WorkflowBuilder(DbConfig db, long tenantId) {
        this.db = db;
        this.tenantId = tenantId;
    }

    private static com.labos.kernel.persistence.GraphMapper gm() {
        return com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.GraphMapper.class);
    }

    /**
     * 建流程：workflow + 节点 + 边 + graph XML，返回新 workflow id。
     *
     * <p>整个过程在一个事务里，任何一步失败都回滚——不留半条流程。
     */
    public long build(String name, List<Step> steps) throws SQLException {
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("至少要有一步");
        }
        if (steps.size() > 50) {
            throw new IllegalArgumentException("一条流程最多 50 步");
        }

        return com.labos.kernel.persistence.Tx.inTransaction(db, c -> {
            long wfId = insertWorkflow(c, name);

            // 起止事件 + 每步一个 task
            long startId = insertNode(c, wfId, "S", "开始", "start", null, null, null);
            long endId = insertNode(c, wfId, "E", "结束", "end", null, null, null);

            List<String> keys = new ArrayList<>();
            int seq = 0;
            for (Step st : steps) {
                seq++;
                String key = "N" + seq;
                String cap = capabilityOf(c, st.resourceId());
                String devName = resourceName(c, st.resourceId());
                String nodeName = devName == null ? key : devName + " / " + st.method();
                insertNode(c, wfId, key, nodeName, "task", cap, st, st.resourceId());
                keys.add(key);
            }

            // 边：S -> N1 -> N2 -> ... -> E
            String prev = "S";
            for (String k : keys) {
                insertEdge(c, wfId, prev, k);
                prev = k;
            }
            insertEdge(c, wfId, prev, "E");

            // graph XML
            String xml = buildXml(name, keys, steps, startId, endId);
            gm().writeWorkflowGraphXml(tenantId, wfId, xml);

            return wfId;
        });
    }

    private long insertWorkflow(Connection c, String name) throws SQLException {
        // c 形参保留（Tx 事务内调用，mapper 复用绑定连接）
        String code = "AUTO-" + System.currentTimeMillis() % 100000;
        return gm().insertWorkflowRow(tenantId, code,
                name == null || name.isBlank() ? "搬运流程 " + code : name);
    }

    private long insertNode(Connection c, long wfId, String key, String name, String type,
                            String capability, Step step, Long resourceId) throws SQLException {
        StringBuilder json = new StringBuilder("{");
        if (step != null) {
            json.append("\"protocol_name\":\"").append(step.method()).append("\"");
            json.append(",\"bound_resource_id\":").append(step.resourceId());
        }
        json.append("}");
        return gm().insertWorkflowNodeRow(tenantId, wfId, key, name, type,
                capability == null ? "LOGIC" : capability, json.toString());
    }

    private void insertEdge(Connection c, long wfId, String from, String to) throws SQLException {
        gm().insertWorkflowEdgeRow(tenantId, wfId, from, to);
    }

    private String capabilityOf(Connection c, long resourceId) throws SQLException {
        return gm().selectCapabilityCodeOfResource(resourceId);
    }

    private String resourceName(Connection c, long resourceId) throws SQLException {
        return com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.QueryMapper.class)
                .selectResourceName(resourceId);
    }

    /** 生成带 bpmn: 前缀的 XML（bpmn-js 与 BpmnGraphParser 都认）。 */
    private String buildXml(String name, List<String> keys, List<Step> steps,
                            long startId, long endId) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
          .append("<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\"\n")
          .append("  xmlns:bpmndi=\"http://www.omg.org/spec/BPMN/20100524/DI\"\n")
          .append("  xmlns:dc=\"http://www.omg.org/spec/DD/20100524/DC\"\n")
          .append("  xmlns:di=\"http://www.omg.org/spec/DD/20100524/DI\"\n")
          .append("  id=\"Def_auto\" targetNamespace=\"http://bpmn.io/schema/bpmn\">\n")
          .append("  <bpmn:process id=\"Proc_auto\" isExecutable=\"false\">\n")
          .append("    <bpmn:startEvent id=\"S\" name=\"开始\" />\n");

        for (int i = 0; i < keys.size(); i++) {
            sb.append("    <bpmn:task id=\"").append(keys.get(i))
              .append("\" name=\"").append(escape(steps.get(i).method()))
              .append("\" protocolName=\"").append(escape(steps.get(i).method()))
              .append("\" resource=\"").append(steps.get(i).resourceId()).append("\" />\n");
        }
        sb.append("    <bpmn:endEvent id=\"E\" name=\"结束\" />\n");

        String prev = "S";
        for (String k : keys) {
            sb.append("    <bpmn:sequenceFlow id=\"F_").append(prev).append("_").append(k)
              .append("\" sourceRef=\"").append(prev).append("\" targetRef=\"")
              .append(k).append("\" />\n");
            prev = k;
        }
        sb.append("    <bpmn:sequenceFlow id=\"F_").append(prev).append("_E")
          .append("\" sourceRef=\"").append(prev).append("\" targetRef=\"E\" />\n")
          .append("  </bpmn:process>\n")
          .append("  <bpmndi:BPMNDiagram id=\"Diag_auto\">\n")
          .append("    <bpmndi:BPMNPlane id=\"Plane_auto\" bpmnElement=\"Proc_auto\">\n")
          .append("      <bpmndi:BPMNShape id=\"S_di\" bpmnElement=\"S\">")
          .append("<dc:Bounds x=\"80\" y=\"120\" width=\"36\" height=\"36\" /></bpmndi:BPMNShape>\n");

        int x = 180;
        java.util.Map<String, Integer> xOf = new java.util.LinkedHashMap<>();
        for (String k : keys) {
            xOf.put(k, x);
            sb.append("      <bpmndi:BPMNShape id=\"").append(k).append("_di\" bpmnElement=\"")
              .append(k).append("\"><dc:Bounds x=\"").append(x)
              .append("\" y=\"98\" width=\"100\" height=\"80\" /></bpmndi:BPMNShape>\n");
            x += 160;
        }
        sb.append("      <bpmndi:BPMNShape id=\"E_di\" bpmnElement=\"E\">")
          .append("<dc:Bounds x=\"").append(x).append("\" y=\"120\" width=\"36\" height=\"36\" />")
          .append("</bpmndi:BPMNShape>\n");

        // **连线（BPMNEdge）**：只写 <bpmn:sequenceFlow> 不够，bpmn-js 只认 DI 里的
        // waypoint —— 没有它，图上一排方块彼此不相连，看不出先后顺序。
        // 坐标按上面的布局算：所有图形垂直中心 138，任务宽 100、间距 160。
        int cy = 138;
        String from = "S";
        int fromRight = 116;
        for (String k : keys) {
            int kx = xOf.get(k);
            sb.append(edgeDi("F_" + from + "_" + k, fromRight, cy, kx, cy));
            from = k;
            fromRight = kx + 100;
        }
        sb.append(edgeDi("F_" + from + "_E", fromRight, cy, x, cy));

        sb.append("    </bpmndi:BPMNPlane>\n")
          .append("  </bpmndi:BPMNDiagram>\n")
          .append("</bpmn:definitions>\n");
        return sb.toString();
    }

    /**
     * 一条连线在 DI 里的表示。
     *
     * <p>只写 {@code <bpmn:sequenceFlow>} 是不够的：bpmn-js（以及任何合规的 BPMN 渲染器）
     * 只按 DI 里的 {@code waypoint} 画线。少了这段，图上一排方块彼此不相连，
     * **看不出先后顺序** —— 2026-11-03 用户在页面上指出来的就是这个。
     */
    private static String edgeDi(String flowId, int x1, int y1, int x2, int y2) {
        return "      <bpmndi:BPMNEdge id=\"" + flowId + "_di\" bpmnElement=\"" + flowId + "\">"
                + "<di:waypoint x=\"" + x1 + "\" y=\"" + y1 + "\" />"
                + "<di:waypoint x=\"" + x2 + "\" y=\"" + y2 + "\" />"
                + "</bpmndi:BPMNEdge>\n";
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
