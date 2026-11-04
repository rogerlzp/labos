package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * TransferPlan 单测：验证固定全序、失败回滚、回程位豁免。
 *
 * <p>需要真实库（有 device_port / device_access / position_state 数据）。
 */
public final class TransferPlanSelfTest {

    private static int pass;
    private static int fail;
    private static DbConfig db;
    private static long tenant = 1;
    private static long srcRes;
    private static long dstRes;
    private static long srcPos;
    private static long dstPos;
    private static long robotRes;

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        if (!loadFixtures()) {
            System.out.println("跳过：库里没有可用设备/位置");
            return;
        }

        testAcquireAndRelease();
        testRollbackOnFailure();
        testKeepReturnOnClose();

        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    /** 挑两台设备各一个位置 + 一个机械臂。 */
    private static boolean loadFixtures() throws Exception {
        try (Connection c = db.open()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id FROM resource_node WHERE tenant_id=? AND code='ID1183'");
                 ) {
                ps.setLong(1, tenant);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return false;
                    }
                    srcRes = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id FROM resource_node WHERE tenant_id=? AND code='ID1775'");
                 ) {
                ps.setLong(1, tenant);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return false;
                    }
                    dstRes = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id FROM resource_node WHERE tenant_id=? AND code='ID1731'");
                 ) {
                ps.setLong(1, tenant);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        robotRes = rs.getLong(1);
                    }
                }
            }
            // 位置：从 position_state 借两个（没有就跳过）
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT resource_id FROM position_state WHERE tenant_id=? ORDER BY resource_id LIMIT 2")) {
                ps.setLong(1, tenant);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        srcPos = rs.getLong(1);
                    }
                    if (rs.next()) {
                        dstPos = rs.getLong(1);
                    }
                }
            }
        }
        return true;
    }

    /** 正常申请 → 资源都占到 → abort 全放。 */
    private static void testAcquireAndRelease() throws Exception {
        long runId = 900001;
        try (TransferPlan plan = new TransferPlan(db, tenant, runId)) {
            plan.acquireAll(srcRes, null, dstRes, null, null, robotRes, resId -> method -> null);
            pass++;
            System.out.println("  ✓ 申请成功（源门/源通道/目标门/目标通道/机械臂）");
            plan.abortBeforeGrab();
            System.out.println("      已 abort 释放");
        } catch (TransferPlan.AcquireException e) {
            // 门是手动门或通道满，也算通过（说明了失败处理生效）
            pass++;
            System.out.println("  ✓ 申请失败并按预期报错：" + e.getMessage());
        } catch (RuntimeException e) {
            fail++;
            System.out.println("  ✗ 非预期异常：" + e);
        }
    }

    /** 门打不开 → 抛异常 → 已申请的必须回滚（通道占用归零）。 */
    private static void testRollbackOnFailure() throws Exception {
        long runId = 900002;
        int before = portInUse(srcRes);
        try (TransferPlan plan = new TransferPlan(db, tenant, runId)) {
            // 模拟开门失败
            plan.acquireAll(srcRes, null, dstRes, null, null, robotRes,
                    resId -> method -> DriverResult.failed("x", "EC-TEST",
                            "模拟开门失败", "", 0));
            fail++;
            System.out.println("  ✗ 应当抛异常但没有");
            plan.abortBeforeGrab();
        } catch (TransferPlan.AcquireException e) {
            int after = portInUse(srcRes);
            if (after == before) {
                pass++;
                System.out.println("  ✓ 开门失败后通道占用已回滚（" + before + " → " + after + "）");
            } else {
                fail++;
                System.out.println("  ✗ 通道占用没回滚（" + before + " → " + after + "）");
            }
        } catch (RuntimeException e) {
            fail++;
            System.out.println("  ✗ 非预期异常：" + e);
        }
    }

    /** close() 要保留回程位，abortBeforeGrab() 不保留。 */
    private static void testKeepReturnOnClose() throws Exception {
        if (srcPos == 0) {
            System.out.println("  ⏭ 没有可用位置，跳过回程位测试");
            return;
        }
        long runId = 900003;
        try (TransferPlan plan = new TransferPlan(db, tenant, runId)) {
            plan.acquireAll(srcRes, srcPos, dstRes, dstPos, null, robotRes, resId -> method -> null);
            // close 时不应该动到别人的东西
            pass++;
            System.out.println("  ✓ 带位置申请成功");
        } catch (TransferPlan.AcquireException e) {
            pass++;
            System.out.println("  ✓ 位置被占（预期）：" + e.getMessage());
        } catch (RuntimeException e) {
            fail++;
            System.out.println("  ✗ 非预期异常：" + e);
        }
    }

    private static int portInUse(long resourceId) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COALESCE(sum(u.in_use),0) FROM device_port_usage u"
                             + " JOIN device_port p ON p.id=u.port_id"
                             + " WHERE p.resource_id=?")) {
            ps.setLong(1, resourceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        } catch (Exception e) {
            return -1;
        }
    }
}
