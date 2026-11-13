package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.DeviceStateMapper;
import com.labos.kernel.persistence.Mappers;
import com.labos.kernel.persistence.PauseMapper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 设备内部搬运通知处理（装载口 ↔ 内部格）。
 *
 * <p>用户口径：培养箱/耗材堆栈有一个入口兼出口；流程上调用该设备的方法
 * （{@code MoveInLabware} / {@code MoveOutLabware} 等，复用现有方法名），
 * 设备自己决定内部格位并在完成后通知中控，中控据此维护「板 ↔ 位置」关系。
 *
 * <p><b>板型/高度校验在这里才真正触发</b>：{@code noteTransferMove} 选到的是
 * 机械臂可达的装载口（通常无约束），而约束配置在 {@code internal} 内部格上；
 * 只有设备把要搬入的格位通知回来，中控才知道该拿哪一格的约束去校验。
 *
 * <p>设计见 {@code docs/architecture/位置与耗材管理-设计_20261113.md §8.3}
 * 与 {@code docs/交接-位置与耗材管理-20261113.md §6}。
 */
public final class DeviceInternalTransfer {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(DeviceInternalTransfer.class);

    private final DbConfig db;
    private final long tenantId;

    public DeviceInternalTransfer(DbConfig db, long tenantId) {
        this.db = db;
        this.tenantId = tenantId;
    }

    private DeviceStateMapper dm() {
        return Mappers.get(DeviceStateMapper.class);
    }

    /** 复用现有设备方法名，不新造动词。 */
    public static boolean isInternalMoveMethod(String method) {
        if (method == null) {
            return false;
        }
        return "MoveInLabware".equals(method) || "LoadPlate".equals(method)
                || "MoveOutLabware".equals(method) || "UnloadPlate".equals(method);
    }

    /**
     * 设备回包里的**业务拒绝码**（协议层成功但 {@code data.error_code} 非空）。
     *
     * <p>典型值：{@code PLATE_TOO_HIGH} / {@code PLATE_TYPE_NOT_ALLOWED}。
     * 返回 null = 设备没有拒绝。调用方应据此把步骤升级为 REJECTED，
     * 而不是当成功继续（否则板留在装载口，后续按「已进站」推进）。
     */
    public static String rejectionCode(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return null;
        }
        String code = str(rawResponse, "error_code");
        return code == null || code.isBlank() ? null : code;
    }

    /** 业务拒绝的人话原因；没有则空串。 */
    public static String rejectionMessage(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return "";
        }
        String msg = str(rawResponse, "error_message");
        return msg == null ? "" : msg;
    }

    /**
     * 处理设备完成通知，更新 {@code position_state} + audit + 板位置。
     *
     * <p>设备拒绝（板高/板型不匹配）时**不改动任何位置**：板留在装载口，转人工。
     * 通知解析/写库失败只告警，不阻断物理执行（沿用扫码关联的同款口径）。
     *
     * @return true=位置关系已更新
     */
    public boolean applyNotification(String rawResponse, long runId) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return false;
        }
        String errCode = str(rawResponse, "error_code");
        String errMsg = str(rawResponse, "error_message");
        if (errCode != null && !errCode.isBlank()) {
            log.warn("[内部搬运] 设备拒绝：{} {}（板留在原处，需人工处理）", errCode, errMsg);
            return false;
        }
        String direction = str(rawResponse, "direction");
        String plate = str(rawResponse, "plate");
        if (plate == null || plate.isBlank()) {
            plate = str(rawResponse, "barcode");
        }
        String fromCode = str(rawResponse, "from_position");
        String toCode = str(rawResponse, "to_position");
        String gridRow = num(rawResponse, "grid_row");
        String gridCol = num(rawResponse, "grid_col");
        if (direction == null || plate == null || fromCode == null || toCode == null) {
            log.warn("[内部搬运] 通知字段不全（direction/plate/from/to 必需），忽略");
            return false;
        }

        Long fromRes = dm().selectResourceIdByCode(tenantId, fromCode);
        Long toRes = dm().selectResourceIdByCode(tenantId, toCode);
        if (fromRes == null || toRes == null) {
            log.warn("[内部搬运] 点位 code 找不到资源：{} / {}", fromCode, toCode);
            return false;
        }

        // 出站防御：from 位置必须确有这块板。防止「进站失败/未完成，设备仍按
        // 写死格位通知」造成凭空把板从空格搬出的错误位置记录。
        if ("outbound".equals(direction)) {
            String actual = dm().selectPlateOnPosition(tenantId, fromRes);
            if (actual == null || !actual.equals(plate)) {
                log.warn("[内部搬运] 出站被拒：{} 上是 {}，不是 {}（可能进站未完成，转人工）",
                        fromCode, actual == null ? "空" : actual, plate);
                return false;
            }
        }

        // 只有「搬入内部格」才校验板型/高度；出库到装载口不受内部格约束限制。
        if ("inbound".equals(direction)) {
            String fitErr = new PositionLock(db, tenantId).checkPlateFits(toRes, plate);
            if (fitErr != null) {
                log.warn("[内部搬运] 内部格 {} 拒绝板 {}：{}（板留在 {}，转人工）",
                        toCode, plate, fitErr, fromCode);
                return false;
            }
        }

        Long uid = dm().selectPlateUidByBarcode(tenantId, plate);
        if (uid == null) {
            log.warn("[内部搬运] 板 {} 未建立身份，无法写 content_id", plate);
            return false;
        }

        try {
            dm().clearPositionForInternalMove(tenantId, fromRes);
            dm().setPositionForInternalMove(tenantId, toRes, plate, uid, runId);
            PauseMapper pm = Mappers.get(PauseMapper.class);
            String note = "设备内部搬运 " + direction
                    + " grid=" + gridRow + "x" + gridCol + " run=" + runId;
            pm.insertPlateAudit(tenantId, plate, "KNOWN", "IN_TRANSFER",
                    fromCode, "DEVICE", note, runId);
            pm.insertPlateAudit(tenantId, plate, "IN_TRANSFER", "KNOWN",
                    toCode, "DEVICE", note, runId);
            dm().updatePlateLocation(tenantId, plate, toRes);
            pm.updatePlatePositionStatus(tenantId, plate, "KNOWN");
            log.info("[内部搬运] {} 板 {} : {} → {} (grid {}x{}) 已更新位置关系",
                    direction, plate, fromCode, toCode, gridRow, gridCol);
            return true;
        } catch (RuntimeException e) {
            log.error("[内部搬运] 写库失败（不阻断执行，需人工核对）：" + e.getMessage());
            return false;
        }
    }

    private static String str(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static String num(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)")
                .matcher(json);
        return m.find() ? m.group(1) : "?";
    }
}
