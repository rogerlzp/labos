package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Set;

/**
 * 实验级运行控制 落库自测（需要真实库）。
 *
 * <p>三条此前<b>零测试</b>的安全关键路径，这里逐条压住：
 * <ol>
 *   <li><b>pause 覆盖范围</b>：岛级暂停必须把同岛其他实验一起停（机械臂分不清板子归属），
 *       但不该动到别的岛/未分配岛的实验；未分配岛时退化为实验级，只停自己。</li>
 *   <li><b>stop 双分支</b>：运行中只落 {@code stop_requested}（有动作在进行，不能硬杀）；
 *       非运行态直接落 {@code aborted}；已是终态再停返回 false。</li>
 *   <li><b>resume 断点续跑基线</b>：恢复只把 run 拉回 {@code queued}，
 *       <b>不清 {@code experiment_run_step}</b>；已完成的步（{@code status='completed'}）
 *       由执行器跳过，被中断的步（{@code status='paused'}）重跑。</li>
 * </ol>
 *
 * <p>用合成 id（负数、与真实自增 id 不可能撞）并在 finally 里清干净。
 * <b>不用 site 范围</b> —— 全场闸门会把正在跑的实验一起停掉，自测不能这么干。
 *
 * <p><b>本自测逼出的一个真实缺陷</b>：{@code markRunning} / {@code syncExperimentStatus}
 * 曾把 {@code experiment_run.id} 写进 {@code experiment.run_id}，而后者外键指向
 * {@code run(id)} —— 两张表各自独立自增，不是一个 id 空间。线上能跑通只是因为
 * {@code experiment_run} 的自增 id（当时 39）恰好落在 {@code run} 的连续段内
 * （实测 run id 1..197 连续、198 起有空洞）：再跑约 158 次实验，起跑处就会抛 23503。
 * 现已改为不写该列；本自测特意用<b>没有对应 run 行</b>的合成 id，守住这条不变量。
 */
public final class RunControlDbSelfTest {

    private static int pass;
    private static int fail;
    private static DbConfig db;
    private static final long TENANT = 1L;

    /**
     * 合成岛 id <b>必须为正</b>：{@code RunControl.effectiveScope} 用 {@code islandId > 0}
     * 区分"已分配岛"与"未分配（0）"，负数会被当成未分配而退化成实验级。
     * 取 990001，远离真实岛区间（线上约 100027~100033），避免扣留误伤真实实验。
     */
    private static final long ISL = 990001L;

    private static final long EXP_A = -992010L;   // 岛 ISL
    private static final long EXP_B = -992011L;   // 岛 ISL
    private static final long EXP_C = -992012L;   // 未分配岛
    private static final long EXP_D = -992013L;   // 未分配岛
    private static final long EXP_E = -992014L;   // 岛 ISL，停止用
    private static final long EXP_F = -992015L;   // 岛 ISL，恢复用
    private static final long EXP_G = -992016L;   // 岛 ISL，流程级 run 作废验证用

    private static final long RUN_A = -992101L;   // EXP_A，queued
    private static final long RUN_B = -992102L;   // EXP_B，running
    private static final long RUN_C = -992103L;   // EXP_C，running
    private static final long RUN_D = -992104L;   // EXP_D，running
    private static final long RUN_ER = -992105L;  // EXP_E，running
    private static final long RUN_EQ = -992106L;  // EXP_E，queued
    private static final long RUN_F = -992107L;   // EXP_F，paused
    private static final long RUN_G = -992111L;   // EXP_G，paused（验证流程级 run 作废）

    /**
     * 流程级 {@code run}（挂在 experiment_run_step.run_id 上）：
     * 实验级暂停会把当前步骤的 workflow 级 run 也落成 paused（见
     * {@code WorkflowService.pauseRunAtBoundary}），恢复时要把它作废，
     * 否则它会一直挂在暂停队列里、被误点「继续」会重做一遍设备动作。
     */
    private static final long RUN_WF_PAUSED = -992108L;  // 被中断步的流程级 run：paused
    private static final long RUN_WF_DONE = -992109L;    // 已完成步的流程级 run：completed（对照）

