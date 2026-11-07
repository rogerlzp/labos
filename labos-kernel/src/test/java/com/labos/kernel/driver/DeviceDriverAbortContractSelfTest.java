package com.labos.kernel.driver;

/** 回归：没有 HIL 证明的驱动默认不具备物理中止能力。 */
public final class DeviceDriverAbortContractSelfTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        check("物理中止能力默认关闭", () -> {
            DeviceDriver driver = new DeviceDriver() {
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
            if (driver.supportsPhysicalAbort()) {
                throw new AssertionError("unsupported driver must not claim physical abort");
            }
        });
        System.out.printf("通过 %d, 失败 %d%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
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

    private DeviceDriverAbortContractSelfTest() {
    }
}
