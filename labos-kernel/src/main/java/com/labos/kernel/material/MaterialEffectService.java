package com.labos.kernel.material;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labos.kernel.engine.MaterialOutcome;
import com.labos.kernel.engine.NodeResolution;
import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.Hashes;
import com.labos.kernel.persistence.Jsons;
import com.labos.kernel.persistence.Mappers;
import com.labos.kernel.persistence.MaterialMapper;
import com.labos.kernel.persistence.Tx;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 物料效果处理服务（§8.2 第 4 项）：T1（执行确认 + 声明快照 + 效果意图）、
 * T2（物料投影）、Worker（重试）、隔离/对账。
 *
 * <p>第一版：执行确认（§3.2）+ 声明快照（§3.3）+ 效果意图（T1，显式 plateId 目标）
 * + T2 物料投影（§3.4 单事务：锁 effect/plate → 校验版本 → 写 event → 更新板 → APPLIED）。
 * Worker/隔离/对账 后续增量补。
 */
public final class MaterialEffectService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(MaterialEffectService.class);

    private final long tenantId;
    private final DbConfig db;
    private final MaterialMapper mapper;

    public MaterialEffectService(long tenantId, DbConfig db) {
        this.tenantId = tenantId;
        this.db = db;
        this.mapper = Mappers.get(MaterialMapper.class);
    }

    /**
     * T1 步骤 0：写声明快照（append-only；续跑幂等 ON CONFLICT DO NOTHING）。
     *
     * @return 声明哈希（64 字符 sha256，与库列 declaration_hash 一致）
     */
    public String writeDeclarationSnapshot(long runId, String nodeKey,
                                           int effectIndex, String declarationJson) {
        String hash = Hashes.sha256Hex(declarationJson);
        mapper.insertDeclaration(tenantId, runId, nodeKey, effectIndex,
                declarationJson, hash, null);
        return hash;
    }

    /**
     * 完成处理器入口（§3.1）：写节点执行确认（§3.2 四态）+ T1 效果意图。
     *
     * <p>返回 CONTINUE（HOLD 在效果 PENDING 未闭环时产生——后续增量）。
     */
    public MaterialOutcome onNodeResolution(long runId, String nodeKey, NodeResolution r) {
        switch (r.resolution()) {
            case COMPLETED -> {
                mapper.updateNodeExecution(tenantId, runId, nodeKey,
                        "completed", "device_ack", "{\"source\":\"execution\"}");
                recordEffectIntent(runId, nodeKey);
            }
            case FAILED_CONFIRMED -> mapper.updateNodeExecution(tenantId, runId, nodeKey,
                    "failed_confirmed", null, null);
            case RECONCILIATION_REQUIRED -> mapper.updateNodeExecution(tenantId, runId, nodeKey,
                    "reconciliation_required", null, null);
            case UNRESOLVED -> {
                // 未开始/执行中，不写执行确认
            }
        }
        return MaterialOutcome.CONTINUE;
    }

    /** 闸门（§3.7 / §8.2 第 3 项）：本 run 是否有未闭环效果。 */
    public boolean hasUnclosedEffect(long runId) {
        return mapper.countUnclosedEffects(tenantId, runId) > 0;
    }

    /** 闸门阻断（§3.7 / 第 12 项）：未闭环效果 或 reconciliation_required 节点（无物料声明也阻断）。 */
    public boolean hasGateBlocker(long runId) {
        return mapper.countGateBlockers(tenantId, runId) > 0;
    }

    /** 未闭环计数（隔离确认返回清单用）。 */
    public int unclosedCount(long runId) {
        return mapper.countGateBlockers(tenantId, runId);
    }

    /**
     * 对账（§3.6）：NEEDS_RECONCILIATION → 人工对账 → PENDING(completed) / CANCELLED(not_completed)。
     *
     * <p>completed 需 confirmedPlateId；在同一事务重读板当前 state_version 作为
     * expected_state_version（§3.4 对账转 PENDING 前置），随后立即尝试 T2。
     */
    public MaterialOutcome reconcile(long effectId, boolean completed, Long confirmedPlateId,
                                     Long operatorUserId, String reason) throws SQLException {
        return Tx.inTransaction(db, c -> {
            Map<String, Object> eff = mapper.selectEffectForUpdate(tenantId, effectId);
            if (eff == null || !"NEEDS_RECONCILIATION".equals(String.valueOf(eff.get("status")))) {
                return MaterialOutcome.CONTINUE;   // 不存在或已处理
            }
            if (!completed) {
                mapper.updateEffectStatus(tenantId, effectId, "NEEDS_RECONCILIATION",
                        "CANCELLED", null, null);
                mapper.insertAction(tenantId, effectId, "RECONCILE", "NEEDS_RECONCILIATION",
                        "CANCELLED", "MANUAL", operatorUserId, reason, "{}", null);
                return MaterialOutcome.CONTINUE;
            }
            if (confirmedPlateId == null) {
                return MaterialOutcome.HOLD;   // completed 必须带 confirmedTarget
            }
            Long version = mapper.selectPlateStateVersion(tenantId, confirmedPlateId);
            if (version == null) {
                return MaterialOutcome.HOLD;   // 板不存在
            }
            mapper.updateEffectReconcile(tenantId, effectId, confirmedPlateId, version);
            mapper.insertAction(tenantId, effectId, "RECONCILE", "NEEDS_RECONCILIATION",
                    "PENDING", "MANUAL", operatorUserId, reason, "{}", null);
            // 转 PENDING 后立即尝试 T2（同事务，applyEffect 会 JOIN）
            return applyEffect(effectId);
        });
    }

    /**
     * retry（§3.6）：FAILED（非 MAT_STATE_CONFLICT）→ PENDING，expected_state_version 不变。
     */
    public MaterialOutcome retry(long effectId, Long operatorUserId, String reason)
            throws SQLException {
        return Tx.inTransaction(db, c -> {
            Map<String, Object> eff = mapper.selectEffectForUpdate(tenantId, effectId);
            if (eff == null || !"FAILED".equals(String.valueOf(eff.get("status")))) {
                return MaterialOutcome.CONTINUE;
            }
            if ("MAT_STATE_CONFLICT".equals(eff.get("lastErrorCode"))) {
                return MaterialOutcome.HOLD;   // MAT_STATE_CONFLICT 只能 rebase，不能 retry
            }
            mapper.updateEffectStatus(tenantId, effectId, "FAILED", "PENDING", null, null);
            mapper.insertAction(tenantId, effectId, "RETRY", "FAILED", "PENDING",
                    "MANUAL", operatorUserId, reason, "{}", null);
            return applyEffect(effectId);
        });
    }

    /**
     * rebase（§3.6）：FAILED + MAT_STATE_CONFLICT → PENDING，
     * 把 expected_state_version 更新为板当前 state_version（板已被别处推进）。
     */
    public MaterialOutcome rebase(long effectId, Long operatorUserId, String reason)
            throws SQLException {
        return Tx.inTransaction(db, c -> {
            Map<String, Object> eff = mapper.selectEffectForUpdate(tenantId, effectId);
            if (eff == null || !"FAILED".equals(String.valueOf(eff.get("status")))) {
                return MaterialOutcome.CONTINUE;
            }
            if (!"MAT_STATE_CONFLICT".equals(eff.get("lastErrorCode"))) {
                return MaterialOutcome.HOLD;   // 非 MAT_STATE_CONFLICT 不 rebase
            }
            Long plateId = (Long) eff.get("plateId");
            if (plateId == null) {
                return MaterialOutcome.HOLD;
            }
            Long version = mapper.selectPlateStateVersion(tenantId, plateId);
            if (version == null) {
                return MaterialOutcome.HOLD;
            }
            mapper.updateEffectRebase(tenantId, effectId, version);
            mapper.insertAction(tenantId, effectId, "REBASE", "FAILED", "PENDING",
                    "MANUAL", operatorUserId, reason, "{}", null);
            return applyEffect(effectId);
        });
    }

    /**
     * cancel（§3.6）：未应用效果 → CANCELLED（PENDING/FAILED/NEEDS_RECONCILIATION）。
     */
    public MaterialOutcome cancel(long effectId, Long operatorUserId, String reason)
            throws SQLException {
        return Tx.inTransaction(db, c -> {
            Map<String, Object> eff = mapper.selectEffectForUpdate(tenantId, effectId);
            if (eff == null) {
                return MaterialOutcome.CONTINUE;
            }
            String status = String.valueOf(eff.get("status"));
            if ("APPLIED".equals(status) || "CANCELLED".equals(status)) {
                return MaterialOutcome.CONTINUE;   // 终态不可取消
            }
            mapper.updateEffectStatus(tenantId, effectId, status, "CANCELLED", null, null);
            mapper.insertAction(tenantId, effectId, "CANCEL", status, "CANCELLED",
                    "MANUAL", operatorUserId, reason, "{}", null);
            return MaterialOutcome.CONTINUE;
        });
    }

    /**
     * 人工改状态（§3.6a，单事务）：锁板 → 版本校验 → 写 MANUAL 事件 → 更新板 →
     * 板上已过期的 PENDING 效果转 NEEDS_RECONCILIATION + RECONCILE action。
     *
     * <p>必须复用 T2 写入函数（同一 mapper），不允许另开直写 plate 的路径（§5.5 单写者）。
     *
     * @throws IllegalStateException 版本冲突（expectedVersion 不符）→ 调用方映射 409
     */
    public void manualStateChange(long plateId, String processState, String sealState,
                                  Long expectedVersion, Long operatorUserId, String note)
            throws SQLException {
        Tx.inTransaction(db, c -> {
            Map<String, Object> plate = mapper.selectPlateForUpdate(tenantId, plateId);
            if (plate == null) {
                throw new IllegalStateException("板不存在: " + plateId);
            }
            long curVersion = ((Number) plate.get("stateVersion")).longValue();
            if (expectedVersion != null && expectedVersion.longValue() != curVersion) {
                throw new IllegalStateException("版本冲突：期望 " + expectedVersion
                        + "，当前 " + curVersion);
            }
            String fromProcess = (String) plate.get("processState");
            String fromSeal = (String) plate.get("sealState");
            String toProcess = processState != null ? processState : fromProcess;
            String toSeal = sealState != null ? sealState : fromSeal;
            long toVersion = curVersion + 1;

            java.util.List<String> changed = new ArrayList<>();
            if (!Objects.equals(fromProcess, toProcess)) {
                changed.add("process_state");
            }
            if (!Objects.equals(fromSeal, toSeal)) {
                changed.add("seal_state");
            }

            mapper.insertEvent(tenantId, plateId, null, "manual_correction",
                    fromProcess, toProcess, fromSeal, toSeal,
                    Jsons.arr(changed.stream().map(Jsons::quote).toList()),
                    curVersion, toVersion,
                    null, null, 0, null,
                    "MANUAL_CORRECTION", "manual_confirmed", "{}", "MANUAL",
                    operatorUserId, note, null);

            mapper.updatePlateState(tenantId, plateId, toProcess, toSeal, toVersion);

            // 步骤 6：板上已过期的 PENDING 效果 → NEEDS_RECONCILIATION + RECONCILE
            for (Long effectId : mapper.selectStaleEffectIds(tenantId, plateId, toVersion)) {
                mapper.updateEffectStatus(tenantId, effectId, "PENDING", "NEEDS_RECONCILIATION",
                        null, "板已被人工改状态，期望版本过期");
                mapper.insertAction(tenantId, effectId, "RECONCILE", "PENDING",
                        "NEEDS_RECONCILIATION", "MANUAL", operatorUserId, note, "{}", null);
            }
            return null;
        });
    }

    /**
     * Worker：处理一个到期的 PENDING 效果（单次）。
     *
     * @return true=处理了一个；false=无到期效果
     */
    public boolean processNextDue() throws SQLException {
        Long effectId = mapper.selectDueEffectId(tenantId);
        if (effectId == null) {
            return false;
        }
        try {
            applyEffect(effectId);
        } catch (SQLException e) {
            // applyEffect 事务已回滚，effect 保持 PENDING；独立记失败计数 + 退避
            mapper.recordRetryFailure(tenantId, effectId,
                    java.time.Instant.now().plusSeconds(30).toString(),
                    "EC-MATERIAL-DB", e.getMessage());
            log.warn("[Worker] effect {} 应用失败，退避 30s：{}", effectId, e.getMessage());
        }
        return true;
    }

    /**
     * T2 物料投影（§3.4，单事务）：
     * 锁 effect/plate → 校验 state_version → 写 event → 更新板 → APPLIED + action + 节点投影。
     *
     * @return CONTINUE=已应用；HOLD=版本冲突/目标不可用（需 rebase/reconcile）
     */
    public MaterialOutcome applyEffect(long effectId) throws SQLException {
        return Tx.inTransaction(db, c -> {
            Map<String, Object> eff = mapper.selectEffectForUpdate(tenantId, effectId);
            if (eff == null || !"PENDING".equals(String.valueOf(eff.get("status")))) {
                return MaterialOutcome.CONTINUE;   // 不存在或已处理
            }

            Long plateId = (Long) eff.get("plateId");
            Long expectedVersion = (Long) eff.get("expectedStateVersion");
            if (plateId == null || expectedVersion == null) {
                // 无确定目标（NEEDS_RECONCILIATION 类效果不该进 T2）
                return MaterialOutcome.HOLD;
            }

            Map<String, Object> plate = mapper.selectPlateForUpdate(tenantId, plateId);
            if (plate == null) {
                mapper.updateEffectStatus(tenantId, effectId, "PENDING",
                        "NEEDS_RECONCILIATION", "PLATE_MISSING", "目标板不存在");
                return MaterialOutcome.HOLD;
            }

            long curVersion = ((Number) plate.get("stateVersion")).longValue();
            if (curVersion != expectedVersion) {
                // MAT_STATE_CONFLICT（§3.4）：不自动重试，只能 rebase 或转对账
                mapper.updateEffectStatus(tenantId, effectId, "PENDING",
                        "FAILED", "MAT_STATE_CONFLICT",
                        "state_version 冲突：期望 " + expectedVersion + "，实际 " + curVersion);
                return MaterialOutcome.HOLD;
            }

            String fromProcess = (String) plate.get("processState");
            String fromSeal = (String) plate.get("sealState");
            String toProcess = (String) eff.get("expectedProcessState");
            String toSeal = (String) eff.get("expectedSealState");
            long toVersion = curVersion + 1;

            List<String> changed = new ArrayList<>();
            if (!Objects.equals(fromProcess, toProcess)) {
                changed.add("process_state");
            }
            if (!Objects.equals(fromSeal, toSeal)) {
                changed.add("seal_state");
            }

            long sourceRunId = ((Number) eff.get("sourceRunId")).longValue();
            String sourceNodeKey = String.valueOf(eff.get("sourceNodeKey"));
            int sourceLoopIndex = ((Number) eff.get("sourceLoopIndex")).intValue();

            // ④ 写恰好一条事件（before/after + 版本区间 + changed_fields）
            mapper.insertEvent(tenantId, plateId, effectId, "state_changed",
                    fromProcess, toProcess, fromSeal, toSeal,
                    Jsons.arr(changed.stream().map(Jsons::quote).toList()),
                    curVersion, toVersion,
                    sourceRunId, sourceNodeKey, sourceLoopIndex, null,
                    "DEVICE_CONFIRMED", "device_ack", "{}", "AUTO",
                    null, null, null);

            // ⑤ 更新板投影
            mapper.updatePlateState(tenantId, plateId, toProcess, toSeal, toVersion);
            // ⑥ effect → APPLIED + action + 节点投影
            mapper.updateEffectStatus(tenantId, effectId, "PENDING", "APPLIED", null, null);
            mapper.insertAction(tenantId, effectId, "APPLY", "PENDING", "APPLIED",
                    "AUTO", null, null, "{}", null);
            mapper.updateNodeMaterialSync(tenantId, sourceRunId, sourceNodeKey, "applied");

            return MaterialOutcome.CONTINUE;
        });
    }

    /**
     * T1 效果意图：节点成功且声明 mode=transition 时，写 plate_material_effect + DECLARE action。
     *
     * <p>第一版目标解析只处理显式 {@code target.plateId}（§3.3a 规则 1）；
     * 无 plateId 或解析不到 → 写 NEEDS_RECONCILIATION（plate_id NULL，规则 7）。
     */
    private void recordEffectIntent(long runId, String nodeKey) {
        Map<String, Object> decl = mapper.selectDeclaration(tenantId, runId, nodeKey);
        if (decl == null || decl.get("declaration") == null) {
            return;   // 无声明快照（mode=none/legacy 或非 transition 节点）
        }
        String declarationJson = String.valueOf(decl.get("declaration"));
        String declHash = String.valueOf(decl.get("declarationHash"));
        if (!"transition".equals(modeOf(declarationJson))) {
            return;   // none / legacy_unspecified：不建 effect
        }

        Long plateId = resolveTargetPlate(declarationJson, runId);
        String payloadHash = Hashes.sha256Hex(declarationJson + "|plateId=" + plateId);
        Long expectedStateVersion = plateId == null
                ? null : mapper.selectPlateStateVersion(tenantId, plateId);
        if (expectedStateVersion == null) {
            // 板不存在或版本不可读 ⇒ 不能确定目标板（§3.3a 规则 7）：
            // 写 plate_id=NULL + NEEDS_RECONCILIATION，不写悬空 plate_id。
            plateId = null;
        }

        // 第一版 target_snapshot = 原始声明（证据链/position_status 快照后续补）
        long effectId = insertEffect(runId, nodeKey, 0, 0, 0, plateId,
                declarationJson, declHash, payloadHash, declarationJson,
                expectedStateVersion, expectedProcessState(declarationJson),
                expectedSealState(declarationJson));
        if (effectId > 0) {
            boolean pending = plateId != null;
            appendAction(effectId, "DECLARE", null,
                    pending ? "PENDING" : "NEEDS_RECONCILIATION",
                    "AUTO", null, null, "{}", null);
            if (pending) {
                // T1 → T2 立即尝试（§3.4：当前线程立即尝试；失败由 Worker 补偿）
                try {
                    applyEffect(effectId);
                } catch (SQLException e) {
                    log.error("[T2] 立即应用失败（effect 保持 PENDING，Worker 补偿）: "
                            + effectId + " " + e.getMessage());
                }
            }
        }
    }

    /**
     * T1：写效果意图。
     *
     * <p>业务规则（ck_effect_applicable_target）：{@code PENDING} 必须
     * {@code plate_id} + {@code expected_state_version} 非空；解析不到确定的
     * 目标板时写 {@code NEEDS_RECONCILIATION}（plate_id 可为 NULL，§3.3a 规则 7）。
     */
    public long insertEffect(long runId, String nodeKey, int loopIndex, int effectIndex,
                             int attempt, Long plateId, String declarationJson,
                             String declarationHash, String payloadHash,
                             String targetSnapshot, Long expectedStateVersion,
                             String expectedProcessState, String expectedSealState) {
        String status = (plateId != null && expectedStateVersion != null)
                ? "PENDING" : "NEEDS_RECONCILIATION";
        mapper.insertEffect(tenantId, runId, nodeKey, loopIndex, effectIndex,
                attempt, plateId, declarationJson, declarationHash, payloadHash,
                targetSnapshot, "{}", expectedStateVersion,
                expectedProcessState, expectedSealState, status);
        Long id = mapper.selectEffectId(tenantId, runId, nodeKey, loopIndex, effectIndex);
        return id == null ? -1L : id;
    }

    /** 追加一条效果动作（append-only）。 */
    public void appendAction(long effectId, String actionType, String fromStatus,
                             String toStatus, String actorType, Long operatorUserId,
                             String reason, String evidenceSnapshot, String errorCode) {
        mapper.insertAction(tenantId, effectId, actionType, fromStatus, toStatus,
                actorType, operatorUserId, reason, evidenceSnapshot, errorCode);
    }

    /**
     * §3.3a 目标解析（第一版）：plateId 优先 → barcode → 位置（fail-closed）。
     *
     * <p>位置寻址（规则 2/3/3b）：occupied_by_run 必须 = 当前 run；
     * position_status ∈ {UNKNOWN, LOST} 或 reserved_by_run 非空 ⇒ 不可用，返回 null。
     */
    private Long resolveTargetPlate(String declarationJson, long runId) {
        Long plateId = targetPlateId(declarationJson);
        if (plateId != null) {
            return plateId;
        }
        String barcode = targetBarcode(declarationJson);
        if (barcode != null && !barcode.isBlank()) {
            Map<String, Object> p = mapper.selectPlateByBarcode(tenantId, barcode);
            return p == null ? null : ((Number) p.get("id")).longValue();
        }
        Long resourceId = targetResourceId(declarationJson);
        if (resourceId != null) {
            Map<String, Object> ps = mapper.selectPlateByLocation(tenantId, resourceId);
            if (ps == null) {
                return null;   // 位置无板
            }
            Long occupiedByRun = (Long) ps.get("occupiedByRun");
            if (occupiedByRun == null || occupiedByRun.longValue() != runId) {
                return null;   // 归属不符（规则 2）
            }
            String posStatus = (String) ps.get("positionStatus");
            if ("UNKNOWN".equals(posStatus) || "LOST".equals(posStatus)) {
                return null;   // 位置不可信（规则 3）
            }
            if (ps.get("reservedByRun") != null) {
                return null;   // 在途/预约（规则 3b）
            }
            return ((Number) ps.get("id")).longValue();
        }
        return null;
    }

    // ---------------------------------------------------------------- JSON 解析

    static String modeOf(String declarationJson) {
        JsonNode n = read(declarationJson);
        JsonNode m = n == null ? null : n.get("mode");
        return m == null || !m.isTextual() ? "none" : m.asText();
    }

    static Long targetPlateId(String declarationJson) {
        JsonNode n = read(declarationJson);
        JsonNode plateId = n == null ? null : n.path("target").path("plateId");
        return plateId != null && plateId.isNumber() ? plateId.asLong() : null;
    }

    static String expectedProcessState(String declarationJson) {
        JsonNode n = read(declarationJson);
        JsonNode v = n == null ? null : n.path("set").path("processState");
        return v != null && v.isTextual() ? v.asText() : null;
    }

    static String expectedSealState(String declarationJson) {
        JsonNode n = read(declarationJson);
        JsonNode v = n == null ? null : n.path("set").path("sealState");
        return v != null && v.isTextual() ? v.asText() : null;
    }

    static String targetBarcode(String declarationJson) {
        JsonNode n = read(declarationJson);
        JsonNode b = n == null ? null : n.path("target").path("barcode");
        return b != null && b.isTextual() ? b.asText() : null;
    }

    static Long targetResourceId(String declarationJson) {
        JsonNode n = read(declarationJson);
        JsonNode rid = n == null ? null : n.path("target").path("location").path("resourceId");
        return rid != null && rid.isNumber() ? rid.asLong() : null;
    }

    private static JsonNode read(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            return null;   // 非法声明按无声明处理（预检已在启动前拒绝，这里兜底）
        }
    }
}
