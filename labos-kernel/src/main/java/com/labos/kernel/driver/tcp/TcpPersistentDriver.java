package com.labos.kernel.driver.tcp;

import com.labos.kernel.device.DeviceErrorMapper;
import com.labos.kernel.driver.DeviceDriver;
import com.labos.kernel.driver.DriverCommand;
import com.labos.kernel.driver.DriverDescriptor;
import com.labos.kernel.driver.DriverResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 长连接 TCP 驱动：一条常驻连接，按 message_id 配对请求与响应。
 *
 * <p><b>为什么需要它</b>：短连接（{@link TcpDriverAdapter}）适合"发一条等一条、几秒回"。
 * 但孵育、离心这类动辄几十分钟，短连接会：
 * <ul>
 *   <li>一直占着 socket 干等，连接可能被中间设备掐掉</li>
 *   <li>设备执行期间推的进度/心跳全丢</li>
 * </ul>
 *
 * <p><b>核心结构</b>：
 * <pre>
 *   execute(cmd)
 *     ├ 分配 messageId
 *     ├ pending.put(messageId, future)
 *     ├ 写一行到 socket
 *     └ future.get(timeout)          ← 阻塞在这里
 *
 *   readerLoop()  （独立线程）
 *     ├ readLine()
 *     ├ 解析出 message_id
 *     ├ pending.remove(message_id).complete(result)
 *     └ 心跳/无主消息直接丢弃
 * </pre>
 *
 * <p><b>三个必须处理的点</b>：
 * <ol>
 *   <li><b>重连</b>：连接断了要能自动重连，否则一次网络抖动就永久失联</li>
 *   <li><b>写串行</b>：多个线程同时写同一条 socket 会串包，必须加锁</li>
 *   <li><b>清理悬空</b>：超时的 future 要从 pending 摘掉，否则内存泄漏</li>
 * </ol>
 */
public final class TcpPersistentDriver implements DeviceDriver, AutoCloseable {

    private static final String SEP = "#!LABSEP!#";
    private static final Pattern ID_FIELD = Pattern.compile(
            "\"message_id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern STATUS_FIELD = Pattern.compile(
            "\"method_status\"\\s*:\\s*(-?\\d+)");
    private static final Pattern ERROR_FIELD = Pattern.compile(
            "\"error\"\\s*:\\s*(\\{[^}]*}|null)");

    private final DriverDescriptor descriptor;
    private final String host;
    private final int port;

    /** 待配对的请求。key = messageId。 */
    private final Map<String, CompletableFuture<DriverResult>> pending = new ConcurrentHashMap<>();

    /** messageId → 设备实例 id：回包时要用它找型号，才能翻译设备错误码。 */
    private final Map<String, Long> pendingDevice = new ConcurrentHashMap<>();

    /**
     * 设备错误码翻译器。默认不翻译（保持 1.0 行为：原样透传）。
     *
     * <p>长连接**同样要翻译**：改动前这里把所有失败都压成 {@code EC-05-0001}，
     * 设备说的 {@code 0x8000000C}（无纸）在长连接模式下完全丢失，
     * 恢复引擎只能看到"未知错误"。
     */
    private final DeviceErrorMapper errorMapper;

    private volatile Socket socket;
    private volatile BufferedReader reader;
    private volatile OutputStream writer;
    /** 写锁：多个线程会同时调用 execute，socket 写必须串行。 */
    private final Object writeLock = new Object();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong reconnectCount = new AtomicLong();
    private Thread readerThread;

    public TcpPersistentDriver(DriverDescriptor descriptor) {
        this(descriptor, DeviceErrorMapper.passthrough());
    }

    public TcpPersistentDriver(DriverDescriptor descriptor, DeviceErrorMapper errorMapper) {
        this.descriptor = descriptor;
        this.errorMapper = errorMapper == null
                ? DeviceErrorMapper.passthrough() : errorMapper;
        // endpoint 形如 tcp://192.0.2.10:8501
        String ep = descriptor.endpoint();
        String hostPort = ep.startsWith("tcp://") ? ep.substring(6) : ep;
        int idx = hostPort.lastIndexOf(':');
        this.host = idx > 0 ? hostPort.substring(0, idx) : hostPort;
        this.port = idx > 0 ? Integer.parseInt(hostPort.substring(idx + 1)) : 8501;
    }

    @Override
    public DriverDescriptor describe() {
        return descriptor;
    }

    /**
     * B-02：当前 TCP 协议**没有设备侧 Abort 消息**（只有 {@code message_type=1} 的命令帧）。
     *
     * <p>所以这里只能什么都不做。改动前会把 {@code pending} 里的 future 摘掉并塞一个
     * 「被中止」，看上去很干净：内核立刻拿到失败、节点记 aborted、界面显示已停 ——
     * 但设备**从来没有收到停止指令，动作还在继续**。恢复时那一步因为不是 completed
     * 会被重跑，于是同一条物理动作执行两次。
     *
     * <p>{@link #supportsPhysicalAbort()} 保持接口安全默认 {@code false}，
     * 上层据此改成「等当前动作跑到安全边界再暂停」。
     */
    @Override
    public void abort(String commandId) {
        // 故意空实现：不能伪造「设备已停止」。要真停，得先在协议里加停止帧并做 HIL。
    }

