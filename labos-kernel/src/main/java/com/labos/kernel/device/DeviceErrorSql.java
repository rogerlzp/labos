package com.labos.kernel.device;

import com.labos.kernel.device.DeviceModel.Disposition;
import com.labos.kernel.device.DeviceModel.ErrorDef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从**型号库**生成错误码字典与恢复策略的 SQL。
 *
 * <p><b>为什么要有生成器</b>：设备错误码在两处存在——
 * 型号库（代码，跟手册走版本）和数据库（{@code error_code} + {@code recovery_policy}）。
 * 手抄两遍必然漂移：手册改了、代码改了，SQL 里却没跟着更新，
 * 结果就是"日志里能看到 {@code 0x8000000C}，恢复引擎却查不到策略"。
 *
 * <p>所以：<b>型号库是唯一真源</b>，SQL 由它生成。
 * 改了型号库就重跑一次，把输出贴进 {@code pg_rebuild/schema/95_seed_data.sql}
 * （自检 {@code DeviceErrorChainSelfTest} 会检查种子文件是否已同步）。
 *
 * <p>用法：
 * <pre>
 *   java -cp out com.labos.kernel.device.DeviceErrorSql --list
 *   java -cp out com.labos.kernel.device.DeviceErrorSql --emit-sql
 * </pre>
 */
public final class DeviceErrorSql {

    /**
     * 适配层自己产生的错误码（不是设备回的，是我们判断出来的时序/部署问题）。
     *
     * <p>它们也必须进字典：否则 {@code recovery_policy} 查不到，
     * 会被当作"未知错误"转人工，掩盖掉真正的配置错误。
     */
    private static final List<ErrorDef> ADAPTER_LEVEL = List.of(
            new ErrorDef("nRetValue=0", "SendData 返回 0（设备拒绝或通讯异常）",
                    Disposition.MANUAL, "EC-05-2001",
                    "看 AOutResult 里的 XML 错误；核对 CAN/串口与模块节点配置"),
            new ErrorDef("PcCreateModules 未调用", "未创建模块实例就下发了指令",
                    Disposition.ABORT, "EC-05-2002",
                    "流程/适配层先发 PcCreateModules，再发其它指令"),
            new ErrorDef("Init 未调用", "模块未初始化就下发了动作指令",
                    Disposition.ABORT, "EC-05-2003",
                    "动作前先发 Init（回原点），否则设备状态未知"));

    private DeviceErrorSql() {
    }

    /**
     * 型号库里所有错误码 → 中控码（含适配层码）。
     *
     * <p><b>按中控码去重</b>：适配层码（EC-05-200x）既挂在型号上（说明"这台会报这个"），
     * 也在 {@link #ADAPTER_LEVEL} 里有全局定义。生成 SQL 时同一个中控码只能有一行，
     * 否则 `ON CONFLICT DO NOTHING` 之外的行为（策略重复插入）会出问题。
     */
    public static List<Mapping> allMappings() {
        Map<String, Mapping> byCode = new LinkedHashMap<>();
        for (DeviceModel m : DeviceModels.all()) {
            for (Map.Entry<String, ErrorDef> e : m.errorTable().entrySet()) {
                ErrorDef d = e.getValue();
                byCode.putIfAbsent(d.kernelCode(), new Mapping(m.id, m.type, d));
            }
        }
        for (ErrorDef d : ADAPTER_LEVEL) {
            byCode.putIfAbsent(d.kernelCode(), new Mapping("适配层", "adapter", d));
        }
        return new ArrayList<>(byCode.values());
    }

    /** 一条映射。 */
    public record Mapping(String modelId, String type, ErrorDef def) {
    }

    /** 该错误码对应的恢复策略（规则，不逐条手填）。 */
    public static Policy policyOf(Disposition d) {
        return switch (d) {
            case RETRY -> new Policy("retry", 3,
                    "{\"backoff\":\"exponential\",\"base_ms\":500}", 10);
            case FIX_PARAM, MANUAL -> new Policy("manual", 1, "{}", 20);
            case ABORT -> new Policy("abort", 1, "{}", 5);
        };
    }

    /** 策略三元组：strategy / max_attempts / params / priority。 */
    public record Policy(String strategy, int maxAttempts, String params, int priority) {
    }

    // ---------------------------------------------------------------- 输出

