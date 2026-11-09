package com.labos.boot.web.controller;

import com.labos.kernel.api.QueryService;
import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.JdbcManualWindowStore;
import com.labos.kernel.persistence.Jsons;
import com.labos.kernel.workflow.PauseManager;
import com.labos.kernel.workflow.RuntimeHold;
import com.labos.kernel.workflow.StartupRecovery;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.labos.kernel.api.ApiServer.err;

/**
 * 从 {@code RunApi} 拆出的**只读**执行域 Controller（15 个端点）。
 *
 * <p>只搬读查询，不动状态机：暂停/恢复/忽略/处置仍在 {@code RunActionController}。
 * 响应结构与原实现逐字一致（含 {@code Jsons.Raw} 包裹的嵌套数组）。
 *
 * <p>路径注意：{@code /api/runs/{id}} 必须限定 {@code \d+}，否则
 * {@code /api/runs/paused}、{@code /quarantined}、{@code /recoverable}
 * 会被当成 id 匹配进来（类型转换失败 → 400）。
 */
@RestController
@RequestMapping("/api")
public class RunQueryController {

    private static final long TENANT = 1L;

    private final QueryService query;
    private final DbConfig db;

    public RunQueryController(QueryService query, DbConfig db) {
        this.query = query;
        this.db = db;
    }

    // ---------------------------------------------------------------- 人工窗口

    @GetMapping(value = "/manual-windows", produces = "application/json; charset=utf-8")
    public String manualWindows() throws SQLException {
        JdbcManualWindowStore store = new JdbcManualWindowStore(db, TENANT);
        store.expireOverdue();
        List<String> items = new ArrayList<>();
        for (JdbcManualWindowStore.Window w : store.pending(20)) {
            items.add(Jsons.obj(
                    "windowId", w.id(),
                    "runId", w.runId(),
                    "windowKey", w.windowKey(),
                    "errorCode", w.errorCode(),
                    "nodeKey", w.nodeKey(),
                    "strategy", w.strategy(),
                    "defaultAction", w.defaultAction(),
                    "allowedActions", new Jsons.Raw(w.allowedActions()),
                    "decisionReason", w.decisionReason(),
                    "status", w.status(),
                    "openedAt", w.openedAt(),
                    "timeoutAt", w.timeoutAt()));
        }
        return Jsons.obj("total", items.size(),
                "items", new Jsons.Raw(Jsons.arr(items)));
    }

    // ---------------------------------------------------------------- 命令 outbox

    @GetMapping(value = "/command-outbox", produces = "application/json; charset=utf-8")
    public String commandOutbox(@RequestParam(required = false) String state,
                                @RequestParam(required = false) String limit) throws SQLException {
        return query.commandOutboxJson(state, intOr(limit, 100));
    }

    // ---------------------------------------------------------------- 搬运事务

    @GetMapping(value = "/transfers", produces = "application/json; charset=utf-8")
    public String transfers(@RequestParam(required = false) String status,
                            @RequestParam(required = false) String limit) throws SQLException {
        return query.transferTransactionsJson(status, intOr(limit, 100));
    }

    // ---------------------------------------------------------------- 板位证据

    @GetMapping(value = "/plate-position-evidence",
            produces = "application/json; charset=utf-8")
    public String positionEvidence(@RequestParam(required = false) String plateBarcode,
                                   @RequestParam(required = false) String limit)
            throws SQLException {
        return query.positionEvidenceJson(plateBarcode, intOr(limit, 100));
    }

    // ---------------------------------------------------------------- 隔离 / 介入 / 扣留

    @GetMapping(value = "/runs/quarantined", produces = "application/json; charset=utf-8")
    public String quarantinedRuns(@RequestParam(required = false) String limit)
            throws SQLException {
        return query.quarantinedRunsJson(intOr(limit, 100));
    }

    @GetMapping(value = "/intervention-records", produces = "application/json; charset=utf-8")
    public String interventionRecords(@RequestParam(required = false) String limit)
            throws SQLException {
        return query.interventionRecordsJson(intOr(limit, 100));
    }

