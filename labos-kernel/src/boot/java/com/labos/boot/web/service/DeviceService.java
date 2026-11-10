package com.labos.boot.web.service;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.DeviceMapper;
import com.labos.kernel.persistence.QueryMapper;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Map;
import static com.labos.kernel.persistence.QJson.*;
import com.labos.kernel.persistence.Jsons;
import java.util.List;

/**
 * 设备域的写 Service：{@code DeviceController} 的建/改/克隆/部署四个端点。
 *
 * <p>按「一个 Controller → 一个 Service → 一个 Mapper」从 3500 行的
 * {@code QueryService} 里拆出：13 条 SQL 归到 {@link DeviceMapper}，
 * 业务逻辑归到这里。仍复用 {@code QueryMapper} 上四个共享只读查询
 * （existsDeviceCode / selectIslandPath / selectCapabilityId /
 * selectDeviceIdByCode）——它们还被 QueryService 的设备读方法共用，
 * 等阶段 2 把 QueryMapper 按域拆完会一并归位。
 *
 * <p>事务边界沿用原来的 {@code try (Connection c = db.open())}：
 * Mapper 经 Spring 的 {@code DataSourceUtils} 取同一条绑定连接
 * （与之前所有 SQL 收编批次的模式一致）。
 */
@Service
public class DeviceService {

    private static final long TENANT = 1L;

    private final DbConfig db;
    private final DeviceMapper deviceMapper;
    private final QueryMapper queryMapper;

    public DeviceService(DbConfig db, DeviceMapper deviceMapper,
                         QueryMapper queryMapper) {
        this.db = db;
        this.deviceMapper = deviceMapper;
        this.queryMapper = queryMapper;
    }

    /** 克隆设备：源设备实例连同型号/驱动一起复制，编号自动加后缀避重。 */
    public long cloneDevice(long sourceResourceId, long islandId)
            throws java.sql.SQLException {
        try (Connection c = db.open()) {
            try {
                Map<String, Object> src = deviceMapper.selectCloneSource(
                        TENANT, sourceResourceId, islandId);
                if (src == null) {
                    throw new IllegalArgumentException("源设备不存在: " + sourceResourceId);
                }
                String srcCode = (String) src.get("code");
                String srcName = (String) src.get("name");
                Object path = src.get("islandPath");
                if (path == null) {
                    throw new IllegalArgumentException("岛屿不存在: " + islandId);
                }
                String islandPath = String.valueOf(path);

                String newCode = srcCode + "-2";
                int suffix = 2;
                while (queryMapper.existsDeviceCode(TENANT, newCode)) {
                    suffix++;
                    newCode = srcCode + "-" + suffix;
                }

                Long newResourceId = deviceMapper.insertDeviceNode(TENANT, islandId,
                        newCode, (srcName == null ? newCode : srcName) + " (" + suffix + ")",
                        islandPath + "/" + newCode);
                deviceMapper.cloneDeviceInstance(TENANT, newCode,
                        (srcName == null ? newCode : srcName) + " (" + suffix + ")",
                        newResourceId, sourceResourceId);
                return newResourceId == null ? 0L : newResourceId;
            } catch (RuntimeException e) {
                throw e;
            }
        }
    }

    /** 从型号库部署一台真实设备到岛上（能力必须一起写，否则驱动跳过整条）。 */
    public long deployModel(long modelId, long islandId) throws java.sql.SQLException {
        try (Connection c = db.open()) {
            try {
                Map<String, Object> row = deviceMapper.selectModelForDeploy(
                        TENANT, modelId, islandId);
                if (row == null) {
                    throw new IllegalArgumentException("型号不存在: " + modelId);
                }
                String modelName = (String) row.get("name");
                String modelCode = (String) row.get("modelCode");
                Object path = row.get("islandPath");
                if (path == null) {
                    throw new IllegalArgumentException("岛屿不存在: " + islandId);
                }
                String islandPath = String.valueOf(path);

                // 编号：**用设备池 code**（S100 / ASPIN_2K），不用 name。
                // 用 name 的话中文会被逐个替换成下划线，实测出过
                // "_________ASpin_2K-1" 这种编号，运维看不懂。
                String base = (modelCode == null || modelCode.isBlank()
                        ? modelName : modelCode).replaceAll("[^A-Za-z0-9_]", "_");
                int seq = 1;
                String code = base + "-" + seq;
                while (queryMapper.existsDeviceCode(TENANT, code)) {
                    seq++;
                    code = base + "-" + seq;
                }

                Long rid = deviceMapper.insertDeviceNode(TENANT, islandId, code,
                        modelName + " #" + seq, islandPath + "/" + code);
                deviceMapper.insertModelDeviceInstance(TENANT, code,
                        modelName + " #" + seq, modelId, rid);
                // 只建实例不建能力，驱动会因为"名下设备都没有声明能力"被整条跳过，
                // 现象是设备在界面上有、点不动，报 EC-06-0001（驱动未加载）。
                deviceMapper.insertResourceCapabilityFromModel(rid, modelId);
                return rid == null ? 0L : rid;
            } catch (RuntimeException e) {
                throw e;
            }
        }
    }

