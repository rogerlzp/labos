package com.labos.kernel.recovery;

/**
 * 退避方式。
 */
public enum Backoff {

    /** 固定间隔重试。 */
    FIXED,

    /** 指数退避：base * 2^attempt。用于避免对已故障设备的高频冲击。 */
    EXPONENTIAL
}
