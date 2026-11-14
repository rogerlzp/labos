package com.labos.boot.web.controller;

import com.labos.kernel.persistence.Jsons;
import com.labos.kernel.workflow.ExperimentScheduler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 从 {@code SchedulerApi} 拆出的注解式 Controller（1 个端点）。
 *
 * <p>响应结构与原实现逐字一致（§7.1 的 queue 明细 / 岛锁主体 / workers 快照），
 * 三个 JSON 辅助方法原样搬过来，避免两套格式漂移。
 */
@RestController
public class SchedulerController {

    private final ExperimentScheduler scheduler;

    public SchedulerController(ExperimentScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @GetMapping(value = "/api/scheduler/status", produces = "application/json; charset=utf-8")
    public String status() {
        // §7.1（2026-11-03）：在计数字段上补 queue 明细 / 岛锁主体 / workers。
        // 快照只拍一次 —— 与 /api/experiments/eta 共用同一份内存态口径。
        ExperimentScheduler.Snapshot st = scheduler.snapshot();
        return Jsons.obj(
                "running", st.running(),
                "queued", st.queued(),
                "maxConcurrent", st.maxConcurrent(),
                "stalled", st.stalled(),
                "started", st.started(),
                "loopStarted", st.loopStarted(),
                "lastDispatchAt", st.lastDispatchAt(),
                // 2026-11-10：调度粒度改成资源级 ⇒ islandLocks 改名 resourceLocks
                "resourceLocks", new Jsons.Raw(locksJson(st.resourceLocks())),
                "queue", new Jsons.Raw(queueJson(st.queue())),
                "workers", new Jsons.Raw(workersJson(st)));
    }

    /** 资源占用列表转 JSON 数组（§7.1：补 resourceCode / experimentId / experimentNo）。 */
    private static String locksJson(List<ExperimentScheduler.LockEntry> locks) {
        if (locks == null) {
            return "[]";
        }
        List<String> items = new ArrayList<>();
        for (ExperimentScheduler.LockEntry l : locks) {
            items.add(Jsons.obj(
                    "resourceId", l.resourceId(),
                    "resourceCode", l.resourceCode(),
                    "resourceName", l.resourceName(),
                    "experimentRunId", l.experimentRunId(),
                    "runId", l.experimentRunId(),
                    "experimentId", l.experimentId(),
                    "experimentNo", l.experimentNo(),
                    "since", l.since(),
                    "expiresAt", l.expiresAt()));
        }
        return Jsons.arr(items);
    }

    /** 排队明细转 JSON 数组（§7.1：按派发口径排好序、position 从 1 起）。 */
    private static String queueJson(List<ExperimentScheduler.QueueEntry> queue) {
        if (queue == null) {
            return "[]";
        }
        List<String> items = new ArrayList<>();
        for (ExperimentScheduler.QueueEntry q : queue) {
            items.add(Jsons.obj(
                    "position", q.position(),
                    "experimentId", q.experimentId(),
                    "experimentNo", q.experimentNo(),
                    "experimentRunId", q.experimentRunId(),
                    "islandId", q.islandId() <= 0 ? null : q.islandId(),
                    "islandCode", q.islandCode(),
                    "priority", q.priority(),
                    "deadline", q.deadline(),
                    "submittedAt", q.submittedAt()));
        }
        return Jsons.arr(items);
    }

    /** 线程池快照（§7.1：回答"是池满了还是锁没释放"）。 */
    private static String workersJson(ExperimentScheduler.Snapshot st) {
        return Jsons.obj(
                "pool", st.maxConcurrent(),
                "active", st.running(),
                "queue", 0);
    }

    /** P2-5 死锁/饥饿检测（阈值可传，默认 30 分钟）。 */
    @GetMapping(value = "/api/scheduler/deadlock-check", produces = "application/json; charset=utf-8")
    public String deadlockCheck(@RequestParam(defaultValue = "30") long heldMinutes,
                                @RequestParam(defaultValue = "30") long waitMinutes) {
        java.time.Duration held = java.time.Duration.ofMinutes(Math.max(heldMinutes, 1));
        java.time.Duration wait = java.time.Duration.ofMinutes(Math.max(waitMinutes, 1));
        List<ExperimentScheduler.DeadlockRisk> risks = scheduler.deadlockRisks(held, wait);
        List<String> items = new ArrayList<>();
        for (ExperimentScheduler.DeadlockRisk r : risks) {
            items.add(Jsons.obj(
                    "kind", r.kind(),
                    "resourceId", r.resourceId(),
                    "resourceCode", r.resourceCode(),
                    "experimentRunId", r.experimentRunId(),
                    "since", r.since(),
                    "message", r.message()));
        }
        return Jsons.obj("total", risks.size(),
                "heldThresholdMinutes", held.toMinutes(),
                "waitThresholdMinutes", wait.toMinutes(),
                "risks", new Jsons.Raw(Jsons.arr(items)));
    }

    /** P2-4 当前排程参数（消费 schedule_plan 的结果）。 */
    @GetMapping(value = "/api/scheduler/config", produces = "application/json; charset=utf-8")
    public String config() {
        java.util.Map<String, Object> c = scheduler.config();
        return Jsons.obj("maxConcurrent", c.get("maxConcurrent"),
                "optimizationFactor", c.get("optimizationFactor"),
                "tickMs", c.get("tickMs"));
    }

    /** P2-4 手动重载排程参数（绕过 60s 自动重载，建/改 schedule_plan 后立即生效）。 */
    @PostMapping(value = "/api/scheduler/reload-config", produces = "application/json; charset=utf-8")
    public String reloadConfig() {
        scheduler.reloadConfig();
        java.util.Map<String, Object> c = scheduler.config();
        return Jsons.obj("reloaded", true,
                "maxConcurrent", c.get("maxConcurrent"),
                "optimizationFactor", c.get("optimizationFactor"),
                "tickMs", c.get("tickMs"));
    }

    /** P2 暂停堆积治理：列出暂停超过 N 天的 run（默认 7 天，不自动处置）。 */
    @GetMapping(value = "/api/scheduler/stale-pauses", produces = "application/json; charset=utf-8")
    public String stalePauses(@RequestParam(defaultValue = "7") int days) {
        java.util.List<java.util.Map<String, Object>> stale = scheduler.stalePausedRuns(days);
        java.util.List<String> items = new ArrayList<>();
        for (java.util.Map<String, Object> m : stale) {
            items.add(Jsons.obj(
                    "runId", m.get("runId"), "runNo", m.get("runNo"),
                    "pauseKind", m.get("pauseKind"), "pausedAt", m.get("pausedAt"),
                    "pauseNodeKey", m.get("pauseNodeKey"), "nodeName", m.get("nodeName")));
        }
        return Jsons.obj("total", stale.size(), "days", days,
                "items", new Jsons.Raw(Jsons.arr(items)));
    }
}