    /** 手工建设备（型号按 code 复用，没有就新建）。 */
    public long createDevice(long islandId, String code, String name, String deviceType,
                             String capabilityCode, String ip, Long driverId)
            throws java.sql.SQLException {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("设备编号不能为空");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("设备名称不能为空");
        }

        try (Connection c = db.open()) {
            try {
                // 1) 岛屿必须存在且是 island
                String islandPath = deviceMapper.selectIslandPath(TENANT, islandId);
                if (islandPath == null) {
                    throw new IllegalArgumentException("岛屿不存在: " + islandId);
                }

                // 2) 资源树节点
                Long resourceId = deviceMapper.insertDeviceNode(TENANT, islandId,
                        code.trim(), name.trim(), islandPath + "/" + code.trim());

                // 3) 型号（按 code 查，没有则建）
                Long capabilityId = null;
                if (capabilityCode != null && !capabilityCode.isBlank()) {
                    capabilityId = queryMapper.selectCapabilityId(
                            TENANT, capabilityCode.trim());
                }
                long deviceId;
                Long existing = queryMapper.selectDeviceIdByCode(TENANT, code.trim());
                if (existing != null) {
                    deviceId = existing;
                } else {
                    Long created = deviceMapper.insertDeviceModel(TENANT, code.trim(),
                            name.trim(),
                            deviceType == null || deviceType.isBlank()
                                    ? "MISC" : deviceType.trim(),
                            capabilityId);
                    deviceId = created == null ? 0L : created;
                }

                // 4) 设备实例
                deviceMapper.insertDeviceInstance(TENANT, code.trim(), name.trim(),
                        deviceId, resourceId,
                        ip == null || ip.isBlank() ? null : ip.trim(), driverId);
                return resourceId == null ? 0L : resourceId;
            } catch (RuntimeException e) {
                // Spring 把唯一键冲突包成 DuplicateKeyException（不再是 JDBC 的
                // SQLIntegrityConstraintViolationException），按 message 判断。
                if (e.getMessage() != null && e.getMessage().contains("duplicate key")) {
                    throw new IllegalArgumentException("设备编号已存在: " + code);
                }
                throw e;
            }
        }
    }

    /**
     * 改设备：名称 / IP / 驱动 / 能力。
     *
     * <p>名称同时改资源树节点（界面上看到的是那个）；能力改的是型号，
     * 会影响同型号的所有实例——这是刻意的，能力属于型号不属于单台。
     */
    @org.springframework.transaction.annotation.Transactional(
            rollbackFor = Exception.class)
    public int updateDevice(long resourceId, String name, String ip, Long driverId,
                            String capabilityCode) throws java.sql.SQLException {
        try (Connection c = db.open()) {
            try {
                if (name != null && !name.isBlank()) {
                    deviceMapper.updateResourceNodeName(TENANT, resourceId, name.trim());
                }
                if (capabilityCode != null && !capabilityCode.isBlank()) {
                    Long capId = queryMapper.selectCapabilityId(
                            TENANT, capabilityCode.trim());
                    if (capId != null) {
                        deviceMapper.updateModelCapability(TENANT, resourceId, capId);
                    }
                }
                return deviceMapper.updateDeviceInstanceEndpoint(TENANT, resourceId,
                        ip == null || ip.isBlank() ? null : ip.trim(), driverId);
            } catch (RuntimeException e) {
                throw e;
            }
        }
    }

    public String deviceModelsJson() throws java.sql.SQLException {
        List<String> items = new ArrayList<>();
        for (java.util.Map<String, Object> row : deviceMapper.selectDeviceModels(TENANT)) {
            items.add(Jsons.obj(
                    "modelId", row.get("modelId"),
                    "code", row.get("code"),
                    "name", row.get("name"),
                    "deviceType", row.get("deviceType"),
                    "vendor", row.get("vendor"),
                    "icon", row.get("icon"),
                    "capability", row.get("capability"),
                    "deployed", mint(row, "deployed")));
        }
        return Jsons.obj("items", new Jsons.Raw(Jsons.arr(items)));
    }

    public int deleteDevice(long resourceId) throws java.sql.SQLException {
        // 不删 resource_node：run_node.resource_id 可能引用它（外键会挡住），
        // 且历史执行记录要能查回"这台设备当时在哪"。
        // 拓扑查询会过滤 deleted_at IS NOT NULL，界面上等于删掉了。
        deviceMapper.softDeleteDeviceInstance(TENANT, resourceId);
        return 1;
    }

    public int moveDeviceToIsland(long resourceId, long islandId) throws java.sql.SQLException {
        // islandId <= 0 表示移出（放回设备池）
        if (islandId <= 0) {
            return deviceMapper.detachDeviceFromIsland(TENANT, resourceId);
        }
        try (Connection c = db.open()) {
            try {
                String islandPath = deviceMapper.selectIslandPath(TENANT, islandId);
                if (islandPath == null) {
                    throw new IllegalArgumentException("岛屿不存在: " + islandId);
                }

                String code = deviceMapper.selectNodeCode(TENANT, resourceId, "device");
                if (code == null) {
                    throw new IllegalArgumentException("设备不存在: " + resourceId);
                }

                return deviceMapper.attachDeviceToIsland(TENANT, resourceId,
                        islandId, islandPath + "/" + code);
            } catch (RuntimeException e) {
                throw e;
            }
        }
    }

    /**
     * 保存设备在岛上的摆放位置。
     *
     * <p>坐标存在 {@code resource_node.attr.pos_x/pos_y}——布局是"资源"的属性，
     * 不是"实例"的，所以换驱动、换 IP 都不影响位置。
     */
    public int saveDevicePosition(long resourceId, double x, double y) throws java.sql.SQLException {
        return deviceMapper.updateDevicePosition(TENANT, resourceId, x, y);
    }

    public String devicePoolJson() throws java.sql.SQLException {
        return devicePoolJson(null);
    }

    /**
     * 设备池，可按岛过滤（设计器「按岛找设备」）。
     * islandId 为 null / <=0 时保持全量，保证岛屿视图与 e2e-sim-flow 不回归。
     */
    public String devicePoolJson(Long islandId) throws java.sql.SQLException {
        List<String> items = new ArrayList<>();
        Long islFilter = islandId == null || islandId <= 0 ? null : islandId;
        for (java.util.Map<String, Object> row
                : deviceMapper.selectDevicePool(TENANT, islFilter)) {
            items.add(Jsons.obj(
                    "resourceId", row.get("resourceId"),
                    "code", row.get("code"),
                    "name", row.get("name"),
                    "ip", row.get("ip"),
                    "capability", row.get("capability"),
                    // 型号库登记的原始图片路径。多数存量值指向源服务器
                    // /image/device/<uuid>.png（换环境 404），前端 iconFor()
                    // 会按 capability 映射回本地 /devices/<CAP>.svg。
                    "icon", row.get("icon"),
                    "islandId", row.get("islandId"),
                    "islandCode", row.get("islandCode")));
        }
        return Jsons.obj("items", new Jsons.Raw(Jsons.arr(items)));
    }

    /** 未挂到任何岛的设备（拖拽时的"设备池"）。 */
    public String unassignedDevicesJson() throws java.sql.SQLException {
        List<String> items = new ArrayList<>();
        for (java.util.Map<String, Object> row
                : deviceMapper.selectUnassignedDevices(TENANT)) {
            items.add(Jsons.obj(
                    "resourceId", row.get("resourceId"),
                    "code", row.get("code"),
                    "name", row.get("name"),
                    "ip", row.get("ip"),
                    "capability", row.get("capability")));
        }
        return Jsons.obj("items", new Jsons.Raw(Jsons.arr(items)));
    }

    /** 某台设备（resource_node.id）支持的方法。 */
    public String deviceMethodsJson(long resourceId) throws java.sql.SQLException {
        List<String> items = new ArrayList<>();
        for (java.util.Map<String, Object> row
                : deviceMapper.selectDeviceMethods(resourceId)) {
            // 参数定义原样透出：{seq,name,desc,type,default,required,control_type,unit,range}
            Object params = row.get("params");
            items.add(Jsons.obj(
                    "method", row.get("method"),
                    "name", row.get("name"),
                    "capability", row.get("capability"),
                    "estimateSec", row.get("estimateSec"),
                    "timeoutSec", row.get("timeoutSec"),
                    // 可中断标志：现场确认清单后置 true，暂停才会真的停掉这一步
                    "interruptible", Boolean.TRUE.equals(row.get("interruptible")),
                    "params", params == null ? new Jsons.Raw("[]")
                            : new Jsons.Raw(String.valueOf(params))));
        }
        return Jsons.obj("resourceId", resourceId, "total", items.size(),
                "items", new Jsons.Raw(Jsons.arr(items)));
    }

    /** 某个设备方法的参数定义（设计器填参用）。 */
    public String methodParamsJson(String method) throws java.sql.SQLException {
        java.util.Map<String, Object> row = deviceMapper.selectMethodParams(TENANT, method);
        if (row == null) {
            return Jsons.obj("method", method, "found", false,
                    "params", new Jsons.Raw("[]"));
        }
        Object params = row.get("params");
        return Jsons.obj(
                "method", row.get("method"),
                "name", row.get("name"),
                "capability", row.get("capability"),
                "found", true,
                "params", params == null ? new Jsons.Raw("[]")
                        : new Jsons.Raw(String.valueOf(params)));
    }

    /** 驱动端点列表（新增设备时选）。 */
    public String driversJson() throws java.sql.SQLException {
        List<String> items = new ArrayList<>();
        for (java.util.Map<String, Object> row : deviceMapper.selectDrivers(TENANT)) {
            items.add(Jsons.obj(
                    "id", row.get("id"),
                    "code", row.get("code"),
                    "name", row.get("name"),
                    "protocol", row.get("protocol"),
                    "endpoint", row.get("endpoint"),
                    "status", row.get("status")));
        }
        return Jsons.obj("items", new Jsons.Raw(Jsons.arr(items)));
    }

    /**
     * 拓扑：岛屿 → 设备。
     *
     * <p>层级来自 {@code resource_node}（kind=island / device），
     * 型号信息来自 {@code device}（经 device_instance 关联）。
     */
    public String topologyJson() throws java.sql.SQLException {
        List<String> islands = new ArrayList<>();
        Map<Long, String[]> head = new java.util.LinkedHashMap<>();
        Map<Long, List<String>> devices = new java.util.LinkedHashMap<>();

        for (java.util.Map<String, Object> row : deviceMapper.selectTopology(TENANT)) {
            long iid = ((Number) row.get("id")).longValue();
            head.putIfAbsent(iid, new String[]{
                    String.valueOf(row.get("code")), String.valueOf(row.get("name"))});
            devices.computeIfAbsent(iid, k -> new ArrayList<>());
            // devId / instanceId 为空说明实例已软删（或从未建过），拓扑里不显示
            if (row.get("devId") != null && row.get("instanceId") != null) {
                devices.get(iid).add(Jsons.obj(
                        "resourceId", row.get("devId"),
                        "code", row.get("devCode"),
                        "name", row.get("devName"),
                        "model", row.get("model"),
                        "vendor", row.get("vendor"),
                        "capability", row.get("capability"),
                        // 设备示意图（/devices/xxx.svg）。没有就由前端退回能力色块
                        "icon", row.get("icon"),
                        "instanceId", row.get("instanceId"),
                        "ip", row.get("ip"),
                        "driverEndpoint", row.get("driverEndpoint"),
                        "driverCode", row.get("driverCode"),
                        "x", row.get("posX"),
                        "y", row.get("posY"),
                        "online", Boolean.TRUE.equals(row.get("online")),
                        "status", row.get("devStatus"),
                        "currentMethod", row.get("currentMethod"),
                        "hasPlate", Boolean.TRUE.equals(row.get("hasPlate"))));
            }
        }

        for (Map.Entry<Long, String[]> e : head.entrySet()) {
            List<String> devs = devices.getOrDefault(e.getKey(), List.of());
            islands.add(Jsons.obj(
                    "id", e.getKey(),
                    "code", e.getValue()[0],
                    "name", e.getValue()[1],
                    "deviceCount", devs.size(),
                    "devices", new Jsons.Raw(Jsons.arr(devs))));
        }
        return Jsons.obj("islands", new Jsons.Raw(Jsons.arr(islands)));
    }

    /**
     * 设置设备方法的「执行中可中断」标志（产品写入口；方法必须已在设备库中）。
     *
     * @return 受影响行数（0 = 设备/方法不存在，调用方回 404）
     */
    public int setDeviceMethodInterruptible(long deviceId, String method, boolean value)
            throws java.sql.SQLException {
        return deviceMapper.setDeviceMethodInterruptible(TENANT, deviceId, method, value);
    }

    /**
     * 新增岛屿。
     *
     * <p>岛屿是资源树的顶层（parent_id 为空），path 用 {@code /CODE}。
     */
    public long createIsland(String code, String name) throws java.sql.SQLException {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("岛屿编号不能为空");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("岛屿名称不能为空");
        }
        try {
            Long id = deviceMapper.insertIsland(TENANT, code.trim(), name.trim(),
                    "/" + code.trim());
            return id == null ? 0L : id;
        } catch (RuntimeException e) {
            // Spring 把唯一键冲突包成 DuplicateKeyException（不再是 JDBC 的
            // SQLIntegrityConstraintViolationException），按 message 判断。
            if (e.getMessage() != null && e.getMessage().contains("duplicate key")) {
                throw new IllegalArgumentException("岛屿编号已存在: " + code);
            }
            throw e;
        }
    }

    /**
     * 删岛。岛下还有设备时拒绝——避免设备变成孤儿（parent 指向已删节点）。
     */
    public int deleteIsland(long islandId) throws java.sql.SQLException {
        int deviceCount = deviceMapper.countDevicesUnderIsland(TENANT, islandId);
        if (deviceCount > 0) {
            throw new IllegalArgumentException(
                    "岛上还有 " + deviceCount + " 台设备，先把它们移出再删");
        }
        return deviceMapper.deleteIslandNode(TENANT, islandId);
    }

    /** 某个岛的机械臂点位列表。 */
    public String robotPointsJson(long islandId) throws java.sql.SQLException {
        List<String> items = new ArrayList<>();
        for (java.util.Map<String, Object> row
                : deviceMapper.selectRobotPointDetails(TENANT, islandId)) {
            items.add(Jsons.obj(
                    "id", row.get("id"),
                    "robotResourceId", row.get("robotResourceId"),
                    "code", row.get("code"),
                    "name", row.get("name"),
                    "x", row.get("x"),
                    "y", row.get("y"),
                    "z", row.get("z"),
                    "action", row.get("action"),
                    "deviceResourceId", row.get("deviceResourceId"),
                    "deviceCode", row.get("deviceCode"),
                    "syncedAt", mstr(row, "syncedAt")));
        }
        return Jsons.obj("islandId", islandId,
                "items", new Jsons.Raw(Jsons.arr(items)));
    }

    /** 点位导出 CSV（现场示教器导出的表可以直接对照）。 */
    public String robotPointsCsv(long islandId) throws java.sql.SQLException {
        StringBuilder sb = new StringBuilder("code,name,x,y,z,device_code,action\n");
        for (java.util.Map<String, Object> row
                : deviceMapper.selectRobotPoints(TENANT, islandId)) {
            sb.append(mcsv(row.get("code"))).append(',')
              .append(mcsv(row.get("name"))).append(',')
              .append(mnum(row.get("x"))).append(',')
              .append(mnum(row.get("y"))).append(',')
              .append(mnum(row.get("z"))).append(',')
              .append(mcsv(row.get("deviceCode"))).append(',')
              .append(mcsv(row.get("action"))).append('\n');
        }
        return sb.toString();
    }

    public int importRobotPointsCsv(long islandId, long robotResourceId, String body)
            throws java.sql.SQLException {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("CSV 为空");
        }
        String[] lines = body.split("\\r?\\n");
        int ok = 0;
        try (Connection c = db.open()) {
            try {
                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i].trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    if (i == 0 && line.toLowerCase().startsWith("code")) {
                        continue;   // 表头
                    }
                    String[] f = line.split(",", -1);
                    if (f.length < 1 || f[0].isBlank()) {
                        continue;
                    }
                    String code = unq(f[0]);
                    String name = f.length > 1 ? unq(f[1]) : null;
                    java.math.BigDecimal px = dec(f, 2);
                    java.math.BigDecimal py = dec(f, 3);
                    java.math.BigDecimal pz = dec(f, 4);
                    String devCode = f.length > 5 ? unq(f[5]) : null;
                    String action = f.length > 6 ? unq(f[6]) : "PickPlace";
                    Long devId = devCode == null || devCode.isBlank()
                            ? null : resourceIdByCode(c, devCode);

                    deviceMapper.upsertRobotPoint(TENANT, islandId, robotResourceId,
                            code, name, px, py, pz, devId, action);
                    ok++;
                }
                return ok;
            } catch (java.sql.SQLException | RuntimeException e) {
                throw e;
            }
        }
    }

    /**
     * 一次性同步点位：把岛上的设备都建成"默认点位"。
     *
     * <p>为什么提供这个：现场先是手工导出机械臂点位表，再导入。
     * 但演示/首装时没人有那表，按设备自动生成一版能先跑起来，
     * 后续再逐个改坐标。
     *
     * <p>点位 code 用设备编号（`ID1775`）——`Transfer` 的 source/target 直接引用它。
     */
    public int syncRobotPoints(long islandId, long robotResourceId) throws java.sql.SQLException {
        return deviceMapper.syncRobotPoints(TENANT, islandId, robotResourceId);
    }

    /**
     * 更新点位（示教：把当前机械臂坐标记下来）。
     *
     * <p>只更新传了值的字段：示教时可能只改坐标，不改关联设备。
     */
    public int updateRobotPoint(long islandId, String code,
                                Double x, Double y, Double z,
                                Long deviceResourceId, String action) throws java.sql.SQLException {
        return deviceMapper.updateRobotPoint(TENANT, islandId, code,
                x, y, z, deviceResourceId, action);
    }

    /**
     * 设备型号池：一个型号可以被拖多次，每次产生一台真实设备。
     *
     * <p>这是实验室的实际语义——"这个岛上要 3 台 Incubator_2C"，
     * 而不是"把某一台具体的孵箱搬过去"。
     */
    /** resource_node.id -> device_instance.id（探针/驱动认这个）。 */
    public Long instanceIdOf(long resourceId) throws java.sql.SQLException {
        return deviceMapper.selectInstanceId(TENANT, resourceId);
    }

    /** 岛屿动线（设备节点坐标 + 搬运箭头）。 */
    public String islandFlowJson(long islandId) throws java.sql.SQLException {
        String g = deviceMapper.selectIslandFlow(TENANT, islandId);
        return g == null ? "{\"nodes\":[],\"edges\":[]}" : g;
    }

    /** 保存岛屿动线（整体替换）。 */
    public int saveIslandFlow(long islandId, String graphJson) throws java.sql.SQLException {
        if (graphJson == null || graphJson.isBlank()) {
            throw new IllegalArgumentException("动线不能为空");
        }
        return deviceMapper.upsertIslandFlow(TENANT, islandId, graphJson);
    }


    private Long resourceIdByCode(Connection c, String code) throws java.sql.SQLException {
        // c 只表示调用方已在事务内；Mapper 取同一条 Spring 绑定连接。
        return deviceMapper.selectResourceIdByDeviceCode(TENANT, code);
    }

}
