package com.labos.kernel.engine;

import com.labos.kernel.driver.DriverResult;
import com.labos.kernel.recovery.RecoveryScope;

/**
 * 一步执行失败的信号。
 *
 * <p>带上 {@code scope}（错误层级）与 {@code stepSeq}（第几步失败）：
 * 前者决定走哪条恢复策略（COMMAND 重试 / NODE 换机 / RUN 终止），
 * 后者用于定位"7 步里第 3 步挂了"。
 */
public final class StepFailure extends Exception {

    private static final long serialVersionUID = 1L;

    private final String errorCode;
    private final RecoveryScope scope;
    /** 1-based 步骤序号；0 表示未分步（单步执行）。 */
    private final int stepSeq;
    private final String stepMethod;
    /** S0-safety U10 载体 ①：驱动状态（TIMEOUT/REJECTED/FAILED）；非驱动失败为 null。 */
    private final DriverResult.Status driverStatus;

    public StepFailure(String errorCode, RecoveryScope scope, String message) {
        this(errorCode, scope, message, 0, null, null);
    }

    public StepFailure(String errorCode, RecoveryScope scope, String message,
                       int stepSeq, String stepMethod) {
        this(errorCode, scope, message, stepSeq, stepMethod, null);
    }

    public StepFailure(String errorCode, RecoveryScope scope, String message,
                       int stepSeq, String stepMethod, DriverResult.Status driverStatus) {
        super(message);
        if (errorCode == null || errorCode.isBlank()) {
            throw new IllegalArgumentException("errorCode 不能为空");
        }
        if ("宽泛错误码".equals(errorCode)) {
            throw new IllegalArgumentException(
                    "禁止使用兜底码 宽泛错误码——必须给出可区分语义的错误码");
        }
        if (scope == null) {
            throw new IllegalArgumentException("scope 不能为空");
        }
        this.errorCode = errorCode;
        this.scope = scope;
        this.stepSeq = stepSeq;
        this.stepMethod = stepMethod;
        this.driverStatus = driverStatus;
    }

    public String errorCode() {
        return errorCode;
    }

    public RecoveryScope scope() {
        return scope;
    }

    public int stepSeq() {
        return stepSeq;
    }

    public String stepMethod() {
        return stepMethod;
    }

    public DriverResult.Status driverStatus() {
        return driverStatus;
    }

    /** 给事件/日志用的简短描述。 */
    public String describe() {
        return stepSeq > 0
                ? String.format("%s | 第 %d 步 %s | %s", errorCode, stepSeq, stepMethod, getMessage())
                : errorCode + " | " + getMessage();
    }
}
