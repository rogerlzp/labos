package com.labos.kernel.api;

import com.labos.kernel.engine.EngineDemo;
import com.labos.kernel.engine.NodeProgress;
import com.labos.kernel.engine.RecoverableNode;
import com.labos.kernel.engine.RunProgress;
import com.labos.kernel.engine.RunStateMachine;
import com.labos.kernel.engine.SimulatedBus;
import com.labos.kernel.engine.Sleeper;
import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.JdbcErrorOccurrenceStore;
import com.labos.kernel.persistence.JdbcEventStore;
import com.labos.kernel.persistence.JdbcRecoveryPolicyRepository;
import com.labos.kernel.persistence.Jsons;
import com.labos.kernel.persistence.Occurrence;
import com.labos.kernel.recovery.RecoveryDecider;
import com.labos.kernel.recovery.RecoveryPolicy;
import com.labos.kernel.recovery.RecoveryPolicyResolver;
import com.labos.kernel.recovery.RecoveryScope;
import com.labos.kernel.recovery.RecoveryStrategy;
import com.labos.kernel.workflow.PreflightChecker;
import com.labos.kernel.workflow.StartupRecovery;
import com.labos.kernel.workflow.RecipeCompiler;
import com.labos.kernel.driver.DriverRegistry;
import com.labos.kernel.driver.PositionLock;
import com.labos.kernel.driver.ShadowProbe;
import com.labos.kernel.driver.ShadowRunner;
import com.labos.kernel.workflow.BatchOrchestrator;
import com.labos.kernel.workflow.ExperimentExecutor;
import com.labos.kernel.workflow.ExperimentScheduler;
import com.labos.kernel.workflow.PauseCommand;
import com.labos.kernel.workflow.PauseCoordinator;
import com.labos.kernel.workflow.SchedulerEta;
import com.labos.kernel.workflow.StatusVocabulary;
import com.labos.kernel.workflow.WorkflowBuilder;
import com.labos.kernel.workflow.WorkflowService;
import com.labos.kernel.workflow.RealWorkflowRunner.WorkflowDef;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static com.labos.kernel.api.ApiServer.*;


/**
 * ExperimentApi：从 {@link ApiServer#route} 拆出的 Experiment 资源域路由（26 个路由块）。
 *
 * <p>路径：{@code /api/experiments}、{@code /api/experiments/eta}、{@code /api/experiments/flow-run}、{@code /api/experiments/reorder}
 * <p>正则路由：{@code EXP_BATCHES}、{@code EXP_BATCH_RUN}、{@code EXP_BATCH_SPLIT}、{@code EXP_GRAPH}、{@code EXP_LOOP}、{@code EXP_PROC}、{@code EXP_RUN}、{@code EXP_RUNS}、{@code EXP_RUN_ACTION}、{@code EXP_RUN_STEPS}、{@code EXP_VARS}
 *
 * <p><b>拆分约定</b>：路由顺序按首次出现顺序分发（跨 controller 稳定）；命中即 return true。
 * 共享 helper 仍由 ApiServer 提供（static import + {@code s.xxx}），不重复实现。
 */
final class ExperimentApi {

    /** 宿主：提供库连接/服务与共享 helper（与 ApiServer 内部调用等价）。 */
    private final ApiServer s;

    ExperimentApi(ApiServer s) {
        this.s = s;
    }

