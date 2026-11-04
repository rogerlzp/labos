package com.labos.kernel.workflow;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把「多步节点」展开成 BPMN 的 expanded subProcess，让设计器里能折叠/展开。
 *
 * <p>只在<span>展示</span>时展开——库里的 {@code workflow.graph} 不被修改，
 * 编辑保存时前端会还原回普通 task（designer.html 的 collapse()）。
 *
 * <p>三件事必须同时做对，否则渲染会坏：
 * <ol>
 *   <li>结构：subProcess 内放 N 个 task + N-1 条 sequenceFlow</li>
 *   <li>图形：subProcess 放大，内部每个节点生成 BPMNShape，**每条 flow 生成 BPMNEdge**</li>
 *   <li>避让：右侧其他节点整体右移，否则会被 subProcess 盖住</li>
 * </ol>
 */
public final class BpmnExpander {

    private static final String NS_BPMN = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    private static final String NS_DI = "http://www.omg.org/spec/BPMN/20100524/DI";
    private static final String NS_DC = "http://www.omg.org/spec/DD/20100524/DC";
    private static final String NS_DD = "http://www.omg.org/spec/DD/20100524/DI";

    /** 步骤节点 id 后缀，前端据此还原。 */
    public static final String STEP_INFIX = "__s";

    private static final double STEP_W = 110;
    private static final double STEP_H = 80;
    private static final double STEP_GAP = 150;
    private static final double SUB_H = 220;

    /**
     * 老库的 XML 用默认命名空间（{@code <definitions xmlns="...">}），
     * 而本类创建的元素带 {@code bpmn:} 前缀。
     *
     * <p>两者混在一起序列化后，前缀没有声明，bpmn-js 直接解析失败。
     * 所以在解析前把默认命名空间统一改成带前缀的形式。
     */
    private static String normalizeNamespace(String xml) {
        if (xml == null || xml.contains("xmlns:bpmn=")) {
            return xml;   // 已经是前缀形式
        }
        String out = xml;
        // 1) 根元素：默认命名空间 -> bpmn 前缀
        out = out.replace("<definitions ", "<bpmn:definitions xmlns:bpmn=\"" + NS_BPMN + "\" ")
                .replace("<definitions>", "<bpmn:definitions xmlns:bpmn=\"" + NS_BPMN + "\">")
                .replace("</definitions>", "</bpmn:definitions>");
        out = out.replace(" xmlns=\"" + NS_BPMN + "\"", "");
        // 2) 所有无前缀标签加 bpmn:（有前缀的 bpmndi/omgdi/omgdc/dc/di 不动）
        out = out.replaceAll("<(?!/?bpmn:)(?!/?bpmndi:)(?!/?omgdi:)(?!/?omgdc:)(?!/?dc:)(?!/?di:)(?!/\\?)(?![!?])/([a-zA-Z])", "</bpmn:$1");
        out = out.replaceAll("<(?!/?bpmn:)(?!/?bpmndi:)(?!/?omgdi:)(?!/?omgdc:)(?!/?dc:)(?!/?di:)(?![!?])/([a-zA-Z])", "<bpmn:$1");
        return out;
    }

    public static String expand(String xml, Map<String, List<String>> stepsByKey) {
        if (xml == null || xml.isBlank() || stepsByKey == null || stepsByKey.isEmpty()) {
            return xml;
        }
        try {
            Document doc = parse(normalizeNamespace(xml));

            NodeList processes = doc.getElementsByTagNameNS(NS_BPMN, "process");
            if (processes.getLength() == 0) {
                return xml;
            }
            Element process = (Element) processes.item(0);

            Map<String, double[]> bounds = collectBounds(doc);
            Element plane = firstPlane(doc);

            NodeList tasks = process.getElementsByTagNameNS(NS_BPMN, "task");
            int expanded = 0;
            for (int i = tasks.getLength() - 1; i >= 0; i--) {
                Element task = (Element) tasks.item(i);
                String id = task.getAttribute("id");
                List<String> steps = stepsByKey.get(id);
                if (steps == null || steps.size() <= 1) {
                    continue;
                }
                double[] box = bounds.getOrDefault(id, new double[]{220, 100, 100, 80});
                double newW = Math.max(560, 40 + steps.size() * STEP_GAP);

                process.replaceChild(buildSubProcess(doc, task, steps), task);

                if (plane != null) {
                    expandShape(doc, plane, id, steps, box, newW);
                    // 右侧节点让位
                    shiftRight(doc, id, box[0], newW - box[2], stepIds(id, steps.size()));
                }
                expanded++;
            }

            return expanded == 0 ? xml : serialize(doc);

        } catch (Exception e) {
            // 展开失败不能阻断设计器
            System.err.println("[WARN] BPMN 展开失败，退回原图: " + e.getMessage());
            return xml;
        }
    }

