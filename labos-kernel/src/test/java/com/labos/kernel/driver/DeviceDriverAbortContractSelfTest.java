package com.labos.kernel.driver;

/**
 * B-02 回归：没有 HIL 证明的驱动默认**不具备物理中止能力**，
 * 且「方法声明可中断」不足以进入可中断等待 —— 必须同时具备物理 Abort。
 *
 * <p>语义：软件线程停了 ≠ 物理动作停了。取消本地 Future 只是让内核以为停了。
 */
public final class DeviceDriverAbortContractSelfTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        caseDefaultIsUnsupported();
        caseExplicitDeclarationIsHonoured();
        caseBothConditionsRequired();
        System.out.printf("通过 %d, 失败 %d%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** 任何未显式声明的驱动，都必须回答「我没有物理中止能力」。 */
    private static void caseDefaultIsUnsupported() {
        check("物理中止能力默认关闭", () -> {
            if (plainDriver().supportsPhysicalAbort()) {
                throw new AssertionError("unsupported driver must not claim physical abort");
            }
        });
    }

    /** 接口留了口子：真做过 HIL 的驱动可以显式声明 true（否则这个契约没法用）。 */
    private static void caseExplicitDeclarationIsHonoured() {
        check("显式声明时才认为具备物理中止", () -> {
            if (!new PhysicalAbortDriver().supportsPhysicalAbort()) {
                throw new AssertionError("explicit override must be honoured");
            }
        });
    }

    /** 准入判据：method 可中断 **且** driver 有物理 Abort，缺一不可。 */
    private static void caseBothConditionsRequired() {
        check("方法可中断+驱动可物理中止 ⇒ 允许可中断等待",
                () -> eq("组合(1,1)", true,
                        DriverStepExecutor.allowInterruptibleWait(true, true)));
        check("方法可中断但驱动无物理中止 ⇒ 不得取消本地等待",
                () -> eq("组合(1,0)", false,
                        DriverStepExecutor.allowInterruptibleWait(true, false)));
        check("方法不可中断时，驱动能力再强也不进可中断等待",
                () -> eq("组合(0,1)", false,
                        DriverStepExecutor.allowInterruptibleWait(false, true)));
        check("两者都不满足 ⇒ 不得取消本地等待",
                () -> eq("组合(0,0)", false,
                        DriverStepExecutor.allowInterruptibleWait(false, false)));
    }

    private static DeviceDriver plainDriver() {
        return new DeviceDriver() {
            @Override
            public DriverDescriptor describe() {
                return null;
            }

            @Override
            public DriverResult execute(DriverCommand command) {
                return DriverResult.success(command.commandId(), "test", 0);
            }

            @Override
            public void abort(String commandId) {
                // test stub
            }

            @Override
            public boolean healthy() {
                return true;
            }
        };
    }

    /** 模拟"做过 HIL、真能停"的驱动。 */
    private static final class PhysicalAbortDriver implements DeviceDriver {
        @Override
        public DriverDescriptor describe() {
            return null;
        }

        @Override
        public DriverResult execute(DriverCommand command) {
            return DriverResult.success(command.commandId(), "test", 0);
        }

        @Override
        public void abort(String commandId) {
            // 设备侧停止确认（这里是桩）
        }

        @Override
        public boolean supportsPhysicalAbort() {
            return true;
        }

        @Override
        public boolean healthy() {
            return true;
        }
    }

    private static void check(String name, Runnable body) {
        try {
            body.run();
            passed++;
            System.out.println("  ✔ " + name);
        } catch (Throwable e) {
            failed++;
            System.out.println("  ✘ " + name + " | " + e);
        }
    }

    private static void eq(String label, Object expected, Object actual) {
        if (!String.valueOf(expected).equals(String.valueOf(actual))) {
            throw new AssertionError(label + " expected=" + expected + " actual=" + actual);
        }
    }

    private DeviceDriverAbortContractSelfTest() {
    }
}
