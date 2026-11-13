package com.labos.kernel.engine;

import com.labos.kernel.driver.DriverResult;
import com.labos.kernel.recovery.RecoveryScope;

import java.util.Objects;

/**
 * §3.2.1 执行确认四态分类器 纯逻辑自测（不连库）。
 *
 * <p>锁住：驱动状态 → 四态映射，以及「未知是否发生」保守归 RECONCILIATION_REQUIRED，
 * 不把「可能已发生」误判成「确认未执行」。
 */
public final class ExecutionResolutionSelfTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        section("1. 驱动状态 → 四态");
        eq("SUCCESS → completed", ExecutionResolution.COMPLETED,
                ExecutionResolution.classify(DriverResult.Status.SUCCESS, null));
        eq("REJECTED → failed_confirmed", ExecutionResolution.FAILED_CONFIRMED,
                ExecutionResolution.classify(DriverResult.Status.REJECTED, "EC-06-0002"));
        eq("FAILED → reconciliation_required", ExecutionResolution.RECONCILIATION_REQUIRED,
                ExecutionResolution.classify(DriverResult.Status.FAILED, "EC-05-2001"));
        eq("TIMEOUT → reconciliation_required", ExecutionResolution.RECONCILIATION_REQUIRED,
                ExecutionResolution.classify(DriverResult.Status.TIMEOUT, "EC-05-1007"));
        eq("null(非驱动失败) → 保守 reconciliation_required",
                ExecutionResolution.RECONCILIATION_REQUIRED,
                ExecutionResolution.classify(null, "EC-99-0001"));
        eq("资源预留失败(命令未下发) → failed_confirmed",
                ExecutionResolution.FAILED_CONFIRMED,
                ExecutionResolution.classify(null, "EC-RESOURCE-BUSY"));

        section("2. dbValue 与 DDL CHECK 对齐");
        eq("unresolved", "unresolved", ExecutionResolution.UNRESOLVED.dbValue());
        eq("completed", "completed", ExecutionResolution.COMPLETED.dbValue());
        eq("failed_confirmed", "failed_confirmed", ExecutionResolution.FAILED_CONFIRMED.dbValue());
        eq("reconciliation_required", "reconciliation_required",
                ExecutionResolution.RECONCILIATION_REQUIRED.dbValue());

        section("3. StepFailure 携带 driverStatus（U10 载体 ①）");
        StepFailure timeout = new StepFailure("EC-05-1007", RecoveryScope.COMMAND,
                "超时", 1, "Seal", DriverResult.Status.TIMEOUT);
        eq("TIMEOUT StepFailure → driverStatus 保留", DriverResult.Status.TIMEOUT,
                timeout.driverStatus());
        eq("StepFailure.of → reconciliation_required",
                ExecutionResolution.RECONCILIATION_REQUIRED, ExecutionResolution.of(timeout));

        StepFailure rejected = new StepFailure("EC-06-0002", RecoveryScope.COMMAND,
                "参数非法", 1, "OpenDoor", DriverResult.Status.REJECTED);
        eq("REJECTED StepFailure → failed_confirmed", ExecutionResolution.FAILED_CONFIRMED,
                ExecutionResolution.of(rejected));

        StepFailure noStatus = new StepFailure("EC-06-0001", RecoveryScope.RUN, "驱动没加载");
        eq("无 driverStatus 的 StepFailure → null", null, noStatus.driverStatus());

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
            throw new RuntimeException("ExecutionResolutionSelfTest 有失败用例");
        }
    }

    private ExecutionResolutionSelfTest() {
    }
}
