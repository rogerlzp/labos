package com.labos.kernel.device;

import java.util.Map;

/**
 * 把型号库导出成 JSON，给 **.NET sidecar** 用。
 *
 * <p><b>为什么要有这个类</b>：型号定义现在有三处消费者——模拟器（Java）、
 * 中控适配层（Java）、真机 sidecar（C#，跑在实验室的 Windows 工控机上）。
 * 如果 sidecar 自己再抄一份指令表，两份一定漂移：
 * 改手册时只改了一边，现象是"模拟器能跑、现场发不出指令"。
 *
 * <p>所以 sidecar **不写死任何设备知识**，启动时读这份 JSON。
 * 改型号库后跑一次：
 * <pre>
 *   cd labos-kernel
 *   java -cp out com.labos.kernel.device.DeviceModelExport &gt; ../sidecar-dotnet/models.json
 * </pre>
 *
 * <p>生成的文件进版本库（sidecar 部署到工控机时不带 Java 环境）。
 */
public final class DeviceModelExport {

    public static void main(String[] args) {
        System.out.println(json());
    }

    /** 供自检用：导出内容里必须包含某个型号时直接比对字符串。 */
    public static String json() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"generatedBy\": \"com.labos.kernel.device.DeviceModelExport\",\n");
        sb.append("  \"note\": \"由型号库自动生成，不要手改；改 DeviceModels 后重跑\",\n");
        sb.append("  \"models\": [\n");

        boolean firstModel = true;
        for (DeviceModel m : DeviceModels.all()) {
            if (!firstModel) {
                sb.append(",\n");
            }
            firstModel = false;
            sb.append("    {\n");
            sb.append("      \"id\": ").append(q(m.id)).append(",\n");
            sb.append("      \"type\": ").append(q(m.type)).append(",\n");
            sb.append("      \"vendor\": ").append(q(m.vendor)).append(",\n");
            sb.append("      \"model\": ").append(q(m.model)).append(",\n");
            sb.append("      \"protocol\": ").append(q(m.protocol)).append(",\n");
            sb.append("      \"transport\": ").append(q(m.transport)).append(",\n");
            sb.append("      \"evidence\": ").append(q(m.evidence.name())).append(",\n");
            sb.append("      \"doorCount\": ").append(m.doorCount).append(",\n");
            sb.append("      \"actionTimeoutMs\": ").append(m.actionTimeoutMs).append(",\n");
            sb.append("      \"errorBlock\": ").append(m.errorBlock).append(",\n");
            sb.append("      \"note\": ").append(q(m.note)).append(",\n");

            // 方法：同一 cmd 有多个别名，逐个列出（sidecar 按别名分发）
            sb.append("      \"methods\": [\n");
            boolean first = true;
            for (DeviceModel.Method mm : m.methods()) {
                for (DeviceModel.Method v : m.aliases(mm.cmd())) {
                    if (!first) {
                        sb.append(",\n");
                    }
                    first = false;
                    sb.append("        { \"cmd\": ").append(q(v.cmd()))
                            .append(", \"alias\": ").append(q(v.aliasOf()))
                            .append(", \"args\": ").append(q(v.args()))
                            .append(", \"delayMs\": ").append(v.delayMs())
                            .append(", \"documented\": ").append(v.documented())
                            .append(" }");
                }
            }
            sb.append("\n      ],\n");

            sb.append("      \"errors\": [\n");
            first = true;
            for (Map.Entry<String, DeviceModel.ErrorDef> e : m.errorTable().entrySet()) {
                DeviceModel.ErrorDef d = e.getValue();
                if (!first) {
                    sb.append(",\n");
                }
                first = false;
                sb.append("        { \"vendorCode\": ").append(q(d.vendorCode()))
                        .append(", \"meaning\": ").append(q(d.meaning()))
                        .append(", \"disposition\": ").append(q(d.disposition().name()))
                        .append(", \"kernelCode\": ").append(q(d.kernelCode()))
                        .append(", \"action\": ").append(q(d.action()))
                        .append(" }");
            }
            sb.append("\n      ]\n");
            sb.append("    }");
        }

        sb.append("\n  ],\n");
        sb.append("  \"planned\": [\n");
        boolean first = true;
        for (DeviceModels.Planned p : DeviceModels.planned()) {
            if (!first) {
                sb.append(",\n");
            }
            first = false;
            sb.append("    { \"id\": ").append(q(p.id()))
                    .append(", \"type\": ").append(q(p.type()))
                    .append(", \"model\": ").append(q(p.vendorModel()))
                    .append(", \"missing\": ").append(q(p.missing()))
                    .append(" }");
        }
        sb.append("\n  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    /** JSON 字符串转义（只有引号/反斜杠/控制字符，够用）。 */
    private static String q(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private DeviceModelExport() {
    }
}
