package com.labos.boot.web.controller;

import com.labos.boot.web.ApiAuthInterceptor;
import com.labos.boot.web.AuditRecorder;
import com.labos.boot.web.service.ExperimentActionService;
import com.labos.boot.web.service.PauseCoordinatorFactory;
import com.labos.boot.web.service.RunService;
import com.labos.kernel.api.ApiServer;
import com.labos.kernel.api.AuthService;
import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.Jsons;
import com.labos.kernel.workflow.BatchOrchestrator;
import com.labos.kernel.workflow.ExperimentFlowRunner;
import com.labos.kernel.workflow.ExperimentScheduler;
import com.labos.kernel.workflow.PauseCommand;
import com.labos.kernel.workflow.PauseCoordinator;
import com.labos.kernel.workflow.WorkflowService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.labos.kernel.api.ApiServer.err;
import static com.labos.kernel.api.ApiServer.jsonField;
import static com.labos.kernel.api.ApiServer.jsonInt;
import static com.labos.kernel.api.ApiServer.jsonLong;
import static com.labos.kernel.api.ApiServer.paramLong;
import static com.labos.kernel.api.ApiServer.paramStr;
import static com.labos.kernel.api.ApiServer.parseVarsExpr;

/**
 * 实验域**写动作**端点（13 个）——纯 HTTP 层。
 *
 * <p>执行控制（pause/stop/resume）统一走 {@link PauseCoordinator}：这里只解析
 * HTTP 参数，路由到既有 RunControl + RuntimeHold 的语义不在此处重写。
 * 回"实际生效"的作用域：未分配岛时 island 会被后端退化成 experiment，
 * 回请求值会让界面说"作用域：岛"而库里只有一道实验级扣留。
 *
 * <p>整实验执行（{@code POST /experiments/{id}/run}）的 async/sync、
 * 分批/不分批四种响应形状与原实现逐字一致（回归断言见 BatchDbSelfTest）。
 */
@RestController
@RequestMapping("/api")
public class ExperimentActionController {

    private static final long TENANT = 1L;

    private final DbConfig db;
    private final WorkflowService workflows;
    private final ExperimentScheduler scheduler;
    private final AuditRecorder audit;
    private final RunService runService;
    private final PauseCoordinatorFactory pauseFactory;
    private final ExperimentActionService experimentActionService;

    public ExperimentActionController(DbConfig db,
                                      WorkflowService workflows, ExperimentScheduler scheduler,
                                      AuditRecorder audit, RunService runService,
                                      PauseCoordinatorFactory pauseFactory,
                                      ExperimentActionService experimentActionService) {
        this.db = db;
        this.workflows = workflows;
        this.scheduler = scheduler;
        this.audit = audit;
        this.runService = runService;
        this.pauseFactory = pauseFactory;
        this.experimentActionService = experimentActionService;
    }

    // ---------------------------------------------------------------- 流程直跑（演示/调试）

