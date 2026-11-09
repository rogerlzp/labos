package com.labos.boot;

import com.labos.kernel.api.ApiServer;
import com.labos.kernel.persistence.DbConfig;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 容器就绪后拉起原有内核。
 *
 * <p>直接调用 {@code ApiServer.main} 是为了**零遗漏**：启动收尾包含崩溃孤儿
 * settle、experiment_run 孤儿兜底、命令账本对账、物理未知隔离、queued rehydrate
 * 等一长串逻辑，复制一份迟早会漂。独立线程是因为 main 末尾有
 * {@code Thread.currentThread().join()} 会永久阻塞。
 *
 * <p>{@code labos.kernel.http.enabled=false} 用于自测：只起 Spring 容器与事务，
 * 不占 18080 端口、不拉 HTTP 服务。
 */
@Component
public class KernelRunner implements ApplicationRunner {

    private final DbConfig dbConfig;   // 依赖它 = 保证 DataSource 已绑定

    public KernelRunner(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    @Override
    public void run(ApplicationArguments args) {
        if ("false".equalsIgnoreCase(System.getProperty("labos.kernel.http.enabled", "true"))) {
            System.out.println("[Spring Boot] 容器已就绪（HTTP 内核按配置不启动）；"
                    + "DataSource=" + DbConfig.boundDataSource());
            return;
        }
        String port = System.getenv().getOrDefault("LABOS_API_PORT", "18080");
        Thread t = new Thread(() -> {
            try {
                ApiServer.main(new String[]{port});
            } catch (Exception e) {
                System.err.println("[Spring Boot] 内核启动失败：" + e);
                e.printStackTrace();
            }
        }, "labos-http-kernel");
        t.setDaemon(false);
        t.start();
        System.out.println("[Spring Boot] 容器已就绪，内核在 " + t.getName()
                + " 线程启动（端口 " + port + "），DataSource=" + dbConfig);
    }
}
