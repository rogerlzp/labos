package com.labos.kernel.device;

import com.labos.kernel.persistence.DbConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * **把一台设备加进"设备池"**（{@code device} 表），带图片。
 *
 * <p>设备池 = 型号池：一行就是"我们支持这台机器"，前端「岛 → 部署」按型号起实例。
 * 加一台设备过去要手写 SQL（还容易漏 {@code capability_id}、{@code icon}、{@code attr}），
 * 这个命令把它做成一步，并且**以型号库为准**——不让人手抄参数。
 *
 * <h3>两种用法</h3>
 * <pre>
 *   # ① 型号库里已有的（推荐）：名称/厂商/类型/能力全部从型号库取
 *   java -cp out:lib/postgresql-42.7.3.jar com.labos.kernel.device.DeviceRegistrar \
 *        --model s100 --image docs/设备/images/s100.svg
 *
 *   # ② 型号库里还没有的：全部显式给（或从 JSON 文件读）
 *   java ... DeviceRegistrar --from my-device.json
 * </pre>
 *
 * <h3>图片怎么处理</h3>
 * <p>图片拷进 {@code webapp/public/devices/<code>.<ext>}，{@code device.icon} 写
 * {@code /devices/<code>.<ext>}（前端直接 {@code <img src>} 就能显示）。
 * 没给图片时**生成一张占位 SVG**（写清型号名 + "示意图"），
 * 目的是让池子里不出现空白格子，同时不假装我们有实物照片。
 *
 * <h3>幂等</h3>
 * <p>按 {@code (tenant_id, code)} upsert：重复执行只更新名称/图片/型号绑定，
 * 不会产生第二行；**不会**动 {@code device_instance}（部署到岛上是前端的事）。
 */
public final class DeviceRegistrar {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(DeviceRegistrar.class);
    /** 兼容原有 System.out.printf 的格式化输出。 */
    private static void printf(String format, Object... args) {
        log.info(String.format(format, args));
    }

    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parseArgs(args);
        if (opt.containsKey("help") || opt.containsKey("h")) {
            usage();
            return;
        }
        if (opt.containsKey("list")) {
            listPool();
            return;
        }

        Spec spec = Spec.from(opt);
        String check = spec.validate();
        if (check != null) {
            log.error("参数不对：" + check);
            log.error("用 --help 看用法。");
            System.exit(2);
        }

        String icon = ensureImage(spec);

        if (opt.containsKey("dry-run")) {
            log.info("-- 干跑，不写库。实际会执行：");
            log.info(sqlFor(spec, icon, capabilityIdOrNull(spec.capability)));
            log.info("-- 图片位置：" + icon);
            return;
        }

