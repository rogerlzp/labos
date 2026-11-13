# pg_rebuild/migration · S1 迁移脚本（对既有库做增量升级）

> 与 `schema/` 的区别：`schema/` 是**建库/重建**形态（`90_all_in_one.sql` 一建到底）；
> 本目录是**对既有库做增量迁移**的脚本（S1 形态），幂等、带 catalog drift 校验。

## 文件

### 20261112 · 物料身份（S1）

| 文件 | 作用 | 用法 |
|---|---|---|
| `20261112_material_identity.sql` | §4 DDL 的 S1 幂等迁移形态（5 新表 + 3 ALTER + 11 索引 + CHECK/UNIQUE；每个对象 DO/EXCEPTION + 定义校验，drift 即报错） | `psql -v ON_ERROR_STOP=1 -f 20261112_material_identity.sql`（**连跑两次，两次都 exit 0**） |
| `20261112_legacy_backfill.sql` | legacy 回填：旧 `run_node.status='completed'` 回填 `execution_resolution_status='completed'` + legacy 证据（幂等） | `psql -v ON_ERROR_STOP=1 -f 20261112_legacy_backfill.sql` |
| `inspection_q1_q18.sql` | §5.3 巡检 Q1–Q18（引用完整性兜底，必须全部 0 行） | `psql -v ON_ERROR_STOP=1 -f inspection_q1_q18.sql` |
| `seed_plate_process_state.sql` | `plate_process_state` 推荐集 seed（5 态，产品已确认 2026-11-12） | `psql -v ON_ERROR_STOP=1 -f seed_plate_process_state.sql` |

### 20261113 · 系统重构 P0/P1/P2（分支 `系统重构1`）

| 文件 | 作用 | 用法 |
|---|---|---|
| `20261113_device_point.sql` | 点位初始化配置层与物料身份链路 | `psql -v ON_ERROR_STOP=1 -f 20261113_device_point.sql` |
| `20261113_device_point_attrs.sql` | 点位属性扩展（排/列/高度/可放板型） | `psql -v ON_ERROR_STOP=1 -f 20261113_device_point_attrs.sql` |
| `20261113_device_point_standalone.sql` | 培养箱/耗材堆栈网格外的「单独位置」+ 位置标识 | `psql -v ON_ERROR_STOP=1 -f 20261113_device_point_standalone.sql` |
| `20261113_device_positions_fix.sql` | 点位模型修正：机械臂无点位；存储格改 internal（机器人不可达） | `psql -v ON_ERROR_STOP=1 -f 20261113_device_positions_fix.sql` |
| `20261113_node_execution_phase.sql` | 节点执行阶段拆分（WAITING_RESOURCE / DISPATCHING，仅观测） | `psql -v ON_ERROR_STOP=1 -f 20261113_node_execution_phase.sql` |
| `20261113_device_pool.sql` | P0-1 设备池（device_pool + member，operation 同型号约束） | `psql -v ON_ERROR_STOP=1 -f 20261113_device_pool.sql` |
| `20261113_position_method.sql` | P0-2 点位×方法×作用域（experiment > workflow > global） | `psql -v ON_ERROR_STOP=1 -f 20261113_position_method.sql` |
| `20261113_node_binding.sql` | P0-3 流程节点真绑定（node_binding）+ workflow_node.binding_mode | `psql -v ON_ERROR_STOP=1 -f 20261113_node_binding.sql` |
| `20261113_role_designer.sql` | P0-6 角色重划：新增 designer（operator/designer/admin/viewer） | `psql -v ON_ERROR_STOP=1 -f 20261113_role_designer.sql` |
| `20261113_p1_execution.sql` | P1 执行层 DDL：实验模板/manifest/物料角色/搬运段/既有表改造 | `psql -v ON_ERROR_STOP=1 -f 20261113_p1_execution.sql` |
| `20261113_workstation.sql` | P2-1 工位层：`resource_kind` 加 'workstation' + ID-2011 工位种子（P1-5） | `psql -v ON_ERROR_STOP=1 -f 20261113_workstation.sql`（**连跑两次，验证幂等**） |

### 20261114 · 独立评审修正

| 文件 | 作用 | 用法 |
|---|---|---|
| `20261114_deck_in_reachable_fix.sql` | P0-1：deck_in（台面内工位）机器人不可达口径修正（internal/robot_reachable=false） | `psql -v ON_ERROR_STOP=1 -f 20261114_deck_in_reachable_fix.sql`（幂等） |

## 执行顺序

```bash
# 1. 物料身份（连跑两次，验证幂等）
psql -h <host> -U <user> -d <db> -v ON_ERROR_STOP=1 -f 20261112_material_identity.sql
psql -h <host> -U <user> -d <db> -v ON_ERROR_STOP=1 -f 20261112_material_identity.sql

# 2. legacy 回填（幂等）
psql -h <host> -U <user> -d <db> -v ON_ERROR_STOP=1 -f 20261112_legacy_backfill.sql

# 3. 系统重构 P0/P1/P2（按文件序）
for f in 20261113_device_point.sql 20261113_device_point_attrs.sql \
         20261113_device_point_standalone.sql 20261113_device_positions_fix.sql \
         20261113_node_execution_phase.sql 20261113_device_pool.sql \
         20261113_position_method.sql 20261113_node_binding.sql \
         20261113_role_designer.sql 20261113_p1_execution.sql \
         20261113_workstation.sql; do
  psql -h <host> -U <user> -d <db> -v ON_ERROR_STOP=1 -f "$f"
done

# 4. 独立评审修正（P0-1 deck_in 可达性）
psql -h <host> -U <user> -d <db> -v ON_ERROR_STOP=1 -f 20261114_deck_in_reachable_fix.sql

# 5. 巡检（全部 0 行）
psql -h <host> -U <user> -d <db> -v ON_ERROR_STOP=1 -f inspection_q1_q18.sql

# 6. seed 字典（幂等）
psql -h <host> -U <user> -d <db> -v ON_ERROR_STOP=1 -f seed_plate_process_state.sql
```

## 绑定

`20261112_material_identity.sql` 绑定设计稿 §4 的 sha256：
`sha256(section4_ddl.sql) = a3dd715554e603c312be319f2fb0e2d9cdcc0d37b765224a29d23a4360b8a674`。
设计稿 §4 一变，本脚本的「期望定义」即失效（会按设计意图报 DRIFT），必须重新生成。

## 待办（未归档）

- 把 20261113 的 6 个新脚本（device_pool/position_method/node_binding/p1_execution/role_designer/workstation）合并进 `schema/90_all_in_one.sql`（新装库也要有这些表）—— 见下「P1-7」。
- D5 append-only 角色权限：延后到最后阶段（见 `docs/S1-待确认项-20261112.md`）。

## 执行记录

测试库 `192.168.124.2`（2026-11-12）：迁移连跑 2 次 exit=0、回填 467 行（幂等）、巡检 22/22 = 0 行。
详见 `docs/S1-迁移执行记录-测试库-20261112.md`。
- 测试库 `192.168.124.2`（2026-11-14）：`20261114_deck_in_reachable_fix.sql` 修正 4 行 deck_in（WS1..4 → internal）。
