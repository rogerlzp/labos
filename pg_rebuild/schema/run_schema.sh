#!/bin/bash
# ============================================================
# 按顺序执行 schema/*.sql
#   ./run_schema.sh --check                只连接体检
#   ./run_schema.sh --dry-run              只打印将执行的语句
#   ./run_schema.sh                        执行全部
# 环境变量：HOST PORT DB USER PASS
# ============================================================
set -euo pipefail

HOST=${HOST:-127.0.0.1}
PORT=${PORT:-5432}
DB=${DB:-labdb}
USER=${USER_DB:-labos}
PASS=${PASS_DB:-}   # 必须通过环境变量传入，不设默认值

PSQL=$(command -v psql || true)
[[ -z "$PSQL" && -x /opt/homebrew/opt/libpq/bin/psql ]] && { PSQL=/opt/homebrew/opt/libpq/bin/psql; export PATH="/opt/homebrew/opt/libpq/bin:$PATH"; }
[[ -n "$PSQL" ]] || { echo "找不到 psql"; exit 1; }

[[ -z "$PASS" ]] && { echo "请用 PASS_DB 环境变量提供数据库密码"; exit 1; }

psql_db() { PGPASSWORD="$PASS" "$PSQL" -h "$HOST" -p "$PORT" -U "$USER" -d "$DB" -v ON_ERROR_STOP=1 "$@"; }

cd "$(dirname "$0")"
# F5（评审）：FILES 原只列 10/20/30/40，漏掉 92..99x（含 99x_p2_rebuild）——
# 与 merge.sh 不同源会导致 run_schema.sh 建出的库缺分片表。
# 改为自动发现全部 DDL 分片（排除 95_seed_data 数据 + 99_verify 校验），与 merge.sh 同源。
FILES=( $(ls [0-9]*.sql | grep -vE '^(95_seed_data|99_verify)\.sql$' | sort) )

case "${1:-}" in
  --check)
    PGPASSWORD="$PASS" "$PSQL" -h "$HOST" -p "$PORT" -U "$USER" -d "$DB" -tAc \
      "select current_user||'@'||current_database()||' pg'||current_setting('server_version')" 2>&1 || {
      echo "连接失败：数据库 $DB 可能尚未创建。";
      echo "请先执行： CREATE DATABASE $DB OWNER $USER;";
      exit 1; }
    echo "--- 扩展可用性 ---"
    psql_db -tAc "select name, default_version, installed_version from pg_available_extensions where name in ('btree_gist','pgcrypto')"
    echo "--- 现有对象 ---"
    psql_db -c "select * from v_schema_health" 2>/dev/null || echo "(schema 尚未初始化)"
    exit 0;;
  --dry-run)
    for f in "${FILES[@]}"; do echo "==> $f ($(wc -l < "$f") 行)"; done
    exit 0;;
esac

echo "目标: $USER@$HOST:$PORT/$DB"
for f in "${FILES[@]}"; do
  echo "==> 执行 $f"
  psql_db -q -f "$f" || { echo "!! $f 失败"; exit 1; }
done

echo
echo "== 完成，对象统计 =="
psql_db -c "select * from v_schema_health"
psql_db -c "
SELECT CASE
    WHEN c.relname LIKE 'run%'      THEN '执行内核'
    WHEN c.relname LIKE 'workflow%' OR c.relname LIKE 'protocol%' THEN '工艺编排'
    WHEN c.relname IN ('resource_node','resource_capability','resource_booking','capability') THEN '资源与能力'
    WHEN c.relname LIKE 'driver%'   THEN '驱动'
    WHEN c.relname LIKE 'schedule%' THEN '调度'
    WHEN c.relname LIKE 'error%' OR c.relname LIKE 'recovery%' THEN '错误恢复'
    WHEN c.relname IN ('sample','sample_aliquot','plate','plate_well','sample_order','sample_order_item') THEN '样本'
    WHEN c.relname LIKE 'consumable%' OR c.relname LIKE 'reagent%' THEN '耗材试剂'
    WHEN c.relname IN ('project','experiment','experiment_sample') THEN '项目实验'
    WHEN c.relname LIKE 'device%'   THEN '设备'
    WHEN c.relname IN ('tenant','app_user','role','permission','user_role','role_permission','dict_item','sys_setting') THEN '平台'
    ELSE '运维/其它' END AS domain, count(*) AS tables
FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE n.nspname='public' AND c.relkind IN ('r','p')
GROUP BY 1 ORDER BY 2 DESC;"