    @Override
    public boolean supportsPhysicalAbort() {
        return false;
    }

    /** 包可见：仅供自测断言「abort 不会伪造完成」。 */
    Map<String, CompletableFuture<DriverResult>> pendingForTest() {
        return pending;
    }

    @Override
    public boolean healthy() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    /**
     * 发一条命令并等响应。
     *
     * <p>超时时间用命令自带的 {@code timeoutMs}——**长任务的超时要按方法配置**，
     * 孵育 30 分钟不能用 15 秒的默认值。
     */
    @Override
    public DriverResult execute(DriverCommand cmd) {
        ensureConnected();

        String messageId = cmd.commandId() == null
                ? "cmd-" + System.nanoTime() : cmd.commandId();
        CompletableFuture<DriverResult> future = new CompletableFuture<>();
        pending.put(messageId, future);
        pendingDevice.put(messageId, cmd.deviceInstanceId());

        long t0 = System.nanoTime();
        try {
            writeLine(buildMessage(messageId, cmd));
        } catch (IOException e) {
            pending.remove(messageId);
            // 写失败通常是连接断了，标记为需要重连
            closeQuietly();
            return DriverResult.failed(messageId, "EC-99-0001",
                    "发送失败：" + e.getMessage(), null, 0);
        }

        long timeout = cmd.timeoutMs() > 0 ? cmd.timeoutMs() : 15_000L;
        try {
            DriverResult r = future.get(timeout, TimeUnit.MILLISECONDS);
            return r;
        } catch (TimeoutException e) {
            // 关键：超时要把 future 摘掉，否则设备后来真回了会往一个没人等的 future 里塞
            pending.remove(messageId);
            pendingDevice.remove(messageId);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            return DriverResult.timeout(messageId, ms);
        } catch (InterruptedException e) {
            pending.remove(messageId);
            pendingDevice.remove(messageId);
            Thread.currentThread().interrupt();
            return DriverResult.failed(messageId, "EC-99-0003", "等待被中断", null, 0);
        } catch (ExecutionException e) {
            pending.remove(messageId);
            pendingDevice.remove(messageId);
            return DriverResult.failed(messageId, "EC-99-0002",
                    "执行异常：" + e.getCause(), null, 0);
        }
    }

    // ---------------------------------------------------------------- 连接管理

