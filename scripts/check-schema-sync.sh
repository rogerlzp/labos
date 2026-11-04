#!/usr/bin/env bash
# ============================================================================
# 建库产物一致性检查（BUG-20261103-01 的回归断言）
#
# 背景：deploy.sh 部署的是合并产物 `pg_rebuild/schema/90_all_in_one.sql`，
# 但改的是分片（20_kernel / 96_schema_fix…）。**忘了重跑 merge.sh** 时，
# 分片是对的、被部署的那份是旧的 —— 新环境装出来的库就缺列/缺约束，
# 而本地库一切正常，最难发现。
#
# 这个脚本做两件事：
#   ① 重跑 merge.sh，产物必须与仓库里提交的一致（否则说明产物陈旧）
#   ② 产物里必须有 run_node.loop_index / 4 列唯一键 / parent 外键
#
# 用法：./scripts/check-schema-sync.sh
# ============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 1

PRODUCT="pg_rebuild/schema/90_all_in_one.sql"
FAIL=0
ok()   { printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad()  { printf '  \033[31m✗\033[0m %s\n' "$1"; FAIL=$((FAIL + 1)); }

echo "══ 建库产物一致性 ══"

# ---- ① 产物 vs 分片 ----
if [[ ! -f "$PRODUCT" ]]; then
  bad "$PRODUCT 不存在"
else
  cp "$PRODUCT" /tmp/_schema_product_backup.sql
  bash pg_rebuild/schema/merge.sh >/tmp/_merge.log 2>&1
  if diff -q /tmp/_schema_product_backup.sql "$PRODUCT" >/dev/null 2>&1; then
    ok "产物与分片同源（重跑 merge.sh 无变化）"
  else
    bad "产物陈旧：分片改过但没重跑 merge.sh"
    diff /tmp/_schema_product_backup.sql "$PRODUCT" | head -20
    # 还原成仓库里那份：CI 不该改工作区；本地跑的人自己重跑 merge.sh 并提交
    cp /tmp/_schema_product_backup.sql "$PRODUCT"
    echo "     修复：bash pg_rebuild/schema/merge.sh && git add $PRODUCT"
  fi
fi

# ---- ② 关键结构必须在产物里 ----
need() { # need <说明> <grep 模式>
  if grep -qE "$2" "$PRODUCT"; then
    ok "$1"
  else
    bad "$1（产物里没有）"
  fi
}

need "run_node.loop_index 存在"            '^\s*loop_index\s+smallint'
need "uk_run_node 含 loop_index（4 列）"    'CONSTRAINT uk_run_node UNIQUE \(run_id, node_key, attempt, loop_index\)'
need "parent_node_id 外键（级联删除）"      'run_node_parent_node_id_fkey[\s\S]*?REFERENCES run_node\(id\) ON DELETE CASCADE|ADD CONSTRAINT run_node_parent_node_id_fkey'
need "experiment_process_edge 存在（M9.5 偏序）" 'CREATE TABLE IF NOT EXISTS experiment_process_edge'
need "experiment_process_edge 随 process 级联删除" 'REFERENCES experiment_process\(id\) ON DELETE CASCADE'
need "runtime_hold 存在（M9.5 作用域扣留）" 'CREATE TABLE IF NOT EXISTS runtime_hold'
need "runtime_hold 同 scope 只能一条生效" 'uk_runtime_hold_active'
need "experiment_run 停止请求列存在" 'ALTER TABLE experiment_run ADD COLUMN IF NOT EXISTS stop_requested'
# ---- M9.5 S3 分批执行 ----
need "experiment.batch_split 存在（S3 分批开关）" 'ALTER TABLE experiment ADD COLUMN IF NOT EXISTS batch_split'
need "experiment_batch 存在（S3 逻辑批次）" 'CREATE TABLE IF NOT EXISTS experiment_batch \('
need "experiment_batch_run 存在（S3 每次尝试）" 'CREATE TABLE IF NOT EXISTS experiment_batch_run \('
need "experiment_batch 按实验+批号唯一" 'CONSTRAINT uk_experiment_batch UNIQUE \(tenant_id, experiment_id, batch_no\)'
need "experiment_batch_run 按批+尝试号唯一" 'CONSTRAINT uk_experiment_batch_run UNIQUE \(batch_id, attempt_no\)'

echo
if [[ $FAIL -eq 0 ]]; then
  printf '  \033[32m产物一致\033[0m\n'
  exit 0
fi
printf '  \033[31m产物不一致：%d 项（重新跑 ./pg_rebuild/schema/merge.sh 并提交产物）\033[0m\n' "$FAIL"
exit 1
