package com.labos.gateway;

import com.labos.gateway.driver.DriverConnection;
import com.labos.gateway.redis.RedisClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 设备网关入口。
 *
 * <p><b>它做什么</b>：
 * <ol>
 *   <li>为每条驱动建一条长连接</li>
 *   <li>从 Redis 队列取命令，发到设备</li>
 *   <li>把结果和设备状态写回 Redis</li>
 * </ol>
 *
 * <p><b>它不做什么</b>（这条边界很重要）：
 * <ul>
 *   <li>不判断业务语义（"这块板该不该搬"是中控的事）</li>
 *   <li>不重试（重试要用同一个 commandId，只有中控知道能不能重试）</li>
 *   <li>不存历史（历史在 run_command 表里）</li>
 * </ul>
 *
 * <p><b>启动</b>：
 * <pre>
 *   java -jar labos-gateway.jar
 * </pre>
 *
 * <p><b>配置（环境变量）</b>：
 * <pre>
 *   LABOS_REDIS_HOST       默认 127.0.0.1
 *   LABOS_REDIS_PORT       默认 6379
 *   LABOS_REDIS_PASSWORD   无
 *   LABOS_GATEWAY_DRIVERS  DRV-A=tcp://192.0.2.10:8501,DRV-B=tcp://192.0.2.11:8501
 *   LABOS_RESULT_TTL_SEC   命令结果保留秒数，默认 300
 * </pre>
 */
public final class GatewayMain {

    private static final Pattern QUEUE_CMD =
            Pattern.compile("\"commandId\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern QUEUE_DEV =
            Pattern.compile("\"deviceInstanceId\"\\s*:\\s*(\\d+)");
    private static final Pattern QUEUE_METHOD =
            Pattern.compile("\"method\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern QUEUE_PARAMS =
            Pattern.compile("\"params\"\\s*:\\s*(\\{[^}]*})");
    private static final Pattern QUEUE_TIMEOUT =
            Pattern.compile("\"timeoutMs\"\\s*:\\s*(\\d+)");

    /** 每条驱动一个连接 + 一个消费线程。 */
    private static final Map<String, DriverConnection> CONNECTIONS = new LinkedHashMap<>();
    private static final AtomicBoolean RUNNING = new AtomicBoolean(true);

    public static void main(String[] args) throws Exception {
        System.out.println("=== 实验室中控 · 设备网关 v0.1 ===");

        RedisClient redis = RedisClient.fromEnv();
        if (!redis.ping()) {
            System.err.println("Redis 不可达，退出");
            System.exit(1);
        }
        System.out.println("Redis 已连接");

        Map<String, String> drivers = parseDrivers(
                System.getenv("LABOS_GATEWAY_DRIVERS"));
        if (drivers.isEmpty()) {
            System.err.println("LABOS_GATEWAY_DRIVERS 未配置，退出");
            System.err.println("示例：DRV-A=tcp://192.0.2.10:8501,DRV-B=tcp://...:8501");
            System.exit(1);
        }

        int ttl = intEnv("LABOS_RESULT_TTL_SEC", 300);

        for (Map.Entry<String, String> e : drivers.entrySet()) {
            String code = e.getKey();
            String endpoint = e.getValue();
            String hostPort = endpoint.startsWith("tcp://")
                    ? endpoint.substring(6) : endpoint;
            int idx = hostPort.lastIndexOf(':');
            String host = idx > 0 ? hostPort.substring(0, idx) : hostPort;
            int port = idx > 0 ? Integer.parseInt(hostPort.substring(idx + 1)) : 8501;

            DriverConnection conn = new DriverConnection(code, host, port, redis, ttl);
            CONNECTIONS.put(code, conn);

            Thread t = new Thread(() -> consumeLoop(code, conn, redis), "consumer-" + code);
            t.setDaemon(false);
            t.start();
            System.out.println("已启动消费者：" + code + " → " + endpoint);
        }

        // 优雅退出：把在途命令都标记失败，别让中控干等
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            RUNNING.set(false);
            CONNECTIONS.values().forEach(DriverConnection::close);
            redis.close();
            System.out.println("网关已退出");
        }));

        // 每 30 秒打一次状态，现场好判断"还活着吗"
        while (RUNNING.get()) {
            Thread.sleep(30_000);
            System.out.println("— " + java.time.LocalTime.now().withNano(0) + " —");
            CONNECTIONS.values().forEach(c -> System.out.println("  " + c.stats()));
        }
    }

    /**
     * 消费循环：一条驱动一个线程，天然串行。
     *
     * <p><b>为什么一个线程够</b>：同一台驱动上的命令必须串行（设备是物理的），
     * 多线程反而要加锁。一条连接一个线程，模型最简单。
     */
    private static void consumeLoop(String driverCode, DriverConnection conn,
                                    RedisClient redis) {
        String queueKey = "labos:cmd:queue:" + driverCode;
        while (RUNNING.get()) {
            try {
                String item = redis.rpop(queueKey);
                if (item == null) {
                    Thread.sleep(100);   // 队列空，歇一下（不阻塞连接，便于优雅退出）
                    continue;
                }
                handle(driverCode, conn, redis, item);
            } catch (Exception e) {
                System.err.println("[GW:" + driverCode + "] 消费异常：" + e.getMessage());
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static void handle(String driverCode, DriverConnection conn,
                               RedisClient redis, String item) throws IOException {
        Matcher mc = QUEUE_CMD.matcher(item);
        Matcher md = QUEUE_DEV.matcher(item);
        Matcher mm = QUEUE_METHOD.matcher(item);
        Matcher mt = QUEUE_TIMEOUT.matcher(item);
        if (!mc.find() || !md.find() || !mm.find()) {
            System.err.println("[GW:" + driverCode + "] 命令格式不对，丢弃：" + item);
            return;
        }
        String commandId = mc.group(1);
        long deviceId = Long.parseLong(md.group(1));
        String method = mm.group(1);
        long timeout = mt.find() ? Long.parseLong(mt.group(1)) : 15_000L;

        Matcher mp = QUEUE_PARAMS.matcher(item);
        String paramsJson = mp.find() ? mp.group(1) : "{}";

        // 记在途：网关崩溃重启后能知道"发过但没结果"
        redis.hset("labos:cmd:pending:" + driverCode, commandId,
                String.valueOf(System.currentTimeMillis()));

        conn.execute(commandId, deviceId, method, paramsJson, timeout);
    }

    private static Map<String, String> parseDrivers(String expr) {
        Map<String, String> m = new LinkedHashMap<>();
        if (expr == null || expr.isBlank()) {
            return m;
        }
        for (String pair : expr.split(",")) {
            int i = pair.indexOf('=');
            if (i <= 0) {
                continue;
            }
            m.put(pair.substring(0, i).trim(), pair.substring(i + 1).trim());
        }
        return m;
    }

    private static int intEnv(String key, int def) {
        String v = System.getenv(key);
        try {
            return v == null ? def : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
