package com.labos.kernel.engine;

import com.labos.kernel.driver.DriverResult;

/**
 * §3.2.1「错误码/驱动状态 → 执行确认四态」的纯逻辑分类器（第一版，S2 用）。
 *
 * <p>把一次 step 执行的结果归到 {@code run_node.execution_resolution_status}
 * 的四个取值之一。纯函数，不碰库/不碰 IO，单测覆盖。
 *
 * <p><b>为什么保守</b>：material-transition 节点「设备可能已动」时一律归
 * {@link #RECONCILIATION_REQUIRED}（需对账），而不是 {@link #FAILED_CONFIRMED}
 * （确认没发生）——一旦把「已发生」误判成「没发生」，物料状态会与物理现实脱节。
 */
public enum ExecutionResolution {

    /** 未开始 / 正在执行。 */
    UNRESOLVED("unresolved"),
    /** 成功且证据满足声明快照。 */
    COMPLETED("completed"),
    /** 设备明确拒绝/参数错误，确认「未执行」，且无派发迹象。 */
    FAILED_CONFIRMED("failed_confirmed"),
    /** 已下发但物理结果不确定（超时/连接中断/设备回了失败），需对账。 */
    RECONCILIATION_REQUIRED("reconciliation_required");

    private final String dbValue;

    ExecutionResolution(String dbValue) {
        this.dbValue = dbValue;
    }

    /** 数据库列 {@code execution_resolution_status} 的取值（小写）。 */
    public String dbValue() {
        return dbValue;
    }

    /**
     * 根据驱动状态 + 错误码分类。
     *
     * @param status    {@link StepFailure#driverStatus()}；非驱动失败为 null
     * @param errorCode 错误码（当前第一版不参与区分，保留参数以备按码细分）
     */
    public static ExecutionResolution classify(DriverResult.Status status, String errorCode) {
        if (status == null) {
            // 没有 DriverResult.Status = 命令没走到「驱动回包」这一步。
            // 但「驱动异常 RuntimeException」可能已经半路发出命令（未知是否发生），
            // 保守按「需对账」处理，不冒险当「确认未执行」。
            return RECONCILIATION_REQUIRED;
        }
        return switch (status) {
            case SUCCESS -> COMPLETED;
            case REJECTED -> FAILED_CONFIRMED;          // 设备明确拒绝，未执行
            case FAILED -> RECONCILIATION_REQUIRED;      // 回了失败，物理结果不确定
            case TIMEOUT -> RECONCILIATION_REQUIRED;     // 无回包，可能已发生
        };
    }

    /** 从 StepFailure 直接分类的便捷入口。 */
    public static ExecutionResolution of(StepFailure f) {
        return classify(f.driverStatus(), f.errorCode());
    }
}
