package com.labos.kernel.sim;

import com.labos.kernel.device.DeviceModel;
import com.labos.kernel.device.DeviceModels;

/**
 * 模拟器的**命令行入口**（无界面，自动化测试用）。
 *
 * <p>业务逻辑都在 {@link DeviceCore}；这里只做参数解析 + 起监听 + 等退出。
 *
 * <p><b>要带界面的模拟器</b>用 {@link SimulatorApp}——每台设备一个桌面窗口，
 * 里面有该设备专属的操作面板、IP/端口设置、日志、连接状态。
 *
 * <p>用法：
 * <pre>
 *   java -cp out com.labos.kernel.sim.DeviceSimulator \
 *        --port 19500 --device 206 --code ID0000 --profile liquid_handler
 *
 *   --host 0.0.0.0        绑定地址（默认所有网卡；写 127.0.0.1 只本机）
 *   --delay 0             模拟耗时；0/负数 = 用该型号/类型的默认值
 *   --long                长任务模式（先回"运行中"再回"完成"）
 *   --rand-delay 30000:300000[:SEED]
 *                         方法耗时在 [MIN,MAX] 毫秒内**随机**（真机时序）；
 *                         给 SEED 则可复现。用于测「设备还在执行时来暂停」
 *   --fail 5              每 5 条**动作类**命令失败一次
 *   --fail-method Seal    只在 Seal 上失败（配合 --fail / --fail-code）
 *   --fail-from 3         第 3 条命令起才开始失败（前两条正常，测"跑一半坏了"）
 *   --fail-code 0x8000010C 失败时回的错误码（型号库里有登记的会被解释成中文+建议）
 *   --offline             不监听端口（测中控连不上设备）
 *   --model s100          按**具体型号**模拟（真实指令名 + 专属错误码 + 时序约束）
 *   --list-models         列出已登记型号 / 待适配清单 / 错误码映射
 *   --list-profiles       列出所有设备类型
 *
 *   --spontaneous "Start=waiting_interaction@2000;Continue=waiting_interaction@2000;Continue=process_done@2000"
 *                         命令成功后，设备**主动**上报（没有 message_id 的那种帧）。
 *                         格式 method=eventType@delayMs，多条用 ; 分隔；
 *                         同一个 method 可写多条 ⇒ 按写的顺序（FIFO）依次上报；
 *                         delayMs 省略 = 立即上报
 * </pre>
 */
public final class DeviceSimulator {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(DeviceSimulator.class);
    /** 兼容原有 System.out.printf 的格式化输出。 */
    private static void printf(String format, Object... args) {
        log.info(String.format(format, args));
    }

