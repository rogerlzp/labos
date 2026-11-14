package com.labos.kernel.event;

/**
 * 一条设备主动上报的事件。
 *
 * <p><b>为什么需要它</b>：客户的提取仪是「分段自驱」——设备自己跑到交换点，
 * <b>主动上报【等待交互】</b>，等上位机把板换好、上位机发【继续信号】它才往下走。
 * ⇒ 上位机要等的是「一次事件」，不是「某个变量当前等于某值」。
 *
 * <p>与「变量等值」的本质差别：变量是<b>电平</b>（设备报了就一直是这样），
 * 事件是<b>边</b>（一次性的、带着上下文的）。等事件必须带 <b>游标</b>，
 * 否则会把历史存量当新事件。
 *
 * @param id         事件 id（全局单调 ⇒ 游标）
 * @param deviceCode 设备码
 * @param eventType  事件类型（例：{@code waiting_interaction}）
 * @param payload    事件载荷 JSON 文本（哪块板、哪个工位…）
 */
public record DeviceEvent(long id,
                           String deviceCode,
                           String eventType,
                           String payload) {
}
