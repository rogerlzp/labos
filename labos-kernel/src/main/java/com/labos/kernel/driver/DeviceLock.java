package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.DeviceStateMapper;
import com.labos.kernel.persistence.Mappers;

import java.time.Duration;

/**
 * 设备级锁：一台设备同一时刻只服务一个执行。
 *
 * <p><b>为什么不用岛级锁</b>：岛是"设备的集合"，不是一个可执行单元。
 * 一个岛里有孵箱、离心机、读板机、机械臂，只有**机械臂**是真共享的
 * （同一时刻只能搬一块板）。岛级互斥会把"抢机械臂"放大成"抢整个岛"，
 * 让本来能并行的实验互相排队：
 *
 * <pre>
 *   实验 A：孵育 30 分钟（只用孵箱）
 *   实验 B：离心 5 分钟（只用离心机）
 *   岛锁 → B 等 30 分钟。这是纯浪费。
 * </pre>
 *
 * <p>设备锁的粒度正好：A 占孵箱、B 占离心机，互不影响；
 * 只有两边都要搬板时才争机械臂——这才是真实的物理约束。
 *
 * <p><b>机械臂不用特殊处理</b>：它就是一台设备，
 * 用同一把锁自然实现了"一次搬一块板"。
 *
 * <h3>和位置锁的分工</h3>
 * <table>
 *   <tr><th>锁</th><th>粒度</th><th>防什么</th></tr>
 *   <tr><td>设备锁（本类）</td><td>一台设备</td><td>两个实验同时命令同一台设备</td></tr>
 *   <tr><td>位置锁 {@link PositionLock}</td><td>一个层位</td><td>两块板放同一个位置</td></tr>
 * </table>
 *
 * <p>阶段3第21批：4 条裸 SQL 收编进 {@code DeviceStateMapper}
 * （{@code mapper/DeviceStateMapper.xml} 的 DeviceLock 段），本类只留锁语义
 * 与重试/兜底策略；db 字段保留以维持构造签名，SQL 经 {@link Mappers#get} 执行。
 */
public final class DeviceLock {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(DeviceLock.class);

    /*
     * device_state.current_run_id 只有 run 粒度，允许同 run 重入；但 ParallelGateway
     * 的两个分支也属于同一 run，不能因为 runId 相同就同时进入设备。
     *
     * 用固定条带的线程级可重入锁补上“同 JVM、同 run、同设备、跨线程互斥”：
     * 同一线程内的 TransferPlan 嵌套仍可重入；不同线程即使 runId 相同也必须等待。
     * 固定数组不会随 run 数增长；哈希碰撞只会短暂多串行两个临界区，不改变正确性。
     */
    private static final int LOCAL_STRIPES = 256;
    private static final java.util.concurrent.locks.ReentrantLock[] LOCAL_LOCKS =
            new java.util.concurrent.locks.ReentrantLock[LOCAL_STRIPES];
    static {
        for (int i = 0; i < LOCAL_LOCKS.length; i++) {
            LOCAL_LOCKS[i] = new java.util.concurrent.locks.ReentrantLock(true);
        }
    }

    private record LocalKey(long tenantId, long resourceId, long runId) {
    }

    /* 条带可能碰撞，重入次数必须按真实的 tenant/resource/run 键分开记录。 */
    private static final ThreadLocal<java.util.Map<LocalKey, Integer>> LOCAL_HOLDS =
            ThreadLocal.withInitial(java.util.HashMap::new);

    private final DbConfig db;
    private final long tenantId;

    public DeviceLock(DbConfig db, long tenantId) {
        this.db = db;
        this.tenantId = tenantId;
    }

    private DeviceStateMapper mapper() {
        return Mappers.get(DeviceStateMapper.class);
    }

    private java.util.concurrent.locks.ReentrantLock localLock(long resourceId, long runId) {
        long h = tenantId * 31L + resourceId * 17L + runId;
        int slot = (int) ((h ^ (h >>> 32)) & Integer.MAX_VALUE) % LOCAL_STRIPES;
        return LOCAL_LOCKS[slot];
    }

    private LocalKey localKey(long resourceId, long runId) {
        return new LocalKey(tenantId, resourceId, runId);
    }

    private int holdCount(LocalKey key) {
        return LOCAL_HOLDS.get().getOrDefault(key, 0);
    }

    private void incrementHold(LocalKey key) {
        LOCAL_HOLDS.get().merge(key, 1, Integer::sum);
    }

    private void decrementHold(LocalKey key) {
        java.util.Map<LocalKey, Integer> holds = LOCAL_HOLDS.get();
        int count = holds.getOrDefault(key, 0);
        if (count <= 1) {
            holds.remove(key);
            if (holds.isEmpty()) {
                LOCAL_HOLDS.remove();
            }
        } else {
            holds.put(key, count - 1);
        }
    }

