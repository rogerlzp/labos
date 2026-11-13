package com.labos.kernel.driver;

/**
 * P2-7 · 扫码校验（B6）的纯逻辑：node_binding.barcode_policy 的判定。
 *
 * <p>五值（外部厂商B P30）：
 * <ul>
 *   <li>{@code none} 不扫码；</li>
 *   <li>{@code read} 读码写日志，不校验；</li>
 *   <li>{@code update} 读码后修正库存（副作用在别处）；</li>
 *   <li>{@code validate_in} 进设备时校验条码一致，不一致 ⇒ 转人工；</li>
 *   <li>{@code validate_both} 进、出都校验。</li>
 * </ul>
 *
 * <p>这里只做「该不该校验 + 是否不匹配」的纯判定，不含 IO；运行时接线
 * （从 rawResponse 抽条码、写 manual_window）在执行路径。
 */
public final class ScanPolicy {

    private ScanPolicy() {
    }

    /** 该策略在「进设备」方向是否需要校验。 */
    public static boolean validateOnEnter(String policy) {
        return "validate_in".equals(policy) || "validate_both".equals(policy);
    }

    /** 该策略在「出设备」方向是否需要校验。 */
    public static boolean validateOnExit(String policy) {
        return "validate_both".equals(policy);
    }

    /**
     * 是否违反（条码不匹配）——策略不校验、或策略非法时视为不违反（不放行也不拦）。
     *
     * @param policy   barcode_policy 五值之一
     * @param inbound  true=进设备方向；false=出设备方向
     * @param expected 期望条码（库存/声明里的）
     * @param scanned  实际扫到的条码（设备回包）
     * @return true = 需要校验且不匹配（应转人工）
     */
    public static boolean violates(String policy, boolean inbound,
                                   String expected, String scanned) {
        if (policy == null) {
            return false;
        }
        boolean need = inbound ? validateOnEnter(policy) : validateOnExit(policy);
        if (!need) {
            return false;
        }
        if (scanned == null || scanned.isBlank()) {
            return true;   // 该校验却没扫到码 ⇒ 视为不匹配（fail-closed）
        }
        if (expected == null || expected.isBlank()) {
            return false;  // 没有期望值，无从比对（放行，避免误拦）
        }
        return !expected.equals(scanned);
    }
}
