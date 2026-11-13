package com.labos.boot.web.controller;

import com.labos.boot.web.ApiAuthInterceptor;
import com.labos.boot.web.AuditRecorder;
import com.labos.kernel.api.ApiServer;
import com.labos.kernel.api.AuthService;
import com.labos.kernel.persistence.ConsumableMapper;
import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.Jsons;
import com.labos.kernel.persistence.Mappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.labos.kernel.api.ApiServer.err;
import static com.labos.kernel.api.ApiServer.jsonBool;
import static com.labos.kernel.api.ApiServer.jsonField;
import static com.labos.kernel.api.ApiServer.jsonInt;
import static com.labos.kernel.api.ApiServer.jsonLong;

/**
 * 耗材/试剂目录 + 库存批次 + 点位配置（运维域，位置/耗材管理）。
 *
 * <p>统一「点位内容」模型：批次挂到某点位时，除写 {@code xxx_lot.location_id/device_id} 外，
 * 还写 {@code position_state.content_kind/content_id}（content_id = 批次 id），
 * 让「从位置看」和「从耗材/试剂看」两条链路收敛到同一份点位内容真源。
 * 位置视图（{@code GET /api/positions}）按 content_kind/content_id 关联出内容名称。
 */
@RestController
@RequestMapping("/api")
public class StockController {

    private static final long TENANT = 1L;
    private static final Set<String> POINT_ROLES = Set.of("entry", "exit", "both", "internal");
    private static final Set<String> LOT_STATUS = Set.of("available", "reserved", "used", "expired", "discarded");

    private final DbConfig db;
    private final AuditRecorder audit;

    public StockController(DbConfig db, AuditRecorder audit) {
        this.db = db;
        this.audit = audit;
    }

    private ConsumableMapper m() {
        return Mappers.get(ConsumableMapper.class);
    }

    // ---------------------------------------------------------------- 耗材目录

