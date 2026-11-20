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
 * Compiles BPMN XML to the deliberately bounded V1 execution subset.
 * Unsupported event definitions and ambiguous gateway shapes fail closed.
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
            NodeList all=doc.getElementsByTagNameNS(BPMN_NS,"*");
            for(int i=0;i<all.getLength();i++) {
                Element el=(Element)all.item(i);
                String local=el.getLocalName();
                if ("eventDefinition".equals(local) || local.endsWith("EventDefinition"))
                    throw new IllegalArgumentException("Unsupported BPMN event definition: "+local);
                if(isUnsupported(local))
                    throw new IllegalArgumentException("Unsupported BPMN element: "+local+" id="+el.getAttribute("id"));
                if(isExecutable(local)) {
                    String id=el.getAttribute("id");
                    if(id.isBlank()) throw new IllegalArgumentException("BPMN executable element has no id: "+local);
                    if(elements.putIfAbsent(id,el)!=null)
                        throw new IllegalArgumentException("Duplicate BPMN element id: "+id);
                }
            }
            if(elements.isEmpty()) throw new IllegalArgumentException("BPMN contains no supported executable nodes");

            Map<String,String> defaultByGateway=new HashMap<>();
            for(Element el:elements.values()) {
                String d=blankToNull(el.getAttribute("default"));
                if(d!=null) {
                    if(!Set.of("exclusiveGateway","inclusiveGateway").contains(el.getLocalName()))
                        throw new IllegalArgumentException("default flow is only supported on XOR/Inclusive gateways: "+el.getAttribute("id"));
                    defaultByGateway.put(el.getAttribute("id"),d);
                }
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
                    if(child instanceof Element ce && "conditionExpression".equals(ce.getLocalName())
                            && BPMN_NS.equals(ce.getNamespaceURI())) {
                        if(condition!=null) throw new IllegalArgumentException("Multiple conditionExpression elements on "+id);
                        condition=blankToNull(ce.getTextContent());
                    }
                }
                edges.add(new GraphPlan.Edge(id,from,to,condition,false));
            }

            Map<String,Integer> in=new HashMap<>(), out=new HashMap<>();
            Map<String,List<GraphPlan.Edge>> outgoing=new HashMap<>();
            for(GraphPlan.Edge e:edges) {
                out.merge(e.from(),1,Integer::sum);
                in.merge(e.to(),1,Integer::sum);
                outgoing.computeIfAbsent(e.from(),k->new ArrayList<>()).add(e);
            }
            Set<String> defaultIds=new HashSet<>();
            for(Map.Entry<String,String> entry:defaultByGateway.entrySet()) {
                String gateway=entry.getKey(), flowId=entry.getValue();
                GraphPlan.Edge edge=edges.stream().filter(e->e.key().equals(flowId)).findFirst()
                        .orElseThrow(()->new IllegalArgumentException("Gateway "+gateway+" references missing default flow "+flowId));
                if(!edge.from().equals(gateway))
                    throw new IllegalArgumentException("Default flow "+flowId+" is not outgoing from gateway "+gateway);
                if(edge.condition()!=null)
                    throw new IllegalArgumentException("Default flow must not have conditionExpression: "+flowId);
                defaultIds.add(flowId);
            }

            List<GraphPlan.Edge> normalizedEdges=new ArrayList<>();
            for(GraphPlan.Edge edge:edges) {
                String sourceType=elements.get(edge.from()).getLocalName();
                boolean conditionalGateway=Set.of("exclusiveGateway","inclusiveGateway").contains(sourceType)
                        && out.getOrDefault(edge.from(),0)>1;
                if(edge.condition()!=null && !conditionalGateway)
                    throw new IllegalArgumentException("Condition expression is only supported on XOR/Inclusive split flows: "+edge.key());
                if(conditionalGateway && !defaultIds.contains(edge.key()) && edge.condition()==null)
                    throw new IllegalArgumentException("Conditional gateway flow has no conditionExpression or default: "+edge.key());
                normalizedEdges.add(new GraphPlan.Edge(edge.key(),edge.from(),edge.to(),edge.condition(),
                        defaultIds.contains(edge.key())));
            }

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
                if(n.kind()==GraphPlan.Kind.TASK && n.protocol()==null)
                    throw new IllegalArgumentException("Device task has no protocolName: "+n.key());
                if(n.kind()==GraphPlan.Kind.TASK && n.resourceId()==null && n.capabilityCode()==null)
                    throw new IllegalArgumentException("Device task has neither resource binding nor capability metadata: "+n.key());
            }
            return new GraphPlan(1,VERSION,input.definitionKey(),input.definitionVersion(),
                    GraphPlanCodec.sha256(input.bpmnXml()),nodes,normalizedEdges);
        } catch(IllegalArgumentException e) { throw e; }
        catch(Exception e) { throw new IllegalArgumentException("BPMN compilation failed: "+e.getMessage(),e); }
    }

    private static boolean isExecutable(String t) {
        return Set.of("startEvent","endEvent","task","userTask","serviceTask","scriptTask",
                "sendTask","receiveTask","customDevice","exclusiveGateway","parallelGateway",
                "inclusiveGateway").contains(t);
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
            // V1 intentionally supports only canonical 1->N splits and N->1 joins.
            // Reject zero-sided, pass-through, and mixed-direction shapes rather than
            // guessing a single Kind that would erase BPMN execution semantics.
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
