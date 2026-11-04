package com.labos.kernel.driver.tcp;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模拟 .NET 驱动进程：TCP 服务端 + JSON 报文 + {@code #!LABSEP!#} 分隔符。
 *
 * <p>行为尽量贴近真实驱动：
 * <ul>
 *   <li>收到 {@code message_type=1}(Task) → 执行 → 回 {@code message_type=5}(Finish)</li>
 *   <li>Finish 里带 {@code error} 嵌套对象（成功时为空）</li>
 *   <li>可注入故障：某设备某方法返回指定错误码</li>
 * </ul>
 *
 * <p>它的存在是为了在没有 .NET 环境的情况下验证 Sidecar 的协议转换是否正确。
 */
public final class MockDriverServer implements AutoCloseable {

    // 分帧标记。早期版本用的是别的前缀，品牌中性化时一并改了。
    // **升级注意**：厂商驱动侧必须同步改成同一个标记，否则通信会失败。
    private static final String DELIMITER = "#!LABSEP!#";
    private static final Pattern MSG_ID = Pattern.compile("\"message_id\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern METHOD = Pattern.compile("\"method\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern EQUIP = Pattern.compile("\"equipment_id\"\\s*:\\s*\"([^\"]*)\"");

    private final ServerSocket server;
    private final Map<String, String> faults = new HashMap<>();   // equipmentId:method -> errorCode
    private final List<String> received = new ArrayList<>();
    private volatile boolean running = true;

    public MockDriverServer(int port) throws IOException {
        this.server = new ServerSocket(port);
        Thread t = new Thread(this::acceptLoop, "mock-driver-acceptor");
        t.setDaemon(true);
        t.start();
    }

    public int port() {
        return server.getLocalPort();
    }

    /** 注入故障：该设备该方法的指令返回指定错误码。 */
    public synchronized MockDriverServer fault(String equipmentId, String method, String errorCode) {
        faults.put(equipmentId + ":" + method, errorCode);
        return this;
    }

    public synchronized List<String> received() {
        return List.copyOf(received);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                Thread h = new Thread(() -> handle(s), "mock-driver-conn");
                h.setDaemon(true);
                h.start();
            } catch (IOException e) {
                if (running) {
                    // accept 失败（关闭时正常抛异常）
                }
                return;
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket;
             Reader in = new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8);
             OutputStream out = s.getOutputStream()) {

            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) != -1) {
                sb.append((char) c);
                int len = sb.length();
                if (len >= DELIMITER.length()
                        && sb.substring(len - DELIMITER.length()).equals(DELIMITER)) {
                    String msg = sb.substring(0, len - DELIMITER.length());
                    sb.setLength(0);
                    String reply = process(msg);
                    out.write((reply + DELIMITER).getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
        } catch (IOException ignored) {
            // 连接断开
        }
    }

    /**
     * 是否模拟"长任务"：先回 method_status=1（运行中），1 秒后再回 2（完成）。
     *
     * <p>用来验证驱动和状态机在长任务上的行为——真机上孵育要几十分钟，
     * 驱动会先报运行中。没有这个开关，仿真永远只走"立即完成"这一条路径。
     */
    private final boolean simulateLongTask =
            System.getenv("LABOS_MOCK_LONG_TASK") != null;

    private String process(String msg) {
        String msgId = find(MSG_ID, msg);
        String method = find(METHOD, msg);
        String equip = find(EQUIP, msg);
        synchronized (this) {
            received.add(equip + ":" + method);
        }

        String faultKey = equip + ":" + method;
        String errorCode;
        synchronized (this) {
            errorCode = faults.get(faultKey);
        }

        if (errorCode != null) {
            return finish(msgId, method, equip, 3,
                    "{\"error_code\":\"" + errorCode + "\",\"troubleshoot\":0,"
                            + "\"error_text\":\"模拟设备故障 " + errorCode + "\"}",
                    "{}");
        }
        String finalFrame = finish(msgId, method, equip, 2, "null",
                "{\"position\":\"A1\",\"plate\":\"PL20261029\"}");
        if (simulateLongTask) {
            // 先拼一帧"运行中"，再用分隔符接上"完成"帧。
            // 客户端按分隔符分帧，会先收到 1、再收到 2——正好验证"继续等"的逻辑。
            String runningFrame = finish(msgId, method, equip, 1, "null", "{}");
            return runningFrame + DELIMITER + finalFrame;
        }
        return finalFrame;
    }

    private String finish(String msgId, String method, String equip, int methodStatus,
                          String errorJson, String dataJson) {
        return "{"
                + "\"message_id\":\"" + msgId + "\","
                + "\"message_type\":5,"
                + "\"method\":\"" + method + "\","
                + "\"equipment_id\":\"" + equip + "\","
                + "\"equipment_status\":1,"
                + "\"method_status\":" + methodStatus + ","
                + "\"error\":" + errorJson + ","
                + "\"data\":" + dataJson + ","
                + "\"process_info\":{},"
                + "\"timestamp\":\"2026-10-29 11:30:00\","
                + "\"checksum\":\"\","
                + "\"connected\":1"
                + "}";
    }

    private static String find(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : "";
    }

    @Override
    public void close() {
        running = false;
        try {
            server.close();
        } catch (IOException ignored) {
            // 忽略
        }
    }

    /**
     * 独立启动：{@code java -cp out com.labos.kernel.driver.tcp.MockDriverServer 19401}
     *
     * <p>环境变量 {@code LABOS_MOCK_LONG_TASK=1} 会模拟长任务
     * （先回 method_status=1，再回 2），用于验证"运行中继续等"的逻辑。
     */
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 19401;
        MockDriverServer server = new MockDriverServer(port);
        System.out.println("[MOCK] 监听 " + port
                + (System.getenv("LABOS_MOCK_LONG_TASK") != null
                   ? "（长任务模拟：先回运行中再回完成）" : ""));
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        Thread.currentThread().join();
    }
}