    // ---------------------------------------------------------------- 结构

    private static Element buildSubProcess(Document doc, Element task, List<String> steps) {
        String id = task.getAttribute("id");
        String name = task.getAttribute("name");

        Element sub = doc.createElementNS(NS_BPMN, "bpmn:subProcess");
        sub.setAttribute("id", id);
        sub.setAttribute("name", name == null || name.isBlank() ? id : name);
        for (String a : new String[]{"protocolName", "protocolId", "resource",
                "resourceName", "variablesValue"}) {
            String v = task.getAttribute(a);
            if (v != null && !v.isBlank()) {
                sub.setAttribute(a, v);
            }
        }

        String prev = null;
        for (int i = 0; i < steps.size(); i++) {
            String stepId = id + STEP_INFIX + (i + 1);

            Element st = doc.createElementNS(NS_BPMN, "bpmn:task");
            st.setAttribute("id", stepId);
            st.setAttribute("name", steps.get(i));
            Element in = doc.createElementNS(NS_BPMN, "bpmn:incoming");
            Element out = doc.createElementNS(NS_BPMN, "bpmn:outgoing");
            in.setTextContent(prev == null ? id + "__in" : id + "__f" + i);
            out.setTextContent(id + "__f" + (i + 1));
            st.appendChild(in);
            st.appendChild(out);
            sub.appendChild(st);

            if (prev != null) {
                Element flow = doc.createElementNS(NS_BPMN, "bpmn:sequenceFlow");
                flow.setAttribute("id", id + "__f" + i);
                flow.setAttribute("sourceRef", prev);
                flow.setAttribute("targetRef", stepId);
                sub.appendChild(flow);
            }
            prev = stepId;
        }
        return sub;
    }

    // ---------------------------------------------------------------- 图形

    private static void expandShape(Document doc, Element plane, String id,
                                    List<String> steps, double[] box, double newW) {
        double x = box[0];
        double y = box[1];

        Element shape = findShape(doc, id);
        if (shape == null) {
            shape = doc.createElementNS(NS_DI, "bpmndi:BPMNShape");
            shape.setAttribute("id", id + "_di");
            shape.setAttribute("bpmnElement", id);
            plane.appendChild(shape);
        }
        shape.setAttribute("isExpanded", "true");
        setBounds(doc, shape, x, y, newW, SUB_H);

        for (int i = 0; i < steps.size(); i++) {
            String stepId = id + STEP_INFIX + (i + 1);
            Element sh = doc.createElementNS(NS_DI, "bpmndi:BPMNShape");
            sh.setAttribute("id", stepId + "_di");
            sh.setAttribute("bpmnElement", stepId);
            double sx = x + 50 + i * STEP_GAP;
            double sy = y + 80;
            setBounds(doc, sh, sx, sy, STEP_W, STEP_H);
            plane.appendChild(sh);

            // 步骤之间的连线
            if (i > 0) {
                String flowId = id + "__f" + i;
                Element edge = doc.createElementNS(NS_DI, "bpmndi:BPMNEdge");
                edge.setAttribute("id", flowId + "_di");
                edge.setAttribute("bpmnElement", flowId);
                edge.appendChild(waypoint(doc, sx - STEP_GAP + STEP_W, sy + STEP_H / 2));
                edge.appendChild(waypoint(doc, sx, sy + STEP_H / 2));
                plane.appendChild(edge);
            }
        }
        // subProcess 出口 -> 最后一个步骤
        String lastId = id + STEP_INFIX + steps.size();
        Element outEdge = doc.createElementNS(NS_DI, "bpmndi:BPMNEdge");
        outEdge.setAttribute("id", id + "__out_di");
        outEdge.setAttribute("bpmnElement", id + "__out");
        outEdge.appendChild(waypoint(doc, x + 50 + (steps.size() - 1) * STEP_GAP + STEP_W,
                y + 80 + STEP_H / 2));
        outEdge.appendChild(waypoint(doc, x + newW - 10, y + 80 + STEP_H / 2));
        plane.appendChild(outEdge);
    }

