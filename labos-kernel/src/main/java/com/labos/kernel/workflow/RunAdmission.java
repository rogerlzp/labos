package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.Tx;

import java.sql.SQLException;

/**
 * 统一准入（2026-11-19 新增）。
 *
 * <p><b>解决什么</b>：现在"能不能启动"这个判断散在各入口，且与建 run 不在同一事务：
 *
 * <pre>
 *   旧：gate.check（独立连接） → insertRun（另一个事务） → 执行
 *        └─ 两步之间的窗口：读到"无活跃"，写入前别人插了一条
 * </pre>
 *
 * <p><b>怎么做</b>：把「门禁检查」与「建 run」收进同一个事务、同一个连接，
 * 由数据库唯一索引（{@code uk_run_active_workflow}，见 K-01a）做最后兜底：
 *
 * <pre>
 *   新：BEGIN
 *         gate.checkInTx(c)          ← 同一连接
 *         insertRun                  ← 同一事务
 *       COMMIT
 *       然后才 execute(presetRunId)
 * </pre>
 *
 * <p><b>为什么执行不能放进事务</b>：{@code WorkflowService.execute} 可能跑几十分钟并持有设备锁，
 * 放进事务会把锁和 MVCC 快照一起拖长。所以只把"准入 + 插入"做成原子，执行在事务外。
 *
 * <p><b>边界</b>：本类不做任何业务校验（workflow 是否存在、参数是否合法），
 * 那些是 {@code WorkflowService} 的职责。它只回答一个问题：
 * <b>这个流程现在允不允许再起一个实例</b>。
 */
public final class RunAdmission {

    private RunAdmission() {
    }

    /**
     * 准入被拒。
     *
     * <p>用 {@link RuntimeException} 而不是受检异常：它会在 {@link Tx#inTransaction} 的
     * lambda 里抛出，由事务模板触发回滚（受检异常不会让 Spring 事务回滚）。
     */
    public static final class Rejected extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final WorkflowRunGate.Decision decision;

        Rejected(WorkflowRunGate.Decision decision) {
            super(decision.reason());
            this.decision = decision;
        }

        public WorkflowRunGate.Decision decision() {
            return decision;
        }
    }

    /**
     * 准入 + 建 run（同一事务）。
     *
     * <p>返回的 runId <b>尚未开始执行</b>，调用方需要把它传给
     * {@code WorkflowService.execute(workflowId, attendance, runId)}。
     *
     * @throws Rejected   门禁拒绝（此时事务已回滚，没有任何 run 被创建）
     * @throws SQLException 数据库错误
     */
    public static long start(DbConfig db, long tenantId, long workflowId,
                             String attendance, WorkflowService service) throws SQLException {
        // 图定义在事务外加载：它只读、可能较大，没必要占着事务。
        RealWorkflowRunner.WorkflowDef wf = RealWorkflowRunner.loadWorkflow(db, workflowId);
        return Tx.inTransaction(db, c -> {
            WorkflowRunGate.Decision gate =
                    WorkflowRunGate.checkInTx(c, tenantId, workflowId, null);
            if (!gate.allowed()) {
                throw new Rejected(gate);
            }
            return service.createRunForAdmission(wf, attendance);
        });
    }
}