    @PostMapping(value = "/experiments/flow-run", produces = "application/json; charset=utf-8")
    /** 原路由对 IllegalArgumentException 统一 400（batchSplit 负数、实验运行不存在等）。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err(e.getMessage()));
    }

    public ResponseEntity<String> flowRun(HttpServletRequest req) throws SQLException {
        String qs = req.getQueryString();
        // steps 里的 = : , 都会被 URL 编码，先解码再解析
        String stepsRaw = paramStr(qs, "steps");
        String stepsExpr = stepsRaw == null ? null
                : URLDecoder.decode(stepsRaw, StandardCharsets.UTF_8);
        if (stepsExpr == null || stepsExpr.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(err("需要 steps，格式 id:method[:output]，逗号分隔"));
        }
        List<ExperimentFlowRunner.Step> steps = new ArrayList<>();
        // 每段：id:method[:output[:k=v;k2=v2]]
        for (String part : stepsExpr.split(",")) {
            String[] f = part.split(":", -1);
            if (f.length < 2 || f[0].isBlank() || f[1].isBlank()) {
                continue;
            }
            steps.add(new ExperimentFlowRunner.Step(
                    Long.parseLong(f[0].trim()),
                    f[1].trim(),
                    f.length > 2 && !f[2].isBlank() ? f[2].trim() : null,
                    f.length > 3 && !f[3].isBlank() ? f[3].trim() : null));
        }
        if (steps.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err("steps 解析后为空"));
        }
        ExperimentFlowRunner runner = new ExperimentFlowRunner(db, TENANT, workflows);
        ExperimentFlowRunner.Result r = runner.run(paramStr(qs, "name"),
                steps, parseVarsExpr(paramStr(qs, "vars")));
        audit.record(session(req), "experiment.flowrun", "experiment", null,
                "run#" + r.runId(), Jsons.obj("status", r.status(), "steps", r.total()),
                req.getRemoteAddr());
        return ResponseEntity.status(r.status().equals("completed") ? 200 : 400)
                .body(Jsons.obj(
                        "runId", r.runId(),
                        "status", r.status(),
                        "completed", r.completed(),
                        "total", r.total(),
                        "error", r.error(),
                        "vars", new Jsons.Raw(varsToJson(r.vars()))));
    }

    // ---------------------------------------------------------------- 交接

    @PostMapping(value = "/experiments/{id:\\d+}/handoffs/confirm",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> confirmHandoff(
            @PathVariable long id,
            @RequestBody(required = false) String body,
            HttpServletRequest req) throws SQLException {
        Long from = jsonLong(body, "fromWorkcellId");
        Long to = jsonLong(body, "toWorkcellId");
        if (from == null || to == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(err("需要 fromWorkcellId / toWorkcellId"));
        }
        String op = operator(req);
        int n = experimentActionService.confirmHandoff(id, from, to, op, jsonField(body, "note"));
        if (n == 0) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(err("Handoff 不存在或已确认"));
        }
        audit.record(session(req), "experiment.handoff.confirm", "experiment", id,
                from + "->" + to, null, req.getRemoteAddr());
        return ResponseEntity.ok(Jsons.obj("experimentId", id,
                "fromWorkcellId", from, "toWorkcellId", to, "status", "confirmed"));
    }

    @PutMapping(value = "/experiments/{id:\\d+}/handoffs",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> setHandoffs(
            @PathVariable long id,
            @RequestBody(required = false) String body,
            HttpServletRequest req) throws SQLException {
        List<long[]> pairs = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\\{[^{}]*\"fromWorkcellId\"\\s*:\\s*(\\d+)[^{}]*\"toWorkcellId\"\\s*:\\s*(\\d+)[^{}]*\\}")
                .matcher(body);
        while (m.find()) {
            pairs.add(new long[]{Long.parseLong(m.group(1)), Long.parseLong(m.group(2))});
        }
        int n = experimentActionService.setExperimentHandoffs(id, pairs);
        if (n == -1) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(err("实验不存在"));
        }
        if (n == -2) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(err("Handoff 起点/终点无效或不在实验 Workcell 绑定内"));
        }
        audit.record(session(req), "experiment.handoffs.set", "experiment", id,
                "pairs=" + pairs.size(), Jsons.obj("count", n), req.getRemoteAddr());
        return ResponseEntity.ok(Jsons.obj("experimentId", id, "updated", n));
    }

    @PostMapping(value = "/experiments/{id:\\d+}/handoffs",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> setHandoffsPost(
            @PathVariable long id,
            @RequestBody(required = false) String body,
            HttpServletRequest req) throws SQLException {
        return setHandoffs(id, body, req);
    }

    // ---------------------------------------------------------------- Workcell 绑定

    @PutMapping(value = "/experiments/{id:\\d+}/workcells",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> setWorkcells(
            @PathVariable long id,
            @RequestBody(required = false) String body,
            HttpServletRequest req) throws SQLException {
        java.util.regex.Matcher arr = java.util.regex.Pattern
                .compile("\"workcellIds\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(body);
        if (!arr.find()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(err("需要 workcellIds 数组（空数组=清空绑定）"));
        }
        List<Long> ids = new ArrayList<>();
        for (String part : arr.group(1).split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                ids.add(Long.parseLong(t));
            }
        }
        int n = experimentActionService.setExperimentWorkcells(id, ids);
        if (n < 0) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(err("实验不存在"));
        }
        audit.record(session(req), "experiment.workcells.set", "experiment", id,
                ids.toString(), Jsons.obj("count", n), req.getRemoteAddr());
        return ResponseEntity.ok(Jsons.obj("experimentId", id, "updated", n));
    }

    @PostMapping(value = "/experiments/{id:\\d+}/workcells",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> setWorkcellsPost(
            @PathVariable long id,
            @RequestBody(required = false) String body,
            HttpServletRequest req) throws SQLException {
        return setWorkcells(id, body, req);
    }

    // ---------------------------------------------------------------- 编排图

    @PutMapping(value = "/experiments/{id:\\d+}/graph",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> saveGraph(
            @PathVariable long id,
            @RequestBody(required = false) String body,
            HttpServletRequest req) throws SQLException {
        int n = experimentActionService.saveExperimentGraph(id, body);
        audit.record(session(req), "experiment.graph", "experiment", id, null,
                Jsons.obj("bytes", body == null ? 0 : body.length(),
                        "compiledProcesses", n), req.getRemoteAddr());
        return ResponseEntity.ok(Jsons.obj("saved", 1,
                "bytes", body == null ? 0 : body.length(), "compiledProcesses", n));
    }

    @PostMapping(value = "/experiments/{id:\\d+}/graph",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> saveGraphPost(
            @PathVariable long id,
            @RequestBody(required = false) String body,
            HttpServletRequest req) throws SQLException {
        return saveGraph(id, body, req);
    }

    // ---------------------------------------------------------------- 运行控制

    @PostMapping(value = "/experiments/runs/{id:\\d+}/{action:(?:pause|stop|resume)}",
            produces = "application/json; charset=utf-8")
    public String runAction(@PathVariable long id, @PathVariable String action,
                            HttpServletRequest req,
                            @RequestHeader(value = "X-Request-Id", required = false) String requestId)
            throws SQLException {
        String qs = req.getQueryString();
        // 统一走 PauseCoordinator（S-02 / P-06）：这里只解析 HTTP 参数，
        // 路由到既有 RunControl + RuntimeHold 的暂停/停止/恢复语义不在此处重写。
        PauseCoordinator coord = pauseFactory.forTenant(TENANT);

        if ("pause".equals(action)) {
            PauseCoordinator.ExperimentPause r = coord.pauseExperimentRun(
                    PauseCommand.experiment(id, PauseCommand.Action.PAUSE,
                            paramStr(qs, "kind"), paramStr(qs, "reason"), requestId),
                    paramStr(qs, "scope"));
            audit.record(session(req), "experiment.pause", "experiment", r.experimentId(),
                    "run#" + id + " scope=" + r.scopeCode(), null, req.getRemoteAddr());
            return Jsons.obj(
                    "runId", r.runId(),
                    "experimentId", r.experimentId(),
                    "status", r.status(),
                    "controlState", r.controlState(),
                    "scope", r.scopeCode(),
                    "kind", r.kindCode());
        }

        if ("stop".equals(action)) {
            PauseCoordinator.ExperimentStop r = coord.stopExperimentRun(
                    PauseCommand.experiment(id, PauseCommand.Action.STOP, null, null, requestId));
            audit.record(session(req), "experiment.stop", "experiment", r.experimentId(),
                    "run#" + id, null, req.getRemoteAddr());
            return Jsons.obj(
                    "runId", r.runId(),
                    "experimentId", r.experimentId(),
                    "changed", r.changed(),
                    "status", r.status());
        }

        // resume
        PauseCoordinator.ExperimentResume r = coord.resumeExperimentRun(
                PauseCommand.experiment(id, PauseCommand.Action.RESUME, null, null, requestId),
                paramStr(qs, "note"));
        audit.record(session(req), "experiment.resume", "experiment", r.experimentId(),
                "run#" + id, null, req.getRemoteAddr());
        return Jsons.obj(
                "runId", r.runId(),
                "experimentId", r.experimentId(),
                "clearedHold", r.clearedHold(),
                "requeued", r.requeued(),
                "stillHeldReason", r.stillHeldReason(),
                "status", r.status());
    }

    // ---------------------------------------------------------------- 循环 / 分批 / 执行

    @PutMapping(value = "/experiments/{id:\\d+}/processes/{seq:\\d+}/loop",
            produces = "application/json; charset=utf-8")
    public String setLoop(@PathVariable long id, @PathVariable int seq,
                          @RequestParam(required = false) String count) throws SQLException {
        int n = experimentActionService.setLoopCount(id, seq, count);
        return Jsons.obj("updated", n, "seq", seq, "loopCount", count);
    }

    @PutMapping(value = "/experiments/{id:\\d+}/batch-split",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> setBatchSplit(
            @PathVariable long id,
            @RequestBody(required = false) String body,
            HttpServletRequest req) throws SQLException {
        Integer split = batchSplitFromBody(body);
        // D11：>0 或 null；已有批次行时改值 -> IllegalArgumentException（400）
        Integer saved = new BatchOrchestrator(db, TENANT, workflows).setBatchSplit(id, split);
        audit.record(session(req), "experiment.batch_split", "experiment", id, null,
                Jsons.obj("batchSplit", saved), req.getRemoteAddr());
        return ResponseEntity.ok(Jsons.obj(
                "experimentId", id, "batchSplit", saved, "updated", true));
    }

    @PostMapping(value = "/experiments/{id:\\d+}/batches/{batchNo:\\d+}/run",
            produces = "application/json; charset=utf-8")
    public String runBatch(
            @PathVariable long id,
            @PathVariable int batchNo,
            @RequestBody(required = false) String body,
            HttpServletRequest req) throws SQLException {
        Map<String, String> varsB = experimentRunVars(body);
        // D4：只跑这一批，新 attempt，历史尝试保留；已完成/不存在 -> 400
        BatchOrchestrator.BatchResult rb = new BatchOrchestrator(db, TENANT, workflows)
                .runBatch(id, batchNo, varsB);
        audit.record(session(req), "experiment.batch_run", "experiment", id,
                "batch#" + batchNo,
                Jsons.obj("status", rb.status(), "runId", rb.runId(),
                        "attemptNo", rb.attemptNo()), req.getRemoteAddr());
        return Jsons.obj(
                "experimentId", id,
                "batchNo", rb.batchNo(),
                "runId", rb.runId(),
                "batchRunId", rb.batchRunId(),
                "attemptNo", rb.attemptNo(),
                "status", rb.status(),
                "doneSteps", rb.doneSteps(),
                "totalSteps", rb.totalSteps(),
                "error", rb.error());
    }

    @PostMapping(value = "/experiments/{id:\\d+}/run",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> runExperiment(
            @PathVariable long id,
            @RequestBody(required = false) String body,
            HttpServletRequest req) throws SQLException {
        Map<String, String> overrides = experimentRunVars(body);
        BatchOrchestrator orchestrator = new BatchOrchestrator(db, TENANT, workflows);
        Integer batchSplit = orchestrator.batchSplit(id);
        // async=1 交给调度器（岛级互斥 + 排队）；默认同步执行
        if (paramLong(req.getQueryString(), "async") == 1L) {
            if (batchSplit == null) {
                // S3.2-C：async 请求体的 vars 落 experiment_run.variables，
                // 调度器执行时作为用户 overrides（响应形状不变）
                long erid = scheduler.submit(id, overrides);
                audit.record(session(req), "experiment.submit", "experiment", id,
                        "run#" + erid, null, req.getRemoteAddr());
                return ResponseEntity.ok(Jsons.obj(
                        "experimentRunId", erid, "status", "queued", "async", true));
            }
            // 分批 + async：调度器队列的单位是"一条 run"，不认"整单多批"，
            // 所以改为**按批派发** —— 首次调用建/取第 1 批的 queued run 并入队；
            // 每批跑完后由 ExperimentScheduler.executeOne 续建下一批
            // （失败继续 = D3、aborted/paused 不续、扣留停在批边界）。
            // 响应保留 experimentRunId，追加 batchNo/async（前端只需看前两个字段）。
            BatchOrchestrator.NextBatchAttempt next =
                    orchestrator.createNextBatchAttempt(id, 0, overrides);
            switch (next.outcome()) {
                case HELD -> {
                    // 批边界被现场扣留：不建尝试，候选批已标 paused、实验聚合 paused。
                    // 解除扣留后由调度器每 5 个 tick 自动续队（S3.2-B），
                    // 也可以 POST /run?async=1 立即从该批继续。
                    audit.record(session(req), "experiment.submit", "experiment", id,
                            "held batch#" + next.batchNo(), null, req.getRemoteAddr());
                    return ResponseEntity.ok(Jsons.obj(
                            "experimentId", id,
                            "experimentRunId", null,
                            "async", true,
                            "held", true,
                            "status", "paused",
                            "batchNo", next.batchNo(),
                            "holdReason", next.holdReason()));
                }
                case NO_MORE -> {
                    // 所有批次都已成功：没有可派发的批次（等同同步 runAll 的空跑）
                    audit.record(session(req), "experiment.submit", "experiment", id,
                            "no-more-batch", null, req.getRemoteAddr());
                    return ResponseEntity.ok(Jsons.obj(
                            "experimentId", id,
                            "experimentRunId", null,
                            "async", true,
                            "status", next.status() == null ? "finished" : next.status(),
                            "batchNo", null));
                }
                case NOT_BATCHED -> throw new IllegalArgumentException(
                        "实验已配置分批但取不到切批计划（batch_split 可能刚被清空），请重试");
                default -> {
                    // CREATED（新建 queued 批次 run）/ EXISTING（已有未结束批次 run）
                    if (next.queueable()) {
                        scheduler.resumeRun(id, next.runId());
                    }
                    audit.record(session(req), "experiment.submit", "experiment", id,
                            "run#" + next.runId() + " batch#" + next.batchNo(),
                            Jsons.obj("attemptNo", next.attemptNo(),
                                    "batchStatus", next.status()), req.getRemoteAddr());
                    return ResponseEntity.ok(Jsons.obj(
                            "experimentId", id,
                            "experimentRunId", next.runId(),
                            "batchNo", next.batchNo(),
                            "batchRunId", next.batchRunId(),
                            "attemptNo", next.attemptNo(),
                            "status", next.status(),
                            "async", true));
                }
            }
        }

        // D1：batch_split 为空 -> runAll 内部原样走非分批路径（一次 run 跑整单）；
        //     非空 -> 按批串行跑所有未成功批，响应追加 batchSplit + batches。
        BatchOrchestrator.RunAllResult r = orchestrator.runAll(id, overrides);
        audit.record(session(req), "experiment.run", "experiment", id,
                r.batched() ? "batches#" + r.batches().size() : "run#" + r.experimentRunId(),
                Jsons.obj("status", r.status(), "steps", r.totalSteps(),
                        "batched", r.batched()), req.getRemoteAddr());
        if (!r.batched()) {
            // 不分批：响应形状与改动前完全一致（回归断言见 BatchDbSelfTest）
            return ResponseEntity.ok(Jsons.obj(
                    "experimentRunId", r.experimentRunId(),
                    "status", r.status(),
                    "totalSteps", r.totalSteps(),
                    "doneSteps", r.doneSteps(),
                    "error", r.error()));
        }
        List<String> batchItems = new ArrayList<>();
        for (BatchOrchestrator.BatchResult b : r.batches()) {
            batchItems.add(Jsons.obj(
                    "batchNo", b.batchNo(),
                    "runId", b.runId(),
                    "batchRunId", b.batchRunId(),
                    "attemptNo", b.attemptNo(),
                    "status", b.status(),
                    "doneSteps", b.doneSteps(),
                    "totalSteps", b.totalSteps()));
        }
        return ResponseEntity.ok(Jsons.obj(
                "experimentRunId", r.experimentRunId() == 0L ? null : r.experimentRunId(),
                "status", r.status(),
                "totalSteps", r.totalSteps(),
                "doneSteps", r.doneSteps(),
                "error", r.error(),
                "batchSplit", r.batchSplit(),
                "batches", new Jsons.Raw(Jsons.arr(batchItems))));
    }

    // ---------------------------------------------------------------- 变量 / 编排顺序 / 新建

    @PutMapping(value = "/experiments/{id:\\d+}/variables",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> saveVariables(
            @PathVariable long id,
            @RequestBody(required = false) String body,
            HttpServletRequest req) throws SQLException {
        List<String[]> vars = new ArrayList<>();
        java.util.regex.Matcher vm = java.util.regex.Pattern.compile(
                "\\{[^{}]*\"name\"\\s*:\\s*\"([^\"]*)\"[^{}]*\\}").matcher(body);
        while (vm.find()) {
            String chunk = vm.group(0);
            vars.add(new String[]{
                    vm.group(1),
                    extractStr(chunk, "type"),
                    extractStr(chunk, "defaultValue"),
                    extractStr(chunk, "description"),
            });
        }
        int n = experimentActionService.saveExperimentVariables(id, vars);
        audit.record(session(req), "experiment.vars", "experiment", id, null,
                Jsons.obj("count", n), req.getRemoteAddr());
        return ResponseEntity.ok(Jsons.obj("saved", n));
    }

    @PostMapping(value = "/experiments/{id:\\d+}/variables",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> saveVariablesPost(
            @PathVariable long id,
            @RequestBody(required = false) String body,
            HttpServletRequest req) throws SQLException {
        return saveVariables(id, body, req);
    }

    /**
     * 改**一个**实验变量的值（同名即覆盖）。
     *
     * <p>body: {@code {"name":"VAR001","value":"7","type":"number"}}。
     * 语义：这个实验用这个值（**实验级覆盖流程级**），下一次启动实验时生效。</p>
     */
    @PutMapping(value = "/experiments/{id:\\d+}/variables/{name}",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> setVariable(@PathVariable long id,
                            @PathVariable String name,
                            @RequestBody(required = false) String body,
                            HttpServletRequest req) throws SQLException {
        String n = URLDecoder.decode(name, StandardCharsets.UTF_8);
        String value = extractStr(body, "value");
        String type = extractStr(body, "type");
        String desc = extractStr(body, "description");
        try {
            int c = experimentActionService.setExperimentVariableValue(id, n, type, value, desc);
            audit.record(session(req), "experiment.var_set", "experiment", id,
                    n, Jsons.obj("value", value), req.getRemoteAddr());
            return ResponseEntity.ok(Jsons.obj("ok", c > 0, "name", n, "value", value));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Jsons.obj("ok", false, "error", e.getMessage()));
        }
    }

