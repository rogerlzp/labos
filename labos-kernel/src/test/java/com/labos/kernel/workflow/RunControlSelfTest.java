package com.labos.kernel.workflow;

/**
 * 实验级运行控制 纯逻辑自测（不连库）。
 *
 * <p>锁住两条"按错了会出事"的判断：
 * <ol>
 *   <li><b>作用域退化</b>：操作员按了"暂停"，但实验还没分配岛（{@code islandId=0}）。
 *       此时岛级扣留匹配不到任何实验（覆盖查询要求 {@code e.island_id = scope_id}），
 *       必须退化成<b>实验级</b>，否则现场以为自己停住了、实际什么都没停。</li>
 *   <li><b>site 不解除</b>：{@code site} 是全场闸门（公共气路故障这类事故），
 *       不能由一次单实验的"恢复"顺手解除整场扣留 —— 必须如实告知"仍被现场扣留"。</li>
 * </ol>
 */
public final class RunControlSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        section("1. 实际生效范围（岛未分配 -> 退化为实验级）");
        scope("site 请求：保持全场", RuntimeHold.Scope.SITE, 7L, RuntimeHold.Scope.SITE);
        scope("site 请求、无岛：仍是全场", RuntimeHold.Scope.SITE, 0L, RuntimeHold.Scope.SITE);
        scope("island 请求、有岛：岛级", RuntimeHold.Scope.ISLAND, 7L, RuntimeHold.Scope.ISLAND);
        scope("island 请求、无岛(0)：退化为实验级",
                RuntimeHold.Scope.ISLAND, 0L, RuntimeHold.Scope.EXPERIMENT);
        scope("experiment 请求、有岛：实验级",
                RuntimeHold.Scope.EXPERIMENT, 7L, RuntimeHold.Scope.EXPERIMENT);
        scope("experiment 请求、无岛：实验级",
                RuntimeHold.Scope.EXPERIMENT, 0L, RuntimeHold.Scope.EXPERIMENT);

        section("2. 恢复能否顺手解除扣留（site 不解除）");
        clearable("实验级扣留：可解除", RuntimeHold.Scope.EXPERIMENT.code(), true);
        clearable("岛级扣留：可解除", RuntimeHold.Scope.ISLAND.code(), true);
        clearable("全场扣留：不可解除（须先解现场）", RuntimeHold.Scope.SITE.code(), false);
        clearable("未知范围：保守按不可信处理，非 site 仍可解除", "weird", true);

        System.out.printf("%n通过 %d, 失败 %d%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void scope(String name, RuntimeHold.Scope requested, long islandId,
                              RuntimeHold.Scope expect) {
        RuntimeHold.Scope got = RunControl.effectiveScope(requested, islandId);
        if (got == expect) {
            passed++;
            System.out.printf("  ✓ %-38s %s%n", name, got.code());
        } else {
            failed++;
            System.out.printf("  ✗ %-38s 期望 %s，实际 %s%n", name, expect.code(), got.code());
        }
    }

    private static void clearable(String name, String scopeCode, boolean expect) {
        boolean got = RunControl.clearableByExperimentResume(scopeCode);
        if (got == expect) {
            passed++;
            System.out.printf("  ✓ %-38s %s%n", name, got);
        } else {
            failed++;
            System.out.printf("  ✗ %-38s 期望 %s，实际 %s%n", name, expect, got);
        }
    }

    private static void section(String title) {
        System.out.println("\n── " + title + " ──");
    }

    private RunControlSelfTest() {
    }
}
