package com.labos.boot.web.controller;

import com.labos.boot.web.ApiAuthInterceptor;
import com.labos.boot.web.AuditRecorder;
import com.labos.kernel.api.ApiServer;
import com.labos.kernel.api.AuthService;
import com.labos.boot.web.service.DeviceService;
import com.labos.kernel.driver.HeartbeatService;
import com.labos.kernel.driver.ShadowProbe;
import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.DeviceStateMapper;
import com.labos.kernel.persistence.Jsons;
import com.labos.kernel.persistence.Mappers;
import com.labos.kernel.driver.PositionLock;
import com.labos.kernel.workflow.WorkflowService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.labos.kernel.persistence.QJson.extractXYZ;
import static com.labos.kernel.api.ApiServer.err;
import static com.labos.kernel.api.ApiServer.jsonField;
import static com.labos.kernel.api.ApiServer.jsonInt;
import static com.labos.kernel.api.ApiServer.jsonLong;
import static com.labos.kernel.api.ApiServer.paramLong;
import static com.labos.kernel.api.ApiServer.paramStr;

/**
 * 设备/岛/型号/点位域 Controller（25 个端点）——纯 HTTP 层。
 *
 * <p>读与写都经 {@code DeviceService}（按域拆自原 {@code QueryService}），
 * 岛锁等执行语义在 {@code WorkflowService} 里；本类只做
 * 参数解析、审计与响应，不 new 任何工具类。
 *
 * <p>捕获采集（{@code /devices/{id}/methods/.../capture}）里 ShadowProbe
 * 需要 {@code device_instance.id} 而不是 resource_node.id（这个坑出现过五次），
 * 已用 {@code DeviceService.instanceIdOf} 转换。
 */
@RestController
@RequestMapping("/api")
public class DeviceController {

    private static final long TENANT = 1L;

    private final DeviceService deviceService;
    private final WorkflowService workflows;
    private final DbConfig db;
    private final HeartbeatService heartbeat;
    private final AuditRecorder audit;

    public DeviceController(DeviceService deviceService,
                            WorkflowService workflows, DbConfig db,
                            HeartbeatService heartbeat, AuditRecorder audit) {
        this.deviceService = deviceService;
        this.workflows = workflows;
        this.db = db;
        this.heartbeat = heartbeat;
        this.audit = audit;
    }

