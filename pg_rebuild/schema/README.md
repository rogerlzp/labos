# 新一代 Schema：建库与执行

## 一、建库信息（建议值，可自行改）

| 项 | 值 |
|---|---|
| 主机 | `192.0.2.1:5432` |
| 数据库 | `labos` |
| 应用用户 | `XXX` |
| 应用密码 | `CHANGE_ME` |
| 编码 | UTF8 |
| 版本 | PostgreSQL 17（兼容 15+） |

## 二、建库（用 postgres 或有 CREATEDB 权限的账号执行一次）

```sql
-- 1) 建用户
CREATE ROLE labos LOGIN PASSWORD 'CHANGE_ME';

-- 2) 建库（template0 + UTF8，避免继承 template1 的编码/排序）
CREATE DATABASE labos
  OWNER labos
  ENCODING 'UTF8'
  LC_COLLATE 'C'
  LC_CTYPE 'C'
  TEMPLATE template0;

-- 3) 授权
GRANT ALL PRIVILEGES ON DATABASE labos TO labos;
```

> 若想复用现有账号，把 `labos` 换成 `trading` 即可（`CREATE DATABASE labos OWNER trading;`）。

## 三、执行建表

```bash
# 全量建表（幂等，可重复执行）
psql -h 192.0.2.1 -U XXX -d labdb -v ON_ERROR_STOP=1 -f 90_all_in_one.sql

# 自检（对象计数 + 约束检查 + 资源冲突功能验证）
psql -h 192.0.2.1 -U XXX -d labdb -f 99_verify.sql
```

分模块执行（可选，顺序不能变）：

```bash
psql ... -f 10_base_platform.sql
psql ... -f 20_kernel.sql
psql ... -f 30_business.sql
psql ... -f 40_ops.sql
```

## 四、连接串

```
postgresql://labos:CHANGE_ME@192.0.2.1:5432/labdb
```

Java (Spring Boot) 示例：
```yaml
spring:
  datasource:
    url: jdbc:postgresql://192.0.2.1:5432/labdb
    username: labos
    password: CHANGE_ME
    driver-class-name: org.postgresql.Driver
```

## 五、结构总览（53 张表 + 3 个视图）

| 层 | 文件 | 表数 | 内容 |
|---|---|---|---|
| 基础与平台 | `10_base_platform.sql` | 8 | `tenant` `app_user` `app_role` `permission` `user_role` `role_permission` `dict_item` `sys_setting` |
| 内核 | `20_kernel.sql` | 23 | **资源与能力(4)**：`capability` `resource_node` `resource_capability` `resource_booking`<br>**驱动(3)**：`driver` `driver_capability` `driver_health`<br>**工艺编排(5)**：`protocol` `protocol_step` `workflow` `workflow_node` `workflow_edge`<br>**执行(6)**：`run` `run_node` `run_command` `run_event`(分区) `run_variable` `run_artifact`<br>**调度(2)**：`schedule_plan` `schedule_item`<br>**恢复(3)**：`error_code` `recovery_policy` `error_occurrence` |
| 业务 | `30_business.sql` | 18 | 样本 `sample` `sample_aliquot` `plate` `plate_well` `sample_order` `sample_order_item`；耗材试剂 `consumable` `consumable_lot` `reagent` `reagent_lot`；项目实验 `project` `experiment` `experiment_sample`；设备 `device` `device_method` `device_instance` `device_log`(分区)；文件 `file_blob` |
| 运维 | `40_ops.sql` | 4 + 3 视图 | `notify_channel` `notification` `notify_log` `audit_log`；视图 `v_legacy_wf_process_instance` `v_legacy_equipment_instance` `v_schema_health` |

## 六、执行状态：✅ 已在真实库验证通过

**2026-10-29 已在 `192.0.2.1:5432/labdb`（PostgreSQL 17.10 Ubuntu pgdg）实际执行成功**：

| 检查项 | 结果 |
|---|---|
| 实体表 | 51 |
| 分区父表 | 2（`run_event`、`device_log`，各 15 个分区） |
| 视图 | 3 |
| 索引 | 205 |
| 枚举类型 | 9 |
| 外键 | 107 |
| `updated_at` 触发器 | 27 |
| `ex_booking_overlap` 资源重叠约束 | **exists**（btree_gist 生效） |
| **资源冲突功能验证** | **首次预约=t，重叠预约被拒绝=t** ✅ |
| 幂等重跑 | 0 ERROR / 0 WARNING |

已修复的两个真实问题（首次执行时暴露）：

1. `device_log` 分区表定义了两次主键（列级 `PRIMARY KEY` + 表级 `PRIMARY KEY (logged_at, id)`）→ 去掉列级
2. `EXCLUDE` 约束重复执行时误报 warning → 加 `IF NOT EXISTS` 判断

> 若你的目标库是全新实例，直接执行 `90_all_in_one.sql` 即可；
> 若该库已建过，重跑也是安全的（幂等）。

## 七、验证方法

```bash
psql -h 192.0.2.1 -U XXX -d labdb -f 99_verify.sql
```

期望输出：

- 普通表 **81**（51 实体 + 30 分区子表）、分区表 **2**、视图 **3**、枚举 **9**
- `ex_booking_overlap` = `exists`
- 最后一段：`首次预约成功=t  重叠预约被拒绝=t`
