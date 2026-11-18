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
        logEffectiveSwitches();
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

    /**
     * 启动时回显三个关键开关的**生效值**（2026-11-18）。
     *
     * <p>为什么放在内核里、而不是只靠 {@code scripts/start-kernel.sh}：启动脚本只是**一条**启动路径，
     * 容器编排 / IDE / 手工 {@code java} 启动都可能绕过它。而搬运保护没开时的症状是
     * <b>run 照样 completed，只是位置锁、搬运事务、轨道互锁、扫码证据确认全没有</b>——
     * 不回显就等于静默降级（2026-11-18 就因此查了半天才定位）。
     *
     * <p>三者的「怎么关」语义不一样，别照抄：
     * <ul>
     *   <li>{@code LABOS_TRANSFER_PLAN}：内核判据是 {@code getenv() != null} ⇒ <b>非 null 即开</b>
     *       （置 {@code 0}、空串都算开）；</li>
     *   <li>{@code LABOS_EXEC_GRAPH} / {@code LABOS_AUTH}：判据是 {@code !"0".equals(getenv())}
     *       ⇒ <b>{@code =0} 才算关</b>。</li>
     * </ul>
     */
    private void logEffectiveSwitches() {
        String planRaw = System.getenv("LABOS_TRANSFER_PLAN");
        String graphRaw = System.getenv("LABOS_EXEC_GRAPH");
        String authRaw = System.getenv("LABOS_AUTH");
        boolean planOn = com.labos.kernel.driver.DriverStepExecutor.transferProtectionEnabled();
        boolean graphOn = !"0".equals(graphRaw);
        boolean authOn = !"0".equals(authRaw);
        log.info("[启动开关] 搬运保护={}（raw={}）｜ 执行图={}（raw={}）｜ API鉴权={}（raw={}）",
                planOn ? "开" : "关", planRaw == null ? "<未设置>" : planRaw,
                graphOn ? "开" : "关", graphRaw == null ? "<未设置>" : graphRaw,
                authOn ? "开" : "关", authRaw == null ? "<未设置>" : authRaw);
        if (!planOn) {
            log.warn("[启动开关] ⚠ 搬运保护处于【关闭】：位置锁、搬运事务(transfer_transaction)、"
                    + "轨道分区互锁、扫码证据确认 全部不生效；搬运仍会执行、run 仍会 completed。"
                    + "开启方式：export LABOS_TRANSFER_PLAN=1 后重启内核"
                    + "（scripts/start-kernel.sh 已默认带上）。");
            log.warn("[启动开关] ⚠ 本次搬运在 interlock_decision 里会留 SKIP 决策（判据见 "
                    + "DriverStepExecutor#transferProtectionEnabled），可据此分辨「判过」与「没判」。");
        }
    }
}