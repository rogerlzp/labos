package com.labos.kernel.device;

import com.labos.kernel.device.DeviceModel.Disposition;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * **设备原始错误码 → 中控错误码**的翻译层。
 *
 * <p><b>为什么必须有这一层</b>：设备说的是它自己的话。
 * S100 报 {@code 0x8000000C}，中控的恢复引擎只认 {@code EC-xx-xxxx}；
 * 不翻译的话，这个码会一路透传到 {@code recovery_policy} 查不到任何策略，
 * 于是<b>所有设备故障都被当成"未知错误，转人工"</b>——
 * 重试该自动化的没有自动化，该叫人的人和"未知错误"混在一起，告警就没人看了。
 *
 * <p><b>翻译结果分两部分</b>：
 * <ul>
 *   <li><b>码</b>（{@link Mapped#kernelCode()}）：进 {@code run_command.error_code}，
 *       {@code recovery_policy} 按它查策略；</li>
 *   <li><b>处置建议</b>（{@link Mapped#disposition()} / {@link Mapped#action()}）：
 *       进告警正文和现场看板，告诉人"换纸"还是"重启"。</li>
 * </ul>
 *
 * <p><b>未登记的码怎么处理</b>：统一翻成 {@code EC-08-9001}（"未登记的设备错误码"），
 * 并按 <b>MANUAL</b> 处置 —— 不猜、不重试。原始码原样保留在
 * {@code device_raw_response} 与 message 里，方便事后补登记。
 *
 * <p><b>型号怎么知道</b>：一台设备实例（{@code device_instance.id}）对应哪个型号，
 * 由调用方注入（生产环境从 {@code device_instance → device.model / attr.device_model} 读，
 * 见 {@code WorkflowService}；测试和现场临时联调可用环境变量
 * {@code LABOS_DEVICE_MODELS="214=s100,215=aspin-2k"}，或用
 * {@code LABOS_DEFAULT_DEVICE_MODEL=s100} 给所有设备一个兜底型号）。
 */
public final class DeviceErrorMapper {

    /** 未登记的设备错误码（保守：转人工，不重试）。 */
    public static final String UNKNOWN_CODE = "EC-08-9001";

    /** 已经是中控码（EC-xx-xxxx）的原样透传，避免二次翻译。 */
    private static final Pattern KERNEL_CODE =
            Pattern.compile("^EC-[A-Z0-9]+(-[A-Z0-9]+)+$");

    /**
     * 翻译结果。
     *
     * @param modelId     命中的型号；null = 这台设备没绑型号
     * @param vendorCode  设备原始码（未知也原样保留）
     * @param known       是否在型号库里登记过
     */
    public record Mapped(String kernelCode, String severity, String modelId,
                         String vendorCode, String meaning,
                         Disposition disposition, String action, boolean known) {

        /** 给告警/日志用的一行摘要。 */
        public String describe() {
            String who = modelId == null ? "未绑定型号" : modelId;
            return String.format("%s 报 %s「%s」→ %s（%s）；建议：%s",
                    who, vendorCode, meaning, kernelCode, disposition, action);
        }
    }

    private final Map<Long, String> modelByInstance;
    private final String defaultModelId;

    public DeviceErrorMapper(Map<Long, String> modelByInstance, String defaultModelId) {
        this.modelByInstance = modelByInstance == null
                ? Map.of() : new LinkedHashMap<>(modelByInstance);
        this.defaultModelId = defaultModelId;
    }

    /** 不做翻译（保持 1.0 行为：设备码原样透传）。 */
    public static DeviceErrorMapper passthrough() {
        return new DeviceErrorMapper(Map.of(), null);
    }

    /**
     * 从环境变量构建：{@code LABOS_DEVICE_MODELS="214=s100,215=aspin-2k"}，
     * {@code LABOS_DEFAULT_DEVICE_MODEL=s100}（可选兜底）。
     *
     * <p>只认型号库里存在的 id；写错的型号在启动时打印出来，
     * <b>不会静默失效</b>（静默失效的映射比没有映射更危险）。
     */
    public static DeviceErrorMapper fromEnv() {
        Map<Long, String> map = new LinkedHashMap<>();
        String raw = System.getenv("LABOS_DEVICE_MODELS");
        if (raw != null && !raw.isBlank()) {
            for (String part : raw.split("[,;]")) {
                String[] kv = part.trim().split("=");
                if (kv.length != 2) {
                    continue;
                }
                String modelId = kv[1].trim();
                if (DeviceModels.of(modelId) == null) {
                    System.err.println("[设备型号] 忽略未知型号绑定 " + part.trim()
                            + "，可用：" + DeviceModels.knownIds());
                    continue;
                }
                try {
                    map.put(Long.parseLong(kv[0].trim()), modelId);
                } catch (NumberFormatException e) {
                    System.err.println("[设备型号] 忽略非法设备 id " + part.trim());
                }
            }
        }
        String def = System.getenv("LABOS_DEFAULT_DEVICE_MODEL");
        if (def != null && !def.isBlank() && DeviceModels.of(def) == null) {
            System.err.println("[设备型号] LABOS_DEFAULT_DEVICE_MODEL=" + def
                    + " 不在型号库中，已忽略");
            def = null;
        }
        return new DeviceErrorMapper(map, def == null || def.isBlank() ? null : def);
    }

    public boolean enabled() {
        return !modelByInstance.isEmpty() || defaultModelId != null;
    }

    /** 某台设备绑定的型号 id；null = 没绑。 */
    public String modelOf(long deviceInstanceId) {
        return modelByInstance.getOrDefault(deviceInstanceId, defaultModelId);
    }

    /** 型号绑定（只读），给启动日志用。 */
    public Map<Long, String> bindings() {
        return Map.copyOf(modelByInstance);
    }

    /**
     * 翻译一个设备错误码。
     *
     * @param deviceInstanceId 设备实例 id（用于找型号）
     * @param vendorCode       设备原始码；已经是 EC 码时原样返回
     * @param errorText        设备给的原文（可能为空）
     */
    public Mapped map(long deviceInstanceId, String vendorCode, String errorText) {
        String code = vendorCode == null ? "" : vendorCode.trim();
        String modelId = modelOf(deviceInstanceId);

        if (KERNEL_CODE.matcher(code).matches()) {
            // 适配层已经归一过（或设备本来就报中控码），别再翻一次
            return new Mapped(code, "error", modelId, code,
                    errorText == null ? "设备返回失败" : errorText, Disposition.MANUAL,
                    "按错误码表处理", true);
        }

        DeviceModel model = DeviceModels.of(modelId);
        DeviceModel.ErrorDef def = model == null ? null : model.error(code);
        if (def != null) {
            return new Mapped(def.kernelCode(), severityOf(def.disposition()), modelId,
                    def.vendorCode(), def.meaning(), def.disposition(), def.action(), true);
        }

        String text = (errorText == null || errorText.isBlank())
                ? "设备返回了未登记的错误码" : errorText;
        String meaning = code.isEmpty() ? text : "未登记的设备错误码 " + code + "（" + text + "）";
        return new Mapped(UNKNOWN_CODE, "error", modelId, code, meaning,
                Disposition.MANUAL, "查手册补登记到型号库后再接真机", false);
    }

    /**
     * 处置 → 严重度。
     *
     * <p>这条映射是**规则**不是逐条手填，理由是它要被审计：
     * 能自动重试的不该是 fatal（否则半夜叫人起床），
     * 必须人处理的不该是 warn（否则被忽略）。
     */
    public static String severityOf(Disposition d) {
        return switch (d) {
            case RETRY -> "warn";
            case FIX_PARAM, MANUAL -> "error";
            case ABORT -> "fatal";
        };
    }
}
