package com.labos.kernel.event;

import java.util.List;

/**
 * 设备事件的读取口。
 *
 * <p><b>为什么是接口而不是直接查库</b>：等待节点（{@code __awaitEvent}）只关心
 * "起手之后新来的、匹配条件的事件"。默认实现查 {@code device_event} 表；
 * 自测可以塞一个内存实现 ⇒ <b>不连库也能验事件语义</b>。
 *
 * <p>语义约定：
 * <ul>
 *   <li>{@code deviceCode} / {@code eventType} 传 null 或空 = <b>不过滤</b>；</li>
 *   <li>返回的必须是 <b>按 id 升序</b> 的事件（调用方取第一条命中的）；</li>
 *   <li>实现必须<b>不抛异常</b>（读不到就当没有事件，让调用方按超时处理）——
 *       事件源是"锦上添花"，不能因为它坏了就带崩整条流程。</li>
 * </ul>
 */
public interface DeviceEventSource {

    /**
     * 取 {@code afterId} 之后、匹配条件的事件。
     *
     * @param deviceCode 设备码；null/空 = 任意设备
     * @param eventType  事件类型；null/空 = 任意类型
     * @param afterId    游标（只取比它大的）
     * @param limit      最多取几条
     */
    List<DeviceEvent> since(String deviceCode, String eventType, long afterId, int limit);

    /**
     * 标记事件已被消费（**观测用**：让"这条事件被谁拿走了"可查）。
     *
     * <p>默认空实现 —— 内存实现（自测用）不需要落库。
     * 实现方**不得抛异常**：标记失败不影响流程。
     */
    default void markDispatched(long eventId) {
    }
}