    /**
     * 处理本资源域请求。
     *
     * @return true = 已处理（调用方直接 return）；false = 不归本域，交给下一个 controller
     */
    boolean handle(HttpExchange ex, String path, String method) throws Exception {
        if ("/api/experiments/flow-run".equals(path)) {
            if (!"POST".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            String qs = ex.getRequestURI().getQuery();
            // steps 里的 = : , 都会被 URL 编码，先解码再解析
            String stepsRaw = paramStr(qs, "steps");
            String stepsExpr = stepsRaw == null ? null
                    : java.net.URLDecoder.decode(stepsRaw, StandardCharsets.UTF_8);
            if (stepsExpr == null || stepsExpr.isBlank()) {
                s.send(ex, 400, err("需要 steps，格式 id:method[:output]，逗号分隔"));
                return true;
            }
            List<com.labos.kernel.workflow.ExperimentFlowRunner.Step> steps =
                    new ArrayList<>();
            // 每段：id:method[:output[:k=v;k2=v2]]
            for (String part : stepsExpr.split(",")) {
                String[] f = part.split(":", -1);
                if (f.length < 2 || f[0].isBlank() || f[1].isBlank()) {
                    continue;
                }
                steps.add(new com.labos.kernel.workflow.ExperimentFlowRunner.Step(
                        Long.parseLong(f[0].trim()),
                        f[1].trim(),
                        f.length > 2 && !f[2].isBlank() ? f[2].trim() : null,
                        f.length > 3 && !f[3].isBlank() ? f[3].trim() : null));
            }
            if (steps.isEmpty()) {
                s.send(ex, 400, err("steps 解析后为空"));
                return true;
            }
            com.labos.kernel.workflow.ExperimentFlowRunner runner =
                    new com.labos.kernel.workflow.ExperimentFlowRunner(
                            s.db, TENANT, s.workflows);
            com.labos.kernel.workflow.ExperimentFlowRunner.Result r =
                    runner.run(paramStr(qs, "name"),
                            steps, parseVarsExpr(paramStr(qs, "vars")));

            s.audited("experiment.flowrun", "experiment", null, "run#" + r.runId(),
                    Jsons.obj("status", r.status(), "steps", r.total()), ex);
            s.send(ex, r.status().equals("completed") ? 200 : 400, Jsons.obj(
                    "runId", r.runId(),
                    "status", r.status(),
                    "completed", r.completed(),
                    "total", r.total(),
                    "error", r.error(),
                    "vars", new Jsons.Raw(varsToJson(r.vars()))));
            return true;
        }
        Matcher expGraph = EXP_GRAPH.matcher(path);
        Matcher handoffConfirm = EXP_HANDOFF_CONFIRM.matcher(path);
        if (handoffConfirm.matches()) {
            if (!"POST".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            long eid = Long.parseLong(handoffConfirm.group(1));
            String body = new String(s.readBody(ex), StandardCharsets.UTF_8);
            Long from = jsonLong(body, "fromWorkcellId");
            Long to = jsonLong(body, "toWorkcellId");
            if (from == null || to == null) {
                s.send(ex, 400, err("需要 fromWorkcellId / toWorkcellId"));
                return true;
            }
            int n = s.query.confirmHandoff(eid, from, to, "api", jsonField(body, "note"));
            if (n == 0) {
                s.send(ex, 409, err("Handoff 不存在或已确认"));
                return true;
            }
            s.audited("experiment.handoff.confirm", "experiment", eid,
                    from + "->" + to, null, ex);
            s.send(ex, 200, Jsons.obj("experimentId", eid,
                    "fromWorkcellId", from, "toWorkcellId", to,
                    "status", "confirmed"));
            return true;
        }

        Matcher expHandoffs = EXP_HANDOFFS.matcher(path);
        if (expHandoffs.matches()) {
            long eid = Long.parseLong(expHandoffs.group(1));
            if ("GET".equals(method)) {
                s.send(ex, 200, s.query.experimentHandoffsJson(eid));
                return true;
            }
            if (!"PUT".equals(method) && !"POST".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            String body = new String(s.readBody(ex), StandardCharsets.UTF_8);
            List<long[]> pairs = new ArrayList<>();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "\\{[^{}]*\"fromWorkcellId\"\\s*:\\s*(\\d+)[^{}]*\"toWorkcellId\"\\s*:\\s*(\\d+)[^{}]*\\}")
                    .matcher(body);
            while (m.find()) {
                pairs.add(new long[]{Long.parseLong(m.group(1)), Long.parseLong(m.group(2))});
            }
            int n = s.query.setExperimentHandoffs(eid, pairs);
            if (n == -1) {
                s.send(ex, 404, err("实验不存在"));
                return true;
            }
            if (n == -2) {
                s.send(ex, 400, err("Handoff 起点/终点无效或不在实验 Workcell 绑定内"));
                return true;
            }
            s.audited("experiment.handoffs.set", "experiment", eid,
                    "pairs=" + pairs.size(), Jsons.obj("count", n), ex);
            s.send(ex, 200, Jsons.obj("experimentId", eid, "updated", n));
            return true;
        }

        Matcher expWorkcells = EXP_WORKCELLS.matcher(path);
        if (expWorkcells.matches()) {
            long eid = Long.parseLong(expWorkcells.group(1));
            if ("GET".equals(method)) {
                s.send(ex, 200, s.query.experimentWorkcellsJson(eid));
                return true;
            }
            if (!"PUT".equals(method) && !"POST".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            String body = new String(s.readBody(ex), StandardCharsets.UTF_8);
            java.util.regex.Matcher arr = java.util.regex.Pattern
                    .compile("\"workcellIds\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(body);
            if (!arr.find()) {
                s.send(ex, 400, err("需要 workcellIds 数组（空数组=清空绑定）"));
                return true;
            }
            List<Long> ids = new ArrayList<>();
            for (String part : arr.group(1).split(",")) {
                String t = part.trim();
                if (!t.isEmpty()) {
                    ids.add(Long.parseLong(t));
                }
            }
            int n = s.query.setExperimentWorkcells(eid, ids);
            if (n < 0) {
                s.send(ex, 404, err("实验不存在"));
                return true;
            }
            s.audited("experiment.workcells.set", "experiment", eid,
                    ids.toString(), Jsons.obj("count", n), ex);
            s.send(ex, 200, Jsons.obj("experimentId", eid, "updated", n));
            return true;
        }

        if (expGraph.matches()) {
            long eidg = Long.parseLong(expGraph.group(1));
            if ("GET".equals(method)) {
                s.send(ex, 200, s.query.experimentGraphJson(eidg));
                return true;
            }
            if ("PUT".equals(method) || "POST".equals(method)) {
                String body = new String(s.readBody(ex), StandardCharsets.UTF_8);
                int n = s.query.saveExperimentGraph(eidg, body);
                s.audited("experiment.graph", "experiment", eidg, null,
                        Jsons.obj("bytes", body.length(), "compiledProcesses", n), ex);
                s.send(ex, 200, Jsons.obj("saved", 1, "bytes", body.length(),
                        "compiledProcesses", n));
                return true;
            }
            throw new IllegalStateException("method not allowed: " + method);
        }
        Matcher expRunSteps = EXP_RUN_STEPS.matcher(path);
        if (expRunSteps.matches()) {
            s.requireGet(method, ex);
            s.send(ex, 200, s.query.experimentRunStepsJson(
                    Long.parseLong(expRunSteps.group(1))));
            return true;
        }
        Matcher runAction = EXP_RUN_ACTION.matcher(path);
        if (runAction.matches()) {
            if (!"POST".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            long rid = Long.parseLong(runAction.group(1));
            String act = runAction.group(2);
            String qs = ex.getRequestURI().getQuery();
            String requestId = ex.getRequestHeaders().getFirst("X-Request-Id");
            // 统一走 PauseCoordinator（S-02 / P-06）：这里只解析 HTTP 参数，
            // 路由到既有 RunControl + RuntimeHold 的暂停/停止/恢复语义不在此处重写。
            PauseCoordinator coord = new PauseCoordinator(s.db, TENANT, s.workflows, s.scheduler);

            if ("pause".equals(act)) {
                PauseCoordinator.ExperimentPause r = coord.pauseExperimentRun(
                        PauseCommand.experiment(rid, PauseCommand.Action.PAUSE,
                                paramStr(qs, "kind"), paramStr(qs, "reason"), requestId),
                        paramStr(qs, "scope"));
                // 回"实际生效"的作用域：未分配岛时 island 会被后端退化成 experiment，
                // 回请求值会让界面说"作用域：岛"，而库里只有一道实验级扣留。
                s.audited("experiment.pause", "experiment", r.experimentId(),
                        "run#" + rid + " scope=" + r.scopeCode(), null, ex);
                s.send(ex, 200, Jsons.obj(
                        "runId", r.runId(),
                        "experimentId", r.experimentId(),
                        "status", r.status(),
                        "controlState", r.controlState(),
                        "scope", r.scopeCode(),
                        "kind", r.kindCode()));
                return true;
            }

            if ("stop".equals(act)) {
                PauseCoordinator.ExperimentStop r = coord.stopExperimentRun(
                        PauseCommand.experiment(rid, PauseCommand.Action.STOP,
                                null, null, requestId));
                s.audited("experiment.stop", "experiment", r.experimentId(),
                        "run#" + rid, null, ex);
                s.send(ex, 200, Jsons.obj(
                        "runId", r.runId(),
                        "experimentId", r.experimentId(),
                        "changed", r.changed(),
                        "status", r.status()));
                return true;
            }

            // resume
            PauseCoordinator.ExperimentResume r = coord.resumeExperimentRun(
                    PauseCommand.experiment(rid, PauseCommand.Action.RESUME,
                            null, null, requestId),
                    paramStr(qs, "note"));
            s.audited("experiment.resume", "experiment", r.experimentId(),
                    "run#" + rid, null, ex);
            s.send(ex, 200, Jsons.obj(
                    "runId", r.runId(),
                    "experimentId", r.experimentId(),
                    "clearedHold", r.clearedHold(),
                    "requeued", r.requeued(),
                    "stillHeldReason", r.stillHeldReason(),
                    "status", r.status()));
            return true;
        }
        Matcher expRuns = EXP_RUNS.matcher(path);
        if (expRuns.matches()) {
            s.requireGet(method, ex);
            s.send(ex, 200, s.query.experimentRunsJson(Long.parseLong(expRuns.group(1))));
            return true;
        }
        Matcher expLoop = EXP_LOOP.matcher(path);
        if (expLoop.matches()) {
            if (!"PUT".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            long eid3 = Long.parseLong(expLoop.group(1));
            int sq = Integer.parseInt(expLoop.group(2));
            String cnt = paramStr(ex.getRequestURI().getQuery(), "count");
            int n = s.query.setLoopCount(eid3, sq, cnt);
            s.send(ex, 200, Jsons.obj("updated", n, "seq", sq, "loopCount", cnt));
            return true;
        }
        Matcher expBatchSplit = EXP_BATCH_SPLIT.matcher(path);
        if (expBatchSplit.matches()) {
            if (!"PUT".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            long eidS = Long.parseLong(expBatchSplit.group(1));
            String bodyS = new String(s.readBody(ex), StandardCharsets.UTF_8);
            Integer split = batchSplitFromBody(bodyS);
            // D11：>0 或 null；已有批次行时改值 -> IllegalArgumentException（400）
            Integer saved = new BatchOrchestrator(s.db, TENANT, s.workflows)
                    .setBatchSplit(eidS, split);
            s.audited("experiment.batch_split", "experiment", eidS, null,
                    Jsons.obj("batchSplit", saved), ex);
            s.send(ex, 200, Jsons.obj(
                    "experimentId", eidS,
                    "batchSplit", saved,
                    "updated", true));
            return true;
        }
        Matcher expBatchRun = EXP_BATCH_RUN.matcher(path);
        if (expBatchRun.matches()) {
            if (!"POST".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            long eidB = Long.parseLong(expBatchRun.group(1));
            int batchNo = Integer.parseInt(expBatchRun.group(2));
            String bodyB = new String(s.readBody(ex), StandardCharsets.UTF_8);
            Map<String, String> varsB = experimentRunVars(bodyB);
            // D4：只跑这一批，新 attempt，历史尝试保留；已完成/不存在 -> 400
            BatchOrchestrator.BatchResult rb = new BatchOrchestrator(
                    s.db, TENANT, s.workflows).runBatch(eidB, batchNo, varsB);
            s.audited("experiment.batch_run", "experiment", eidB, "batch#" + batchNo,
                    Jsons.obj("status", rb.status(), "runId", rb.runId(),
                            "attemptNo", rb.attemptNo()), ex);
            s.send(ex, 200, Jsons.obj(
                    "experimentId", eidB,
                    "batchNo", rb.batchNo(),
                    "runId", rb.runId(),
                    "batchRunId", rb.batchRunId(),
                    "attemptNo", rb.attemptNo(),
                    "status", rb.status(),
                    "doneSteps", rb.doneSteps(),
                    "totalSteps", rb.totalSteps(),
                    "error", rb.error()));
            return true;
        }
        Matcher expBatches = EXP_BATCHES.matcher(path);
        if (expBatches.matches()) {
            s.requireGet(method, ex);
            s.send(ex, 200, new BatchOrchestrator(s.db, TENANT, s.workflows)
                    .batchesJson(Long.parseLong(expBatches.group(1))));
            return true;
        }
        Matcher expRun = EXP_RUN.matcher(path);
        if (expRun.matches()) {
            if (!"POST".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            long eid2 = Long.parseLong(expRun.group(1));
            String body = new String(s.readBody(ex), StandardCharsets.UTF_8);
            Map<String, String> overrides = experimentRunVars(body);
            BatchOrchestrator orchestrator = new BatchOrchestrator(s.db, TENANT, s.workflows);
            Integer batchSplit = orchestrator.batchSplit(eid2);
            // async=1 交给调度器（岛级互斥 + 排队）；默认同步执行
            if (paramLong(ex.getRequestURI().getQuery(), "async") == 1L) {
                if (batchSplit == null) {
                    // S3.2-C：async 请求体的 vars 落 experiment_run.variables，
                    // 调度器执行时作为用户 overrides（响应形状不变）
                    long erid = s.scheduler.submit(eid2, overrides);
                    s.audited("experiment.submit", "experiment", eid2, "run#" + erid, null, ex);
                    s.send(ex, 200, Jsons.obj(
                            "experimentRunId", erid,
                            "status", "queued",
                            "async", true));
                    return true;
                }
                // 分批 + async：调度器队列的单位是"一条 run"，不认"整单多批"，
                // 所以改为**按批派发** —— 首次调用建/取第 1 批的 queued run 并入队；
                // 每批跑完后由 ExperimentScheduler.executeOne 续建下一批
                // （失败继续 = D3、aborted/paused 不续、扣留停在批边界）。
                // 响应保留 experimentRunId，追加 batchNo/async（前端只需看前两个字段）。
                BatchOrchestrator.NextBatchAttempt next =
                        orchestrator.createNextBatchAttempt(eid2, 0, overrides);
                switch (next.outcome()) {
                    case HELD -> {
                        // 批边界被现场扣留：不建尝试，候选批已标 paused、实验聚合 paused。
                        // 解除扣留后由调度器每 5 个 tick 自动续队（S3.2-B），
                        // 也可以 POST /run?async=1 立即从该批继续。
                        s.audited("experiment.submit", "experiment", eid2,
                                "held batch#" + next.batchNo(), null, ex);
                        s.send(ex, 200, Jsons.obj(
                                "experimentId", eid2,
                                "experimentRunId", null,
                                "async", true,
                                "held", true,
                                "status", "paused",
                                "batchNo", next.batchNo(),
                                "holdReason", next.holdReason()));
                        return true;
                    }
                    case NO_MORE -> {
                        // 所有批次都已成功：没有可派发的批次（等同同步 runAll 的空跑）
                        s.audited("experiment.submit", "experiment", eid2, "no-more-batch",
                                null, ex);
                        s.send(ex, 200, Jsons.obj(
                                "experimentId", eid2,
                                "experimentRunId", null,
                                "async", true,
                                "status", next.status() == null ? "finished" : next.status(),
                                "batchNo", null));
                        return true;
                    }
                    case NOT_BATCHED -> throw new IllegalArgumentException(
                            "实验已配置分批但取不到切批计划（batch_split 可能刚被清空），请重试");
                    default -> {
                        // CREATED（新建 queued 批次 run）/ EXISTING（已有未结束批次 run）
                        if (next.queueable()) {
                            s.scheduler.resumeRun(eid2, next.runId());
                        }
                        s.audited("experiment.submit", "experiment", eid2,
                                "run#" + next.runId() + " batch#" + next.batchNo(),
                                Jsons.obj("attemptNo", next.attemptNo(),
                                        "batchStatus", next.status()), ex);
                        s.send(ex, 200, Jsons.obj(
                                "experimentId", eid2,
                                "experimentRunId", next.runId(),
                                "batchNo", next.batchNo(),
                                "batchRunId", next.batchRunId(),
                                "attemptNo", next.attemptNo(),
                                "status", next.status(),
                                "async", true));
                        return true;
                    }
                }
            }

            // D1：batch_split 为空 -> runAll 内部原样走非分批路径（一次 run 跑整单）；
            //     非空 -> 按批串行跑所有未成功批，响应追加 batchSplit + batches。
            BatchOrchestrator.RunAllResult r = orchestrator.runAll(eid2, overrides);
            s.audited("experiment.run", "experiment", eid2,
                    r.batched() ? "batches#" + r.batches().size() : "run#" + r.experimentRunId(),
                    Jsons.obj("status", r.status(), "steps", r.totalSteps(),
                            "batched", r.batched()), ex);
            if (!r.batched()) {
                // 不分批：响应形状与改动前完全一致（回归断言见 BatchDbSelfTest）
                s.send(ex, 200, Jsons.obj(
                        "experimentRunId", r.experimentRunId(),
                        "status", r.status(),
                        "totalSteps", r.totalSteps(),
                        "doneSteps", r.doneSteps(),
                        "error", r.error()));
                return true;
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
            s.send(ex, 200, Jsons.obj(
                    "experimentRunId", r.experimentRunId() == 0L ? null : r.experimentRunId(),
                    "status", r.status(),
                    "totalSteps", r.totalSteps(),
                    "doneSteps", r.doneSteps(),
                    "error", r.error(),
                    "batchSplit", r.batchSplit(),
                    "batches", new Jsons.Raw(Jsons.arr(batchItems))));
            return true;
        }
        Matcher expVars = EXP_VARS.matcher(path);
        if (expVars.matches()) {
            long eid = Long.parseLong(expVars.group(1));
            if ("GET".equals(method)) {
                s.send(ex, 200, s.query.experimentVariablesJson(eid));
                return true;
            }
            if ("PUT".equals(method) || "POST".equals(method)) {
                String body = new String(s.readBody(ex), StandardCharsets.UTF_8);
                List<String[]> vars = new ArrayList<>();
                java.util.regex.Matcher vm = java.util.regex.Pattern.compile(
                        "\\{[^{}]*\"name\"\\s*:\\s*\"([^\"]*)\"[^{}]*\\}")
                        .matcher(body);
                while (vm.find()) {
                    String chunk = vm.group(0);
                    vars.add(new String[]{
                            vm.group(1),
                            extractStr(chunk, "type"),
                            extractStr(chunk, "defaultValue"),
                            extractStr(chunk, "description"),
                    });
                }
                int n = s.query.saveExperimentVariables(eid, vars);
                s.audited("experiment.vars", "experiment", eid, null,
                        Jsons.obj("count", n), ex);
                s.send(ex, 200, Jsons.obj("saved", n));
                return true;
            }
            throw new IllegalStateException("method not allowed: " + method);
        }
        Matcher expProc = EXP_PROC.matcher(path);
        if (expProc.matches()) {
            long eid = Long.parseLong(expProc.group(1));
            if ("GET".equals(method)) {
                s.send(ex, 200, s.query.experimentProcessesJson(eid));
                return true;
            }
            if ("PUT".equals(method) || "POST".equals(method)) {
                s.send(ex, 409, err("流程编排表已改为只读镜像；请到“画布编排”保存，系统会自动编译成流程顺序"));
                return true;
            }
            throw new IllegalStateException("method not allowed: " + method);
        }
        if ("/api/experiments/reorder".equals(path)) {
            // 人工插队：{"ids":[3,1,2]} —— 数组顺序就是执行顺序
            if (!"POST".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            String body = new String(s.readBody(ex), StandardCharsets.UTF_8);
            java.util.List<Long> order = new ArrayList<>();
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
                int n = s.query.reorderExperiments(order);
                s.audited("experiment.reorder", "experiment", null,
                        "count=" + order.size(), null, ex);
                s.send(ex, 200, Jsons.obj("reordered", n, "ids",
                        new Jsons.Raw(Jsons.arr(order.stream()
                                .map(String::valueOf).toList()))));
            } catch (IllegalArgumentException e) {
                s.send(ex, 400, err(e.getMessage()));
            }
            return true;
        }
        if ("/api/experiments".equals(path)) {
            if ("POST".equals(method)) {
                // 新建实验：{name, code?, islandId?, priority?, workflowIds?[]}
                String body = new String(s.readBody(ex), StandardCharsets.UTF_8);
                java.util.List<Long> wfs = new ArrayList<>();
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
                String eid = s.query.createExperiment(
                        jsonField(body, "name"), jsonField(body, "code"),
                        jsonLong(body, "islandId"), jsonInt(body, "priority"), wfs);
                s.audited("experiment.create", "experiment", null,
                        jsonField(body, "name"), null, ex);
                s.send(ex, 200, eid);
                return true;
            }
            s.requireGet(method, ex);
            s.send(ex, 200, s.query.experimentsJson(100));
            return true;
        }
        if ("/api/experiments/eta".equals(path)) {
            s.requireGet(method, ex);
            // §7.2（2026-11-03）：预计等待。limit 默认 100、上限 500（与列表页同上限）。
            // 队列/岛锁取调度器内存快照（库里 experiment_run.status 是回写的、有延迟），
            // 估算本身是纯函数（SchedulerEta.estimateWait）。
            int limit = intParam(ex, "limit", 100);
            limit = Math.max(1, Math.min(limit, 500));
            ExperimentScheduler.Snapshot snap = s.scheduler.snapshot();
            s.send(ex, 200, SchedulerEta.experimentsEtaJson(s.db, TENANT, limit,
                    snap.queue(), snap.islandLocks()));
            return true;
        }
        return false;
    }


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
     * <p>口径与改动前 EXP_RUN 内联解析一致（极简正则，够用即可）；
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
                if (i <= 0) continue;
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


    /** Map<String,String> 转 JSON（Jsons.obj 不接受 Map）。 */
    private static String varsToJson(Map<String, String> m) {
        java.util.List<Object> kv = new ArrayList<>();
        m.forEach((k, v) -> { kv.add(k); kv.add(v); });
        return Jsons.obj(kv.toArray());
    }

    // ------------------------------------------------------------ 路由正则

    private static final Pattern EXP_PROC = Pattern.compile("^/api/experiments/(\\d+)/processes$");

    private static final Pattern EXP_VARS = Pattern.compile("^/api/experiments/(\\d+)/variables$");

    private static final Pattern EXP_RUN = Pattern.compile("^/api/experiments/(\\d+)/run$");

    private static final Pattern EXP_LOOP = Pattern.compile("^/api/experiments/(\\d+)/processes/(\\d+)/loop$");

    private static final Pattern EXP_RUNS = Pattern.compile("^/api/experiments/(\\d+)/runs$");

    /** S3 分批执行：批次列表（批次视图）。 */
    private static final Pattern EXP_BATCHES =
            Pattern.compile("^/api/experiments/(\\d+)/batches$");

    /** S3 分批执行：只重跑某一批。 */
    private static final Pattern EXP_BATCH_RUN =
            Pattern.compile("^/api/experiments/(\\d+)/batches/(\\d+)/run$");

    /** S3.1 分批配置：PUT 每批样品数（null/0 = 关闭分批；已有批次时拒绝改）。 */
    private static final Pattern EXP_BATCH_SPLIT =
            Pattern.compile("^/api/experiments/(\\d+)/batch-split$");

    private static final Pattern EXP_GRAPH = Pattern.compile("^/api/experiments/(\\d+)/graph$");

    private static final Pattern EXP_WORKCELLS =
            Pattern.compile("^/api/experiments/(\\d+)/workcells$");

    private static final Pattern EXP_HANDOFFS =
            Pattern.compile("^/api/experiments/(\\d+)/handoffs$");

    private static final Pattern EXP_HANDOFF_CONFIRM =
            Pattern.compile("^/api/experiments/(\\d+)/handoffs/confirm$");

    /** 一次实验执行的逐步明细（含"未执行"的步骤）。 */
    private static final Pattern EXP_RUN_STEPS =
            Pattern.compile("^/api/experiments/runs/(\\d+)/steps$");

    /** 实验运行控制：暂停 / 停止 / 恢复。 */
    private static final Pattern EXP_RUN_ACTION =
            Pattern.compile("^/api/experiments/runs/(\\d+)/(pause|stop|resume)$");
}
