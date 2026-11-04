#!/bin/bash
# 合并分模块 SQL 为单文件 90_all_in_one.sql
set -euo pipefail
cd "$(dirname "$0")"

cat > /tmp/_schema_header.sql <<'HDR'
-- =============================================================================
-- LabOS · 新一代 PostgreSQL Schema（全量单文件，自动生成请勿手改）
-- 目标版本: PostgreSQL 17（兼容 15+）
--
-- 执行:
--   psql -h <HOST> -U <USER> -d labos -v ON_ERROR_STOP=1 -f 90_all_in_one.sql
--
-- 结构: 76 张表 + 5 个视图（纯 DDL，不含任何数据）
--   10 基础与平台  : 租户/用户/角色/权限/字典/配置          (8 表)
--   20 内核层      : 资源与能力/资源日历/驱动注册/工艺编排/
--                    执行内核/事件流/调度/错误恢复          (23 表)
--   30 业务层      : 样本/板/孔位/订单/耗材试剂批次/
--                    项目实验/设备/文件                    (18 表)
--   40 运维层      : 通知/审计/兼容视图/健康自检            (4 表 + 3 视图)
--   92/93/94/96/97/98 : 增量分片：实验室档案 / M1 并行基础设施 /
--                    M4 暂停补列 / schema 补齐 / M9.5 实验流程偏序 /
--                    M9.5 作用域扣留 / M9.5 分批执行 (26 表 + 2 视图)
--
-- 幂等: 全部 DDL 使用 IF NOT EXISTS，可重复执行
-- 数据: **本文件不含任何 INSERT**。初始数据在 95_seed_data.sql，按需执行。
-- 依赖: plpgsql（内置）；btree_gist 可选（缺失时资源冲突约束降级并告警）
--
-- 已验证: PostgreSQL 17.10 (Ubuntu pgdg) 2026-10-29
--         81 表对象 / 2 分区父表 / 3 视图 / 205 索引 / 9 枚举
--         资源重叠约束生效（重叠预约被数据库拒绝）
-- =============================================================================

HDR

# ★ 2026-11-02 修复 P0-17：原来只拼 10/20/30/40，漏了 93(M1)/94(M4)，
#   导致 90_all_in_one.sql 建出的库缺 M1/M4 的表和列（pause_kind、release_exempt、
#   plate_location_audit 等），与分片不同源。现在按序号拼全部 DDL 分片。
#   注意：不含 95_seed_data（那是数据不是结构）、99_verify（校验脚本）。
cat /tmp/_schema_header.sql \
    10_base_platform.sql 20_kernel.sql 30_business.sql 40_ops.sql \
    92_lab_profile.sql \
    93_m1_parallel.sql 94_m4_pause.sql \
    96_schema_fix.sql \
    97_m95_process_edge.sql \
    98_m95_run_hold.sql \
    > 90_all_in_one.sql
rm -f /tmp/_schema_header.sql

# ★ BUG-20261103-08：分片里同时有手写 DDL 和 pg_dump 风格尾部，两套定义同一批表，
#   全新库执行会撞 "already exists"（deploy.sh 用 ON_ERROR_STOP=1，直接中断）。
#   这里把重复声明型语句（ADD GENERATED / ADD CONSTRAINT）包成可跳过的 DO 块。
python3 guard_legacy_tail.py   # 已在 schema/ 目录下（脚本开头 cd 过）

echo "已生成 90_all_in_one.sql: $(wc -l < 90_all_in_one.sql) 行, $(du -h 90_all_in_one.sql | cut -f1)"
