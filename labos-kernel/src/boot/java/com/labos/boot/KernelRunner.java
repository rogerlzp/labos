package com.labos.boot;

import com.labos.kernel.api.ApiServer;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 容器就绪后启动内核（A 批：唯一启动入口）。
 *
 * <p>{@code ApiServer.main()} 已删除，启动收尾（崩溃孤儿 settle、命令账本对账、
 * 物理未知隔离、queued rehydrate 等）整体迁入 {@code ApiServer.runKernel()}，
 * 由这里调用 —— 逻辑一行不丢，只是不再允许绕过容器直接起 JVM。
 *
 * <p>{@code labos.kernel.http.enabled=false} 供事务自测使用：只起容器与事务，
 * 不占 18080 端口。
 */
@Component
public class KernelRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KernelRunner.class);

    private final ApiServer api;

    public KernelRunner(ApiServer api) {
        this.api = api;
    }

    @Override
    public void run(ApplicationArguments args) {
        if ("false".equalsIgnoreCase(System.getProperty("labos.kernel.http.enabled", "true"))) {
            log.info("容器已就绪（HTTP 内核按配置不启动）");
            return;
        }
        Thread t = new Thread(() -> {
            try {
                api.runKernel();
            } catch (Exception e) {
                log.error("内核启动失败", e);
            }
        }, "labos-http-kernel");
        t.setDaemon(false);
        t.start();
        log.info("容器已就绪，内核在 {} 线程启动", t.getName());
    }
}
