package com.labos.kernel.workflow.runtime.graph;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.*;

/**
 * Compiles BPMN XML to the V1 execution subset. Unsupported executable elements fail closed;
 * no node/edge is silently dropped. Metadata maps are read-only projections from Kernel storage.
 */
public final class BpmnGraphCompiler {
    public static final String VERSION = "graph-compiler-v1";
    private static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    public record NodeMetadata(String capabilityCode, Long workflowNodeId) {}
    public record CompilationInput(String definitionKey, String definitionVersion, String bpmnXml,
                                   Map<String, NodeMetadata> nodeMetadata) {
        public CompilationInput {
            Objects.requireNonNull(nodeMetadata, "nodeMetadata");
            nodeMetadata = Map.copyOf(nodeMetadata);
        }
    }

    public GraphPlan compile(CompilationInput input) {
        Objects.requireNonNull(input, "input");
        if (input.bpmnXml()==null || input.bpmnXml().isBlank())
            throw new IllegalArgumentException("BPMN XML is empty");
        try {
            DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            Document doc=f.newDocumentBuilder().parse(new InputSource(new StringReader(input.bpmnXml())));

            Map<String,Element> elements=new LinkedHashMap<>();
            NodeList all=doc.getElementsByTagNameNS("*","*");
            for(int i=0;i<all.getLength();i++) {
                Element el=(Element)all.item(i);
                String local=el.getLocalName();
                if(local==null) continue;
                // Event definitions change execution semantics even when nested inside
                // an otherwise supported Start/End Event. V1 has no event-trigger runtime.
                String namespace = el.getNamespaceURI();
                if (BPMN_NS.equals(namespace)
                        && (local.equals("eventDefinition") || local.endsWith("EventDefinition")))
                    throw new IllegalArgumentException("Unsupported BPMN event definition: "+local);
                if (local.equals("sequenceFlow") && !BPMN_NS.equals(namespace))
                    throw new IllegalArgumentException("sequenceFlow must use the BPMN namespace");
                if (BPMN_NS.equals(namespace) && isUnsupported(local))
                    throw new IllegalArgumentException("Unsupported BPMN element: "+local+" id="+el.getAttribute("id"));
                if(isExecutable(local)) {
                    if (!BPMN_NS.equals(namespace))
                        throw new IllegalArgumentException("Executable BPMN element must use the BPMN namespace: "+local);
                    String id=el.getAttribute("id");
                    if(id.isBlank()) throw new IllegalArgumentException("BPMN executable element has no id: "+local);
                    if(elements.putIfAbsent(id,el)!=null)
                        throw new IllegalArgumentException("Duplicate BPMN element id: "+id);
                }
            }
            if(elements.isEmpty()) throw new IllegalArgumentException("BPMN contains no supported executable nodes");

            Set<String> defaultIds=new HashSet<>();
            for(Element el:elements.values()) {
                String d=el.getAttribute("default");
                if(!d.isBlank()) defaultIds.add(d);
            }
            List<GraphPlan.Edge> edges=new ArrayList<>();
            Set<String> edgeIds=new HashSet<>();
            NodeList flows=doc.getElementsByTagNameNS(BPMN_NS,"sequenceFlow");
            for(int i=0;i<flows.getLength();i++) {
                Element el=(Element)flows.item(i);
                String id=el.getAttribute("id"), from=el.getAttribute("sourceRef"), to=el.getAttribute("targetRef");
                if(id.isBlank() || from.isBlank() || to.isBlank())
                    throw new IllegalArgumentException("sequenceFlow requires id/sourceRef/targetRef");
                if(!edgeIds.add(id)) throw new IllegalArgumentException("Duplicate sequenceFlow id: "+id);
                if(!elements.containsKey(from) || !elements.containsKey(to))
                    throw new IllegalArgumentException("sequenceFlow "+id+" references unknown node "+from+" -> "+to);
                String condition=null;
                NodeList kids=el.getChildNodes();
                for(int k=0;k<kids.getLength();k++) {
                    Node child=kids.item(k);
                    if(child instanceof Element ce && "conditionExpression".equals(ce.getLocalName()))
                        condition=blankToNull(ce.getTextContent());
                }
                edges.add(new GraphPlan.Edge(id,from,to,condition,defaultIds.contains(id)));
            }

            Map<String,Integer> in=new HashMap<>(), out=new HashMap<>();
            for(GraphPlan.Edge e:edges) { out.merge(e.from(),1,Integer::sum); in.merge(e.to(),1,Integer::sum); }
            List<GraphPlan.Node> nodes=new ArrayList<>();
            for(Map.Entry<String,Element> entry:elements.entrySet()) {
                String id=entry.getKey(); Element el=entry.getValue(); String type=el.getLocalName();
                String protocol=blankToNull(el.getAttribute("protocolName"));
                String name=first(el.getAttribute("name"),protocol,id);
                GraphPlan.Kind kind=kind(type,protocol,in.getOrDefault(id,0),out.getOrDefault(id,0));
                NodeMetadata meta=input.nodeMetadata().get(id);
                Long resource=parseLong(el.getAttribute("resource"));
                String methodParams=blankToNull(el.getAttribute("methodParams"));
                String stage=blankToNull(el.getAttribute("stageCode"));
                nodes.add(new GraphPlan.Node(id,name,kind,type,protocol,resource,methodParams,stage,
                        meta==null?null:meta.capabilityCode(),meta==null?null:meta.workflowNodeId()));
            }
            long starts=nodes.stream().filter(n->n.kind()==GraphPlan.Kind.START).count();
            if(starts!=1) throw new IllegalArgumentException("V1 requires exactly one startEvent; found "+starts);
            for(GraphPlan.Node n:nodes) {
                if (n.kind()==GraphPlan.Kind.START && out.getOrDefault(n.key(),0)!=1)
                    throw new IllegalArgumentException("V1 startEvent requires exactly one outgoing sequenceFlow: "+n.key());
                if(n.kind()==GraphPlan.Kind.TASK && n.protocol()==null)
                    throw new IllegalArgumentException("Device task has no protocolName: "+n.key());
                if(n.kind()==GraphPlan.Kind.TASK && n.resourceId()==null && n.capabilityCode()==null)
                    throw new IllegalArgumentException("Device task has neither resource binding nor capability metadata: "+n.key());
            }
            return new GraphPlan(1,VERSION,input.definitionKey(),input.definitionVersion(),
                    GraphPlanCodec.sha256(input.bpmnXml()),nodes,edges);
        } catch(IllegalArgumentException e) { throw e; }
        catch(Exception e) { throw new IllegalArgumentException("BPMN compilation failed: "+e.getMessage(),e); }
    }

