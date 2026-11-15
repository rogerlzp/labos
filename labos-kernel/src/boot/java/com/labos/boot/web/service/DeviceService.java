package com.labos.boot.web.service;

import com.labos.kernel.device.DeviceModels;
import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.DeviceMapper;
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

    public DeviceService(DbConfig db, DeviceMapper deviceMapper) {
        this.db = db;
        this.deviceMapper = deviceMapper;
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
                while (deviceMapper.existsDeviceCode(TENANT, newCode)) {
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
    /** 部署型号实例（不带 IP / 驱动 —— 事后补）。 */
    public long deployModel(long modelId, long islandId) throws java.sql.SQLException {
        return deployModel(modelId, islandId, null, null);
    }

    /**
     * 部署型号实例（2026-11-15：可同时带 IP 与驱动）。
     *
     * <p><b>为什么</b>：原来 deploy 建出来的实例 {@code ip=NULL}、{@code driver_id} 也不写
     * ⇒ 部署完还得回头去设备卡片上补，交付时**很容易漏** ⇒ 设备在界面上有、点不动。
     */
    public long deployModel(long modelId, long islandId, String ip, Long driverId)
            throws java.sql.SQLException {
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
                while (deviceMapper.existsDeviceCode(TENANT, code)) {
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
                // 部署时就能填 IP / 驱动（原来只能建完再回头补，现场很容易漏）
                if (rid != null && ((ip != null && !ip.isBlank()) || driverId != null)) {
                    deviceMapper.updateDeviceInstanceEndpoint(TENANT, rid,
                            ip == null ? null : ip.trim(), driverId);
                }
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
                    capabilityId = deviceMapper.selectCapabilityId(
                            TENANT, capabilityCode.trim());
                }
                long deviceId;
                Long existing = deviceMapper.selectDeviceIdByCode(TENANT, code.trim());
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
                    Long capId = deviceMapper.selectCapabilityId(
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

    /**
     * 设备清单 —— **模拟端 / tcp client 从中控读的唯一真源**（2026-11-11）。
     *
     * <p>一台设备由 <b>设备ID</b>（device_instance.id）+ <b>设备IP</b>
     * （device_instance.ip）+ 端口 三元组唯一确定。三端（模拟端 / 中控 / tcp client）
     * 拿到的必须是同一组值，所以这里<b>只报表里的值</b>：
     * <ul>
     *   <li>设备IP 取 {@code device_instance.ip}（已与 driver.endpoint 的 host 核对一致）；</li>
     *   <li>端口从 {@code driver.endpoint}（{@code tcp://host:port}）解析；</li>
     *   <li>不猜、不兜底 —— 猜出来的 deviceId 会让 Redis 里的长连接状态挂错设备，
     *       表现为"这台明明在线，页面显示离线"，比直接报错更难查。</li>
     * </ul>
     *
     * <p>{@code simProfile} 是给模拟端的行为画像（robot / incubator / centrifuge …），
     * 由设备类型映射而来 —— 映射放在中控侧，模拟端就不用自己猜"我是哪种设备"。
     */
    public String deviceRegistryJson() throws java.sql.SQLException {
        List<String> items = new ArrayList<>();
        for (java.util.Map<String, Object> row : deviceMapper.selectDeviceRegistry(TENANT)) {
            String endpoint = str(row.get("endpoint"));
            items.add(Jsons.obj(
                    "deviceId", row.get("deviceId"),
                    "resourceId", row.get("resourceId"),
                    "code", row.get("code"),
                    "name", row.get("name"),
                    // **生效的**设备IP：表里的 device_instance.ip 优先，
                    // 没填就用 driver.endpoint 的 host —— 与内核 HeartbeatService
                    // 的 resolveHost 完全同一套规则。
                    // ⇒ 清单给出的值就是内核会拿去拼 Redis key 的那个值，
                    //   sidecar 直接用清单的 ip 写 key，两端必然对得上。
                    //   （早先这里直接输出表里的空 ip，会让 sidecar 写出
                    //     "labos:link:171::19500"，而内核找的是
                    //     "labos:link:171:127.0.0.1:19500" —— 永远离线。）
                    "ip", effectiveIp(str(row.get("ip")), endpoint),
                    "port", portOf(endpoint),
                    "endpoint", endpoint,
                    "capability", row.get("capability"),
                    "deviceType", row.get("deviceType"),
                    "simProfile", simProfileOf(row.get("modelId"), row.get("deviceType"),
                            row.get("capability")),
                    "modelId", row.get("modelId"),
                    "driverCode", row.get("driverCode"),
                    "status", row.get("status")));
        }
        return Jsons.obj("items", new Jsons.Raw(Jsons.arr(items)));
    }

    /**
     * 生效的设备IP：{@code device_instance.ip} 优先，没填就用 driver.endpoint 的 host。
     *
     * <p>与内核 {@code HeartbeatService.resolveHost} 同一套规则 —— 两边必须一致，
     * 否则 sidecar 写的 Redis key 和内核找的 key 不是一个。
     */
    static String effectiveIp(String ip, String endpoint) {
        if (ip != null && !ip.isBlank()) {
            return ip.trim();
        }
        String hp = endpoint == null ? "" : endpoint.trim();
        if (hp.startsWith("tcp://")) {
            hp = hp.substring(6);
        }
        int idx = hp.lastIndexOf(':');
        return idx > 0 ? hp.substring(0, idx) : hp;
    }

    /**
     * 设备类型 → 模拟器行为画像。
     *
     * <p>先按设备类型（{@code device.device_type}，是厂商登记的分类）映射，
     * 映射不到再按能力（{@code capability.code}）兜底 —— 能力比类型粗，
     * 但比"没有"强。都匹配不上就是 generic（不校验方法、不校验参数）。
     */
    static String simProfileOf(Object deviceType, Object capability) {
        return simProfileOf(null, deviceType, capability);
    }

    /**
     * 设备类型/能力 → 模拟器画像。
     *
     * <p><b>型号优先</b>：设备类型分不出"通用机械臂"与"轨道四轴臂"、
     * "通用板存储"与"自动化耗材栈"（对模拟器是不同画像）⇒ 型号 id 优先。
     */
    public static String simProfileOf(Object modelId, Object deviceType, Object capability) {
        String byModel = DeviceModels.simProfileOf(str(modelId));
        if (byModel != null) {
            return byModel;
        }
        String t = str(deviceType);
        switch (t) {
            case "Incubators": return "incubator";
            case "Storage": return "plate_hotel";
            case "Robots": return "robot";
            case "Liquid Handling": return "liquid_handler";
            case "Centrifugation": return "centrifuge";
            case "Sealing": return "sealer";
            case "Peeling": return "peeler";
            case "Dispensers & Washers": return "washer";
            case "Readers & Imagers": return "reader";
            case "Flow Cytometers": return "reader";
            case "Barcode Scanning & Labeling": return "barcode";
            case "MISC": break;                      // MISC 下面能力太杂，靠能力兜底
            default: break;
        }
        switch (str(capability)) {
            case "INCUBATE": return "incubator";
            case "STORE": return "plate_hotel";
            case "TRANSFER": return "robot";
            case "ALIQUOT": return "liquid_handler";
            case "CENTRIFUGE": return "centrifuge";
            case "SEAL": return "sealer";
            case "PEEL": return "peeler";
            case "WASH": return "washer";
            case "READ_PLATE": return "reader";
            case "SCAN": return "barcode";
            default: return "generic";
        }
    }

    /** 从 {@code tcp://host:port} 里取端口；取不到返回 0。 */
    static int portOf(String endpoint) {
        if (endpoint == null) {
            return 0;
        }
        String hp = endpoint.trim();
        if (hp.startsWith("tcp://")) {
            hp = hp.substring(6);
        }
        int idx = hp.lastIndexOf(':');
        if (idx <= 0) {
            return 0;
        }
        try {
            return Integer.parseInt(hp.substring(idx + 1).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
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
                        "hasPlate", Boolean.TRUE.equals(row.get("hasPlate")),
                        // 2026-11-15：接板/交板就绪与最近错误（提取仪握手的关键状态）
                        "readyGive", Boolean.TRUE.equals(row.get("readyGive")),
                        "readyTake", Boolean.TRUE.equals(row.get("readyTake")),
                        "lastError", row.get("lastError"),
                        "currentRunId", row.get("currentRunId")));
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
     * 改岛（名字 / 物理坐标 / 容量）。
     *
     * <p><b>为什么</b>：原来岛<b>建完就不能改</b>（没有 {@code PUT /api/islands/{id}}）
     * ⇒ 改个名或挪个位置只能删了重建，而删岛要求"岛上没有设备" ⇒ 现场根本改不动。
     */
    public int updateIsland(long islandId, String name, String locationJson, int capacity)
            throws java.sql.SQLException {
        if (islandId <= 0) {
            throw new IllegalArgumentException("岛屿 id 不对");
        }
        return deviceMapper.updateIsland(TENANT, islandId, name, locationJson, capacity);
    }

    // ---------------------------------------------------------------- 驱动登记

    /**
     * 登记驱动（2026-11-15）。
     *
     * <p><b>为什么</b>：原来<b>全仓没有一条 {@code INSERT INTO driver} 的 Java 路径</b>
     * ⇒ 新设备只能手写 SQL 登记驱动 ⇒ 交付时这一步必卡。
     */
    public long createDriver(String code, String name, String protocol, String endpoint,
                             String vendor, Boolean resident) throws java.sql.SQLException {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("驱动编号不能为空");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("驱动名称不能为空");
        }
        if (protocol == null || protocol.isBlank()) {
            throw new IllegalArgumentException("驱动协议不能为空（tcp / http / mock / netdll …）");
        }
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("驱动端点不能为空（如 tcp://192.168.1.10:8501）");
        }
        // ⚠ 编号必须先查：uk_driver 是 UNIQUE(code, version)，而 version 可为 NULL
        // （PG 里 NULL 不参与唯一判断）⇒ 库里已有 17 条 version 为 NULL 的驱动，
        // 只靠唯一键拦不住重复编号。
        if (deviceMapper.existsDriverCode(TENANT, code.trim())) {
            throw new IllegalArgumentException("驱动编号已存在: " + code);
        }
        try {
            Long id = deviceMapper.insertDriver(TENANT, code.trim(), name.trim(),
                    protocol.trim().toLowerCase(java.util.Locale.ROOT), endpoint.trim(),
                    vendor == null ? null : vendor.trim(), resident);
            return id == null ? 0L : id;
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("duplicate key")) {
                throw new IllegalArgumentException("驱动编号已存在: " + code);
            }
            throw e;
        }
    }

    /** 改驱动（端点 / 协议 / 名字）。 */
    public int updateDriver(long driverId, String name, String protocol, String endpoint,
                            String vendor, Boolean resident) throws java.sql.SQLException {
        if (driverId <= 0) {
            throw new IllegalArgumentException("驱动 id 不对");
        }
        return deviceMapper.updateDriver(TENANT, driverId, name,
                protocol == null || protocol.isBlank() ? null
                        : protocol.trim().toLowerCase(java.util.Locale.ROOT),
                endpoint, vendor, resident);
    }

    /** 删驱动；有设备实例在用时拒绝（否则那些设备会变成"没驱动"的孤儿）。 */
    public int deleteDriver(long driverId) throws java.sql.SQLException {
        if (driverId <= 0) {
            throw new IllegalArgumentException("驱动 id 不对");
        }
        int used = deviceMapper.countInstancesByDriver(TENANT, driverId);
        if (used > 0) {
            throw new IllegalArgumentException(
                    "还有 " + used + " 台设备在用这个驱动，先把设备改到别的驱动再删");
        }
        return deviceMapper.deleteDriver(TENANT, driverId);
    }

    // ---------------------------------------------------------------- 批量造点位

    /**
     * 批量造网格点位（cols × rows 个层位挂在某台设备下）。
     *
     * <p><b>为什么</b>：交付一条流程要造几十上百个层位（转盘式提取仪 8 个板位 ×4 台、
     * 耗材栈 60 个槽位），而原来只有 {@code scripts/gen-grid-points.py} 能造
     * （要 DB 口令 + shell）⇒ 交付时要么手写 SQL，要么改脚本。
     * <b>搬进 API + 界面</b> ⇒ 建岛后当场把层位点齐。
     *
     * <p>幂等：同 code 复用并更新配置，可反复跑。
     *
     * @return 写了多少个点位
     */
    public int createGridPoints(long deviceResourceId, String deviceCode, int cols, int rows,
                                String pointKind, String slotKind, int slotIndexBase,
                                int maxStack, Double maxHeightMm, String plateTypes,
                                boolean isEntry, boolean isExit, boolean robotReachable)
            throws java.sql.SQLException {
        if (deviceResourceId <= 0) {
            throw new IllegalArgumentException("设备 id 不对");
        }
        if (cols <= 0 || rows <= 0 || cols > 200 || rows > 200) {
            throw new IllegalArgumentException("列数/行数要在 1–200 之间");
        }
        String kind = pointKind == null || pointKind.isBlank() ? "storage" : pointKind.trim();
        // ⚠ 这三类之外的 kind，机械臂**根本看不见这个点**（选位 SQL 硬性过滤）
        if (!java.util.Set.of("incubator", "storage", "transfer_point").contains(kind)) {
            throw new IllegalArgumentException(
                    "点位 kind 只能是 incubator / storage / transfer_point（机械臂只能取这三类）");
        }
        // ⚠ 两个 kind 是**两个不同的轴**，别混：
        //   pointKind → resource_node.kind = "这个节点是什么"（机械臂只看得见这三类）
        //   slotKind → device_point.point_kind = "这个坑位在设备里扮演什么角色"
        //             （CHECK 只允许 nest/deck_in/deck_out/waste）
        String slot = slotKind == null || slotKind.isBlank() ? "nest" : slotKind.trim();
        if (!java.util.Set.of("nest", "waste", "deck_in", "deck_out").contains(slot)) {
            throw new IllegalArgumentException(
                    "坑位语义（slotKind）只能是 nest / waste / deck_in / deck_out");
        }
        // deck_in = 设备**内部**工位（机械臂够不到）；deck_out = 设备**外部**工位（可达）。
        // ⇒ 这两种的 point_role / robot_reachable 由语义**推导**，不接受调用方乱传
        //   （CHECK ck_device_point_deck_in_unreachable：deck_in 必须 internal + 不可达）。
        String role = "both";
        boolean reachable = robotReachable;
        boolean entry = isEntry;
        boolean exit = isExit;
        if ("deck_in".equals(slot)) {
            role = "internal";
            reachable = false;
            entry = false;
            exit = false;
        } else if ("deck_out".equals(slot)) {
            role = "both";
            reachable = true;
            entry = true;
            exit = true;
        }
        if (deviceCode == null || deviceCode.isBlank()) {
            // 没传就按资源 id 反查设备编号（点位编号按它拼）
            deviceCode = com.labos.kernel.persistence.Mappers.get(
                    com.labos.kernel.persistence.ConsumableMapper.class)
                    .selectDeviceCode(TENANT, deviceResourceId);
            if (deviceCode == null || deviceCode.isBlank()) {
                throw new IllegalArgumentException("设备编号不能为空（点位编号按它拼）");
            }
        }
        var cm = com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.ConsumableMapper.class);
        int n = 0;
        for (int r = 1; r <= rows; r++) {
            for (int c = 1; c <= cols; c++) {
                String code = deviceCode.trim() + "-R" + r + "C" + c;
                // 槽位稳定编码 1..N（与 P1 的 device_point.slot_index 对齐：
                // 「从第 N 槽取哪块板」要能精确表达，不随插入顺序漂移）
                int slotIndex = slotIndexBase + (r - 1) * cols + (c - 1);
                cm.insertGridPoint(TENANT, deviceResourceId, code, code, kind, slot,
                        slotIndex, role, r, c, maxStack, maxHeightMm, plateTypes,
                        entry, exit, reachable, (r - 1) * cols + c);
                n++;
            }
        }
        return n;
    }

    /**
     * 按**规格**造点位（不是均匀网格）—— 提取仪的<b>双工位</b>就是这种：
     * 外部交换位（deck_out，机械臂可达）+ 内部操作位（deck_in，够不到）。
     *
     * <p>每条规格：{@code {code, label, slotKind, slotIndex}}。
     * 幂等：同 code 复用并更新。
     *
     * @return 写了多少个点位
     */
    public int createSpecPoints(long deviceResourceId, String deviceCode,
                                java.util.List<java.util.Map<String, Object>> points)
            throws java.sql.SQLException {
        if (deviceResourceId <= 0) {
            throw new IllegalArgumentException("设备 id 不对");
        }
        if (points == null || points.isEmpty()) {
            throw new IllegalArgumentException("缺点位规格（points 不能为空）");
        }
        if (deviceCode == null || deviceCode.isBlank()) {
            deviceCode = com.labos.kernel.persistence.Mappers.get(
                    com.labos.kernel.persistence.ConsumableMapper.class)
                    .selectDeviceCode(TENANT, deviceResourceId);
            if (deviceCode == null || deviceCode.isBlank()) {
                throw new IllegalArgumentException("设备编号不能为空（点位编号按它拼）");
            }
        }
        var cm = com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.ConsumableMapper.class);
        int n = 0;
        int seq = 0;
        for (java.util.Map<String, Object> spec : points) {
            String code = str(spec.get("code"));
            if (code == null || code.isBlank()) {
                code = deviceCode.trim() + "-P" + (seq + 1);
            }
            String label = str(spec.get("label"));
            String slot = str(spec.get("slotKind"));
            slot = slot == null || slot.isBlank() ? "nest" : slot.trim();
            if (!java.util.Set.of("nest", "waste", "deck_in", "deck_out").contains(slot)) {
                throw new IllegalArgumentException(
                        "坑位语义（slotKind）只能是 nest / waste / deck_in / deck_out");
            }
            String role = "both";
            boolean reachable = true;
            boolean entry = true;
            boolean exit = true;
            if ("deck_in".equals(slot)) {
                role = "internal";
                reachable = false;
                entry = false;
                exit = false;
            }
            Object si = spec.get("slotIndex");
            // ⚠ slotIndex 可能是数字也可能是字符串（不同客户端习惯不同）⇒ 两种都认，
            //   别让它抛 ClassCastException 变成 500（这是客户端能修的问题，不该 500）。
            Integer slotIndex = null;
            if (si instanceof Number num) {
                slotIndex = num.intValue();
            } else if (si != null) {
                try {
                    slotIndex = Integer.valueOf(String.valueOf(si).trim());
                } catch (NumberFormatException ignore) {
                    slotIndex = null;   // 认不出来就当没给
                }
            }
            // ⚠ 节点的 kind 必须是机械臂**看得见**的三类之一
            //   （选位 SQL 硬性过滤 resource_node.kind IN
            //    ('incubator','storage','transfer_point')）⇒ 写成 'device' 的话
            //   点位建出来了但**永远选不到**（不报错，只是选不中）。
            String nodeKind = str(spec.get("pointKind"));
            nodeKind = nodeKind == null || nodeKind.isBlank() ? "transfer_point" : nodeKind.trim();
            if (!java.util.Set.of("incubator", "storage", "transfer_point").contains(nodeKind)) {
                throw new IllegalArgumentException(
                        "节点类型（pointKind）只能是 incubator / storage / transfer_point"
                        + "（机械臂只能取这三类）");
            }
            cm.insertGridPoint(TENANT, deviceResourceId, code.trim(),
                    label == null || label.isBlank() ? code.trim() : label.trim(),
                    nodeKind, slot, slotIndex, role,
                    0, 0, 1, null, null, entry, exit, reachable, seq);
            seq++;
            n++;
        }
        return n;
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
