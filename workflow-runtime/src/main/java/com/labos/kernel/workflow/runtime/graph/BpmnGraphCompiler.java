package com.labos.kernel.workflow.runtime.graph;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.*;

/**
 * Compiles BPMN XML to the V1/V2 execution subset. Unsupported executable elements fail closed;
 * no node/edge is silently dropped. Metadata maps are read-only projections from Kernel storage.
 */
public final class BpmnGraphCompiler {
    public static final String VERSION = "graph-compiler-v1";
    private static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    private static final String LABOS_NS = "urn:labos:workflow-runtime:v1";

    public record NodeMetadata(String capabilityCode, Long workflowNodeId) {}
    public record CompilationInput(String definitionKey, String definitionVersion, String bpmnXml,
                                   Map<String, NodeMetadata> nodeMetadata) {
        public CompilationInput {
            Objects.requireNonNull(nodeMetadata, "nodeMetadata");
            nodeMetadata = Map.copyOf(nodeMetadata);
        }
    }

    private record TimerSpec(String durationIso, long durationMillis) {}
    private record BoundaryAttachment(String id, String hostId, TimerSpec timer) {}

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
            Map<String,Element> boundaryElements=new LinkedHashMap<>();
            Set<String> allElementIds=new HashSet<>();
            NodeList all=doc.getElementsByTagNameNS("*","*");
            for(int i=0;i<all.getLength();i++) {
                Element el=(Element)all.item(i);
                String local=el.getLocalName();
                if(local==null) continue;
                String namespace = el.getNamespaceURI();
                if (LABOS_NS.equals(namespace)) {
                    validateLabosExtensionPlacement(el);
                    continue;
                }
                if (BPMN_NS.equals(namespace)
                        && (local.equals("eventDefinition") || local.endsWith("EventDefinition"))) {
                    if (!"timerEventDefinition".equals(local) || !isAllowedTimerParent(el)) {
                        throw new IllegalArgumentException("Unsupported BPMN event definition: " + local);
                    }
                }
                if (local.equals("sequenceFlow") && !BPMN_NS.equals(namespace))
                    throw new IllegalArgumentException("sequenceFlow must use the BPMN namespace");
                if (BPMN_NS.equals(namespace) && isUnsupported(local))
                    throw new IllegalArgumentException("Unsupported BPMN element: "+local+" id="+el.getAttribute("id"));
                if ("boundaryEvent".equals(local)) {
                    if (!BPMN_NS.equals(namespace))
                        throw new IllegalArgumentException("Executable BPMN element must use the BPMN namespace: "+local);
                    String id=el.getAttribute("id");
                    if(id.isBlank()) throw new IllegalArgumentException("BPMN boundaryEvent has no id");
                    if(!allElementIds.add(id))
                        throw new IllegalArgumentException("Duplicate BPMN element id: "+id);
                    boundaryElements.put(id, el);
                    continue;
                }
                if(isExecutable(local)) {
                    if (!BPMN_NS.equals(namespace))
                        throw new IllegalArgumentException("Executable BPMN element must use the BPMN namespace: "+local);
                    String id=el.getAttribute("id");
                    if(id.isBlank()) throw new IllegalArgumentException("BPMN executable element has no id: "+local);
                    if(!allElementIds.add(id))
                        throw new IllegalArgumentException("Duplicate BPMN element id: "+id);
                    elements.put(id,el);
                }
            }
            if(elements.isEmpty()) throw new IllegalArgumentException("BPMN contains no supported executable nodes");

            Set<String> defaultIds=new HashSet<>();
            Map<String,String> defaultByGateway=new LinkedHashMap<>();
            for(Element el:elements.values()) {
                String d=el.getAttribute("default");
                if(!d.isBlank()) {
                    if(!el.getLocalName().endsWith("Gateway"))
                        throw new IllegalArgumentException("BPMN default flow is only supported on gateways: "+el.getAttribute("id"));
                    defaultIds.add(d);
                    defaultByGateway.put(el.getAttribute("id"),d);
                }
            }
            for(Element el:boundaryElements.values()) {
                if(!el.getAttribute("default").isBlank()) {
                    throw new IllegalArgumentException("BPMN default flow is only supported on gateways: "+el.getAttribute("id"));
                }
            }

