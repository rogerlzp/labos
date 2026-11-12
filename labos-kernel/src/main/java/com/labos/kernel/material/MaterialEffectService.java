package com.labos.kernel.material;

import com.labos.kernel.persistence.Hashes;
import com.labos.kernel.persistence.Mappers;
import com.labos.kernel.persistence.MaterialMapper;

/**
 * 物料效果处理服务（§8.2 第 4 项）：T1（执行确认 + 声明快照 + 效果意图）、
 * T2（物料投影）、Worker（重试）、隔离/对账。
 *
 * <p>第一版骨架：先落「声明快照写入」与「效果意图」两个最基础的 T1 步骤；
 * T2/Worker/对账 后续增量补。业务规则见设计稿 §3.3/§3.4/§3.6。
 */
public final class MaterialEffectService {

    private final long tenantId;
    private final MaterialMapper mapper;

    public MaterialEffectService(long tenantId) {
        this.tenantId = tenantId;
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
     * T1：写效果意图。
     *
     * <p>业务规则（ck_effect_applicable_target）：{@code PENDING} 必须
     * {@code plate_id} + {@code expected_state_version} 非空；解析不到确定的
     * 目标板时写 {@code NEEDS_RECONCILIATION}（plate_id 可为 NULL，§3.3a 规则 7）。
     *
     * @return effect id（后续 DECLARE 动作/巡检用）
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
}
