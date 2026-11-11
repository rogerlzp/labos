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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * BPMN **DI 补全**：给缺 {@code BPMNEdge} 的 {@code sequenceFlow} 补上连线图形信息。
 *
 * <p><b>为什么需要</b>（2026-11-11 实测）：
 * BPMN 里「逻辑连线」是 {@code <bpmn:sequenceFlow sourceRef= targetRef=>}，
 * 而「画在纸上的连线」是 DI 段里的 {@code <bpmndi:BPMNEdge>}（带 waypoints）。
 * 前端 bpmn-js 导入时**只渲染有 DI 的元素** ⇒
 * <b>XML 里有 sequenceFlow、却没有 BPMNEdge ⇒ 画布上是一堆孤立节点、一条线都没有</b>。
 * 而后端保存与执行看的是 sequenceFlow ⇒ <b>图能存、能跑，只是看不见</b>。
 * 现场看到的就是「节点都在、连线全没了，但保存还成功」——
 * 用户会以为保存没校验、流程是断的。
 *
 * <p><b>为什么在后端补而不是让每个造图的脚本自己写全</b>：
 * XML 的来源有三处（页面的 bpmn-js 导出、脚本造的、第三方导入的），
 * 只有后端这一处是**必经之路** ⇒ 在这里补一次，所有来源都受益。
 * 补全是**幂等**的：已经有 BPMNEdge 的不动（页面导出的图本来就有）。
 *
 * <p><b>边界</b>：两端节点都没有 DI（位置）时补不出来 —— 那时连线仍然没有图形，
 * 但流程本身连位置都没有，页面上也是画不出来的，不在本类的职责内。
 */
public final class BpmnDiCompleter {

    private static final String NS_BPMN = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    private static final String NS_BPMNDI = "http://www.omg.org/spec/BPMN/20100524/DI";
    private static final String NS_DI = "http://www.omg.org/spec/DD/20100524/DI";

    private BpmnDiCompleter() {
    }

    /** 补全并返回新的 XML；解析失败时**原样返回**（不因为补 DI 而把用户的图弄坏）。 */
    public static String complete(String xml) {
        if (xml == null || xml.isBlank()) {
            return xml;
        }
        try {
            Document doc = parse(xml);
            int added = complete(doc);
            return added > 0 ? serialize(doc) : xml;
        } catch (Exception e) {
            // 补 DI 是**锦上添花**，不该因为它把一张本来能存的图弄成存不了
            return xml;
        }
    }

    /** 补全 Document；返回补了几条边。 */
    public static int complete(Document doc) {
        // ① 已有形状：bpmnElement -> (x, y, w, h)
        java.util.Map<String, double[]> shapes = new java.util.LinkedHashMap<>();
        for (Element shape : elements(doc, "BPMNShape")) {
            String be = shape.getAttribute("bpmnElement");
            if (be.isBlank()) {
                continue;
            }
            double[] box = bounds(shape);
            if (box != null) {
                shapes.put(be, box);
            }
        }
        if (shapes.isEmpty()) {
            return 0;   // 没有位置信息，补不了
        }

        // ② 已有 DI 的连线
        Set<String> withDi = new HashSet<>();
        for (Element edge : elements(doc, "BPMNEdge")) {
            String be = edge.getAttribute("bpmnElement");
            if (!be.isBlank()) {
                withDi.add(be);
            }
        }

        // ③ 找 BPMNPlane（BPMNEdge 要挂进去）
        Element plane = null;
        for (Element e : elements(doc, "BPMNPlane")) {
            plane = e;
            break;
        }
        if (plane == null) {
            return 0;
        }

        // ④ 逐条 sequenceFlow 补
        int added = 0;
        for (Element flow : elements(doc, "sequenceFlow")) {
            String id = flow.getAttribute("id");
            if (id.isBlank() || withDi.contains(id)) {
                continue;
            }
            double[] s = shapes.get(flow.getAttribute("sourceRef"));
            double[] t = shapes.get(flow.getAttribute("targetRef"));
            if (s == null || t == null) {
                continue;   // 两端没有位置 ⇒ 补不出来（页面上也画不出位置）
            }
            double[] from = anchor(s, t, true);
            double[] to = anchor(t, s, false);

            Element edge = doc.createElementNS(NS_BPMNDI, "bpmndi:BPMNEdge");
            edge.setAttribute("id", id + "_di");
            edge.setAttribute("bpmnElement", id);
            edge.appendChild(waypoint(doc, from[0], from[1]));
            edge.appendChild(waypoint(doc, to[0], to[1]));
            plane.appendChild(edge);
            added++;
        }
        return added;
    }

    /**
     * 取连线端点：源节点取「朝向目标那一侧」的中点，目标节点取「朝向来源那一侧」的中点。
     *
     * @param box   本端形状（x, y, w, h）
     * @param other 另一端形状
     * @param isLeft true = 取左边（目标在左边时），false = 取右边
     */
    private static double[] anchor(double[] box, double[] other, boolean isLeft) {
        double x = box[0];
        double y = box[1];
        double w = box[2];
        double h = box[3];
        double midY = y + h / 2.0;
        // 目标在左边 ⇒ 从左边出；否则从右边出
        boolean otherIsLeft = other[0] < x;
        boolean takeLeft = isLeft ? otherIsLeft : !otherIsLeft;
        return new double[]{takeLeft ? x : x + w, midY};
    }

    private static Element waypoint(Document doc, double x, double y) {
        Element wp = doc.createElementNS(NS_DI, "di:waypoint");
        wp.setAttribute("x", fmt(x));
        wp.setAttribute("y", fmt(y));
        return wp;
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    /** 取形状的 dc:Bounds（x, y, w, h）；取不到返回 null。 */
    private static double[] bounds(Element shape) {
        NodeList kids = shape.getElementsByTagNameNS("*", "Bounds");
        if (kids.getLength() == 0) {
            kids = shape.getElementsByTagName("dc:Bounds");
        }
        if (kids.getLength() == 0) {
            return null;
        }
        Element b = (Element) kids.item(0);
        try {
            return new double[]{
                    Double.parseDouble(b.getAttribute("x")),
                    Double.parseDouble(b.getAttribute("y")),
                    Double.parseDouble(b.getAttribute("width")),
                    Double.parseDouble(b.getAttribute("height"))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<Element> elements(Document doc, String localName) {
        List<Element> out = new ArrayList<>();
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Node n = all.item(i);
            if (n instanceof Element el && localName.equals(el.getLocalName())) {
                out.add(el);
            }
        }
        return out;
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        // 防 XXE：不允许外部实体
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder b = f.newDocumentBuilder();
        return b.parse(new InputSource(new StringReader(xml)));
    }

    private static String serialize(Document doc) throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer t = tf.newTransformer();
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        t.setOutputProperty(OutputKeys.INDENT, "no");
        StringWriter w = new StringWriter();
        t.transform(new DOMSource(doc), new StreamResult(w));
        return w.toString();
    }
}
