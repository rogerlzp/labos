package com.labos.boot.web.service;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.DeviceMapper;
import com.labos.kernel.persistence.QueryMapper;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.util.Map;

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
                String islandPath = queryMapper.selectIslandPath(TENANT, islandId);
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
}
