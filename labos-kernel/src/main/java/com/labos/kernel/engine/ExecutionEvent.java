package com.labos.kernel.engine;

import java.time.Instant;

/**
 * 一条执行事件（不可变）。
 *
 * <p>{@code seq} 单调递增，保证事件顺序可重建；
 * {@code at} 由注入的 Clock 提供，测试中可用固定时钟得到完全可复现的事件流。
 */
public record ExecutionEvent(
        long seq,
        Instant at,
        EventType type,
        long nodeId,
        String nodeKey,
        String detail) {

    public ExecutionEvent {
        if (type == null) {
            throw new IllegalArgumentException("type 不能为空");
        }
        if (detail == null) {
            detail = "";
        }
    }

    @Override
    public String toString() {
        return String.format("#%-3d %-20s node=%-4d %-12s %s",
                seq, type, nodeId, nodeKey == null ? "-" : nodeKey, detail);
    }
}
