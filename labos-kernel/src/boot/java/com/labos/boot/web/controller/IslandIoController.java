package com.labos.boot.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labos.boot.web.AuditRecorder;
import com.labos.boot.web.service.DeviceService;
import com.labos.kernel.persistence.ManifestMapper;
import com.labos.kernel.persistence.Mappers;
import com.labos.kernel.persistence.TopologyMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.labos.kernel.api.ApiServer.err;

/**
 * 整岛导出 / 导入：**岛 + 设备 + 点位 + 点位方法 + 示教**一次搬完。
 *
 * <p>为什么需要：管理员那 7 步配置（设备上线、造 82 个点位、逐点授权方法、抓 78 个示教点）
 * 在演示机 / 交付机 / 测试库之间要反复重建。模板导出只搬"工艺"，这一层搬"现场"。
 *
 * <p>包里**只有业务码**：islandCode / deviceCode / pointCode / methodName / driverCode。
 * 导入按码解析，已存在的跳过（幂等），解析不到的进 warnings（不阻塞），
 * 落库异常进 problems（整单 409 并带出已完成的对象清单）。
 */
@RestController
@RequestMapping("/api")
public class IslandIoController {

    private static final long TENANT = 1L;
    private static final String KIND = "labos.island";
    private static final int VERSION = 1;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DeviceService devices;
    private final AuditRecorder audit;

    public IslandIoController(DeviceService devices, AuditRecorder audit) {
        this.devices = devices;
        this.audit = audit;
    }

    private TopologyMapper topo() {
        return Mappers.get(TopologyMapper.class);
    }

    private ManifestMapper manifest() {
        return Mappers.get(ManifestMapper.class);
    }

    // ---------------------------------------------------------------- 导出

