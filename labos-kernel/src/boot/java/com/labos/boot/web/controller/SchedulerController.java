package com.labos.boot.web.controller;

import com.labos.kernel.persistence.Jsons;
import com.labos.kernel.workflow.ExperimentScheduler;
import org.springframework.web.bind.annotation.GetMapping;
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
                "islandLocks", new Jsons.Raw(locksJson(st.islandLocks())),
                "queue", new Jsons.Raw(queueJson(st.queue())),
                "workers", new Jsons.Raw(workersJson(st)));
    }

    /** 岛锁列表转 JSON 数组（§7.1：补 islandCode / experimentId / experimentNo）。 */
    private static String locksJson(List<ExperimentScheduler.LockEntry> locks) {
        if (locks == null) {
            return "[]";
        }
        List<String> items = new ArrayList<>();
        for (ExperimentScheduler.LockEntry l : locks) {
            items.add(Jsons.obj(
                    "islandId", l.islandId(),
                    "islandCode", l.islandCode(),
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
}
