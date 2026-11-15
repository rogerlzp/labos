package com.labos.kernel.driver;

/**
 * 设备侧命令状态查询结果（R4 超时对账）。
 *
 * <p>只有 {@link #COMPLETED} / {@link #FAILED} 能使命令进入完成态；
 * {@link #IN_PROGRESS} 表示设备明确仍在执行；{@link #NOT_FOUND} 与 {@link #UNKNOWN}
 * 都不能证明设备没有动作，账本必须落 UNKNOWN 并转人工，禁止自动重发。
 */
public record CommandQueryResult(CommandQueryResult.State state, String detail) {

    public enum State {
        COMPLETED,
        FAILED,
        IN_PROGRESS,
        NOT_FOUND,
        UNKNOWN
    }

    public CommandQueryResult {
        if (state == null) {
            throw new IllegalArgumentException("查询状态不能为空");
        }
    }

    public static CommandQueryResult completed(String detail) {
        return new CommandQueryResult(State.COMPLETED, detail);
    }

    public static CommandQueryResult failed(String detail) {
        return new CommandQueryResult(State.FAILED, detail);
    }

    public static CommandQueryResult inProgress(String detail) {
        return new CommandQueryResult(State.IN_PROGRESS, detail);
    }

    public static CommandQueryResult notFound(String detail) {
        return new CommandQueryResult(State.NOT_FOUND, detail);
    }

    public static CommandQueryResult unknown(String detail) {
        return new CommandQueryResult(State.UNKNOWN, detail);
    }
}