    @GetMapping(value = "/islands/{id:\\d+}/export",
            produces = "application/json; charset=utf-8")
    public ResponseEntity<String> exportIsland(@PathVariable long id) {
        Map<String, Object> island = topo().selectIslandRow(TENANT, id);
        if (island == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(err("岛不存在: " + id));
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("kind", KIND);
        root.put("version", VERSION);
        root.put("exportedAt", java.time.OffsetDateTime.now().toString());
        root.put("island", island);
        root.put("devices", topo().selectIslandDevices(TENANT, id));
        root.put("points", topo().selectIslandPoints(TENANT, id));
        root.put("pointMethods", topo().selectIslandPointMethods(TENANT, id));
        root.put("robotPoints", topo().selectIslandRobotPoints(TENANT, id));
        try {
            return ResponseEntity.ok(JSON.writeValueAsString(root));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(err("导出序列化失败：" + e.getMessage()));
        }
    }

    // ---------------------------------------------------------------- 导入

    /**
     * @param dryRun 1 = 只解析与校验，不写库（先看"会建什么、会缺什么"）
     */
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    @PostMapping(value = "/islands/import", produces = "application/json; charset=utf-8")
    public ResponseEntity<String> importIsland(@RequestBody(required = false) String body,
                                               @RequestParam(required = false) String dryRun,
                                               HttpServletRequest req) {
        JsonNode n = parse(body);
        if (!KIND.equals(text(n, "kind"))) {
            return ResponseEntity.badRequest().body(err("不是整岛导出文件（kind != " + KIND + "）"));
        }
        boolean dry = "1".equals(dryRun) || "true".equalsIgnoreCase(dryRun);
        JsonNode islandNode = n.path("island");
        String islandCode = text(islandNode, "code");
        if (islandCode == null || islandCode.isBlank()) {
            return ResponseEntity.badRequest().body(err("缺少 island.code"));
        }
        List<String> warnings = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        // ---- ① 岛：有就复用，没有就建 ----
        Long islandId = manifest().selectIslandIdByCode(TENANT, islandCode);
        if (islandId == null) {
            Long other = topo().selectResourceIdByCode(TENANT, islandCode);
            if (other != null) {
                return ResponseEntity.badRequest().body(err(
                        "编码「" + islandCode + "」在目标环境已存在，但不是岛（kind≠island）"));
            }
            if (!dry) {
                try {
                    islandId = devices.createIsland(islandCode,
                            defaultIfBlank(text(islandNode, "name"), islandCode));
                } catch (Exception e) {
                    return ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(err("建岛失败：" + e.getMessage()));
                }
            }
            warnings.add("岛「" + islandCode + "」在目标环境不存在"
                    + (dry ? "（dryRun：将新建）" : "，已新建"));
        }
        if (islandId == null && !dry) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(err("建岛失败"));
        }

        int devCreated = 0;
        int devSkipped = 0;
        int pointUpsert = 0;
        int methodCreated = 0;
        int robotUpsert = 0;

        // ---- ② 设备：按 code 幂等 ----
        for (JsonNode d : array(n, "devices")) {
            String code = text(d, "code");
            if (code == null || code.isBlank()) {
                problems.add("有设备缺少 code，已跳过");
                continue;
            }
            if (topo().selectResourceIdByCode(TENANT, code) != null) {
                devSkipped++;
                continue;
            }
            String driverCode = text(d, "driverCode");
            Long driverId = driverCode == null || driverCode.isBlank()
                    ? null : topo().selectDriverIdByCode(TENANT, driverCode);
            if (driverCode != null && !driverCode.isBlank() && driverId == null) {
                warnings.add("设备「" + code + "」的驱动「" + driverCode
                        + "」在目标环境未登记，导入后需手工挂驱动");
            }
            if (dry) {
                devCreated++;
                continue;
            }
            try {
                devices.createDevice(islandId, code,
                        defaultIfBlank(text(d, "name"), code),
                        text(d, "deviceType"), text(d, "capability"),
                        text(d, "ip"), driverId);
                devCreated++;
            } catch (Exception e) {
                problems.add("建设备「" + code + "」失败：" + e.getMessage());
            }
        }

        // ---- ③ 点位 ----
        for (JsonNode p : array(n, "points")) {
            String deviceCode = text(p, "deviceCode");
            String pointCode = text(p, "pointCode");
            if (deviceCode == null || pointCode == null) {
                problems.add("有点位缺少 deviceCode/pointCode，已跳过");
                continue;
            }
            Long devRes = topo().selectResourceIdByCode(TENANT, deviceCode);
            if (devRes == null) {
                problems.add("点位「" + pointCode + "」所属设备「" + deviceCode
                        + "」在目标环境不存在");
                continue;
            }
            if (dry) {
                pointUpsert++;
                continue;
            }
            try {
                // ck_device_point_flags：point_role≠internal 时 **必须** is_entry 或 is_exit 为真。
                // 导出包里一般都有，但手写/老包常常只有 pointRole ⇒ 这里补一个默认，
                // 否则整岛导入会在第一个点位上撞约束（实测）。
                String role = defaultIfBlank(text(p, "pointRole"), "both");
                boolean entry = Boolean.TRUE.equals(boolOrNull(p, "isEntry"));
                boolean exit = Boolean.TRUE.equals(boolOrNull(p, "isExit"));
                if ("internal".equals(role)) {
                    entry = false;
                    exit = false;
                } else if (!entry && !exit) {
                    entry = true;
                }
                topo().insertPointForImport(TENANT, devRes, pointCode,
                        // ⚠ 两个 kind 不是一回事：
                        //   resourceKind = 资源树节点类型（storage/incubator/transfer_point）
                        //   pointKind    = device_point 的工位类型（nest/deck_in/deck_out/waste）
                        // 混用会撞 ck_device_point_kind（实测）
                        defaultIfBlank(text(p, "nodeKind"), "storage"),
                        defaultIfBlank(text(p, "pointKind"), "nest"),
                        role,
                        !Boolean.FALSE.equals(boolOrNull(p, "robotReachable")),
                        text(p, "positionLabel"),
                        intOrNull(p, "slotIndex"), intOrNull(p, "gridRow"),
                        intOrNull(p, "gridCol"), intOrNull(p, "maxStack"),
                        doubleOrNull(p, "maxHeightMm"), text(p, "plateTypes"),
                        entry,
                        exit,
                        Boolean.TRUE.equals(boolOrNull(p, "isStandalone")),
                        Boolean.TRUE.equals(boolOrNull(p, "isPrimary")),
                        intOrNull(p, "orderInDevice"), deviceCode);
                pointUpsert++;
            } catch (Exception e) {
                problems.add("建点位「" + pointCode + "」失败：" + e.getMessage());
            }
        }

        // ---- ④ 点位 × 方法 ----
        for (JsonNode m : array(n, "pointMethods")) {
            String deviceCode = text(m, "deviceCode");
            String pointCode = text(m, "pointCode");
            String methodName = text(m, "methodName");
            if (deviceCode == null || pointCode == null || methodName == null) {
                continue;
            }
            Long positionId = manifest().selectPointIdByCode(TENANT, pointCode, deviceCode);
            if (positionId == null) {
                problems.add("点位「" + pointCode + "」不存在，方法「" + methodName + "」未导入");
                continue;
            }
            Long methodId = manifest().selectMethodIdByDeviceAndName(
                    TENANT, deviceCode, methodName);
            if (methodId == null) {
                warnings.add("设备「" + deviceCode + "」没有方法「" + methodName
                        + "」，该授权未导入");
                continue;
            }
            if (dry) {
                methodCreated++;
                continue;
            }
            try {
                topo().insertPointMethodIfAbsent(TENANT, positionId, methodId,
                        defaultIfBlank(text(m, "scopeKind"), "global"),
                        text(m, "purpose"), defaultIfBlank(text(m, "allocation"), "explicit"),
                        intOrNull(m, "priority") == null ? 100 : intOrNull(m, "priority"));
                methodCreated++;
            } catch (Exception e) {
                problems.add("授权「" + deviceCode + "/" + pointCode + "/"
                        + methodName + "」失败：" + e.getMessage());
            }
        }

        // ---- ⑤ 示教点 ----
        for (JsonNode r : array(n, "robotPoints")) {
            String robotCode = text(r, "robotCode");
            String code = text(r, "code");
            if (robotCode == null || code == null) {
                continue;
            }
            Long robotRes = topo().selectResourceIdByCode(TENANT, robotCode);
            if (robotRes == null) {
                warnings.add("示教点「" + code + "」的机械臂「" + robotCode
                        + "」不存在，已跳过");
                continue;
            }
            if (dry) {
                robotUpsert++;
                continue;
            }
            Long devRes = null;
            String devCode = text(r, "deviceCode");
            if (devCode != null && !devCode.isBlank()) {
                devRes = topo().selectResourceIdByCode(TENANT, devCode);
            }
            try {
                if (islandId == null) {
                    warnings.add("dryRun 下岛未建，示教点「" + code + "」未统计");
                    continue;
                }
                topo().upsertRobotPointForImport(TENANT, islandId, robotRes, code,
                        defaultIfBlank(text(r, "name"), code),
                        doubleOrNull(r, "x"), doubleOrNull(r, "y"), doubleOrNull(r, "z"),
                        defaultIfBlank(text(r, "pointType"), "load"),
                        text(r, "fillStatus"), devRes);
                robotUpsert++;
            } catch (Exception e) {
                problems.add("写示教点「" + code + "」失败：" + e.getMessage());
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", problems.isEmpty());
        out.put("dryRun", dry);
        out.put("islandCode", islandCode);
        out.put("islandId", islandId);
        out.put("devicesCreated", devCreated);
        out.put("devicesSkipped", devSkipped);
        out.put("pointsUpserted", pointUpsert);
        out.put("pointMethodsCreated", methodCreated);
        out.put("robotPointsUpserted", robotUpsert);
        out.put("warnings", warnings);
        out.put("problems", problems);
        if (!problems.isEmpty()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(toJson(out));
        }
        if (!dry) {
            audit.record(session(req), "island.import", "resource_node", islandId,
                    islandCode, toJson(Map.of("devices", devCreated,
                            "points", pointUpsert, "methods", methodCreated)),
                    req.getRemoteAddr());
        }
        return ResponseEntity.ok(toJson(out));
    }

    // ---------------------------------------------------------------- 小工具

    private static JsonNode parse(String body) {
        try {
            return body == null ? JSON.createObjectNode() : JSON.readTree(body);
        } catch (Exception e) {
            return JSON.createObjectNode();
        }
    }

    private static List<JsonNode> array(JsonNode n, String key) {
        List<JsonNode> out = new ArrayList<>();
        JsonNode arr = n.path(key);
        if (arr.isArray()) {
            arr.forEach(out::add);
        }
        return out;
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.path(key);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static Boolean boolOrNull(JsonNode n, String key) {
        JsonNode v = n.path(key);
        return v.isMissingNode() || v.isNull() ? null : v.asBoolean();
    }

    private static Integer intOrNull(JsonNode n, String key) {
        JsonNode v = n.path(key);
        return v.isMissingNode() || v.isNull() || !v.isNumber() ? null : v.asInt();
    }

    private static Double doubleOrNull(JsonNode n, String key) {
        JsonNode v = n.path(key);
        return v.isMissingNode() || v.isNull() || !v.isNumber() ? null : v.asDouble();
    }

    private static String defaultIfBlank(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }

    private static String toJson(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{\"error\":\"序列化失败\"}";
        }
    }

    /** 审计/会话：与其它控制器一致的口径（拿不到会话就不记操作人）。 */
    private static com.labos.kernel.api.AuthService.Session session(HttpServletRequest req) {
        Object v = req.getAttribute(com.labos.boot.web.ApiAuthInterceptor.SESSION_ATTR);
        return v instanceof com.labos.kernel.api.AuthService.Session s ? s : null;
    }
}
