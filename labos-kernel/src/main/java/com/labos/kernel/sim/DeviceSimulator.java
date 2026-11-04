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
 *   --fail 5              每 5 条**动作类**命令失败一次
 *   --fail-method Seal    只在 Seal 上失败（配合 --fail / --fail-code）
 *   --fail-from 3         第 3 条命令起才开始失败（前两条正常，测"跑一半坏了"）
 *   --fail-code 0x8000010C 失败时回的错误码（型号库里有登记的会被解释成中文+建议）
 *   --offline             不监听端口（测中控连不上设备）
 *   --model s100          按**具体型号**模拟（真实指令名 + 专属错误码 + 时序约束）
 *   --list-models         列出已登记型号 / 待适配清单 / 错误码映射
 *   --list-profiles       列出所有设备类型
 * </pre>
 */
public final class DeviceSimulator {

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

        // --list-* 是单参数，先单独处理（下面的循环是成对解析）
        for (String a : args) {
            if ("--list-models".equals(a)) {
                printModels();
                return;
            }
            if ("--list-profiles".equals(a)) {
                System.out.println("可用设备类型（--profile）：");
                DeviceProfile.knownNames().stream().sorted()
                        .forEach(n -> System.out.println("  " + n));
                System.out.println("  generic（不校验，调试用）");
                return;
            }
        }

        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--host" -> host = args[++i];
                case "--device" -> device = args[++i];
                case "--code" -> code = args[++i];
                case "--cap" -> cap = args[++i];
                case "--delay" -> delay = Long.parseLong(args[++i]);
                case "--fail" -> failEvery = Integer.parseInt(args[++i]);
                case "--fail-code" -> failCode = args[++i];
                case "--fail-method" -> failMethod = args[++i];
                case "--fail-from" -> failFrom = Integer.parseInt(args[++i]);
                case "--long" -> longTask = true;
                case "--profile" -> profile = args[++i];
                case "--model" -> modelId = args[++i];
                case "--offline" -> offline = true;
                default -> { }
            }
        }
        if (port <= 0) {
            System.err.println("必须指定 --port，例如 --port 19401");
            System.exit(2);
        }

        DeviceModel model = DeviceModels.of(modelId);
        if (modelId != null && model == null) {
            System.err.println("未知型号 " + modelId + "，可用：" + DeviceModels.knownIds());
            System.exit(2);
        }
        // 有型号时，类型从型号推出来（省得两处写不一致）
        if (model != null) {
            profile = model.type;
            // 型号库里没有的码，直接说清楚，别等到跑起来才发现打错了
            if (failEvery > 0 && !model.errorTable().isEmpty()
                    && !model.errorTable().containsKey(failCode)
                    && !failCode.startsWith("EC-")) {
                System.err.println("提示：型号 " + model.id + " 的错误码表里没有 "
                        + failCode + "，可用：" + model.errorTable().keySet());
            }
        }

        DeviceCore core = new DeviceCore(port, device, code, cap,
                delay, longTask, failEvery, failCode, offline, profile,
                model, failMethod, failFrom);

        core.setListener(new DeviceCore.Listener() {
            @Override
            public void onLog(String line) {
                // CLI 的日志已经由 DeviceCore 打到 stdout，这里不重复
            }
        });

        core.startServer(host, port);

        final String tag = code;
        Runtime.getRuntime().addShutdownHook(new Thread(() ->
                System.out.println("[" + tag + "] 模拟器退出，共收到 "
                        + core.snapshot().get("received") + " 条命令")));

        // 常驻：不能用 Thread.currentThread().join()——那是对自己 join，
        // 行为不可靠（实测进程会直接退出，日志只剩一句"模拟器退出"）。
        // 用 CountDownLatch 明确阻塞，只有 JVM 关闭时才结束。
        new java.util.concurrent.CountDownLatch(1).await();
    }

    /** {@code --list-models}：已适配 / 待适配 / 错误码映射一次看全。 */
    private static void printModels() {
        System.out.println("已适配的具体型号（--model）：依据 MANUAL 的才能对接真机");
        System.out.println("─".repeat(96));
        System.out.printf("  %-22s %-10s %-24s %-8s %s%n",
                "型号 id", "类型", "厂商 型号", "依据", "指令/错误码");
        for (DeviceModel m : DeviceModels.all()) {
            System.out.printf("  %-22s %-10s %-24s %-8s %d 条 / %d 个%n",
                    m.id, m.type, m.vendor + " " + m.model, m.evidence,
                    m.methods().size(), m.errorTable().size());
            if (m.note != null && !m.note.isBlank()) {
                System.out.println("      注意：" + m.note);
            }
        }

        System.out.println();
        System.out.println("待适配型号（手册还没到，不能对接真机）：");
        System.out.println("─".repeat(96));
        for (DeviceModels.Planned p : DeviceModels.planned()) {
            System.out.printf("  %-24s %-10s %-28s 缺：%s%n",
                    p.id(), p.type(), p.vendorModel(), p.missing());
        }

        System.out.println();
        System.out.println("错误码映射（设备码 → 中控码 → 处置）："
                + "完整表格用 com.labos.kernel.device.DeviceErrorSql --list");
    }

    private DeviceSimulator() {
    }
}
