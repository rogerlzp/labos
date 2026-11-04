#!/bin/bash
# =============================================================================
# 多实验并行压测
#
# 测什么：
#   1. 同岛并发 —— 验证岛级互斥（应当排队，不并行）
#   2. 吞吐随并发的变化 —— 找出拐点
#   3. 错误率 —— 并发上去了会不会出错
#
# 用法：
#   ./scripts/load-test.sh                 # 默认压 LAB-DEMO-02
#   ./scripts/load-test.sh LAB-DEMO-02     # 指定岛
#   ./scripts/load-test.sh LAB-DEMO-02 1,2,4,8,16   # 自定义并发梯度
# =============================================================================
set -uo pipefail
cd "$(dirname "$0")/.."

ISLAND="${1:-LAB-DEMO-02}"
LEVELS="${2:-1,2,4,8,16}"
BASE="http://localhost:18080"
OUT="/tmp/loadtest.csv"

: "${LABOS_DB_HOST:?请设置 LABOS_DB_HOST}"
: "${LABOS_DB_NAME:?请设置 LABOS_DB_NAME}"
: "${LABOS_DB_USER:?请设置 LABOS_DB_USER}"
: "${LABOS_DB_PASS:?请设置 LABOS_DB_PASS}"

PSQL=$(command -v psql || true)
[[ -z "$PSQL" && -x /opt/homebrew/opt/libpq/bin/psql ]] && PSQL=/opt/homebrew/opt/libpq/bin/psql
q() { PGPASSWORD="$LABOS_DB_PASS" "$PSQL" -h "$LABOS_DB_HOST" -U "$LABOS_DB_USER" \
        -d "$LABOS_DB_NAME" -tAc "$1" 2>/dev/null; }

echo "========================================" | tee "$OUT"
echo " 多实验并行压测  $(date '+%H:%M:%S')" | tee -a "$OUT"
echo " 岛：$ISLAND  并发梯度：$LEVELS" | tee -a "$OUT"
echo "========================================" | tee -a "$OUT"

TOKEN=$(curl -s -X POST "$BASE/api/auth/login" \
        -d '{"username":"admin","password":"admin123"}' \
        | python3 -c "import sys,json;print(json.load(sys.stdin).get('token',''))" 2>/dev/null)
[ -n "$TOKEN" ] || { echo "登录失败"; exit 1; }

ISL_ID=$(q "SELECT id FROM resource_node WHERE code = '$ISLAND' AND kind='island';")
[ -n "$ISL_ID" ] || { echo "岛 $ISLAND 不存在"; exit 1; }

WF_ID=$(q "SELECT id FROM workflow WHERE name LIKE '%nch%' ORDER BY id LIMIT 1;")
[ -z "$WF_ID" ] && WF_ID=$(q "SELECT id FROM workflow WHERE graph IS NOT NULL ORDER BY id LIMIT 1;")
[ -n "$WF_ID" ] || { echo "没有可用流程"; exit 1; }

echo "" | tee -a "$OUT"
printf "%-6s %-8s %-8s %-8s %-10s %-10s %-8s\n" \
      "并发" "成功" "失败" "耗时(s)" "吞吐(个/s)" "单均(ms)" "峰值并行" | tee -a "$OUT"
echo "------ -------- -------- -------- ---------- ---------- --------" | tee -a "$OUT"

for N in ${LEVELS//,/ }; do
    # 记录起点（用 run id 水位）
    BASE_ID=$(q "SELECT COALESCE(max(id),0) FROM run WHERE tenant_id=1;")

    T0=$(python3 -c "import time;print(time.time())")

    # 并发发起 N 个（后台）
    for i in $(seq 1 "$N"); do
        curl -s -m 120 -X POST -H "Authorization: Bearer $TOKEN" \
             "$BASE/api/workflows/$WF_ID/run?islandId=$ISL_ID" \
             -o "/tmp/lt-$N-$i.json" &
    done
    wait

    T1=$(python3 -c "import time;print(time.time())")
    ELAPSED=$(python3 -c "print('%.2f' % ($T1 - $T0))")

    # 统计这批 run
    STATS=$(q "
        SELECT count(*) || '|' ||
               count(*) FILTER (WHERE status = 'completed') || '|' ||
               count(*) FILTER (WHERE status NOT IN ('completed','running','pending')) || '|' ||
               COALESCE(avg(EXTRACT(EPOCH FROM (actual_end - actual_start)) * 1000), 0)::int
        FROM run WHERE tenant_id = 1 AND id > $BASE_ID;")

    TOTAL="${STATS%%|*}"; REST="${STATS#*|}"
    OK="${REST%%|*}"; REST="${REST#*|}"
    FAIL="${REST%%|*}"; AVG_MS="${REST##*|}"

    THROUGHPUT=$(python3 -c "
e=$ELAPSED
o=${OK:-0}
print('%.2f' % (o/e if e > 0 else 0))")

    # 峰值并行：这批 run 里同时处于 running 的最大数量
    PEAK=$(q "
        SELECT COALESCE(max(c), 0) FROM (
          SELECT id, (SELECT count(*) FROM run r2
                      WHERE r2.tenant_id = 1 AND r2.id > $BASE_ID
                        AND r2.actual_start <= r.actual_start
                        AND (r2.actual_end IS NULL OR r2.actual_end >= r.actual_start)) AS c
          FROM run r WHERE r.tenant_id = 1 AND r.id > $BASE_ID
        ) t;")

    printf "%-6s %-8s %-8s %-8s %-10s %-10s %-8s\n" \
           "$N" "${OK:-0}" "${FAIL:-0}" "$ELAPSED" "$THROUGHPUT" "${AVG_MS:-0}" "${PEAK:-?}" | tee -a "$OUT"

    sleep 3
done

echo "" | tee -a "$OUT"
echo "========================================" | tee -a "$OUT"
echo " 明细：$OUT" | tee -a "$OUT"
echo " 说明：" | tee -a "$OUT"
echo "   峰值并行 > 1 说明岛锁没生效（同岛应当串行）" | tee -a "$OUT"
echo "   吞吐随并发先升后平 = 到达瓶颈" | tee -a "$OUT"
echo "========================================" | tee -a "$OUT"
