package com.labos.kernel.persistence;

import com.labos.kernel.recovery.Backoff;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 极简 JSON 字段提取——只为解析 {@code recovery_policy.params} 里那几个已知字段，
 * 刻意不引入 Jackson，保持零依赖。
 *
 * <p>解析失败一律回退默认值，不抛异常：策略配置写错不该让引擎起不来。
 */
public final class JsonParams {

    private static final Pattern BASE_MS = Pattern.compile("\"base_ms\"\\s*:\\s*(\\d+)");
    private static final Pattern BACKOFF = Pattern.compile("\"backoff\"\\s*:\\s*\"([a-zA-Z_]+)\"");

    private JsonParams() {
    }

    public static Backoff backoff(String json, Backoff def) {
        if (json == null) {
            return def;
        }
        Matcher m = BACKOFF.matcher(json);
        if (m.find() && "exponential".equalsIgnoreCase(m.group(1))) {
            return Backoff.EXPONENTIAL;
        }
        return BACKOFF.matcher(json).find() ? Backoff.FIXED : def;
    }

    public static Duration baseDelay(String json, Duration def) {
        if (json == null) {
            return def;
        }
        Matcher m = BASE_MS.matcher(json);
        if (m.find()) {
            try {
                long ms = Long.parseLong(m.group(1));
                return Duration.ofMillis(Math.max(0, ms));
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }
}