            // Classify receiveTask / intermediateCatchEvent Wait specs before attaching boundaryEvents.
            Map<String,String> waitCorrelationByNodeId=new LinkedHashMap<>();
            Map<String,TimerSpec> timerWaitByNodeId=new LinkedHashMap<>();
            for(Map.Entry<String,Element> entry:elements.entrySet()) {
                String id=entry.getKey();
                Element el=entry.getValue();
                String type=el.getLocalName();
                NodeMetadata meta=input.nodeMetadata().get(id);
                if ("receiveTask".equals(type)) {
                    String corr = extractWaitCorrelationKeyIfPresent(el, id, meta);
                    if (corr != null) {
                        waitCorrelationByNodeId.put(id, corr);
                    }
                } else if ("intermediateCatchEvent".equals(type)) {
                    ensureNoTaskConfig(el, id, meta, type);
                    timerWaitByNodeId.put(id, parseSingleTimerDefinition(el, id));
                } else {
                    ensureNoReceiveWaitExtension(el, id);
                }
            }

            // Validate and index boundaryEvents attached to Wait receiveTasks.
            Map<String,BoundaryAttachment> boundaryById=new LinkedHashMap<>();
            Map<String,BoundaryAttachment> boundaryByHostWaitId=new LinkedHashMap<>();
            for(Map.Entry<String,Element> entry:boundaryElements.entrySet()) {
                BoundaryAttachment attachment = parseBoundaryAttachment(
                        entry.getKey(), entry.getValue(), elements, waitCorrelationByNodeId, input.nodeMetadata());
                BoundaryAttachment existing = boundaryByHostWaitId.putIfAbsent(attachment.hostId(), attachment);
                if (existing != null) {
                    throw new IllegalArgumentException(
                            "WAIT node cannot have multiple boundaryEvent timeouts: " + attachment.hostId());
                }
                boundaryById.put(attachment.id(), attachment);
            }

            List<GraphPlan.Edge> edges=new ArrayList<>();
            Set<String> edgeIds=new HashSet<>();
            Map<String,List<GraphPlan.Edge>> rawOutgoing=new LinkedHashMap<>();
            Map<String,Integer> in=new HashMap<>();
            NodeList flows=doc.getElementsByTagNameNS(BPMN_NS,"sequenceFlow");
            for(int i=0;i<flows.getLength();i++) {
                Element el=(Element)flows.item(i);
                String id=el.getAttribute("id"), from=el.getAttribute("sourceRef"), to=el.getAttribute("targetRef");
                if(id.isBlank() || from.isBlank() || to.isBlank())
                    throw new IllegalArgumentException("sequenceFlow requires id/sourceRef/targetRef");
                if(!edgeIds.add(id)) throw new IllegalArgumentException("Duplicate sequenceFlow id: "+id);
                if(allElementIds.contains(id))
                    throw new IllegalArgumentException("BPMN sequenceFlow id collides with element id: "+id);
                if(boundaryById.containsKey(to))
                    throw new IllegalArgumentException("boundaryEvent cannot have incoming sequenceFlow: "+to);
                boolean fromBoundary = boundaryById.containsKey(from);
                if((!elements.containsKey(from) && !fromBoundary) || !elements.containsKey(to))
                    throw new IllegalArgumentException("sequenceFlow "+id+" references unknown node "+from+" -> "+to);
                String condition=extractConditionExpression(el, id);
                boolean isDefault=defaultIds.contains(id);
                BoundaryAttachment attachment = fromBoundary ? boundaryById.get(from) : null;
                boolean fromWait = attachment != null
                        || waitCorrelationByNodeId.containsKey(from)
                        || timerWaitByNodeId.containsKey(from);
                if (fromWait && (condition != null || isDefault)) {
                    throw new IllegalArgumentException(
                            "WAIT/Timer/Timeout outgoing sequenceFlow cannot carry condition or default: " + id);
                }
                String normalizedFrom = attachment != null ? attachment.hostId() : from;
                GraphPlan.RouteKind routeKind = attachment != null
                        ? GraphPlan.RouteKind.TIMEOUT
                        : (fromWait ? GraphPlan.RouteKind.NORMAL : null);
                GraphPlan.Edge edge = new GraphPlan.Edge(id, normalizedFrom, to, condition, isDefault, routeKind);
                edges.add(edge);
                rawOutgoing.computeIfAbsent(from, k -> new ArrayList<>()).add(edge);
                in.merge(to, 1, Integer::sum);
            }
            Map<String,GraphPlan.Edge> edgesById=new HashMap<>();
            for(GraphPlan.Edge edge:edges) edgesById.put(edge.key(),edge);
            for(Map.Entry<String,String> entry:defaultByGateway.entrySet()) {
                GraphPlan.Edge defaultEdge=edgesById.get(entry.getValue());
                if(defaultEdge==null)
                    throw new IllegalArgumentException("Gateway default references unknown sequenceFlow: "+entry.getKey()+" -> "+entry.getValue());
                if(!entry.getKey().equals(defaultEdge.from()))
                    throw new IllegalArgumentException("Gateway default sequenceFlow is not outgoing from gateway: "+entry.getKey()+" -> "+entry.getValue());
            }

