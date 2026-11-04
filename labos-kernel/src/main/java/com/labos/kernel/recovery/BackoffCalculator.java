package com.labos.kernel.recovery;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * 退避时长计算。
 *
 * <p>指数退避 + 抖动，避免多台设备同时故障时所有重试在同一毫秒打过去。
 * 设备离线时若以 150ms 轮询、每秒重试，一天会打 5,928 次 —— 这就是没有退避的代价。
 */
public final class BackoffCalculator {

    /** 单次等待上限，防止指数退避算出离谱的值。 */
    static final long MAX_DELAY_MS = 5 * 60 * 1000L;

    private BackoffCalculator() {
    }

    /**
     * @param type        退避方式
     * @param baseDelay   基础间隔
     * @param attempt     已重试次数（0 表示第一次重试）
     * @param jitterRatio 抖动比例，0~1；0 表示关闭抖动（便于测试断言）
     * @param rnd         随机源
     */
    public static long compute(Backoff type, Duration baseDelay, int attempt,
                               double jitterRatio, RandomGenerator rnd) {
        if (baseDelay == null || baseDelay.isNegative()) {
            throw new IllegalArgumentException("baseDelay 非法");
        }
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt 不能为负");
        }
        if (jitterRatio < 0 || jitterRatio > 1) {
            throw new IllegalArgumentException("jitterRatio 必须在 [0,1]");
        }

        long base = baseDelay.toMillis();
        long raw = switch (type) {
            case FIXED -> base;
            case EXPONENTIAL -> {
                // 防溢出：attempt 很大时直接取上限
                if (attempt >= 20) {
                    yield MAX_DELAY_MS;
                }
                long shifted = base << attempt;
                yield shifted <= 0 ? MAX_DELAY_MS : shifted;
            }
        };

        long capped = Math.min(raw, MAX_DELAY_MS);

        if (jitterRatio == 0 || rnd == null) {
            return capped;
        }
        // 在 [capped*(1-j), capped*(1+j)] 内抖动
        long span = (long) (capped * jitterRatio);
        if (span <= 0) {
            return capped;
        }
        long offset = (long) (rnd.nextDouble() * (2 * span + 1)) - span;
        return Math.max(0, Math.min(MAX_DELAY_MS, capped + offset));
    }
}
