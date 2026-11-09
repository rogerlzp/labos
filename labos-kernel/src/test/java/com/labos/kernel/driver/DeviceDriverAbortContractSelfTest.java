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
        caseOnlyPhysicalStopCounts();
        caseDecideAfterAbort();
        caseDefaultAbortIsUnsupported();
        caseDetailTruncatedToColumnWidth();
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

    /** B-04：五种状态里只有 PHYSICALLY_STOPPED 能算「停了」。 */
    private static void caseOnlyPhysicalStopCounts() {
        check("只有 PHYSICALLY_STOPPED 算物理停止", () -> {
            eq("UNSUPPORTED", false, AbortResult.unsupported("x").physicallyStopped());
            eq("REQUESTED", false, AbortResult.requested("x").physicallyStopped());
            eq("DEVICE_ACKNOWLEDGED", false,
                    AbortResult.acknowledged("x").physicallyStopped());
            eq("UNKNOWN", false, AbortResult.unknown("x").physicallyStopped());
            eq("PHYSICALLY_STOPPED", true, AbortResult.stopped("x").physicallyStopped());
        });
    }

    /** B-04：拿到中止结果后的决策——只有设备确认停了才敢"当作已停"。 */
    private static void caseDecideAfterAbort() {
        check("设备确认停止才 TREAT_AS_STOPPED", () -> eq("stopped", "TREAT_AS_STOPPED",
                DriverStepExecutor.decideAfterAbort(AbortResult.stopped("ok")).name()));
        check("REQUESTED 必须继续等（不能说成停了）", () -> eq("requested", "KEEP_WAITING",
                DriverStepExecutor.decideAfterAbort(AbortResult.requested("sent")).name()));
        check("DEVICE_ACKNOWLEDGED 仍要继续等", () -> eq("ack", "KEEP_WAITING",
                DriverStepExecutor.decideAfterAbort(AbortResult.acknowledged("ack")).name()));
        check("UNKNOWN 必须继续等", () -> eq("unknown", "KEEP_WAITING",
                DriverStepExecutor.decideAfterAbort(AbortResult.unknown("?")).name()));
        check("UNSUPPORTED / null 必须继续等", () -> {
            eq("unsupported", "KEEP_WAITING",
                    DriverStepExecutor.decideAfterAbort(
                            AbortResult.unsupported("no")).name());
            eq("null", "KEEP_WAITING", DriverStepExecutor.decideAfterAbort(null).name());
        });
    }

    /** 接口默认实现必须给「没有中止能力」，而不是假装请求成功。 */
    private static void caseDefaultAbortIsUnsupported() {
        check("未实现 abort 的驱动默认返回 UNSUPPORTED", () -> {
            // 只实现 execute/healthy 的匿名类：命中接口默认的 abort 实现
            DeviceDriver bare = new DeviceDriver() {
                @Override
                public DriverDescriptor describe() {
                    return null;
                }

                @Override
                public DriverResult execute(DriverCommand command) {
                    return DriverResult.success(command.commandId(), "test", 0);
                }

                @Override
                public boolean healthy() {
                    return true;
                }
            };
            eq("implicit", "UNSUPPORTED", bare.abort("c1").state().name());
            eq("implicit-detail 非空", true, !bare.abort("c1").detail().isBlank());
        });
    }

    /** detail 落的是 varchar(200)，超长必须在构造时截断，而不是让 UPDATE 抛错。 */
    private static void caseDetailTruncatedToColumnWidth() {
        check("detail 超 200 字符被截断", () -> {
            String longDetail = "设".repeat(500);
            eq("len", 200, AbortResult.unknown(longDetail).detail().length());
        });
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
            public AbortResult abort(String commandId) {
                // 未声明物理中止能力的驱动：默认给 UNSUPPORTED（安全默认）
                return AbortResult.unsupported("test stub");
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
        public AbortResult abort(String commandId) {
            // 设备侧停止确认（这里是桩）
            return AbortResult.stopped("设备确认动作已停止");
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
