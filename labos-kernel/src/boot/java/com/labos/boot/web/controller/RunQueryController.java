package com.labos.boot.web.controller;

import com.labos.boot.web.service.RunService;
import com.labos.kernel.persistence.RunMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;

import static com.labos.kernel.api.ApiServer.err;

/**
 * 执行域**只读**端点（15 个）——纯 HTTP 层，业务在 {@link RunService} 与
 * {@link QueryService}，数据访问在 Mapper。
 *
 * <p>路径注意：{@code /api/runs/{id}} 必须限定 {@code \d+}，否则
 * {@code /runs/paused}、{@code /quarantined}、{@code /recoverable}
 * 会被当成 id 匹配进来（类型转换失败 → 400）。
 */
@RestController
@RequestMapping("/api")
public class RunQueryController {

    private final RunService runService;
    
    public RunQueryController(RunService runService) {
        this.runService = runService;
            }

    @GetMapping(value = "/manual-windows", produces = "application/json; charset=utf-8")
    public String manualWindows() throws SQLException {
        return runService.manualWindows();
    }

    @GetMapping(value = "/command-outbox", produces = "application/json; charset=utf-8")
    public String commandOutbox(@RequestParam(required = false) String state,
                                @RequestParam(required = false) String limit) throws SQLException {
        return runService.commandOutboxJson(state, intOr(limit, 100));
    }

    @GetMapping(value = "/transfers", produces = "application/json; charset=utf-8")
    public String transfers(@RequestParam(required = false) String status,
                            @RequestParam(required = false) String limit) throws SQLException {
        return runService.transferTransactionsJson(status, intOr(limit, 100));
    }

    @GetMapping(value = "/plate-position-evidence",
            produces = "application/json; charset=utf-8")
    public String positionEvidence(@RequestParam(required = false) String plateBarcode,
                                   @RequestParam(required = false) String limit)
            throws SQLException {
        return runService.positionEvidenceJson(plateBarcode, intOr(limit, 100));
    }

    @GetMapping(value = "/runs/quarantined", produces = "application/json; charset=utf-8")
    public String quarantinedRuns(@RequestParam(required = false) String limit)
            throws SQLException {
        return runService.quarantinedRunsJson(intOr(limit, 100));
    }

    @GetMapping(value = "/intervention-records", produces = "application/json; charset=utf-8")
    public String interventionRecords(@RequestParam(required = false) String limit)
            throws SQLException {
        return runService.interventionRecordsJson(intOr(limit, 100));
    }

    /** 命令级台账：这个 run 下发过哪些命令、什么参数、发出/完成时刻、耗时。 */
    @GetMapping(value = "/runs/{id:\\d+}/commands", produces = "application/json; charset=utf-8")
    public String runCommands(@PathVariable long id) throws SQLException {
        return runService.runCommandsJson(id);
    }

    /**
     * 运行期变量表：这条执行**当前**的变量值（以及它声明了哪些变量、还没值的是哪些）。
     *
     * <p>为什么要有这个接口：变量以前只能在设计器里声明，运行期到底"是多少"
     * 只有跑的人才知道（现场要对着参数看板数值）。</p>
     */
    @GetMapping(value = "/runs/{id:\\d+}/variables", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> runVariables(@PathVariable long id) throws SQLException {
        String body = runService.runVariablesJson(id);
        if (body == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(err("run not found"));
        }
        return ResponseEntity.ok(body);
    }

    @GetMapping(value = "/runtime/holds", produces = "application/json; charset=utf-8")
    public String runtimeHolds() throws SQLException {
        return runService.holds();
    }

    @GetMapping(value = "/runs/paused", produces = "application/json; charset=utf-8")
    public String pausedRuns() throws SQLException {
        return runService.pausedRuns();
    }

    @GetMapping(value = "/plates/unknown", produces = "application/json; charset=utf-8")
    public String unknownPlates() throws SQLException {
        return runService.unknownPlates();
    }

    @GetMapping(value = "/plates/in-transit", produces = "application/json; charset=utf-8")
    public String inTransitPlates() throws SQLException {
        return runService.inTransitPlates();
    }

    /**
     * 板子**流转轨迹**：谁在什么时候把板子放/取到了哪个位置。
     *
     * <p>board=PL-001 看单个板的轨迹；不带 board 看最近的流转流水。</p>
     * <p>experimentId 限定「一个实验里面」板子怎么流转；runId 再缩小到某张执行单。
     * 两者都不带 = 全部（含独立 workflow 执行与人工盘点）。</p>
     */
    @GetMapping(value = "/plates/traces", produces = "application/json; charset=utf-8")
    public String plateTraces(@RequestParam(required = false) String barcode,
                               @RequestParam(required = false) Integer limit,
                               @RequestParam(required = false) Long experimentId,
                               @RequestParam(required = false) Long runId)
            throws SQLException {
        return runService.plateTracesJson(barcode, limit == null ? 200 : limit,
                experimentId, runId);
    }

    /**
     * 一个执行单内板子的**完整流转时间线**：搬运（源设备→目标设备）+ 位置状态变更 + 位置证据。
     *
     * <p>与 {@code /plates/traces} 的分工：后者跨实验检索板号；本端点是
     * 「在一个实验里面，看板子是怎么走的」，每条搬运自带 from/to 设备码。
     *
     * @param id      执行单 id
     * @param barcode 可选；只看某一块板
     */
    @GetMapping(value = "/runs/{id:\\d+}/plate-flow", produces = "application/json; charset=utf-8")
    public String runPlateFlow(@PathVariable long id,
                               @RequestParam(required = false) String barcode)
            throws SQLException {
        return runService.plateFlowJson(id, barcode);
    }

    @GetMapping(value = "/runs/{id:\\d+}/graph", produces = "application/json; charset=utf-8")
    public String runGraph(@PathVariable long id) throws SQLException {
        return runService.runGraphJson(id);
    }

    @GetMapping(value = "/runs/{id:\\d+}/timeline", produces = "application/json; charset=utf-8")
    public String runTimeline(@PathVariable long id) throws SQLException {
        return runService.timelineJson(id);
    }

    @GetMapping(value = "/runs/recoverable", produces = "application/json; charset=utf-8")
    public String recoverable() throws SQLException {
        return runService.recoverable();
    }

    @GetMapping(value = "/process-status", produces = "application/json; charset=utf-8")
    public String processStatus() throws SQLException {
        return runService.processStatusJson();
    }

    @GetMapping(value = "/runs", produces = "application/json; charset=utf-8")
    public String recentRuns(@RequestParam(required = false) String limit) throws SQLException {
        return runService.recentRuns(intOr(limit, 20));
    }

    @GetMapping(value = "/runs/{id:\\d+}", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> runDetail(@PathVariable long id) throws SQLException {
        String body = runService.runDetail(id);
        if (body == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(err("run not found"));
        }
        return ResponseEntity.ok(body);
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
