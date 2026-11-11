package com.labos.kernel.workflow;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 解析执行用的流程图（含网关），与保存用的 {@link BpmnGraphParser} 分开。
 *
 * <p>{@code BpmnGraphParser} 只认 task（保存时用），这里要识别**控制流**：
 * <ul>
 *   <li>{@code parallelGateway}：分叉与汇合</li>
 *   <li>{@code exclusiveGateway}：二选一（异或）</li>
 *   <li>{@code inclusiveGateway}：条件网关（包容）—— 条件成立的几条分支**都走**，
 *       汇合只等实际会到达的分支</li>
 *   <li>{@code sequenceFlow} 的 {@code conditionExpression} 与网关的 {@code default}</li>
 * </ul>
 *
 * <p>存量流程里有 parallelGateway 和 exclusiveGateway，
 * 但改动前的执行器只按 sequenceFlow 顺序推进，网关被完全忽略。
 *
 * <p><b>2026-11-11</b>：补上 {@code inclusiveGateway}。此前它在**保存侧**
 * （{@link BpmnGraphParser} 的 TYPE_MAP）有映射、在**执行侧**没有 ⇒
 * 拖到画布上的条件网关被静默跳过，整段分支不执行且不报错
 * （评审记录 20261102-03 已记过这条「inclusiveGateway 不识别致断流」）。
 */
public final class WorkflowGraphParser {

    /** 节点类型：决定推进语义。 */
    public enum Kind {
        TASK, START, END, SPLIT_PARALLEL, JOIN_PARALLEL, SPLIT_XOR, JOIN_XOR,

        /**
         * <b>条件网关（包容网关，{@code bpmn:InclusiveGateway}）</b>：分叉时
         * **所有条件成立的分支都走**（可能 0 条、1 条、多条），汇合时
         * **只等实际会到达的分支**。
         *
         * <p>为什么单独一种而不用异或顶上：异或是「第一条成立就赢」，
         * 多条同时成立时后面的条件根本不会被求值 ⇒ 表达不了
         * 「合格就同时送去分析和存档」这种**多路并行**的编排。
         *
         * <p>与并行网关的区别：并行网关**无条件全走**；条件网关走哪几条
         * 由每条出边上的条件表达式决定。
         */
        SPLIT_CONDITIONAL, JOIN_CONDITIONAL,

        /**
         * **内置节点**：不驱动设备，引擎自己算。
         *
         * <p>用 {@code protocolName} 以 {@code __} 开头标记，例如
         * {@code __setVar}（把值写进运行时变量）、{@code __expr}（算一个表达式存进变量）。
         * 这样不用给 BPMN 加自定义元素类型，bpmn-js 也能正常保存/打开。
         */
        BUILTIN
    }

    public record GNode(
            String key,
            String name,
            String rawType,
            Kind kind,
            String capability,
            String method,
            Long resourceId) {
    }

    public record GEdge(
            String id,
            String from,
            String to,
            String condition,
            boolean isDefault) {
    }

    public record Graph(
            Map<String, GNode> nodes,
            List<GEdge> edges,
            Map<String, List<GEdge>> outgoing,
            Map<String, List<GEdge>> incoming) {

        public List<GEdge> out(String key) {
            return outgoing.getOrDefault(key, List.of());
        }

        public List<GEdge> in(String key) {
            return incoming.getOrDefault(key, List.of());
        }
    }

    private static final String NS_BPMN = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    public static Graph parse(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new IllegalArgumentException("XML 为空");
        }
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            DocumentBuilder b = f.newDocumentBuilder();
            Document doc = b.parse(new InputSource(new StringReader(xml)));

            // 1) 先收边（含条件与 default 标记）
            List<GEdge> edges = new ArrayList<>();
            Set<String> defaultFlowIds = new LinkedHashSet<>();
            collectDefaults(doc, defaultFlowIds);

            NodeList flows = doc.getElementsByTagNameNS(NS_BPMN, "sequenceFlow");
            for (int i = 0; i < flows.getLength(); i++) {
                Element el = (Element) flows.item(i);
                String id = el.getAttribute("id");
                String from = el.getAttribute("sourceRef");
                String to = el.getAttribute("targetRef");
                if (from.isBlank() || to.isBlank()) {
                    continue;
                }
                String cond = null;
                NodeList kids = el.getChildNodes();
                for (int k = 0; k < kids.getLength(); k++) {
                    Node kn = kids.item(k);
                    if (kn.getNodeType() == Node.ELEMENT_NODE
                            && "conditionExpression".equals(kn.getLocalName())) {
                        cond = kn.getTextContent() == null ? null : kn.getTextContent().trim();
                    }
                }
                edges.add(new GEdge(id, from, to,
                        cond == null || cond.isBlank() ? null : cond,
                        defaultFlowIds.contains(id)));
            }