    /** 把 x 大于 fromX 的其他图形整体右移，避免被展开的框盖住。 */
    private static void shiftRight(Document doc, String selfId, double fromX,
                                   double delta, Set<String> skip) {
        if (delta <= 0) {
            return;
        }
        NodeList shapes = doc.getElementsByTagNameNS(NS_DI, "BPMNShape");
        for (int i = 0; i < shapes.getLength(); i++) {
            Element sh = (Element) shapes.item(i);
            String be = sh.getAttribute("bpmnElement");
            if (be == null || be.isBlank() || be.equals(selfId) || skip.contains(be)) {
                continue;
            }
            Element bd = firstChildNs(sh, NS_DC, "Bounds");
            if (bd == null) {
                continue;
            }
            double x = dbl(bd.getAttribute("x"));
            if (x > fromX) {
                bd.setAttribute("x", fmt(x + delta));
            }
        }
    }

    private static Set<String> stepIds(String id, int n) {
        Set<String> out = new LinkedHashSet<>();
        for (int i = 1; i <= n; i++) {
            out.add(id + STEP_INFIX + i);
        }
        return out;
    }

    // ---------------------------------------------------------------- 工具

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        DocumentBuilder b = f.newDocumentBuilder();
        return b.parse(new InputSource(new StringReader(xml)));
    }

    private static Element firstPlane(Document doc) {
        NodeList planes = doc.getElementsByTagNameNS(NS_DI, "BPMNPlane");
        return planes.getLength() > 0 ? (Element) planes.item(0) : null;
    }

    private static Element findShape(Document doc, String bpmnElement) {
        NodeList shapes = doc.getElementsByTagNameNS(NS_DI, "BPMNShape");
        for (int i = 0; i < shapes.getLength(); i++) {
            Element sh = (Element) shapes.item(i);
            if (bpmnElement.equals(sh.getAttribute("bpmnElement"))) {
                return sh;
            }
        }
        return null;
    }

    private static void setBounds(Document doc, Element shape,
                                  double x, double y, double w, double h) {
        Element bd = firstChildNs(shape, NS_DC, "Bounds");
        if (bd == null) {
            bd = doc.createElementNS(NS_DC, "dc:Bounds");
            shape.appendChild(bd);
        }
        bd.setAttribute("x", fmt(x));
        bd.setAttribute("y", fmt(y));
        bd.setAttribute("width", fmt(w));
        bd.setAttribute("height", fmt(h));
    }

    private static Element waypoint(Document doc, double x, double y) {
        Element wp = doc.createElementNS(NS_DD, "di:waypoint");
        wp.setAttribute("x", fmt(x));
        wp.setAttribute("y", fmt(y));
        return wp;
    }

    private static Map<String, double[]> collectBounds(Document doc) {
        Map<String, double[]> out = new LinkedHashMap<>();
        NodeList shapes = doc.getElementsByTagNameNS(NS_DI, "BPMNShape");
        for (int i = 0; i < shapes.getLength(); i++) {
            Element sh = (Element) shapes.item(i);
            String el = sh.getAttribute("bpmnElement");
            Element bd = firstChildNs(sh, NS_DC, "Bounds");
            if (el != null && bd != null) {
                out.put(el, new double[]{
                        dbl(bd.getAttribute("x")), dbl(bd.getAttribute("y")),
                        dbl(bd.getAttribute("width")), dbl(bd.getAttribute("height"))});
            }
        }
        return out;
    }

    private static Element firstChildNs(Element parent, String ns, String local) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE
                    && ns.equals(k.getNamespaceURI())
                    && local.equals(k.getLocalName())) {
                return (Element) k;
            }
        }
        return null;
    }

    private static String serialize(Document doc) throws Exception {
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        t.setOutputProperty(OutputKeys.INDENT, "yes");
        StringWriter w = new StringWriter();
        t.transform(new DOMSource(doc), new StreamResult(w));
        return w.toString();
    }

    private static double dbl(String s) {
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private BpmnExpander() {
    }
}
