package com.labos.kernel.workflow;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析 BPMN 2.0 XML，抽出节点与连线。
 *
 * <p>要同时应付两种格式：
 * <ul>
 *   <li><b>存量流程</b>的 {@code <customDevice protocolName="Deposit1" resource="155"/>}</li>
 *   <li><b>bpmn-js</b> 编辑后的标准 {@code <task name="Deposit1"/>}</li>
 * </ul>
 *
 * <p>安全：禁用 DOCTYPE，防 XXE。
 */
public final class BpmnGraphParser {

    public record ParsedNode(String key, String name, String type,
                             String protocolName, Long resourceId,
                             /** 设备方法参数（JSON 字符串），来自 BPMN 自定义属性 methodParams。 */
                             String methodParams) {

        /** 兼容无参构造。 */
        public ParsedNode(String key, String name, String type,
                          String protocolName, Long resourceId) {
            this(key, name, type, protocolName, resourceId, null);
        }
    }

    /**
     * 按 **XML 文档顺序** 给节点编号（从 1 开始）。
     *
     * <p>为什么不用 {@link ParsedGraph#nodes()} 的遍历顺序：那个顺序是解析器内部
     * 合并任务/事件后的 Map 顺序，**拿它当 seq 会在两次运行间漂移**
     * （回填一次、检查一次就对不上）。文档顺序才是"图上从左到右"的真实顺序。
     */
    public static Map<String, Integer> documentOrder(String xml, java.util.Collection<String> nodeKeys) {
        java.util.Set<String> wanted = new java.util.HashSet<>(nodeKeys);
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        if (xml == null) {
            return out;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<[A-Za-z0-9_]+:[A-Za-z0-9_]+\\b[^>]*?\\bid=\"([^\"]+)\"")
                .matcher(xml);
        int seq = 0;
        while (m.find()) {
            String id = m.group(1);
            // 只数真实节点：XML 里还有 Def_/Proc_/Diag_/Plane_ 这些定义级 id，
            // 全数进去会让 seq 从 3 或 5 开始，虽然顺序没错但读起来莫名其妙。
            if (wanted.contains(id) && !out.containsKey(id)) {
                out.put(id, ++seq);
            }
        }
        return out;
    }

    public record ParsedEdge(String from, String to) {
    }

    public record ParsedGraph(List<ParsedNode> nodes, List<ParsedEdge> edges) {
    }

    /** node_type 取值，与 workflow_node.node_type 保持一致。 */
    private static final Map<String, String> TYPE_MAP = Map.ofEntries(
            Map.entry("startEvent", "start"),
            Map.entry("endEvent", "end"),
            Map.entry("task", "task"),
            Map.entry("userTask", "task"),
            Map.entry("serviceTask", "task"),
            Map.entry("scriptTask", "task"),
            Map.entry("customDevice", "task"),
            Map.entry("exclusiveGateway", "gateway"),
            Map.entry("parallelGateway", "gateway_parallel"),
            // 条件网关（包容）：**不能**再映射成 gateway_parallel ——
            // 那样它在 workflow_node.node_type 里和并行网关完全一样，
            // 两者的执行语义不同（条件网关看边上的条件），分不开。
            // 前缀仍是 "gateway" ⇒ MainPathSelector.isGateway() 照旧认得。
            Map.entry("inclusiveGateway", "gateway_conditional"),
            Map.entry("subProcess", "subprocess"));

    public static ParsedGraph parse(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new IllegalArgumentException("XML 为空");
        }
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setExpandEntityReferences(false);

            DocumentBuilder b = f.newDocumentBuilder();
            Document doc = b.parse(new InputSource(new StringReader(xml)));

            Map<String, ParsedNode> nodes = new LinkedHashMap<>();
            List<ParsedEdge> edges = new ArrayList<>();

            // 节点：遍历 process 下所有带 id 的可执行元素
            for (String tag : TYPE_MAP.keySet()) {
                NodeList list = doc.getElementsByTagNameNS("*", tag);
                for (int i = 0; i < list.getLength(); i++) {
                    Element el = (Element) list.item(i);
                    String id = el.getAttribute("id");
                    if (id == null || id.isBlank()) {
                        continue;
                    }
                    String name = el.getAttribute("name");
                    // 存量数据 name 为空，用 protocolName 兜底
                    String protocol = el.getAttribute("protocolName");
                    if ((name == null || name.isBlank()) && protocol != null && !protocol.isBlank()) {
                        name = protocol;
                    }
                    if (name == null || name.isBlank()) {
                        name = id;
                    }
                    Long resource = parseLong(el.getAttribute("resource"));
                    // 设计器填的参数存在自定义属性 methodParams（JSON 字符串）
                    String mp = el.getAttribute("methodParams");
                    nodes.putIfAbsent(id, new ParsedNode(id, name, TYPE_MAP.get(tag),
                            protocol == null || protocol.isBlank() ? null : protocol, resource,
                            mp == null || mp.isBlank() ? null : mp));
                }
            }

            // 连线
            NodeList flows = doc.getElementsByTagNameNS("*", "sequenceFlow");
            for (int i = 0; i < flows.getLength(); i++) {
                Element el = (Element) flows.item(i);
                String from = el.getAttribute("sourceRef");
                String to = el.getAttribute("targetRef");
                if (from != null && !from.isBlank() && to != null && !to.isBlank()) {
                    edges.add(new ParsedEdge(from, to));
                }
            }

            if (nodes.isEmpty()) {
                throw new IllegalArgumentException("XML 里没有找到任何节点");
            }
            return new ParsedGraph(new ArrayList<>(nodes.values()), edges);

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("BPMN 解析失败: " + e.getMessage(), e);
        }
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

    private BpmnGraphParser() {
    }
}
