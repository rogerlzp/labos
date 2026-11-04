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
 * DeviceApi：从 {@link ApiServer#route} 拆出的 Device 资源域路由（38 个路由块）。
 *
 * <p>路径：{@code /api/device-models}、{@code /api/devices}、{@code /api/devices/pool}、{@code /api/devices/unassigned}、{@code /api/drivers}、{@code /api/heartbeat}、{@code /api/islands}、{@code /api/positions}、{@code /api/positions/stats}、{@code /api/topology}
 * <p>正则路由：{@code DEVICE_CLONE}、{@code DEVICE_ISLAND}、{@code DEVICE_METHODS}、{@code DEVICE_ONE}、{@code DEVICE_POS}、{@code ISLAND_FLOW}、{@code ISLAND_ONE}、{@code METHOD_PARAMS}、{@code MODEL_DEPLOY}、{@code ROBOT_CAPTURE}、{@code ROBOT_CSV}、{@code ROBOT_POINTS}、{@code ROBOT_POINT_ONE}、{@code ROBOT_SYNC}
 *
 * <p><b>拆分约定</b>：路由顺序按首次出现顺序分发（跨 controller 稳定）；命中即 return true。
 * 共享 helper 仍由 ApiServer 提供（static import + {@code s.xxx}），不重复实现。
 */
final class DeviceApi {

    /** 宿主：提供库连接/服务与共享 helper（与 ApiServer 内部调用等价）。 */
    private final ApiServer s;

    DeviceApi(ApiServer s) {
        this.s = s;
    }

    /**
     * 处理本资源域请求。
     *
     * @return true = 已处理（调用方直接 return）；false = 不归本域，交给下一个 controller
     */
    boolean handle(HttpExchange ex, String path, String method) throws Exception {
        Matcher modelDeploy = MODEL_DEPLOY.matcher(path);
        if (modelDeploy.matches()) {
            if (!"POST".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            long mid = Long.parseLong(modelDeploy.group(1));
            long toIsl = paramLong(ex.getRequestURI().getQuery(), "islandId");
            if (toIsl <= 0) {
                s.send(ex, 400, err("需要 islandId"));
                return true;
            }
            long newRid = s.query.deployModel(mid, toIsl);
            s.audited("device.deploy", "device", newRid, "model=" + mid,
                    Jsons.obj("islandId", toIsl), ex);
            s.send(ex, 200, Jsons.obj("resourceId", newRid, "modelId", mid));
            return true;
        }
        if ("/api/positions".equals(path)) {
            s.requireGet(method, ex);
            long isl = paramLong(ex.getRequestURI().getQuery(), "islandId");
            if (isl <= 0) {
                s.send(ex, 400, err("需要 islandId"));
                return true;
            }
            PositionLock lock = new PositionLock(s.db, TENANT);
            List<String> items = new ArrayList<>();
            for (PositionLock.PositionState st : lock.byIsland(isl)) {
                items.add(Jsons.obj(
                        "resourceId", st.resourceId(),
                        "code", st.code(),
                        "name", st.name(),
                        "kind", st.kind(),
                        "plateId", st.plateId(),
                        "reserved", st.reserved(),
                        "reservedByRun", st.reservedByRun(),
                        "occupiedSince", st.occupiedSince(),
                        "available", st.available(),
                        "parentDeviceId", st.parentDeviceId(),
                        "parentDeviceCode", st.parentDeviceCode()));
            }
            s.send(ex, 200, Jsons.obj("islandId", isl,
                    "items", new Jsons.Raw(Jsons.arr(items))));
            return true;
        }
        if ("/api/heartbeat".equals(path)) {
            s.requireGet(method, ex);
            Map<String, Boolean> r = s.heartbeat.lastResults();
            List<String> items = new ArrayList<>();
            r.forEach((k, v) -> items.add(Jsons.obj("driver", k, "online", v)));
            s.send(ex, 200, Jsons.obj(
                    "lastRunAt", s.heartbeat.lastRunAt() == 0 ? null
                            : new java.sql.Timestamp(s.heartbeat.lastRunAt()).toInstant().toString(),
                    "drivers", new Jsons.Raw(Jsons.arr(items))));
            return true;
        }
        if ("/api/positions/stats".equals(path)) {
            s.requireGet(method, ex);
            PositionLock lock = new PositionLock(s.db, TENANT);
            Map<String, Object> st = lock.stats();
            s.send(ex, 200, Jsons.obj("withPlate", st.get("withPlate"),
                    "reserved", st.get("reserved"),
                    "tracked", st.get("tracked")));
            return true;
        }
        if ("/api/device-models".equals(path)) {
            s.requireGet(method, ex);
            s.send(ex, 200, s.query.deviceModelsJson());
            return true;
        }
        Matcher devClone = DEVICE_CLONE.matcher(path);
        if (devClone.matches()) {
            if (!"POST".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            long src = Long.parseLong(devClone.group(1));
            String q3 = ex.getRequestURI().getQuery();
            long toIsland = paramLong(q3, "islandId");
            if (toIsland <= 0) {
                s.send(ex, 400, err("需要 islandId"));
                return true;
            }
            long newRid = s.query.cloneDevice(src, toIsland);
            s.audited("device.clone", "device", newRid, "from=" + src,
                    Jsons.obj("islandId", toIsland), ex);
            s.send(ex, 200, Jsons.obj("resourceId", newRid, "from", src));
            return true;
        }
        Matcher devPos = DEVICE_POS.matcher(path);
        if (devPos.matches()) {
            if (!"PUT".equals(method) && !"POST".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            long rid3 = Long.parseLong(devPos.group(1));
            String body = new String(s.readBody(ex), StandardCharsets.UTF_8);
            java.util.regex.Matcher mx = java.util.regex.Pattern
                    .compile("\"x\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)").matcher(body);
            java.util.regex.Matcher my = java.util.regex.Pattern
                    .compile("\"y\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)").matcher(body);
            if (!mx.find() || !my.find()) {
                s.send(ex, 400, err("需要 x 与 y"));
                return true;
            }
            int n4 = s.query.saveDevicePosition(rid3,
                    Double.parseDouble(mx.group(1)), Double.parseDouble(my.group(1)));
            s.send(ex, 200, Jsons.obj("saved", n4, "resourceId", rid3));
            return true;
        }
        Matcher devIsland = DEVICE_ISLAND.matcher(path);
        if (devIsland.matches()) {
            if (!"PUT".equals(method) && !"POST".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            long rid2 = Long.parseLong(devIsland.group(1));
            String q2 = ex.getRequestURI().getQuery();
            long isl = paramLong(q2, "islandId");
            if (isl <= 0) {
                s.send(ex, 400, err("需要 islandId"));
                return true;
            }
            int n2 = s.query.moveDeviceToIsland(rid2, isl);
            s.audited("device.move", "device", rid2, null,
                    Jsons.obj("islandId", isl), ex);
            s.send(ex, 200, Jsons.obj("moved", n2, "resourceId", rid2, "islandId", isl));
            return true;
        }
        if ("/api/devices/pool".equals(path)) {
            s.requireGet(method, ex);
            s.send(ex, 200, s.query.devicePoolJson());
            return true;
        }
        if ("/api/devices/unassigned".equals(path)) {
            s.requireGet(method, ex);
            s.send(ex, 200, s.query.unassignedDevicesJson());
            return true;
        }
        Matcher deviceOne = DEVICE_ONE.matcher(path);
        if (deviceOne.matches()) {
            long rid = Long.parseLong(deviceOne.group(1));
            if ("PUT".equals(method) || "PATCH".equals(method)) {
                String body = new String(s.readBody(ex), StandardCharsets.UTF_8);
                int n = s.query.updateDevice(rid,
                        jsonField(body, "name"),
                        jsonField(body, "ip"),
                        jsonInt(body, "driverId") == null ? null
                                : jsonInt(body, "driverId").longValue(),
                        jsonField(body, "capability"));
                s.audited("device.update", "device", rid, null, body, ex);
                s.send(ex, 200, Jsons.obj("updated", n, "resourceId", rid));
                return true;
            }
            if ("DELETE".equals(method)) {
                int n = s.query.deleteDevice(rid);
                s.audited("device.delete", "device", rid, null, null, ex);
                s.send(ex, 200, Jsons.obj("deleted", n, "resourceId", rid));
                return true;
            }
            throw new IllegalStateException("method not allowed: " + method);
        }
        Matcher pts = ROBOT_POINTS.matcher(path);
        if (pts.matches()) {
            s.requireGet(method, ex);
            s.send(ex, 200, s.query.robotPointsJson(Long.parseLong(pts.group(1))));
            return true;
        }
        Matcher ptCsv = ROBOT_CSV.matcher(path);
        if (ptCsv.matches()) {
            long iidv = Long.parseLong(ptCsv.group(1));
            if ("GET".equals(method)) {
                String csv2 = s.query.robotPointsCsv(iidv);
                ex.getResponseHeaders().set("Content-Type",
                        "text/csv; charset=utf-8");
                ex.getResponseHeaders().set("Content-Disposition",
                        "attachment; filename=\"robot-points-" + iidv + ".csv\"");
                s.send(ex, 200, csv2);
                return true;
            }
            if ("POST".equals(method)) {
                long robot2 = paramLong(ex.getRequestURI().getQuery(), "robotResourceId");
                if (robot2 <= 0) {
                    s.send(ex, 400, err("需要 robotResourceId"));
                    return true;
                }
                String body = new String(s.readBody(ex), StandardCharsets.UTF_8);
                int n = s.query.importRobotPointsCsv(iidv, robot2, body);
                s.audited("robot.points.import", "island", iidv, null,
                        Jsons.obj("rows", n), ex);
                s.send(ex, 200, Jsons.obj("imported", n, "islandId", iidv));
                return true;
            }
            throw new IllegalStateException("method not allowed: " + method);
        }
        Matcher ptCap = ROBOT_CAPTURE.matcher(path);
        if (ptCap.matches()) {
            if (!"POST".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            long iidc = Long.parseLong(ptCap.group(1));
            String ccode = java.net.URLDecoder.decode(ptCap.group(2), StandardCharsets.UTF_8);
            String q = ex.getRequestURI().getQuery();
            long robot = paramLong(q, "robotResourceId");
            String readMethod = paramStr(q, "method");
            if (robot <= 0 || readMethod == null || readMethod.isBlank()) {
                s.send(ex, 400, err("需要 robotResourceId 和 method"));
                return true;
            }
            // ShadowProbe 要 device_instance.id，不是 resource_node.id（这个坑第五次了）
            Long robotInst = s.query.instanceIdOf(robot);
            if (robotInst == null) {
                s.send(ex, 400, err("机械臂实例不存在: " + robot));
                return true;
            }
            com.labos.kernel.driver.ShadowProbe probe2 =
                    new com.labos.kernel.driver.ShadowProbe(s.workflows.buildDriverRegistry());
            com.labos.kernel.driver.ShadowProbe.Probe pr =
                    probe2.probe(robotInst, readMethod, Map.of());
            if (!pr.ok()) {
                s.send(ex, 400, Jsons.obj("error",
                        "读取失败：" + pr.errorCode() + " " + pr.errorText(),
                        "captured", 0));
                return true;
            }
            Double[] xyz = QueryService.extractXYZ(pr.rawResponse());
            if (xyz == null) {
                // 驱动不返回坐标：把原文给前端看，现场好判断该配什么字段
                s.send(ex, 200, Jsons.obj("captured", 0,
                        "reason", "驱动返回里没有 x/y/z 字段",
                        "raw", pr.rawResponse()));
                return true;
            }
            int n = s.query.updateRobotPoint(iidc, ccode, xyz[0], xyz[1], xyz[2], null, null);
            s.audited("robot.point.capture", "island", iidc, ccode,
                    Jsons.obj("method", readMethod), ex);
            s.send(ex, 200, Jsons.obj("captured", n, "code", ccode,
                    "x", xyz[0], "y", xyz[1], "z", xyz[2]));
            return true;
        }
        Matcher ptOne = ROBOT_POINT_ONE.matcher(path);
        if (ptOne.matches()) {
            if (!"PUT".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            long iidp2 = Long.parseLong(ptOne.group(1));
            String pcode = java.net.URLDecoder.decode(ptOne.group(2), StandardCharsets.UTF_8);
            String body = new String(s.readBody(ex), StandardCharsets.UTF_8);
            java.util.regex.Matcher mx = java.util.regex.Pattern
                    .compile("\"x\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)").matcher(body);
            String act = paramStr(ex.getRequestURI().getQuery(), "action");
            int n2 = s.query.updateRobotPoint(iidp2, pcode,
                    mx.find() ? Double.parseDouble(mx.group(1)) : null,
                    null, null,
                    paramLong(ex.getRequestURI().getQuery(), "deviceResourceId") > 0
                            ? paramLong(ex.getRequestURI().getQuery(), "deviceResourceId") : null,
                    act);
            s.audited("robot.point.update", "island", iidp2, pcode, null, ex);
            s.send(ex, 200, Jsons.obj("updated", n2, "code", pcode));
            return true;
        }
        Matcher ptSync = ROBOT_SYNC.matcher(path);
        if (ptSync.matches()) {
            if (!"POST".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            long iidp = Long.parseLong(ptSync.group(1));
            long robotId = paramLong(ex.getRequestURI().getQuery(), "robotResourceId");
            if (robotId <= 0) {
                s.send(ex, 400, err("需要 robotResourceId"));
                return true;
            }
            int n = s.query.syncRobotPoints(iidp, robotId);
            s.audited("robot.points.sync", "island", iidp, null,
                    Jsons.obj("robot", robotId, "points", n), ex);
            s.send(ex, 200, Jsons.obj("synced", n, "islandId", iidp, "robot", robotId));
            return true;
        }
        Matcher islFlow = ISLAND_FLOW.matcher(path);
        if (islFlow.matches()) {
            long iidf = Long.parseLong(islFlow.group(1));
            if ("GET".equals(method)) {
                s.send(ex, 200, s.query.islandFlowJson(iidf));
                return true;
            }
            if ("PUT".equals(method) || "POST".equals(method)) {
                String body = new String(s.readBody(ex), StandardCharsets.UTF_8);
                int n = s.query.saveIslandFlow(iidf, body);
                s.audited("island.flow", "island", iidf, null,
                        Jsons.obj("bytes", body.length()), ex);
                s.send(ex, 200, Jsons.obj("saved", n, "bytes", body.length()));
                return true;
            }
            throw new IllegalStateException("method not allowed: " + method);
        }
        Matcher islandOne = ISLAND_ONE.matcher(path);
        if (islandOne.matches()) {
            if (!"DELETE".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            long iid = Long.parseLong(islandOne.group(1));
            int n3 = s.query.deleteIsland(iid);
            s.audited("island.delete", "island", iid, null, null, ex);
            s.send(ex, 200, Jsons.obj("deleted", n3, "id", iid));
            return true;
        }
        if ("/api/islands".equals(path)) {
            if (!"POST".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            String body = new String(s.readBody(ex), StandardCharsets.UTF_8);
            long id = s.query.createIsland(jsonField(body, "code"), jsonField(body, "name"));
            s.audited("island.create", "island", id, jsonField(body, "code"), body, ex);
            s.send(ex, 200, Jsons.obj("id", id));
            return true;
        }
        if ("/api/devices".equals(path)) {
            if (!"POST".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            String body = new String(s.readBody(ex), StandardCharsets.UTF_8);
            Integer islandId = jsonInt(body, "islandId");
            if (islandId == null) {
                throw new IllegalArgumentException("必须指定所属岛屿");
            }
            long rid = s.query.createDevice(
                    islandId.longValue(),
                    jsonField(body, "code"),
                    jsonField(body, "name"),
                    jsonField(body, "deviceType"),
                    jsonField(body, "capability"),
                    jsonField(body, "ip"),
                    jsonInt(body, "driverId") == null ? null
                            : jsonInt(body, "driverId").longValue());
            s.audited("device.create", "device", rid, jsonField(body, "code"),
                    Jsons.obj("islandId", islandId,
                            "ip", jsonField(body, "ip"),
                            "capability", jsonField(body, "capability"),
                            "driverId", jsonInt(body, "driverId")), ex);
            s.send(ex, 200, Jsons.obj("resourceId", rid));
            return true;
        }
        Matcher methodParams = METHOD_PARAMS.matcher(path);
        if (methodParams.matches()) {
            s.requireGet(method, ex);
            s.send(ex, 200, s.query.methodParamsJson(
                    java.net.URLDecoder.decode(methodParams.group(1), StandardCharsets.UTF_8)));
            return true;
        }
        if ("/api/drivers".equals(path)) {
            s.requireGet(method, ex);
            s.send(ex, 200, s.query.driversJson());
            return true;
        }
        if ("/api/topology".equals(path)) {
            s.requireGet(method, ex);
            s.send(ex, 200, s.query.topologyJson());
            return true;
        }
        Matcher devMethods = DEVICE_METHODS.matcher(path);
        if (devMethods.matches()) {
            s.requireGet(method, ex);
            s.send(ex, 200, s.query.deviceMethodsJson(Long.parseLong(devMethods.group(1))));
            return true;
        }
        return false;
    }
    // ------------------------------------------------------------ 路由正则

    private static final Pattern DEVICE_METHODS = Pattern.compile("^/api/devices/(\\d+)/methods$");

    private static final Pattern DEVICE_ONE = Pattern.compile("^/api/devices/(\\d+)$");

    private static final Pattern DEVICE_ISLAND = Pattern.compile("^/api/devices/(\\d+)/island$");

    private static final Pattern DEVICE_POS = Pattern.compile("^/api/devices/(\\d+)/position$");

    private static final Pattern DEVICE_CLONE = Pattern.compile("^/api/devices/(\\d+)/clone$");

    private static final Pattern MODEL_DEPLOY = Pattern.compile("^/api/device-models/(\\d+)/deploy$");

    private static final Pattern ISLAND_ONE = Pattern.compile("^/api/islands/(\\d+)$");

    private static final Pattern ISLAND_FLOW = Pattern.compile("^/api/islands/(\\d+)/flow$");

    private static final Pattern ROBOT_POINTS = Pattern.compile("^/api/islands/(\\d+)/robot-points$");

    private static final Pattern ROBOT_SYNC = Pattern.compile("^/api/islands/(\\d+)/robot-points/sync$");

    private static final Pattern ROBOT_POINT_ONE = Pattern.compile("^/api/islands/(\\d+)/robot-points/([^/]+)$");

    private static final Pattern ROBOT_CAPTURE = Pattern.compile("^/api/islands/(\\d+)/robot-points/([^/]+)/capture$");

    private static final Pattern ROBOT_CSV = Pattern.compile("^/api/islands/(\\d+)/robot-points\\.csv$");

    private static final Pattern METHOD_PARAMS = Pattern.compile("^/api/methods/([^/]+)/params$");
}