    /** 原路由对 IllegalArgumentException 统一 400（如「必须指定所属岛屿」）。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err(e.getMessage()));
    }

    // ---------------------------------------------------------------- 型号部署

    @PostMapping(value = "/device-models/{id:\\d+}/deploy",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> deployModel(
            @PathVariable long id,
            @RequestParam(required = false) String islandId,
            HttpServletRequest req) throws SQLException {
        long toIsl = paramLongValue(islandId);
        if (toIsl <= 0) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err("需要 islandId"));
        }
        // 部署时就能填 IP 与驱动（原来只能建完再回头补，现场很容易漏 ⇒ 设备点不动）
        String ip = paramStr(req.getQueryString(), "ip");
        Long driverId = paramLong(req.getQueryString(), "driverId");
        long newRid = deviceService.deployModel(id, toIsl, ip, driverId);
        audit.record(session(req), "device.deploy", "device", newRid, "model=" + id,
                Jsons.obj("islandId", toIsl, "ip", ip, "driverId", driverId),
                req.getRemoteAddr());
        return ResponseEntity.ok(Jsons.obj("resourceId", newRid, "modelId", id,
                "ip", ip, "driverId", driverId));
    }

    // ---------------------------------------------------------------- 位置 / 心跳

    @GetMapping(value = "/positions", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> positions(@RequestParam(required = false) String islandId)
            throws SQLException {
        long isl = paramLongValue(islandId);
        if (isl <= 0) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err("需要 islandId"));
        }
        PositionLock lock = new PositionLock(db, TENANT);
        List<String> items = new ArrayList<>();
        for (PositionLock.PositionState st : lock.byIsland(isl)) {
            items.add(Jsons.obj(
                    "resourceId", st.resourceId(),
                    "pointId", st.pointId(),
                    "code", st.code(),
                    "name", st.name(),
                    "kind", st.kind(),
                    "plateId", st.plateId(),
                    "reserved", st.reserved(),
                    "reservedByRun", st.reservedByRun(),
                    "occupiedSince", st.occupiedSince(),
                    "available", st.available(),
                    "parentDeviceId", st.parentDeviceId(),
                    "parentDeviceCode", st.parentDeviceCode(),
                    "pointRole", st.pointRole(),
                    "robotReachable", st.robotReachable(),
                    "isEntry", st.isEntry(),
                    "isExit", st.isExit(),
                    "contentKind", st.contentKind(),
                    "plateUid", st.plateUid(),
                    "identityStatus", st.identityStatus(),
                    "contentCode", st.contentCode(),
                    "contentName", st.contentName(),
                    "gridRow", st.gridRow(),
                    "gridCol", st.gridCol(),
                    "maxHeightMm", st.maxHeightMm(),
                    "plateTypes", st.plateTypes(),
                    "positionLabel", st.positionLabel(),
                    "standalone", st.standalone()));
        }
        return ResponseEntity.ok(Jsons.obj("islandId", isl,
                "total", items.size(),
                "items", new Jsons.Raw(Jsons.arr(items))));
    }

    /** P2：实验开始前的点位与物料清单快照（append-only，按 run 查询）。 */
    @GetMapping(value = "/positions/snapshot", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> positionSnapshot(
            @RequestParam(required = false) String runId) throws SQLException {
        long rid = paramLongValue(runId);
        if (rid <= 0) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err("需要 runId"));
        }
        List<String> items = new ArrayList<>();
        for (Map<String, Object> row : Mappers.get(DeviceStateMapper.class)
                .selectRunPositionSnapshot(TENANT, rid)) {
            items.add(Jsons.obj(
                    "pointCode", row.get("pointCode"),
                    "deviceResourceId", row.get("deviceResourceId"),
                    "pointRole", row.get("pointRole"),
                    "robotReachable", row.get("robotReachable"),
                    "occupied", row.get("occupied"),
                    "plateBarcode", row.get("plateBarcode"),
                    "plateUid", row.get("plateUid"),
                    "contentKind", row.get("contentKind"),
                    "reservedByRun", row.get("reservedByRun")));
        }
        return ResponseEntity.ok(Jsons.obj("runId", rid, "total", items.size(),
                "items", new Jsons.Raw(Jsons.arr(items))));
    }

    @GetMapping(value = "/heartbeat", produces = "application/json; charset=utf-8")
    public String heartbeat() {
        Map<String, Boolean> r = heartbeat.lastResults();
        List<String> items = new ArrayList<>();
        r.forEach((k, v) -> items.add(Jsons.obj("driver", k, "online", v)));
        return Jsons.obj(
                "lastRunAt", heartbeat.lastRunAt() == 0 ? null
                        : new java.sql.Timestamp(heartbeat.lastRunAt()).toInstant().toString(),
                "drivers", new Jsons.Raw(Jsons.arr(items)));
    }

    @GetMapping(value = "/positions/stats", produces = "application/json; charset=utf-8")
    public String positionStats() throws SQLException {
        Map<String, Object> st = new PositionLock(db, TENANT).stats();
        return Jsons.obj("withPlate", st.get("withPlate"),
                "reserved", st.get("reserved"),
                "tracked", st.get("tracked"));
    }

    // ---------------------------------------------------------------- 型号库

    @GetMapping(value = "/device-models", produces = "application/json; charset=utf-8")
    public String deviceModels() throws SQLException {
        return deviceService.deviceModelsJson();
    }

    // ---------------------------------------------------------------- 设备 CRUD

    @PostMapping(value = "/devices", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> createDevice(
            @RequestBody(required = false) String body,
            HttpServletRequest req) throws SQLException {
        Integer islandId = jsonInt(body, "islandId");
        if (islandId == null) {
            throw new IllegalArgumentException("必须指定所属岛屿");
        }
        long rid = deviceService.createDevice(
                islandId.longValue(),
                jsonField(body, "code"),
                jsonField(body, "name"),
                jsonField(body, "deviceType"),
                jsonField(body, "capability"),
                jsonField(body, "ip"),
                jsonInt(body, "driverId") == null ? null
                        : jsonInt(body, "driverId").longValue());
        audit.record(session(req), "device.create", "device", rid,
                jsonField(body, "code"),
                Jsons.obj("islandId", islandId,
                        "ip", jsonField(body, "ip"),
                        "capability", jsonField(body, "capability"),
                        "driverId", jsonInt(body, "driverId")),
                req.getRemoteAddr());
        return ResponseEntity.ok(Jsons.obj("resourceId", rid));
    }

    @PutMapping(value = "/devices/{id:\\d+}", produces = "application/json; charset=utf-8")
    public String updateDevice(@PathVariable long id,
                               @RequestBody(required = false) String body,
                               HttpServletRequest req) throws SQLException {
        int n = deviceService.updateDevice(id,
                jsonField(body, "name"),
                jsonField(body, "ip"),
                jsonInt(body, "driverId") == null ? null
                        : jsonInt(body, "driverId").longValue(),
                jsonField(body, "capability"));
        audit.record(session(req), "device.update", "device", id, null, body,
                req.getRemoteAddr());
        return Jsons.obj("updated", n, "resourceId", id);
    }

    @PatchMapping(value = "/devices/{id:\\d+}", produces = "application/json; charset=utf-8")
    public String patchDevice(@PathVariable long id,
                              @RequestBody(required = false) String body,
                              HttpServletRequest req) throws SQLException {
        return updateDevice(id, body, req);
    }

    @DeleteMapping(value = "/devices/{id:\\d+}", produces = "application/json; charset=utf-8")
    public String deleteDevice(@PathVariable long id,
                               HttpServletRequest req) throws SQLException {
        int n = deviceService.deleteDevice(id);
        audit.record(session(req), "device.delete", "device", id, null, null,
                req.getRemoteAddr());
        return Jsons.obj("deleted", n, "resourceId", id);
    }

    @PostMapping(value = "/devices/{id:\\d+}/clone",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> cloneDevice(
            @PathVariable long id,
            @RequestParam(required = false) String islandId,
            HttpServletRequest req) throws SQLException {
        long toIsland = paramLongValue(islandId);
        if (toIsland <= 0) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err("需要 islandId"));
        }
        long newRid = deviceService.cloneDevice(id, toIsland);
        audit.record(session(req), "device.clone", "device", newRid, "from=" + id,
                Jsons.obj("islandId", toIsland), req.getRemoteAddr());
        return ResponseEntity.ok(Jsons.obj("resourceId", newRid, "from", id));
    }

    @PutMapping(value = "/devices/{id:\\d+}/island", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> moveDevice(
            @PathVariable long id,
            @RequestParam(required = false) String islandId,
            HttpServletRequest req) throws SQLException {
        long isl = paramLongValue(islandId);
        if (isl <= 0) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err("需要 islandId"));
        }
        int n = deviceService.moveDeviceToIsland(id, isl);
        audit.record(session(req), "device.move", "device", id, null,
                Jsons.obj("islandId", isl), req.getRemoteAddr());
        return ResponseEntity.ok(Jsons.obj("moved", n, "resourceId", id, "islandId", isl));
    }

    @PostMapping(value = "/devices/{id:\\d+}/island", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> moveDevicePost(
            @PathVariable long id,
            @RequestParam(required = false) String islandId,
            HttpServletRequest req) throws SQLException {
        return moveDevice(id, islandId, req);
    }

    @PutMapping(value = "/devices/{id:\\d+}/position",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> savePosition(
            @PathVariable long id,
            @RequestBody(required = false) String body) throws SQLException {
        java.util.regex.Matcher mx = java.util.regex.Pattern
                .compile("\"x\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)").matcher(body);
        java.util.regex.Matcher my = java.util.regex.Pattern
                .compile("\"y\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)").matcher(body);
        if (!mx.find() || !my.find()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err("需要 x 与 y"));
        }
        int n = deviceService.saveDevicePosition(id,
                Double.parseDouble(mx.group(1)), Double.parseDouble(my.group(1)));
        return ResponseEntity.ok(Jsons.obj("saved", n, "resourceId", id));
    }

    @PostMapping(value = "/devices/{id:\\d+}/position",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> savePositionPost(
            @PathVariable long id,
            @RequestBody(required = false) String body) throws SQLException {
        return savePosition(id, body);
    }

    // ---------------------------------------------------------------- 设备池 / 方法

    @GetMapping(value = "/devices/pool", produces = "application/json; charset=utf-8")
    public String devicePool(@RequestParam(required = false) String islandId)
            throws SQLException {
        // 可选 ?islandId=：设计器按「这个岛」找设备；不传仍是全量池（岛屿视图 / e2e 回归）
        long isl = paramLongValue(islandId);
        return deviceService.devicePoolJson(isl > 0 ? isl : null);
    }

    /**
     * 设备清单 —— 模拟端 / tcp client 从中控读的**唯一真源**。
     *
     * <p>一台设备 = 设备ID（device_instance.id）+ 设备IP（device_instance.ip）+ 端口。
     * 三端（模拟端 / 中控 / tcp client）拿到的必须是同一组值，
     * 否则 Redis 里的长连接状态会挂到别的设备名下。
     *
     * <p><b>免鉴权</b>（见 {@code ApiServer.isPublic}）：端侧进程（模拟器 / sidecar）
     * 启动时必须能读到清单，而它们没有中控的账号。清单只含设备端点与型号，
     * 不含岛归属、人员、账号等业务数据。
     */
    @GetMapping(value = "/devices/registry", produces = "application/json; charset=utf-8")
    public String deviceRegistry() throws SQLException {
        return deviceService.deviceRegistryJson();
    }

    @GetMapping(value = "/devices/unassigned", produces = "application/json; charset=utf-8")
    public String unassignedDevices() throws SQLException {
        return deviceService.unassignedDevicesJson();
    }

    @GetMapping(value = "/devices/{id:\\d+}/methods",
            produces = "application/json; charset=utf-8")
    public String deviceMethods(@PathVariable long id) throws SQLException {
        return deviceService.deviceMethodsJson(id);
    }

    @GetMapping(value = "/methods/{code}/params", produces = "application/json; charset=utf-8")
    public String methodParams(@PathVariable String code) throws SQLException {
        return deviceService.methodParamsJson(URLDecoder.decode(code, StandardCharsets.UTF_8));
    }

    /**
     * 登记驱动（2026-11-15）。
     *
     * <p><b>为什么</b>：原来全仓没有一条 {@code INSERT INTO driver} 的 Java 路径
     * ⇒ 新设备只能手写 SQL 登记驱动 ⇒ 交付时这一步必卡。
     */
    @PostMapping(value = "/drivers", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> createDriver(@RequestBody(required = false) String body,
                                               HttpServletRequest req) throws SQLException {
        try {
            long id = deviceService.createDriver(
                    jsonField(body, "code"), jsonField(body, "name"),
                    jsonField(body, "protocol"), jsonField(body, "endpoint"),
                    jsonField(body, "vendor"),
                    body != null && body.contains("\"resident\":true"));
            audit.record(session(req), "driver.create", "driver", id,
                    jsonField(body, "code"), body, req.getRemoteAddr());
            return ResponseEntity.ok(Jsons.obj("id", id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err(e.getMessage()));
        }
    }

    /** 改驱动（端点 / 协议 / 名字）。 */
    @PutMapping(value = "/drivers/{id:\\d+}", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> updateDriver(@PathVariable long id,
                                                @RequestBody(required = false) String body,
                                                HttpServletRequest req) throws SQLException {
        try {
            int n = deviceService.updateDriver(id,
                    jsonField(body, "name"), jsonField(body, "protocol"),
                    jsonField(body, "endpoint"), jsonField(body, "vendor"),
                    body != null && body.contains("\"resident\":true") ? Boolean.TRUE : null);
            audit.record(session(req), "driver.update", "driver", id, null, body,
                    req.getRemoteAddr());
            return ResponseEntity.ok(Jsons.obj("updated", n, "id", id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err(e.getMessage()));
        }
    }

    /** 删驱动（仍被设备实例引用时拒绝）。 */
    @DeleteMapping(value = "/drivers/{id:\\d+}", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> deleteDriver(@PathVariable long id,
                                               HttpServletRequest req) throws SQLException {
        try {
            int n = deviceService.deleteDriver(id);
            audit.record(session(req), "driver.delete", "driver", id, null, null,
                    req.getRemoteAddr());
            return ResponseEntity.ok(Jsons.obj("deleted", n, "id", id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err(e.getMessage()));
        }
    }

    /**
     * 批量造网格点位（2026-11-15）。
     *
     * <p>交付一条流程要造几十上百个层位（转盘式提取仪 8 个板位 ×4 台、耗材栈 60 槽），
     * 原来只有 {@code scripts/gen-grid-points.py} 能造（要 DB 口令 + shell）。
     */
    @PostMapping(value = "/devices/{id:\\d+}/points/grid",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> createGridPoints(@PathVariable long id,
                                                    @RequestBody(required = false) String body,
                                                    HttpServletRequest req) throws SQLException {
        try {
            // ⚠ 用 jsonInt：**带不带引号都要认**。
            // jsonField 只匹配引号里的值 ⇒ 裸数字（{"cols":8}）会被读成 null→0 ⇒
            // 直接撞"列数/行数要在 1–200 之间"；而前端表单发的是字符串，又对不上。
            Integer cols = jsonInt(body, "cols");
            Integer rows = jsonInt(body, "rows");
            Integer maxStack = jsonInt(body, "maxStack");
            Integer slotIndexBase = jsonInt(body, "slotIndexBase");
            Double maxHeight = jsonDouble(body, "maxHeightMm");
            int n = deviceService.createGridPoints(id,
                    jsonField(body, "deviceCode"),
                    cols == null ? 0 : cols,
                    rows == null ? 0 : rows,
                    jsonField(body, "pointKind"),
                    jsonField(body, "slotKind"),
                    slotIndexBase == null ? 1 : slotIndexBase,
                    maxStack == null ? 0 : maxStack,
                    maxHeight,
                    jsonField(body, "plateTypes"),
                    !body.contains("\"isEntry\":false"),
                    !body.contains("\"isExit\":false"),
                    !body.contains("\"robotReachable\":false"));
            audit.record(session(req), "device.points_grid", "device", id,
                    "cols=" + cols + ",rows=" + rows, body, req.getRemoteAddr());
            return ResponseEntity.ok(Jsons.obj("created", n, "deviceId", id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err(e.getMessage()));
        }
    }

    /**
     * 按**规格**造点位（不是均匀网格）—— 提取仪的<b>双工位</b>：
     * 外部交换位（deck_out，机械臂可达）+ 内部操作位（deck_in，够不到）。
     *
     * <p>body: {@code {"deviceCode":"ID-EXT1","points":[
     * {"code":"ID-EXT1-EXT2","label":"EXT2","slotKind":"deck_out","slotIndex":1,
     *  "pointKind":"transfer_point"},
     * {"code":"ID-EXT1-INT1","label":"INT1","slotKind":"deck_in",
     *  "pointKind":"transfer_point"}]}}
     */
    @PostMapping(value = "/devices/{id:\\d+}/points/spec",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> createSpecPoints(@PathVariable long id,
                                                    @RequestBody(required = false) String body,
                                                    HttpServletRequest req) throws SQLException {
        try {
            List<java.util.Map<String, Object>> points = new ArrayList<>();
            String raw = rawJsonField(body, "points");
            if (raw != null && raw.startsWith("[")) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("\\{([^{}]*)\\}").matcher(raw);
                while (m.find()) {
                    java.util.Map<String, Object> one = new java.util.LinkedHashMap<>();
                    java.util.regex.Matcher kv = java.util.regex.Pattern
                            .compile("\\"([A-Za-z_][A-Za-z0-9_]*)\"\\s*:\\s*"
                                    + "(?:\"([^\"]*)\"|(-?\\d+(?:\\.\\d+)?)|(true|false))")
                            .matcher(m.group(1));
                    while (kv.find()) {
                        String k = kv.group(1);
                        if (kv.group(2) != null) {
                            one.put(k, kv.group(2));
                        } else if (kv.group(3) != null) {
                            one.put(k, Double.valueOf(kv.group(3)));
                        } else if (kv.group(4) != null) {
                            one.put(k, Boolean.valueOf(kv.group(4)));
                        }
                    }
                    points.add(one);
                }
            }
            int n = deviceService.createSpecPoints(id, jsonField(body, "deviceCode"), points);
            audit.record(session(req), "device.points_spec", "device", id,
                    "n=" + n, body, req.getRemoteAddr());
            return ResponseEntity.ok(Jsons.obj("created", n, "deviceId", id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err(e.getMessage()));
        }
    }

    /**
     * 取一个数字字段（带不带引号都认）。
     *
     * <p>为什么不能只用 {@code jsonField}：它只匹配引号里的值 ⇒
     * 裸数字（{@code {"maxHeightMm":45}}）会被读成 null。
     */
    private static Double jsonDouble(String body, String key) {
        if (body == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*\"?(-?\\d+(?:\\.\\d+)?)\"?")
                .matcher(body);
        return m.find() ? Double.valueOf(m.group(1)) : null;
    }

    /** 取一段原始 JSON（对象/数组），用于 body 里的嵌套数组。 */
    private static String rawJsonField(String body, String key) {
        return com.labos.boot.web.controller.DeviceEventController.rawJsonField(body, key);
    }

    @GetMapping(value = "/drivers", produces = "application/json; charset=utf-8")
    public String drivers() throws SQLException {
        return deviceService.driversJson();
    }

    @GetMapping(value = "/topology", produces = "application/json; charset=utf-8")
    public String topology() throws SQLException {
        return deviceService.topologyJson();
    }

    @PutMapping(value = "/devices/{id:\\d+}/methods/{method}/interruptible",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> setInterruptible(
            @PathVariable long id,
            @PathVariable String method,
            @RequestBody(required = false) String body,
            HttpServletRequest req) throws SQLException {
        String dMethod = URLDecoder.decode(method, StandardCharsets.UTF_8);
        String raw = null;
        java.util.regex.Matcher bm = java.util.regex.Pattern
                .compile("\"interruptible\"\\s*:\\s*(true|false)").matcher(body);
        if (bm.find()) {
            raw = bm.group(1);
        }
        if (raw == null) {
            raw = paramStr(req.getQueryString(), "interruptible");
        }
        if (!"true".equalsIgnoreCase(raw) && !"false".equalsIgnoreCase(raw)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(err("需要 interruptible=true/false"));
        }
        boolean value = Boolean.parseBoolean(raw);
        int n = deviceService.setDeviceMethodInterruptible(id, dMethod, value);
        if (n == 0) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(err("设备方法不存在"));
        }
        com.labos.kernel.driver.DriverStepExecutor.invalidateInterruptible(id, dMethod);
        audit.record(session(req), "device.method.interruptible", "device", id,
                dMethod + "=" + value, null, req.getRemoteAddr());
        return ResponseEntity.ok(Jsons.obj("deviceId", id, "method", dMethod,
                "interruptible", value));
    }

    // ---------------------------------------------------------------- 岛

    @PostMapping(value = "/islands", produces = "application/json; charset=utf-8")
    public String createIsland(@RequestBody(required = false) String body,
                               HttpServletRequest req) throws SQLException {
        long id = deviceService.createIsland(jsonField(body, "code"), jsonField(body, "name"));
        audit.record(session(req), "island.create", "island", id,
                jsonField(body, "code"), body, req.getRemoteAddr());
        return Jsons.obj("id", id);
    }

    /** 改岛（名字 / 物理坐标 / 容量）——2026-11-15：原来岛建完就只能删了重建。 */
    @PutMapping(value = "/islands/{id:\\d+}", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> updateIsland(@PathVariable long id,
                                                @RequestBody(required = false) String body,
                                                HttpServletRequest req) throws SQLException {
        int n = deviceService.updateIsland(id,
                jsonField(body, "name"),
                jsonField(body, "location"),
                (int) paramLongValue(jsonField(body, "capacity")));
        audit.record(session(req), "island.update", "island", id, null, body,
                req.getRemoteAddr());
        return ResponseEntity.ok(Jsons.obj("updated", n, "id", id));
    }

    @DeleteMapping(value = "/islands/{id:\\d+}", produces = "application/json; charset=utf-8")
    public String deleteIsland(@PathVariable long id,
                               HttpServletRequest req) throws SQLException {
        int n = deviceService.deleteIsland(id);
        audit.record(session(req), "island.delete", "island", id, null, null,
                req.getRemoteAddr());
        return Jsons.obj("deleted", n, "id", id);
    }

    // ---------------------------------------------------------------- 机器人点位

    @GetMapping(value = "/islands/{id:\\d+}/robot-points",
            produces = "application/json; charset=utf-8")
    public String robotPoints(@PathVariable long id) throws SQLException {
        return deviceService.robotPointsJson(id);
    }

    @GetMapping(value = "/islands/{id:\\d+}/robot-points.csv")
    public ResponseEntity<String> robotPointsCsv(@PathVariable long id) throws SQLException {
        String csv = deviceService.robotPointsCsv(id);
        return ResponseEntity.ok()
                .header("Content-Type", "text/csv; charset=utf-8")
                .header("Content-Disposition",
                        "attachment; filename=\"robot-points-" + id + ".csv\"")
                .body(csv);
    }

    @PostMapping(value = "/islands/{id:\\d+}/robot-points.csv",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> importRobotPoints(
            @PathVariable long id,
            @RequestParam(required = false) String robotResourceId,
            @RequestBody(required = false) String body,
            HttpServletRequest req) throws SQLException {
        long robot = paramLongValue(robotResourceId);
        if (robot <= 0) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(err("需要 robotResourceId"));
        }
        int n = deviceService.importRobotPointsCsv(id, robot, body);
        audit.record(session(req), "robot.points.import", "island", id, null,
                Jsons.obj("rows", n), req.getRemoteAddr());
        return ResponseEntity.ok(Jsons.obj("imported", n, "islandId", id));
    }

    @PostMapping(value = "/islands/{id:\\d+}/robot-points/sync",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> syncRobotPoints(
            @PathVariable long id,
            @RequestParam(required = false) String robotResourceId,
            HttpServletRequest req) throws SQLException {
        long robotId = paramLongValue(robotResourceId);
        if (robotId <= 0) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(err("需要 robotResourceId"));
        }
        int n = deviceService.syncRobotPoints(id, robotId);
        audit.record(session(req), "robot.points.sync", "island", id, null,
                Jsons.obj("robot", robotId, "points", n), req.getRemoteAddr());
        return ResponseEntity.ok(Jsons.obj("synced", n, "islandId", id, "robot", robotId));
    }

    @PutMapping(value = "/islands/{id:\\d+}/robot-points/{code}",
            produces = "application/json; charset=utf-8")
    public String updateRobotPoint(
            @PathVariable long id,
            @PathVariable String code,
            @RequestBody(required = false) String body,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String deviceResourceId,
            HttpServletRequest req) throws SQLException {
        String pcode = URLDecoder.decode(code, StandardCharsets.UTF_8);
        java.util.regex.Matcher mx = java.util.regex.Pattern
                .compile("\"x\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)").matcher(body);
        int n = deviceService.updateRobotPoint(id, pcode,
                mx.find() ? Double.parseDouble(mx.group(1)) : null,
                null, null,
                paramLongValue(deviceResourceId) > 0
                        ? paramLongValue(deviceResourceId) : null,
                action);
        audit.record(session(req), "robot.point.update", "island", id, pcode, null,
                req.getRemoteAddr());
        return Jsons.obj("updated", n, "code", pcode);
    }

    @PostMapping(value = "/islands/{id:\\d+}/robot-points/{code}/capture",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> captureRobotPoint(
            @PathVariable long id,
            @PathVariable String code,
            @RequestParam(required = false) String robotResourceId,
            @RequestParam(required = false) String method,
            HttpServletRequest req) throws SQLException {
        var denied = debugDenied(req, ApiServer.DEVICE_PROBE_ENABLED, "robot-points capture");
        if (denied != null) {
            return denied;
        }
        String ccode = URLDecoder.decode(code, StandardCharsets.UTF_8);
        long robot = paramLongValue(robotResourceId);
        String readMethod = paramStr(req.getQueryString(), "method");
        if (robot <= 0 || readMethod == null || readMethod.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(err("需要 robotResourceId 和 method"));
        }
        // ShadowProbe 要 device_instance.id，不是 resource_node.id（这个坑第五次了）
        Long robotInst = deviceService.instanceIdOf(robot);
        if (robotInst == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(err("机械臂实例不存在: " + robot));
        }
        ShadowProbe probe = new ShadowProbe(workflows.buildDriverRegistry());
        ShadowProbe.Probe pr = probe.probe(robotInst, readMethod, Map.of());
        if (!pr.ok()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Jsons.obj("error", "读取失败：" + pr.errorCode() + " " + pr.errorText(),
                            "captured", 0));
        }
        Double[] xyz = extractXYZ(pr.rawResponse());
        if (xyz == null) {
            // 驱动不返回坐标：把原文给前端看，现场好判断该配什么字段
            return ResponseEntity.ok(Jsons.obj("captured", 0,
                    "reason", "驱动返回里没有 x/y/z 字段",
                    "raw", pr.rawResponse()));
        }
        int n = deviceService.updateRobotPoint(id, ccode, xyz[0], xyz[1], xyz[2], null, null);
        audit.record(session(req), "robot.point.capture", "island", id, ccode,
                Jsons.obj("method", readMethod), req.getRemoteAddr());
        return ResponseEntity.ok(Jsons.obj("captured", n, "code", ccode,
                "x", xyz[0], "y", xyz[1], "z", xyz[2]));
    }

    // ---------------------------------------------------------------- 岛内流图

    @GetMapping(value = "/islands/{id:\\d+}/flow", produces = "application/json; charset=utf-8")
    public String islandFlow(@PathVariable long id) throws SQLException {
        return deviceService.islandFlowJson(id);
    }

    @PutMapping(value = "/islands/{id:\\d+}/flow", produces = "application/json; charset=utf-8")
    public String saveIslandFlow(@PathVariable long id,
                                 @RequestBody(required = false) String body,
                                 HttpServletRequest req) throws SQLException {
        int n = deviceService.saveIslandFlow(id, body);
        audit.record(session(req), "island.flow", "island", id, null,
                Jsons.obj("bytes", body == null ? 0 : body.length()), req.getRemoteAddr());
        return Jsons.obj("saved", n, "bytes", body == null ? 0 : body.length());
    }

    @PostMapping(value = "/islands/{id:\\d+}/flow", produces = "application/json; charset=utf-8")
    public String saveIslandFlowPost(@PathVariable long id,
                                     @RequestBody(required = false) String body,
                                     HttpServletRequest req) throws SQLException {
        return saveIslandFlow(id, body, req);
    }

    // ---------------------------------------------------------------- 内部

    /** @RequestParam 拿到的是裸值；paramLong(query,key) 是给完整 query string 用的，这里不能混用。 */
    private static long paramLongValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** S0-safety：调试通路默认拒绝。开关关闭或非 admin 时返回 403；放行返回 null。 */
    private static ResponseEntity<String> debugDenied(HttpServletRequest req, boolean enabled, String what) {
        if (!enabled) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(err("调试通路已关闭（S0-safety）：" + what));
        }
        AuthService.Session s0 = session(req);
        if (s0 == null || !s0.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(err("调试通路仅管理员可用（S0-safety）"));
        }
        return null;
    }

    private static AuthService.Session session(HttpServletRequest req) {
        Object v = req.getAttribute(ApiAuthInterceptor.SESSION_ATTR);
        return v instanceof AuthService.Session s0 ? s0 : null;
    }
}