    private static final long STEP_1 = -992201L;
    private static final long STEP_2 = -992202L;
    private static final long STEP_3 = -992203L;
    private static final long STEP_4 = -992204L;
    private static final long STEP_G_DONE = -992205L;    // RUN_G seq1，completed，挂 RUN_WF_DONE
    private static final long STEP_G_PAUSED = -992206L;  // RUN_G seq2，paused，挂 RUN_WF_PAUSED
    private static final long WF_DUMMY = -992999L;   // experiment_run_step.workflow_id 无外键

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        cleanup();
        try {
            RunControl rc = new RunControl(db, TENANT);

            pauseIslandCoverage(rc);
            pauseNoIslandDegradesToExperiment(rc);
            stopTwoBranches(rc);
            resumeKeepsCompletedSteps();
            resumeClearsWhatItMay(rc);
            resumeSupersedesInterruptedWorkflowRun(rc);
            pauseProtectionKeepsPaused();
        } finally {
            cleanup();
        }

        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 1. 岛级暂停

    private static void pauseIslandCoverage(RunControl rc) throws Exception {
        seed();
        new RuntimeHold(db, TENANT).clear(RuntimeHold.Scope.ISLAND, ISL, null, "自测预清理");

        rc.pause(rc.info(RUN_A), RuntimeHold.Scope.ISLAND, RuntimeHold.Kind.MANUAL,
                "自测：岛级人工暂停", null);

        RuntimeHold.Active h = new RuntimeHold(db, TENANT).coveringFor(EXP_A, ISL);
        check("岛级暂停抬出岛级扣留",
                h != null && RuntimeHold.Scope.ISLAND.code().equals(h.scope()), h);
        check("界面看到的扣留原因是人写的",
                h != null && "自测：岛级人工暂停".equals(h.reason()), h == null ? null : h.reason());
        // S-04-B：受理时只写控制状态，不假写 paused（真正收敛在执行器边界/调度器）
        check("同岛排队实验：control_state=PAUSE_REQUESTED",
                "PAUSE_REQUESTED".equals(strOf(RUN_A, "control_state")),
                strOf(RUN_A, "control_state"));
        check("同岛运行实验：control_state=PAUSE_REQUESTED",
                "PAUSE_REQUESTED".equals(strOf(RUN_B, "control_state")),
                strOf(RUN_B, "control_state"));
        check("受理时排队 run 的 status 仍是 queued",
                "queued".equals(statusOf(RUN_A)), statusOf(RUN_A));
        check("受理时运行 run 的 status 仍是 running",
                "running".equals(statusOf(RUN_B)), statusOf(RUN_B));
        check("别岛/无岛实验不被牵连", "running".equals(statusOf(RUN_C)), statusOf(RUN_C));
        check("受理的 run 记下扣留类型",
                "manual".equals(strOf(RUN_A, "pause_kind")), strOf(RUN_A, "pause_kind"));

        // S-04-B 收敛：排队 run 由调度器 convergeQueuedPauses 收成 paused；
        // 运行 run 必须留给执行器在动作边界 settle，不能被提前写 paused。
        int converged = RunControl.convergeQueuedPauses(db, TENANT);
        check("调度收敛把排队 run 收成 paused（" + converged + " 条）",
                "paused".equals(statusOf(RUN_A)), statusOf(RUN_A));
        check("收敛后 control_state 回到 NONE",
                "NONE".equals(strOf(RUN_A, "control_state")), strOf(RUN_A, "control_state"));
        check("在跑的 run 不被收敛抢写（仍需执行器边界）",
                "running".equals(statusOf(RUN_B)), statusOf(RUN_B));

        new RuntimeHold(db, TENANT).clear(RuntimeHold.Scope.ISLAND, ISL, null, "自测：清岛级");
        // 清完之后不应该还有覆盖
        check("解除后不再覆盖",
                new RuntimeHold(db, TENANT).coveringFor(EXP_A, ISL) == null,
                new RuntimeHold(db, TENANT).coveringFor(EXP_A, ISL));
    }

