package com.labos.gateway.redis;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 最小 Redis 客户端（RESP2 协议）。
 *
 * <p><b>为什么不引第三方库</b>：项目 classpath 只有 PostgreSQL 驱动，
 * 为几个命令引 Lettuce/Jedis 会带一堆依赖（Netty 等）。
 * 这里只需要 GET/SET/HSET/LPUSH/BRPOP 几种，手写 200 行够用。
 *
 * <p><b>不是线程安全的长连接池</b>：每次操作借一条连接、用完还回去。
 * 真正的连接池要处理空闲回收、健康检查，那是框架的活；
 * 这里的用法是"一个命令一次往返"，简单借还足够。
 *
 * <p><b>RESP2 的五种类型</b>：
 * <pre>
 *   +OK\r\n              简单字符串
 *   -ERR msg\r\n         错误
 *   :123\r\n             整数
 *   $5\r\nhello\r\n      批量字符串（$ -1 表示 null）
 *   *2\r\n$3\r\nfoo\r\n  数组
 * </pre>
 */
public final class RedisClient implements AutoCloseable {

    private final String host;
    private final int port;
    private final String password;
    private final int database;

    private Socket socket;
    private OutputStream out;
    private InputStream in;

    public RedisClient(String host, int port, String password, int database) {
        this.host = host;
        this.port = port;
        this.password = password;
        this.database = database;
    }

    /** 从环境变量读配置：LABOS_REDIS_HOST / PORT / PASSWORD / DB。 */
    public static RedisClient fromEnv() {
        return new RedisClient(
                env("LABOS_REDIS_HOST", "127.0.0.1"),
                Integer.parseInt(env("LABOS_REDIS_PORT", "6379")),
                System.getenv("LABOS_REDIS_PASSWORD"),
                Integer.parseInt(env("LABOS_REDIS_DB", "0")));
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? def : v;
    }

    // ---------------------------------------------------------------- 连接

    private synchronized void ensureConnected() throws IOException {
        if (socket != null && socket.isConnected() && !socket.isClosed()) {
            return;
        }
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 3_000);
        s.setSoTimeout(10_000);
        s.setTcpNoDelay(true);
        socket = s;
        out = s.getOutputStream();
        in = new BufferedInputStream(s.getInputStream());

        if (password != null && !password.isBlank()) {
            Object r = call("AUTH", password);
            if (r instanceof RedisError e) {
                close();
                throw new IOException("Redis AUTH 失败: " + e.message);
            }
        }
        if (database != 0) {
            call("SELECT", String.valueOf(database));
        }
    }

    // ---------------------------------------------------------------- 命令

    /**
     * 执行一条命令，返回解析后的值（String / Long / List / null）。
     *
     * <p><b>P0-8 修复（评审 20261102-03）</b>：本类是单例，只持有一条 socket；
     * 原来 {@code call()} 没有同步 —— 两个线程同时进来会
     * <b>把两个命令的字节交错写出去</b>，或 A 读到 B 的应答（串包）。
     * 现象是"偶发的脏数据 / 类型转换异常"，极难复现。
     *
     * <p>加 {@code synchronized} 后：一条连接同一时刻只跑一条命令。
     * <b>代价</b>：并发度降到 1。要真正并发需要连接池，
     * 那是下一轮的事（见评审记录 P1）。
     */
    public synchronized Object call(String... args) throws IOException {
        ensureConnected();
        writeCommand(args);
        Object r = readReply();
        if (r instanceof RedisError e) {
            throw new RedisException(e.message);
        }
        return r;
    }

    public String get(String key) throws IOException {
        Object r = call("GET", key);
        return r == null ? null : String.valueOf(r);
    }

    public void set(String key, String value, int ttlSeconds) throws IOException {
        if (ttlSeconds > 0) {
            call("SET", key, value, "EX", String.valueOf(ttlSeconds));
        } else {
            call("SET", key, value);
        }
    }

    public void hset(String key, String field, String value) throws IOException {
        call("HSET", key, field, value);
    }

    public String hget(String key, String field) throws IOException {
        Object r = call("HGET", key, field);
        return r == null ? null : String.valueOf(r);
    }

    public void hdel(String key, String field) throws IOException {
        call("HDEL", key, field);
    }

    public long lpush(String key, String value) throws IOException {
        Object r = call("LPUSH", key, value);
        return r instanceof Long l ? l : 0L;
    }

    /** 非阻塞右弹出。队列空返回 null。 */
    public String rpop(String key) throws IOException {
        Object r = call("RPOP", key);
        return r == null ? null : String.valueOf(r);
    }

    public long del(String key) throws IOException {
        Object r = call("DEL", key);
        return r instanceof Long l ? l : 0L;
    }

    public boolean ping() {
        try {
            Object r = call("PING");
            return "PONG".equals(String.valueOf(r));
        } catch (IOException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 协议

    private void writeCommand(String... args) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append('*').append(args.length).append("\r\n");
        for (String a : args) {
            byte[] b = a == null ? new byte[0] : a.getBytes(StandardCharsets.UTF_8);
            sb.append('$').append(b.length).append("\r\n").append(a == null ? "" : a)
              .append("\r\n");
        }
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private Object readReply() throws IOException {
        int type = in.read();
        if (type < 0) {
            throw new IOException("Redis 连接关闭");
        }
        String line = readLine();
        switch (type) {
            case '+':
                return line;
            case '-':
                return new RedisError(line);
            case ':':
                return Long.parseLong(line);
            case '$': {
                int len = Integer.parseInt(line);
                if (len < 0) {
                    return null;
                }
                byte[] buf = new byte[len + 2];   // +2 是 \r\n
                int read = 0;
                while (read < buf.length) {
                    int n = in.read(buf, read, buf.length - read);
                    if (n < 0) {
                        throw new IOException("读取 bulk string 时连接关闭");
                    }
                    read += n;
                }
                return new String(buf, 0, len, StandardCharsets.UTF_8);
            }
            case '*': {
                int n = Integer.parseInt(line);
                if (n < 0) {
                    return null;
                }
                List<Object> list = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    list.add(readReply());
                }
                return list;
            }
            default:
                throw new IOException("未识别的 RESP 类型: " + (char) type);
        }
    }

    private String readLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = in.read();
            if (c < 0) {
                throw new IOException("读行时连接关闭");
            }
            if (c == '\r') {
                int n = in.read();       // 吃掉 \n
                if (n != '\n') {
                    throw new IOException("协议错：CR 后不是 LF");
                }
                return sb.toString();
            }
            sb.append((char) c);
        }
    }

    /** 错误回复。 */
    private record RedisError(String message) {
    }

    /** Redis 返回的错误（业务层可见）。 */
    public static final class RedisException extends IOException {
        public RedisException(String message) {
            super("Redis: " + message);
        }
    }

    @Override
    public synchronized void close() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignore) {
            // 关不上也没别的办法
        }
        socket = null;
        out = null;
        in = null;
    }
}