    @GetMapping(value = "/runtime/holds", produces = "application/json; charset=utf-8")
    public String runtimeHolds() throws SQLException {
        RuntimeHold.HoldView view = new RuntimeHold(db, TENANT).activeView();
        boolean unknown = view.state() == RuntimeHold.HoldState.UNKNOWN;
        return Jsons.obj(
                "anyActive", !view.holds().isEmpty(),
                // ADR-PAUSE-010：读不到扣留(=UNKNOWN)会让调度器 fail-closed 阻断新派发，
                // 必须让运维在界面上看得见，而不是只落日志。
                "unknown", unknown,
                "unknownReason", unknown
                        ? "读取现场扣留失败：调度器已按 fail-closed 阻断新实验派发，"
                        + "请检查数据库连通性，确认后再放行。"
                        : null,
                "holds", new Jsons.Raw(holdsJson(view.holds())));
    }

    // ---------------------------------------------------------------- 暂停队列 / 未知板位

    @GetMapping(value = "/runs/paused", produces = "application/json; charset=utf-8")
    public String pausedRuns() throws SQLException {
        return keyValueArrayJson(new PauseManager(db, TENANT).pausedRuns());
    }

    @GetMapping(value = "/plates/unknown", produces = "application/json; charset=utf-8")
    public String unknownPlates() throws SQLException {
        return keyValueArrayJson(new PauseManager(db, TENANT).unknownPlates());
    }

    // ---------------------------------------------------------------- 执行详情

    @GetMapping(value = "/runs/{id:\\d+}/graph", produces = "application/json; charset=utf-8")
    public String runGraph(@PathVariable long id) throws SQLException {
        return query.runGraphJson(id);
    }

    @GetMapping(value = "/runs/{id:\\d+}/timeline", produces = "application/json; charset=utf-8")
    public String runTimeline(@PathVariable long id) throws SQLException {
        return query.timelineJson(id);
    }

    @GetMapping(value = "/runs/recoverable", produces = "application/json; charset=utf-8")
    public String recoverable() throws SQLException {
        List<String> items = new ArrayList<>();
        for (var o : StartupRecovery.listRecoverable(db, TENANT)) {
            items.add(Jsons.obj(
                    "runId", o.runId(),
                    "runNo", o.runNo(),
                    "completedNodes", o.completedNodes(),
                    "totalNodes", o.totalNodes(),
                    "failedNodes", o.failedNodes()));
        }
        return Jsons.obj("total", items.size(),
                "items", new Jsons.Raw(Jsons.arr(items)));
    }

    @GetMapping(value = "/process-status", produces = "application/json; charset=utf-8")
    public String processStatus() throws SQLException {
        return query.processStatusJson();
    }

    @GetMapping(value = "/runs", produces = "application/json; charset=utf-8")
    public String recentRuns(@RequestParam(required = false) String limit) throws SQLException {
        return query.recentRuns(intOr(limit, 20));
    }

    @GetMapping(value = "/runs/{id:\\d+}", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> runDetail(@PathVariable long id) throws SQLException {
        String body = query.runDetail(id);
        if (body == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(err("run not found"));
        }
        return ResponseEntity.ok(body);
    }

    // ---------------------------------------------------------------- 内部

    /** 生效扣留列表 → JSON 数组。 */
    private static String holdsJson(List<Map<String, Object>> list) {
        List<String> items = new ArrayList<>();
        for (Map<String, Object> m : list) {
            items.add(Jsons.obj(
                    "id", m.get("id"),
                    "scope", m.get("scope"),
                    "scopeId", m.get("scopeId"),
                    "kind", m.get("kind"),
                    "reason", m.get("reason"),
                    "heldAt", m.get("heldAt"),
                    "heldBy", m.get("heldBy")));
        }
        return Jsons.arr(items);
    }

    /** {@code List<Map>} → {@code [{k:v,...}, ...]}，值按类型直出。 */
    private static String keyValueArrayJson(List<Map<String, Object>> rows) {
        List<String> items = new ArrayList<>();
        for (Map<String, Object> m : rows) {
            List<String> kvs = new ArrayList<>();
            m.forEach((k, v) -> kvs.add(Jsons.quote(k) + ":" + jsonVal(v)));
            items.add("{" + String.join(",", kvs) + "}");
        }
        return Jsons.arr(items);
    }

    private static String jsonVal(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        if (v instanceof Iterable<?> it) {
            List<String> parts = new ArrayList<>();
            for (Object o : it) {
                parts.add(jsonVal(o));
            }
            return "[" + String.join(",", parts) + "]";
        }
        return Jsons.quote(String.valueOf(v));
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
