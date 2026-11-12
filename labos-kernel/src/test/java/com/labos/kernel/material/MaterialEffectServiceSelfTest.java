package com.labos.kernel.material;

import java.util.Objects;

/**
 * 物料声明 JSON 解析 纯逻辑自测（不连库）。
 *
 * <p>锁住 mode / target.plateId / set.processState / set.sealState 的解析，
 * 以及非法 JSON 的兜底（按无声明处理，不抛）。
 */
public final class MaterialEffectServiceSelfTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        String transition = "{\"mode\":\"transition\",\"target\":{\"plateId\":123},"
                + "\"set\":{\"processState\":\"completed\",\"sealState\":\"sealed\"}}";

        section("1. mode");
        eq("transition", "transition", MaterialEffectService.modeOf(transition));
        eq("缺 mode 视为 none", "none", MaterialEffectService.modeOf("{\"target\":{}}"));
        eq("非法 JSON 视为 none", "none", MaterialEffectService.modeOf("not-json"));

        section("2. target.plateId");
        eq("显式 plateId", 123L, MaterialEffectService.targetPlateId(transition));
        eq("无 plateId → null", null,
                MaterialEffectService.targetPlateId("{\"mode\":\"transition\",\"target\":{}}"));
        eq("非法 JSON → null", null, MaterialEffectService.targetPlateId("x"));

        section("3. set 期望态");
        eq("processState", "completed", MaterialEffectService.expectedProcessState(transition));
        eq("sealState", "sealed", MaterialEffectService.expectedSealState(transition));
        eq("缺 set → null", null,
                MaterialEffectService.expectedProcessState("{\"mode\":\"transition\"}"));
        eq("缺 set → null(seal)", null,
                MaterialEffectService.expectedSealState("{\"mode\":\"transition\"}"));

        summary();
    }

    private static void section(String title) {
        System.out.println("\n── " + title + " ──");
    }

    private static void eq(String name, Object expect, Object got) {
        if (Objects.equals(expect, got)) {
            pass++;
            System.out.printf("  ✓ %s%n", name);
        } else {
            fail++;
            System.out.printf("  ✗ %s（期望 %s，实际 %s）%n", name, expect, got);
        }
    }

    private static void summary() {
        System.out.printf("%n通过 %d, 失败 %d%n", pass, fail);
        if (fail != 0) {
            throw new RuntimeException("MaterialEffectServiceSelfTest 有失败用例");
        }
    }

    private MaterialEffectServiceSelfTest() {
    }
}
