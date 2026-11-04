package com.labos.kernel.engine;

/**
 * 事件监听器：把状态机产生的事件落库、推送前端或写审计日志。
 *
 * <p>状态机本身不碰 IO，所有副作用由监听器承担。
 */
@FunctionalInterface
public interface ExecutionListener {

    void onEvent(ExecutionEvent event);

    ExecutionListener NOOP = event -> { };
}
