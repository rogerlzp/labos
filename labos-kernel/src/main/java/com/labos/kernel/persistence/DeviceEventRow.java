package com.labos.kernel.persistence;

import java.time.Instant;

/**
 * 一条设备事件（{@code device_event} 的一行）。
 *
 * <p>{@code payload} 是 **JSON 文本**（不是 PGobject）——
 * SQL 里已经 {@code payload::text} 出来，调用方直接当字符串用。
 *
 * @param id         事件 id（全局单调 ⇒ 等待节点拿它当游标）
 * @param deviceId   设备实例 id（可空：只按设备码上报时）
 * @param deviceCode 设备码（原样保留）
 * @param eventType  事件类型（例：{@code waiting_interaction}）
 * @param payload    事件载荷 JSON 文本
 * @param runId      能关联到的 run（可空）
 * @param createdAt  落库时间
 */
public record DeviceEventRow(long id,
                              Long deviceId,
                              String deviceCode,
                              String eventType,
                              String payload,
                              Long runId,
                              Instant createdAt) {
}
