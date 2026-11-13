package com.labos.kernel.driver;

import java.util.Map;
import java.util.Objects;

/**
 * 板号单一真源解析器（{@link PositionLock#resolvePlateBarcode}）的纯逻辑自测，不连库。
 *
 * <p>锁住三条容易回归的契约：① 搬运事务与位置链路读同一批别名；
 * ② 数值 plate.id / 未替换的 ${...} / AUTO- 占位一律拒绝（否则会造出假板或串账）；
 * ③ 旧的 {@code resolvePlateId} 仍返回 AUTO- 占位，但自动确认链路不得用它。
 */
public final class PlateBarcodeSelfTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        section("1. 权威键与别名");
        eq("barcode", "PLT-1", r(Map.of("barcode", "PLT-1")));
        eq("plate", "PLT-2", r(Map.of("plate", "PLT-2")));
        eq("plateBarcode", "PLT-3", r(Map.of("plateBarcode", "PLT-3")));
        eq("plateId", "PLT-4", r(Map.of("plateId", "PLT-4")));
        eq("plateID", "PLT-5", r(Map.of("plateID", "PLT-5")));
        eq("plate_id", "PLT-6", r(Map.of("plate_id", "PLT-6")));
        eq("首尾空白被 trim", "PLT-7", r(Map.of("barcode", "  PLT-7  ")));

        section("2. 优先级");
        eq("barcode 优先于 plate", "A", r(Map.of("barcode", "A", "plate", "B")));
        eq("plate 优先于 plate_id", "B", r(Map.of("plate", "B", "plate_id", "C")));

        section("3. 拒绝非法身份");
        eq("数值 plate.id 拒绝", null, r(Map.of("plateId", 123L)));
        eq("数值别名被跳过后继续找合法别名", "OK",
                r(Map.of("barcode", 123L, "plate", "OK")));
        eq("未替换的 ${} 变量拒绝", null, r(Map.of("barcode", "${mainPlate}")));
        eq("AUTO- 占位拒绝", null, r(Map.of("barcode", "AUTO-1001389")));
        eq("空白串拒绝", null, r(Map.of("barcode", "   ")));
        eq("空 map → null", null, r(Map.of()));
        eq("null map → null", null, PositionLock.resolvePlateBarcode(null, null));

        section("4. rawResponse 回退");
        eq("回包 barcode", "DEV-1",
                PositionLock.resolvePlateBarcode(null, "{\"barcode\":\"DEV-1\"}"));
        eq("回包 plateBarcode", "DEV-2",
                PositionLock.resolvePlateBarcode(null, "{\"plateBarcode\":\"DEV-2\"}"));
        eq("回包 AUTO- 拒绝", null,
                PositionLock.resolvePlateBarcode(null, "{\"barcode\":\"AUTO-9\"}"));
        eq("params 优先于回包", "P",
                PositionLock.resolvePlateBarcode(Map.of("plate", "P"), "{\"barcode\":\"D\"}"));

        section("5. 旧 resolvePlateId 兼容");
        eq("有真板号不兜底", "PLT-1",
                PositionLock.resolvePlateId(Map.of("barcode", "PLT-1"), null, 42L));
        eq("解析不到兜底 AUTO-<runId>", "AUTO-42",
                PositionLock.resolvePlateId(Map.of(), null, 42L));
        eq("AUTO- 占位也走兜底", "AUTO-42",
                PositionLock.resolvePlateId(Map.of("plate", "AUTO-9"), null, 42L));

        section("6. 自动确认开关（按 workflow，默认关）");
        eq("未设置 → 关", false, PositionLock.parseAutoConfirmScope(null, 1000830L));
        eq("空白 → 关", false, PositionLock.parseAutoConfirmScope("  ", 1000830L));
        eq("1 → 全局开", true, PositionLock.parseAutoConfirmScope("1", 999L));
        eq("true → 全局开", true, PositionLock.parseAutoConfirmScope("true", 999L));
        eq("* → 全局开", true, PositionLock.parseAutoConfirmScope("*", 999L));
        eq("列表命中", true,
                PositionLock.parseAutoConfirmScope("1000830,1000831", 1000830L));
        eq("列表不命中（其它 22 个流程不被阻断）", false,
                PositionLock.parseAutoConfirmScope("1000830", 1000661L));
        eq("带空格的列表", true,
                PositionLock.parseAutoConfirmScope(" 1000830 , 1000831 ", 1000831L));

        section("7. 临时板号身份（P3）");
        eq("TMP- 是合法临时身份", "TMP-1001392-1000025-1",
                r(Map.of("barcode", "TMP-1001392-1000025-1")));
        eq("按 run+点位+序号生成", "TMP-9-7-2",
                com.labos.kernel.material.PlateIdentity.newTempBarcode(9L, 7L, 2));
        eq("不同点位不会共用身份", false,
                com.labos.kernel.material.PlateIdentity.newTempBarcode(9L, 7L, 1)
                        .equals(com.labos.kernel.material.PlateIdentity
                                .newTempBarcode(9L, 8L, 1)));
        eq("AUTO- 仍被拒绝（同一个假身份）", null, r(Map.of("barcode", "AUTO-9")));

        summary();
    }

    private static String r(Map<String, Object> m) {
        return PositionLock.resolvePlateBarcode(m, null);
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
            throw new RuntimeException("PlateBarcodeSelfTest 有失败用例");
        }
    }

    private PlateBarcodeSelfTest() {
    }
}