    // ---------------------------------------------------------------- 2. 未分配岛退化

    private static void pauseNoIslandDegradesToExperiment(RunControl rc) throws Exception {
        setRunStatus(RUN_C, "running");
        setRunStatus(RUN_D, "running");

        rc.pause(rc.info(RUN_C), RuntimeHold.Scope.ISLAND, RuntimeHold.Kind.MANUAL,
                "自测：无岛实验按岛暂停", null);

        RuntimeHold.Active h = new RuntimeHold(db, TENANT).coveringFor(EXP_C, 0L);
        check("无岛时退化为实验级扣留",
                h != null && RuntimeHold.Scope.EXPERIMENT.code().equals(h.scope()), h);
        check("退化后只扣自己这条实验",
                h != null && h.scopeId() != null && h.scopeId() == EXP_C,
                h == null ? null : h.scopeId());
        check("自己被受理：control_state=PAUSE_REQUESTED",
                "PAUSE_REQUESTED".equals(strOf(RUN_C, "control_state")),
                strOf(RUN_C, "control_state"));
        check("受理时 status 仍是 running（不假写 paused）",
                "running".equals(statusOf(RUN_C)), statusOf(RUN_C));
        check("同无岛的别的实验不受影响", "running".equals(statusOf(RUN_D)), statusOf(RUN_D));

        new RuntimeHold(db, TENANT).clear(RuntimeHold.Scope.EXPERIMENT, EXP_C, null, "自测：清实验级");
    }

    // ---------------------------------------------------------------- 3. 停止双分支

    private static void stopTwoBranches(RunControl rc) throws Exception {
        setRunStatus(RUN_ER, "running");
        setRunStatus(RUN_EQ, "queued");

        boolean changedRunning = rc.stop(rc.info(RUN_ER), null);
        check("运行中：停止请求被接受", changedRunning, changedRunning);
        check("运行中：不硬杀，仍是 running", "running".equals(statusOf(RUN_ER)), statusOf(RUN_ER));
        check("运行中：落下停止请求", boolOf(RUN_ER, "stop_requested"), boolOf(RUN_ER, "stop_requested"));

        boolean changedQueued = rc.stop(rc.info(RUN_EQ), null);
        check("非运行态：直接落终态", changedQueued, changedQueued);
        check("排队停下即 aborted", "aborted".equals(statusOf(RUN_EQ)), statusOf(RUN_EQ));
        check("aborted 也带停止标记", boolOf(RUN_EQ, "stop_requested"), boolOf(RUN_EQ, "stop_requested"));
        check("aborted 有结束时间", strOf(RUN_EQ, "finished_at") != null, strOf(RUN_EQ, "finished_at"));
        check("aborted 有可读原因",
                "人工停止".equals(strOf(RUN_EQ, "error")), strOf(RUN_EQ, "error"));

        boolean again = rc.stop(rc.info(RUN_EQ), null);
        check("已是终态再停返回 false", !again, again);
        check("实验表跟随到 aborted",
                "aborted".equals(strOfExperiment(EXP_E, "status")), strOfExperiment(EXP_E, "status"));
        check("实验表不写 run_id（该列外键指向 run 表，与 experiment_run.id 不是一个 id 空间）",
                strOfExperiment(EXP_E, "run_id") == null, strOfExperiment(EXP_E, "run_id"));
    }

    // ---------------------------------------------------------------- 4. 恢复保留断点