    @PutMapping(value = "/experiments/{id:\\d+}/processes",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> saveProcesses(
            @PathVariable long id,
            @RequestBody(required = false) String body,
            HttpServletRequest req) throws SQLException {
        // 列表编排（纯顺序形态）的写入口：{"workflowIds":[12,13]}
        //
        // 为什么原来是 409 只读：编排表被当成「画布编排」的**编译产物**，
        // 写入口唯一（画布）⇒ 避免两处写打架。
        // 但那让「实验建好之后，把已经建好的流程加进来」**在界面上无处可做**
        // —— 只有「新建实验」弹窗能带流程，实验建好后编排表就只能看不能改
        // （2026-11-11 用户现场：新建了实验却找不到地方放已建好的流程）。
        //
        // 现在放开，但守住"单一权威"：**已有画布编排的实验仍然拒绝**。
        // 因为前端画布加载时 graph 非空就用 graph（graph 优先），两边都写会出现
        // 「画布一套、顺序一套」的分裂状态（画布还显示旧顺序）。
        // 没有画布的实验：写编排表后，画布页会从编排表自动派生出一致的线性画布。
        if (experimentActionService.hasCanvasGraph(id)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(err("这个实验已经用「画布编排」保存过，画布是权威的；"
                            + "请到画布编排里调整流程顺序（画布支持条件分支与并行，"
                            + "列表只表达纯顺序）"));
        }
        // ⚠ **必须同时认 body 与 query string**（2026-11-11 修）：
        //   只读 body 时 `POST .../processes?workflowIds=-1` 被当成"没传"⇒ 解析成空列表
        //   ⇒ 走全量替换把编排**清空**，而且校验根本没执行（实测：非法 id -1 也返回 200、
        //   编排 1 条 → 0 条）⇒ 数据破坏级缺陷。
        //   现在：两处都没有 workflowIds ⇒ 400（不碰数据）；值非法 ⇒ 400（也不动数据）。
        java.util.List<Long> workflowIds =
                extractLongArray(body, req.getQueryString(), "workflowIds");
        if (workflowIds == null) {
            return ResponseEntity.badRequest()
                    .body(err("缺少 workflowIds：请求体或查询串里都没有这个字段"
                            + "（显式给空数组 [] 才表示清空编排）"));
        }
        int n;
        try {
            n = experimentActionService.replaceExperimentProcesses(id, workflowIds);
        } catch (IllegalArgumentException e) {
            // 流程不存在 / 超过 50 个 ⇒ 明确 400（**不改数据**）
            return ResponseEntity.badRequest().body(err(String.valueOf(e.getMessage())));
        }
        audit.record(session(req), "experiment.processes.save", "experiment", id,
                "workflows=" + workflowIds.size(), null, req.getRemoteAddr());
        return ResponseEntity.ok(Jsons.obj("saved", n, "workflowIds", workflowIds));
    }

    @PostMapping(value = "/experiments/{id:\\d+}/processes",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> saveProcessesPost(
            @PathVariable long id,
            @RequestBody(required = false) String body,
            HttpServletRequest req) throws SQLException {
        return saveProcesses(id, body, req);
    }

    @PostMapping(value = "/experiments/reorder", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> reorder(
            @RequestBody(required = false) String body,
            HttpServletRequest req) throws SQLException {
        // 人工插队：{"ids":[3,1,2]} —— 数组顺序就是执行顺序
        List<Long> order = new ArrayList<>();
        java.util.regex.Matcher arr = java.util.regex.Pattern
                .compile("\"ids\"\\s*:\\s*\\[([^\\]]*)\\]")
                .matcher(body == null ? "" : body);
        if (arr.find()) {
            java.util.regex.Matcher im = java.util.regex.Pattern
                    .compile("\\d+").matcher(arr.group(1));
            while (im.find()) {
                order.add(Long.parseLong(im.group()));
            }
        }
        try {
            int n = experimentActionService.reorderExperiments(order);
            audit.record(session(req), "experiment.reorder", "experiment", null,
                    "count=" + order.size(), null, req.getRemoteAddr());
            return ResponseEntity.ok(Jsons.obj("reordered", n, "ids",
                    new Jsons.Raw(Jsons.arr(order.stream().map(String::valueOf).toList()))));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err(e.getMessage()));
        }
    }

    @PostMapping(value = "/experiments", produces = "application/json; charset=utf-8")
    public String create(@RequestBody(required = false) String body,
                         HttpServletRequest req) throws SQLException {
        // 新建实验：{name, code?, islandId?, priority?, workflowIds?[]}
        List<Long> wfs = new ArrayList<>();
        java.util.regex.Matcher arr = java.util.regex.Pattern
                .compile("\"workflowIds\"\\s*:\\s*\\[([^\\]]*)\\]")
                .matcher(body == null ? "" : body);
        if (arr.find()) {
            java.util.regex.Matcher im = java.util.regex.Pattern
                    .compile("\\d+").matcher(arr.group(1));
            while (im.find()) {
                wfs.add(Long.parseLong(im.group()));
            }
        }
        String eid = experimentActionService.createExperiment(
                jsonField(body, "name"), jsonField(body, "code"),
                jsonLong(body, "islandId"), jsonInt(body, "priority"), wfs);
        audit.record(session(req), "experiment.create", "experiment", null,
                jsonField(body, "name"), null, req.getRemoteAddr());
        return eid;
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 解析分批配置请求体（D11）：{@code {"batchSplit":24}} / {@code {"batchSplit":null}}。
     *
     * <p>口径：正整数 = 每批样品数；{@code 0} 视为 null（关闭分批）；
     * 负数 / 非数字 / 缺字段一律 400 —— 静默当成 null 会把"配错了"变成"关掉了"，
     * 第二天现场发现没分批，查起来毫无线索。
     */
    private static Integer batchSplitFromBody(String body) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"batchSplit\"\\s*:\\s*([^,}\\s]+)")
                .matcher(body == null ? "" : body);
        if (!m.find()) {
            throw new IllegalArgumentException(
                    "需要 batchSplit 字段：正整数（每批样品数）或 null（关闭分批）");
        }
        String raw = m.group(1).trim();
        if ("null".equalsIgnoreCase(raw)) {
            return null;
        }
        int n;
        try {
            n = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("batchSplit 必须是整数或 null：" + raw);
        }
        if (n < 0) {
            throw new IllegalArgumentException("batchSplit 不能为负数：" + n);
        }
        // 0 视为 null = 关闭分批（与 BatchPlanner.normalizeSplit 同口径）
        return n == 0 ? null : n;
    }

    /**
     * 解析实验执行请求体里的变量覆盖：{@code {"vars":{"k":"v"}}}。
     *
     * <p>口径与原 EXP_RUN 内联解析一致（极简正则，够用即可）；
     * 整实验执行与单批重跑必须用同一份解析，否则"重跑批拿到不同变量"会查不出来。
     */
    private static Map<String, String> experimentRunVars(String body) {
        Map<String, String> overrides = new LinkedHashMap<>();
        java.util.regex.Matcher vm = java.util.regex.Pattern
                .compile("\"vars\"\\s*:\\s*\\{([^{}]*)\\}")
                .matcher(body == null ? "" : body);
        if (vm.find()) {
            for (String pair : vm.group(1).split(",")) {
                int i = pair.indexOf(':');
                if (i <= 0) {
                    continue;
                }
                String k = pair.substring(0, i).trim().replaceAll("^\"|\"$", "");
                String v = pair.substring(i + 1).trim().replaceAll("^\"|\"$", "");
                if (!k.isEmpty()) {
                    overrides.put(k, v);
                }
            }
        }
        return overrides;
    }

    /** 从 JSON 片段里取某个字符串字段（够用即可，不追求通用）。 */
    private static String extractStr(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 从 JSON 里取一个数字数组字段（如 {@code "workflowIds":[12,13]}）。
     *
     * <p>跟本文件其它解析一样走正则（够用即可，不追求通用 JSON 解析）：
     * 字段缺失时返回**空列表**（不是 null）⇒ 调用方可以直接当"清空"处理。
     */
    /**
     * 取一个数字数组字段（如 {@code "workflowIds":[12,13]}）。
     *
     * <p><b>body 与 query string 都找</b>：验收脚本与部分调用方用
     * {@code ?workflowIds=12,13} 传参 ⇒ 只看 body 会当成"没传"⇒ 解析成空列表
     * ⇒ 全量替换把编排**清空**（2026-11-11 实测踩到：非法 id -1 也返回 200、
     * 编排 1 条 → 0 条）。
     *
     * <p>返回 **null = 字段根本没给**（调用方应回 400，别当成"清空"）；
     * 返回**空列表 = 显式给了空数组**（= 显式清空编排）。
     */
    private static List<Long> extractLongArray(String json, String query, String key) {
        java.util.regex.Pattern p = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*\\[([^\\]]*)\\]");
        java.util.regex.Matcher m = p.matcher(json == null ? "" : json);
        if (!m.find()) {
            if (query == null) {
                return null;
            }
            // 查询串形态：workflowIds=12,13（没有方括号）
            java.util.regex.Matcher q = java.util.regex.Pattern
                    .compile("(?:^|[?&])" + key + "=([^&]*)").matcher(query);
            if (!q.find()) {
                return null;
            }
            List<Long> qids = new ArrayList<>();
            java.util.regex.Matcher im =
                    java.util.regex.Pattern.compile("-?\\d+").matcher(q.group(1));
            while (im.find()) {
                qids.add(Long.parseLong(im.group()));
            }
            return qids;
        }
        List<Long> out = new ArrayList<>();
        java.util.regex.Matcher im = java.util.regex.Pattern.compile("\\d+").matcher(m.group(1));
        while (im.find()) {
            out.add(Long.parseLong(im.group()));
        }
        return out;
    }

    /** Map&lt;String,String&gt; 转 JSON（Jsons.obj 不接受 Map）。 */
    private static String varsToJson(Map<String, String> m) {
        List<Object> kv = new ArrayList<>();
        m.forEach((k, v) -> {
            kv.add(k);
            kv.add(v);
        });
        return Jsons.obj(kv.toArray());
    }

    private static String operator(HttpServletRequest req) {
        AuthService.Session s0 = session(req);
        return s0 == null ? "api" : s0.username();
    }

    private static AuthService.Session session(HttpServletRequest req) {
        Object v = req.getAttribute(ApiAuthInterceptor.SESSION_ATTR);
        return v instanceof AuthService.Session s0 ? s0 : null;
    }
}
