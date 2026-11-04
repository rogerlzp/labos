#!/usr/bin/env bash
# ============================================================================
# 长稳压测（M8）：连续跑 N 次，看内存有没有涨、数据有没有漏、错误率是否 0。
#
# 为什么不是"跑 2 小时"：CI 跑不完。这里跑的是**可复现的短压**（默认 20 次），
# 抓的是三类问题：
#   ① JVM 内存单调上涨（连接/事件没释放）
#   ② run 起来了但 run_node/run_command 没落（写库漏）
#   ③ 执行失败率非 0（回归）
#
# 用法：
#   export LABOS_DB_HOST=... LABOS_DB_NAME=... LABOS_DB_USER=... LABOS_DB_PASS=...
#   ./scripts/soak-test.sh            # 20 次
#   ./scripts/soak-test.sh --count 50 # 50 次
#   ./scripts/soak-test.sh --workflow 1254 --api http://127.0.0.1:18080
#
# 前置：API 在跑、模拟器在跑（否则每次都会失败，脚本会如实报失败率）。
# ============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 1

API=${API:-http://127.0.0.1:18080}
WORKFLOW=${WORKFLOW:-1254}
COUNT=20
SLEEP=3

while [[ $# -gt 0 ]]; do
  case "$1" in
    --count) COUNT="$2"; shift 2 ;;
    --workflow) WORKFLOW="$2"; shift 2 ;;
    --api) API="$2"; shift 2 ;;
    --sleep) SLEEP="$2"; shift 2 ;;
    *) echo "未知参数：$1"; exit 2 ;;
  esac
done

q() { # q <sql>
  PGPASSWORD="${LABOS_DB_PASS:-}" psql -h "${LABOS_DB_HOST:-127.0.0.1}" \
    -U "${LABOS_DB_USER:-postgres}" -d "${LABOS_DB_NAME:-postgres}" -tAc "$1" 2>/dev/null
}

API_PID=$(lsof -nP -iTCP:18080 -sTCP:LISTEN 2>/dev/null | tail -1 | awk '{print $2}')
if [[ -z "$API_PID" ]]; then
  echo "找不到 18080 上的 API 进程，先起服务"; exit 2
fi

TOKEN=$(curl -s -X POST "$API/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin).get('token',''))")
if [[ -z "$TOKEN" ]]; then
  echo "登录失败，检查 $API"; exit 2
fi

rss() { ps -o rss= -p "$API_PID" 2>/dev/null | tr -d ' '; }

# 先热身 3 次再取基线：JVM 从空闲到跑起来会涨一截（类加载/连接池/JIT），
# 那部分不是泄漏。不热身的话第一次测必然"涨 50%"，报的全是假警。
for ((w = 1; w <= 3; w++)); do
  curl -s -X POST "$API/api/workflows/$WORKFLOW/run?async=1" \
    -H "Authorization: Bearer $TOKEN" >/dev/null
  sleep "$SLEEP"
done

RSS0=$(rss)
RUN0=$(q "select count(*) from run")
CMD0=$(q "select count(*) from run_command")
echo "基线：RSS ${RSS0}KB / run $RUN0 / run_command $CMD0"

OK=0; BAD=0
for ((i = 1; i <= COUNT; i++)); do
  curl -s -X POST "$API/api/workflows/$WORKFLOW/run?async=1" \
    -H "Authorization: Bearer $TOKEN" >/dev/null
  sleep "$SLEEP"
  ST=$(q "select status from run order by id desc limit 1")
  if [[ "$ST" == "completed" ]]; then
    OK=$((OK + 1))
  else
    BAD=$((BAD + 1))
    echo "  第 $i 次：$ST"
  fi
  if (( i % 5 == 0 )); then
    echo "  已完成 $i 次，RSS 现在 $(rss)KB"
  fi
done

RSS1=$(rss)
RUN1=$(q "select count(*) from run")
CMD1=$(q "select count(*) from run_command")
GROW=$(( (RSS1 - RSS0) * 100 / (RSS0 == 0 ? 1 : RSS0) ))

echo
echo "──────────────────────────────────────────"
echo "执行：成功 $OK / 失败 $BAD"
echo "数据：run +$((RUN1 - RUN0))、run_command +$((CMD1 - CMD0))"
echo "内存：${RSS0}KB → ${RSS1}KB（${GROW}%）"
echo "──────────────────────────────────────────"

FAIL=0
[[ "$BAD" -gt 0 ]] && { echo "✗ 有失败执行"; FAIL=1; }
[[ "$GROW" -gt 20 ]] && { echo "✗ 内存增长超过 20%"; FAIL=1; }
[[ $((RUN1 - RUN0)) -lt "$COUNT" ]] && { echo "✗ run 少写了（数据漏）"; FAIL=1; }
[[ $((CMD1 - CMD0)) -lt "$OK" ]] && { echo "✗ run_command 少于成功次数（命令没落库）"; FAIL=1; }
[[ "$FAIL" -eq 0 ]] && echo "✓ 长稳通过（无失败、内存稳定、数据完整）"
exit "$FAIL"
