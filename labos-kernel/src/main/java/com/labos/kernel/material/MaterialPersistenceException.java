package com.labos.kernel.material;

/**
 * T1 执行确认 / 效果意图写不进（§3.1 第 7 出口、§3.3、设计 §3.6）。
 *
 * <p>设备可能已经动作，但「物理完成」的结论没有落库。此时绝不能按普通执行失败
 * 处理（{@code markRunFailed} 允许续跑重放），必须由调用方把 run 转
 * {@code quarantined} 并抬实验级 hold，等人工先按命令账本对账。
 *
 * <p>本异常只包装 T1 主事务的失败；T2 物料投影失败是可补偿的
 * （effect 保持 PENDING，由 Worker 重试），不抛本异常。
 */
public final class MaterialPersistenceException extends RuntimeException {

    private final long runId;
    private final String nodeKey;

    public MaterialPersistenceException(long runId, String nodeKey, Throwable cause) {
        super("T1 物料证据落库或闸门判定失败（run=" + runId + ", node=" + nodeKey + "）: "
                + (cause == null ? "unknown" : cause.getMessage()), cause);
        this.runId = runId;
        this.nodeKey = nodeKey;
    }

    public long runId() {
        return runId;
    }

    public String nodeKey() {
        return nodeKey;
    }
}
