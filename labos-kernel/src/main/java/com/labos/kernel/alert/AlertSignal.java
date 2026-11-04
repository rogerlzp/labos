package com.labos.kernel.alert;

import java.time.Instant;

/**
 * 一条待聚合的告警信号。
 *
 * <p>刻意不依赖 {@code Occurrence}，保持 alert 包独立可测。
 */
public record AlertSignal(
        String errorCode,
        Long resourceId,
        String severity,
        String message,
        Instant at) {

    public AlertSignal {
        if (errorCode == null || errorCode.isBlank()) {
            throw new IllegalArgumentException("errorCode 不能为空");
        }
        if (at == null) {
            throw new IllegalArgumentException("at 不能为空");
        }
        if (severity == null || severity.isBlank()) {
            severity = "warn";
        }
    }

    /**
     * 聚合键：错误码 + 设备。
     *
     * <p>同一台设备的同一个错误才算"同一类"，不同设备要分开告警——
     * 否则一台设备离线会掩盖另一台设备的真实故障。
     */
    public String fingerprint() {
        return errorCode + "|" + (resourceId == null ? "any" : resourceId);
    }
}
