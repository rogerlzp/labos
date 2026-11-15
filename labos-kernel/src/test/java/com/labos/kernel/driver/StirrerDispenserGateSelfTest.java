package com.labos.kernel.driver;

import java.time.Duration;
import java.util.Objects;

/**
 * P2 阶段 C · L4 搅拌—分液互锁 纯逻辑自测（不连库）。
 */
public final class StirrerDispenserGateSelfTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        long now = System.currentTimeMillis();

        section("1. 放行：可用 + 新鲜");
        StirrerDispenserGate.Verdict v = StirrerDispenserGate.check("idle", now, null);
        eq("idle + 新鲜 → allow", true, v.allow());

        section("2. 反例：故障/过期/未知");
        eq("error → 拒绝 EC-INTERLOCK-STIRRER", "EC-INTERLOCK-STIRRER",
                StirrerDispenserGate.check("error", now, null).errorCode());
        eq("fault → 拒绝", "EC-INTERLOCK-STIRRER",
                StirrerDispenserGate.check("FAULT", now, null).errorCode());
        eq("idle + 过期 → EC-STATE-STALE", "EC-STATE-STALE",
                StirrerDispenserGate.check("idle", now - 60_000L, Duration.ofSeconds(10)).errorCode());
        eq("null 状态 → EC-STATE-UNKNOWN", "EC-STATE-UNKNOWN",
                StirrerDispenserGate.check(null, now, null).errorCode());
        eq("未知状态 → EC-STATE-UNKNOWN", "EC-STATE-UNKNOWN",
                StirrerDispenserGate.check("weird", now, null).errorCode());
        eq("观测时间未知 → EC-STATE-STALE", "EC-STATE-STALE",
                StirrerDispenserGate.check("idle", 0L, null).errorCode());

        section("3. 暂态忙 → busy（有界等待，不直接拒绝）");
        StirrerDispenserGate.Verdict b = StirrerDispenserGate.check("busy", now, null);
        eq("busy → 非 allow 非 reject", false, b.allow());
        eq("busy → busy 标记", true, b.busy());
        eq("busy 错误码 EC-RESOURCE-BUSY", "EC-RESOURCE-BUSY", b.errorCode());

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

    private StirrerDispenserGateSelfTest() {
    }
}
