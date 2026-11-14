package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 物料生命周期 · **两条自动卸载路径**的落库自测（需要真实库）。
 *
 * <p><b>为什么要有这个自测</b>：§4.5.2 的"实验结束自动卸载"有两条路径，
 * 而 {@code verify-material-lifecycle.py} 只覆盖了 Controller 手动入口
 * （{@code POST /api/experiment-runs/{runId}/unload-process-materials}）——
 * <b>两条自动路径都没有端到端证据</b>：
 * <ol>
 *   <li><b>非批次</b>：{@code ExperimentExecutor.settle(... syncExperimentStatus=true)}
 *       → {@code unloadProcessMaterials(runId)} → run 终态即卸载。</li>
 *   <li><b>批次</b>：{@code BatchOrchestrator.syncExperimentStatusFromBatches(experimentId)}
 *       → {@code unloadProcessMaterialsByExperiment(experimentId)}
 *       → <b>整单聚合终态</b>才卸载（跨所有批次 run）。</li>
 * </ol>
 *
 * <p><b>判别点（本自测的核心）</b>：
 * <ul>
 *   <li>批次路径：<b>只有一个批次终态时不得卸载</b>（聚合非终态），
 *       全部批次终态后才卸载 ⇒ 这条断言能区分"聚合终态才卸载"与"见一个终态就卸"。</li>
 *   <li>两条路径都断言：{@code lifecycle='process'} 的物料被卸载
 *       （placement → unloaded + position_state 清空），
 *       {@code lifecycle='inventory'} 的物料**原位不动**。</li>
 * </ul>
 *
 * <p><b>不跑 workflow、不碰设备</b>：两条路径的入口方法都**可直接调** ——
 * {@code ExperimentExecutor.settle(...)} 是包内可见（见其 JavaDoc「供 RunControlDbSelfTest
 * 直接压这条异常敏感路径」），{@code BatchOrchestrator.syncExperimentStatusFromBatches(...)} 是 public。
 * 这样自测只碰自己造的负数 id 数据，finally 清干净，且**不受设备/调度器状态影响**。
 */
public final class MaterialUnloadPathsDbSelfTest {

    private static int pass;
    private static int fail;
    private static DbConfig db;
    private static final long TENANT = 1L;

    // ---- 合成负数 id（与真实自增 id 不会撞）----
    private static final long EXP_DIRECT = -994011L;   // 非批次实验
    private static final long EXP_BATCH = -994012L;    // 批次实验
    private static final long RUN_DIRECT = -994013L;   // 非批次的 run
    private static final long RUN_BATCH1 = -994014L;   // 批次 1 的 run
    private static final long RUN_BATCH2 = -994015L;   // 批次 2 的 run
    private static final long TPL = -994016L;          // experiment_template
    private static final long MI_PROC_A = -994017L;    // process 物料（路径 A）
    private static final long MI_INV_A = -994018L;     // inventory 物料（路径 A）
    private static final long MI_PROC_B = -994019L;    // process 物料（路径 B）
    private static final long MI_INV_B = -994020L;     // inventory 物料（路径 B）
    private static final long PLATE_PROC_A = -994021L;
    private static final long PLATE_INV_A = -994022L;
    private static final long PLATE_PROC_B = -994023L;
    private static final long PLATE_INV_B = -994024L;
    private static final long BATCH1 = -994025L;
    private static final long BATCH2 = -994026L;

    // ---- 自造虚拟点位（不碰现有点位：position_state 主键是 (tenant_id, resource_id)，
    //      一点位只有一行，用现成点位会跟别人的板撞车、cleanup 也不敢删）----
    private static final long DEV_HOST = 200200L;       // ID-1021（只作归属，不真跑）
    private static final long DP_PROC_A = -994050L;
    private static final long DP_INV_A = -994051L;
    private static final long DP_PROC_B = -994052L;
    private static final long DP_INV_B = -994053L;
    private static final long RES_PROC_A = -994060L;
    private static final long RES_INV_A = -994061L;
    private static final long RES_PROC_B = -994062L;
    private static final long RES_INV_B = -994063L;

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        cleanup();
        try {
            seed();
            directPathUnloadsProcessOnly();
            batchPathWaitsForAggregateTerminal();
        } finally {
            cleanup();
        }

