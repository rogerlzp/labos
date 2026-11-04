package com.labos.kernel.driver.tcp;

import com.labos.kernel.device.DeviceErrorMapper;
import com.labos.kernel.driver.DeviceDriver;
import com.labos.kernel.driver.DriverCommand;
import com.labos.kernel.driver.DriverDescriptor;
import com.labos.kernel.driver.DriverResult;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TCP 驱动适配器：把现有 .NET 驱动的 JSON-over-TCP 协议包装成 {@link DeviceDriver}。
 *
 * <p><b>这是 .NET Sidecar 的 Java 参考实现</b>——字段名、分隔符、分帧、幂等、错误映射
 * 全部照真实协议来，C# 版可以逐段对照翻译。
 *
 * <p>协议：
 * <pre>
 *   下行 MethodMessage  {"message_id":"...","message_type":1,"method":"Deposit1",
 *                        "equipment_id":"id0044","parameters":{...}}
 *   上行 FinishMessage  {"message_id":"...","message_type":5,"method":"Deposit1",
 *                        "method_status":2,"error":{...},"data":{...}}
 *   帧分隔符            "#!LABSEP!#"
 * </pre>
 *
 * <p><b>三个必须处理的点</b>（C# 版同样适用）：
 * <ol>
 *   <li>分帧：TCP 是流，必须按分隔符切，不能假设一次 read 一条</li>
 *   <li>串行化：同一连接上的请求必须串行，否则响应会串</li>
 *   <li>按 message_id 过滤：驱动会异步推心跳，不能拿错响应</li>
 * </ol>
 */
public final class TcpDriverAdapter implements DeviceDriver {

    // 分帧标记。驱动侧必须用同一个标记，否则通信会失败。
    static final String DELIMITER = "#!LABSEP!#";
    private static final int MSG_TYPE_TASK = 1;
    private static final int MSG_TYPE_FINISH = 5;

    private final DriverDescriptor descriptor;
    private final String host;
    private final int port;
    /** deviceInstanceId -> equipment_id（驱动认的是 code 不是 id） */
    private final Map<Long, String> deviceCodes;

    /**
     * 设备错误码翻译器（设备码 {@code 0x8000000C} → 中控码 {@code EC-08-300C}）。
     *
     * <p>不翻译的后果：设备码一路透传到 {@code recovery_policy}，
     * 查不到任何策略 → 所有设备故障都变成"未知错误，转人工"。
     * 默认 passthrough（行为与该开关引入前一致），生产装配时由 {@code WorkflowService} 注入。
     */
    private final DeviceErrorMapper errorMapper;

    private final Object lock = new Object();
    private Socket socket;
    private Reader reader;
    private OutputStream out;
    private boolean healthy = false;

    public TcpDriverAdapter(DriverDescriptor descriptor, String host, int port,
                            Map<Long, String> deviceCodes) {
        this(descriptor, host, port, deviceCodes, DeviceErrorMapper.passthrough());
    }

    public TcpDriverAdapter(DriverDescriptor descriptor, String host, int port,
                            Map<Long, String> deviceCodes, DeviceErrorMapper errorMapper) {
        this.descriptor = descriptor;
        this.host = host;
        this.port = port;
        this.deviceCodes = deviceCodes;
        this.errorMapper = errorMapper == null
                ? DeviceErrorMapper.passthrough() : errorMapper;
    }

    @Override
    public DriverDescriptor describe() {
        return descriptor;
    }

    @Override
    public DriverResult execute(DriverCommand cmd) {
        synchronized (lock) {
            long t0 = System.currentTimeMillis();
            try {
                ensureConnected();

                String json = methodMessage(cmd);
                out.write((json + DELIMITER).getBytes(StandardCharsets.UTF_8));
                out.flush();

                long deadline = t0 + cmd.timeoutMs();
                while (true) {
                    String frame = readFrame(deadline);
                    if (frame == null) {
                        return DriverResult.timeout(cmd.commandId(),
                                System.currentTimeMillis() - t0);
                    }
                    Integer type = intField(frame, "message_type");
                    String msgId = strField(frame, "message_id");
                    // 过滤心跳/其他消息，只认同 id 的 Finish
                    if (type != null && type == MSG_TYPE_FINISH
                            && cmd.commandId().equals(msgId)) {
                        // method_status=1 表示"还在跑"，继续读到完成为止。
                        // 短连接上这很常见：孵育要几十分钟，驱动会先报一次运行中。
                        Integer st = intField(frame, "method_status");
                        if (st != null && st == 1) {
                            continue;   // 不返回，继续读下一帧
                        }
                        return toResult(frame, cmd.commandId(),
                                System.currentTimeMillis() - t0, cmd.deviceInstanceId());
                    }
                }
            } catch (java.net.SocketTimeoutException e) {
                // P1-18/19 修复（评审 20261102-03）：
                // **读超时 ≠ 连接坏了**。离心跑 30 分钟、孵育几小时都正常，
                // 设备只是还没回话。
                //
                // 改动前这里被下面的 IOException 分支一起吞掉：
                //   ① 错误码错成 EC-05-1006（通讯失败）而不是 EC-05-1007（超时）
                //   ② **把 healthy 置 false** —— 一次超时就把设备判死，
                //      后续所有命令都走重连/拒绝，等于设备掉线
                // 所以必须单独 catch（SocketTimeoutException 是 IOException 子类，
                // 必须放在前面）。
                return DriverResult.timeout(cmd.commandId(),
                        System.currentTimeMillis() - t0);
            } catch (IOException e) {
                healthy = false;
                return DriverResult.failed(cmd.commandId(), "EC-05-1006",
                        "TCP 通讯失败: " + e.getMessage(), null,
                        System.currentTimeMillis() - t0);
            }
        }
    }