            // 2) 索引入/出边
            Map<String, List<GEdge>> out = new LinkedHashMap<>();
            Map<String, List<GEdge>> in = new LinkedHashMap<>();
            for (GEdge e : edges) {
                out.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e);
                in.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(e);
            }

            // 3) 收节点：task / start / end / 两种网关
            Map<String, GNode> nodes = new LinkedHashMap<>();
            addTasks(doc, nodes, in, out);
            addEvents(doc, nodes);
            addGateways(doc, nodes, in, out);

            return new Graph(nodes, edges, out, in);

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("流程图解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 任务节点。
     *
     * <p>存量流程用的是自定义元素 {@code <customDevice protocolName="..." resource="..."/>}，
     * 不是标准 {@code <bpmn:task>}；bpmn-js 保存后又是标准 task。
     * 两种都要认，否则 存量流程会解析出 0 个可执行节点。
     */
    private static void addTasks(Document doc, Map<String, GNode> nodes,
                                 Map<String, List<GEdge>> in,
                                 Map<String, List<GEdge>> out) {
        NodeList all = doc.getElementsByTagNameNS(NS_BPMN, "*");
        for (int i = 0; i < all.getLength(); i++) {
            Element el = (Element) all.item(i);
            String ln = el.getLocalName();
            if (ln == null || !isTaskLike(ln)) {
                continue;
            }
            String id = el.getAttribute("id");
            // 只跳过「多步节点投影」产生的步骤节点（id 带 __s）。
            // 存量流程的 subProcess 是真业务分组，里面的节点仍要执行。
            if (id.isBlank() || id.contains(BpmnExpander.STEP_INFIX)
                    || nodes.containsKey(id)) {
                continue;
            }
            String name = firstNonBlank(el.getAttribute("name"),
                    el.getAttribute("protocolName"), id);
            String proto = blankToNull(el.getAttribute("protocolName"));
            // __ 开头 = 内置节点（变量/表达式），不找设备、不走驱动
            Kind kind = isBuiltinProtocol(proto) ? Kind.BUILTIN : Kind.TASK;
            nodes.put(id, new GNode(id, name, ln, kind,
                    null,                     // 能力由外部按 node_key 补（要查库）
                    proto,
                    parseLong(el.getAttribute("resource"))));
        }
    }

    /**
     * `protocolName` 以 `__` 开头 = 内置逻辑节点（不找设备、不走驱动）。
     *
     * <p>这是内置节点的**单一判定源**：解析器用它定 {@link Kind#BUILTIN}，
     * 能力解析（{@code RealWorkflowRunner.resolveCapability}）也用它 —— 那边若只看
     * 节点显示名，中文名（「延时」「等待变量」）匹配不上关键词，内置节点会被解析成
     * `UNKNOWN` 能力、候选设备为空。
     */
    public static boolean isBuiltinProtocol(String protocolName) {
        return protocolName != null && protocolName.startsWith("__");
    }

    /**
     * 是否算任务节点。
     *
     * <p>存量流程里常见 {@code custom} 前缀元素（如 {@code customDevice}），
     * 以及 bpmn-js 保存后的标准 task。
     * 统一按「custom 前缀 + 标准 task 系」判定。
     */
    private static boolean isTaskLike(String localName) {
        if (localName.startsWith("custom")) {
            return true;
        }
        return switch (localName) {
            case "task", "userTask", "serviceTask", "scriptTask", "sendTask", "receiveTask" -> true;
            default -> false;
        };
    }

    private static void addEvents(Document doc, Map<String, GNode> nodes) {
        for (String[] pair : new String[][]{{"startEvent", "start"}, {"endEvent", "end"}}) {
            NodeList list = doc.getElementsByTagNameNS(NS_BPMN, pair[0]);
            for (int i = 0; i < list.getLength(); i++) {
                Element el = (Element) list.item(i);
                String id = el.getAttribute("id");
                if (id.isBlank()) {
                    continue;
                }
                Kind k = "start".equals(pair[1]) ? Kind.START : Kind.END;
                nodes.put(id, new GNode(id, firstNonBlank(el.getAttribute("name"), id),
                        pair[1], k, null, null, null));
            }
        }
    }

    private static void addGateways(Document doc, Map<String, GNode> nodes,
                                    Map<String, List<GEdge>> in,
                                    Map<String, List<GEdge>> out) {
        addGatewayKind(doc, nodes, in, out, "parallelGateway", Kind.SPLIT_PARALLEL, Kind.JOIN_PARALLEL);
        addGatewayKind(doc, nodes, in, out, "exclusiveGateway", Kind.SPLIT_XOR, Kind.JOIN_XOR);
        // 条件网关（包容网关）：保存侧早就映射了 inclusiveGateway，
        // 执行侧此前**不认** ⇒ 拖到画布上的 ○ 会被静默跳过（node == null → continue），
        // 后面整段不执行且不报错。2026-11-11 补上。
        addGatewayKind(doc, nodes, in, out, "inclusiveGateway", Kind.SPLIT_CONDITIONAL, Kind.JOIN_CONDITIONAL);
    }

    private static void addGatewayKind(Document doc, Map<String, GNode> nodes,
                                       Map<String, List<GEdge>> in,
                                       Map<String, List<GEdge>> out,
                                       String tag, Kind split, Kind join) {
        NodeList list = doc.getElementsByTagNameNS(NS_BPMN, tag);
        for (int i = 0; i < list.getLength(); i++) {
            Element el = (Element) list.item(i);
            String id = el.getAttribute("id");
            if (id.isBlank()) {
                continue;
            }
            int ins = in.getOrDefault(id, List.of()).size();
            int outs = out.getOrDefault(id, List.of()).size();

            // 判定分叉还是汇合：出边多=分叉，入边多=汇合；都多时按汇合处理（先等再分）
            Kind kind = ins > 1 ? join : split;
            if (ins <= 1 && outs <= 1) {
                kind = split;   // 单进单出，等价于直通
            }
            nodes.put(id, new GNode(id, firstNonBlank(el.getAttribute("name"), tag),
                    tag, kind, null, null, null));
        }
    }

    private static void collectDefaults(Document doc, Set<String> out) {
        NodeList list = doc.getElementsByTagNameNS(NS_BPMN, "*");
        for (int i = 0; i < list.getLength(); i++) {
            Element el = (Element) list.item(i);
            String d = el.getAttribute("default");
            if (d != null && !d.isBlank()) {
                out.add(d);
            }
        }
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static Long parseLong(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private WorkflowGraphParser() {
    }
}
