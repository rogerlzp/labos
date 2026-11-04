#!/bin/bash
# ============================================================
# LabOS PostgreSQL 建库部署脚本
#
# 前置：需要一个有 CREATEDB 权限的账号（服务器 127.0.0.1 上的
#       trading / cbquant 均无 CREATEDB，必须用 postgres 超级用户）
#
# 用法:
#   ./deploy.sh --su-user postgres --su-pass '密码'           # 完整版 185 表
#   ./deploy.sh --su-user postgres --su-pass '密码' --clean  # 精简版 172 表
#   ./deploy.sh --su-user postgres --su-pass '密码' --db labos_uat
#   ./deploy.sh --check                                      # 只做连接体检，不建库
# ============================================================
set -euo pipefail

HOST=${HOST:-127.0.0.1}
PORT=${PORT:-5432}
DB=${DB:-labdb}
SU_USER=${SU_USER:-postgres}
SU_PASS=${SU_PASS:-}
APP_USER=${APP_USER:-labos}
APP_PASS=${APP_PASS:-}   # 必须通过环境变量传入
# P0-18 修复（评审 20261102-03）：原来指向 02_labos_pg.sql，
# 那个文件在仓库里**不存在**，脚本一跑就报"找不到 SQL 文件"。
# 现在指向 schema/ 下的合并产物（由 schema/merge.sh 生成，含全部 DDL 分片）。
SCHEMA_DIR="$(cd "$(dirname "$0")" && pwd)/schema"
SQL_FILE="$SCHEMA_DIR/90_all_in_one.sql"
SEED_FILE="$SCHEMA_DIR/95_seed_data.sql"
CLEAN=0
SUDO_USER_SQL=""

usage() {
  sed -n '3,14p' "$0" | sed 's/^# \{0,1\}//'
  exit 0
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --host) HOST="$2"; shift 2;;
    --port) PORT="$2"; shift 2;;
    --db) DB="$2"; shift 2;;
    --su-user) SU_USER="$2"; shift 2;;
    --su-pass) SU_PASS="$2"; shift 2;;
    --app-user) APP_USER="$2"; shift 2;;
    --app-pass) APP_PASS="$2"; shift 2;;
    # --clean 语义：先删库重建（不是换一个 SQL 文件）
    --clean) CLEAN=1; shift;;
    --check) CHECK_ONLY=1; shift;;
    -h|--help) usage;;
    *) echo "未知参数: $1"; usage;;
  esac
done

# 定位 psql
PSQL=$(command -v psql || true)
if [[ -z "$PSQL" && -x /opt/homebrew/opt/libpq/bin/psql ]]; then
  PSQL=/opt/homebrew/opt/libpq/bin/psql
  export PATH="/opt/homebrew/opt/libpq/bin:$PATH"
fi
if [[ -z "$PSQL" && -x /usr/bin/psql ]]; then PSQL=/usr/bin/psql; fi
[[ -n "$PSQL" ]] || { echo "找不到 psql"; exit 1; }

cd "$(dirname "$0")"

# 注意：不能用 arr=(PGPASSWORD=x) 再 "${arr[@]}" psql —— bash 不会把数组展开的
# VAR=value 识别为命令前缀的环境变量赋值，会报 "command not found"
psql_su() { PGPASSWORD="$SU_PASS" "$PSQL" "$@"; }

echo "psql    : $PSQL"
echo "目标    : $HOST:$PORT/$DB"
echo "脚本    : $SQL_FILE"

# ---------- 体检 ----------
echo
echo "== 连接体检 =="
if psql_su -h "$HOST" -p "$PORT" -U "$SU_USER" -d postgres -tAc \
    "select 'PG '||current_setting('server_version')||' / user='||current_user||' / createdb='||(select rolcreatedb from pg_roles where rolname=current_user)" 2>&1; then
  echo "连接 OK"
