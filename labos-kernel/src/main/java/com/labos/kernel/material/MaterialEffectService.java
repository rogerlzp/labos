package com.labos.kernel.material;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labos.kernel.engine.ExecutionResolution;
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
     * 完成处理器入口（§3.1）：单事务写节点执行确认（§3.2 四态）+ T1 效果意图，
     * 事务提交后再在**独立事务**立即尝试 T2；物料未闭环返回 HOLD。
     *
     * <p>评审 R13-01 / R13-02 / R13-04 的修复点：
     * <ul>
     *   <li>执行确认 + effect + DECLARE action 在同一事务原子提交，否则崩溃会留
     *       「completed 但无 effect」或「effect 无 DECLARE action」；</li>
     *   <li>执行确认只从 unresolved 单向写（CAS），第 7 出口不得把已写的 completed
     *       与物理证据三件套覆盖成 reconciliation_required；</li>
     *   <li>T2 独立于 T1：T2 回滚不能把「设备已动作」的执行确认一起回滚；
     *       T2 失败只让 effect 保持 PENDING，由 Worker 补偿，不升级为隔离；</li>
     *   <li>T1 写不进 ⇒ {@link MaterialPersistenceException} ⇒ 调用方隔离 run、不重跑设备；</li>
     *   <li>HOLD 真正返回，使顺序执行路径与图路径都在后继派发前停下。</li>
     * </ul>
     */
    public MaterialOutcome onNodeResolution(long runId, String nodeKey, NodeResolution r) {
        long pendingEffect;
        try {
            pendingEffect = Tx.inTransaction(db, c -> {
                if (r.resolution() == ExecutionResolution.COMPLETED) {
                    int written = mapper.updateNodeResolutionIfUnresolved(
                            tenantId, runId, nodeKey, "completed",
                            "device_ack", "{\"source\":\"execution\"}");
                    if (written == 0 && r.loopIndex() <= 1) {
                        // 已有终态确认且不是多圈：只补记录，禁止重新创建效果意图
                        // （§3.2 禁止重跑设备）。
                        return -1L;
                    }
                    // 多圈（loopIndex>=2）时汇总行早已 completed，CAS 必然 0 行，
                    // 但**每圈都要有一条 Effect**（R13-06）—— 效果是圈级数据，
                    // 执行确认是节点级数据，两者不能共用一个「写一次」的判断。
                    // 同圈重复调用由唯一键 (run,node,loop_index,effect_index) 兜住。
                    return recordEffectIntent(runId, nodeKey, r.loopIndex());
                }
                if (r.resolution() == ExecutionResolution.FAILED_CONFIRMED) {
                    mapper.updateNodeResolutionIfUnresolved(
                            tenantId, runId, nodeKey, "failed_confirmed", null, null);
                } else if (r.resolution() == ExecutionResolution.RECONCILIATION_REQUIRED) {
                    mapper.updateNodeResolutionIfUnresolved(
                            tenantId, runId, nodeKey, "reconciliation_required", null, null);
                }
                // UNRESOLVED：未开始/执行中，不写执行确认
                return -1L;
            });
        } catch (SQLException | RuntimeException e) {
            if (e instanceof MaterialPersistenceException) {
                throw (MaterialPersistenceException) e;
            }
            throw new MaterialPersistenceException(runId, nodeKey, e);
        }

        if (pendingEffect > 0) {
            try {
                applyEffect(pendingEffect);
            } catch (Exception e) {
                log.warn("[T2] 立即应用失败（effect 保持 PENDING，Worker 补偿）: effect={}",
                        pendingEffect, e);
            }
        }

        // fail-closed：闸门读失败时闭环状态未知，绝不能当成「无阻断」放行后继设备动作。
        // 同样抛 MaterialPersistenceException，由上层隔离 run 而不是标 failed 后允许续跑。
        try {
            return hasGateBlocker(runId) ? MaterialOutcome.HOLD : MaterialOutcome.CONTINUE;
        } catch (RuntimeException e) {
            throw new MaterialPersistenceException(runId, nodeKey, e);
        }
    }

    /** 闸门（§3.7 / §8.2 第 3 项）：本 run 是否有未闭环效果。 */
    public boolean hasUnclosedEffect(long runId) {
        return mapper.countUnclosedEffects(tenantId, runId) > 0;
    }

    /** 闸门阻断（§3.7 / 第 12 项）：未闭环效果 或 reconciliation_required 节点（无物料声明也阻断）。 */
    public boolean hasGateBlocker(long runId) {
        return mapper.countGateBlockers(tenantId, runId) > 0;
    }

    /** 闸门阻断（§3.7 前置域）：只在**直接前驱**里判未闭环效果/reconciliation_required，不是整 run。 */
    public boolean hasGateBlockerFor(long runId, java.util.List<String> preds) {
        if (preds == null || preds.isEmpty()) {
            return false;
        }
        return mapper.countGateBlockersByNodes(tenantId, runId, preds) > 0;
    }

    /** 未闭环计数（隔离确认返回清单用）。 */
    public int unclosedCount(long runId) {
        return mapper.countGateBlockers(tenantId, runId);
    }

    /** §6 读：效果列表。 */
    public java.util.List<Map<String, Object>> listEffects(String status, Long runId, Long plateId) {
        return mapper.selectEffects(tenantId, status, runId, plateId);
    }

    /** §6 读：效果动作历史。 */
    public java.util.List<Map<String, Object>> listActions(long effectId) {
        return mapper.selectActions(tenantId, effectId);
    }

    /** §6 人工改状态：barcode → plate.id（与 T1 同一条查询，只认未删除的板）。 */
    public Long plateIdByBarcode(String barcode) {
        if (barcode == null || barcode.isBlank()) {
            return null;
        }
        Map<String, Object> p = mapper.selectPlateByBarcode(tenantId, barcode);
        return p == null ? null : ((Number) p.get("id")).longValue();
    }

    /** 规则 4：processState 是否在启用字典里。 */
    public boolean processStateEnabled(String code) {
        return code == null || mapper.countEnabledProcessState(tenantId, code) > 0;
    }

    /** 规则 4：声明里的 processState 必须在启用字典里。返回 null=通过，否则错误消息。 */
    public String checkProcessStateDict(String declarationJson) {
        String code = expectedProcessState(declarationJson);
        if (code != null && mapper.countEnabledProcessState(tenantId, code) == 0) {
            return "processState 不在启用字典: " + code;
        }
        return null;
    }

    /** §3.5 跨圈聚合：重算节点物料同步状态（最阻塞者优先）并写回汇总行。 */
    public void recomputeNodeMaterialSync(long runId, String nodeKey) {
        String sync = mapper.selectNodeMaterialSync(tenantId, runId, nodeKey);
        if (sync != null) {
            mapper.updateNodeMaterialSync(tenantId, runId, nodeKey, sync);
        }
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
            // 评审 R13-10：锁序必须与 applyEffect 一致（先 effect 后 plate）。
            // 原实现先锁板、再无锁 UPDATE effect，与 applyEffect 构成死锁环。
            List<Map<String, Object>> pending =
                    mapper.selectPendingEffectIdsForUpdate(tenantId, plateId);
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
            // 与 T2 共用同一个纯函数：null = 不改，不能两条路径各写一份口径。
            TargetStates to = TargetStates.merge(fromProcess, fromSeal, processState, sealState);
            String toProcess = to.processState();
            String toSeal = to.sealState();
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

            // 步骤 6：板上已过期的 PENDING 效果 → NEEDS_RECONCILIATION + RECONCILE。
            // 用上面已加锁的候选集，在内存里按新版本过滤（不能先读板版本再锁 effect）。
            for (Map<String, Object> e : pending) {
                Object v = e.get("expectedStateVersion");
                if (v == null || ((Number) v).longValue() == toVersion) {
                    continue;   // 期望版本仍有效，不动
                }
                long effectId = ((Number) e.get("id")).longValue();
                // 评审 R13-13：CAS 失败不写 action（避免写出与实际状态不符的审计行）
                int n = mapper.updateEffectStatus(tenantId, effectId, "PENDING",
                        "NEEDS_RECONCILIATION", null, "板已被人工改状态，期望版本过期");
                if (n == 0) {
                    continue;
                }
                mapper.insertAction(tenantId, effectId, "RECONCILE", "PENDING",
                        "NEEDS_RECONCILIATION", "MANUAL", operatorUserId, note, "{}", null);
                Object rid = e.get("sourceRunId");
                Object nk = e.get("sourceNodeKey");
                if (rid instanceof Number && nk != null) {
                    recomputeNodeMaterialSync(((Number) rid).longValue(), String.valueOf(nk));
                }
            }
            return null;
        });
    }

    /** B6：Worker 最多重试次数，超限转 FAILED + 人工告警（不无限重试）。 */
    private static final int MAX_APPLY_ATTEMPTS = 5;

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
            // §3.5 / 评审 R13-10：applyEffect 事务已回滚，失败计数与退避必须在
            // **独立短事务**里写（此时上一层事务已结束，Tx 会新开事务），
            // 不能与 T2 共用，也不能在事务外裸 UPDATE。
            int exhausted = 0;
            try {
                exhausted = Tx.inTransaction(db, c -> {
                    mapper.recordRetryFailure(tenantId, effectId,
                            java.time.Instant.now().plusSeconds(30).toString(),
                            "EC-MATERIAL-DB", e.getMessage());
                    // B6：重试超限 → FAILED（转人工，不无限重试）
                    return mapper.failEffectIfExhausted(tenantId, effectId, MAX_APPLY_ATTEMPTS);
                });
            } catch (SQLException inner) {
                log.error("[Worker] effect {} 失败计数写入失败（需巡检/人工）: {}",
                        effectId, inner.getMessage());
            }
            if (exhausted > 0) {
                raiseRetryExhaustedAlert(effectId);
                log.warn("[Worker] effect {} 重试 {} 次超限，已转 FAILED 并告警",
                        effectId, MAX_APPLY_ATTEMPTS);
            } else {
                log.warn("[Worker] effect {} 应用失败，退避 30s：{}", effectId, e.getMessage());
            }
        }
        return true;
    }

    /** B6：写一条 alert_state（fingerprint 幂等，重复不重推）。 */
    private void raiseRetryExhaustedAlert(long effectId) {
        try {
            Mappers.get(com.labos.kernel.persistence.OpsUserMapper.class)
                    .upsertAlertState(tenantId, "material-effect-failed:" + effectId,
                            "open", null, "物料效果重试超限，转人工对账（effect #" + effectId + "）");
        } catch (RuntimeException e) {
            log.error("[Worker] effect {} 告警写入失败（不重试）: {}", effectId, e.getMessage());
        }
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
            long sourceRunId = ((Number) eff.get("sourceRunId")).longValue();
            String sourceNodeKey = String.valueOf(eff.get("sourceNodeKey"));
            if (plateId == null || expectedVersion == null) {
                // 无确定目标（NEEDS_RECONCILIATION 类效果不该进 T2）
                return MaterialOutcome.HOLD;
            }

            Map<String, Object> plate = mapper.selectPlateForUpdate(tenantId, plateId);
            if (plate == null) {
                // 评审 R13-13：失败分支也要写 action 并重算节点投影（与成功路径对称）
                int n = mapper.updateEffectStatus(tenantId, effectId, "PENDING",
                        "NEEDS_RECONCILIATION", "PLATE_MISSING", "目标板不存在");
                if (n > 0) {
                    mapper.insertAction(tenantId, effectId, "APPLY", "PENDING",
                            "NEEDS_RECONCILIATION", "AUTO", null, null, "{}", "PLATE_MISSING");
                    recomputeNodeMaterialSync(sourceRunId, sourceNodeKey);
                }
                return MaterialOutcome.HOLD;
            }

            long curVersion = ((Number) plate.get("stateVersion")).longValue();
            if (curVersion != expectedVersion) {
                // MAT_STATE_CONFLICT（§3.4）：不自动重试，只能 rebase 或转对账
                int n = mapper.updateEffectStatus(tenantId, effectId, "PENDING",
                        "FAILED", "MAT_STATE_CONFLICT",
                        "state_version 冲突：期望 " + expectedVersion + "，实际 " + curVersion);
                if (n > 0) {
                    mapper.insertAction(tenantId, effectId, "APPLY", "PENDING",
                            "FAILED", "AUTO", null, null, "{}", "MAT_STATE_CONFLICT");
                    recomputeNodeMaterialSync(sourceRunId, sourceNodeKey);
                }
                return MaterialOutcome.HOLD;
            }

            String fromProcess = (String) plate.get("processState");
            String fromSeal = (String) plate.get("sealState");
            // 声明只改了其中一个字段时，另一个字段必须保持现值：
            // set 里没写 = 不改（null）；写 sealState="none" 才是显式清膜。
            // 与 manualStateChange 共用同一个纯函数（可纯逻辑单测），
            // 否则没写的那个字段会被写成 NULL 清掉。
            TargetStates to = TargetStates.merge(fromProcess, fromSeal,
                    (String) eff.get("expectedProcessState"),
                    (String) eff.get("expectedSealState"));
            String toProcess = to.processState();
            String toSeal = to.sealState();
            long toVersion = curVersion + 1;

            List<String> changed = new ArrayList<>();
            if (!Objects.equals(fromProcess, toProcess)) {
                changed.add("process_state");
            }
            if (!Objects.equals(fromSeal, toSeal)) {
                changed.add("seal_state");
            }

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
            // §3.5 跨圈聚合：节点投影按最阻塞者重算（不硬编码 applied）
            recomputeNodeMaterialSync(sourceRunId, sourceNodeKey);

            return MaterialOutcome.CONTINUE;
        });
    }

    /**
     * T1 效果意图：节点成功且声明 mode=transition 时，写 plate_material_effect + DECLARE action。
     *
     * <p>第一版目标解析只处理显式 {@code target.plateId}（§3.3a 规则 1）；
     * 无 plateId 或解析不到 → 写 NEEDS_RECONCILIATION（plate_id NULL，规则 7）。
     */
    private long recordEffectIntent(long runId, String nodeKey, int loopIndex) {
        Map<String, Object> decl = mapper.selectDeclaration(tenantId, runId, nodeKey);
        if (decl == null || decl.get("declaration") == null) {
            return -1L;   // 无声明快照（mode=none/legacy 或非 transition 节点）
        }
        String declarationJson = String.valueOf(decl.get("declaration"));
        String declHash = String.valueOf(decl.get("declarationHash"));
        if (!"transition".equals(modeOf(declarationJson))) {
            return -1L;   // none / legacy_unspecified：不建 effect
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
        // R13-06：loopIndex 用 GraphRunner 注入的真实圈次（顺序路径为 0），
        // 这样循环每圈各有一条 Effect；此前写死 0 会被唯一键 DO NOTHING 静默丢弃。
        long effectId = insertEffect(runId, nodeKey, loopIndex, 0, 0, plateId,
                declarationJson, declHash, payloadHash, declarationJson,
                expectedStateVersion, expectedProcessState(declarationJson),
                expectedSealState(declarationJson));
        if (effectId > 0) {
            boolean pending = plateId != null;
            appendAction(effectId, "DECLARE", null,
                    pending ? "PENDING" : "NEEDS_RECONCILIATION",
                    "AUTO", null, null, "{}", null);
        }
        // T2 不在这里调：它必须在 T1 事务提交之后、独立事务里执行（见 onNodeResolution）
        return effectId;
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
    /** 包内可见（DbSelfTest 直接打 R6 IN_TRANSFER 门禁）。 */
    Long resolveTargetPlate(String declarationJson, long runId) {
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
            // R6：IN_TRANSFER = 已取板、在途未确认到位，位置不可信（与 UNKNOWN/LOST 同口径）。
            // 不能用它做目标寻址——板可能还停在源位或夹在机械臂上。
            if ("UNKNOWN".equals(posStatus) || "LOST".equals(posStatus)
                    || "IN_TRANSFER".equals(posStatus)) {
                return null;   // 位置不可信 / 在途未确认（规则 3）
            }
            if (ps.get("reservedByRun") != null) {
                return null;   // 在途/预约（规则 3b）
            }
            return ((Number) ps.get("id")).longValue();
        }
        return null;
    }

    /**
     * 目标态合并（纯逻辑、单写者）：声明里没写的字段保持现值。
     *
     * <p>{@code null} = 不改；写 {@code sealState="none"} 才是显式清膜。
     * {@link #applyEffect}（T2 设备确认）与 {@link #manualStateChange}（人工改状态）
     * 必须共用这一个函数：两条路径对「没写的字段」若给出不同解释，就会出现
     * 「封膜后只改 process_state，seal_state 被清成 NULL」这类静默数据损坏。
     *
     * <p>抽成纯函数是为了能在 {@code MaterialEffectServiceSelfTest}（不连库）里
     * 锁住该语义；SelfTest 无法覆盖 {@code applyEffect} 的事务内运行时行为。
     */
    public record TargetStates(String processState, String sealState) {
        public static TargetStates merge(String fromProcess, String fromSeal,
                                         String declaredProcess, String declaredSeal) {
            return new TargetStates(
                    declaredProcess != null ? declaredProcess : fromProcess,
                    declaredSeal != null ? declaredSeal : fromSeal);
        }
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
