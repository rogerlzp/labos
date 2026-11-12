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

        Long plateId = targetPlateId(declarationJson);
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
            appendAction(effectId, "DECLARE", null,
                    plateId != null ? "PENDING" : "NEEDS_RECONCILIATION",
                    "AUTO", null, null, "{}", null);
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
