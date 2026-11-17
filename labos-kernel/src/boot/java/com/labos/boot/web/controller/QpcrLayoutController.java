package com.labos.boot.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.labos.boot.web.service.TwinDiffService;
import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.Jsons;
import com.labos.kernel.persistence.Tx;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.labos.kernel.api.ApiServer.err;

/**
 * qPCR 96→384 孔板布局落库 + PyLabRobot 仿真下发。
 *
 * <p>为什么是独立 Controller 而不是塞进 StockController：这里写的是
 * {@code plate / plate_well_design}（物料/孔位域），但语义是「实验设计结果」，
 * 两者消费者不同 —— {@code plate_well_design} 是**设计态**孔位真源（实验设计的意图体积），
 * {@code plate.attr} 存 qPCR 专属元数据（靶点/通道/每孔映射/校验/仿真摘要）。
 *
 * <p><b>设计态 / 运行态的边界（Q10 已裁决：拆表）</b>：本控制器是
 * {@code plate_well_design} 的**唯一写入方**，只写「打算做什么」。
 * {@code plate_well} 是**运行态**孔位表，写「实际发生了什么」，唯一写入方是物料投影 T2
 * （TwinDiff 的 db 来源、L0 物理闸门、审计都读它）。两张表列名/类型/精度全同，
 * 用 {@code plate_id} 关联但**不做值同步** —— 差异本身就是诊断信号。
 *
 * <p>三条硬规则（对齐前端 qpcrLayout.ts 的「AI 出方案、规则校验、系统执行」）：
 * <ul>
 *   <li>save 只接受**已经过前端确定性校验**的布局；后端再兜底查一遍体积/孔位重复。</li>
 *   <li>写库用事务：plate 与 384 行 plate_well_design 要么全成，要么全回滚。</li>
 *   <li>simulate 不直接碰真机、不碰内核命令链：跑独立 Python 仿真器拿逐孔体积报告，
 *       返回给页面人工核对。真机下发是后续 sidecar 的事。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class QpcrLayoutController {

    private static final long TENANT = 1L;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DbConfig db;
    private final TwinDiffService twinDiff;

    public QpcrLayoutController(DbConfig db, TwinDiffService twinDiff) {
        this.db = db;
        this.twinDiff = twinDiff;
    }

    /** 前端 qPCR 页保存：POST /api/qpcr-layout */
    @PostMapping(value = "/qpcr-layout", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> save(@RequestBody(required = false) String body,
                                       HttpServletRequest req) {
        try {
            JsonNode n = JSON.readTree(body == null ? "{}" : body);
            String barcode = text(n, "plateBarcode");
            if (barcode.isBlank()) return bad("plateBarcode 必填");
            JsonNode wells = n.path("wells");
            if (!wells.isArray() || wells.isEmpty()) return bad("wells 必须是非空数组");
            if (wells.size() > 384) return bad("384 板最多 384 孔，实际 " + wells.size());

            // 后端兜底校验：孔位唯一、体积为正、row/col 在 384 范围内
            boolean[][] seen = new boolean[16][24];
            for (JsonNode w : wells) {
                int row = w.path("wellRow").asInt(-1);
                int col = w.path("wellCol").asInt(-1);
                double vol = w.path("volume").asDouble(-1);
                if (row < 0 || row >= 16 || col < 0 || col >= 24) {
                    return bad("孔位越界：row=" + row + ", col=" + col);
                }
                if (vol <= 0) return bad("体积必须 > 0：" + row + "/" + col);
                if (seen[row][col]) return bad("孔位重复：" + row + "/" + col);
                seen[row][col] = true;
            }

            // attr 存整份布局元数据（靶点配置 + 每孔映射 + 校验摘要），不新增表
            ObjectNode attr = JSON.createObjectNode();
            attr.put("kind", "qpcr_96_384_layout");
            attr.put("sourceBarcode", text(n, "sourceBarcode"));
            attr.put("sampleCount", n.path("sampleCount").asInt(0));
            attr.put("replicates", n.path("replicates").asInt(1));
            attr.put("masterMixVol", n.path("masterMixVol").asDouble(0));
            attr.put("sampleVol", n.path("sampleVol").asDouble(0));
            // 体积口径（必须与前端 buildSaveBody / qpcr_sim.py 三处一致）：
            // plate_well.volume = 本段 worklist 真正转移的样品体积；MasterMix 是预分装，
            // 只记在 attr 里，不计入 plate_well，否则 TwinDiff 仿真(5µL) vs 落库(20µL) 必然对不上。
            attr.put("volumeSemantics", "transferred_sample_ul");
            attr.set("targets", n.path("targets").isArray() ? n.path("targets") : JSON.createArrayNode());
            attr.set("wells", wells);
            String attrJson = JSON.writeValueAsString(attr);

            int[] saved = Tx.inTransaction(db, c -> {
                // 1) upsert plate（384 板），attr 覆盖
                String upPlate = """
                    INSERT INTO plate (tenant_id, barcode, plate_type, rows, cols, status, attr, updated_at)
                    VALUES (?, ?, '384', 16, 24, 'filled', ?::jsonb, now())
                    ON CONFLICT (tenant_id, barcode) DO UPDATE
                       SET plate_type = '384', rows = 16, cols = 24,
                           status = 'filled', attr = EXCLUDED.attr, updated_at = now()
                    RETURNING id
                    """;
                long plateId;
                try (PreparedStatement ps = c.prepareStatement(upPlate)) {
                    ps.setLong(1, TENANT);
                    ps.setString(2, barcode.strip());
                    ps.setString(3, attrJson);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) throw new IllegalStateException("plate upsert 未返回 id");
                        plateId = rs.getLong(1);
                    }
                }
                // 2) 先清旧孔（重保存 = 整板替换），再逐孔插入
                //    注意：写的是**设计态**表 plate_well_design（Q10 拆表）。
                //    运行态 plate_well 由物料投影 T2 写，本控制器**不得**触碰。
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM plate_well_design WHERE plate_id = ?")) {
                    ps.setLong(1, plateId);
                    ps.executeUpdate();
                }
                String ins = """
                    INSERT INTO plate_well_design
                      (tenant_id, plate_id, well_row, well_col, well_label, volume, status, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, now())
                    ON CONFLICT (plate_id, well_row, well_col) DO UPDATE
                       SET well_label = EXCLUDED.well_label, volume = EXCLUDED.volume,
                           status = EXCLUDED.status, updated_at = now()
                    """;
                int count = 0;
                try (PreparedStatement ps = c.prepareStatement(ins)) {
                    for (JsonNode w : wells) {
                        ps.setLong(1, TENANT);
                        ps.setLong(2, plateId);
                        ps.setInt(3, w.path("wellRow").asInt());
                        ps.setInt(4, w.path("wellCol").asInt());
                        ps.setString(5, w.path("wellLabel").asText());
                        ps.setBigDecimal(6, w.path("volume").decimalValue());
                        ps.setString(7, "planned");   // 设计态：已规划待执行（运行态才用 filled/consumed）
                        count += ps.executeUpdate();
                    }
                }
                return new int[]{(int) plateId, count};
            });

            return ok(Map.of("saved", saved[1], "plateId", saved[0], "barcode", barcode.strip()));
        } catch (Exception e) {
            return writeError(e);
        }
    }

    /** 读回：GET /api/qpcr-layout?barcode=xxx */
    @GetMapping(value = "/qpcr-layout", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> load(@RequestParam String barcode) {
        try (var c = db.open()) {
            String q = """
                SELECT p.id, p.plate_type, p.rows, p.cols, p.attr,
                       w.well_row, w.well_col, w.well_label, w.volume, w.status
                FROM plate p
                LEFT JOIN plate_well_design w ON w.plate_id = p.id
                WHERE p.tenant_id = ? AND p.barcode = ?
                ORDER BY w.well_row, w.well_col
                """;
            List<Object> wells = new ArrayList<>();
            Long plateId = null;
            JsonNode attr = JSON.createObjectNode();
            try (PreparedStatement ps = c.prepareStatement(q)) {
                ps.setLong(1, TENANT);
                ps.setString(2, barcode.strip());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        plateId = rs.getLong("id");
                        String a = rs.getString("attr");
                        if (a != null && !a.isBlank()) {
                            try { attr = JSON.readTree(a); } catch (Exception ignored) { }
                        }
                        if (rs.getObject("well_row") == null) continue;
                        ObjectNode w = JSON.createObjectNode();
                        w.put("wellRow", rs.getInt("well_row"));
                        w.put("wellCol", rs.getInt("well_col"));
                        w.put("wellLabel", rs.getString("well_label"));
                        w.put("volume", rs.getBigDecimal("volume"));
                        w.put("status", rs.getString("status"));
                        wells.add(w);
                    }
                }
            }
            if (plateId == null) return notFound("未找到板：" + barcode);
            ObjectNode out = JSON.createObjectNode();
            out.put("barcode", barcode.strip());
            out.put("plateId", plateId);
            out.set("attr", attr);
            out.set("wells", JSON.valueToTree(wells));
            return ok(JSON.writeValueAsString(out));
        } catch (Exception e) {
            return writeError(e);
        }
    }

    /** 仿真验证：POST /api/qpcr-layout/simulate —— 下发 worklist 给 Python PyLabRobot 仿真器 */
    @PostMapping(value = "/qpcr-layout/simulate", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> simulate(@RequestBody(required = false) String body) {
        try {
            JsonNode n = JSON.readTree(body == null ? "{}" : body);
            JsonNode transfers = n.path("transfers");
            if (!transfers.isArray() || transfers.isEmpty()) return bad("transfers 必须是非空数组");

            Path script = resolveSimScript();
            if (script == null || !Files.exists(script)) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(err("未找到 qpcr_sim.py（应放在仓库 pylabrobot-sim/ 下）"));
            }
            String py = System.getenv().getOrDefault("LABOS_QPCR_PYTHON",
                    script.getParent().resolve(".venv/bin/python").toString());
            if (!Files.exists(Paths.get(py))) py = "python3";

            boolean compareDb = n.path("compareDb").asBoolean(false);
            Path outFile = compareDb ? Files.createTempFile("qpcr-twin-", ".json") : null;
            List<String> cmd = new ArrayList<>();
            cmd.add(py);
            cmd.add(script.toString());
            if (compareDb) {
                cmd.add("--compare-db");
                cmd.add("--out");
                cmd.add(outFile.toString());
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(script.getParent().toFile());
            pb.redirectErrorStream(false);
            Process proc = pb.start();
            // stdin 写 JSON（transfers + 配置）
            try (var os = proc.getOutputStream()) {
                os.write(JSON.writeValueAsBytes(n));
            }
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) stdout.append(line).append('\n');
            }
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) stderr.append(line).append('\n');
            }
            int rc = proc.waitFor();
            if (rc != 0) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(err("仿真器退出码 " + rc + "：" + stderr.toString().strip()));
            }
            // 仿真器 stdout 就是报告 JSON；compareDb 时从 --out 文件读 TwinDiff 报告
            String raw = compareDb
                    ? Files.readString(outFile, StandardCharsets.UTF_8)
                    : stdout.toString();
            JsonNode report = JSON.readTree(raw.strip());
            if (outFile != null) Files.deleteIfExists(outFile);

            if (compareDb) {
                // 后端直接入库，Python 不用登录、不用 urllib（职责单一）
                String uid = report.path("run_id").asText("qpcr-twin-unknown");
                String status = report.path("status").asText("FAIL");
                String pushed = twinDiff.push(
                        uid,
                        report.path("job_id").asText(""),
                        report.path("device_id").asText(""),
                        "db",
                        text(report.path("twin_source"), "kind") + ":" + text(n, "targetBarcode"),
                        status,
                        report.path("job_status").asText(""),
                        JSON.writeValueAsString(report),
                        "qpcr-sim");
                ObjectNode wrapped = (ObjectNode) JSON.createObjectNode();
                wrapped.set("report", report);
                wrapped.set("twinDiffPush", JSON.readTree(pushed));
                return ok(JSON.writeValueAsString(wrapped));
            }
            return ok(JSON.writeValueAsString(report));
        } catch (Exception e) {
            return writeError(e);
        }
    }

    /** 依次尝试：环境变量 > 仓库根 pylabrobot-sim/ > 当前工作目录 */
    private static Path resolveSimScript() {
        String env = System.getenv("LABOS_QPCR_SIM");
        if (env != null && !env.isBlank()) return Paths.get(env);
        Path cwd = Paths.get("").toAbsolutePath();
        Path p = cwd.resolve("pylabrobot-sim/qpcr_sim.py");
        if (Files.exists(p)) return p;
        // cwd 可能是 labos-kernel/，向上一级找
        Path parent = cwd.getParent();
        return parent == null ? p : parent.resolve("pylabrobot-sim/qpcr_sim.py");
    }

    // ---------------------------------------------------------------- 工具

    private static String text(JsonNode n, String key) {
        JsonNode v = n.path(key);
        return v.isMissingNode() || v.isNull() ? "" : v.asText();
    }

    private static ResponseEntity<String> bad(String msg) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err(msg));
    }

    private static ResponseEntity<String> notFound(String msg) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(err(msg));
    }

    /** 统一走 ApiErrors：不再吞真因（带 detail），2026-11-17。 */
    private static ResponseEntity<String> writeError(Exception e) {
        return com.labos.boot.web.ApiErrors.writeError(e);
    }

    private static ResponseEntity<String> ok(String json) {
        return ResponseEntity.ok(json);
    }

    private static ResponseEntity<String> ok(Object o) {
        try { return ok(JSON.writeValueAsString(o)); }
        catch (Exception e) { return bad("序列化失败：" + e.getMessage()); }
    }
}