        System.out.println();
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ================================================================ 1. 非批次路径

    /**
     * 非批次：run 终态即卸载（settle 的 syncExperimentStatus=true 分支）。
     */
    private static void directPathUnloadsProcessOnly() throws Exception {
        section("1. 非批次路径：run 终态即卸载 process 物料，inventory 原位不动");

        ExperimentExecutor executor = new ExperimentExecutor(db, TENANT,
                new WorkflowService(db, TENANT, 18080));
        // 直接打 settle 的终态分支（包内可见）：这正是"实验跑完"时走的那条路
        executor.settle(RUN_DIRECT, "completed", 0, null, null, null, true);

        // R12：settle 只「请求卸载」（loaded→unloading），位置仍占用
        String st = placementStatus(RUN_DIRECT, MI_PROC_A);
        if ("unloading".equals(st)) {
            ok("process 物料 → unloading（请求卸载，位置未清）", st);
        } else {
            bad("process 物料未进入 unloading", "placement.status=" + st);
        }
        if (positionOccupied(RES_PROC_A)) {
            ok("请求卸载后 process 点位仍占用（未确认不清位置）", "resource=" + RES_PROC_A);
        } else {
            bad("请求卸载后 process 点位被提前清空", "resource=" + RES_PROC_A);
        }

        // R12：确认卸载（unloading→unloaded + 清位置）
        confirmUnload(RUN_DIRECT);
        st = placementStatus(RUN_DIRECT, MI_PROC_A);
        if ("unloaded".equals(st)) {
            ok("确认卸载后 process 物料 → unloaded", st);
        } else {
            bad("确认卸载后未到 unloaded", "placement.status=" + st);
        }
        if (positionOccupied(RES_PROC_A)) {
            bad("确认卸载后 process 点位仍占用", "resource=" + RES_PROC_A);
        } else {
            ok("确认卸载后 process 点位已清空", "resource=" + RES_PROC_A);
        }

        String stInv = placementStatus(RUN_DIRECT, MI_INV_A);
        if ("loaded".equals(stInv)) {
            ok("inventory 物料 placement 仍 loaded（没被卸）", stInv);
        } else {
            bad("inventory 物料被误卸", "placement.status=" + stInv);
        }
        if (positionOccupied(RES_INV_A)) {
            ok("inventory 物料点位仍占用（原位不动）", "resource=" + RES_INV_A);
        } else {
            bad("inventory 物料点位被清空了", "resource=" + RES_INV_A);
        }
    }

    // ================================================================ 2. 批次路径（判别性）

