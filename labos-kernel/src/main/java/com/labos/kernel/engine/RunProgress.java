package com.labos.kernel.engine;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次执行的进度快照容器。
 *
 * <p>持有全部节点的可变进度，可序列化落库。状态机本身不存状态，
 * 所以「暂停 → 存盘 → 重启续跑」只需把这个对象还原回去。
 */
public final class RunProgress {

    private final Map<Long, NodeProgress> nodes = new LinkedHashMap<>();
    private boolean aborted;
    /** §3.1：完成处理器返回 HOLD（物理完成、物料未闭环）⇒ run 进 paused(material_sync)。 */
    private boolean materialHold;

    public NodeProgress node(long nodeId) {
        return nodes.computeIfAbsent(nodeId, NodeProgress::new);
    }

    public Collection<NodeProgress> all() {
        return nodes.values();
    }

    public boolean aborted() {
        return aborted;
    }

    void markAborted() {
        this.aborted = true;
    }

    public boolean materialHold() {
        return materialHold;
    }

    void markMaterialHold() {
        this.materialHold = true;
    }

    /**
     * 内置节点（变量/表达式）不走状态机，直接记完成。
     *
     * <p>为什么要有这个入口：正常节点由 {@code RunStateMachine} 推进并把状态写进 NodeProgress，
     * 而变量/表达式节点没有设备、没有重试语义，走状态机反而绕 —— 这里直接落一条完成记录，
     * 让它在 run_node 里可见（否则图上永远显示"未执行"）。
     */
    public NodeProgress complete(long nodeId, String reason) {
        NodeProgress p = node(nodeId);
        p.setStatus(NodeStatus.COMPLETED);
        p.setLastReason(reason);
        return p;
    }

    /** 内置节点算不出来（表达式写错）：记成转人工，让整单停下而不是带着错值继续。 */
    public NodeProgress manual(long nodeId, String reason, String errorCode) {
        NodeProgress p = node(nodeId);
        p.setStatus(NodeStatus.MANUAL);
        p.setLastReason(reason);
        p.setLastErrorCode(errorCode);
        return p;
    }

    public int count(NodeStatus status) {
        return (int) nodes.values().stream().filter(n -> n.status() == status).count();
    }

    public boolean allSettled() {
        return nodes.values().stream().noneMatch(n ->
                n.status() == NodeStatus.PENDING || n.status() == NodeStatus.RUNNING);
    }

    /** 一行摘要，便于日志与演示输出。 */
    public String summary() {
        return String.format(
                "完成 %d / 跳过 %d / 人工 %d / 终止 %d%s",
                count(NodeStatus.COMPLETED), count(NodeStatus.SKIPPED),
                count(NodeStatus.MANUAL), count(NodeStatus.ABORTED),
                aborted ? "（已中止）" : "");
    }
}
