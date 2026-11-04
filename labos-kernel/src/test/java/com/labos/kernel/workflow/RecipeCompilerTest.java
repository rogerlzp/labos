package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.workflow.RealWorkflowRunner.NodeDef;

import java.util.List;
import java.util.Map;

/**
 * 编译规则测试：什么该展开、什么不该。
 *
 * <p>核心规则：**已指定具体方法的节点不展开**——
 * 存量库 已落库的节点都已经落到具体方法（Deposit1/Transfer），
 * 再按能力展开会变成"放入培养箱 = 振荡+孵育+转运"这种荒谬结果。
 */
public final class RecipeCompilerTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        RecipeCompiler compiler = RecipeCompiler.load(DbConfig.fromEnv(), 1L);

        System.out.println("════ 编译规则测试 ════");
        System.out.println("模板: " + compiler.allRecipes().keySet());
        System.out.println();

        caseWithMethodNotExpanded(compiler);
        caseWithoutMethodExpanded(compiler);
        caseUnknownCapabilitySingleStep(compiler);
        caseLogicNodeSingleStep(compiler);
        caseReadPlateSevenSteps(compiler);

        System.out.println();
        System.out.printf("通过 %d, 失败 %d%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void caseWithMethodNotExpanded(RecipeCompiler c) {
        check("已指定方法（Deposit1）→ 单步", () -> {
            NodeDef n = node("Deposit1", "INCUBATE");
            var plan = c.compile(n, Map.of());
            eq("步数", 1, plan.stepCount());
            eq("方法", "Deposit1", plan.steps().get(0).method());
            eq("来自模板", false, plan.fromRecipe());
        });
    }

    private static void caseWithoutMethodExpanded(RecipeCompiler c) {
        check("未指定方法（INCUBATE）→ 展开 6 步", () -> {
            NodeDef n = node(null, "INCUBATE");
            var plan = c.compile(n, Map.of());
            eq("步数", 6, plan.stepCount());
            eq("来自模板", true, plan.fromRecipe());
            eq("首步", "StartShaking", plan.steps().get(0).method());
        });
    }

    private static void caseUnknownCapabilitySingleStep(RecipeCompiler c) {
        check("无模板能力（SENSE）→ 单步用能力码", () -> {
            NodeDef n = node(null, "SENSE");
            var plan = c.compile(n, Map.of());
            eq("步数", 1, plan.stepCount());
            eq("方法", "SENSE", plan.steps().get(0).method());
        });
    }

    private static void caseLogicNodeSingleStep(RecipeCompiler c) {
        check("逻辑节点（Delay）→ 单步", () -> {
            NodeDef n = node(null, "LOGIC");
            var plan = c.compile(n, Map.of());
            eq("步数", 1, plan.stepCount());
        });
    }

    private static void caseReadPlateSevenSteps(RecipeCompiler c) {
        check("未指定方法（READ_PLATE）→ 展开 7 步", () -> {
            NodeDef n = node(null, "READ_PLATE");
            var plan = c.compile(n, Map.of());
            eq("步数", 7, plan.stepCount());
            eq("首步", "OpenDoor", plan.steps().get(0).method());
            eq("末步", "CloseDoor", plan.steps().get(6).method());
            checkTrue("样本数 >= 100", plan.sampleCount() >= 100);
        });
    }

    // ---------------------------------------------------------------- 工具

    /** 构造一个已标能力的节点（protocolName 可为 null）。 */
    private static NodeDef node(String protocolName, String capability) {
        return new NodeDef(1L, "T", "测试节点", "task", capability,
                protocolName, 10, null);
    }

    private static void check(String name, Runnable body) {
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
        if (!String.valueOf(expected).equals(String.valueOf(actual))) {
            throw new AssertionError(what + " 期望 " + expected + " 实际 " + actual);
        }
    }

    private static void checkTrue(String what, boolean cond) {
        if (!cond) {
            throw new AssertionError(what + " 不成立");
        }
    }

    private RecipeCompilerTest() {
    }
}