    public static void main(String[] args) throws Exception {
        int port = 0;
        String device = "0";
        String code = "-";
        String cap = "-";
        String host = "0.0.0.0";
        long delay = -1;
        int failEvery = 0;
        String failCode = "EC-05-0001";
        String failMethod = null;
        int failFrom = 0;
        boolean longTask = false;
        boolean offline = false;
        String profile = "generic";
        String modelId = null;
        // --rand-delay MIN:MAX[:SEED] —— 方法耗时在区间内随机（毫秒）。
        // 为什么需要：固定耗时（默认几百毫秒）测不出「设备还在执行时来暂停」
        // —— 命令早回来了，暂停信号来不及在动作中间到达。
        long randMin = -1;
        long randMax = -1;
        Long randSeed = null;
        String methodsArg = null;
        // --spontaneous "method=eventType@delayMs;..."：命令成功后设备主动上报的脚本。
        // 为什么要有：真设备不是只回命令 —— 提取仪会自己走到交换位然后喊一声
        // "我等着呢，给我继续信号"。没有这条通道，中控的等待节点永远等不到。
        String spontaneousArg = null;
        // --registry URL：从**中控的设备清单**认领自己（设备ID / 编号 / 能力 / 画像）。
        String registryUrl = null;
        // 显式给了的参数优先于清单（人工显式指定是"我就要这个"），
        // 但清单值与显式值不一致时要提醒 —— 那正是要消灭的"两份事实"。
        java.util.Set<String> explicit = new java.util.HashSet<>();

        // --list-* 是单参数，先单独处理（下面的循环是成对解析）
        for (String a : args) {
            if ("--list-models".equals(a)) {
                printModels();
                return;
            }
            if ("--list-profiles".equals(a)) {
                log.info("可用设备类型（--profile）：");
                DeviceProfile.knownNames().stream().sorted()
                        .forEach(n -> log.info("  " + n));
                log.info("  generic（不校验，调试用）");
                return;
            }
        }

        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--host" -> host = args[++i];
                case "--device" -> { device = args[++i]; explicit.add("device"); }
                case "--code" -> { code = args[++i]; explicit.add("code"); }
                case "--cap" -> { cap = args[++i]; explicit.add("cap"); }
                case "--delay" -> delay = Long.parseLong(args[++i]);
                case "--fail" -> failEvery = Integer.parseInt(args[++i]);
                case "--fail-code" -> failCode = args[++i];
                case "--fail-method" -> failMethod = args[++i];
                case "--fail-from" -> failFrom = Integer.parseInt(args[++i]);
                case "--long" -> longTask = true;
                case "--profile" -> { profile = args[++i]; explicit.add("profile"); }
                case "--model" -> modelId = args[++i];
                // --methods "A,B,C"：这台设备**实际**有的方法（来自中控的 device_method 表）。
                // 画像是通用机型的方法集，可能比这台设备实际支持的少
                // （读板机画像里没有 ReadPlate，而这台读板机有）⇒ 不传就会把合法指令误拒。
                case "--methods" -> methodsArg = args[++i];
                case "--spontaneous" -> spontaneousArg = args[++i];
                case "--registry" -> registryUrl = args[++i];
                case "--offline" -> offline = true;
                case "--rand-delay" -> {
                    // 格式 MIN:MAX[:SEED]，单位毫秒
                    String spec = args[++i];
                    String[] parts = spec.split(":");
                    if (parts.length < 2) {
                        log.error("--rand-delay 需要 MIN:MAX 格式（毫秒），例如 --rand-delay 30000:300000");
                        System.exit(2);
                    }
                    try {
                        randMin = Long.parseLong(parts[0].trim());
                        randMax = Long.parseLong(parts[1].trim());
                        if (parts.length >= 3 && !parts[2].isBlank()) {
                            randSeed = Long.parseLong(parts[2].trim());
                        }
                    } catch (NumberFormatException e) {
                        log.error("--rand-delay 参数不是数字：" + spec);
                        System.exit(2);
                    }
                }
                default -> { }
            }
        }
        if (port <= 0) {
            log.error("必须指定 --port，例如 --port 19401");
            System.exit(2);
        }

        // ---- 从中控的设备清单认领自己（三端口径统一的第一步）----
        // 手工传的 --device/--code/--cap/--profile 与中控表里的值是**同一份事实**，
        // 手工传就多了一份可能对不上的副本（设备改了 IP、换了能力就悄悄错位）。
        // 清单读不到时不致命（工位上中控可能还没起），但会警告。
        // 清单里带的"该型号登记的方法名"：与画像方法集取并集（DeviceCore.withExtraMethods）。
        // 这样新设备只要在库里登记 device_method，模拟器不用改代码就能认它的方法。
        java.util.List<String> registryMethods = java.util.List.of();
        if (registryUrl != null) {
            try {
                var items = DeviceRegistry.fetch(registryUrl);
                var me = DeviceRegistry.match(items, host, port);
                if (me == null) {
                    log.warn("[模拟器] 中控清单里没有端口 " + port + " 对应的设备"
                            + "（" + registryUrl + "）；继续用命令行参数。"
                            + "如果这台设备已在中控登记，请检查端口是否一致。");
                } else {
                    log.info("[模拟器] 已从中控认领设备：" + me.code()
                            + "（设备ID " + me.deviceId() + " @ " + me.ip() + ":" + me.port() + "）");
                    if (explicit.contains("device")) {
                        warnIfDiffers("设备ID", device, String.valueOf(me.deviceId()));
                    } else {
                        device = String.valueOf(me.deviceId());
                    }
                    if (explicit.contains("code")) {
                        warnIfDiffers("设备编号", code, me.code());
                    } else {
                        code = me.code();
                    }
                    if (explicit.contains("cap")) {
                        warnIfDiffers("设备能力", cap, me.capability());
                    } else {
                        cap = me.capability();
                    }
                    if (explicit.contains("profile")) {
                        warnIfDiffers("设备画像", profile, me.simProfile());
                    } else if (!me.simProfile().isBlank() && !"generic".equals(me.simProfile())) {
                        profile = me.simProfile();
                    }
                    if (modelId == null && !me.modelId().isBlank()) {
                        modelId = me.modelId();
                    }
                    registryMethods = me.methods();
                }
            } catch (Exception e) {
                log.warn("[模拟器] 读中控设备清单失败（" + e.getMessage()
                        + "）；继续用命令行参数。");
            }
        }

        DeviceModel model = DeviceModels.of(modelId);
        if (modelId != null && model == null) {
            // 不再退出：型号/协议/方法都是**配置**（device / device_method 表），
            // 代码里的 DeviceModels 只是兜底。现场新登记一台设备不该因为
            // "代码里没有这个型号"就起不来 —— 方法集从清单带（registryMethods），
            // 参数边界按画像校验，行为与 generic 兜底一致。
            log.warn("[模拟器] 型号 " + modelId + " 不在静态型号库（协议与方法以库为准），"
                    + "按清单给的方法集 + 画像参数边界运行；静态库已有："
                    + DeviceModels.knownIds());
        }
        // 有型号时，类型从型号推出来（省得两处写不一致）
        if (model != null) {
            profile = model.type;
            // 型号库里没有的码，直接说清楚，别等到跑起来才发现打错了
            if (failEvery > 0 && !model.errorTable().isEmpty()
                    && !model.errorTable().containsKey(failCode)
                    && !failCode.startsWith("EC-")) {
                log.error("提示：型号 " + model.id + " 的错误码表里没有 "
                        + failCode + "，可用：" + model.errorTable().keySet());
            }
        }

        DeviceCore core = new DeviceCore(port, device, code, cap,
                delay, longTask, failEvery, failCode, offline, profile,
                model, failMethod, failFrom, randMin, randMax, randSeed);
        if (methodsArg != null && !methodsArg.isBlank()) {
            core.withExtraMethods(java.util.Arrays.asList(methodsArg.split(",")));
            log.info("[模拟器] 设备方法表：" + methodsArg);
        }
        if (!registryMethods.isEmpty()) {
            // 清单带过来的型号方法（device_method）。命令行 --methods 仍优先叠加，
            // 两边都保留：现场手工补的方法不该因为库里有登记就被丢掉。
            core.withExtraMethods(registryMethods);
            log.info("[模拟器] 型号方法（中控清单：" + modelId + "）共 "
                    + registryMethods.size() + " 条：" + registryMethods);
        }
        if (spontaneousArg != null && !spontaneousArg.isBlank()) {
            // 解析在 DeviceCore 里（脚本的"形状"归它管），CLI 只负责传进去。
            // 坏条目由解析器报到 stderr 并跳过 —— 模拟器照常起，别让一条手写错误停机。
            java.util.Map<String, java.util.List<DeviceCore.SpontaneousEvent>> script =
                    DeviceCore.parseSpontaneousScript(spontaneousArg);
            core.withSpontaneousScript(script);
            log.info("[模拟器] 自发上报脚本：" + spontaneousArg
                    + "（" + script.size() + " 个方法，共 " + countEvents(script) + " 条）");
            if (code == null || code.isBlank() || "-".equals(code)) {
                log.warn("[模拟器] 自发上报要用**设备编号**认领事件（--code），当前没给"
                        + "⇒ 自发帧的 equipment_id 退回用设备实例 id " + device);
            }
        }
        if (randMax > 0) {
            log.info("[模拟器] 方法耗时随机区间 "
                    + randMin + "–" + randMax + "ms"
                    + (randSeed == null ? "（每次不同）" : "（种子 " + randSeed + "，可复现）"));
        }

        core.setListener(new DeviceCore.Listener() {
            @Override
            public void onLog(String line) {
                // CLI 的日志已经由 DeviceCore 打到 stdout，这里不重复
            }
        });

        core.startServer(host, port);

        final String tag = code;
        Runtime.getRuntime().addShutdownHook(new Thread(() ->
                log.info("[" + tag + "] 模拟器退出，共收到 "
                        + core.snapshot().get("received") + " 条命令")));

        // 常驻：不能用 Thread.currentThread().join()——那是对自己 join，
        // 行为不可靠（实测进程会直接退出，日志只剩一句"模拟器退出"）。
        // 用 CountDownLatch 明确阻塞，只有 JVM 关闭时才结束。
        new java.util.concurrent.CountDownLatch(1).await();
    }

    /** 脚本里一共写了多少条上报（只用于启动那行日志）。 */
    private static int countEvents(
            java.util.Map<String, java.util.List<DeviceCore.SpontaneousEvent>> script) {
        int n = 0;
        for (var v : script.values()) {
            n += v == null ? 0 : v.size();
        }
        return n;
    }

    /**
     * 命令行显式给的值与中控清单里的值不一致时提醒一句。
     *
     * <p>不直接改（显式参数是"我就要这个"），但要说出来 ——
     * 这种不一致正是"模拟器说在线、中控说离线"这类问题的源头。
     */
    private static void warnIfDiffers(String what, String given, String fromRegistry) {
        if (fromRegistry == null || fromRegistry.isBlank()) {
            return;
        }
        if (!fromRegistry.equals(given)) {
            log.warn("[模拟器] " + what + "命令行给的是 " + given
                    + "，中控清单里是 " + fromRegistry + "（以清单为准才不会和中控对不上）");
        }
    }

    /** {@code --list-models}：已适配 / 待适配 / 错误码映射一次看全。 */
    private static void printModels() {
        log.info("已适配的具体型号（--model）：依据 MANUAL 的才能对接真机");
        log.info("─".repeat(96));
        printf("  %-22s %-10s %-24s %-8s %s%n",
                "型号 id", "类型", "厂商 型号", "依据", "指令/错误码");
        for (DeviceModel m : DeviceModels.all()) {
            printf("  %-22s %-10s %-24s %-8s %d 条 / %d 个%n",
                    m.id, m.type, m.vendor + " " + m.model, m.evidence,
                    m.methods().size(), m.errorTable().size());
            if (m.note != null && !m.note.isBlank()) {
                log.info("      注意：" + m.note);
            }
        }

        log.info("");
        log.info("待适配型号（手册还没到，不能对接真机）：");
        log.info("─".repeat(96));
        for (DeviceModels.Planned p : DeviceModels.planned()) {
            printf("  %-24s %-10s %-28s 缺：%s%n",
                    p.id(), p.type(), p.vendorModel(), p.missing());
        }

        log.info("");
        log.info("错误码映射（设备码 → 中控码 → 处置）："
                + "完整表格用 com.labos.kernel.device.DeviceErrorSql --list");
    }

    private DeviceSimulator() {
    }
}
