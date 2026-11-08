package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

/**
 * 暂停统一适配层 幂等键 落库自测（需要真实库）—— V1-5 / ADR-PAUSE-009。
 *
 * <p>{@code PauseCoordinator} 在请求带 {@code X-Request-Id} 时按 {@code pause_request}
 * 表去重。这里压三条此前<b>零测试</b>的路径：
 * <ol>
 *   <li><b>同 requestId 重复暂停</b>：第二次必须回放首次结果，<b>不再抬第二道扣留</b>。
 *       走真代码路径断言 {@code pause_request} 只有 1 行、{@code runtime_hold} 生效数不增
 *       —— 只断言"返回相同"是不够的，重复抬 Hold 才是真缺陷。</li>
 *   <li><b>无 requestId 不去重</b>：旧客户端（不带该头）必须与改动前逐字段一致：
 *       {@code pause_request} 一行都不写。</li>
 *   <li><b>失败可重跑</b>：目标 run 不存在时首次执行抛错，{@code abandon} 撤回登记，
 *       带同一 requestId 重试能真正重跑，而不是被回放成一个「从未执行过」的请求。</li>
 * </ol>
 *
 * <p>用合成负数 id（与真实自增 id 不可能撞）并在 finally 里清干净。
 * <b>不用 site 范围</b> —— 全场闸门会把正在跑的实验一起停掉，自测不能这么干。
 */
public final class PauseCoordinatorDbSelfTest {

    private static int pass;
    private static int fail;
    private static DbConfig db;
    private static final long TENANT = 1L;

    private static final long EXP_P = -993010L;    // 幂等暂停用
    private static final long RUN_P = -993101L;    // EXP_P，running
    private static final long RUN_NEW = -993102L;  // EXP_P，重跑用
    private static final long MISSING_RUN = -993999L;  // 库里没有这条 run

    private static final String REQ_PAUSE = "SELF-PC-PAUSE-1";
    private static final String REQ_RETRY = "SELF-PC-RETRY-1";
    private static final String REQ_RESUME = "SELF-PC-RESUME-1";
    private static final String REQ_STOP = "SELF-PC-STOP-1";
    private static final String REQ_STOP_RUNNING = "SELF-PC-STOP-RUNNING-1";
    private static final String REQ_WF = "SELF-PC-WF-1";
    private static final long WF_RUN = -992401L;
    private static final String REQ_DISMISS = "SELF-PC-DISMISS-1";
    private static final String REQ_WF_RESUME = "SELF-PC-WF-RESUME-1";

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        cleanup();
        try {
            PauseCoordinator coord = new PauseCoordinator(db, TENANT, null, null);

            idempotentPauseReplays(coord);
            noRequestIdSkipsDedup(coord);
            failedAttemptCanBeRetried(coord);
            resumeTransactionWritesEvent(coord);
            stopTransactionWritesEvent(coord);
            workflowPauseTransaction(coord);
            dismissTransactions(coord);
            workflowResumeTransaction(coord);
        } finally {
            cleanup();
        }

        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 1. 同 requestId 幂等

