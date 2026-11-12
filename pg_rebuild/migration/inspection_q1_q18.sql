-- Q1 effect → run 同租户
SELECT e.id FROM plate_material_effect e
 WHERE NOT EXISTS (SELECT 1 FROM run r WHERE r.tenant_id = e.tenant_id AND r.id = e.source_run_id);
-- Q2 effect.plate_id → plate 同租户
SELECT e.id FROM plate_material_effect e
 WHERE e.plate_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM plate p WHERE p.tenant_id = e.tenant_id AND p.id = e.plate_id);
-- Q3 effect → 本 run 的 run_node（node_key + 圈次）
SELECT e.id FROM plate_material_effect e
 WHERE NOT EXISTS (SELECT 1 FROM run_node n
                    WHERE n.tenant_id = e.tenant_id AND n.run_id = e.source_run_id
                      AND n.node_key = e.source_node_key AND n.loop_index = e.source_loop_index);
-- Q4 声明快照 → run
SELECT d.id FROM run_material_effect_declaration d
 WHERE NOT EXISTS (SELECT 1 FROM run r WHERE r.tenant_id = d.tenant_id AND r.id = d.run_id);
-- Q5 action → effect 同租户
SELECT a.id FROM plate_material_effect_action a
 WHERE NOT EXISTS (SELECT 1 FROM plate_material_effect e
                    WHERE e.tenant_id = a.tenant_id AND e.id = a.effect_id);
-- Q6 事件 → Effect：必须同租户 + 同 run + 同 plate（v4.1 修正）
-- 原 Q6 用 INNER JOIN + tenant 条件，实测漏判三类：孤儿 effect_id（JOIN 丢行）、
-- effect.plate_id 为 NULL（NULL 比较被过滤）、跨租户（join 条件掩盖）。改 NOT EXISTS 全捕获：
SELECT v.id FROM plate_material_event v
 WHERE v.effect_id IS NOT NULL
   AND NOT EXISTS (
     SELECT 1 FROM plate_material_effect e
      WHERE e.tenant_id = v.tenant_id
        AND e.id = v.effect_id
        AND e.plate_id IS NOT DISTINCT FROM v.plate_id
        AND e.source_run_id IS NOT DISTINCT FROM v.source_run_id);
-- Q7 event.plate_id → plate 同租户
SELECT v.id FROM plate_material_event v
 WHERE NOT EXISTS (SELECT 1 FROM plate p WHERE p.tenant_id = v.tenant_id AND p.id = v.plate_id);
-- Q8/Q9 字典引用
SELECT v.id FROM plate_material_event v
 WHERE (v.from_process_state IS NOT NULL AND NOT EXISTS
          (SELECT 1 FROM plate_process_state s WHERE s.tenant_id = v.tenant_id AND s.code = v.from_process_state))
    OR (v.to_process_state IS NOT NULL AND NOT EXISTS
          (SELECT 1 FROM plate_process_state s WHERE s.tenant_id = v.tenant_id AND s.code = v.to_process_state));
SELECT p.id FROM plate p
 WHERE p.process_state IS NOT NULL AND NOT EXISTS
   (SELECT 1 FROM plate_process_state s WHERE s.tenant_id = p.tenant_id AND s.code = p.process_state);
-- Q8b 膜状态值域（DB CHECK 已拦，巡检兜底）
SELECT p.id FROM plate p
 WHERE p.seal_state IS NOT NULL AND p.seal_state NOT IN ('none','sealed');
-- Q10 事件 source_run → run（同租户）
SELECT v.id FROM plate_material_event v
 WHERE v.source_run_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM run r WHERE r.tenant_id=v.tenant_id AND r.id=v.source_run_id);
-- Q11 事件 source_node → run_node（同 run + 圈次）
SELECT v.id FROM plate_material_event v
 WHERE v.source_run_id IS NOT NULL AND v.source_node_key IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM run_node n
                    WHERE n.tenant_id=v.tenant_id AND n.run_id=v.source_run_id
                      AND n.node_key=v.source_node_key AND n.loop_index=v.source_loop_index);
-- Q12 事件版本链：不连续
SELECT v.plate_id, v.to_state_version FROM plate_material_event v
 WHERE v.to_state_version > 1
   AND NOT EXISTS (SELECT 1 FROM plate_material_event p
                    WHERE p.tenant_id=v.tenant_id AND p.plate_id=v.plate_id
                      AND p.to_state_version = v.to_state_version - 1);
