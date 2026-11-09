package com.labos.kernel.workflow;

/**
 * WorkflowService 同 run 单飞自测（不连库）。
 *
 * <p>锁住 JVM 内这条底线：同一个 {@code runId} 只能有一个后台 worker。
 * 这层保护不替代数据库锁/现场扣留，只负责拦住"同一进程里把同一单号起两条线程"。
 */
public final class WorkflowServiceSingleFlightSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        WorkflowService.clearRunWorkerSlotsForTest();
        try {
            section("1. 同一 run 只允许一个 worker");
            check("首次 claim 成功",
                    WorkflowService.claimRunWorkerSlot(9001L, "worker-a"),
                    WorkflowService.activeRunWorkerOwner(9001L));
            check("重复 claim 失败",
                    !WorkflowService.claimRunWorkerSlot(9001L, "worker-b"),
                    WorkflowService.activeRunWorkerOwner(9001L));
            check("占位者仍是第一条 worker",
                    "worker-a".equals(WorkflowService.activeRunWorkerOwner(9001L)),
                    WorkflowService.activeRunWorkerOwner(9001L));

            section("2. 只有持有者能释放");
            WorkflowService.releaseRunWorkerSlot(9001L, "worker-b");
            check("错误 owner 不能释放别人的占位",
                    "worker-a".equals(WorkflowService.activeRunWorkerOwner(9001L)),
                    WorkflowService.activeRunWorkerOwner(9001L));
            WorkflowService.releaseRunWorkerSlot(9001L, "worker-a");
            check("正确 owner 释放后占位消失",
                    WorkflowService.activeRunWorkerOwner(9001L) == null,
                    WorkflowService.activeRunWorkerOwner(9001L));

            section("3. 释放后可重入；不同 run 可并行");
            check("释放后同一 run 可再次 claim",
                    WorkflowService.claimRunWorkerSlot(9001L, "worker-c"),
                    WorkflowService.activeRunWorkerOwner(9001L));
            check("不同 run 不互相影响",
                    WorkflowService.claimRunWorkerSlot(9002L, "worker-d"),
                    WorkflowService.activeRunWorkerOwner(9002L));
            section("4. 旧 worker 未退出时拒绝重复启动（B-03 的核心语义）");
            WorkflowService.claimRunWorkerSlot(9003L, "old-worker");
            boolean refused = false;
            String msg = null;
            try {
                WorkflowService.claimRunWorkerSlotOrThrow(9003L, "new-worker");
            } catch (IllegalStateException e) {
                refused = true;
                msg = e.getMessage();
            }
            check("第二次启动被拒（抛 IllegalStateException）", refused, msg);
            check("消息里能看出是谁占着（否则排障只能猜）",
                    msg != null && msg.contains("old-worker"), msg);
            check("被拒后占位仍是老 worker",
                    "old-worker".equals(WorkflowService.activeRunWorkerOwner(9003L)),
                    WorkflowService.activeRunWorkerOwner(9003L));
            WorkflowService.releaseRunWorkerSlot(9003L, "old-worker");

            section("5. worker 线程结束会自动释放占位");
            final java.util.concurrent.CountDownLatch ran =
                    new java.util.concurrent.CountDownLatch(1);
            WorkflowService.claimRunWorkerSlot(9004L, "flash-worker");
            WorkflowService.launchRunWorker(9004L, "flash-worker", ran::countDown);
            awaitQuietly(ran);
            check("body 跑过", ran.getCount() == 0, ran.getCount());
            check("线程结束后占位自动释放（否则这个 run 永远续不了）",
                    waitWorkerReleased(9004L), WorkflowService.activeRunWorkerOwner(9004L));

            section("6. body 抛异常也必须释放占位");
            Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((t, e) -> { });   // 别把栈打得到处都是
            boolean releasedAfterCrash;
            try {
                WorkflowService.claimRunWorkerSlot(9005L, "boom-worker");
                WorkflowService.launchRunWorker(9005L, "boom-worker", () -> {
                    throw new IllegalStateException("模拟 worker 崩溃");
                });
                // 先等占位释放（线程随之死亡，异常处理器也已用完），再恢复处理器 ——
                // 顺序反了会把栈打到汇总行上，把测试输出搅乱。
                releasedAfterCrash = waitWorkerReleased(9005L);
            } finally {
                Thread.setDefaultUncaughtExceptionHandler(prev);
            }
            check("body 抛异常后占位仍被释放",
                    releasedAfterCrash, WorkflowService.activeRunWorkerOwner(9005L));
        } finally {
            WorkflowService.clearRunWorkerSlotsForTest();
        }

        System.out.printf("%n通过 %d, 失败 %d%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static boolean awaitQuietly(java.util.concurrent.CountDownLatch latch) {
        try {
            return latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 释放发生在 daemon 线程的 finally 里，比 body 返回晚一瞬：轮询而不是立刻断言。 */
    private static boolean waitWorkerReleased(long runId) {
        for (int i = 0; i < 100; i++) {
            if (WorkflowService.activeRunWorkerOwner(runId) == null) {
                return true;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static void check(String name, boolean ok, Object actual) {
        if (ok) {
            passed++;
            System.out.printf("  ✓ %-30s %s%n", name, actual);
        } else {
            failed++;
            System.out.printf("  ✗ %-30s %s%n", name, actual);
        }
    }

    private static void section(String title) {
        System.out.println("\n── " + title + " ──");
    }

    private WorkflowServiceSingleFlightSelfTest() {
    }
}