            // Validate boundaryEvent outgoing degrees and host WAIT degree / route invariants.
            for (BoundaryAttachment boundary : boundaryById.values()) {
                List<GraphPlan.Edge> bEdges = rawOutgoing.getOrDefault(boundary.id(), List.of());
                if (bEdges.size() != 1) {
                    throw new IllegalArgumentException(
                            "Timer boundaryEvent requires exactly one outgoing sequenceFlow: "
                                    + boundary.id() + " out=" + bEdges.size());
                }
            }
            for (String waitId : waitCorrelationByNodeId.keySet()) {
                int inDeg = in.getOrDefault(waitId, 0);
                List<GraphPlan.Edge> normalEdges = rawOutgoing.getOrDefault(waitId, List.of());
                if (inDeg < 1 || normalEdges.size() != 1) {
                    throw new IllegalArgumentException(
                            "WAIT receiveTask requires in>=1 and out==1 normal sequenceFlow: "
                                    + waitId + " in=" + inDeg + " out=" + normalEdges.size());
                }
                BoundaryAttachment boundary = boundaryByHostWaitId.get(waitId);
                if (boundary != null) {
                    GraphPlan.Edge normalEdge = normalEdges.get(0);
                    GraphPlan.Edge timeoutEdge = rawOutgoing.get(boundary.id()).get(0);
                    if (normalEdge.to().equals(timeoutEdge.to())) {
                        throw new IllegalArgumentException(
                                "MESSAGE_WITH_TIMEOUT NORMAL and TIMEOUT branches cannot share target node: "
                                        + waitId + " -> " + normalEdge.to());
                    }
                }
            }
            for (String timerId : timerWaitByNodeId.keySet()) {
                int inDeg = in.getOrDefault(timerId, 0);
                List<GraphPlan.Edge> timerEdges = rawOutgoing.getOrDefault(timerId, List.of());
                if (inDeg < 1 || timerEdges.size() != 1) {
                    throw new IllegalArgumentException(
                            "Timer intermediateCatchEvent requires in>=1 and out==1 sequenceFlow: "
                                    + timerId + " in=" + inDeg + " out=" + timerEdges.size());
                }
            }

