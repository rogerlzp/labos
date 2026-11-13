package com.labos.kernel.engine;

/**
 * 子步骤回调：执行器每完成一步就报一次，由调用方决定怎么落库。
 *
 * <p>为什么要这个：一个业务节点（如 READ_PLATE）展开成 7 步，
 * 传统上只能看到"节点成功/失败"，看不到"第几步失败、每步多久、设备原文是什么"。
 *
 * <p>有了 sink，上层就能落成树：父 run_node + N 个子 run_node + N 条 run_command。
 */
public interface StepSink {

    /** 某步开始。 */
    default void onStepStart(long nodeId, int seq, String method, long resourceId, int attempt) {
    }

    /** 某步结束。 */
    default void onStepDone(long nodeId, int seq, String method, boolean ok,
                            String errorCode, String rawResponse, long durationMs, int attempt) {
    }

    /**
     * 某步在**下发前**就失败（例如装载口等待超时 EC-RESOURCE-BUSY）。
     *
     * <p>与 {@link #onStepDone} 的关键区别：命令根本没到设备，**不能**写
     * run_command / command_ledger。否则续跑守卫
     * （{@code RunMapper.countResumeGuardHits}）会把它当成「设备已动」而禁止
     * 删除重建 ⇒ 人工修复后点继续永远失败（run 1001411 实测）。
     *
     * <p>默认退化为 onStepDone，保持既有实现零改动。
     */
    default void onStepAborted(long nodeId, int seq, String method,
                               long resourceId, int attempt, String errorCode) {
        onStepDone(nodeId, seq, method, false, errorCode, null, 0L, attempt);
    }

    /** 什么都不做。 */
    StepSink NOOP = new StepSink() {
    };
}
