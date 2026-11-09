package com.labos.boot.web.controller;

import com.labos.boot.web.service.RunService;
import com.labos.kernel.api.QueryService;
import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.Jsons;
import com.labos.kernel.workflow.ExperimentScheduler;
import com.labos.kernel.workflow.SchedulerEta;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.labos.kernel.api.ApiServer.err;

import java.sql.SQLException;

/**
 * 实验域**只读**端点（11 个）——纯 HTTP 层。
 *
 * <p>路径注意：{@code /api/experiments/runs/{runId}/steps} 的 runId 是
 * <b>experiment_run.id</b>；而 {@code /api/experiments/{id}/...} 里的 id 是
 * experiment.id —— 两者不是同一个 id 空间（experiment_run 的外键指向 run 表）。
 * {@code {id:\d+}} 限定避免 {@code /experiments/eta}、{@code /reorder} 被
 * 当成 id 匹配。
 */
@RestController
@RequestMapping("/api")
public class ExperimentQueryController {

    private static final long TENANT = 1L;

    private final QueryService query;
    private final DbConfig db;
    private final ExperimentScheduler scheduler;
    private final RunService runService;

    public ExperimentQueryController(QueryService query, DbConfig db,
                                     ExperimentScheduler scheduler, RunService runService) {
        this.query = query;
        this.db = db;
        this.scheduler = scheduler;
        this.runService = runService;
    }

    /** 原路由对 IllegalArgumentException 统一 400（如「实验执行不存在」）。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err(e.getMessage()));
    }

    @GetMapping(value = "/experiments/{id:\\d+}/graph",
            produces = "application/json; charset=utf-8")
    public String graph(@PathVariable long id) throws SQLException {
        return query.experimentGraphJson(id);
    }

    @GetMapping(value = "/handoffs", produces = "application/json; charset=utf-8")
    public String handoffs(@RequestParam(required = false) String status,
                           @RequestParam(required = false) String limit) throws SQLException {
        return query.handoffsJson(status, intOr(limit, 100));
    }

    @GetMapping(value = "/experiments/{id:\\d+}/handoffs",
            produces = "application/json; charset=utf-8")
    public String experimentHandoffs(@PathVariable long id) throws SQLException {
        return query.experimentHandoffsJson(id);
    }

    @GetMapping(value = "/experiments/{id:\\d+}/workcells",
            produces = "application/json; charset=utf-8")
    public String workcells(@PathVariable long id) throws SQLException {
        return query.experimentWorkcellsJson(id);
    }

    /** 一次实验执行的逐步明细（含"未执行"的步骤）。 */
    @GetMapping(value = "/experiments/runs/{id:\\d+}/steps",
            produces = "application/json; charset=utf-8")
    public String runSteps(@PathVariable long id) throws SQLException {
        return query.experimentRunStepsJson(id);
    }

    @GetMapping(value = "/experiments/{id:\\d+}/runs",
            produces = "application/json; charset=utf-8")
    public String experimentRuns(@PathVariable long id) throws SQLException {
        return query.experimentRunsJson(id);
    }

    @GetMapping(value = "/experiments/{id:\\d+}/batches",
            produces = "application/json; charset=utf-8")
    public String batches(@PathVariable long id) throws SQLException {
        return runService.batchesJson(id);
    }

    @GetMapping(value = "/experiments/{id:\\d+}/variables",
            produces = "application/json; charset=utf-8")
    public String variables(@PathVariable long id) throws SQLException {
        return query.experimentVariablesJson(id);
    }

    @GetMapping(value = "/experiments/{id:\\d+}/processes",
            produces = "application/json; charset=utf-8")
    public String processes(@PathVariable long id) throws SQLException {
        return query.experimentProcessesJson(id);
    }

    @GetMapping(value = "/experiments", produces = "application/json; charset=utf-8")
    public String list() throws SQLException {
        return query.experimentsJson(100);
    }

    @GetMapping(value = "/experiments/eta", produces = "application/json; charset=utf-8")
    public String eta(@RequestParam(required = false) String limit) throws SQLException {
        // §7.2（2026-11-03）：预计等待。limit 默认 100、上限 500（与列表页同上限）。
        // 队列/岛锁取调度器内存快照（库里 experiment_run.status 是回写的、有延迟），
        // 估算本身是纯函数（SchedulerEta.estimateWait）。
        int n = Math.max(1, Math.min(intOr(limit, 100), 500));
        ExperimentScheduler.Snapshot snap = scheduler.snapshot();
        return SchedulerEta.experimentsEtaJson(db, TENANT, n,
                snap.queue(), snap.islandLocks());
    }

    private static int intOr(String raw, int def) {
        if (raw == null || raw.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