    @GetMapping(value = "/consumables", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> consumables(
            @RequestParam(required = false) String islandId,
            @RequestParam(required = false) String category) {
        List<Map<String, Object>> rows = m().selectConsumables(TENANT,
                parseLongOrNull(islandId), blankToNull(category));
        return ok(Map.of("total", rows.size(), "items", rows));
    }

    @PostMapping(value = "/consumables", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> createConsumable(@RequestBody(required = false) String body,
                                                   HttpServletRequest req) {
        String code = jsonField(body, "code");
        String name = jsonField(body, "name");
        if (blank(code) || blank(name)) {
            return bad("必填 code 与 name");
        }
        String unit = blank(jsonField(body, "unit")) ? "pcs" : jsonField(body, "unit").trim();
        try {
            int n = m().insertConsumable(TENANT, code.trim(), name.trim(),
                    jsonField(body, "category"), jsonField(body, "specification"),
                    unit, jsonInt(body, "maxStack"));
            audit.record(session(req), "consumable.create", "consumable", 0L, null,
                    Jsons.obj("code", code.trim()), req.getRemoteAddr());
            return ok(Map.of("created", n, "code", code.trim()));
        } catch (Exception e) {
            return writeError(e, "code 可能重复");
        }
    }

    @PutMapping(value = "/consumables/{id:\\d+}", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> updateConsumable(@PathVariable long id,
                                                   @RequestBody(required = false) String body,
                                                   HttpServletRequest req) {
        try {
            int n = m().updateConsumable(id, jsonField(body, "name"), jsonField(body, "category"),
                    jsonField(body, "specification"), jsonField(body, "unit"),
                    jsonInt(body, "maxStack"));
            if (n == 0) {
                return notFound("耗材不存在: " + id);
            }
            audit.record(session(req), "consumable.update", "consumable", id, null,
                    null, req.getRemoteAddr());
            return ok(Map.of("updated", n, "id", id));
        } catch (Exception e) {
            return writeError(e, null);
        }
    }

    @DeleteMapping(value = "/consumables/{id:\\d+}", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> deleteConsumable(@PathVariable long id, HttpServletRequest req) {
        int n = m().deleteConsumable(TENANT, id);
        if (n == 0) {
            return notFound("耗材不存在: " + id);
        }
        audit.record(session(req), "consumable.delete", "consumable", id, null, null,
                req.getRemoteAddr());
        return ok(Map.of("deleted", n, "id", id));
    }

    // ---------------------------------------------------------------- 耗材库存

    @GetMapping(value = "/consumable-lots", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> lots(
            @RequestParam(required = false) String consumableId,
            @RequestParam(required = false) String islandId) {
        List<Map<String, Object>> rows = m().selectLots(TENANT,
                parseLongOrNull(consumableId), parseLongOrNull(islandId));
        return ok(Map.of("total", rows.size(), "items", rows));
    }

    @PostMapping(value = "/consumable-lots", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> createLot(@RequestBody(required = false) String body,
                                            HttpServletRequest req) {
        Long consumableId = jsonLong(body, "consumableId");
        String lotNo = jsonField(body, "lotNo");
        Integer quantity = jsonInt(body, "quantity");
        if (consumableId == null || quantity == null || quantity < 0) {
            return bad("必填 consumableId 与 quantity（>=0）");
        }
        Integer remaining = jsonInt(body, "remaining");
        if (remaining == null) {
            remaining = quantity;
        }
        if (remaining < 0 || remaining > quantity) {
            return bad("remaining 必须在 [0, quantity] 内");
        }
        String status = normalizeStatus(jsonField(body, "status"));
        if (status == null) {
            return bad("status 非法");
        }
        Long locationId = jsonLong(body, "locationId");
        Long deviceId = jsonLong(body, "deviceId");
        if (locationId != null && deviceId == null) {
            deviceId = m().selectParentDeviceId(TENANT, locationId);
        }
        try {
            int n = m().insertLot(TENANT, consumableId, lotNo, jsonField(body, "barcode"),
                    quantity, remaining, locationId, deviceId, jsonField(body, "expireAt"), status);
            // 统一点位内容：批次挂到点位后写 position_state
            if (locationId != null) {
                Long lotId = m().selectLotIdByNo(TENANT, consumableId, lotNo);
                if (lotId != null) {
                    m().upsertPositionContent(TENANT, locationId, "consumable", lotId);
                }
            }
            audit.record(session(req), "consumableLot.create", "consumable_lot", 0L, null,
                    Jsons.obj("consumableId", consumableId, "lotNo", lotNo == null ? "" : lotNo),
                    req.getRemoteAddr());
            return ok(Map.of("created", n, "consumableId", consumableId));
        } catch (Exception e) {
            return writeError(e, "lotNo 可能重复");
        }
    }

    @PutMapping(value = "/consumable-lots/{id:\\d+}", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> updateLot(@PathVariable long id,
                                            @RequestBody(required = false) String body,
                                            HttpServletRequest req) {
        Integer remaining = jsonInt(body, "remaining");
        if (remaining == null || remaining < 0) {
            return bad("必填 remaining（>=0）");
        }
        String status = blank(jsonField(body, "status")) ? null : jsonField(body, "status");
        if (status != null && !LOT_STATUS.contains(status)) {
            return bad("status 非法：" + status);
        }
        Long oldLoc = null;
        Map<String, Object> old = m().selectLot(TENANT, id);
        if (old != null) {
            oldLoc = asLong(old, "locationId");
        }
        Long newLoc = jsonLong(body, "locationId");
        Long deviceId = jsonLong(body, "deviceId");
        if (newLoc != null && deviceId == null) {
            deviceId = m().selectParentDeviceId(TENANT, newLoc);
        }
        try {
            int n = m().updateLot(id, remaining, newLoc, deviceId,
                    jsonField(body, "expireAt"), status);
            if (n == 0) {
                return notFound("批次不存在: " + id);
            }
            syncPositionContent(oldLoc, newLoc, "consumable", id);
            audit.record(session(req), "consumableLot.update", "consumable_lot", id, null,
                    null, req.getRemoteAddr());
            return ok(Map.of("updated", n, "id", id));
        } catch (Exception e) {
            return writeError(e, "remaining 可能越界（必须在 [0, quantity] 内）");
        }
    }

    @DeleteMapping(value = "/consumable-lots/{id:\\d+}", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> deleteLot(@PathVariable long id, HttpServletRequest req) {
        Map<String, Object> lot = m().selectLot(TENANT, id);
        int n = m().deleteLot(TENANT, id);
        if (n == 0) {
            return notFound("批次不存在: " + id);
        }
        Long loc = lot == null ? null : asLong(lot, "locationId");
        if (loc != null) {
            m().clearPositionContent(TENANT, loc);
        }
        audit.record(session(req), "consumableLot.delete", "consumable_lot", id, null,
                null, req.getRemoteAddr());
        return ok(Map.of("deleted", n, "id", id));
    }

    // ---------------------------------------------------------------- 试剂目录

    @GetMapping(value = "/reagents", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> reagents(
            @RequestParam(required = false) String islandId,
            @RequestParam(required = false) String category) {
        List<Map<String, Object>> rows = m().selectReagents(TENANT,
                parseLongOrNull(islandId), blankToNull(category));
        return ok(Map.of("total", rows.size(), "items", rows));
    }

    @PostMapping(value = "/reagents", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> createReagent(@RequestBody(required = false) String body,
                                                HttpServletRequest req) {
        String code = jsonField(body, "code");
        String name = jsonField(body, "name");
        if (blank(code) || blank(name)) {
            return bad("必填 code 与 name");
        }
        String unit = blank(jsonField(body, "unit")) ? "uL" : jsonField(body, "unit").trim();
        try {
            int n = m().insertReagent(TENANT, code.trim(), name.trim(),
                    jsonField(body, "category"), jsonField(body, "specification"), unit);
            audit.record(session(req), "reagent.create", "reagent", 0L, null,
                    Jsons.obj("code", code.trim()), req.getRemoteAddr());
            return ok(Map.of("created", n, "code", code.trim()));
        } catch (Exception e) {
            return writeError(e, "code 可能重复");
        }
    }

    @PutMapping(value = "/reagents/{id:\\d+}", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> updateReagent(@PathVariable long id,
                                                @RequestBody(required = false) String body,
                                                HttpServletRequest req) {
        try {
            int n = m().updateReagent(id, jsonField(body, "name"), jsonField(body, "category"),
                    jsonField(body, "specification"), jsonField(body, "unit"));
            if (n == 0) {
                return notFound("试剂不存在: " + id);
            }
            audit.record(session(req), "reagent.update", "reagent", id, null, null,
                    req.getRemoteAddr());
            return ok(Map.of("updated", n, "id", id));
        } catch (Exception e) {
            return writeError(e, null);
        }
    }

    @DeleteMapping(value = "/reagents/{id:\\d+}", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> deleteReagent(@PathVariable long id, HttpServletRequest req) {
        int n = m().deleteReagent(TENANT, id);
        if (n == 0) {
            return notFound("试剂不存在: " + id);
        }
        audit.record(session(req), "reagent.delete", "reagent", id, null, null,
                req.getRemoteAddr());
        return ok(Map.of("deleted", n, "id", id));
    }

    // ---------------------------------------------------------------- 试剂库存

    @GetMapping(value = "/reagent-lots", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> reagentLots(
            @RequestParam(required = false) String reagentId,
            @RequestParam(required = false) String islandId) {
        List<Map<String, Object>> rows = m().selectReagentLots(TENANT,
                parseLongOrNull(reagentId), parseLongOrNull(islandId));
        return ok(Map.of("total", rows.size(), "items", rows));
    }

    @PostMapping(value = "/reagent-lots", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> createReagentLot(@RequestBody(required = false) String body,
                                                   HttpServletRequest req) {
        Long reagentId = jsonLong(body, "reagentId");
        String lotNo = jsonField(body, "lotNo");
        Double volume = jsonDouble(body, "volume");
        if (reagentId == null || volume == null || volume < 0) {
            return bad("必填 reagentId 与 volume（>=0）");
        }
        Double remaining = jsonDouble(body, "remaining");
        if (remaining == null) {
            remaining = volume;
        }
        if (remaining < 0 || remaining > volume) {
            return bad("remaining 必须在 [0, volume] 内");
        }
        String status = normalizeStatus(jsonField(body, "status"));
        if (status == null) {
            return bad("status 非法");
        }
        Long locationId = jsonLong(body, "locationId");
        Long deviceId = jsonLong(body, "deviceId");
        if (locationId != null && deviceId == null) {
            deviceId = m().selectParentDeviceId(TENANT, locationId);
        }
        try {
            int n = m().insertReagentLot(TENANT, reagentId, lotNo, volume, remaining,
                    locationId, deviceId, jsonField(body, "expireAt"), status);
            if (locationId != null) {
                Long lotId = m().selectReagentLotIdByNo(TENANT, reagentId, lotNo);
                if (lotId != null) {
                    m().upsertPositionContent(TENANT, locationId, "reagent", lotId);
                }
            }
            audit.record(session(req), "reagentLot.create", "reagent_lot", 0L, null,
                    Jsons.obj("reagentId", reagentId, "lotNo", lotNo == null ? "" : lotNo),
                    req.getRemoteAddr());
            return ok(Map.of("created", n, "reagentId", reagentId));
        } catch (Exception e) {
            return writeError(e, "lotNo 可能重复");
        }
    }

    @PutMapping(value = "/reagent-lots/{id:\\d+}", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> updateReagentLot(@PathVariable long id,
                                                   @RequestBody(required = false) String body,
                                                   HttpServletRequest req) {
        Double remaining = jsonDouble(body, "remaining");
        if (remaining == null || remaining < 0) {
            return bad("必填 remaining（>=0）");
        }
        String status = blank(jsonField(body, "status")) ? null : jsonField(body, "status");
        if (status != null && !LOT_STATUS.contains(status)) {
            return bad("status 非法：" + status);
        }
        Long oldLoc = null;
        Map<String, Object> old = m().selectReagentLot(TENANT, id);
        if (old != null) {
            oldLoc = asLong(old, "locationId");
        }
        Long newLoc = jsonLong(body, "locationId");
        Long deviceId = jsonLong(body, "deviceId");
        if (newLoc != null && deviceId == null) {
            deviceId = m().selectParentDeviceId(TENANT, newLoc);
        }
        try {
            int n = m().updateReagentLot(id, remaining, newLoc, deviceId,
                    jsonField(body, "expireAt"), status);
            if (n == 0) {
                return notFound("批次不存在: " + id);
            }
            syncPositionContent(oldLoc, newLoc, "reagent", id);
            audit.record(session(req), "reagentLot.update", "reagent_lot", id, null,
                    null, req.getRemoteAddr());
            return ok(Map.of("updated", n, "id", id));
        } catch (Exception e) {
            return writeError(e, "remaining 可能越界（必须在 [0, volume] 内）");
        }
    }

    @DeleteMapping(value = "/reagent-lots/{id:\\d+}", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> deleteReagentLot(@PathVariable long id, HttpServletRequest req) {
        Map<String, Object> lot = m().selectReagentLot(TENANT, id);
        int n = m().deleteReagentLot(TENANT, id);
        if (n == 0) {
            return notFound("批次不存在: " + id);
        }
        Long loc = lot == null ? null : asLong(lot, "locationId");
        if (loc != null) {
            m().clearPositionContent(TENANT, loc);
        }
        audit.record(session(req), "reagentLot.delete", "reagent_lot", id, null,
                null, req.getRemoteAddr());
        return ok(Map.of("deleted", n, "id", id));
    }

    // ---------------------------------------------------------------- 点位配置

    @PutMapping(value = "/device-points/{pointResourceId:\\d+}",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> updateDevicePoint(@PathVariable long pointResourceId,
                                                    @RequestBody(required = false) String body,
                                                    HttpServletRequest req) {
        String role = jsonField(body, "pointRole");
        if (role == null || !POINT_ROLES.contains(role)) {
            return bad("pointRole 非法：" + role);
        }
        Boolean rb = jsonBool(body, "robotReachable");
        boolean reachable = rb == null || rb;
        Boolean be = jsonBool(body, "isEntry");
        Boolean bx = jsonBool(body, "isExit");
        boolean isEntry = be != null && be;
        boolean isExit = bx != null && bx;
        if ("internal".equals(role)) {
            isEntry = false;
            isExit = false;
        } else if (!isEntry && !isExit) {
            return bad("非 internal 点位必须至少是进口或出口（isEntry/isExit 至少一个为 true）");
        }
        Integer order = jsonInt(body, "orderInDevice");
        Integer gridRow = jsonInt(body, "gridRow");
        Integer gridCol = jsonInt(body, "gridCol");
        Double maxHeightMm = jsonDouble(body, "maxHeightMm");
        String plateTypes = jsonField(body, "plateTypes");
        // 网格之外的单独位置（进出口装载口）+ 现场贴的位置标识。
        // 单独位置必须是机械臂能到的进口/出口，不能是内部格。
        Boolean standalone = jsonBool(body, "standalone");
        String positionLabel = jsonField(body, "positionLabel");
        if (standalone != null && standalone && "internal".equals(role)) {
            return bad("单独位置（进出口）不能设为内部位");
        }
        // 单独位置不属于「几行几列」：无论表单带了什么排/列值，一律清成 NULL。
        // 放在这里而不是 SQL 的 CASE 里，是因为 CASE 中 untyped 参数会被 PG 推断成
        // text，写回 smallint 列会报 "column grid_row is of type smallint"。
        if (standalone != null && standalone) {
            gridRow = null;
            gridCol = null;
        }
        try {
            int n = m().updateDevicePoint(TENANT, pointResourceId, role, reachable,
                    isEntry, isExit, order == null ? 0 : order, jsonField(body, "note"),
                    gridRow, gridCol, maxHeightMm, plateTypes, positionLabel, standalone);
            if (n == 0) {
                return notFound("点位未配置 device_point: " + pointResourceId);
            }
            audit.record(session(req), "devicePoint.update", "device_point", pointResourceId,
                    null, Jsons.obj("pointRole", role, "robotReachable", reachable),
                    req.getRemoteAddr());
            return ok(Map.of("updated", n, "pointResourceId", pointResourceId));
        } catch (Exception e) {
            return writeError(e, null);
        }
    }

    /**
     * 新建「网格之外的单独位置」：培养箱 / 耗材堆栈的进口 + 出口。
     *
     * <p>为什么需要接口而不是只靠脚本：现场给一台还没配点位的设备补一个进出口位置时，
     * 不该要求运维去跑 python 脚本。编码 = {@code 设备编码-后缀}（默认 {@code -IN}），
     * 同一编码已存在时复用并更新配置 —— 重复点「新建」不会造出第二个装载口。
     */
    @PostMapping(value = "/device-points/standalone",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> createStandalonePoint(
            @RequestBody(required = false) String body, HttpServletRequest req) {
        Long deviceResourceId = jsonLong(body, "deviceResourceId");
        if (deviceResourceId == null) {
            return bad("需要 deviceResourceId");
        }
        String deviceCode = m().selectDeviceCode(TENANT, deviceResourceId);
        if (deviceCode == null) {
            return notFound("设备不存在：" + deviceResourceId);
        }
        String suffix = jsonField(body, "codeSuffix");
        suffix = (suffix == null || suffix.isBlank()) ? "IN" : suffix.trim();
        if (!suffix.matches("[A-Za-z0-9_-]{1,24}")) {
            return bad("编码后缀只能字母/数字/下划线/短横线（≤24 字符）");
        }
        Boolean be = jsonBool(body, "isEntry");
        Boolean bx = jsonBool(body, "isExit");
        boolean isEntry = be == null || be;
        boolean isExit = bx == null || bx;
        if (!isEntry && !isExit) {
            return bad("单独位置至少要是进口或出口");
        }
        String pointCode = deviceCode + "-" + suffix;
        String label = jsonField(body, "positionLabel");
        if (label == null || label.isBlank()) {
            label = pointCode;
        }
        try {
            int n = m().insertStandalonePoint(TENANT, deviceResourceId, deviceCode,
                    pointCode, label, isEntry, isExit);
            audit.record(session(req), "devicePoint.createStandalone", "device_point",
                    deviceResourceId, null,
                    Jsons.obj("pointCode", pointCode, "isEntry", isEntry,
                            "isExit", isExit, "positionLabel", label),
                    req.getRemoteAddr());
            return ok(Map.of("pointCode", pointCode, "positionLabel", label,
                    "isEntry", isEntry, "isExit", isExit, "pointResourceId", n));
        } catch (Exception e) {
            return writeError(e, null);
        }
    }

    // ---------------------------------------------------------------- helpers

    /** 批次位置变更时，同步 position_state 的点位内容真源。 */
    private void syncPositionContent(Long oldLoc, Long newLoc, String kind, long lotId) {
        if (newLoc != null && !newLoc.equals(oldLoc)) {
            if (oldLoc != null) {
                m().clearPositionContent(TENANT, oldLoc);
            }
            m().upsertPositionContent(TENANT, newLoc, kind, lotId);
        } else if (newLoc == null && oldLoc != null) {
            m().clearPositionContent(TENANT, oldLoc);
        }
    }

    private static String normalizeStatus(String status) {
        String s = status == null ? "available" : status;
        return LOT_STATUS.contains(s) ? s : null;
    }

    private static Long asLong(Map<String, Object> row, String key) {
        Object v = row == null ? null : row.get(key);
        return v instanceof Number n ? n.longValue() : null;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return blank(s) ? null : s;
    }

    private static Long parseLongOrNull(String s) {
        if (blank(s)) {
            return null;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double jsonDouble(String body, String key) {
        if (body == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*\"?(-?\\d+(?:\\.\\d+)?)\"?").matcher(body);
        return m.find() ? Double.valueOf(m.group(1)) : null;
    }

    private static AuthService.Session session(HttpServletRequest req) {
        Object v = req.getAttribute(ApiAuthInterceptor.SESSION_ATTR);
        return v instanceof AuthService.Session s0 ? s0 : null;
    }

    private static ResponseEntity<String> bad(String msg) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err(msg));
    }

    private static ResponseEntity<String> notFound(String msg) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(err(msg));
    }

    private static ResponseEntity<String> writeError(Exception e, String hint) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (msg.contains("duplicate key") || msg.contains("violates unique")
                || msg.contains("uk_")) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(err(hint != null ? hint : "唯一约束冲突"));
        }
        if (msg.contains("ck_") || msg.contains("check constraint")) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(err(hint != null ? hint : "数据约束冲突"));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(err("写失败：" + msg));
    }

    private static final ObjectMapper JSON = new ObjectMapper()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static String json(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static ResponseEntity<String> ok(Object o) {
        return ResponseEntity.ok(json(o));
    }
}