            Map<String,Integer> out=new HashMap<>();
            for(GraphPlan.Edge e:edges) { out.merge(e.from(),1,Integer::sum); }
            List<GraphPlan.Node> nodes=new ArrayList<>();
            boolean hasWaitNode = false;
            for(Map.Entry<String,Element> entry:elements.entrySet()) {
                String id=entry.getKey(); Element el=entry.getValue(); String type=el.getLocalName();
                NodeMetadata meta=input.nodeMetadata().get(id);
                String stage=blankToNull(el.getAttribute("stageCode"));
                Long workflowNodeId=meta==null?null:meta.workflowNodeId();
                if (waitCorrelationByNodeId.containsKey(id)) {
                    hasWaitNode = true;
                    String corr = waitCorrelationByNodeId.get(id);
                    BoundaryAttachment boundary = boundaryByHostWaitId.get(id);
                    GraphPlan.WaitSpec waitSpec = boundary == null
                            ? new GraphPlan.WaitSpec(GraphPlan.WaitKind.MESSAGE, corr, null, null)
                            : new GraphPlan.WaitSpec(
                                    GraphPlan.WaitKind.MESSAGE_WITH_TIMEOUT,
                                    corr,
                                    boundary.timer().durationIso(),
                                    boundary.timer().durationMillis());
                    String name=first(el.getAttribute("name"),id);
                    nodes.add(new GraphPlan.Node(id,name,GraphPlan.Kind.WAIT,type,
                            null,null,null,stage,null,workflowNodeId,waitSpec));
                    continue;
                }
                if (timerWaitByNodeId.containsKey(id)) {
                    hasWaitNode = true;
                    TimerSpec timer = timerWaitByNodeId.get(id);
                    GraphPlan.WaitSpec waitSpec = new GraphPlan.WaitSpec(
                            GraphPlan.WaitKind.TIMER, null, timer.durationIso(), timer.durationMillis());
                    String name=first(el.getAttribute("name"),id);
                    nodes.add(new GraphPlan.Node(id,name,GraphPlan.Kind.WAIT,type,
                            null,null,null,stage,null,workflowNodeId,waitSpec));
                    continue;
                }
                String protocol=blankToNull(el.getAttribute("protocolName"));
                String name=first(el.getAttribute("name"),protocol,id);
                GraphPlan.Kind kind=kind(type,protocol,el.getAttribute("gatewayDirection"),
                        in.getOrDefault(id,0),out.getOrDefault(id,0));
                Long resource=parseLong(el.getAttribute("resource"));
                String methodParams=blankToNull(el.getAttribute("methodParams"));
                nodes.add(new GraphPlan.Node(id,name,kind,type,protocol,resource,methodParams,stage,
                        meta==null?null:meta.capabilityCode(),workflowNodeId));
            }
            long starts=nodes.stream().filter(n->n.kind()==GraphPlan.Kind.START).count();
            if(starts!=1) throw new IllegalArgumentException("V1 requires exactly one startEvent; found "+starts);
            for(GraphPlan.Node n:nodes) {
                if (n.kind()==GraphPlan.Kind.START
                        && (in.getOrDefault(n.key(),0)!=0 || out.getOrDefault(n.key(),0)!=1))
                    throw new IllegalArgumentException("V1 startEvent requires zero incoming and exactly one outgoing sequenceFlow: "
                            +n.key()+" in="+in.getOrDefault(n.key(),0)+" out="+out.getOrDefault(n.key(),0));
                if(n.kind()==GraphPlan.Kind.TASK && n.protocol()==null)
                    throw new IllegalArgumentException("Device task has no protocolName: "+n.key());
                if(n.kind()==GraphPlan.Kind.TASK && n.resourceId()==null && n.capabilityCode()==null)
                    throw new IllegalArgumentException("Device task has neither resource binding nor capability metadata: "+n.key());
            }
            if (hasWaitNode) {
                rejectTimeoutWaitsInParallelOrInclusiveRegions(nodes, edges);
            }
            int schemaVersion = hasWaitNode ? GraphPlan.SCHEMA_VERSION_V2 : GraphPlan.SCHEMA_VERSION_V1;
            return new GraphPlan(schemaVersion,VERSION,input.definitionKey(),input.definitionVersion(),
                    GraphPlanCodec.sha256(input.bpmnXml()),nodes,edges);
        } catch(IllegalArgumentException e) { throw e; }
        catch(Exception e) { throw new IllegalArgumentException("BPMN compilation failed: "+e.getMessage(),e); }
    }

    private static boolean isAllowedTimerParent(Element el) {
        Node parentNode = el.getParentNode();
        if (!(parentNode instanceof Element parent)) return false;
        return BPMN_NS.equals(parent.getNamespaceURI())
                && ("intermediateCatchEvent".equals(parent.getLocalName())
                        || "boundaryEvent".equals(parent.getLocalName()));
    }

    private static void validateLabosExtensionPlacement(Element el) {
        if (!"wait".equals(el.getLocalName())) {
            throw new IllegalArgumentException("Unsupported LabOS extension element: " + el.getLocalName());
        }
        Node parentNode = el.getParentNode();
        Node grandParentNode = parentNode != null ? parentNode.getParentNode() : null;
        if (!(parentNode instanceof Element extEl)
                || !BPMN_NS.equals(extEl.getNamespaceURI())
                || !"extensionElements".equals(extEl.getLocalName())
                || !(grandParentNode instanceof Element taskEl)
                || !BPMN_NS.equals(taskEl.getNamespaceURI())
                || !"receiveTask".equals(taskEl.getLocalName())) {
            throw new IllegalArgumentException(
                    "labos:wait extension is only supported inside bpmn:receiveTask extensionElements");
        }
    }

    private static String extractWaitCorrelationKeyIfPresent(Element receiveTask, String id, NodeMetadata meta) {
        Element waitEl = findSingleLabosWaitElement(receiveTask, id);
        boolean hasTaskConfig = hasTaskExecutionConfig(receiveTask, meta);
        if (waitEl == null) {
            if (!hasTaskConfig) {
                throw new IllegalArgumentException(
                        "receiveTask requires either task configuration or labos:wait extension: " + id);
            }
            return null;
        }
        if (hasTaskConfig) {
            throw new IllegalArgumentException(
                    "receiveTask cannot combine labos:wait extension with task execution configuration: " + id);
        }
        validateLabosWaitAttributesAndChildren(waitEl, id);
        String corr = blankToNull(waitEl.getAttribute("correlationKey"));
        if (corr == null) {
            throw new IllegalArgumentException("labos:wait requires non-blank correlationKey on receiveTask: " + id);
        }
        if (corr.contains("${") || corr.contains("#{")) {
            throw new IllegalArgumentException("Dynamic correlationKey expression is not supported: " + corr);
        }
        return corr;
    }

    private static void validateLabosWaitAttributesAndChildren(Element waitEl, String ownerId) {
        NamedNodeMap attrs = waitEl.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr attr = (Attr) attrs.item(i);
            if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attr.getNamespaceURI())) {
                continue;
            }
            String attrName = attr.getLocalName() != null ? attr.getLocalName() : attr.getName();
            if (!"correlationKey".equals(attrName)
                    || (attr.getNamespaceURI() != null && !LABOS_NS.equals(attr.getNamespaceURI()))) {
                throw new IllegalArgumentException(
                        "Unsupported attribute on labos:wait (" + ownerId + "): " + attr.getName());
            }
        }
        NodeList children = waitEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                throw new IllegalArgumentException(
                        "labos:wait cannot contain child elements (" + ownerId + ")");
            }
            if ((child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE)
                    && child.getTextContent() != null && !child.getTextContent().isBlank()) {
                throw new IllegalArgumentException(
                        "labos:wait cannot contain text content (" + ownerId + ")");
            }
        }
    }

    private static Element findSingleLabosWaitElement(Element owner, String ownerId) {
        Element found = null;
        NodeList children = owner.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element ext)) continue;
            if (!BPMN_NS.equals(ext.getNamespaceURI()) || !"extensionElements".equals(ext.getLocalName())) continue;
            NodeList extKids = ext.getChildNodes();
            for (int j = 0; j < extKids.getLength(); j++) {
                if (!(extKids.item(j) instanceof Element child)) continue;
                if (LABOS_NS.equals(child.getNamespaceURI()) && "wait".equals(child.getLocalName())) {
                    if (found != null) {
                        throw new IllegalArgumentException(
                                "receiveTask has multiple labos:wait extensions: " + ownerId);
                    }
                    found = child;
                }
            }
        }
        return found;
    }

    private static void ensureNoReceiveWaitExtension(Element el, String id) {
        if (findSingleLabosWaitElement(el, id) != null) {
            throw new IllegalArgumentException(
                    "labos:wait extension is only supported on bpmn:receiveTask: " + id);
        }
    }

    private static void ensureNoTaskConfig(Element el, String id, NodeMetadata meta, String elementType) {
        if (hasTaskExecutionConfig(el, meta)) {
            throw new IllegalArgumentException(
                    elementType + " cannot carry task execution configuration: " + id);
        }
    }

    private static boolean hasTaskExecutionConfig(Element el, NodeMetadata meta) {
        return blankToNull(el.getAttribute("protocolName")) != null
                || blankToNull(el.getAttribute("resource")) != null
                || blankToNull(el.getAttribute("methodParams")) != null
                || (meta != null && blankToNull(meta.capabilityCode()) != null);
    }

    private static TimerSpec parseSingleTimerDefinition(Element owner, String ownerId) {
        Element timerDef = null;
        NodeList children = owner.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element child)) continue;
            if (BPMN_NS.equals(child.getNamespaceURI()) && "timerEventDefinition".equals(child.getLocalName())) {
                if (timerDef != null) {
                    throw new IllegalArgumentException(
                            owner.getLocalName() + " cannot declare multiple timerEventDefinition elements: " + ownerId);
                }
                timerDef = child;
            }
        }
        if (timerDef == null) {
            throw new IllegalArgumentException(
                    owner.getLocalName() + " requires exactly one timerEventDefinition: " + ownerId);
        }
        Element durationEl = null;
        NodeList defChildren = timerDef.getChildNodes();
        for (int i = 0; i < defChildren.getLength(); i++) {
            if (!(defChildren.item(i) instanceof Element child)) continue;
            if (!BPMN_NS.equals(child.getNamespaceURI()) || !"timeDuration".equals(child.getLocalName())) {
                throw new IllegalArgumentException(
                        "Only bpmn:timeDuration is supported inside timerEventDefinition: " + ownerId
                                + " (found " + child.getLocalName() + ")");
            }
            if (durationEl != null) {
                throw new IllegalArgumentException(
                        "timerEventDefinition cannot declare multiple timeDuration elements: " + ownerId);
            }
            durationEl = child;
        }
        if (durationEl == null) {
            throw new IllegalArgumentException(
                    "timerEventDefinition requires exactly one bpmn:timeDuration: " + ownerId);
        }
        NodeList durKids = durationEl.getChildNodes();
        for (int i = 0; i < durKids.getLength(); i++) {
            if (durKids.item(i) instanceof Element) {
                throw new IllegalArgumentException(
                        "bpmn:timeDuration cannot contain nested elements: " + ownerId);
            }
        }
        String durationIso = blankToNull(durationEl.getTextContent());
        if (durationIso == null) {
            throw new IllegalArgumentException("bpmn:timeDuration must not be empty: " + ownerId);
        }
        long millis = GraphPlan.parseStrictDurationMillis(durationIso);
        return new TimerSpec(durationIso, millis);
    }

    private static BoundaryAttachment parseBoundaryAttachment(
            String id,
            Element el,
            Map<String,Element> elements,
            Map<String,String> waitCorrelationByNodeId,
            Map<String,NodeMetadata> nodeMetadata) {
        ensureNoTaskConfig(el, id, nodeMetadata.get(id), "boundaryEvent");
        ensureNoReceiveWaitExtension(el, id);
        String cancelRaw = blankToNull(el.getAttribute("cancelActivity"));
        if (cancelRaw != null && !"true".equals(cancelRaw)) {
            throw new IllegalArgumentException(
                    "Only interrupting boundaryEvent (cancelActivity=\"true\") is supported: "
                            + id + " cancelActivity=" + cancelRaw);
        }
        String attachedToRef = blankToNull(el.getAttribute("attachedToRef"));
        if (attachedToRef == null) {
            throw new IllegalArgumentException("boundaryEvent requires attachedToRef: " + id);
        }
        Element hostEl = elements.get(attachedToRef);
        if (hostEl == null || !"receiveTask".equals(hostEl.getLocalName())
                || !waitCorrelationByNodeId.containsKey(attachedToRef)) {
            throw new IllegalArgumentException(
                    "Timer boundaryEvent can only attach to an explicit LabOS Wait receiveTask: "
                            + id + " attachedToRef=" + attachedToRef);
        }
        TimerSpec timer = parseSingleTimerDefinition(el, id);
        return new BoundaryAttachment(id, attachedToRef, timer);
    }

    private static String extractConditionExpression(Element el, String id) {
        String condition = null;
        boolean conditionFound = false;
        NodeList kids = el.getChildNodes();
        for (int k = 0; k < kids.getLength(); k++) {
            Node child = kids.item(k);
            if (child instanceof Element ce && "conditionExpression".equals(ce.getLocalName())) {
                if (!BPMN_NS.equals(ce.getNamespaceURI()))
                    throw new IllegalArgumentException("conditionExpression must use the BPMN namespace: " + id);
                if (conditionFound)
                    throw new IllegalArgumentException("sequenceFlow has multiple conditionExpression elements: " + id);
                conditionFound = true;
                condition = blankToNull(ce.getTextContent());
                if (condition == null)
                    throw new IllegalArgumentException("conditionExpression must not be empty: " + id);
            }
        }
        return condition;
    }

    private static void rejectTimeoutWaitsInParallelOrInclusiveRegions(
            List<GraphPlan.Node> nodes, List<GraphPlan.Edge> edges) {
        Map<String, GraphPlan.Node> nodeByKey = new HashMap<>(nodes.size());
        Map<String, List<String>> adj = new HashMap<>(nodes.size());
        for (GraphPlan.Node n : nodes) {
            nodeByKey.put(n.key(), n);
            adj.put(n.key(), new ArrayList<>());
        }
        for (GraphPlan.Edge e : edges) {
            adj.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e.to());
        }
        for (GraphPlan.Node split : nodes) {
            GraphPlan.Kind expectedJoinKind = switch (split.kind()) {
                case SPLIT_PARALLEL -> GraphPlan.Kind.JOIN_PARALLEL;
                case SPLIT_INCLUSIVE -> GraphPlan.Kind.JOIN_INCLUSIVE;
                default -> null;
            };
            if (expectedJoinKind == null) {
                continue;
            }
            Set<String> region = computeSplitRegion(split.key(), expectedJoinKind, nodeByKey, adj);
            for (String memberKey : region) {
                GraphPlan.Node member = nodeByKey.get(memberKey);
                if (member != null
                        && member.kind() == GraphPlan.Kind.WAIT
                        && member.waitSpec().waitKind() == GraphPlan.WaitKind.MESSAGE_WITH_TIMEOUT) {
                    throw new IllegalArgumentException(
                            "MESSAGE_WITH_TIMEOUT Wait is not allowed inside parallel/inclusive gateway region ("
                                    + split.key() + "): " + member.key());
                }
            }
        }
    }

    private static Set<String> computeSplitRegion(
            String splitKey,
            GraphPlan.Kind expectedJoinKind,
            Map<String, GraphPlan.Node> nodeByKey,
            Map<String, List<String>> adj) {
        Set<String> reachable = bfsAvoiding(splitKey, null, adj);
        Set<String> terminals = new HashSet<>();
        List<String> candidateJoins = new ArrayList<>();
        for (String key : reachable) {
            if (adj.getOrDefault(key, List.of()).isEmpty()) {
                terminals.add(key);
            }
            GraphPlan.Node n = nodeByKey.get(key);
            if (n != null && !key.equals(splitKey) && n.kind() == expectedJoinKind) {
                candidateJoins.add(key);
            }
        }
        if (terminals.isEmpty() || candidateJoins.isEmpty()) {
            return reachable;
        }
        Set<String> immediateRegion = null;
        for (String joinKey : candidateJoins) {
            Set<String> withoutJoin = bfsAvoiding(splitKey, joinKey, adj);
            if (Collections.disjoint(withoutJoin, terminals)) {
                if (immediateRegion == null || withoutJoin.size() < immediateRegion.size()) {
                    immediateRegion = withoutJoin;
                }
            }
        }
        return immediateRegion != null ? immediateRegion : reachable;
    }

    private static Set<String> bfsAvoiding(String start, String excluded, Map<String, List<String>> adj) {
        Set<String> visited = new LinkedHashSet<>();
        if (start.equals(excluded)) {
            return visited;
        }
        ArrayDeque<String> queue = new ArrayDeque<>();
        visited.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            for (String next : adj.getOrDefault(cur, List.of())) {
                if (next.equals(excluded) || !visited.add(next)) {
                    continue;
                }
                queue.add(next);
            }
        }
        return visited;
    }

    private static boolean isExecutable(String t) {
        return Set.of("startEvent","endEvent","task","userTask","serviceTask","scriptTask",
                "sendTask","receiveTask","intermediateCatchEvent","customDevice",
                "exclusiveGateway","parallelGateway","inclusiveGateway").contains(t)
                || t.startsWith("custom");
    }
    private static boolean isUnsupported(String t) {
        return Set.of("subProcess","callActivity","businessRuleTask","manualTask",
                "intermediateThrowEvent","eventBasedGateway","complexGateway",
                "transaction","adHocSubProcess").contains(t);
    }
    private static GraphPlan.Kind kind(String t,String protocol,String gatewayDirection,int in,int out) {
        if(t.equals("startEvent")) return GraphPlan.Kind.START;
        if(t.equals("endEvent")) return GraphPlan.Kind.END;
        if(t.endsWith("Gateway")) {
            boolean join=in>1, split=out>1;
            // V1 supports only canonical 1-in/N-out splits and N-in/1-out joins.
            // Mixed, pass-through, and zero-sided gateway shapes are rejected.
            if(join==split || (split && in!=1) || (join && out!=1))
                throw new IllegalArgumentException("Unsupported gateway shape; V1 requires exactly 1-in/N-out split or N-in/1-out join: "+t+" in="+in+" out="+out);
            String direction=blankToNull(gatewayDirection);
            if(direction==null) direction="Unspecified";
            switch(direction) {
                case "Unspecified" -> { /* infer from canonical topology below */ }
                case "Diverging" -> {
                    if(!split) throw new IllegalArgumentException("Gateway direction Diverging conflicts with topology: "+t+" in="+in+" out="+out);
                }
                case "Converging" -> {
                    if(!join) throw new IllegalArgumentException("Gateway direction Converging conflicts with topology: "+t+" in="+in+" out="+out);
                }
                case "Mixed" -> throw new IllegalArgumentException("Gateway direction Mixed is unsupported in V1: "+t);
                default -> throw new IllegalArgumentException("Unsupported gatewayDirection: "+direction+" on "+t);
            }
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
