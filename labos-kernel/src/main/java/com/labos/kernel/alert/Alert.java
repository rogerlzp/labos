package com.labos.kernel.alert;

import java.time.Instant;

/**
 * 聚合后的一条告警。
 */
public record Alert(
        AlertKind kind,
        String fingerprint,
        String errorCode,
        Long resourceId,
        String severity,
        int count,
        Instant firstAt,
        Instant lastAt,
        String message) {

    public Alert {
        if (kind == null) {
            throw new IllegalArgumentException("kind 不能为空");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("count 必须为正: " + count);
        }
    }

    /**
     * 该告警是否应该真的推送给值班人员。
     *
     * <p>两条排除规则：
     * <ul>
     *   <li>{@code SUMMARY} 只是归档统计，不打扰人</li>
     *   <li>{@code info} 级别是"留痕不告警"——SKIP 类错误走这条，
     *       否则 94% 的跳过会把真正的故障淹掉</li>
     * </ul>
     */
    public boolean shouldNotify() {
        return kind != AlertKind.SUMMARY && !"info".equalsIgnoreCase(severity);
    }

    @Override
    public String toString() {
        String range = firstAt.equals(lastAt) ? String.valueOf(firstAt)
                : firstAt + " ~ " + lastAt;
        return String.format("[%s] %s x%d (%s) %s", kind, fingerprint, count, range, message);
    }
}