    private static boolean isExecutable(String t) {
        return Set.of("startEvent","endEvent","task","userTask","serviceTask","scriptTask",
                "sendTask","receiveTask","customDevice","exclusiveGateway","parallelGateway",
                "inclusiveGateway").contains(t) || t.startsWith("custom");
    }
    private static boolean isUnsupported(String t) {
        return Set.of("subProcess","callActivity","businessRuleTask","manualTask","intermediateCatchEvent",
                "intermediateThrowEvent","boundaryEvent","eventBasedGateway","complexGateway",
                "transaction","adHocSubProcess").contains(t);
    }
    private static GraphPlan.Kind kind(String t,String protocol,int in,int out) {
        if(t.equals("startEvent")) return GraphPlan.Kind.START;
        if(t.equals("endEvent")) return GraphPlan.Kind.END;
        if(t.endsWith("Gateway")) {
            boolean join=in>1, split=out>1;
            // V1 supports only canonical 1-in/N-out splits and N-in/1-out joins.
            // Mixed, pass-through, and zero-sided gateway shapes are rejected.
            if(join==split || (split && in!=1) || (join && out!=1))
                throw new IllegalArgumentException("Unsupported gateway shape; V1 requires exactly 1-in/N-out split or N-in/1-out join: "+t+" in="+in+" out="+out);
            return switch(t) {
                case "parallelGateway" -> join?GraphPlan.Kind.JOIN_PARALLEL:GraphPlan.Kind.SPLIT_PARALLEL;
                case "exclusiveGateway" -> join?GraphPlan.Kind.JOIN_XOR:GraphPlan.Kind.SPLIT_XOR;
                case "inclusiveGateway" -> join?GraphPlan.Kind.JOIN_INCLUSIVE:GraphPlan.Kind.SPLIT_INCLUSIVE;
                default -> throw new IllegalArgumentException("Unsupported gateway: "+t);
            };
        }
        return protocol!=null && protocol.startsWith("__")?GraphPlan.Kind.BUILTIN:GraphPlan.Kind.TASK;
    }
    private static String first(String... values) {
        for(String v:values) if(v!=null&&!v.isBlank()) return v.trim();
        return "";
    }
    private static String blankToNull(String v) { return v==null||v.isBlank()?null:v.trim(); }
    private static Long parseLong(String v) {
        if(v==null||v.isBlank()) return null;
        try { return Long.valueOf(v.trim()); }
        catch(NumberFormatException e) { throw new IllegalArgumentException("Invalid resource id: "+v,e); }
    }
}
