package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.Mappers;
import com.labos.kernel.persistence.RailZoneMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * P2 阶段 D · L3 轨道分区互锁（多分区全有或全无 + 固定序 + 完整运动包络）。
 *
 * <p>分区集覆盖机械臂及所抓板件的完整运动包络（接近/源位/途经/目标位/退出）。
 * 未配置分区集的路径 → {@code EC-INTERLOCK-RAIL}（fail-closed，不做空集放行）。
 * 多分区按 resource_id 升序获取，全有或全无，防循环等待；被占时排队，超时返回忙。
 */
public final class RailZoneGuard {

    private static final Logger log = LoggerFactory.getLogger(RailZoneGuard.class);

    /** 已拿到的一组分区的租约；必须在物理动作完成后释放。 */
    public record Lease(long runId, List<Long> zones) {
    }

    /** 获取结果：成功带租约；失败带错误码 + 理由。 */
    public record Acquire(Lease lease, String errorCode, String reason) {
        public boolean ok() {
            return lease != null;
        }

        public static Acquire ok(Lease lease) {
            return new Acquire(lease, null, null);
        }

        public static Acquire fail(String errorCode, String reason) {
            return new Acquire(null, errorCode, reason);
        }
    }

    private static long waitTimeoutMs() {
        String v = System.getenv("LABOS_RAIL_WAIT_TIMEOUT_MS");
        if (v != null && !v.isBlank()) {
            try {
                return Long.parseLong(v.trim());
            } catch (NumberFormatException ignore) {
                // 沿用默认
            }
        }
        return 60_000L;
    }

    /**
     * 获取搬运路径的全部分区锁（固定序、全有或全无）。
     *
     * @param pathKey 路径键（"srcPos->dstPos" 或 "srcDev->dstDev"）
     */
    public static Acquire acquire(DbConfig db, long tenantId, long runId,
                                  String pathKey) {
        if (db == null || runId <= 0 || pathKey == null || pathKey.isBlank()) {
            return Acquire.fail("EC-INTERLOCK-RAIL", "轨道分区路径键缺失");
        }
        List<Long> zones;
        try {
            zones = Mappers.get(RailZoneMapper.class).selectZonesByPath(tenantId, pathKey);
        } catch (RuntimeException e) {
            return Acquire.fail("EC-STATE-UNKNOWN", "分区查询失败：" + e.getMessage());
        }
        if (zones == null || zones.isEmpty()) {
            return Acquire.fail("EC-INTERLOCK-RAIL",
                    "搬运路径未配置轨道分区集，禁止自动运行（fail-closed）");
        }
        // 固定序：resource_id 升序，防循环等待
        List<Long> ordered = new ArrayList<>(zones);
        Collections.sort(ordered);

        DeviceLock lock = new DeviceLock(db, tenantId);
        long deadline = System.currentTimeMillis() + waitTimeoutMs();
        for (;;) {
            List<Long> acquired = new ArrayList<>();
            boolean all = true;
            for (long z : ordered) {
                if (lock.tryAcquire(z, runId)) {
                    acquired.add(z);
                } else {
                    all = false;
                    break;
                }
            }
            if (all) {
                return Acquire.ok(new Lease(runId, ordered));
            }
            // 释放已拿到的（半成品锁集不留），再排队重试
            for (long z : acquired) {
                lock.release(z, runId);
            }
            if (System.currentTimeMillis() >= deadline) {
                log.error("[L3] 分区锁等待超时 {}ms（路径 {}）", waitTimeoutMs(), pathKey);
                return Acquire.fail("EC-RESOURCE-BUSY", "轨道分区被占用，等待超时");
            }
            try {
                Thread.sleep(200L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return Acquire.fail("EC-RESOURCE-BUSY", "等待分区锁被中断");
            }
        }
    }

    /** 释放租约（物理动作完成后调用）。 */
    public static void release(DbConfig db, long tenantId, Lease lease) {
        if (db == null || lease == null || lease.zones() == null) {
            return;
        }
        DeviceLock lock = new DeviceLock(db, tenantId);
        for (long z : lease.zones()) {
            lock.release(z, lease.runId());
        }
    }

    private RailZoneGuard() {
    }
}
