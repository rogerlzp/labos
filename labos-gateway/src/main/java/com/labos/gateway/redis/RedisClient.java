package com.labos.gateway.redis;

import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.output.ValueOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.CommandType;

import java.io.IOException;
import java.util.Locale;

/**
 * Redis 客户端（2026-11-09 换 Lettuce）。
 *
 * <p>改动前是手写的 RESP2 协议 + 裸 Socket：每次操作借一条连接、自己解析
 * 五种 RESP 类型，注释里也承认「真正的连接池是框架的活」。现在连接池、
 * 重连、超时、命令编解码全部交给 Lettuce/Netty。
 *
 * <p><b>对外签名一个没改</b>（{@code DriverConnection} 零改动）：
 * {@code get/set/hset/hget/hdel/lpush/rpop/del/ping/call/close/fromEnv}。
 * 原来的「一个命令一次往返」语义由 Lettuce 的共享长连接覆盖，且天然线程安全
 * （手写版 {@code call} 加了 synchronized 才行）。
 */
public final class RedisClient implements AutoCloseable {

    private final io.lettuce.core.RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> sync;

    public RedisClient(String host, int port, String password, int database) {
        RedisURI.Builder b = RedisURI.Builder.redis(host, port).withDatabase(database);
        if (password != null && !password.isEmpty()) {
            b.withPassword(password.toCharArray());
        }
        RedisURI uri = b.build();
        uri.setTimeout(java.time.Duration.ofSeconds(5));
        this.client = io.lettuce.core.RedisClient.create(uri);
        this.connection = client.connect(StringCodec.UTF8);
        this.sync = connection.sync();
    }

    /** 从环境变量读配置：LABOS_REDIS_HOST / PORT / PASSWORD / DB。 */
    public static RedisClient fromEnv() {
        return new RedisClient(
                env("LABOS_REDIS_HOST", "127.0.0.1"),
                Integer.parseInt(env("LABOS_REDIS_PORT", "6379")),
                System.getenv("LABOS_REDIS_PASSWORD"),
                Integer.parseInt(env("LABOS_REDIS_DB", "0")));
    }

    /**
     * 通用命令（保持旧签名）。gateway 主链路只用 {@code set/hset/hdel}，
     * 这里保留给运维脚本/扩展命令使用；Lettuce 的 {@code dispatch} 负责编解码。
     */
    public synchronized Object call(String... args) throws IOException {
        if (args == null || args.length == 0) {
            throw new RedisException("空命令");
        }
        CommandType type;
        try {
            type = CommandType.valueOf(args[0].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new RedisException("未知 Redis 命令: " + args[0]);
        }
        CommandArgs<String, String> cmd = new CommandArgs<>(StringCodec.UTF8);
        for (int i = 1; i < args.length; i++) {
            cmd.add(args[i]);
        }
        return sync.dispatch(type, new ValueOutput<>(StringCodec.UTF8), cmd);
    }

    public String get(String key) throws IOException {
        return sync.get(key);
    }

    public void set(String key, String value, int ttlSeconds) throws IOException {
        if (ttlSeconds > 0) {
            sync.setex(key, ttlSeconds, value);
        } else {
            sync.set(key, value);
        }
    }

    public void hset(String key, String field, String value) throws IOException {
        sync.hset(key, field, value);
    }

    public String hget(String key, String field) throws IOException {
        return sync.hget(key, field);
    }

    public void hdel(String key, String field) throws IOException {
        sync.hdel(key, field);
    }

    public long lpush(String key, String value) throws IOException {
        Long n = sync.lpush(key, value);
        return n == null ? 0L : n;
    }

    public String rpop(String key) throws IOException {
        return sync.rpop(key);
    }

    public long del(String key) throws IOException {
        Long n = sync.del(key);
        return n == null ? 0L : n;
    }

    public boolean ping() {
        try {
            return "PONG".equalsIgnoreCase(sync.ping());
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } finally {
            client.shutdown();
        }
    }

    /** 保留原有异常类型，避免调用方 catch 不到。 */
    public static class RedisException extends RuntimeException {
        public RedisException(String message) {
            super(message);
        }
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? def : v;
    }
}