-- Q12b plate.state_version 必须等于该板最大事件版本（无事件=0）
SELECT p.id, p.state_version FROM plate p
 WHERE p.state_version <> COALESCE(
   (SELECT max(v.to_state_version) FROM plate_material_event v
     WHERE v.tenant_id=p.tenant_id AND v.plate_id=p.id), 0);
-- Q13 投影一致性：plate 当前值必须等于最后一条事件的 to 值
SELECT p.id FROM plate p
  LEFT JOIN LATERAL (
    SELECT v.to_process_state, v.to_seal_state
      FROM plate_material_event v
     WHERE v.tenant_id=p.tenant_id AND v.plate_id=p.id
     ORDER BY v.to_state_version DESC LIMIT 1
  ) last ON true
 WHERE p.state_version > 0
   AND (p.process_state IS DISTINCT FROM last.to_process_state
        OR p.seal_state IS DISTINCT FROM last.to_seal_state);
-- Q14 事件 seal 值域（DB CHECK 已拦，巡检兜底）
SELECT v.id FROM plate_material_event v
 WHERE (v.from_seal_state IS NOT NULL AND v.from_seal_state NOT IN ('none','sealed'))
    OR (v.to_seal_state IS NOT NULL AND v.to_seal_state NOT IN ('none','sealed'));
-- Q15 声明/哈希完整性
SELECT e.id FROM plate_material_effect e
 WHERE NOT (e.declaration ? 'mode') OR length(e.declaration_hash) <> 64 OR length(e.payload_hash) <> 64;
SELECT d.id FROM run_material_effect_declaration d
 WHERE NOT (d.declaration ? 'mode') OR length(d.declaration_hash) <> 64;
-- Q15b 节点聚合投影可从 Effect 集合重建核对（不一致即告警）
-- 只查汇总行（level=0、loop_index=0）；聚合覆盖该节点**全部圈次**的 Effect（§3.5 跨圈聚合）
SELECT n.run_id, n.node_key, n.loop_index, n.material_sync_status
  FROM run_node n
 WHERE n.level = 0 AND n.loop_index = 0
   AND n.material_sync_status <> COALESCE((
        SELECT CASE
                 WHEN bool_or(e.status='NEEDS_RECONCILIATION') THEN 'needs_reconciliation'
                 WHEN bool_or(e.status='REJECTED') THEN 'rejected'
                 WHEN bool_or(e.status='FAILED') THEN 'failed'
                 WHEN bool_or(e.status='PENDING') THEN 'pending'
                 WHEN bool_or(e.status='CANCELLED') THEN 'cancelled'
                 ELSE 'applied' END
          FROM plate_material_effect e
         WHERE e.tenant_id=n.tenant_id AND e.source_run_id=n.run_id
           AND e.source_node_key=n.node_key), 'not_required')
   AND EXISTS (SELECT 1 FROM plate_material_effect x
                WHERE x.tenant_id=n.tenant_id AND x.source_run_id=n.run_id
                  AND x.source_node_key=n.node_key);
-- Q16 tenant 存在性
SELECT 'event' AS t, v.tenant_id FROM plate_material_event v
 WHERE NOT EXISTS (SELECT 1 FROM tenant t WHERE t.id=v.tenant_id)
UNION ALL
SELECT 'effect', e.tenant_id FROM plate_material_effect e
 WHERE NOT EXISTS (SELECT 1 FROM tenant t WHERE t.id=e.tenant_id)
UNION ALL
SELECT 'declaration', d.tenant_id FROM run_material_effect_declaration d
 WHERE NOT EXISTS (SELECT 1 FROM tenant t WHERE t.id=d.tenant_id);
-- Q17 effect 与声明快照一致（同 run/node/effect_index 的 declaration_hash 必须一致）
SELECT e.id FROM plate_material_effect e
 WHERE NOT EXISTS (SELECT 1 FROM run_material_effect_declaration d
                    WHERE d.tenant_id=e.tenant_id AND d.run_id=e.source_run_id
                      AND d.node_key=e.source_node_key AND d.effect_index=e.effect_index
                      AND d.declaration_hash=e.declaration_hash);
-- Q18 迁移后不应再出现的 legacy 组合（S1 迁移前会命中旧行，迁移后必须 0）
-- 只查设备 TASK 业务节点（level=0 汇总行）；排除 LOGIC/BUILTIN、level=1 步骤行、圈次行
SELECT n.run_id, n.node_key, n.loop_index FROM run_node n
 WHERE n.status='completed' AND n.execution_resolution_status='unresolved'
   AND n.level = 0 AND n.loop_index = 0
   AND COALESCE(n.input->>'capability', '') <> 'LOGIC';
