package com.labos.kernel.sim;

import com.labos.kernel.device.DeviceModel;
import com.labos.kernel.device.DeviceModels;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 模拟器行为自检：**起真的 socket**，按真实协议发命令、看回包。
 *
 * <p>为什么不用单元测试直接调方法：这里要验证的是整条链路——
 * 帧解析、型号校验、状态机、错误码、回包格式。
 * 直接调 {@code S100Behavior.handle} 会把最有价值的部分（分帧与帧结构）绕过去。
 *
 * <p>两条主线：
 * <ul>
 *   <li><b>S100</b>：托盘不在原点不能封膜（{@code 0x8000001A}）、板型只有 1–8、
 *       故障锁存要 ClearFault 才解除；</li>
 *   <li><b>ASpin 2K</b>：没建模块实例就不能发指令、没 Init 不能动作、
 *       两扇门、门开着不能离心、回包用**手册真实指令名**。</li>
 * </ul>
 */
public final class SimulatorBehaviorSelfTest {

    private static final String SEP = "#!LABSEP!#";
    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        testS100();
        testAspin();
        testConcurrentCommands();

        System.out.println();
        System.out.printf("通过 %d, 失败 %d%n", pass, fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- S100

    private static void testS100() throws Exception {
        section("S100 封膜机：托盘原点 / 板型 / 故障锁存");
        DeviceModel model = DeviceModels.of("s100");
        DeviceCore core = new DeviceCore(0, "501", "SEAL1", "SEAL", 1,
                false, 0, "EC-05-0001", false, "sealer", model, null, 0);
        core.startServer("127.0.0.1", 0);
        try (Client c = new Client(core.port(), "501")) {
            // 1) 原点封膜：成功
            Frame r = c.call("m1", "Seal", null);
            eq("原点封膜成功", 2, r.status);
            ok("回包用真实指令名 Seal", r.raw.contains("\"method\":\"Seal\""));

            // 2) 托盘挪走再封膜 → 0x8000001A（手册里明确写了的）
            r = c.call("m2", "MoveTray", "\"value\":10000");
            eq("托盘移动成功", 2, r.status);
            r = c.call("m3", "Seal", null);
            eq("托盘不在原点 → 拒绝", 3, r.status);
            eq("托盘不在原点 → 0x8000001A", "0x8000001A", r.errorCode());
            ok("错误说明是手册原文含义", r.raw.contains("托盘不在原点"));
            ok("错误里带现场建议", r.raw.contains("MoveTray(0)"));

            // 3) 回原点后恢复
            eq("回原点成功", 2, c.call("m4", "MoveTray", "\"value\":0").status);
            eq("回原点后封膜成功", 2, c.call("m5", "Seal", null).status);

            // 4) 板型范围（1–8）
            r = c.call("m6", "SealWithType", "\"plateType\":9");
            eq("板型 9 → 拒绝", 3, r.status);
            eq("板型越界 → 0x80000003", "0x80000003", r.errorCode());
            eq("板型 3 → 成功", 2, c.call("m7", "SealWithType", "\"plateType\":3").status);
            r = c.call("m8", "GetPlateType", null);
            ok("查板型返回 3", r.raw.contains("\"plateType\":3"));

            // 5) 型号不认识的指令 → 中控自己的码（手册里没有这条，适配层先拦）
            r = c.call("m9", "FooBar", null);
            eq("未知指令被拒", 3, r.status);
            eq("未知指令 → EC-06-0004", "EC-06-0004", r.errorCode());

            // 6) 故障锁存：报无纸 → 换纸（清故障）之前一直报
            core.injectNextFailure("0x8000000C");
            r = c.call("m10", "Seal", null);
            eq("注入无纸 → 失败", 3, r.status);
            eq("无纸码原样回（不在模拟器侧翻译）", "0x8000000C", r.errorCode());
            ok("无纸带手册建议（从动轮）", r.raw.contains("从动轮"));
            r = c.call("m11", "Seal", null);
            eq("未清故障前一直失败（真机是锁存的）", 3, r.status);
            eq("清故障成功", 2, c.call("m12", "ClearFault", null).status);
            eq("清故障后封膜成功", 2, c.call("m13", "Seal", null).status);
        } finally {
            core.stopServer();
        }
    }

    // ---------------------------------------------------------------- ASpin

    private static void testAspin() throws Exception {
        section("ASpin 2K：模块实例 / 初始化 / 双门 / 参数");
        DeviceModel model = DeviceModels.of("aspin-2k");
        DeviceCore core = new DeviceCore(0, "601", "CENT1", "CENTRIFUGE", 1,
                false, 0, "EC-05-0001", false, "centrifuge", model, null, 0);
        core.startServer("127.0.0.1", 0);
        try (Client c = new Client(core.port(), "601")) {
            // 1) 手册：先 PcCreateModules，否则指令发不出去
            Frame r = c.call("a1", "RunProgram", "\"time\":60,\"speed\":1500,\"ramp\":150");
            eq("未建模块实例 → 拒绝", 3, r.status);
            eq("未建模块实例 → EC-05-2002", "EC-05-2002", r.errorCode());

            eq("建模块实例成功", 2, c.call("a2", "CreateModules", null).status);

            // 2) 先 Init 再动作
            r = c.call("a3", "RunProgram", "\"time\":60,\"speed\":1500,\"ramp\":150");
            eq("未 Init → 拒绝", 3, r.status);
            eq("未 Init → EC-05-2003", "EC-05-2003", r.errorCode());
            eq("Init 成功", 2, c.call("a4", "Init", null).status);

            // 3) 两扇门：OpenDoorB 要回 ControlCover（手册真实指令名）
            r = c.call("a5", "OpenDoorB", null);
            eq("开 B 门成功", 2, r.status);
            ok("回包 method 是手册真实名 ControlCover", r.raw.contains("\"method\":\"ControlCover\""));
            ok("回包区分 A/B 门", r.raw.contains("\"doorB\":\"open\""));
            r = c.call("a6", "RunProgram", "\"time\":60,\"speed\":1500,\"ramp\":150");
            eq("门没关不能离心", 3, r.status);
            eq("门没关 → EC-06-0006", "EC-06-0006", r.errorCode());

            // 4) 关门后正常离心；参数要按 时间/速度/加速度 传
            eq("关门成功", 2, c.call("a7", "CloseDoor", null).status);
            r = c.call("a8", "RunProgram", "\"time\":60,\"speed\":1500,\"ramp\":150");
            eq("离心启动成功", 2, r.status);
            ok("回显离心参数", r.raw.contains("\"timeSec\":60") && r.raw.contains("\"speed\":1500"));
            ok("查询当前转速返回 1500",
                    c.call("a9", "CurrSpeed", null).raw.contains("\"nRetValue\":1500"));
            ok("查询剩余时间返回 60",
                    c.call("a10", "CurrTime", null).raw.contains("\"nRetValue\":60"));

            // 5) 运行中不能开门
            r = c.call("a11", "OpenDoorA", null);
            eq("运行中开门被拒", 3, r.status);
            eq("运行中开门 → EC-06-0006", "EC-06-0006", r.errorCode());

            // 6) 参数缺失不能当 0 处理
            r = c.call("a12", "RunProgram", "\"speed\":1500");
            eq("缺时间参数 → 拒绝", 3, r.status);
            eq("缺参数 → EC-06-0005", "EC-06-0005", r.errorCode());
        } finally {
            core.stopServer();
        }
    }

    // ---------------------------------------------------------------- 设备级互斥

    private static void testConcurrentCommands() throws Exception {
        section("通用设备：跨连接并发动作互斥 / 执行中状态可查");
        DeviceCore core = new DeviceCore(0, "901", "ROBOT-CONC", "TRANSFER", 1200,
                false, 0, "EC-05-0001", false, "robot", null, null, 0);
        core.withExtraMethods(java.util.List.of("Home", "Transfer", "GetStatus"));
        core.startServer("127.0.0.1", 0);
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        try (Client first = new Client(core.port(), "901");
             Client second = new Client(core.port(), "901");
             Client observer = new Client(core.port(), "901")) {
            java.util.concurrent.Future<Frame> running = pool.submit(
                    () -> first.call("busy-1", "Home", null));
            long deadline = System.currentTimeMillis() + 2_000;
            while (!"Home".equals(core.snapshot().get("currentMethod"))
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            eq("第一条动作已进入执行态", "Home", core.snapshot().get("currentMethod"));

            Frame status = observer.call("busy-status", "GetStatus", null);
            eq("执行中 GetStatus 不被动作阻塞", 2, status.status);
            ok("执行中状态为 running", status.raw.contains(
                    "\"device_state\":{\"status\":\"running\""));
            ok("状态带当前方法", status.raw.contains("\"current_method\":\"Home\""));
            ok("状态带当前命令", status.raw.contains("\"current_command_id\":\"busy-1\""));

            Frame rejected = second.call("busy-2", "Home", null);
            eq("第二条跨连接动作被拒绝", 3, rejected.status);
            eq("设备忙返回 EC-06-0006", "EC-06-0006", rejected.errorCode());
            ok("错误指出正在执行的方法", rejected.raw.contains("设备正在执行 Home"));
            eq("第一条动作正常完成", 2, running.get(3, java.util.concurrent.TimeUnit.SECONDS).status);

            Frame idle = observer.call("idle-status", "GetStatus", null);
            ok("完成后恢复 idle", idle.raw.contains(
                    "\"device_state\":{\"status\":\"idle\""));
            ok("完成后清当前方法", idle.raw.contains("\"current_method\":null"));
            eq("拒绝不污染后续动作", 2, second.call("busy-3", "Home", null).status);
        } finally {
            pool.shutdownNow();
            core.stopServer();
        }
    }

    // ---------------------------------------------------------------- 工具

    /** 一条回包。 */
    private record Frame(int status, String errorCode, String raw) {
    }

    /** 极简客户端：连上去、发一条、读一帧。 */
    private static final class Client implements AutoCloseable {
        private final Socket socket;
        private final BufferedReader in;
        private final OutputStream out;
        private final String deviceId;

        Client(int port, String deviceId) throws Exception {
            this.deviceId = deviceId;
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(8_000);
            in = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8));
            out = socket.getOutputStream();
        }

        Frame call(String id, String method, String extra) throws Exception {
            String msg = "{\"message_id\":\"" + id + "\",\"message_type\":1,"
                    + "\"method\":\"" + method + "\",\"equipment_id\":\""
                    + deviceId + "\",\"parameters\":{"
                    + (extra == null ? "" : extra) + "}}";
            out.write((msg + SEP).getBytes(StandardCharsets.UTF_8));
            out.flush();

            StringBuilder sb = new StringBuilder();
            int ch;
            while ((ch = in.read()) != -1) {
                sb.append((char) ch);
                int idx = sb.indexOf(SEP);
                if (idx >= 0) {
                    sb.setLength(idx);
                    break;
                }
            }
            String raw = sb.toString();
            return new Frame(intField(raw, "method_status"), strField(raw, "error_code"), raw);
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (java.io.IOException ignore) {
                // 测试收尾，关不上也不影响结论
            }
        }
    }

    private static String strField(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static int intField(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)")
                .matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("── " + title + " " + "─".repeat(Math.max(0, 56 - title.length())));
    }

    private static void ok(String what, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("  ✓ " + what);
        } else {
            fail++;
            System.out.println("  ✗ " + what);
        }
    }

    private static void eq(String what, Object want, Object got) {
        boolean same = want == null ? got == null : want.equals(got);
        if (same) {
            pass++;
            System.out.println("  ✓ " + what + (want == null ? "" : " = " + want));
        } else {
            fail++;
            System.out.println("  ✗ " + what + "：期望 " + want + "，实际 " + got);
        }
    }

    private SimulatorBehaviorSelfTest() {
    }
}
