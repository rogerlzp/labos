package com.labos.kernel.engine;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 单个节点的执行进度——状态机的全部可变状态都收在这里。
 *
 * <p>状态机本身无实例状态（只有 seq 计数器），进度对象外部持有、可序列化，
 * 因此「暂停 → 落库 → 重启后续跑」是天然支持的。
 */
public final class NodeProgress {

    private final long nodeId;
    private NodeStatus status = NodeStatus.PENDING;
    private int attempt = 0;
    private Long currentResourceId;
    private final Set<Long> failedResourceIds = new LinkedHashSet<>();
    private String lastReason = "";
    private String lastErrorCode;

    public NodeProgress(long nodeId) {
        this.nodeId = nodeId;
    }

    public long nodeId() { return nodeId; }
    public NodeStatus status() { return status; }
    public int attempt() { return attempt; }
    public Long currentResourceId() { return currentResourceId; }
    public Set<Long> failedResourceIds() { return Collections.unmodifiableSet(failedResourceIds); }
    public String lastReason() { return lastReason; }
    public String lastErrorCode() { return lastErrorCode; }

    void setStatus(NodeStatus s) { this.status = s; }

    void setCurrentResource(Long id) {
        this.currentResourceId = id;
        this.status = NodeStatus.RUNNING;
    }

    void incrementAttempt() { this.attempt++; }

    void addFailedResource(Long id) {
        if (id != null) {
            failedResourceIds.add(id);
        }
    }

    void setLastReason(String reason) {
        this.lastReason = reason == null ? "" : reason;
    }

    void setLastErrorCode(String code) {
        this.lastErrorCode = code;
    }

    @Override
    public String toString() {
        return String.format("node=%d %s attempt=%d resource=%s failed=%s%s",
                nodeId, status, attempt, currentResourceId, failedResourceIds,
                lastReason.isBlank() ? "" : " | " + lastReason);
    }
}