    private static void idempotentPauseReplays(PauseCoordinator coord) throws Exception {
        seed();

        PauseCoordinator.ExperimentPause first = coord.pauseExperimentRun(
                PauseCommand.experiment(RUN_P, PauseCommand.Action.PAUSE, "manual",
                        "自测：首次暂停", REQ_PAUSE), "experiment");
        check("首次暂停落在实验级", RuntimeHold.Scope.EXPERIMENT.code().equals(first.scopeCode()),
                first.scopeCode());
        check("首次暂停只回「已受理」", "accepted".equals(first.status()), first.status());
        check("首次暂停 controlState=PAUSE_REQUESTED",
                "PAUSE_REQUESTED".equals(first.controlState()), first.controlState());
        check("受理时 DB status 仍是 running（不假写 paused）",
                "running".equals(statusOf(RUN_P)), statusOf(RUN_P));
        check("受理时 DB control_state=PAUSE_REQUESTED",
                "PAUSE_REQUESTED".equals(controlStateOf(RUN_P)), controlStateOf(RUN_P));
        check("首次暂停登记了 1 条幂等键", pauseReqCount(REQ_PAUSE) == 1, pauseReqCount(REQ_PAUSE));
        check("首次暂停抬出 1 道扣留", holdCount(EXP_P) == 1, holdCount(EXP_P));
        check("单事务同时写了 pause_event（Outbox）",
                eventCount(REQ_PAUSE) == 1, eventCount(REQ_PAUSE));
        check("Outbox 中本请求事件未派发",
                pendingEventCount(REQ_PAUSE) == 1, pendingEventCount(REQ_PAUSE));
        int dispatched = PauseEventOutbox.dispatchPending(db, TENANT, 20);
        check("Outbox 派发成功（" + dispatched + " 条）", dispatched >= 1, dispatched);
        check("派发后本请求事件已标记 dispatched_at",
                pendingEventCount(REQ_PAUSE) == 0, pendingEventCount(REQ_PAUSE));
        int againDispatch = PauseEventOutbox.dispatchPending(db, TENANT, 20);
        check("重复派发不会重复处理已标记事件（本次 " + againDispatch + " 条）",
                pendingEventCount(REQ_PAUSE) == 0, pendingEventCount(REQ_PAUSE));

        PauseCoordinator.ExperimentPause again = coord.pauseExperimentRun(
                PauseCommand.experiment(RUN_P, PauseCommand.Action.PAUSE, "manual",
                        "自测：重复暂停", REQ_PAUSE), "experiment");
        check("重复暂停回放相同作用域", first.scopeCode().equals(again.scopeCode()), again.scopeCode());
        check("重复暂停回放相同状态", first.status().equals(again.status()), again.status());
        check("重复暂停回放相同 controlState",
                first.controlState().equals(again.controlState()), again.controlState());
        check("重复暂停回放相同扣留类型", first.kindCode().equals(again.kindCode()), again.kindCode());
        check("重复暂停不再新增幂等键", pauseReqCount(REQ_PAUSE) == 1, pauseReqCount(REQ_PAUSE));
        check("重复暂停不再抬第二道扣留（关键）", holdCount(EXP_P) == 1, holdCount(EXP_P));

        new RuntimeHold(db, TENANT).clear(RuntimeHold.Scope.EXPERIMENT, EXP_P, null, "自测：清扣留");
    }

    // ---------------------------------------------------------------- 2. 无 requestId 不去重

    private static void noRequestIdSkipsDedup(PauseCoordinator coord) throws Exception {
        setRunStatus(RUN_P, "running");

        coord.pauseExperimentRun(PauseCommand.experiment(RUN_P, PauseCommand.Action.PAUSE,
                "manual", "自测：旧客户端无幂等键", null), "experiment");

        check("无 requestId 时不写 pause_request", pauseReqCount(REQ_PAUSE) == 1, pauseReqCount(REQ_PAUSE));
        check("无 requestId 仍正常抬出扣留", holdCount(EXP_P) == 1, holdCount(EXP_P));

        new RuntimeHold(db, TENANT).clear(RuntimeHold.Scope.EXPERIMENT, EXP_P, null, "自测：清扣留");
    }

    // ---------------------------------------------------------------- 3. 失败可重跑

    private static void failedAttemptCanBeRetried(PauseCoordinator coord) throws Exception {
        boolean threw = false;
        try {
            // 目标 run 不存在：info() 抛 IllegalArgumentException，Coordinator 应 abandon 后再抛。
            coord.pauseExperimentRun(PauseCommand.experiment(MISSING_RUN, PauseCommand.Action.PAUSE,
                    "manual", "自测：目标不存在", REQ_RETRY), "experiment");
        } catch (RuntimeException e) {
            threw = true;
        }
        check("目标不存在时抛错", threw, threw);
        check("失败后幂等键被撤回（可重跑）", pauseReqCount(REQ_RETRY) == 0, pauseReqCount(REQ_RETRY));
        check("失败时 pause_event 也一起回滚（无半成品事件）",
                eventCount(REQ_RETRY) == 0, eventCount(REQ_RETRY));

        // 补一条真实 run，带同一 requestId 重试 —— 必须真正执行，而不是被回放。
        try (Connection c = db.open()) {
            insertRun(c, RUN_NEW, EXP_P, "running");
        }
        PauseCoordinator.ExperimentPause retry = coord.pauseExperimentRun(
                PauseCommand.experiment(RUN_NEW, PauseCommand.Action.PAUSE, "manual",
                        "自测：重跑", REQ_RETRY), "experiment");
        check("同 requestId 重试真正重跑", "accepted".equals(retry.status()), retry.status());
        check("重试也回 controlState=PAUSE_REQUESTED",
                "PAUSE_REQUESTED".equals(retry.controlState()), retry.controlState());
        check("重跑后幂等键登记为 1 条", pauseReqCount(REQ_RETRY) == 1, pauseReqCount(REQ_RETRY));
        check("重跑抬出扣留", holdCount(EXP_P) == 1, holdCount(EXP_P));

        new RuntimeHold(db, TENANT).clear(RuntimeHold.Scope.EXPERIMENT, EXP_P, null, "自测：清扣留");
    }