    private static void resumeKeepsCompletedSteps() throws Exception {
        setRunStatus(RUN_F, "paused");
        setRunStr(RUN_F, "pause_kind", "manual");
        setRunStr(RUN_F, "pause_reason", "自测：先暂停");
        setRunBool(RUN_F, "stop_requested", true);
        seedStepsForRunF();

        ExperimentExecutor ex = new ExperimentExecutor(db, TENANT, null);

        Set<String> keysBefore = ex.completedStepKeys(RUN_F);
        check("断点集只含 completed 步", keysBefore.size() == 2, keysBefore);
        check("断点集含 seq1:loop0", keysBefore.contains("1:0"), keysBefore);
        check("断点集含 seq1:loop1（循环也要认）", keysBefore.contains("1:1"), keysBefore);
        check("恢复前停止请求为真", ex.stopRequested(RUN_F), ex.stopRequested(RUN_F));

        RunControl rc = new RunControl(db, TENANT);
        rc.resume(rc.info(RUN_F), null, "自测：恢复");

        check("恢复后回到 queued", "queued".equals(statusOf(RUN_F)), statusOf(RUN_F));
        check("恢复后清掉停止请求", !ex.stopRequested(RUN_F), ex.stopRequested(RUN_F));
        check("恢复后清掉暂停类型", strOf(RUN_F, "pause_kind") == null, strOf(RUN_F, "pause_kind"));
        check("恢复后清掉变更前的错误", strOf(RUN_F, "error") == null, strOf(RUN_F, "error"));

        Set<String> keysAfter = ex.completedStepKeys(RUN_F);
        check("恢复不清步记录（断点续跑前提）", keysAfter.size() == 2, keysAfter);
        check("被中断的步仍不在断点集", !keysAfter.contains("2:0"), keysAfter);
    }

    // ---------------------------------------------------------------- 5. 该解除的才解除

    private static void resumeClearsWhatItMay(RunControl rc) throws Exception {
        RuntimeHold holds = new RuntimeHold(db, TENANT);
        boolean raised = holds.raise(RuntimeHold.Scope.ISLAND, ISL, RuntimeHold.Kind.FAULT,
                "自测：岛级故障", null);
        check("先抬一道岛级扣留", raised, raised);

        RunControl.ResumeOutcome out = rc.resume(rc.info(RUN_F), null, "自测：恢复并解扣留");
        check("岛级扣留被这次恢复解除", out.cleared(), out.cleared());
        check("解除后无残留覆盖",
                new RuntimeHold(db, TENANT).coveringFor(EXP_F, ISL) == null,
                new RuntimeHold(db, TENANT).coveringFor(EXP_F, ISL));
        check("没有'仍被扣留'提示", out.stillHeldReason() == null, out.stillHeldReason());
    }

    // ---------------------------------------------------------------- 5.1 作废被中断的流程级 run

    /**
     * 2026-11-06 修复引入的回归守门：实验级暂停会把当前步的<b>流程级</b> {@code run}
     * 也落成 {@code paused}（{@code WorkflowService.pauseRunAtBoundary}）。恢复时这一步会被
     * <b>整段重跑</b>、另起一条新 run，那条旧 paused run 若不处理就会一直挂在暂停队列
     * （{@code PauseManager.pausedRuns} 查 {@code run.status='paused'}）里，
     * 值班员误点「继续」会让机械臂把同一个动作再做一遍。
     *
     * <p>这里造一条 {@code RUN_G}：一个已完成步（其流程级 run 已 completed）+ 一个被中断步
     * （其流程级 run 停在 paused），调真实 {@code resume}，断言被中断那条被作废、已完成那条不动。
     */
    private static void resumeSupersedesInterruptedWorkflowRun(RunControl rc) throws Exception {
        seedWorkflowStepsForRunG();

        rc.resume(rc.info(RUN_G), null, "自测：恢复并作废被中断的流程级 run");

        check("被中断步的流程级 run 被作废为 aborted",
                "aborted".equals(runStatusOf(RUN_WF_PAUSED)), runStatusOf(RUN_WF_PAUSED));
        check("已完成的流程级 run 不受影响",
                "completed".equals(runStatusOf(RUN_WF_DONE)), runStatusOf(RUN_WF_DONE));
        check("作废的流程级 run 补了结束时间",
                strOfRun(RUN_WF_PAUSED, "actual_end") != null, strOfRun(RUN_WF_PAUSED, "actual_end"));
    }

