package com.labos.kernel.driver;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 驱动返回的执行结果。
 *
 * <p><b>{@code deviceRawResponse} 是这份契约最重要的字段</b>：
 * 老系统 十余万 条执行记录里 99.7% 没有保存设备原始回包，
 * 出问题只能靠猜。新契约强制驱动回传原文，落库到 {@code run_command.device_raw_response}。
 *
 * <p><b>{@code errorCode} 必须是有语义的码</b>，禁止一律返回 {@code 宽泛错误码}——
 * 老库 绝大多数 的错误都归到这个万能码，导致 91% 被无脑 Ignore。
 */
public record DriverResult(
        String commandId,
        Status status,
        String errorCode,
        String message,
        String deviceRawResponse,
        long durationMs,
        Map<String, Object> outputs) {

    public enum Status {
        /** 成功。 */
        SUCCESS,
        /** 执行失败（设备回了明确的错误）。 */
        FAILED,
        /** 超时（设备无响应）。 */
        TIMEOUT,
        /** 拒绝执行（参数非法、设备忙、能力不支持）。 */
        REJECTED
    }

    public DriverResult {
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("commandId 不能为空");
        }
        if (status == null) {
            throw new IllegalArgumentException("status 不能为空");
        }
        if (status != Status.SUCCESS && (errorCode == null || errorCode.isBlank())) {
            throw new IllegalArgumentException(
                    "非成功结果必须携带 errorCode——否则恢复引擎无从决策");
        }
        if ("宽泛错误码".equals(errorCode)) {
            throw new IllegalArgumentException(
                    "禁止使用兜底码 宽泛错误码——必须给出可区分语义的错误码");
        }
        outputs = outputs == null
                ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(outputs));
    }

    public static DriverResult success(String commandId, String raw, long ms) {
        return new DriverResult(commandId, Status.SUCCESS, null, "ok", raw, ms, Map.of());
    }

    public static DriverResult failed(String commandId, String code, String msg, String raw, long ms) {
        return new DriverResult(commandId, Status.FAILED, code, msg, raw, ms, Map.of());
    }

    public static DriverResult timeout(String commandId, long ms) {
        return new DriverResult(commandId, Status.TIMEOUT, "EC-05-1007",
                "设备回包超时", null, ms, Map.of());
    }

    public static DriverResult rejected(String commandId, String code, String msg) {
        return new DriverResult(commandId, Status.REJECTED, code, msg, null, 0, Map.of());
    }

    public boolean ok() {
        return status == Status.SUCCESS;
    }

    /** 该失败是否值得重试（决定恢复引擎是否选择 RETRY）。 */
    public boolean retryable() {
        return status == Status.TIMEOUT
                || "EC-05-1006".equals(errorCode)
                || "EC-07-0001".equals(errorCode);
    }
}