    // ------------------------------------------------- 4. 恢复单事务（P-06 V2 第二切片）

    private static void resumeTransactionWritesEvent(PauseCoordinator coord) throws Exception {
        setRunStatus(RUN_P, "paused");
        new RuntimeHold(db, TENANT).raise(RuntimeHold.Scope.EXPERIMENT, EXP_P,
                RuntimeHold.Kind.MANUAL, "自测：恢复前扣留", null);

        PauseCoordinator.ExperimentResume r = coord.resumeExperimentRun(
                PauseCommand.experiment(RUN_P, PauseCommand.Action.RESUME, "manual",
                        "自测：恢复", REQ_RESUME), "自测恢复");
        check("恢复成功解除可解扣留", r.clearedHold(), r.clearedHold());
        check("恢复后重新入队", r.requeued(), r.requeued());
        check("恢复后 run 回 queued", "queued".equals(statusOf(RUN_P)), statusOf(RUN_P));
        check("恢复后 control_state=NONE",
                "NONE".equals(controlStateOf(RUN_P)), controlStateOf(RUN_P));
        check("恢复后实验级扣留已释放", holdCount(EXP_P) == 0, holdCount(EXP_P));
        check("恢复单事务写入 RESUME_REQUESTED 事件",
                eventCount(REQ_RESUME) == 1, eventCount(REQ_RESUME));
        check("恢复事件类型 RESUME_REQUESTED",
                "RESUME_REQUESTED".equals(eventTypeOf(REQ_RESUME)), eventTypeOf(REQ_RESUME));

        PauseCoordinator.ExperimentResume replay = coord.resumeExperimentRun(
                PauseCommand.experiment(RUN_P, PauseCommand.Action.RESUME, "manual",
                        "自测：恢复重放", REQ_RESUME), "自测恢复");
        check("重复恢复回放相同状态", "queued".equals(replay.status()), replay.status());
        check("重复恢复不再新增事件", eventCount(REQ_RESUME) == 1, eventCount(REQ_RESUME));
    }

    // ------------------------------------------------- 5. 停止单事务（P-06 V2 第三切片）

    private static void stopTransactionWritesEvent(PauseCoordinator coord) throws Exception {
        // 非 running：直接落 aborted + 实验表同步 + STOP_REQUESTED 事件同事务
        setRunStatus(RUN_P, "paused");
        PauseCoordinator.ExperimentStop stopped = coord.stopExperimentRun(
                PauseCommand.experiment(RUN_P, PauseCommand.Action.STOP, "manual",
                        "自测：停止", REQ_STOP));
        check("非运行态停止 changed=true", stopped.changed(), stopped.changed());
        check("停止后 run=aborted", "aborted".equals(statusOf(RUN_P)), statusOf(RUN_P));
        check("停止单事务写入 STOP_REQUESTED 事件",
                eventCount(REQ_STOP) == 1, eventCount(REQ_STOP));
        check("停止事件类型 STOP_REQUESTED",
                "STOP_REQUESTED".equals(eventTypeOf(REQ_STOP)), eventTypeOf(REQ_STOP));
        PauseCoordinator.ExperimentStop replay = coord.stopExperimentRun(
                PauseCommand.experiment(RUN_P, PauseCommand.Action.STOP, "manual",
                        "自测：停止重放", REQ_STOP));
        check("重复停止回放相同状态", "aborted".equals(replay.status()), replay.status());
        check("重复停止不新增事件", eventCount(REQ_STOP) == 1, eventCount(REQ_STOP));

        // running：只落 stop_requested=true（在途动作由执行器边界收尾）
        PauseCoordinator.ExperimentStop running = coord.stopExperimentRun(
                PauseCommand.experiment(RUN_NEW, PauseCommand.Action.STOP, "manual",
                        "自测：运行中停止", REQ_STOP_RUNNING));
        check("运行中停止 changed=true", running.changed(), running.changed());
        check("运行中停止返回 stopping", "stopping".equals(running.status()), running.status());
        check("运行中停止落 stop_requested=true",
                stopRequestedOf(RUN_NEW), stopRequestedOf(RUN_NEW));
        check("运行中停止写入 STOP_REQUESTED 事件",
                eventCount(REQ_STOP_RUNNING) == 1, eventCount(REQ_STOP_RUNNING));
    }

