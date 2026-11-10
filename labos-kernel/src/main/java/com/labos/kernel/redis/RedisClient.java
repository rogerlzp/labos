package com.labos.kernel.redis;

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
 * Redis 客户端（2026-11-10 由手写 RESP2 换 Lettuce，与网关统一）。
 *
 * <p>改动前是**手写的 RESP2 协议 + 裸 Socket**：每次操作借一条连接、自己解析
 * 五种 RESP 类型，注释里也承认「真正的连接池是框架的活」。现在连接池、重连、
 * 超时、命令编解码全部交给 Lettuce/Netty。
 *
 * <p><b>为什么内核需要它</b>：Redis 在 LabOS 里不是「网关模式的可选项」，而是
 * 架构级依赖 —— ① 设备网关的状态中心；② 内核分布式资源锁的跨实例协调
 * （ADR-LOCK-001）；③ 内核→网关的设备命令通道。内核侧目前还没接上
 * （调度仍是单实例的资源级准入，{@code LABOS_DRIVER_MODE=redis} 未实现），
 * 但客户端先统一成一种实现，避免两套 RESP2 代码各自腐烂。
 *
 * <p><b>对外签名一个没改</b>（{@code get/set/hset/hget/hdel/lpush/rpop/del/ping/
 * call/close/fromEnv}），调用方无需改。
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
     * 通用命令（保持旧签名）。Lettuce 的 {@code dispatch} 负责编解码。
     */
    public Object call(String... args) throws IOException {
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

    /**
     * 保留原有异常类型（继承 IOException），避免调用方 catch 不到。
     *
     * <p>与网关那份的差别：网关的 {@code RedisException} 是 unchecked，内核这份
     * 历史上是 checked —— 保留原样，签名不变。
     */
    public static class RedisException extends IOException {
        public RedisException(String message) {
            super(message);
        }
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? def : v;
    }
}
