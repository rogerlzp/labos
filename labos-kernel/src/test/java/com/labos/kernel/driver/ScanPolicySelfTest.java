package com.labos.kernel.driver;

import java.util.Objects;

/**
 * P2-7 · 扫码校验（B6）纯逻辑自测，不连库。
 *
 * <p>锁住五值策略的判定契约：none/read/update 不校验；validate_in 只拦进；
 * validate_both 进出都拦；该拦却没扫到码视为不匹配（fail-closed）；
 * 没有期望值时不误拦（放行）。
 */
public final class ScanPolicySelfTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        section("1. 五值策略方向");
        eq("none 进不校验", false, ScanPolicy.validateOnEnter("none"));
        eq("read 进不校验", false, ScanPolicy.validateOnEnter("read"));
        eq("update 进不校验", false, ScanPolicy.validateOnEnter("update"));
        eq("validate_in 进校验", true, ScanPolicy.validateOnEnter("validate_in"));
        eq("validate_both 进校验", true, ScanPolicy.validateOnEnter("validate_both"));
        eq("validate_in 出不校验", false, ScanPolicy.validateOnExit("validate_in"));
        eq("validate_both 出校验", true, ScanPolicy.validateOnExit("validate_both"));
        eq("null 策略进不校验", false, ScanPolicy.validateOnEnter(null));

        section("2. 不匹配判定");
        eq("validate_in 进且码不同 → 违反", true,
                ScanPolicy.violates("validate_in", true, "EXP-1", "OTHER"));
        eq("validate_in 进且码相同 → 不违反", false,
                ScanPolicy.violates("validate_in", true, "EXP-1", "EXP-1"));
        eq("validate_both 出且码不同 → 违反", true,
                ScanPolicy.violates("validate_both", false, "EXP-1", "OTHER"));
        eq("none 进且码不同 → 不违反", false,
                ScanPolicy.violates("none", true, "EXP-1", "OTHER"));
        eq("read 进且码不同 → 不违反", false,
                ScanPolicy.violates("read", true, "EXP-1", "OTHER"));
        eq("validate_in 出且码不同 → 不违反（只拦进）", false,
                ScanPolicy.violates("validate_in", false, "EXP-1", "OTHER"));

        section("3. 边界");
        eq("该校验却没扫到码 → 违反（fail-closed）", true,
                ScanPolicy.violates("validate_in", true, "EXP-1", null));
        eq("该校验但扫到空白 → 违反", true,
                ScanPolicy.violates("validate_in", true, "EXP-1", "   "));
        eq("没有期望值 → 不误拦", false,
                ScanPolicy.violates("validate_in", true, null, "ANY"));
        eq("期望空白 → 不误拦", false,
                ScanPolicy.violates("validate_in", true, "  ", "ANY"));
        eq("null 策略 → 不违反", false,
                ScanPolicy.violates(null, true, "EXP-1", "OTHER"));

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
            throw new RuntimeException("ScanPolicySelfTest 有失败用例");
        }
    }

    private ScanPolicySelfTest() {
    }
}