        com.labos.kernel.persistence.Tx.inTransaction(DbConfig.fromEnv(), c -> {
            Long capId = capabilityId(spec.capability);
            if (spec.capability != null && capId == null) {
                log.error("能力 " + spec.capability + " 在 capability 表里不存在。");
                log.error("现有能力：");
                for (java.util.Map<String, Object> cap
                        : com.labos.kernel.persistence.Mappers.get(
                                com.labos.kernel.persistence.QueryMapper.class)
                                .selectAllCapabilities()) {
                    log.error("  " + cap.get("code") + "  " + cap.get("name"));
                }
                System.exit(3);
            }

            long id = upsert(spec, icon, capId);
            // **方法集要一起落库**：设备池里点「详情」看的方法，来自 device_method 表，
            // 不是型号库。只写 device 不写 device_method，界面就是"这台设备没有任何方法"
            // （2026-11-02 用户点出来：ASPIN_2K-1 方法为空）。
            int methods = syncMethods(id, capId, spec.deviceModel);
            printf("✅ 已加入设备池：id=%d code=%s 型号=%s 能力=%s%n",
                    id, spec.code, spec.model, spec.capability == null ? "-" : spec.capability);
            if (spec.deviceModel != null) {
                printf("   型号绑定 attr.device_model=%s（设备错误码会按它翻译）%n",
                        spec.deviceModel);
            }
            log.info("   图片：" + icon
                    + (methods > 0 ? "，方法 " + methods + " 条" : ""));
            log.info("   下一步：前端「岛 → 设备池 → 部署」选一个岛起实例，再填 IP。");
            return null;
        });
    }

    // ---------------------------------------------------------------- 数据

    /** 一台设备的全部可填字段。 */
    private static final class Spec {
        String code;
        String name;
        String model;
        String vendor;
        String deviceType = "MISC";
        String capability;
        String color = "#8FB3D9";
        String image;
        String remark;
        /** 型号库 id（s100 / aspin-2k）；有它才能翻译设备错误码。 */
        String deviceModel;

        static Spec from(Map<String, String> opt) throws IOException {
            Spec s = new Spec();
            String from = opt.get("from");
            if (from != null && !from.isBlank()) {
                s.readJson(Path.of(from));
            }
            // 命令行覆盖 JSON
            String modelId = opt.get("model");
            if (modelId != null && !modelId.isBlank()) {
                s.applyModel(DeviceModels.of(modelId));
                s.deviceModel = modelId;
                // 没给 code 就按型号 id 推：s100 → S100，aspin-2k → ASPIN_2K
                if (s.code == null || s.code.isBlank()) {
                    s.code = modelId.toUpperCase(Locale.ROOT).replace('-', '_');
                }
            }
            override(s, "code", opt.get("code"));
            override(s, "name", opt.get("name"));
            override(s, "vendor", opt.get("vendor"));
            override(s, "device-type", opt.get("device-type"));
            override(s, "capability", opt.get("capability"));
            override(s, "color", opt.get("color"));
            override(s, "remark", opt.get("remark"));
            override(s, "image", opt.get("image"));
            return s;
        }

        /** 从型号库填默认值 —— 这是"不手抄参数"的关键。 */
        void applyModel(DeviceModel m) {
            if (m == null) {
                return;
            }
            model = m.model;
            // 手册没标厂商时，型号库里写的是"（手册未标注厂商）"——那是文档用的，
            // 别让它跑进设备池的名称里（前端会显示成"（手册未标注厂商） S100"）。
            boolean noVendor = m.vendor == null || m.vendor.contains("未标注");
            vendor = noVendor ? "" : m.vendor;
            String typeCn = switch (m.type) {
                case "centrifuge" -> "离心机";
                case "sealer" -> "封膜机";
                default -> "";
            };
            name = name != null ? name
                    : (vendor.isEmpty() ? m.model + " " + typeCn
                                        : vendor + " " + m.model);
            name = name.trim();
            deviceType = "centrifuge".equals(m.type) ? "Centrifugation"
                    : "sealer".equals(m.type) ? "Sealing"
                    : deviceType;
            capability = capability != null ? capability
                    : switch (m.type) {
                        case "centrifuge" -> "CENTRIFUGE";
                        case "sealer" -> "SEAL";
                        case "incubator" -> "INCUBATE";
                        case "reader" -> "READ_PLATE";
                        case "liquid_handler" -> "ALIQUOT";
                        default -> null;
                    };
            if (m.type.equals("centrifuge")) {
                color = "#FFD9A0";
            } else if (m.type.equals("sealer")) {
                color = "#B7E1CD";
            }
            remark = "型号库 " + m.id + "（" + m.evidence + "）：" + m.protocol
                    + (m.note == null ? "" : "\n注意：" + m.note);
        }

        void readJson(Path p) throws IOException {
            String s = Files.readString(p, StandardCharsets.UTF_8);
            code = str(s, "code", code);
            name = str(s, "name", name);
            model = str(s, "model", model);
            vendor = str(s, "vendor", vendor);
            deviceType = str(s, "deviceType", str(s, "device_type", deviceType));
            capability = str(s, "capability", capability);
            color = str(s, "color", color);
            image = str(s, "image", image);
            remark = str(s, "remark", remark);
            deviceModel = str(s, "deviceModel", str(s, "device_model", deviceModel));
            String mId = deviceModel;
            if (mId != null && DeviceModels.of(mId) != null) {
                applyModel(DeviceModels.of(mId));
            }
        }

        String validate() {
            if (code == null || code.isBlank()) {
                return "缺 --code（设备池编号，唯一）";
            }
            if (!code.matches("[A-Za-z0-9_.-]{2,40}")) {
                return "code 只能用字母数字 _ . -（2–40 位）：" + code;
            }
            if (name == null || name.isBlank()) {
                return "缺 --name（设备名，会显示在池子里）";
            }
            if (color != null && !HEX_COLOR.matcher(color).matches()) {
                return "color 要写成 #RRGGBB：" + color;
            }
            if (deviceModel != null && DeviceModels.of(deviceModel) == null) {
                return "deviceModel=" + deviceModel + " 不在型号库里（见 DeviceErrorSql --list）";
            }
            return null;
        }
    }

    // ---------------------------------------------------------------- 图片

    /**
     * 把图片放进前端静态目录，返回要写进 {@code device.icon} 的路径。
     *
     * <p>没给图片就生成占位 SVG —— 池子里那个格子不能空着，
     * 但也**不假装**我们有实物照片（图上写着"示意图"）。
     */
    private static String ensureImage(Spec spec) throws IOException {
        Path dir = webappDevicesDir();
        Files.createDirectories(dir);

        if (spec.image != null && !spec.image.isBlank()) {
            Path src = Path.of(spec.image);
            if (!Files.isRegularFile(src)) {
                throw new IOException("图片不存在：" + src.toAbsolutePath());
            }
            String ext = extOf(src.getFileName().toString());
            String fileName = spec.code + ext;
            Files.copy(src, dir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
            return "/devices/" + fileName;
        }

        String fileName = spec.code + ".svg";
        Path svg = dir.resolve(fileName);
        if (!Files.exists(svg)) {
            Files.writeString(svg, placeholderSvg(spec.name, spec.vendor, spec.model),
                    StandardCharsets.UTF_8);
        }
        return "/devices/" + fileName;
    }

    private static String placeholderSvg(String name, String vendor, String model) {
        String n = esc(name == null ? "" : name);
        String v = esc((vendor == null ? "" : vendor) + " " + (model == null ? "" : model));
        return """
                <svg xmlns="http://www.w3.org/2000/svg" width="320" height="200" viewBox="0 0 320 200">
                  <rect width="320" height="200" rx="14" fill="#F4F6F8" stroke="#C9D2DB" stroke-width="2"/>
                  <rect x="30" y="34" width="260" height="92" rx="8" fill="#E3EAF1" stroke="#AFC0CF"/>
                  <text x="160" y="86" text-anchor="middle" font-family="sans-serif" font-size="18"
                        fill="#2F3B45">%s</text>
                  <text x="160" y="112" text-anchor="middle" font-family="sans-serif" font-size="12"
                        fill="#61707D">%s</text>
                  <text x="160" y="160" text-anchor="middle" font-family="sans-serif" font-size="11"
                        fill="#9AA7B2">示意图（可在设备池里替换为实物照片）</text>
                </svg>
                """.formatted(n, v);
    }

    private static String extOf(String fileName) {
        int i = fileName.lastIndexOf('.');
        String ext = i >= 0 ? fileName.substring(i).toLowerCase(Locale.ROOT) : ".png";
        return switch (ext) {
            case ".png", ".jpg", ".jpeg", ".webp", ".svg" -> ext;
            default -> ".png";
        };
    }

    /** 仓库根的 {@code webapp/public/devices}；从 cwd 向上找。 */
    private static Path webappDevicesDir() {
        Path p = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && p != null; i++, p = p.getParent()) {
            if (Files.isDirectory(p.resolve("webapp/public"))) {
                return p.resolve("webapp/public/devices");
            }
        }
        // 找不到就放到当前目录（部署机上可能没有 webapp，不阻塞加设备）
        return Path.of("device-images");
    }

    // ---------------------------------------------------------------- 写库

    private static long upsert(Spec s, String icon, Long capId) {
        return com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.DeviceMapper.class)
                .upsertDevicePool(tenantId(), s.code, s.name, s.model, s.vendor,
                        s.deviceType, capId, icon, s.color, s.remark, attrJson(s));
    }

    /**
     * {@code attr} 里放两样东西：
     * <ul>
     *   <li>{@code device_model} —— 型号库 id，**中控按它翻译设备错误码**，
     *       没有它设备码会原样透传（退化成"未登记码 → 转人工"）；</li>
     *   <li>{@code device_type} 等来源信息，方便以后排查"这台是谁加的"。</li>
     * </ul>
     */
    private static String attrJson(Spec s) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"origin\":\"device-registrar\"");
        if (s.deviceModel != null) {
            sb.append(",\"device_model\":\"").append(js(s.deviceModel)).append('"');
        }
        sb.append(",\"type\":\"").append(js(s.deviceType == null ? "" : s.deviceType)).append('"');
        sb.append('}');
        return sb.toString();
    }

    private static Long capabilityId(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.QueryMapper.class)
                .selectCapabilityIdGlobal(code.toUpperCase(Locale.ROOT));
    }

    private static Long capabilityIdOrNull(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            return capabilityId(code);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 把型号库里的方法同步进 {@code device_method}（界面「详情」读的就是这张表）。
     *
     * <p>不去删"型号库里已经没有"的现有方法：存量方法可能有历史流程在引用，
     * 删掉会让那些流程点开就报"方法不存在"。只增改。
     *
     * @return 写了多少条
     */
    private static int syncMethods(long deviceId, Long capId, String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return 0;
        }
        DeviceModel m = DeviceModels.of(modelId);
        if (m == null) {
            return 0;
        }
        // 每行一个方法：label / estimate / timeout 的计算留在 Java 侧（同原 addBatch 循环）。
        java.util.List<java.util.Map<String, Object>> items = new java.util.ArrayList<>();
        for (DeviceModel.Method mm : m.methods()) {
            // 手册没写的指令（如 GetStatus）标出来：接真机时可能根本不存在
            String alias = mm.aliasOf();
            String label = mm.documented()
                    ? alias : alias + "（手册未提供，需现场确认）";
            int estimate = mm.delayMs() > 0 ? (int) Math.max(1, mm.delayMs() / 1000) : 1;
            int timeout = (int) Math.max(estimate + 2, m.actionTimeoutMs / 1000);
            java.util.Map<String, Object> it = new java.util.LinkedHashMap<>();
            it.put("method", alias);
            it.put("label", label);
            it.put("capabilityId", capId);
            it.put("paramsJson", paramsJson(mm));
            it.put("estimate", estimate);
            it.put("timeout", timeout);
            items.add(it);
        }
        if (items.isEmpty()) {
            return 0;
        }
        com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.DeviceMapper.class)
                .upsertDeviceMethods(tenantId(), deviceId, items);
        return items.size();
    }

    /** 参数说明进 param_schema.params（界面「详情」里能看到"这条指令带什么参数"）。 */
    private static String paramsJson(DeviceModel.Method mm) {
        if (mm.args() == null || mm.args().isBlank()) {
            return "{\"params\":[]}";
        }
        return "{\"params\":[{\"seq\":1,\"name\":\"args\",\"desc\":\""
                + js(mm.args()) + "\",\"type\":\"string\",\"required\":false}]}";
    }

    private static String sqlFor(Spec s, String icon, Long capId) {
        return "INSERT INTO device (...) VALUES ('" + s.code + "', ...);  -- capability_id="
                + capId + ", icon=" + icon + ", attr=" + attrJson(s);
    }

    private static long tenantId() {
        String t = System.getenv("LABOS_TENANT");
        return t == null || t.isBlank() ? 1L : Long.parseLong(t);
    }

    // ---------------------------------------------------------------- 查询

    private static void listPool() {
        printf("%-16s %-28s %-14s %-10s %-12s %s%n",
                "code", "名称", "类型", "能力", "型号库", "已部署");
        for (java.util.Map<String, Object> row : com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.DeviceMapper.class)
                .selectDevicePoolList(tenantId())) {
            Object deployed = row.get("deployed");
            printf("%-16s %-28s %-14s %-10s %-12s %d%n",
                    strOf(row.get("code")), cut(strOf(row.get("name")), 26),
                    nvl(strOf(row.get("deviceType"))), nvl(strOf(row.get("cap"))),
                    nvl(strOf(row.get("modelId"))),
                    deployed instanceof Number num ? num.intValue() : 0);
        }
        log.info("");
        log.info("型号库（能对接真机的）：");
        for (DeviceModel m : DeviceModels.all()) {
            printf("  %-14s %s %s（%s）%n", m.id, m.vendor, m.model, m.evidence);
        }
    }

    // ---------------------------------------------------------------- 工具

    private static void usage() {
        log.info("""
                把一台设备加入设备池（device 表），带图片。
                
                用法：
                  # 型号库里已有的（推荐：名称/厂商/类型/能力都从型号库来）
                  DeviceRegistrar --model s100 --image docs/设备/images/s100.svg
                
                  # 从 JSON 描述文件加（给外部/未来用户）
                  DeviceRegistrar --from my-device.json
                
                  # 查现状
                  DeviceRegistrar --list
                
                参数：
                  --model <id>         型号库 id（s100 / aspin-2k / …），会自动填其余字段
                  --code <code>        设备池编号（唯一，默认用 JSON 或 --name 推导）
                  --name <名称>
                  --vendor <厂商>
                  --device-type <类型> Centrifugation / Sealing / Incubators / MISC …
                  --capability <CODE>  SEAL / CENTRIFUGE / INCUBATE …（查 capability 表）
                  --color <#RRGGBB>
                  --image <path>       图片；不给就生成占位 SVG
                  --remark <备注>
                  --tenant <id>        默认 1（也可用 LABOS_TENANT）
                  --dry-run            只打印，不写库
                  --list               列出设备池现状
                
                JSON 描述文件字段：
                  { "code":"S100", "name":"S100 封膜机", "model":"S100", "vendor":"…",
                    "deviceType":"Sealing", "capability":"SEAL", "deviceModel":"s100",
                    "image":"docs/设备/images/s100.svg", "color":"#B7E1CD", "remark":"…" }
                """);
    }

    private static void override(Spec s, String key, String v) {
        if (v == null) {
            return;
        }
        switch (key) {
            case "code" -> s.code = v;
            case "name" -> s.name = v;
            case "vendor" -> s.vendor = v;
            case "device-type" -> s.deviceType = v;
            case "capability" -> s.capability = v;
            case "color" -> s.color = v;
            case "remark" -> s.remark = v;
            case "image" -> s.image = v;
            default -> { }
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> d = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) {
                continue;
            }
            String key = a.substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                d.put(key, args[++i]);
            } else {
                d.put(key, "");
            }
        }
        return d;
    }

    /** 极简 JSON 取字符串字段（够用即可，不引依赖）。 */
    private static String str(String json, String key, String dft) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(json);
        if (!m.find()) {
            return dft;
        }
        return m.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String js(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String nvl(String s) {
        return s == null ? "-" : s;
    }

    /** Map 行里的值统一转字符串（null 保持 null，走 nvl/cut 的原有判空）。 */
    private static String strOf(Object o) {
        return o == null ? null : o.toString();
    }

    private static String cut(String s, int n) {
        if (s == null) {
            return "-";
        }
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private DeviceRegistrar() {
    }
}
