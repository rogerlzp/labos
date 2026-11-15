package com.labos.kernel.driver;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * P2 阶段 A · 互锁契约 纯逻辑自测（不连库）。
 *
 * <p>钉死：错误码分档词表齐全（评审 §6 的 11 个互锁码一个不少）、未知错误码
 * fail-closed 转人工不静默放行、事实类型时效有明确值且未知类型走默认值。
 */
public final class InterlockContractSelfTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        section("1. 错误码 → 恢复作用域（评审 §6 词表）");
        eq("EC-RESOURCE-BUSY → WAIT", InterlockContract.Scope.WAIT,
                InterlockContract.recoveryScope("EC-RESOURCE-BUSY"));
        eq("EC-INTERLOCK-TURNTABLE → WAIT", InterlockContract.Scope.WAIT,
                InterlockContract.recoveryScope("EC-INTERLOCK-TURNTABLE"));
        eq("EC-PLATE-DUPLICATE-POS → BLOCK_RECONCILE", InterlockContract.Scope.BLOCK_RECONCILE,
                InterlockContract.recoveryScope("EC-PLATE-DUPLICATE-POS"));
        eq("EC-INTERLOCK-RAIL → MANUAL", InterlockContract.Scope.MANUAL,
                InterlockContract.recoveryScope("EC-INTERLOCK-RAIL"));
        eq("EC-INTERLOCK-STIRRER → PAUSE_FAULT", InterlockContract.Scope.PAUSE_FAULT,
                InterlockContract.recoveryScope("EC-INTERLOCK-STIRRER"));
        eq("EC-INTERLOCK-NO-WAIT → WAIT", InterlockContract.Scope.WAIT,
                InterlockContract.recoveryScope("EC-INTERLOCK-NO-WAIT"));
        eq("EC-STATE-STALE → MANUAL", InterlockContract.Scope.MANUAL,
                InterlockContract.recoveryScope("EC-STATE-STALE"));
        eq("EC-STATE-UNKNOWN → MANUAL", InterlockContract.Scope.MANUAL,
                InterlockContract.recoveryScope("EC-STATE-UNKNOWN"));
        eq("EC-SCAN-MISMATCH → MANUAL", InterlockContract.Scope.MANUAL,
                InterlockContract.recoveryScope("EC-SCAN-MISMATCH"));
        eq("EC-SCAN-EVIDENCE-MISSING → MANUAL", InterlockContract.Scope.MANUAL,
                InterlockContract.recoveryScope("EC-SCAN-EVIDENCE-MISSING"));
        eq("EC-SCAN-EVIDENCE-CONSUMED → MANUAL", InterlockContract.Scope.MANUAL,
                InterlockContract.recoveryScope("EC-SCAN-EVIDENCE-CONSUMED"));
        eq("EC-PLACE-UNCONFIRMED → BLOCK_RECONCILE", InterlockContract.Scope.BLOCK_RECONCILE,
                InterlockContract.recoveryScope("EC-PLACE-UNCONFIRMED"));

        section("2. 反例：未知错误码 fail-closed 转人工，不静默放行");
        eq("未知码 → MANUAL（不静默当成功/不自动重试）", InterlockContract.Scope.MANUAL,
                InterlockContract.recoveryScope("EC-WHATEVER-NEW"));
        eq("null → MANUAL", InterlockContract.Scope.MANUAL,
                InterlockContract.recoveryScope(null));
        eq("空串 → MANUAL", InterlockContract.Scope.MANUAL,
                InterlockContract.recoveryScope(""));

        section("3. 词表不被悄悄删减（评审 §6 的 12 个互锁码一个不少）");
        List<String> required = List.of(
                "EC-RESOURCE-BUSY", "EC-INTERLOCK-TURNTABLE", "EC-PLATE-DUPLICATE-POS",
                "EC-INTERLOCK-RAIL", "EC-INTERLOCK-STIRRER", "EC-INTERLOCK-NO-WAIT",
                "EC-STATE-STALE", "EC-STATE-UNKNOWN", "EC-SCAN-MISMATCH",
                "EC-SCAN-EVIDENCE-MISSING", "EC-SCAN-EVIDENCE-CONSUMED",
                "EC-PLACE-UNCONFIRMED");
        eq("词表覆盖 12 个必需码", true,
                InterlockContract.errorCodes().containsAll(required));

        section("4. 事实时效");
        eq("turntable 5s", Duration.ofSeconds(5),
                InterlockContract.freshnessTtl("turntable"));
        eq("stirrer_status 10s", Duration.ofSeconds(10),
                InterlockContract.freshnessTtl("stirrer_status"));
        eq("waiting_interaction 60s", Duration.ofSeconds(60),
                InterlockContract.freshnessTtl("waiting_interaction"));
        eq("未知事实走 ZERO（fail-closed：未知必须实时取证）", Duration.ZERO,
                InterlockContract.freshnessTtl("whatever"));
        eq("空事实 ZERO（禁止用缓存/旧值，必须实时取证）", Duration.ZERO,
                InterlockContract.freshnessTtl(null));

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

    private InterlockContractSelfTest() {
    }
}
