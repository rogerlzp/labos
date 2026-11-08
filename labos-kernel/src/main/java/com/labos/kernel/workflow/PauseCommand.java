package com.labos.kernel.workflow;

import java.time.Instant;

/**
 * 暂停 / 恢复 / 停止 的<b>统一请求对象</b>（S-01 §7.1、PauseCoordinator V1 设计 §4）。
 *
 * <p>业务入口（{@code RunApi} / {@code ExperimentApi}）把 HTTP 参数收敛成它，再交给
 * {@link PauseCoordinator} 按目标模型路由。它<b>只描述意图</b>：不携带设备协议细节、
 * 不代表物理动作已经停止 —— 「请求已被受理」与「现场已安全停住」是两件事，
 * 后者由 {@code runtime_hold} 与执行器在动作边界确认，不能靠这个对象表达。
 *
 * <h3>字段口径</h3>
 * <ul>
 *   <li>{@code action}：控制动作（PAUSE / RESUME / STOP / DISMISS），决定路由到哪个既有控制器。</li>
 *   <li>{@code kind}：<b>动作子类型码</b>，随 {@code action} 解释：
 *       <ul>
 *         <li>PAUSE → 暂停类型（{@code paused_fault} / {@code waiting_human} / {@code paused_unknown}）</li>
 *         <li>RESUME → 恢复动作（{@code continue} / {@code retry} / {@code skip} / {@code abort}）</li>
 *         <li>STOP / DISMISS → 忽略</li>
 *       </ul>
 *       </li>
 *   <li>{@code requestId}：幂等键，来自 {@code X-Request-Id} 头；V1 未接幂等，取不到时为 {@code null}。</li>
 *   <li>{@code requestedBy}：操作者。V1 未接会话，恒为 {@code null}；操作者仍由入口的审计事件记录。</li>
 *   <li>{@code resultState}：<b>出参</b>，由 {@link PauseCoordinator} 在返回前标记本次请求的处置结果。</li>
 * </ul>
 *
 * <p>动作特有的附加参数（workflow 的 {@code nodeKey}、experiment 的 {@code scope} / {@code note}、
 * 恢复的板位校正 {@code corrections}、批量的 {@code ids} / {@code beforeHours}）不进本对象，
 * 作为对应方法的显式入参传入 —— 它们是「路由之后的细节」，不是统一请求的公共最低字段。
 */
public final class PauseCommand {

    /** 目标运行模型：Workflow 级 {@code run} 还是 Experiment 级 {@code experiment_run}。 */
    public enum TargetType { WORKFLOW_RUN, EXPERIMENT_RUN }

    /** 控制动作。 */
    public enum Action { PAUSE, RESUME, STOP, DISMISS }

    /**
     * 请求处置结果（出口）。
     *
     * <p>{@code ACCEPTED} 已受理 / {@code COMPLETED} 已完成 / {@code REJECTED} 被拒 /
     * {@code UNKNOWN} 无法确认（如现场扣留仍在，恢复未真正生效）。
     */
    public enum ResultState { ACCEPTED, COMPLETED, REJECTED, UNKNOWN }

    private final String requestId;
    private final TargetType targetType;
    private final long targetId;
    private final Action action;
    private final String source;
    private final String kind;
    private final String reasonText;
    private final String requestedBy;
    private final String requestedAt;
    private ResultState resultState = ResultState.ACCEPTED;

    public PauseCommand(String requestId, TargetType targetType, long targetId,
                        Action action, String source, String kind,
                        String reasonText, String requestedBy) {
        this.requestId = requestId;
        this.targetType = targetType;
        this.targetId = targetId;
        this.action = action;
        this.source = source;
        this.kind = kind;
        this.reasonText = reasonText;
        this.requestedBy = requestedBy;
        this.requestedAt = Instant.now().toString();
    }

    /** 构造一个针对 Workflow 级 {@code run} 的请求。 */
    public static PauseCommand workflow(long runId, Action action, String kind,
                                        String reason, String requestId) {
        return new PauseCommand(requestId, TargetType.WORKFLOW_RUN, runId,
                action, "api", kind, reason, null);
    }

    /** 构造一个针对 Experiment 级 {@code experiment_run} 的请求。 */
    public static PauseCommand experiment(long runId, Action action, String kind,
                                          String reason, String requestId) {
        return new PauseCommand(requestId, TargetType.EXPERIMENT_RUN, runId,
                action, "api", kind, reason, null);
    }

    public String requestId() {
        return requestId;
    }

    public TargetType targetType() {
        return targetType;
    }

    public long targetId() {
        return targetId;
    }

    public Action action() {
        return action;
    }

    public String source() {
        return source;
    }

    public String kind() {
        return kind;
    }

    public String reasonText() {
        return reasonText;
    }

    public String requestedBy() {
        return requestedBy;
    }

    public String requestedAt() {
        return requestedAt;
    }

    public ResultState resultState() {
        return resultState;
    }

    /** 出参：标记本次请求的处置结果，返回自身便于链式使用。 */
    public PauseCommand result(ResultState st) {
        this.resultState = st;
        return this;
    }
}