    /** 确保连接可用；断了就重连。 */
    private synchronized void ensureConnected() {
        if (socket != null && socket.isConnected() && !socket.isClosed()) {
            return;
        }
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, port), 5_000);
            s.setSoTimeout(0);   // 读线程常驻，不设超时；靠心跳/关闭检测断线
            s.setKeepAlive(true);
            s.setTcpNoDelay(true);

            socket = s;
            reader = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            writer = s.getOutputStream();

            // P1-2 修复（评审 20261102-03）：
            // 改动前用 `running.compareAndSet(false, true)` 判断要不要起读线程。
            // 但 readerLoop 退出时**没有把 running 置回 false**，
            // 于是断线重连后 CAS 永远失败 → **新 socket 没有人读** →
            // 请求发得出去、响应读不到，表现为"永久假超时"。
            //
            // 现在看线程是否真的活着，而不看那个不会回退的标志位。
            if (readerThread == null || !readerThread.isAlive()) {
                running.set(true);
                readerThread = new Thread(this::readerLoop,
                        "tcp-reader-" + descriptor.code());
                readerThread.setDaemon(true);
                readerThread.start();
            }
        } catch (IOException e) {
            closeQuietly();
            throw new IllegalStateException(
                    "连接驱动失败 " + host + ":" + port + " — " + e.getMessage(), e);
        }
    }

    /** 读线程：收到一行就按 message_id 分发。 */
    private void readerLoop() {
        StringBuilder buf = new StringBuilder();
        while (running.get()) {
            try {
                BufferedReader r = reader;
                if (r == null) {
                    break;
                }
                int ch = r.read();
                if (ch < 0) {
                    break;   // 对端关闭
                }
                buf.append((char) ch);

                // 按分隔符分帧：TCP 是流，不能假设一次 read 一条
                String data = buf.toString();
                int sepIdx = data.indexOf(SEP);
                if (sepIdx < 0) {
                    // 防止半截帧把内存撑爆
                    if (buf.length() > 4 * 1024 * 1024) {
                        buf.setLength(0);
                    }
                    continue;
                }
                String frame = data.substring(0, sepIdx);
                buf.setLength(0);
                buf.append(data.substring(sepIdx + SEP.length()));

                dispatch(frame);
            } catch (IOException e) {
                break;
            }
        }
        // 读线程退出 = 连接断了，清掉所有等待中的请求
        failAllPending("EC-99-0001", "驱动连接断开");
        closeQuietly();
        // P1-2/P1-14：把 running 置回 false —— 否则下次 ensureConnected
        // 的 CAS 永远失败，重连上来的 socket 不会有读线程，
        // 所有请求都会卡到超时（"永久假超时"）。
        // 重连本身走懒加载：下一次 dispatch 调 ensureConnected 时重建连接 + 读线程。
        running.set(false);
    }

    private void dispatch(String frame) {
        if (frame == null || frame.isBlank()) {
            return;
        }
        Matcher m = ID_FIELD.matcher(frame);
        if (!m.find()) {
            return;   // 没 message_id 的（心跳等）直接丢
        }
        String id = m.group(1);

        // **先看状态再决定要不要摘**：
        // method_status=1 表示"还在跑"（长任务常见：孵育要几十分钟）。
        // 这时不能 remove，也不能 complete——摘掉后设备再报完成，
        // future 已经没了，调用方只会看到一个假的超时。
        Matcher sm = STATUS_FIELD.matcher(frame);
        int status = sm.find() ? Integer.parseInt(sm.group(1)) : -1;
        if (status == 1) {
            CompletableFuture<DriverResult> running = pending.get(id);
            if (running != null && !running.isDone()) {
                System.out.println("[驱动] 运行中 " + id
                        + "（继续等，直到设备报完成或命令超时）");
            }
            return;   // 不摘，等下一帧
        }

        CompletableFuture<DriverResult> future = pending.remove(id);
        Long deviceId = pendingDevice.remove(id);
        if (future == null) {
            // 超时后被摘掉了，或者压根不是我们发的
            return;
        }
        future.complete(parseResult(id, frame, deviceId == null ? 0L : deviceId));
    }

    /** 解析回包。method_status: 2=成功，1=运行中，其他=失败。 */
    private DriverResult parseResult(String id, String frame, long deviceInstanceId) {
        Matcher sm = STATUS_FIELD.matcher(frame);
        int status = sm.find() ? Integer.parseInt(sm.group(1)) : -1;

        if (status == 2) {
            return DriverResult.success(id, frame, 0);
        }
        if (status == 1) {
            // 正常情况下 dispatch 已经把 status=1 拦住了，不该走到这里。
            // 真走到说明调用方直接调了 parseResult，保守返回失败。
            return DriverResult.failed(id, "EC-99-0004",
                    "设备仍在运行（method_status=1）", frame, 0);
        }
        Matcher em = ERROR_FIELD.matcher(frame);
        String err = em.find() ? em.group(1) : "{}";
        if (!errorMapper.enabled()) {
            return DriverResult.failed(id, "EC-05-0001", "设备返回失败：" + err, frame, 0);
        }
        // 设备自己的错误码要翻译成中控码，否则 recovery_policy 查不到策略
        DeviceErrorMapper.Mapped m = errorMapper.map(deviceInstanceId,
                TcpDriverAdapter.nestedErrorCode(frame), TcpDriverAdapter.nestedErrorText(frame));
        return DriverResult.failed(id, m.kernelCode(), m.describe(), frame, 0);
    }

    private void failAllPending(String code, String text) {
        pending.forEach((id, f) ->
                f.complete(DriverResult.failed(id, code, text, null, 0)));
        pending.clear();
    }

    private void writeLine(String json) throws IOException {
        synchronized (writeLock) {
            OutputStream w = writer;
            if (w == null) {
                throw new IOException("连接未建立");
            }
            w.write((json + SEP).getBytes(StandardCharsets.UTF_8));
            w.flush();
        }
    }

    private String buildMessage(String messageId, DriverCommand cmd) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"message_id\":\"").append(esc(messageId)).append("\",")
          .append("\"message_type\":1,")
          .append("\"method\":\"").append(esc(cmd.method())).append("\",")
          .append("\"equipment_id\":\"").append(esc(String.valueOf(cmd.deviceInstanceId()))).append("\",")
          .append("\"parameters\":{");
        boolean first = true;
        for (Map.Entry<String, Object> e : cmd.params().entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(esc(e.getKey())).append("\":");
            Object v = e.getValue();
            if (v == null) {
                sb.append("null");
            } else if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else {
                sb.append('"').append(esc(String.valueOf(v))).append('"');
            }
        }
        return sb.append("}}").toString();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 关闭连接。
     *
     * <p><b>P1-20 修复（评审 20261102-03）</b>：改动前没有同步，
     * 而 {@code ensureConnected} 是 {@code synchronized}。
     * 读线程退出调本方法时，可能正好撞上另一个线程在 {@code ensureConnected}
     * 里新建 socket —— 刚连上就被这一个关掉，然后所有请求超时。
     * 加同一把锁后，两者互斥。
     */
    private synchronized void closeQuietly() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignore) {
            // 关不上也没别的办法
        }
        socket = null;
        reader = null;
        writer = null;
    }

    /** 连接状态（给监控用）。 */
    public Map<String, Object> stats() {
        return Map.of(
                "code", descriptor.code(),
                "endpoint", descriptor.endpoint(),
                "connected", socket != null && !socket.isClosed(),
                "pending", pending.size(),
                "reconnects", reconnectCount.get());
    }

    @Override
    public void close() {
        running.set(false);
        failAllPending("EC-99-0001", "驱动已关闭");
        closeQuietly();
        if (readerThread != null) {
            readerThread.interrupt();
        }
    }
}
