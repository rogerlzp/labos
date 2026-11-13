package com.labos.kernel.material;

import com.labos.kernel.persistence.DeviceStateMapper;
import com.labos.kernel.persistence.Mappers;

/**
 * 板身份单一真源：临时板号与真 barcode 都解析到不可变 {@code plate_uid}（= {@code plate.id}）。
 *
 * <p><b>为什么不改写历史字符串</b>：{@code position_state}、
 * {@code transfer_transaction}、{@code plate_location_audit}、
 * {@code position_evidence}、{@code intervention_record} 都按字符串 barcode 记录身份，
 * 其中 audit / evidence 是 append-only 证据。扫码后如果去 UPDATE 这些历史行，
 * 等于伪造历史，还会破坏 {@code plate_material_event} 的版本链。
 * ⇒ 扫码只做两件事：新增一条 canonical 别名、把板身份升级为 CONFIRMED。
 *
 * <p>设计见 {@code docs/architecture/点位初始化与物料身份-设计_20261113.md §5}。
 */
public final class PlateIdentity {

    private static DeviceStateMapper dm() {
        return Mappers.get(DeviceStateMapper.class);
    }

    /**
     * 临时板号按「run + 点位 + 序号」生成，而不是按 run 生成。
     *
     * <p>旧的 {@code AUTO-{runId}} 受 {@code uk_plate_barcode} 唯一约束，
     * 同一 run 的并行分支/多块板会被迫共用一个假身份并串账。
     */
    public static String newTempBarcode(long runId, long pointResourceId, int seq) {
        return "TMP-" + runId + "-" + pointResourceId + "-" + seq;
    }

    /**
     * 把任意别名（真 barcode / {@code TMP-...} / 老条码）解析到同一个 plate_uid。
     * 先查 {@code plate.barcode}，未命中再查 {@code plate_barcode_alias}。
     *
     * @return plate.id，或 null（未识别）
     */
    public static Long resolveUid(long tenantId, String barcode) {
        if (barcode == null || barcode.isBlank()) {
            return null;
        }
        try {
            return dm().selectPlateUidByBarcode(tenantId, barcode.trim());
        } catch (RuntimeException e) {
            return null;   // 身份解析失败按「未识别」处理，不阻断物理动作
        }
    }

    /**
     * 扫码读到真 barcode 后的关联动作。
     *
     * <p><b>红线</b>：不 UPDATE 任何历史 barcode 字符串，不改 {@code plate.barcode}。
     * 真 barcode 已被另一块板占用时返回 false（交人工合并，绝不自动合板）。
     *
     * @param tempOrKnownBarcode 当前链路里用的身份（可能是 TMP-，也可能已是真 barcode）
     * @param scannedBarcode     本次扫码读到的真 barcode
     * @return true=已关联并升级为 CONFIRMED
     */
    public static boolean confirmScanned(long tenantId, String tempOrKnownBarcode,
                                         String scannedBarcode, long runId) {
        if (scannedBarcode == null || scannedBarcode.isBlank()) {
            return false;
        }
        String scanned = scannedBarcode.trim();
        Long uid = resolveUid(tenantId, tempOrKnownBarcode);
        if (uid == null) {
            return false;
        }
        Long other = dm().selectPlateUidByBarcode(tenantId, scanned);
        if (other != null && other.longValue() != uid.longValue()) {
            return false;   // 真条码属于另一块板：冲突，人工处理
        }
        dm().insertPlateAliasForUid(tenantId, uid, scanned, "SCANNED", true, runId,
                "扫码确认；原身份 " + tempOrKnownBarcode + " 保留不改");
        dm().markPlateIdentityConfirmed(tenantId, uid);
        return true;
    }

    private PlateIdentity() {
    }
}
