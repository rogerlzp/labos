package com.labos.kernel.material;

import java.util.Objects;

/**
 * 物料声明 JSON 解析 纯逻辑自测（不连库）。
 *
 * <p>锁住 mode / target.plateId / set.processState / set.sealState 的解析、
 * 非法 JSON 的兜底（按无声明处理，不抛），以及 {@code TargetStates.merge} 的
 * 「未写字段保持现值」语义（applyEffect 与 manualStateChange 共用的运行时逻辑，
 * 事务内行为本自测覆盖不到，只能锁住这段纯函数）。
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

        section("4. target 目标解析字段");
        String byBarcode = "{\"mode\":\"transition\",\"target\":{\"barcode\":\"PLT-1\"}}";
        eq("barcode", "PLT-1", MaterialEffectService.targetBarcode(byBarcode));
        eq("无 barcode → null", null,
                MaterialEffectService.targetBarcode("{\"mode\":\"transition\",\"target\":{}}"));
        String byLocation = "{\"mode\":\"transition\",\"target\":{\"location\":{\"resourceId\":7}}}";
        eq("location.resourceId", 7L, MaterialEffectService.targetResourceId(byLocation));
        eq("无 location → null", null,
                MaterialEffectService.targetResourceId("{\"mode\":\"transition\",\"target\":{}}"));

        section("5. 目标态合并（未写字段保持现值）");
        var m = MaterialEffectService.TargetStates.merge(
                "in_progress", "sealed", "completed", null);
        eq("只改 process：seal 保持 sealed（seal bug 回归用例）", "sealed", m.sealState());
        eq("只改 process：process 覆盖", "completed", m.processState());

        var s = MaterialEffectService.TargetStates.merge(
                "in_progress", "none", null, "sealed");
        eq("只改 seal：process 保持", "in_progress", s.processState());
        eq("只改 seal：seal 覆盖", "sealed", s.sealState());

        var clr = MaterialEffectService.TargetStates.merge(
                "completed", "sealed", null, "none");
        eq("sealState=none 是显式清膜，不是「没写」", "none", clr.sealState());
        eq("显式清膜时 process 保持", "completed", clr.processState());

        var both = MaterialEffectService.TargetStates.merge(
                "raw", null, "completed", "sealed");
        eq("两者都写：process 覆盖", "completed", both.processState());
        eq("两者都写：seal 覆盖", "sealed", both.sealState());

        var keep = MaterialEffectService.TargetStates.merge(
                "completed", "sealed", null, null);
        eq("都没写：process 保持", "completed", keep.processState());
        eq("都没写：seal 保持", "sealed", keep.sealState());

        var empty = MaterialEffectService.TargetStates.merge(null, null, "raw", null);
        eq("现值 NULL 且未写 seal：仍为 NULL", null, empty.sealState());
        eq("现值 NULL 但写了 process：正常覆盖", "raw", empty.processState());

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