    /**
     * 批次：<b>整单聚合终态</b>才卸载 —— 单批终态时**必须还没卸**。
     */
    private static void batchPathWaitsForAggregateTerminal() throws Exception {
        section("2. 批次路径：单批终态不卸载，整单聚合终态才卸载（判别点）");

        BatchOrchestrator orch = new BatchOrchestrator(db, TENANT);

        // 批次 1 终态、批次 2 还在跑 ⇒ 聚合非终态 ⇒ 不得卸载
        setRunStatus(RUN_BATCH1, "completed");
        setBatchStatus(BATCH1, "completed");
        setBatchRunStatus(BATCH1, RUN_BATCH1, "completed");
        setRunStatus(RUN_BATCH2, "running");
        setBatchStatus(BATCH2, "running");
        setBatchRunStatus(BATCH2, RUN_BATCH2, "running");

        String agg = orch.syncExperimentStatusFromBatches(EXP_BATCH);
        if (agg != null && !"completed".equals(agg) && !"finished".equals(agg)) {
            ok("聚合非终态", "aggregate=" + agg);
        } else {
            bad("单批终态时聚合不该是终态", "aggregate=" + agg);
        }

        String st = placementStatus(RUN_BATCH1, MI_PROC_B);
        if ("loaded".equals(st)) {
            ok("★ 单批终态但整单未终态 ⇒ process 物料**未卸载**（判别点）", st);
        } else {
            bad("单批终态就卸载了 —— 没有等整单聚合终态", "placement.status=" + st);
        }
        if (positionOccupied(RES_PROC_B)) {
            ok("单批终态时 process 点位仍占用", "resource=" + RES_PROC_B);
        } else {
            bad("单批终态时 process 点位已被清空", "resource=" + RES_PROC_B);
        }

        // 全部批次终态 ⇒ 聚合终态 ⇒ 卸载
        setRunStatus(RUN_BATCH2, "completed");
        setBatchStatus(BATCH2, "completed");
        setBatchRunStatus(BATCH2, RUN_BATCH2, "completed");

        agg = orch.syncExperimentStatusFromBatches(EXP_BATCH);
        if ("completed".equals(agg) || "finished".equals(agg)) {
            ok("全部批次终态 ⇒ 聚合终态", "aggregate=" + agg);
        } else {
            bad("全部批次终态后聚合仍非终态", "aggregate=" + agg);
        }

        st = placementStatus(RUN_BATCH1, MI_PROC_B);
        if ("unloading".equals(st)) {
            ok("整单终态后 process 物料 → unloading（请求卸载）", st);
        } else {
            bad("整单终态后 process 物料未进入 unloading", "placement.status=" + st);
        }
        if (positionOccupied(RES_PROC_B)) {
            ok("请求卸载后 process 点位仍占用", "resource=" + RES_PROC_B);
        } else {
            bad("请求卸载后 process 点位被提前清空", "resource=" + RES_PROC_B);
        }

        // R12：确认卸载（unloading→unloaded + 清位置）
        confirmUnloadByExperiment(EXP_BATCH);
        st = placementStatus(RUN_BATCH1, MI_PROC_B);
        if ("unloaded".equals(st)) {
            ok("确认卸载后 process 物料跨批次被卸载", st);
        } else {
            bad("确认卸载后 process 物料未 unloaded", "placement.status=" + st);
        }
        if (positionOccupied(RES_PROC_B)) {
            bad("确认卸载后 process 点位仍占用", "resource=" + RES_PROC_B);
        } else {
            ok("确认卸载后 process 点位已清空", "resource=" + RES_PROC_B);
        }

        String stInv = placementStatus(RUN_BATCH1, MI_INV_B);
        if ("loaded".equals(stInv)) {
            ok("inventory 物料原位不动（批次路径同样）", stInv);
        } else {
            bad("inventory 物料被误卸", "placement.status=" + stInv);
        }
    }

    // ================================================================ 造数 / 清理