    /** 数据库锁；调用方必须已经持有对应的本地锁。 */
    private boolean tryAcquireDatabase(long resourceId, long runId) {
        try {
            mapper().ensureDeviceState(tenantId, resourceId);
            return mapper().tryAcquireDevice(tenantId, resourceId, runId) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 抢设备。同一线程对同一资源键可重入；同 run 的其他线程也会被本地锁拦住。
     *
     * @param resourceId {@code resource_node.id}（不是 device_instance.id）
     * @param runId      谁要占
     * @return true = 抢到；每次 true 都必须对应一次 release
     */
    public boolean tryAcquire(long resourceId, long runId) {
        java.util.concurrent.locks.ReentrantLock local = localLock(resourceId, runId);
        LocalKey key = localKey(resourceId, runId);
        if (!local.tryLock()) {
            return false;
        }
        if (holdCount(key) > 0) {
            incrementHold(key);
            return true;
        }
        boolean ok = tryAcquireDatabase(resourceId, runId);
        if (ok) {
            incrementHold(key);
        } else {
            local.unlock();
        }
        return ok;
    }

    /**
     * 抢设备，抢不到就等一会儿再试。等待本地同 run 分支和数据库其他 run
     * 共用同一个时间预算；超时返回 false，调用方不得下发命令。
     */
    public boolean acquireWithWait(long resourceId, long runId, long timeoutMs) {
        java.util.concurrent.locks.ReentrantLock local = localLock(resourceId, runId);
        LocalKey key = localKey(resourceId, runId);
        if (holdCount(key) > 0) {
            local.lock();
            incrementHold(key);
            return true;
        }
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS
                .toNanos(Math.max(1L, timeoutMs));
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !local.tryLock(remaining,
                    java.util.concurrent.TimeUnit.NANOSECONDS)) {
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        boolean acquired = false;
        try {
            while (System.nanoTime() < deadline) {
                if (tryAcquireDatabase(resourceId, runId)) {
                    incrementHold(key);
                    acquired = true;
                    return true;
                }
                long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
                if (remainingMs <= 0) {
                    break;
                }
                Thread.sleep(Math.min(200L, remainingMs));
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (!acquired) {
                local.unlock();
            }
        }
    }

    /**
     * 释放设备。最外层释放才清数据库锁，内层重入只递减本地持有计数。
     * 非持有线程不能释放另一线程仍持有的同 run 锁。
     */
    public void release(long resourceId, long runId) {
        java.util.concurrent.locks.ReentrantLock local = localLock(resourceId, runId);
        LocalKey key = localKey(resourceId, runId);
        int count = holdCount(key);
        if (count == 0) {
            if (local.isLocked()) {
                log.error("[设备锁] 非持有线程不能释放：resource=" + resourceId + " run=" + runId);
                return;
            }
            releaseDatabase(resourceId, runId);
            return;
        }
        if (count == 1) {
            releaseDatabase(resourceId, runId);
        }
        decrementHold(key);
        local.unlock();
    }

    private void releaseDatabase(long resourceId, long runId) {
        try {
            mapper().releaseDevice(tenantId, resourceId, runId);
        } catch (RuntimeException e) {
            log.error("[设备锁] 释放失败：" + e.getMessage());
        }
    }

    /** 谁占着这台设备；null = 空闲。 */
    public Long holder(long resourceId) {
        try {
            return mapper().selectCurrentRunId(tenantId, resourceId);
        } catch (RuntimeException ignore) {
            // 查不到当空闲
        }
        return null;
    }

    /**
     * 清理孤儿锁：{@code current_run_id} 指向的 run 已经结束，但锁还挂着。
     *
     * <p><b>为什么会有孤儿锁</b>：
     * <ul>
     *   <li>执行线程被 kill -9，finally 跑不到</li>
     *   <li>走了不走 onStepDone 的代码路径（比如模拟总线）</li>
     *   <li>释放时 runId 对不上（两套 ID 转换出错）</li>
     * </ul>
     *
     * <p>孤儿锁的后果很严重：那台设备**永久不可用**，直到重启。
     * 实测已经出现过（两台 INCUBATE 挂着几小时前的 runId）。
     *
     * @return 清理了几条
     */
    public int releaseOrphans() {
        try {
            return mapper().releaseOrphanDeviceLocks(tenantId);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * 清理超时锁：run 还在 running，但没动静超过阈值。
     *
     * <p>这种情况要么是真卡住了，要么是执行线程死了但 run 状态没更新。
     * 保守起见只清"超过 2 小时"的——正常的孵育不会超过这个数。
     *
     * @return 清理了几条
     */
    public int releaseStale(Duration threshold) {
        try {
            return mapper().releaseStaleDeviceLocks(tenantId,
                    String.valueOf(threshold.toSeconds()));
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
