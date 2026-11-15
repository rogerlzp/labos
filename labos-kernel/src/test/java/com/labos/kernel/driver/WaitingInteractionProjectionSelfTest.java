package com.labos.kernel.driver;

import java.util.Objects;

/**
 * P2 阶段 C · L5 有效等待交互投影 纯逻辑自测。
 */
public final class WaitingInteractionProjectionSelfTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        section("1. 有效等待：WAITING + 当前会话/步骤/交互全匹配");
        eq("全匹配 → 授权", true, WaitingInteractionProjection.authorizes(
                "WAITING", "sess-1", "step-3", "ix-9", "sess-1", "step-3", "ix-9"));

        section("2. 反例：状态非 WAITING 一律不授权");
        eq("CONSUMED → 拒绝", false, WaitingInteractionProjection.authorizes(
                "CONSUMED", "sess-1", "step-3", "ix-9", "sess-1", "step-3", "ix-9"));
        eq("CANCELLED → 拒绝", false, WaitingInteractionProjection.authorizes(
                "CANCELLED", "sess-1", "step-3", "ix-9", "sess-1", "step-3", "ix-9"));
        eq("EXPIRED → 拒绝", false, WaitingInteractionProjection.authorizes(
                "EXPIRED", "sess-1", "step-3", "ix-9", "sess-1", "step-3", "ix-9"));
        eq("null → 拒绝", false, WaitingInteractionProjection.authorizes(
                null, "sess-1", "step-3", "ix-9", "sess-1", "step-3", "ix-9"));

        section("3. 反例：会话/步骤/交互不匹配");
        eq("旧会话 → 拒绝", false, WaitingInteractionProjection.authorizes(
                "WAITING", "sess-old", "step-3", "ix-9", "sess-1", "step-3", "ix-9"));
        eq("旧步骤 → 拒绝", false, WaitingInteractionProjection.authorizes(
                "WAITING", "sess-1", "step-old", "ix-9", "sess-1", "step-3", "ix-9"));
        eq("交互不符 → 拒绝", false, WaitingInteractionProjection.authorizes(
                "WAITING", "sess-1", "step-3", "ix-old", "sess-1", "step-3", "ix-9"));

        System.out.println();
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("── " + title + " ──");
    }

    private static void eq(String name, Object expect, Object got) {
        if (Objects.equals(expect, got)) {
            pass++;
            System.out.println("  ✓ " + name + (got == null ? "" : "（" + got + "）"));
        } else {
            fail++;
            System.out.println("  ✗ " + name + " 期望 " + expect + "，实际 " + got);
        }
    }

    private WaitingInteractionProjectionSelfTest() {
    }
}