    private static void seed() throws SQLException {
        insertVirtualPoint(RES_PROC_A, DP_PROC_A, "VERIFY-UNLOAD-PA-PT");
        insertVirtualPoint(RES_INV_A, DP_INV_A, "VERIFY-UNLOAD-IA-PT");
        insertVirtualPoint(RES_PROC_B, DP_PROC_B, "VERIFY-UNLOAD-PB-PT");
        insertVirtualPoint(RES_INV_B, DP_INV_B, "VERIFY-UNLOAD-IB-PT");

        // 非批次实验 + run
        exec("INSERT INTO experiment (id, tenant_id, experiment_no, name, island_id) "
                + "VALUES (?, ?, 'VERIFY-UNLOAD-DIRECT', '非批次·自动卸载', 0)",
                EXP_DIRECT, TENANT);
        exec("INSERT INTO experiment_run (id, tenant_id, experiment_id, status) "
                + "VALUES (?, ?, ?, 'running')", RUN_DIRECT, TENANT, EXP_DIRECT);

        // 批次实验 + 两批 + 两个 run
        exec("INSERT INTO experiment (id, tenant_id, experiment_no, name, island_id, batch_split) "
                + "VALUES (?, ?, 'VERIFY-UNLOAD-BATCH', '批次·自动卸载', 0, 2)",
                EXP_BATCH, TENANT);
        exec("INSERT INTO experiment_run (id, tenant_id, experiment_id, status) "
                + "VALUES (?, ?, ?, 'running')", RUN_BATCH1, TENANT, EXP_BATCH);
        exec("INSERT INTO experiment_run (id, tenant_id, experiment_id, status) "
                + "VALUES (?, ?, ?, 'running')", RUN_BATCH2, TENANT, EXP_BATCH);
        exec("INSERT INTO experiment_batch (id, tenant_id, experiment_id, batch_no, sample_count, status) "
                + "VALUES (?, ?, ?, 1, 0, 'running')", BATCH1, TENANT, EXP_BATCH);
        exec("INSERT INTO experiment_batch (id, tenant_id, experiment_id, batch_no, sample_count, status) "
                + "VALUES (?, ?, ?, 2, 0, 'running')", BATCH2, TENANT, EXP_BATCH);
        exec("INSERT INTO experiment_batch_run (id, tenant_id, batch_id, experiment_run_id, attempt_no, status) "
                + "VALUES (?, ?, ?, ?, 1, 'running')", -994032L, TENANT, BATCH1, RUN_BATCH1);
        exec("INSERT INTO experiment_batch_run (id, tenant_id, batch_id, experiment_run_id, attempt_no, status) "
                + "VALUES (?, ?, ?, ?, 1, 'running')", -994033L, TENANT, BATCH2, RUN_BATCH2);

        // 模板 + 4 块板 + 4 条 manifest item
        exec("INSERT INTO experiment_template (id, tenant_id, template_no, name, island_id) "
                + "VALUES (?, ?, 'VERIFY-UNLOAD-TPL', '卸载验证模板', 0)", TPL, TENANT);
        exec("INSERT INTO plate (id, tenant_id, barcode, lifecycle) VALUES (?, ?, ?, 'process')",
                PLATE_PROC_A, TENANT, "VERIFY-UNLOAD-PA");
        exec("INSERT INTO plate (id, tenant_id, barcode, lifecycle) VALUES (?, ?, ?, 'inventory')",
                PLATE_INV_A, TENANT, "VERIFY-UNLOAD-IA");
        exec("INSERT INTO plate (id, tenant_id, barcode, lifecycle) VALUES (?, ?, ?, 'process')",
                PLATE_PROC_B, TENANT, "VERIFY-UNLOAD-PB");
        exec("INSERT INTO plate (id, tenant_id, barcode, lifecycle) VALUES (?, ?, ?, 'inventory')",
                PLATE_INV_B, TENANT, "VERIFY-UNLOAD-IB");

        insertManifestItem(MI_PROC_A, "proc_a", PLATE_PROC_A);
        insertManifestItem(MI_INV_A, "inv_a", PLATE_INV_A);
        insertManifestItem(MI_PROC_B, "proc_b", PLATE_PROC_B);
        insertManifestItem(MI_INV_B, "inv_b", PLATE_INV_B);

        // placement（loaded）+ position_state（占用）
        insertPlacement(-994040L, RUN_DIRECT, MI_PROC_A, DP_PROC_A, RES_PROC_A,
                PLATE_PROC_A, "VERIFY-UNLOAD-PA");
        insertPlacement(-994041L, RUN_DIRECT, MI_INV_A, DP_INV_A, RES_INV_A,
                PLATE_INV_A, "VERIFY-UNLOAD-IA");
        insertPlacement(-994042L, RUN_BATCH1, MI_PROC_B, DP_PROC_B, RES_PROC_B,
                PLATE_PROC_B, "VERIFY-UNLOAD-PB");
        insertPlacement(-994043L, RUN_BATCH1, MI_INV_B, DP_INV_B, RES_INV_B,
                PLATE_INV_B, "VERIFY-UNLOAD-IB");
    }

