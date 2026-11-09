package com.labos.kernel.driver;

/**
 * 一次「中止请求」的结构化结果（B-04）。
 *
 * <p><b>为什么不能用 boolean</b>：B-02 之后我们知道「发了中止请求」和「设备真停了」
 * 是两件事，但它们之间不是二元的——真实链路有五种可区分状态，把它们压成
 * {@code true/false} 只会重演「本地取消冒充设备已停」：
 *
 * <ul>
 *   <li>{@link State#UNSUPPORTED} —— 驱动/协议根本没有中止能力，**什么都没发出去**；</li>
 *   <li>{@link State#REQUESTED} —— 停止指令已发出，设备还没回话；</li>
 *   <li>{@link State#DEVICE_ACKNOWLEDGED} —— 设备回了话（收到停止请求），
 *       但还没有「动作已停」的证据；</li>
 *   <li>{@link State#PHYSICALLY_STOPPED} —— 设备侧确认动作已停止。
 *       <b>只有这一种</b>才能让上层跳过「重跑这一步」的判断；</li>
 *   <li>{@link State#UNKNOWN} —— 发出去了，但结果不可知（超时/断链/回包无法解析）。</li>
 * </ul>
 *
 * <p>安全默认是 {@link State#UNSUPPORTED}：没有做过 HIL 的驱动不许自报能停。
 * 上层拿到非 {@code PHYSICALLY_STOPPED} 的结果，只能**继续等当前动作跑到安全边界**
 * ，不能在账本上把命令记成「已中止」。
 */
public record AbortResult(State state, String detail) {

    /** 中止结果的五种状态，语义见类注释。 */
    public enum State {
        UNSUPPORTED,
        REQUESTED,
        DEVICE_ACKNOWLEDGED,
        PHYSICALLY_STOPPED,
        UNKNOWN
    }

    public AbortResult {
        if (state == null) {
            throw new IllegalArgumentException("AbortResult.state 不能为空");
        }
        detail = detail == null ? "" : detail;
        if (detail.length() > 200) {
            // 账本列是 varchar(200)：在这里截断而不是让 UPDATE 抛错
            detail = detail.substring(0, 200);
        }
    }

    /** 只有「设备确认已停」才为真。别把 {@code REQUESTED} 当成停了。 */
    public boolean physicallyStopped() {
        return state == State.PHYSICALLY_STOPPED;
    }

    public static AbortResult unsupported(String detail) {
        return new AbortResult(State.UNSUPPORTED, detail);
    }

    public static AbortResult requested(String detail) {
        return new AbortResult(State.REQUESTED, detail);
    }

    public static AbortResult acknowledged(String detail) {
        return new AbortResult(State.DEVICE_ACKNOWLEDGED, detail);
    }

    public static AbortResult stopped(String detail) {
        return new AbortResult(State.PHYSICALLY_STOPPED, detail);
    }

    public static AbortResult unknown(String detail) {
        return new AbortResult(State.UNKNOWN, detail);
    }
}
