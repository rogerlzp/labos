package com.labos.kernel.driver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 锁顺序观察器（2026-11-19 新增）。
 *
 * <p><b>只记录、不阻止</b>：默认 {@code log.warn}，不抛异常；
 * 设置 {@code LABOS_LOCK_ORDER_ENFORCE=true} 后才抛 {@link IllegalStateException}。
 *
 * <p><b>为什么默认不阻止</b>：断言误报会让正常搬运直接失败。
 * 真实顺序是 设备 → 位置 → 轨道（{@code DriverStepExecutor:524} 位置预留在前，
 * {@code :581} 轨道锁在后），但轨道锁复用 {@link DeviceLock} 落在 {@code device_state}，
 * 若按「存储组」而不是「申请槽位」算 rank，会得到 1 → 2 → 1 —— 每次正常搬运都会报警。
 * 所以必须先观察真实数据，确认无告警后再打开强制开关。
 *
 * <p><b>能力边界</b>：本类用 {@code ThreadLocal}，只能表达「本线程这一串申请的单调性」。
 * 跨 step 持有（位置锁可能跨 step）、跨线程释放、Resume 换线程都表达不了，
 * 那些场景必须靠锁表里的显式 {@code owner_token}。
 */
public final class LockOrderGuard {

    /**
     * 申请槽位，不是存储表。数值即顺序，只要求非递减。
     *
     * <p>允许相等：同一槽位可以多次申请（例如批量轨道分区）。
     */
    public enum ResourceType {
        DEVICE(1),
        POSITION(2),
        RAIL(3);

        public final int rank;

        ResourceType(int rank) {
            this.rank = rank;
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(LockOrderGuard.class);

    /** 本线程已持有的最高 rank；0 = 未持有。 */
    private static final ThreadLocal<int[]> HELD_MAX = ThreadLocal.withInitial(() -> new int[]{0});

    private static final boolean ENFORCE =
            "true".equalsIgnoreCase(System.getenv("LABOS_LOCK_ORDER_ENFORCE"));

    private LockOrderGuard() {
    }

    /**
     * 记录一次已持有。允许相等，只禁止倒退。
     *
     * @param type  申请槽位
     * @param where 调用点，写进告警便于定位
     */
    public static void enter(ResourceType type, String where) {
        int[] box = HELD_MAX.get();
        if (type.rank < box[0]) {
            String msg = "锁顺序倒退：" + where + " 申请 " + type + "(rank=" + type.rank
                    + ")，但本线程已持有 rank=" + box[0];
            if (ENFORCE) {
                throw new IllegalStateException(msg);
            }
            LOG.warn(msg);
            return;
        }
        box[0] = type.rank;
    }

    /** 一个 step 结束时重置，防止线程复用带来残留。 */
    public static void reset() {
        HELD_MAX.get()[0] = 0;
    }

    /** 诊断用：当前线程已持有的最高 rank。 */
    public static int current() {
        return HELD_MAX.get()[0];
    }
}
