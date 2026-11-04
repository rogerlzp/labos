package com.labos.kernel.driver;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 下发给驱动的一条指令。
 *
 * <p><b>{@code commandId} 是幂等键</b>：重试必须复用同一个 id，驱动据此判断
 * "这条我已经执行过了"，避免机械臂把同一块板搬两次。
 * 老系统靠 150ms 轮询 + ack 超时重发，没有幂等键，重发就是真重做。
 */
public record DriverCommand(
        String commandId,
        long deviceInstanceId,
        String method,
        Map<String, Object> params,
        long timeoutMs,
        String traceId) {

    public DriverCommand {
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("commandId 不能为空（幂等键）");
        }
        if (deviceInstanceId <= 0) {
            throw new IllegalArgumentException("deviceInstanceId 非法: " + deviceInstanceId);
        }
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("method 不能为空");
        }
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs 必须为正");
        }
        params = params == null
                ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }
}