    @Override
    public void abort(String commandId) {
        // 协议没有 Abort 消息类型，只能靠上层超时
    }

    @Override
    public boolean healthy() {
        synchronized (lock) {
            return socket != null && !socket.isClosed() && healthy;
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException ignored) {
                // 忽略
            }
            healthy = false;
        }
    }

    // ---------------------------------------------------------------- 协议

    private String methodMessage(DriverCommand cmd) {
        String equipmentCode = deviceCodes.getOrDefault(
                cmd.deviceInstanceId(), String.valueOf(cmd.deviceInstanceId()));
        StringBuilder params = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : cmd.params().entrySet()) {
            if (!first) {
                params.append(',');
            }
            params.append('"').append(esc(e.getKey())).append("\":\"")
                    .append(esc(String.valueOf(e.getValue()))).append('"');
            first = false;
        }
        params.append('}');

        return "{" +
                "\"message_id\":\"" + esc(cmd.commandId()) + "\"," +
                "\"message_type\":" + MSG_TYPE_TASK + "," +
                "\"method\":\"" + esc(cmd.method()) + "\"," +
                "\"equipment_id\":\"" + esc(equipmentCode) + "\"," +
                "\"workflow_id\":\"\"," +
                "\"experiment_id\":\"\"," +
                "\"parameters\":" + params + "," +
                "\"description\":\"" + esc(cmd.traceId()) + "\"" +
                "}";
    }

    /**
     * FinishMessage -> DriverResult。
     *
     * <p>关键：整个原始 JSON 塞进 {@code deviceRawResponse}——
     * 这是 99.7% 缺失的东西，也是唯一能复盘的证据。
     */
    private DriverResult toResult(String raw, String commandId, long durationMs,
                                  long deviceInstanceId) {
        Integer methodStatus = intField(raw, "method_status");
        String errorCode = nestedErrorCode(raw);
        String errorText = nestedErrorText(raw);

        if (methodStatus != null && methodStatus == 2) {
            return DriverResult.success(commandId, raw, durationMs);
        }
        // 失败：**先翻译设备码**，再交给恢复引擎。
        // 原始帧仍原样写进 deviceRawResponse —— 翻译不等于丢证据。
        if (!errorMapper.enabled()) {
            // 没配型号绑定 → 保持 1.0 行为（原样透传），不要假装自己懂
            String code = (errorCode == null || errorCode.isBlank()) ? "EC-08-0001" : errorCode;
            String msg = errorText == null ? "method_status=" + methodStatus : errorText;
            return DriverResult.failed(commandId, code, msg, raw, durationMs);
        }
        DeviceErrorMapper.Mapped m = errorMapper.map(deviceInstanceId, errorCode, errorText);
        return DriverResult.failed(commandId, m.kernelCode(), m.describe(), raw, durationMs);
    }

    private void ensureConnected() throws IOException {
        if (socket != null && !socket.isClosed() && healthy) {
            return;
        }
        close();
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 3000);
        socket.setSoTimeout(30_000);
        reader = new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8);
        out = socket.getOutputStream();
        healthy = true;
    }

    /** 按分隔符读一帧；超时返回 null。 */
    private String readFrame(long deadline) throws IOException {
        long remain = deadline - System.currentTimeMillis();
        if (remain <= 0) {
            return null;
        }
        socket.setSoTimeout((int) Math.max(1, remain));

        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = reader.read()) != -1) {
            sb.append((char) c);
            int len = sb.length();
            if (len >= DELIMITER.length()
                    && sb.substring(len - DELIMITER.length()).equals(DELIMITER)) {
                return sb.substring(0, len - DELIMITER.length());
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 极简 JSON 提取

    static String strField(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(json);
        return m.find() ? m.group(1) : null;
    }

    static Integer intField(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)")
                .matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    /** error 是嵌套对象，单独提。 */
    static String nestedErrorCode(String json) {
        Matcher m = Pattern.compile("\"error\"\\s*:\\s*\\{[^}]*\"error_code\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(json);
        return m.find() ? m.group(1) : null;
    }

    static String nestedErrorText(String json) {
        Matcher m = Pattern.compile("\"error\"\\s*:\\s*\\{[^}]*\"error_text\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
