package com.labos.kernel.driver.tcp;

import com.labos.kernel.driver.DriverDescriptor;
import com.labos.kernel.driver.DriverResult;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * B-02 回归：TCP 协议没有设备侧 Abort 消息，驱动**不得**把本地 Future 当作已停止。
 *
 * <p>改动前的实现会把 {@code pending} 里的 future 摘掉并塞一个「被中止」：
 * 内核立刻拿到失败、节点记 aborted、界面显示已停 —— 但设备根本没收到停止指令，
 * 动作还在继续，恢复时那一步还会被重跑。
 */
public final class TcpAbortContractSelfTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        caseDoesNotFakeCompletion();
        caseDeclaresNoPhysicalAbort();
        System.out.printf("通过 %d, 失败 %d%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** abort 之后，等待中的 Future 必须仍然未完成（否则就是伪造「设备已停止」）。 */
    private static void caseDoesNotFakeCompletion() {
        check("TCP abort 不伪造命令完成", () -> {
            TcpPersistentDriver driver = driver();
            CompletableFuture<DriverResult> waiter = new CompletableFuture<>();
            driver.pendingForTest().put("cmd-1", waiter);

            driver.abort("cmd-1");

            if (waiter.isDone()) {
                throw new AssertionError("abort 让本地等待直接完成了："
                        + "内核会误以为设备已停止，实际设备还在动");
            }
            if (!driver.pendingForTest().containsKey("cmd-1")) {
                throw new AssertionError("abort 把 pending 摘掉了：设备回包将无处可投");
            }
        });
    }

    /** TCP 驱动必须保持安全默认：不声明物理中止能力。 */
    private static void caseDeclaresNoPhysicalAbort() {
        check("TCP 驱动不声明物理中止能力", () -> {
            if (driver().supportsPhysicalAbort()) {
                throw new AssertionError("TCP 协议没有 Abort 消息，不得声明物理中止");
            }
        });
    }

    private static TcpPersistentDriver driver() {
        return new TcpPersistentDriver(new DriverDescriptor(
                "tcp-selftest",
                "tcp://127.0.0.1:1",
                "tcp",
                "persistent",
                Set.of("TRANSFER"),
                Map.of(),
                Set.of(),
                Map.of()));
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

    private TcpAbortContractSelfTest() {
    }
}