    /** 造一个虚拟点位：resource_node(kind=storage) + device_point（归属 ID-1021）。 */
    private static void insertVirtualPoint(long resourceId, long dpId, String code) throws SQLException {
        exec("INSERT INTO resource_node (id, tenant_id, code, name, kind, parent_id) "
                + "VALUES (?, ?, ?, ?, 'storage', ?)", resourceId, TENANT, code, code, DEV_HOST);
        // point_role='both' ⇒ 进出位至少一个（ck_device_point_flags）
        exec("INSERT INTO device_point (id, tenant_id, device_resource_id, point_resource_id, "
                + "point_role, robot_reachable, is_entry, is_exit, point_kind) "
                + "VALUES (?, ?, ?, ?, 'both', true, true, true, 'nest')",
                dpId, TENANT, DEV_HOST, resourceId);
    }

    private static void insertManifestItem(long id, String role, long materialRef) throws SQLException {
        exec("INSERT INTO manifest_item (id, tenant_id, template_id, role_code, material_kind, "
                + "material_ref, required) VALUES (?, ?, ?, ?, 'plate', ?, true)",
                id, TENANT, TPL, role, materialRef);
    }

    private static void insertPlacement(long id, long runId, long itemId, long dpId,
                                        long resourceId, long plateId, String barcode)
            throws SQLException {
        exec("INSERT INTO manifest_placement (id, tenant_id, run_id, manifest_item_id, position_id, status) "
                + "VALUES (?, ?, ?, ?, ?, 'loaded')", id, TENANT, runId, itemId, dpId);
        // position_state.plate_id 是**条码**（varchar），content_id 才是 plate.id
        exec("INSERT INTO position_state (tenant_id, resource_id, plate_id, content_kind, content_id, occupied_by_run) "
                + "VALUES (?, ?, ?, 'plate', ?, ?)", TENANT, resourceId, barcode, plateId, runId);
    }

    /**
     * 清理：**每条独立事务**。
     *
     * <p>为什么不用一个大事务：一条失败后面全部 aborted，清理等于没做
     * （下次 seed 直接撞唯一键，uk_resource_node / uk_device_point 都会撞）。
     */
    private static void cleanup() {
        // ⚠ 按 **resource_id** 清，不按 plate_id：被卸载的点位 plate_id 已被置 NULL，
        //   按条码删不到 —— 上一次跑完就留下了残留（下次 seed 撞 position_state 主键）。
        del("position_state", "DELETE FROM position_state WHERE resource_id IN (?, ?, ?, ?)",
                RES_PROC_A, RES_INV_A, RES_PROC_B, RES_INV_B);
        del("manifest_placement", "DELETE FROM manifest_placement WHERE id IN (?, ?, ?, ?)",
                -994040L, -994041L, -994042L, -994043L);
        del("device_point", "DELETE FROM device_point WHERE id IN (?, ?, ?, ?)",
                DP_PROC_A, DP_INV_A, DP_PROC_B, DP_INV_B);
        del("resource_node", "DELETE FROM resource_node WHERE id IN (?, ?, ?, ?)",
                RES_PROC_A, RES_INV_A, RES_PROC_B, RES_INV_B);
        del("manifest_item", "DELETE FROM manifest_item WHERE id IN (?, ?, ?, ?)",
                MI_PROC_A, MI_INV_A, MI_PROC_B, MI_INV_B);
        del("plate", "DELETE FROM plate WHERE id IN (?, ?, ?, ?)",
                PLATE_PROC_A, PLATE_INV_A, PLATE_PROC_B, PLATE_INV_B);
        del("experiment_batch_run", "DELETE FROM experiment_batch_run WHERE id IN (?, ?)",
                -994032L, -994033L);
        del("experiment_batch", "DELETE FROM experiment_batch WHERE id IN (?, ?)",
                BATCH1, BATCH2);
        // ⚠ 按 **experiment_id** 清，不按 id 清：执行器可能给实验**新建**真实 id 的 run
        //   （早期版本跑过一次 executor.run() 就留下了一条 id=1003296 的 run，
        //    按 id 清会漏，然后 experiment 的删除被 FK 挡住 —— 清理等于没做）。
        del("experiment_run", "DELETE FROM experiment_run WHERE experiment_id IN (?, ?)",
                EXP_DIRECT, EXP_BATCH);
        del("experiment_process", "DELETE FROM experiment_process WHERE experiment_id IN (?, ?)",
                EXP_DIRECT, EXP_BATCH);
        del("experiment", "DELETE FROM experiment WHERE id IN (?, ?)",
                EXP_DIRECT, EXP_BATCH);
        del("experiment_template", "DELETE FROM experiment_template WHERE id = ?", TPL);
    }

