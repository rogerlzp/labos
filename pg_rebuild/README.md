# 数据库 Schema

PostgreSQL 建表脚本。**表的用途与字段含义见 [`docs/数据库设计说明.md`](../docs/数据库设计说明.md)**。

---

## 目录结构

```
pg_rebuild/
├── schema/
│   ├── 10_base_platform.sql   # 基础类型、资源树（resource_node）、能力字典
│   ├── 20_kernel.sql          # 流程、执行痕迹（run/run_node/run_command）
│   ├── 30_business.sql        # 实验、板位（experiment/position_state/plate）
│   ├── 40_ops.sql             # 驱动、日志分区、告警
│   ├── 90_all_in_one.sql      # 上面全部合并（纯 DDL，推荐直接用这个）
│   ├── 95_seed_data.sql       # 种子数据（可选，见下）
│   ├── 99_verify.sql          # 建完自检
│   ├── merge.sh               # 重新生成 90_all_in_one.sql
│   └── run_schema.sh          # 一键建库
└── deploy.sh                  # 部署脚本
```

---

## 建库

```bash
cd pg_rebuild

# 方式一：一键（推荐）
./schema/run_schema.sh

# 方式二：手动
psql -h <host> -U <user> -d <db> -f schema/90_all_in_one.sql
psql -h <host> -U <user> -d <db> -f schema/99_verify.sql

# 可选：初始数据
psql -h <host> -U <user> -d <db> -f schema/95_seed_data.sql
```

### 建表 vs 初始数据

**`90_all_in_one.sql` 是纯 DDL，不含任何 INSERT**——建出来就是一堆空表。

初始数据在 `95_seed_data.sql`，**可选**，按需执行：

| 段落 | 内容 | 是否必需 |
|---|---|---|
| tenant | 1 条默认租户 | **必需**（所有表都有 tenant_id 外键） |
| capability / error_code / recovery_policy | 能力、错误码、恢复策略字典 | 建议（缺了节点找不到设备能力） |
| app_user / user_role | 3 个初始账号 | 可选（已上线环境不要重复执行） |

全部语句都是 `ON CONFLICT DO NOTHING`，**不会删除或覆盖已有数据**，可安全重复执行。

**参数**通过环境变量传：

| 变量 | 默认 | 说明 |
|---|---|---|
| `HOST` | 127.0.0.1 | 数据库地址 |
| `PORT` | 5432 | |
| `DB` | labos | 库名 |
| `APP_USER` | labos | 应用账号 |

---

## 设计要点

| 要点 | 说明 |
|---|---|
| **分区表** | `device_log`、`run_event` 按月分区，父表查询会自动路由 |
| **软删** | 有 `deleted_at` 列的表，查询要加 `deleted_at IS NULL` |
| **多租户** | 每表都有 `tenant_id`，查询必须带 |
| **两套 ID** | `resource_node.id`（业务用）与 `device_instance.id`（调驱动用），见设计说明第二节 |
| **枚举分离** | `run.status` 是 `run_status`，`run_node/run_command.status` 是 `node_status` |

---

## 校验

```bash
psql -h <host> -U <user> -d <db> -f schema/99_verify.sql
```

检查项：核心表是否都存在、枚举值是否齐全、分区表是否建好、初始账号是否可用。

---

## 修改 schema 的方式

1. 改 `schema/` 下对应分片（不要直接改 `90_all_in_one.sql`，它是生成物）
2. 跑 `./schema/merge.sh` 重新生成
3. 在测试库上执行 `99_verify.sql` 确认
4. 提交时**分片和合并文件一起提交**

**已上线的库不要直接改 schema**，走迁移脚本（每次一个 `.sql`，按序号命名）。
