package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** 同一 run 的并行分支也必须互斥；同一线程的搬运嵌套仍可重入。 */
public final class DeviceLockSameRunSelfTest {
    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        DbConfig db = DbConfig.fromEnv();
        long runId = -991900L;
        long collisionRunId = runId + 256;
        long resourceId = freeResource(db);
        if (resourceId == 0) {
            throw new IllegalStateException("没有空闲、在线且非故障的设备可用于同 run 锁自测");
        }
        ExecutorService pool = Executors.newSingleThreadExecutor();
        DeviceLock first = new DeviceLock(db, 1L);
        try {
            // 真实 running 行可防止 DeviceStateMapper 把测试锁当作“不存在 run 的幽灵锁”回收。
            insertRun(db, runId);
            insertRun(db, collisionRunId);
            check("第一个分支取得设备", first.tryAcquire(resourceId, runId));

            Future<Boolean> blocked = pool.submit(() -> new DeviceLock(db, 1L)
                    .acquireWithWait(resourceId, runId, 300));
            check("同 run 另一线程不能穿透 run 级数据库锁", !blocked.get(2, TimeUnit.SECONDS));

            check("同一线程搬运嵌套可以重入", first.tryAcquire(resourceId, runId));
            first.release(resourceId, runId);
            check("内层释放不会清掉外层设备锁", Long.valueOf(runId).equals(holder(db, resourceId)));

            // runId + 256 与 runId 映射到同一固定条带；不同 run 不能因此跳过数据库占用。
            check("条带碰撞的不同 run 不会误判重入", !first.tryAcquire(resourceId, collisionRunId));
            check("碰撞尝试失败仍保留原设备锁", Long.valueOf(runId).equals(holder(db, resourceId)));

            blocked = pool.submit(() -> new DeviceLock(db, 1L)
                    .acquireWithWait(resourceId, runId, 300));
            check("重入后外层仍拦住另一线程", !blocked.get(2, TimeUnit.SECONDS));
            first.release(resourceId, runId);

            Future<Boolean> acquired = pool.submit(() -> {
                DeviceLock second = new DeviceLock(db, 1L);
                if (!second.acquireWithWait(resourceId, runId, 1_500)) {
                    return false;
                }
                second.release(resourceId, runId);
                return true;
            });
            check("外层释放后另一分支才能获得设备", acquired.get(3, TimeUnit.SECONDS));
            check("全部分支结束后数据库锁清空", holder(db, resourceId) == null);
        } finally {
            first.release(resourceId, runId);
            pool.shutdownNow();
            try (Connection c = db.open();
                 PreparedStatement ps = c.prepareStatement(
                         "UPDATE device_state SET current_run_id=NULL, current_method=NULL, "
                                 + "status='idle' WHERE resource_id=? AND current_run_id IN (?,?)")) {
                ps.setLong(1, resourceId);
                ps.setLong(2, runId);
                ps.setLong(3, collisionRunId);
                ps.executeUpdate();
            }
            try (Connection c = db.open();
                 PreparedStatement ps = c.prepareStatement("DELETE FROM run WHERE id IN (?,?)")) {
                ps.setLong(1, runId);
                ps.setLong(2, collisionRunId);
                ps.executeUpdate();
            }
            System.out.printf("通过 %d, 失败 %d%n", pass, fail);
        }
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void insertRun(DbConfig db, long runId) throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO run (id,tenant_id,run_no,run_type,status,mode,priority,created_at) "
                             + "VALUES (?,1,?,'workflow','running','normal',5,now())")) {
            ps.setLong(1, runId);
            ps.setString(2, "SELF-LOCK-" + Math.abs(runId));
            ps.executeUpdate();
        }
    }

    private static long freeResource(DbConfig db) throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ds.resource_id FROM device_state ds "
                             + "JOIN device_instance di ON di.resource_id=ds.resource_id "
                             + "AND di.deleted_at IS NULL "
                             + "WHERE ds.tenant_id=1 AND ds.online=true "
                             + "AND ds.status <> 'error' AND ds.current_run_id IS NULL "
                             + "ORDER BY ds.resource_id LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private static Long holder(DbConfig db, long resourceId) throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT current_run_id FROM device_state WHERE tenant_id=1 AND resource_id=?")) {
            ps.setLong(1, resourceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                long value = rs.getLong(1);
                return rs.wasNull() ? null : value;
            }
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  ✓ " + name);
        } else {
            fail++;
            System.out.println("  ✗ " + name);
        }
    }

    private DeviceLockSameRunSelfTest() {
    }
}