else
  echo "连接失败：请确认 --su-user/--su-pass 正确，或让 DBA 执行："
  echo "  ALTER ROLE $SU_USER CREATEDB;"
  exit 1
fi

if [[ "${CHECK_ONLY:-0}" == "1" ]]; then
  psql_su -h "$HOST" -p "$PORT" -U "$SU_USER" -d postgres -c \
    "select datname, pg_get_userbyid(datdba) as owner, pg_size_pretty(pg_database_size(datname)) as size from pg_database order by 1;"
  exit 0
fi

# ---------- 建库 ----------
echo
echo "== 创建数据库 $DB =="
psql_su -h "$HOST" -p "$PORT" -U "$SU_USER" -d postgres -v ON_ERROR_STOP=1 <<EOS
SELECT 'CREATE DATABASE $DB'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = '$DB')\gexec
EOS

psql_su -h "$HOST" -p "$PORT" -U "$SU_USER" -d "$DB" -v ON_ERROR_STOP=1 <<EOS
CREATE ROLE $APP_USER LOGIN PASSWORD '$APP_PASS';
GRANT CONNECT, CREATE ON DATABASE $DB TO $APP_USER;
CREATE SCHEMA IF NOT EXISTS public AUTHORIZATION postgres;
GRANT USAGE ON SCHEMA public TO $APP_USER;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO $APP_USER;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO $APP_USER;
EOS

# ---------- 导入 ----------
echo
echo "== 导入 $SQL_FILE =="
psql_su -h "$HOST" -p "$PORT" -U "$SU_USER" -d "$DB" \
   -v ON_ERROR_STOP=1 -f "$SQL_FILE" 2>&1 | tail -20

# ---------- 授权 ----------
psql_su -h "$HOST" -p "$PORT" -U "$SU_USER" -d "$DB" -v ON_ERROR_STOP=1 <<EOS
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO $APP_USER;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO $APP_USER;
EOS

# ---------- 验证 ----------
echo
echo "== 验证 =="
psql_su -h "$HOST" -p "$PORT" -U "$SU_USER" -d "$DB" <<EOS
\echo '--- 对象计数 ---'
SELECT
  (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
    WHERE n.nspname='public' AND c.relkind='r') AS tables,
  (SELECT count(*) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid
    JOIN pg_namespace n ON n.oid=c.relnamespace
    WHERE n.nspname='public' AND c.relkind='r' AND a.attnum>0 AND NOT a.attisdropped) AS columns,
  (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
    WHERE n.nspname='public' AND c.relkind='i') AS indexes,
  (SELECT count(*) FROM pg_constraint WHERE contype='f') AS foreign_keys,
  (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
    WHERE n.nspname='public' AND c.relkind='S') AS sequences;
\echo '--- 业务域分布 ---'
SELECT CASE
    WHEN relname LIKE 'wf_%'      THEN '工作流'
    WHEN relname LIKE 'experiment%' OR relname LIKE 'culture%' THEN '实验'
    WHEN relname LIKE 'equipment%'  OR relname LIKE 'incubat%'  THEN '设备'
    WHEN relname LIKE 'sample%' OR relname LIKE 'consumable%' OR relname LIKE 'reagent%' THEN '样本耗材'
    WHEN relname LIKE 'protocol%' OR relname LIKE 'script%'     THEN '协议脚本'
    WHEN relname LIKE 'sys_%' OR relname LIKE 'user%' OR relname LIKE 'role%' THEN '权限'
    WHEN relname LIKE 'error%'     THEN '错误'
    WHEN relname LIKE 'agv%' OR relname LIKE 'transfer%' THEN '转运'
    ELSE '其它' END AS domain, count(*) AS tables
FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE n.nspname='public' AND c.relkind='r'
GROUP BY 1 ORDER BY 2 DESC;
EOS

echo
echo "完成。连接串: postgresql://$APP_USER:$APP_PASS@$HOST:$PORT/$DB"