    // ---------------------------------------------------------------- 6. 暂停保护不引不存在列

    /**
     * 2026-11-06 实测缺陷回归：{@code markRunning} 的「保持 paused」分支与
     * {@code settle} 的「已被暂停不覆盖」分支，曾把 {@code updated_at = now()} 写进
     * {@code experiment_run} —— 而该表<b>没有这一列</b>（只有 {@code experiment} 有）。
     * 一命中就抛 PSQLException，把「刚点了暂停」的 run 直接落成 failed。
     *
     * <p>这里走<b>真实代码路径</b>（不是"看 SQL 长得对不对"）：造一条 paused 的 run
     * 加一道生效扣留，依次调两个方法，断言不抛、且状态始终是 paused。
     */
    private static void pauseProtectionKeepsPaused() throws Exception {
        new RuntimeHold(db, TENANT).clear(RuntimeHold.Scope.ISLAND, ISL, null, "自测预清理");
        setRunStatus(RUN_A, "paused");
        new RuntimeHold(db, TENANT).raise(RuntimeHold.Scope.ISLAND, ISL,
                RuntimeHold.Kind.MANUAL, "自测：暂停保护", null);

        ExperimentExecutor ex = new ExperimentExecutor(db, TENANT, null);

        boolean markOk = true;
        try {
            ex.markRunning(RUN_A);
        } catch (SQLException e) {
            markOk = false;
        }
        check("markRunning 保 paused 分支不抛", markOk, markOk);
        check("markRunning 后仍是 paused", "paused".equals(statusOf(RUN_A)), statusOf(RUN_A));

        boolean settleOk = true;
        try {
            ex.settle(RUN_A, "completed", 1, null, null, null, false);
        } catch (SQLException e) {
            settleOk = false;
        }
        check("settle 不覆盖分支不抛", settleOk, settleOk);
        check("settle 后不被 completed 盖掉", "paused".equals(statusOf(RUN_A)), statusOf(RUN_A));

        new RuntimeHold(db, TENANT).clear(RuntimeHold.Scope.ISLAND, ISL, null, "自测：清暂停保护");
    }

    // ---------------------------------------------------------------- 造数 / 查询 / 清理

    private static void seed() throws SQLException {
        try (Connection c = db.open()) {
            insertExperiment(c, EXP_A, "SELF-RC-A", ISL);
            insertExperiment(c, EXP_B, "SELF-RC-B", ISL);
            insertExperiment(c, EXP_C, "SELF-RC-C", null);
            insertExperiment(c, EXP_D, "SELF-RC-D", null);
            insertExperiment(c, EXP_E, "SELF-RC-E", ISL);
            insertExperiment(c, EXP_F, "SELF-RC-F", ISL);
            insertExperiment(c, EXP_G, "SELF-RC-G", ISL);

            insertRun(c, RUN_A, EXP_A, "queued");
            insertRun(c, RUN_B, EXP_B, "running");
            insertRun(c, RUN_C, EXP_C, "running");
            insertRun(c, RUN_D, EXP_D, "running");
            insertRun(c, RUN_ER, EXP_E, "running");
            insertRun(c, RUN_EQ, EXP_E, "queued");
            insertRun(c, RUN_F, EXP_F, "paused");
            insertRun(c, RUN_G, EXP_G, "paused");

            // 流程级 run：RUN_WF_PAUSED 挂在「被中断步」上，恢复时应被作废；RUN_WF_DONE 是已完成步的对照。
            insertWorkflowRun(c, RUN_WF_DONE, "SELF-WF-DONE", "completed");
            insertWorkflowRun(c, RUN_WF_PAUSED, "SELF-WF-PAUSED", "paused");
        }
    }

