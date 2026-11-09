package com.labos.boot;

import org.slf4j.MDC;

/**
 * B 批：排查用的关联字段。日志里带上 run/command/workflow/trace，
 * 才能把「这条命令属于哪次运行、为什么暂停没生效」串起来。
 *
 * <p>用法（try-with-resources，防止线程池复用串号）：
 * <pre>
 * try (LogContext.Run scope = LogContext.forRun(runId, workflowId)) {
 *     ...
 * }
 * </pre>
 */
public final class LogContext {

    public static final String TRACE = "traceId";
    public static final String RUN = "runId";
    public static final String COMMAND = "commandId";
    public static final String WORKFLOW = "workflowId";

    private LogContext() {
    }

    public static Run forRun(long runId, long workflowId) {
        return new Run(String.valueOf(runId), String.valueOf(workflowId));
    }

    public static Run forRun(long runId) {
        return new Run(String.valueOf(runId), null);
    }

    public static void command(String commandId) {
        MDC.put(COMMAND, commandId);
    }

    public static void clearCommand() {
        MDC.remove(COMMAND);
    }

    /** 一个运行作用域：进入时写入，退出时精确还原旧值（支持嵌套）。 */
    public static final class Run implements AutoCloseable {
        private final String oldRun;
        private final String oldWorkflow;
        private final String oldTrace;

        private Run(String runId, String workflowId) {
            oldRun = MDC.get(RUN);
            oldWorkflow = MDC.get(WORKFLOW);
            oldTrace = MDC.get(TRACE);
            MDC.put(RUN, runId);
            if (workflowId != null) {
                MDC.put(WORKFLOW, workflowId);
            }
            if (MDC.get(TRACE) == null) {
                MDC.put(TRACE, Long.toString(System.nanoTime(), 36));
            }
        }

        @Override
        public void close() {
            restore(RUN, oldRun);
            restore(WORKFLOW, oldWorkflow);
            restore(TRACE, oldTrace);
        }

        private static void restore(String key, String value) {
            if (value == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, value);
            }
        }
    }
}