    // ================================================================ 小工具

    private static String placementStatus(long runId, long itemId) throws SQLException {
        return queryOne("SELECT status FROM manifest_placement "
                + "WHERE tenant_id = ? AND run_id = ? AND manifest_item_id = ?",
                TENANT, runId, itemId);
    }

    /** R12 确认卸载（unloading→unloaded + 清位置），直接打 Mapper（与 Controller 同逻辑）。 */
    private static void confirmUnload(long runId) throws SQLException {
        com.labos.kernel.persistence.ManifestMapper mm = com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.ManifestMapper.class);
        com.labos.kernel.persistence.Tx.inTransaction(db, c -> {
            mm.clearProcessPositionStates(TENANT, runId);
            mm.markProcessPlacementsUnloaded(TENANT, runId);
            return null;
        });
    }

    private static void confirmUnloadByExperiment(long experimentId) throws SQLException {
        com.labos.kernel.persistence.ManifestMapper mm = com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.ManifestMapper.class);
        com.labos.kernel.persistence.Tx.inTransaction(db, c -> {
            mm.clearProcessPositionStatesByExperiment(TENANT, experimentId);
            mm.markProcessPlacementsUnloadedByExperiment(TENANT, experimentId);
            return null;
        });
    }

    private static boolean positionOccupied(long resourceId) throws SQLException {
        String v = queryOne("SELECT CASE WHEN plate_id IS NULL THEN 'f' ELSE 't' END "
                + "FROM position_state WHERE tenant_id = ? AND resource_id = ?",
                TENANT, resourceId);
        return "t".equals(v);
    }

    private static void setRunStatus(long runId, String status) throws SQLException {
        exec("UPDATE experiment_run SET status = ?, finished_at = now() "
                + "WHERE tenant_id = ? AND id = ?", status, TENANT, runId);
    }

    private static void setBatchStatus(long batchId, String status) throws SQLException {
        exec("UPDATE experiment_batch SET status = ? WHERE tenant_id = ? AND id = ?",
                status, TENANT, batchId);
    }

    private static void setBatchRunStatus(long batchId, long runId, String status) throws SQLException {
        exec("UPDATE experiment_batch_run SET status = ? "
                + "WHERE tenant_id = ? AND batch_id = ? AND experiment_run_id = ?",
                status, TENANT, batchId, runId);
    }

    private static String queryOne(String sql, Object... args) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static void exec(String sql, Object... args) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    /** 清理用：单条独立事务 + 容错，失败打印但不中断（清理要尽力，失败要看得见）。 */
    private static void del(String label, String sql, Object... args) {
        try (Connection c = db.open()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    for (int i = 0; i < args.length; i++) {
                        ps.setObject(i + 1, args[i]);
                    }
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                System.out.println("  [清理] " + label + " 失败："
                        + e.getMessage().replace('\n', ' '));
            }
        } catch (SQLException e) {
            System.out.println("  [清理] " + label + " 连接失败：" + e.getMessage());
        }
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("── " + title + " ──");
    }

    private static void ok(String msg, String detail) {
        pass++;
        System.out.println("  ✓ " + msg + (detail == null || detail.isEmpty() ? "" : "（" + detail + "）"));
    }

    private static void bad(String msg, String detail) {
        fail++;
        System.out.println("  ✗ " + msg + (detail == null || detail.isEmpty() ? "" : "（" + detail + "）"));
    }
}