    /** 流程级 {@code run}（experiment_run_step.run_id 指向它，无外键）。 */
    private static void insertWorkflowRun(Connection c, long id, String runNo, String status)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO run (id, tenant_id, run_no, run_type, status)
                VALUES (?, ?, ?, 'workflow', ?::run_status)
                """)) {
            ps.setLong(1, id);
            ps.setLong(2, TENANT);
            ps.setString(3, runNo);
            ps.setString(4, status);
            ps.executeUpdate();
        }
    }

    private static void insertExperiment(Connection c, long id, String no, Long islandId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO experiment (id, tenant_id, experiment_no, name, status, island_id)
                VALUES (?, ?, ?, ?, 'created', ?)
                """)) {
            ps.setLong(1, id);
            ps.setLong(2, TENANT);
            ps.setString(3, no);
            ps.setString(4, no);
            if (islandId == null) {
                ps.setNull(5, Types.BIGINT);
            } else {
                ps.setLong(5, islandId);
            }
            ps.executeUpdate();
        }
    }

    private static void insertRun(Connection c, long id, long experimentId, String status)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO experiment_run (id, tenant_id, experiment_id, status)
                VALUES (?, ?, ?, ?)
                """)) {
            ps.setLong(1, id);
            ps.setLong(2, TENANT);
            ps.setLong(3, experimentId);
            ps.setString(4, status);
            ps.executeUpdate();
        }
    }

    /** RUN_F 的步：1:0 与 1:1 已完成，2:0 被暂停中断，3:0 还没跑。 */
    private static void seedStepsForRunF() throws SQLException {
        insertStep(STEP_1, RUN_F, 1, 0, "completed");
        insertStep(STEP_2, RUN_F, 1, 1, "completed");
        insertStep(STEP_3, RUN_F, 2, 0, "paused");
        insertStep(STEP_4, RUN_F, 3, 0, "pending");
    }

    /** RUN_G 的步：1:0 已完成（挂已完成流程级 run），2:0 被中断（挂 paused 流程级 run）。 */
    private static void seedWorkflowStepsForRunG() throws SQLException {
        insertStepWithRun(STEP_G_DONE, RUN_G, 1, 0, "completed", RUN_WF_DONE);
        insertStepWithRun(STEP_G_PAUSED, RUN_G, 2, 0, "paused", RUN_WF_PAUSED);
    }

    private static void insertStep(long id, long runId, int seq, int loopIndex, String status)
            throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO experiment_run_step
                         (id, tenant_id, experiment_run_id, seq, loop_index, workflow_id, status)
                     VALUES (?, ?, ?, ?, ?, ?, ?)
                     """)) {
            ps.setLong(1, id);
            ps.setLong(2, TENANT);
            ps.setLong(3, runId);
            ps.setInt(4, seq);
            ps.setInt(5, loopIndex);
            ps.setLong(6, WF_DUMMY);
            ps.setString(7, status);
            ps.executeUpdate();
        }
    }

    /** 同 {@link #insertStep}，但额外把步关联到它的流程级 {@code run}（{@code run_id}）。 */
    private static void insertStepWithRun(long id, long runId, int seq, int loopIndex,
                                          String status, long wfRunId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO experiment_run_step
                         (id, tenant_id, experiment_run_id, seq, loop_index, workflow_id, run_id, status)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            ps.setLong(1, id);
            ps.setLong(2, TENANT);
            ps.setLong(3, runId);
            ps.setInt(4, seq);
            ps.setInt(5, loopIndex);
            ps.setLong(6, WF_DUMMY);
            ps.setLong(7, wfRunId);
            ps.setString(8, status);
            ps.executeUpdate();
        }
    }

    private static void setRunStatus(long runId, String status) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     UPDATE experiment_run SET status = ?, control_state = 'NONE'
                      WHERE tenant_id = ? AND id = ?
                     """)) {
            ps.setString(1, status);
            ps.setLong(2, TENANT);
            ps.setLong(3, runId);
            ps.executeUpdate();
        }
    }

    private static void setRunStr(long runId, String column, String value) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE experiment_run SET " + column + " = ? WHERE tenant_id = ? AND id = ?")) {
            ps.setString(1, value);
            ps.setLong(2, TENANT);
            ps.setLong(3, runId);
            ps.executeUpdate();
        }
    }

    private static void setRunBool(long runId, String column, boolean value) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE experiment_run SET " + column + " = ? WHERE tenant_id = ? AND id = ?")) {
            ps.setBoolean(1, value);
            ps.setLong(2, TENANT);
            ps.setLong(3, runId);
            ps.executeUpdate();
        }
    }

    private static String statusOf(long runId) throws SQLException {
        return strOf(runId, "status");
    }

    private static String strOf(long runId, String column) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT " + column + " FROM experiment_run WHERE tenant_id = ? AND id = ?")) {
            ps.setLong(1, TENANT);
            ps.setLong(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static boolean boolOf(long runId, String column) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT " + column + " FROM experiment_run WHERE tenant_id = ? AND id = ?")) {
            ps.setLong(1, TENANT);
            ps.setLong(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private static String runStatusOf(long wfRunId) throws SQLException {
        return strOfRun(wfRunId, "status");
    }

    private static String strOfRun(long wfRunId, String column) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT " + column + " FROM run WHERE tenant_id = ? AND id = ?")) {
            ps.setLong(1, TENANT);
            ps.setLong(2, wfRunId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static String strOfExperiment(long experimentId, String column) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT " + column + " FROM experiment WHERE tenant_id = ? AND id = ?")) {
            ps.setLong(1, TENANT);
            ps.setLong(2, experimentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** 删掉自测造的行（物理删除：自测数据不该留在现场闸门表里）。 */
    private static void cleanup() throws SQLException {
        try (Connection c = db.open()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM runtime_hold WHERE tenant_id = ? AND scope_id IN (?, ?, ?, ?)")) {
                ps.setLong(1, TENANT);
                ps.setLong(2, ISL);
                ps.setLong(3, EXP_A);
                ps.setLong(4, EXP_C);
                ps.setLong(5, EXP_F);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM experiment_run_step WHERE tenant_id = ? AND experiment_run_id IN "
                            + "(?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setLong(1, TENANT);
                ps.setLong(2, RUN_A);
                ps.setLong(3, RUN_B);
                ps.setLong(4, RUN_C);
                ps.setLong(5, RUN_D);
                ps.setLong(6, RUN_ER);
                ps.setLong(7, RUN_EQ);
                ps.setLong(8, RUN_F);
                ps.setLong(9, RUN_G);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM experiment_run WHERE tenant_id = ? AND id IN "
                            + "(?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setLong(1, TENANT);
                ps.setLong(2, RUN_A);
                ps.setLong(3, RUN_B);
                ps.setLong(4, RUN_C);
                ps.setLong(5, RUN_D);
                ps.setLong(6, RUN_ER);
                ps.setLong(7, RUN_EQ);
                ps.setLong(8, RUN_F);
                ps.setLong(9, RUN_G);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM run WHERE tenant_id = ? AND id IN (?, ?)")) {
                ps.setLong(1, TENANT);
                ps.setLong(2, RUN_WF_DONE);
                ps.setLong(3, RUN_WF_PAUSED);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM experiment WHERE tenant_id = ? AND id IN (?, ?, ?, ?, ?, ?, ?)")) {
                ps.setLong(1, TENANT);
                ps.setLong(2, EXP_A);
                ps.setLong(3, EXP_B);
                ps.setLong(4, EXP_C);
                ps.setLong(5, EXP_D);
                ps.setLong(6, EXP_E);
                ps.setLong(7, EXP_F);
                ps.setLong(8, EXP_G);
                ps.executeUpdate();
            }
        }
    }

    private static void check(String name, boolean ok, Object actual) {
        if (ok) {
            pass++;
            System.out.printf("  ✓ %-32s %s%n", name, actual);
        } else {
            fail++;
            System.out.printf("  ✗ %-32s 实际 %s%n", name, actual);
        }
    }

    private RunControlDbSelfTest() {
    }
}
