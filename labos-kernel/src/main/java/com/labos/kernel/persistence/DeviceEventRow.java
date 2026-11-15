package com.labos.kernel.persistence;

import java.time.Instant;

/** 一条设备事件；payload 已由 SQL 转成 JSON 文本。 */
public record DeviceEventRow(long id,
                              Long deviceId,
                              String deviceCode,
                              String eventType,
                              String payload,
                              Long runId,
                              String sourceEventId,
                              String processId,
                              String interactionStep,
                              Instant createdAt) {
}