    // ------------------------------------------------- 6. Workflow 级暂停单事务（E-1）

    private static void workflowPauseTransaction(PauseCoordinator coord) throws Exception {
        exec("DELETE FROM run WHERE tenant_id=1 AND run_no='SELF-PC-WF'");
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO run (tenant_id, run_no, run_type, workflow_id,
                                      workflow_ver, mode, status, actual_start, progress)
                     VALUES (?, 'SELF-PC-WF', 'workflow', 239, 1, 'normal',
                             'running', now(), 0)
                     """)) {
            ps.setLong(1, TENANT);
            ps.executeUpdate();
        }
        long wfRun = Long.parseLong(one("SELECT id FROM run WHERE tenant_id=1 "
                + "AND run_no='SELF-PC-WF'"));
        PauseCoordinator.WorkflowPause r = coord.pauseWorkflowRun(
                PauseCommand.workflow(wfRun, PauseCommand.Action.PAUSE, "waiting_human",
                        "自测：流程级暂停", REQ_WF), "node-x");
        check("流程级暂停受理", "waiting_human".equals(r.pauseKindCode()), r.pauseKindCode());
        check("流程级 run 落 paused",
                "paused".equals(one("SELECT status::text FROM run WHERE id=" + wfRun)),
                one("SELECT status::text FROM run WHERE id=" + wfRun));
        check("流程级暂停单事务写入 pause_event",
                eventCount(REQ_WF) == 1, eventCount(REQ_WF));
        check("流程级事件类型 PAUSE_REQUESTED",
                "PAUSE_REQUESTED".equals(eventTypeOf(REQ_WF)), eventTypeOf(REQ_WF));
        PauseCoordinator.WorkflowPause replay = coord.pauseWorkflowRun(
                PauseCommand.workflow(wfRun, PauseCommand.Action.PAUSE, "waiting_human",
                        "自测：重复", REQ_WF), "node-x");
        check("重复流程暂停回放相同类型", "waiting_human".equals(replay.pauseKindCode()),
                replay.pauseKindCode());
        check("重复流程暂停不新增事件", eventCount(REQ_WF) == 1, eventCount(REQ_WF));
        exec("DELETE FROM run WHERE tenant_id=1 AND id=" + wfRun);
    }

    // ------------------------------------------------- 7. E-3 忽略单条/批量单事务

    private static void dismissTransactions(PauseCoordinator coord) throws Exception {
        exec("DELETE FROM run WHERE tenant_id=1 AND run_no LIKE 'SELF-PC-CRASH%'");
        exec("DELETE FROM run WHERE tenant_id=1 AND run_no LIKE 'SELF-PC-DISM%'");
        exec("""
                INSERT INTO run (tenant_id, run_no, run_type, workflow_id, workflow_ver,
                                 mode, status, actual_start, progress, input)
                VALUES (1, 'SELF-PC-CRASH', 'workflow', 239, 1, 'normal',
                        'failed', now(), 0, '{"recoverable":true}'::jsonb)
                """);
        long crashRun = Long.parseLong(one(
                "SELECT id FROM run WHERE tenant_id=1 AND run_no='SELF-PC-CRASH'"));
        boolean done = coord.dismissWorkflowRun(PauseCommand.workflow(crashRun,
                PauseCommand.Action.DISMISS, "waiting_human",
                "自测：忽略崩溃可恢复", REQ_DISMISS));
        check("E-3b 忽略崩溃可恢复 run 成功", done, done);
        check("E-3b 单事务写入 DISMISS_REQUESTED 事件",
                eventCount(REQ_DISMISS) == 1, eventCount(REQ_DISMISS));
        check("E-3b 事件类型 DISMISS_REQUESTED",
                "DISMISS_REQUESTED".equals(eventTypeOf(REQ_DISMISS)),
                eventTypeOf(REQ_DISMISS));
        boolean replay = coord.dismissWorkflowRun(PauseCommand.workflow(crashRun,
                PauseCommand.Action.DISMISS, "waiting_human",
                "自测：重复忽略", REQ_DISMISS));
        check("E-3b 重复忽略回放首次结果", replay, replay);
        check("E-3b 重复忽略不新增事件",
                eventCount(REQ_DISMISS) == 1, eventCount(REQ_DISMISS));

        exec("""
                INSERT INTO run (tenant_id, run_no, run_type, workflow_id, workflow_ver,
                                 mode, status, actual_start, progress)
                SELECT 1, 'SELF-PC-DISM-A', 'workflow', 239, 1, 'normal',
                       'paused', now(), 0
                """);
        exec("""
                INSERT INTO run (tenant_id, run_no, run_type, workflow_id, workflow_ver,
                                 mode, status, actual_start, progress)
                SELECT 1, 'SELF-PC-DISM-B', 'workflow', 239, 1, 'normal',
                       'paused', now(), 0
                """);
        long a = Long.parseLong(one(
                "SELECT id FROM run WHERE tenant_id=1 AND run_no='SELF-PC-DISM-A'"));
        long b = Long.parseLong(one(
                "SELECT id FROM run WHERE tenant_id=1 AND run_no='SELF-PC-DISM-B'"));
        int n = coord.dismissBatchWorkflowRuns(List.of(a, b), null, null);
        check("E-3a 批量忽略 2 条同事务", n == 2, n);
        check("E-3a A 落 aborted",
                "aborted".equals(one("SELECT status::text FROM run WHERE id=" + a)),
                one("SELECT status::text FROM run WHERE id=" + a));
        check("E-3a B 落 aborted",
                "aborted".equals(one("SELECT status::text FROM run WHERE id=" + b)),
                one("SELECT status::text FROM run WHERE id=" + b));
        exec("DELETE FROM run WHERE tenant_id=1 AND run_no LIKE 'SELF-PC-CRASH%'");
        exec("DELETE FROM run WHERE tenant_id=1 AND run_no LIKE 'SELF-PC-DISM%'");
    }

    // ------------------------------------------------- 8. Workflow 级恢复单事务（E-2）

    private static void workflowResumeTransaction(PauseCoordinator coord) throws Exception {
        exec("DELETE FROM run WHERE tenant_id=1 AND run_no='SELF-PC-WF-RESUME'");
        exec("""
                INSERT INTO run (tenant_id, run_no, run_type, workflow_id, workflow_ver,
                                 mode, status, pause_kind, pause_reason,
                                 actual_start, progress)
                VALUES (1, 'SELF-PC-WF-RESUME', 'workflow', 239, 1, 'normal',
                        'paused', 'waiting_human', '自测：待恢复', now(), 0)
                """);
        long runId = Long.parseLong(one(
                "SELECT id FROM run WHERE tenant_id=1 AND run_no='SELF-PC-WF-RESUME'"));
        PauseManager.ResumeResult r = coord.resumeWorkflowRun(PauseCommand.workflow(runId,
                PauseCommand.Action.RESUME, "continue", "自测：恢复", REQ_WF_RESUME),
                java.util.Map.of());
        check("E-2 恢复成功", r.ok(), r.error());
        check("E-2 run 回 queued",
                "queued".equals(one("SELECT status::text FROM run WHERE id=" + runId)),
                one("SELECT status::text FROM run WHERE id=" + runId));
        check("E-2 单事务写入 RESUME_REQUESTED 事件",
                eventCount(REQ_WF_RESUME) == 1, eventCount(REQ_WF_RESUME));
        check("E-2 事件类型 RESUME_REQUESTED",
                "RESUME_REQUESTED".equals(eventTypeOf(REQ_WF_RESUME)),
                eventTypeOf(REQ_WF_RESUME));
        PauseManager.ResumeResult replay = coord.resumeWorkflowRun(PauseCommand.workflow(runId,
                PauseCommand.Action.RESUME, "continue", "自测：重复", REQ_WF_RESUME),
                java.util.Map.of());
        check("E-2 重复恢复回放首次结果", replay.ok(), replay.error());
        check("E-2 重复恢复不新增事件",
                eventCount(REQ_WF_RESUME) == 1, eventCount(REQ_WF_RESUME));
        exec("DELETE FROM run WHERE tenant_id=1 AND run_no='SELF-PC-WF-RESUME'");
    }

    // ---------------------------------------------------------------- 造数 / 查询 / 清理

    private static void seed() throws SQLException {
        try (Connection c = db.open()) {
            insertExperiment(c);
            insertRun(c, RUN_P, EXP_P, "running");
        }
    }

    private static void insertExperiment(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO experiment (id, tenant_id, experiment_no, name, status, island_id)
                VALUES (?, ?, ?, ?, 'created', ?)
                """)) {
            ps.setLong(1, EXP_P);
            ps.setLong(2, TENANT);
            ps.setString(3, "SELF-PC-P");
            ps.setString(4, "SELF-PC-P");
            ps.setNull(5, Types.BIGINT);
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

    private static void setRunStatus(long runId, String status) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE experiment_run SET status = ?, control_state = 'NONE' "
                             + "WHERE tenant_id = ? AND id = ?")) {
            ps.setString(1, status);
            ps.setLong(2, TENANT);
            ps.setLong(3, runId);
            ps.executeUpdate();
        }
    }

    private static String one(String sql) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static void exec(String sql) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    private static String statusOf(long runId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT status FROM experiment_run WHERE tenant_id = ? AND id = ?")) {
            ps.setLong(1, TENANT);
            ps.setLong(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static boolean stopRequestedOf(long runId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT stop_requested FROM experiment_run "
                             + "WHERE tenant_id = ? AND id = ?")) {
            ps.setLong(1, TENANT);
            ps.setLong(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private static String controlStateOf(long runId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT control_state FROM experiment_run WHERE tenant_id = ? AND id = ?")) {
            ps.setLong(1, TENANT);
            ps.setLong(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** 该 requestId 在 pause_event 里的行数（单事务 Outbox 证据）。 */
    private static int eventCount(String requestId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM pause_event WHERE tenant_id = ? AND request_id = ?")) {
            ps.setLong(1, TENANT);
            ps.setString(2, requestId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** 该 requestId 最新事件类型（恢复事件证据）。 */
    private static String eventTypeOf(String requestId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT event_type FROM pause_event "
                             + "WHERE tenant_id = ? AND request_id = ? ORDER BY id DESC LIMIT 1")) {
            ps.setLong(1, TENANT);
            ps.setString(2, requestId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** 该 requestId 还没派发的事件数。 */
    private static int pendingEventCount(String requestId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM pause_event "
                             + "WHERE tenant_id = ? AND request_id = ? AND dispatched_at IS NULL")) {
            ps.setLong(1, TENANT);
            ps.setString(2, requestId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** 该 requestId 在 pause_request 里的行数（幂等键唯一性证据）。 */
    private static int pauseReqCount(String requestId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM pause_request WHERE tenant_id = ? AND request_id = ?")) {
            ps.setLong(1, TENANT);
            ps.setString(2, requestId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** 该实验生效中的实验级扣留条数（重复抬 Hold 的反面证据）。 */
    private static int holdCount(long experimentId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT count(*) FROM runtime_hold
                      WHERE tenant_id = ? AND released_at IS NULL
                        AND scope = 'experiment' AND scope_id = ?
                     """)) {
            ps.setLong(1, TENANT);
            ps.setLong(2, experimentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** 删掉自测造的行（物理删除：自测数据不该留在现场闸门表 / 幂等表里）。 */
    private static void cleanup() throws SQLException {
        try (Connection c = db.open()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM pause_event WHERE tenant_id = ? AND request_id LIKE 'SELF-PC-%'")) {
                ps.setLong(1, TENANT);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM pause_request WHERE tenant_id = ? AND request_id LIKE 'SELF-PC-%'")) {
                ps.setLong(1, TENANT);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM runtime_hold WHERE tenant_id = ? AND scope = 'experiment' AND scope_id = ?")) {
                ps.setLong(1, TENANT);
                ps.setLong(2, EXP_P);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM experiment_run WHERE tenant_id = ? AND id IN (?, ?)")) {
                ps.setLong(1, TENANT);
                ps.setLong(2, RUN_P);
                ps.setLong(3, RUN_NEW);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM experiment WHERE tenant_id = ? AND id = ?")) {
                ps.setLong(1, TENANT);
                ps.setLong(2, EXP_P);
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

    private PauseCoordinatorDbSelfTest() {
    }
}
