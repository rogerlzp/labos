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
import com.labos.kernel.workflow.RunControl;
import com.labos.kernel.workflow.RuntimeHold;
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
 * SchedulerApi：从 {@link ApiServer#route} 拆出的 Scheduler 资源域路由（1 个路由块）。
 *
 * <p>路径：{@code /api/scheduler/status}
 *
 * <p><b>拆分约定</b>：路由顺序按首次出现顺序分发（跨 controller 稳定）；命中即 return true。
 * 共享 helper 仍由 ApiServer 提供（static import + {@code s.xxx}），不重复实现。
 */
final class SchedulerApi {

    /** 宿主：提供库连接/服务与共享 helper（与 ApiServer 内部调用等价）。 */
    private final ApiServer s;

    SchedulerApi(ApiServer s) {
        this.s = s;
    }

    /**
     * 处理本资源域请求。
     *
     * @return true = 已处理（调用方直接 return）；false = 不归本域，交给下一个 controller
     */
    boolean handle(HttpExchange ex, String path, String method) throws Exception {
        if ("/api/scheduler/status".equals(path)) {
            s.requireGet(method, ex);
            // §7.1（2026-11-03）：在计数字段上补 queue 明细 / 岛锁主体 / workers。
            // 快照只拍一次 —— 与 /api/experiments/eta 共用同一份内存态口径。
            ExperimentScheduler.Snapshot st = s.scheduler.snapshot();
            s.send(ex, 200, Jsons.obj(
                    "running", st.running(),
                    "queued", st.queued(),
                    "maxConcurrent", st.maxConcurrent(),
                    "stalled", st.stalled(),
                    // §7.1：started = 累计派发次数（boolean 语义挪到 loopStarted）
                    "started", st.started(),
                    "loopStarted", st.loopStarted(),
                    "lastDispatchAt", st.lastDispatchAt(),
                    "islandLocks", new Jsons.Raw(locksJson(st.islandLocks())),
                    "queue", new Jsons.Raw(queueJson(st.queue())),
                    "workers", new Jsons.Raw(workersJson(st))));
            return true;
        }
        return false;
    }


    /**
     * 岛锁列表转 JSON 数组（§7.1：补 islandCode / experimentId / experimentNo）。
     *
     * <p>联查不到的字段为 null（库抖动时仍返回内存态：islandId/experimentRunId/since/expiresAt）。
     * runId 是 §7.1 示例里的历史别名（与 experimentRunId 同值）。
     */
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
                // 池子是直接 submit，队列里恒 0（§7.1 保留字段给未来队列化）
                "queue", 0);
    }
}
