package com.labos.gateway.driver;

import com.labos.gateway.redis.RedisClient;

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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一条驱动长连接。
 *
 * <p>职责只有四件：
 * <ol>
 *   <li>维持 TCP 连接（断了重连）</li>
 *   <li>发命令、按 message_id 配对回包</li>
 *   <li>写 Redis（结果 + 设备状态）</li>
 *   <li>心跳</li>
 * </ol>
 *
 * <p><b>不做的事</b>：不判断业务语义、不重试、不存历史。
 * 网关越聪明，出问题越难定位。
 */
public final class DriverConnection {

    private static final String SEP = "#!LABSEP!#";
    private static final Pattern ID_FIELD =
            Pattern.compile("\"message_id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern STATUS_FIELD =
            Pattern.compile("\"method_status\"\\s*:\\s*(-?\\d+)");
    private static final Pattern METHOD_FIELD =
            Pattern.compile("\"method\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern EQUIPMENT_FIELD =
            Pattern.compile("\"equipment_id\"\\s*:\\s*\"([^\"]*)\"");

    private final String driverCode;
    private final String host;
    private final int port;
    private final RedisClient redis;
    private final int resultTtlSec;

    private final Map<String, CompletableFuture<String>> pending = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();
    private final AtomicLong reconnects = new AtomicLong();
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    private volatile Socket socket;
    private volatile BufferedReader reader;
    private volatile OutputStream writer;
    private volatile boolean running = true;

    public DriverConnection(String driverCode, String host, int port,
                            RedisClient redis, int resultTtlSec) {
        this.driverCode = driverCode;
        this.host = host;
        this.port = port;
        this.redis = redis;
        this.resultTtlSec = resultTtlSec;
    }

    public String driverCode() {
        return driverCode;
    }

    // ---------------------------------------------------------------- 连接

    private synchronized boolean ensureConnected() {
        if (socket != null && socket.isConnected() && !socket.isClosed()) {
            return true;
        }
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, port), 5_000);
            s.setSoTimeout(0);
            s.setKeepAlive(true);
            s.setTcpNoDelay(true);

            socket = s;
            reader = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            writer = s.getOutputStream();

            Thread t = new Thread(this::readerLoop, "reader-" + driverCode);
            t.setDaemon(true);
            t.start();

            log("已连接 " + host + ":" + port);
            return true;
        } catch (IOException e) {
            log("连接失败：" + e.getMessage());
            closeQuietly();
            return false;
        }
    }

    private void readerLoop() {
        StringBuilder buf = new StringBuilder();
        while (running) {
            try {
                BufferedReader r = reader;
                if (r == null) {
                    break;
                }
                int ch = r.read();
                if (ch < 0) {
                    break;
                }
                buf.append((char) ch);

                String data = buf.toString();
                int idx = data.indexOf(SEP);
                if (idx < 0) {
                    if (buf.length() > 4 * 1024 * 1024) {
                        buf.setLength(0);
                    }
                    continue;
                }
                String frame = data.substring(0, idx);
                buf.setLength(0);
                buf.append(data.substring(idx + SEP.length()));
                onFrame(frame);
            } catch (IOException e) {
                break;
            }
        }
        // 读线程退出 = 连接断，所有等待中的请求立刻失败
        failAllPending("EC-99-0001", "驱动连接断开");
        closeQuietly();
        log("连接断开");
    }

    private void onFrame(String frame) {
        if (frame == null || frame.isBlank()) {
            return;
        }
        Matcher m = ID_FIELD.matcher(frame);
        if (!m.find()) {
            return;   // 心跳等无主消息
        }
        String id = m.group(1);
        CompletableFuture<String> f = pending.remove(id);
        if (f != null) {
            f.complete(frame);
        }
        // 无论有没有人等，都要把结果写进 Redis（晚到的消费者也能取到）
        writeResult(id, frame);
    }

    /**
     * 发一条命令并等回包。
     *
     * @return 回包原文；超时或连接失败返回 null（结果已写 Redis）
     */
    public String execute(String commandId, long deviceInstanceId, String method,
                          String paramsJson, long timeoutMs) {
        if (!ensureConnected()) {
            writeResultText(commandId, false, "EC-06-0001",
                    "驱动不可用：" + driverCode, null, 0);
            failed.incrementAndGet();
            return null;
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        pending.put(commandId, future);

        // 先标 running，前端能立刻看到"这台设备在忙"
        markState(deviceInstanceId, "running", method, commandId);

        long t0 = System.nanoTime();
        try {
            write(buildMessage(commandId, deviceInstanceId, method, paramsJson));
            sent.incrementAndGet();
        } catch (IOException e) {
            pending.remove(commandId);
            closeQuietly();
            writeResultText(commandId, false, "EC-99-0001",
                    "发送失败：" + e.getMessage(), null, 0);
            markState(deviceInstanceId, "error", method, commandId);
            failed.incrementAndGet();
            return null;
        }

        long timeout = timeoutMs > 0 ? timeoutMs : 15_000L;
        try {
            String frame = future.get(timeout, TimeUnit.MILLISECONDS);
            markState(deviceInstanceId, "idle", null, null);
            return frame;
        } catch (TimeoutException e) {
            // 关键：超时要从 pending 摘掉，否则设备后来真回了会往没人等的 future 里塞
            pending.remove(commandId);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            writeResultText(commandId, false, "EC-99-0006",
                    "等待 " + (timeout / 1000) + "s 超时（已等 " + ms + "ms）", null, ms);
            markState(deviceInstanceId, "error", method, commandId);
            failed.incrementAndGet();
            return null;
        } catch (Exception e) {
            pending.remove(commandId);
            writeResultText(commandId, false, "EC-99-0002",
                    "执行异常：" + e.getMessage(), null, 0);
            failed.incrementAndGet();
            return null;
        }
    }

    // ---------------------------------------------------------------- Redis

    private void writeResult(String commandId, String frame) {
        Matcher sm = STATUS_FIELD.matcher(frame);
        int status = sm.find() ? Integer.parseInt(sm.group(1)) : -1;
        boolean ok = status == 2;
        String errCode = ok ? null : (status == 1 ? "EC-99-0007" : "EC-05-0001");

        Matcher mm = METHOD_FIELD.matcher(frame);
        String method = mm.find() ? mm.group(1) : null;
        Matcher em = EQUIPMENT_FIELD.matcher(frame);
        String equipment = em.find() ? em.group(1) : null;

        writeResultText(commandId, ok, errCode, null, frame, 0);
        if (equipment != null) {
            try {
                markState(Long.parseLong(equipment), "idle", method, null);
            } catch (NumberFormatException ignore) {
                // equipment_id 不是数字就不标状态
            }
        }
    }

    private void writeResultText(String commandId, boolean ok, String errCode,
                                 String message, String raw, long ms) {
        try {
            StringBuilder sb = new StringBuilder("{");
            sb.append("\"commandId\":\"").append(commandId).append("\",");
            sb.append("\"ok\":").append(ok).append(',');
            sb.append("\"errorCode\":").append(errCode == null ? "null"
                    : "\"" + errCode + "\"").append(',');
            sb.append("\"error\":").append(message == null ? "null"
                    : "\"" + esc(message) + "\"").append(',');
            sb.append("\"elapsedMs\":").append(ms).append(',');
            sb.append("\"raw\":").append(raw == null ? "null"
                    : "\"" + esc(raw) + "\"").append('}');
            redis.set("labos:cmd:result:" + commandId, sb.toString(), resultTtlSec);
            redis.hdel("labos:cmd:pending:" + driverCode, commandId);
        } catch (IOException e) {
            log("写结果失败：" + e.getMessage());
        }
    }

    private void markState(long deviceInstanceId, String status,
                           String method, String commandId) {
        try {
            String key = "labos:dev:state:" + deviceInstanceId;
            redis.hset(key, "status", status);
            if (method != null) {
                redis.hset(key, "method", method);
            }
            if (commandId != null) {
                redis.hset(key, "commandId", commandId);
            }
            redis.hset(key, "updatedAt", String.valueOf(System.currentTimeMillis()));
        } catch (IOException e) {
            log("写状态失败：" + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 收发

    private void write(String json) throws IOException {
        synchronized (writeLock) {
            OutputStream w = writer;
            if (w == null) {
                throw new IOException("连接未建立");
            }
            w.write((json + SEP).getBytes(StandardCharsets.UTF_8));
            w.flush();
        }
    }

    private String buildMessage(String commandId, long deviceId,
                                String method, String paramsJson) {
        String params = (paramsJson == null || paramsJson.isBlank()) ? "{}" : paramsJson;
        return "{\"message_id\":\"" + esc(commandId) + "\","
                + "\"message_type\":1,"
                + "\"method\":\"" + esc(method) + "\","
                + "\"equipment_id\":\"" + deviceId + "\","
                + "\"parameters\":" + params + "}";
    }

    private void failAllPending(String code, String text) {
        pending.forEach((id, f) -> {
            writeResultText(id, false, code, text, null, 0);
            f.completeExceptionally(new IOException(text));
        });
        pending.clear();
    }

    private void closeQuietly() {
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

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void log(String msg) {
        System.out.println("[GW:" + driverCode + "] " + msg);
    }

    /** 状态摘要（给 /health 用）。 */
    public String stats() {
        return driverCode + " connected=" + (socket != null && !socket.isClosed())
                + " pending=" + pending.size()
                + " sent=" + sent.get()
                + " failed=" + failed.get()
                + " reconnects=" + reconnects.get();
    }

    public void close() {
        running = false;
        failAllPending("EC-99-0001", "网关关闭");
        closeQuietly();
    }
}
