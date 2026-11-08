package com.labos.kernel.workflow;

import java.util.List;
import java.util.Set;

/**
 * N-10 影响闭包纯逻辑自测（ADR-FAULT-SCOPE-002）。
 *
 * <p>同一实验涉及的**全部 Workcell** 中任一 Workcell 有扣留，整个实验都要判
 * HELD；不在该实验内的 Workcell 扣留不能误伤；experiment/site 级语义不变。
 */
public final class ExperimentFaultScopeSelfTest {

    private static int pass;
    private static int fail;

    private static final long EXP = 7L;
    private static final Set<Long> ISLANDS = Set.of(11L, 22L);

    public static void main(String[] args) {
        testIslandInScopeBlocksWholeExperiment();
        testIslandOutOfScopeDoesNotBlock();
        testExperimentHoldStillWins();
        testSiteHoldStillBlocks();
        testNoHoldClear();
        testPriorityExperimentOverIsland();
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void testIslandInScopeBlocksWholeExperiment() {
        RuntimeHold.Coverage c = cover(List.of(
                hold("island", 22L, "manual", "B 岛暂停")));
        check("实验所属 Workcell（22）扣留 ⇒ 整实验 HELD",
                c.state() == RuntimeHold.HoldState.HELD, c.state());
        check("命中原因可追溯（B 岛暂停）",
                c.hold() != null && "B 岛暂停".equals(c.hold().reason()),
                c.hold());
    }

    private static void testIslandOutOfScopeDoesNotBlock() {
        RuntimeHold.Coverage c = cover(List.of(
                hold("island", 99L, "manual", "无关岛")));
        check("不在实验内的 Workcell 扣留不误伤", c.state() == RuntimeHold.HoldState.CLEAR,
                c.state());
    }

    private static void testExperimentHoldStillWins() {
        RuntimeHold.Coverage c = cover(List.of(
                hold("experiment", EXP, "manual", "实验暂停")));
        check("experiment 级扣留仍覆盖", c.state() == RuntimeHold.HoldState.HELD, c.state());
    }

    private static void testSiteHoldStillBlocks() {
        RuntimeHold.Coverage c = cover(List.of(
                hold("site", null, "fault", "现场故障")));
        check("site 级扣留仍覆盖", c.state() == RuntimeHold.HoldState.HELD, c.state());
    }

    private static void testNoHoldClear() {
        check("无扣留 ⇒ CLEAR",
                cover(List.of()).state() == RuntimeHold.HoldState.CLEAR, "CLEAR");
    }

    private static void testPriorityExperimentOverIsland() {
        RuntimeHold.Coverage c = cover(List.of(
                hold("island", 11L, "manual", "A 岛暂停"),
                hold("experiment", EXP, "manual", "实验暂停")));
        check("实验级优先于 Workcell 级（留痕用实验级）",
                c.hold() != null && "experiment".equals(c.hold().scope()),
                c.hold());
    }

    private static RuntimeHold.Coverage cover(List<RuntimeHold.Active> active) {
        return RuntimeHold.coverageForExperiment(active, EXP, ISLANDS);
    }

    private static RuntimeHold.Active hold(String scope, Long scopeId, String kind, String why) {
        return new RuntimeHold.Active(scope, scopeId, kind, why);
    }

    private static void check(String name, boolean ok, Object actual) {
        if (ok) {
            pass++;
            System.out.println("  ✓ " + name);
        } else {
            fail++;
            System.out.println("  ✗ " + name + "（实际：" + actual + "）");
        }
    }
}
