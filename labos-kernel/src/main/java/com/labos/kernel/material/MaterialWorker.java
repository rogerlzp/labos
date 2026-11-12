package com.labos.kernel.material;

/**
 * 物料效果 Worker（§3.5）：后台循环扫描到期的 PENDING 效果，重试 {@code applyEffect}。
 *
 * <p>单实例、单线程足够：同一台驱动上的命令必须串行，多线程反而要加锁。
 * 启动/停止由内核容器接线（见 §8.2 第 4 项 Worker）。
 */
public final class MaterialWorker implements Runnable {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(MaterialWorker.class);

    private final MaterialEffectService svc;
    private final long idleSleepMs;
    private volatile boolean running = true;

    public MaterialWorker(MaterialEffectService svc, long idleSleepMs) {
        this.svc = svc;
        this.idleSleepMs = idleSleepMs <= 0 ? 1_000 : idleSleepMs;
    }

    @Override
    public void run() {
        while (running) {
            try {
                boolean did = svc.processNextDue();
                if (!did) {
                    Thread.sleep(idleSleepMs);   // 无到期效果，歇一下
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("[MaterialWorker] 一轮扫描异常：{}", e.getMessage());
                try {
                    Thread.sleep(idleSleepMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** 停止循环（优雅退出）。 */
    public void stop() {
        running = false;
    }
}
