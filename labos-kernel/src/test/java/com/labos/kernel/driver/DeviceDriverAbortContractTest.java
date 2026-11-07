package com.labos.kernel.driver;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class DeviceDriverAbortContractTest {

    @Test
    void physicalAbortIsUnsupportedByDefault() {
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
                // no-op test driver
            }

            @Override
            public boolean healthy() {
                return true;
            }
        };

        assertFalse(driver.supportsPhysicalAbort(),
                "没有明确声明并经过 HIL 验证的驱动不得被当作可物理中止");
    }
}
