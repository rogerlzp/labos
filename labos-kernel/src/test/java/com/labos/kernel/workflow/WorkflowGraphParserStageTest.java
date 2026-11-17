package com.labos.kernel.workflow;

/**
 * 阶段编码（P3）在解析层的验收。
 *
 * <p>为什么值得单独测：`stageCode` 走的是 BPMN 的**自定义属性**，不是标准字段。
 * 解析器一旦把它当未知属性丢掉，流程照常能跑、日志也不报错 —— 但设计器里分的阶段
 * 会在下次保存时全部变空，甘特/看板随之失去聚合维度。这类"静默丢字段"只能靠断言守住。
 */
public final class WorkflowGraphParserStageTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        run("带 stageCode 的节点解析出阶段", WorkflowGraphParserStageTest::parsesStage);
        run("没有 stageCode 时为 null（不是空串）", WorkflowGraphParserStageTest::missingIsNull);
        run("阶段不影响边与拓扑", WorkflowGraphParserStageTest::topologyUnchanged);
        System.out.printf("%n  WorkflowGraphParserStageTest  通过 %d, 失败 %d%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void parsesStage() {
        eq("阶段编码", "S4", nodeA("stageCode=\"S4\"").stageCode());
    }

    private static void missingIsNull() {
        eq("缺省为 null", null, nodeA("").stageCode());
    }

    /** 阶段是 store 侧的属性（ParsedNode），不在执行图 GNode 上 —— 这里断言的是落库路径。 */
    private static BpmnGraphParser.ParsedNode nodeA(String extraAttr) {
        return BpmnGraphParser.parse(xml(extraAttr)).nodes().stream()
                .filter(n -> "A".equals(n.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("节点 A 不存在"));
    }

    private static void topologyUnchanged() {
        WorkflowGraphParser.Graph g = WorkflowGraphParser.parse(xml("stageCode=\"S7\""));
        eq("节点数", 4, g.nodes().size());
        eq("边数", 3, g.edges().size());
        checkTrue("A 的出边指向 B", g.out("A").stream().anyMatch(e -> "B".equals(e.to())));
    }

    private static String xml(String extraAttr) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <bpmn:process id="P1" isExecutable="true">
                    <bpmn:startEvent id="S"/>
                    <bpmn:task id="A" name="Run" protocolName="Run" resource="200" %s/>
                    <bpmn:task id="B" name="Run" protocolName="Run" resource="300"/>
                    <bpmn:endEvent id="E"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="S" targetRef="A"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="A" targetRef="B"/>
                    <bpmn:sequenceFlow id="f3" sourceRef="B" targetRef="E"/>
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(extraAttr);
    }

    private static void run(String name, Runnable body) {
        try {
            body.run();
            passed++;
            System.out.printf("  ✔ %s%n", name);
        } catch (AssertionError e) {
            failed++;
            System.out.printf("  ✘ %s%n     %s%n", name, e.getMessage());
        }
    }

    private static void eq(String what, Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(what + " 期望 " + expected + " 实际 " + actual);
        }
    }

    private static void checkTrue(String what, boolean cond) {
        if (!cond) {
            throw new AssertionError(what + " 不成立");
        }
    }

    private WorkflowGraphParserStageTest() {
    }
}
