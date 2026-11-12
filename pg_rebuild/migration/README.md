# pg_rebuild/migration · S1 迁移脚本（对既有库做增量升级）

> 与 `schema/` 的区别：`schema/` 是**建库/重建**形态（`90_all_in_one.sql` 一建到底）；
> 本目录是**对既有库做增量迁移**的脚本（S1 形态），幂等、带 catalog drift 校验。

## 文件

| 文件 | 作用 | 用法 |
|---|---|---|
| `20261112_material_identity.sql` | §4 DDL 的 S1 幂等迁移形态（5 新表 + 3 ALTER + 11 索引 + CHECK/UNIQUE；每个对象 DO/EXCEPTION + 定义校验，drift 即报错） | `psql -v ON_ERROR_STOP=1 -f 20261112_material_identity.sql`（**连跑两次，两次都 exit 0**） |
| `20261112_legacy_backfill.sql` | legacy 回填：旧 `run_node.status='completed'` 回填 `execution_resolution_status='completed'` + legacy 证据（幂等） | `psql -v ON_ERROR_STOP=1 -f 20261112_legacy_backfill.sql` |
| `inspection_q1_q18.sql` | §5.3 巡检 Q1–Q18（引用完整性兜底，必须全部 0 行） | `psql -v ON_ERROR_STOP=1 -f inspection_q1_q18.sql` |
| `seed_plate_process_state.sql` | `plate_process_state` 推荐集 seed（5 态，产品已确认 2026-11-12） | `psql -v ON_ERROR_STOP=1 -f seed_plate_process_state.sql` |

## 执行顺序

```bash
# 1. 迁移（连跑两次，验证幂等）
psql -h <host> -U <user> -d <db> -v ON_ERROR_STOP=1 -f 20261112_material_identity.sql
psql -h <host> -U <user> -d <db> -v ON_ERROR_STOP=1 -f 20261112_material_identity.sql

# 2. legacy 回填（幂等）
psql -h <host> -U <user> -d <db> -v ON_ERROR_STOP=1 -f 20261112_legacy_backfill.sql

# 3. 巡检（全部 0 行）
psql -h <host> -U <user> -d <db> -v ON_ERROR_STOP=1 -f inspection_q1_q18.sql

# 4. seed 字典（幂等）
psql -h <host> -U <user> -d <db> -v ON_ERROR_STOP=1 -f seed_plate_process_state.sql
```

## 绑定

`20261112_material_identity.sql` 绑定设计稿 §4 的 sha256：
`sha256(section4_ddl.sql) = a3dd715554e603c312be319f2fb0e2d9cdcc0d37b765224a29d23a4360b8a674`。
设计稿 §4 一变，本脚本的「期望定义」即失效（会按设计意图报 DRIFT），必须重新生成。

## 待办（未归档）

- 把 §4 DDL 合并进 `schema/90_all_in_one.sql`（新装库也要有物料表）—— 待定，先只提供增量迁移。
- D5 append-only 角色权限：延后到最后阶段（见 `docs/S1-待确认项-20261112.md`）。

## 执行记录

测试库 `192.168.124.2`（2026-11-12）：迁移连跑 2 次 exit=0、回填 467 行（幂等）、巡检 22/22 = 0 行。
详见 `docs/S1-迁移执行记录-测试库-20261112.md`。