    public static void main(String[] args) {
        boolean emitSql = false;
        for (String a : args) {
            if ("--emit-sql".equals(a)) {
                emitSql = true;
            }
            if ("--list".equals(a)) {
                emitSql = false;
            }
        }
        if (emitSql) {
            System.out.print(sql());
        } else {
            System.out.print(listing());
        }
    }

    /** 人看的表。 */
    public static String listing() {
        StringBuilder sb = new StringBuilder();
        sb.append("设备错误码映射（型号库 → 中控码 → 恢复动作）\n");
        sb.append("=".repeat(110)).append('\n');
        String lastType = null;
        for (Mapping m : allMappings()) {
            if (!m.type().equals(lastType)) {
                sb.append('\n').append("【").append(typeName(m.type())).append("】\n");
                sb.append(String.format("  %-12s %-20s %-12s %-10s %-9s %s%n",
                        "型号", "设备码", "中控码", "处置", "严重度", "含义 / 建议"));
                lastType = m.type();
            }
            ErrorDef d = m.def();
            sb.append(String.format("  %-12s %-20s %-12s %-10s %-9s %s%n",
                    m.modelId(), d.vendorCode(), d.kernelCode(), d.disposition(),
                    DeviceErrorMapper.severityOf(d.disposition()),
                    d.meaning() + " / " + d.action()));
        }
        sb.append('\n');
        sb.append("规则：可自动重试的严重度=warn；需人处理=error；协议/时序配错=fatal。\n");
        sb.append("表格里的\"处置\"是设备侧建议，最终由 recovery_policy 表决定（现场可改）。\n");
        return sb.toString();
    }

    /** 机器用的 SQL（贴进 95_seed_data.sql）。 */
    public static String sql() {
        StringBuilder sb = new StringBuilder();
        sb.append("-- ─────────────────────────────────────────────────────────────\n");
        sb.append("-- 设备专属错误码 → 中控错误码 → 恢复策略\n");
        sb.append("-- **由 com.labos.kernel.device.DeviceErrorSql --emit-sql 生成，不要手改**\n");
        sb.append("-- 改了型号库（DeviceModels）就重跑一次，整体替换本节。\n");
        sb.append("-- 说明：scope 用 node —— 设备调用失败在 DriverStepExecutor 里\n");
        sb.append("--       一律以 RecoveryScope.NODE 抛出，写成 command 永远匹配不上。\n");
        sb.append("-- ─────────────────────────────────────────────────────────────\n");

        List<Mapping> mappings = allMappings();
        // 1) 错误码字典
        List<String> values = new ArrayList<>();
        for (Mapping m : mappings) {
            ErrorDef d = m.def();
            String title = "adapter".equals(m.type())
                    ? d.meaning() + "（适配层 " + d.vendorCode() + "）"
                    : d.meaning() + "（" + m.modelId() + " " + d.vendorCode() + "）";
            values.add(String.format("  (1,%s,'device',%s,%s,%s)",
                    q(d.kernelCode()), q(DeviceErrorMapper.severityOf(d.disposition())),
                    q(title), q("建议：" + d.action())));
        }
        sb.append("INSERT INTO error_code (tenant_id, code, source, severity, title, detail) VALUES\n");
        sb.append(String.join(",\n", values));
        sb.append("\nON CONFLICT (tenant_id, code) DO NOTHING;\n\n");

        // 2) 恢复策略
        Map<String, Mapping> byCode = new LinkedHashMap<>();
        for (Mapping m : mappings) {
            byCode.putIfAbsent(m.def().kernelCode(), m);
        }
        for (Mapping m : byCode.values()) {
            ErrorDef d = m.def();
            Policy p = policyOf(d.disposition());
            sb.append("INSERT INTO recovery_policy ")
                    .append("(tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)\n")
                    .append("SELECT 1, id, 'node', ").append(q(p.strategy()))
                    .append(", ").append(q(p.params()))
                    .append("::jsonb, ").append(p.maxAttempts())
                    .append(", ").append(p.priority())
                    .append("\nFROM error_code WHERE tenant_id=1 AND code=")
                    .append(q(d.kernelCode()))
                    .append("\nON CONFLICT DO NOTHING;\n");
        }
        return sb.toString();
    }

    private static String q(String v) {
        if (v == null) {
            return "NULL";
        }
        return "'" + v.replace("'", "''") + "'";
    }

    private static String typeName(String type) {
        return switch (type) {
            case "centrifuge" -> "离心机";
            case "sealer" -> "封膜机";
            case "adapter" -> "适配层（不是设备回的码，是我们判断出来的时序/部署问题）";
            default -> type;
        };
    }
}
