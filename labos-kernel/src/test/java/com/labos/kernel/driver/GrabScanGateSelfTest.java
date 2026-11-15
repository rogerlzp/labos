package com.labos.kernel.driver;

import java.time.Duration;
import java.util.Objects;

/**
 * P2 阶段 B · 抓板扫码闸门 纯逻辑自测（不连库）。
 *
 * <p>钉死 fail-closed：无码/无期望/无源位/不匹配/已消费/过期 一律拒绝抓板；
 * 与 {@link ScanPolicy} 的「无期望值放行」口径相反。
 */
public final class GrabScanGateSelfTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        long now = System.currentTimeMillis();

        section("1. 放行：一致且未过期");
        GrabScanGate.Verdict v = GrabScanGate.check("PLATE-1", "PLATE-1", 100L,
                "scan-1", now, false, Duration.ofSeconds(60));
        eq("一致 → allow", true, v.allow());

        section("2. 反例：无码/空码/缺期望/缺源位 → 拒绝");
        eq("无 scan_id", false, GrabScanGate.check("PLATE-1", "PLATE-1", 100L,
                null, now, false, null).allow());
        eq("空扫码", false, GrabScanGate.check("PLATE-1", "", 100L,
                "scan-1", now, false, null).allow());
        eq("缺期望板号", false, GrabScanGate.check(null, "PLATE-1", 100L,
                "scan-1", now, false, null).allow());
        eq("缺源位置", false, GrabScanGate.check("PLATE-1", "PLATE-1", null,
                "scan-1", now, false, null).allow());

        section("3. 反例：不匹配 / 已消费 / 过期 → 拒绝 + 错误码分档");
        GrabScanGate.Verdict mismatch = GrabScanGate.check("PLATE-1", "PLATE-2", 100L,
                "scan-1", now, false, null);
        eq("不匹配 → 拒绝", false, mismatch.allow());
        eq("不匹配错误码 EC-SCAN-MISMATCH", "EC-SCAN-MISMATCH", mismatch.errorCode());

        GrabScanGate.Verdict reused = GrabScanGate.check("PLATE-1", "PLATE-1", 100L,
                "scan-1", now, true, null);
        eq("已消费 → 拒绝", false, reused.allow());
        eq("已消费错误码 EC-SCAN-EVIDENCE-CONSUMED", "EC-SCAN-EVIDENCE-CONSUMED", reused.errorCode());

        GrabScanGate.Verdict stale = GrabScanGate.check("PLATE-1", "PLATE-1", 100L,
                "scan-1", now - 120_000L, false, Duration.ofSeconds(60));
        eq("过期 → 拒绝", false, stale.allow());
        eq("过期错误码 EC-STATE-STALE", "EC-STATE-STALE", stale.errorCode());

        GrabScanGate.Verdict unknownTime = GrabScanGate.check("PLATE-1", "PLATE-1", 100L,
                "scan-1", 0L, false, null);
        eq("观测时间未知 → 按过期拒绝", false, unknownTime.allow());

        section("4. 错误码全集覆盖");
        eq("EC-SCAN-EVIDENCE-MISSING 无码", "EC-SCAN-EVIDENCE-MISSING",
                GrabScanGate.check("P", "P", 1L, null, now, false, null).errorCode());
        eq("EC-SCAN-MISMATCH 不匹配", "EC-SCAN-MISMATCH",
                GrabScanGate.check("P", "Q", 1L, "s", now, false, null).errorCode());
        eq("EC-STATE-STALE 过期", "EC-STATE-STALE",
                GrabScanGate.check("P", "P", 1L, "s", 1L, false, null).errorCode());

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

    private GrabScanGateSelfTest() {
    }
}
