package com.labos.kernel.workflow;

import com.labos.kernel.workflow.PreflightChecker.Issue;
import com.labos.kernel.workflow.WorkflowGraphParser.Graph;

import java.util.List;

/**
 * 网关**结构**预检（PF-15/16/17/18）的**纯逻辑**自测。
 *
 * <p>为什么能不连库测：这段检查只看**图**（连了几条线、能不能走到汇合点），
 * 不碰数据库 ⇒ 进纯逻辑轨，不占库。
 *
 * <p>为什么要有这组检查：用户 2026-11-11 指出「并行网关、异或网关、条件网关
 * 应该都有开始和结束网关成对出现，现在拖出来只有一个，看不清结构」。
 * {@code docs/测试总纲.md} 规则 5/6/7 早就写了这些要求，但只停在文档里 ——
 * 实现层从没强制过，用户拖一堆网关出来系统一声不吭全收下。
 *
 * <p>覆盖：
 * <ol>
 *   <li>成对的并行分叉/汇合 ⇒ 无告警（这是"正常"的样子，别误报）；</li>
 *   <li>1 进 1 出的网关 ⇒ PF-15；</li>
 *   <li>并行分叉缺汇合 ⇒ PF-16；</li>
 *   <li>并行汇合缺分叉 ⇒ PF-17；</li>
 *   <li>条件网关出边没条件 ⇒ PF-18；</li>
 *   <li>异或分叉收口到 end ⇒ **不报** PF-16（异或汇合是"任一条到达即放行"）。</li>
 * </ol>
 */
public final class PreflightGatewayStructureSelfTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        System.out.println("════ 网关结构预检 · 纯逻辑自测 ════");
        System.out.println();

        pairedParallelIsClean();
        straightThroughIsPf15();
        forkWithoutJoinIsPf16();
        joinWithoutForkIsPf17();
        conditionalWithoutConditionIsPf18();
        conditionalPairedIsClean();
        isolatedGatewayIsPf19();
        xorToEndIsNotPf16();

        System.out.println();
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 用例

    /** 成对的并行分叉/汇合 ⇒ 无告警。 */
    private static void pairedParallelIsClean() {
        check("成对的并行分叉/汇合：无告警", () -> {
            List<Issue> issues = issues("""
                    <startEvent id="S"/>
                    <parallelGateway id="SPLIT"/>
                    <task id="A" name="Transfer"/>
                    <task id="B" name="Transfer"/>
                    <parallelGateway id="JOIN"/>
                    <task id="C" name="Transfer"/>
                    <endEvent id="E"/>
                    <sequenceFlow id="F1" sourceRef="S" targetRef="SPLIT"/>
                    <sequenceFlow id="F2" sourceRef="SPLIT" targetRef="A"/>
                    <sequenceFlow id="F3" sourceRef="SPLIT" targetRef="B"/>
                    <sequenceFlow id="F4" sourceRef="A" targetRef="JOIN"/>
                    <sequenceFlow id="F5" sourceRef="B" targetRef="JOIN"/>
                    <sequenceFlow id="F6" sourceRef="JOIN" targetRef="C"/>
                    <sequenceFlow id="F7" sourceRef="C" targetRef="E"/>
                    """);
            eq("告警条数", 0, issues.size());
        });
    }

    /** 1 进 1 出 = 直通 ⇒ PF-15。 */
    private static void straightThroughIsPf15() {
        check("1 进 1 出的网关：报 PF-15", () -> {
            List<Issue> issues = issues("""
                    <startEvent id="S"/>
                    <exclusiveGateway id="G"/>
                    <task id="A" name="Transfer"/>
                    <endEvent id="E"/>
                    <sequenceFlow id="F1" sourceRef="S" targetRef="G"/>
                    <sequenceFlow id="F2" sourceRef="G" targetRef="A"/>
                    <sequenceFlow id="F3" sourceRef="A" targetRef="E"/>
                    """);
            eq("告警条数", 1, issues.size());
            eq("告警码", "PF-15", issues.get(0).code());
            eq("级别", PreflightChecker.Level.WARN, issues.get(0).level());
        });
    }

    /** 并行分叉的分支收不到汇合 ⇒ PF-16。 */
    private static void forkWithoutJoinIsPf16() {
        check("并行分叉没有汇合点：报 PF-16", () -> {
            List<Issue> issues = issues("""
                    <startEvent id="S"/>
                    <parallelGateway id="SPLIT"/>
                    <task id="A" name="Transfer"/>
                    <task id="B" name="Transfer"/>
                    <endEvent id="E"/>
                    <sequenceFlow id="F1" sourceRef="S" targetRef="SPLIT"/>
                    <sequenceFlow id="F2" sourceRef="SPLIT" targetRef="A"/>
                    <sequenceFlow id="F3" sourceRef="SPLIT" targetRef="B"/>
                    <sequenceFlow id="F4" sourceRef="A" targetRef="E"/>
                    <sequenceFlow id="F5" sourceRef="B" targetRef="E"/>
                    """);
            checkTrue("报了 PF-16", codes(issues).contains("PF-16"));
        });
    }

    /** 孤立的并行汇合 ⇒ PF-17。 */
    private static void joinWithoutForkIsPf17() {
        check("并行汇合找不到同族分叉：报 PF-17", () -> {
            List<Issue> issues = issues("""
                    <startEvent id="S"/>
                    <task id="A" name="Transfer"/>
                    <task id="B" name="Transfer"/>
                    <parallelGateway id="JOIN"/>
                    <task id="C" name="Transfer"/>
                    <endEvent id="E"/>
                    <sequenceFlow id="F1" sourceRef="S" targetRef="A"/>
                    <sequenceFlow id="F2" sourceRef="A" targetRef="JOIN"/>
                    <sequenceFlow id="F3" sourceRef="B" targetRef="JOIN"/>
                    <sequenceFlow id="F4" sourceRef="JOIN" targetRef="C"/>
                    <sequenceFlow id="F5" sourceRef="C" targetRef="E"/>
                    """);
            checkTrue("报了 PF-17", codes(issues).contains("PF-17"));
        });
    }

    /** 条件网关出边没条件 ⇒ PF-18。 */
    private static void conditionalWithoutConditionIsPf18() {
        check("条件网关出边没写条件：报 PF-18", () -> {
            List<Issue> issues = issues("""
                    <startEvent id="S"/>
                    <inclusiveGateway id="COND"/>
                    <task id="A" name="Transfer"/>
                    <task id="B" name="Transfer"/>
                    <inclusiveGateway id="JOIN"/>
                    <endEvent id="E"/>
                    <sequenceFlow id="F1" sourceRef="S" targetRef="COND"/>
                    <sequenceFlow id="F2" sourceRef="COND" targetRef="A"/>
                    <sequenceFlow id="F3" sourceRef="COND" targetRef="B"/>
                    <sequenceFlow id="F4" sourceRef="A" targetRef="JOIN"/>
                    <sequenceFlow id="F5" sourceRef="B" targetRef="JOIN"/>
                    <sequenceFlow id="F6" sourceRef="JOIN" targetRef="E"/>
                    """);
            checkTrue("报了 PF-18", codes(issues).contains("PF-18"));
        });
    }

    /** 成对的条件网关（出边都有条件）⇒ 无告警。 */
    private static void conditionalPairedIsClean() {
        check("成对且带条件的条件网关：无告警", () -> {
            List<Issue> issues = issues("""
                    <startEvent id="S"/>
                    <inclusiveGateway id="COND"/>
                    <task id="A" name="Transfer"/>
                    <task id="B" name="Transfer"/>
                    <inclusiveGateway id="JOIN"/>
                    <task id="C" name="Transfer"/>
                    <endEvent id="E"/>
                    <sequenceFlow id="F1" sourceRef="S" targetRef="COND"/>
                    <sequenceFlow id="F2" sourceRef="COND" targetRef="A">
                      <conditionExpression>${a} == 'yes'</conditionExpression>
                    </sequenceFlow>
                    <sequenceFlow id="F3" sourceRef="COND" targetRef="B">
                      <conditionExpression>${b} == 'yes'</conditionExpression>
                    </sequenceFlow>
                    <sequenceFlow id="F4" sourceRef="A" targetRef="JOIN"/>
                    <sequenceFlow id="F5" sourceRef="B" targetRef="JOIN"/>
                    <sequenceFlow id="F6" sourceRef="JOIN" targetRef="C"/>
                    <sequenceFlow id="F7" sourceRef="C" targetRef="E"/>
                    """);
            eq("告警条数", 0, issues.size());
        });
    }

    /** 孤立的网关（一条线都没连）⇒ PF-19。 */
    private static void isolatedGatewayIsPf19() {
        check("孤立的网关（没连任何线）：报 PF-19", () -> {
            List<Issue> issues = issues("""
                    <startEvent id="S"/>
                    <exclusiveGateway id="G"/>
                    <task id="A" name="Transfer"/>
                    <endEvent id="E"/>
                    <sequenceFlow id="F1" sourceRef="S" targetRef="A"/>
                    <sequenceFlow id="F2" sourceRef="A" targetRef="E"/>
                    """);
            eq("告警条数", 1, issues.size());
            eq("告警码", "PF-19", issues.get(0).code());
        });
    }

    /** 异或分叉收口到 end ⇒ 不报 PF-16（异或汇合是"任一条到达即放行"）。 */
    private static void xorToEndIsNotPf16() {
        check("异或分叉收口到 end：不报 PF-16", () -> {
            List<Issue> issues = issues("""
                    <startEvent id="S"/>
                    <exclusiveGateway id="XOR"/>
                    <task id="A" name="Transfer"/>
                    <task id="B" name="Transfer"/>
                    <endEvent id="E"/>
                    <sequenceFlow id="F1" sourceRef="S" targetRef="XOR"/>
                    <sequenceFlow id="F2" sourceRef="XOR" targetRef="A"/>
                    <sequenceFlow id="F3" sourceRef="XOR" targetRef="B"/>
                    <sequenceFlow id="F4" sourceRef="A" targetRef="E"/>
                    <sequenceFlow id="F5" sourceRef="B" targetRef="E"/>
                    """);
            checkTrue("没有 PF-16", !codes(issues).contains("PF-16"));
            checkTrue("没有 PF-17", !codes(issues).contains("PF-17"));
        });
    }

    // ---------------------------------------------------------------- 工具

    private static List<Issue> issues(String body) {
        Graph g = WorkflowGraphParser.parse(bpmn(body));
        return PreflightChecker.checkGatewayStructure(g);
    }

    private static List<String> codes(List<Issue> issues) {
        return issues.stream().map(Issue::code).toList();
    }

    private static String bpmn(String body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             id="D1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <process id="P1" isExecutable="false">
                %s
                  </process>
                </definitions>
                """.formatted(body);
    }

    private static void check(String name, Runnable body) {
        try {
            body.run();
            pass++;
            System.out.printf("  ✔ %s%n", name);
        } catch (AssertionError e) {
            fail++;
            System.out.printf("  ✘ %s%n     %s%n", name, e.getMessage());
        }
    }

    private static void eq(String what, Object expected, Object actual) {
        if (!String.valueOf(expected).equals(String.valueOf(actual))) {
            throw new AssertionError(what + " 期望 " + expected + " 实际 " + actual);
        }
    }

    private static void checkTrue(String what, boolean cond) {
        if (!cond) {
            throw new AssertionError(what + " 不成立");
        }
    }

    private PreflightGatewayStructureSelfTest() {
    }
}
