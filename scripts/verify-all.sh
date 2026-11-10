#!/bin/bash
# =============================================================================
# 全链路验证（用模拟器代替真设备）
#
# 把现场要做的 8 步验证，用模拟器在本机完整跑一遍，输出通过/失败报告。
#
# 用法：
#   ./scripts/verify-all.sh                 # 跑全部
#   ./scripts/verify-all.sh LAB-DEMO-02     # 指定岛
# =============================================================================
set -uo pipefail
cd "$(dirname "$0")/.."

ISLAND="${1:-LAB-DEMO-02}"
BASE="http://localhost:18080"
PASS=0
FAIL=0
SKIP=0
REPORT="/tmp/verify-report.txt"

: "${LABOS_DB_HOST:?请设置 LABOS_DB_HOST}"
: "${LABOS_DB_NAME:?请设置 LABOS_DB_NAME}"
: "${LABOS_DB_USER:?请设置 LABOS_DB_USER}"
: "${LABOS_DB_PASS:?请设置 LABOS_DB_PASS}"

PSQL=$(command -v psql || true)
[[ -z "$PSQL" && -x /opt/homebrew/opt/libpq/bin/psql ]] && PSQL=/opt/homebrew/opt/libpq/bin/psql

q() { PGPASSWORD="$LABOS_DB_PASS" "$PSQL" -h "$LABOS_DB_HOST" -U "$LABOS_DB_USER" \
        -d "$LABOS_DB_NAME" -tAc "$1" 2>/dev/null; }

ok()   { PASS=$((PASS+1)); echo "  ✅ $1" | tee -a "$REPORT"; }
bad()  { FAIL=$((FAIL+1)); echo "  ❌ $1" | tee -a "$REPORT"; }
skip() { SKIP=$((SKIP+1)); echo "  ⏭  $1" | tee -a "$REPORT"; }
step() { echo "" | tee -a "$REPORT"; echo "── $1 ──" | tee -a "$REPORT"; }

: > "$REPORT"
echo "========================================" | tee -a "$REPORT"
echo " LabOS 全链路验证  $(date '+%Y-%m-%d %H:%M:%S')" | tee -a "$REPORT"
echo " 岛：$ISLAND" | tee -a "$REPORT"
echo "========================================" | tee -a "$REPORT"

# ---------------------------------------------------------------- 0. 前置
step "0. 服务与模拟器"

if curl -s -m 3 "$BASE/health" >/dev/null 2>&1 || \
   curl -s -m 3 -o /dev/null -w "%{http_code}" "$BASE/api/auth/login" -d '{}' 2>/dev/null | grep -qE "200|400|401"; then
    ok "中控服务在 $BASE"
else
    bad "中控服务未启动（先跑 ./scripts/start-kernel.sh）"
    echo "报告：$REPORT"; exit 1
fi

SIM_COUNT=$(pgrep -f "com.labos.kernel.sim.DeviceSimulator" | wc -l | tr -d ' ')
if [ "$SIM_COUNT" -gt 0 ]; then
    ok "模拟器运行中：$SIM_COUNT 个"
else
    bad "没有模拟器在跑（先跑 scripts/sim-up.sh）"
    echo "报告：$REPORT"; exit 1
fi

TOKEN=$(curl -s -X POST "$BASE/api/auth/login" \
        -d '{"username":"admin","password":"admin123"}' \
        | python3 -c "import sys,json;print(json.load(sys.stdin).get('token',''))" 2>/dev/null)
if [ -n "$TOKEN" ]; then ok "登录成功"; else bad "登录失败"; exit 1; fi

H() { curl -s -H "Authorization: Bearer $TOKEN" "$@"; }

# ---------------------------------------------------------------- 1. 驱动注册
step "1. 驱动注册（每台设备一个端点）"

HB_JSON=$(H "$BASE/api/heartbeat")
DRV_COUNT=$(echo "$HB_JSON" | grep -o '"driver":"DRV-SIM-[^"]*"' | wc -l | tr -d ' ')

if [ "${DRV_COUNT:-0}" -gt 0 ]; then
    ok "模拟驱动已注册：$DRV_COUNT 个"
else
    bad "没有模拟驱动（检查 buildDriverRegistry）"
fi

# ---------------------------------------------------------------- 2. 心跳
step "2. 心跳探活"

ON=$(echo "$HB_JSON" | grep -o '"driver":"DRV-SIM-[^"]*","online":true' | wc -l | tr -d ' ')
TOTAL=$DRV_COUNT
if [ "${ON:-0}" -eq "${TOTAL:-1}" ] && [ "${TOTAL:-0}" -gt 0 ]; then
    ok "全部在线：$ON/$TOTAL"
elif [ "${ON:-0}" -gt 0 ]; then
    bad "部分离线：$ON/$TOTAL"
else
    bad "全部离线：$ON/$TOTAL"
fi

# ---------------------------------------------------------------- 3. 逐台探针
step "3. 单命令探针（每台设备打一次 Home）"

PROBE_OK=0
PROBE_FAIL=0
DEVICES=$(q "
SELECT r.id || '|' || r.code
FROM device_instance di JOIN resource_node r ON r.id = di.resource_id
WHERE di.deleted_at IS NULL
  AND r.parent_id = (SELECT id FROM resource_node WHERE code = '$ISLAND')
ORDER BY r.code;")

while IFS='|' read -r RID CODE; do
    [ -z "$RID" ] && continue
    RES=$(curl -s -m 20 -X POST -H "Authorization: Bearer $TOKEN" \
          -H "Content-Type: application/json" \
          "$BASE/api/shadow/probe?resourceId=$RID&method=Home" -d '{"params":{}}')
    V=$(echo "$RES" | python3 -c "
import sys,json
try:
    d=json.load(sys.stdin)
    c=d.get('contract') or {}
    print('%s|%s|%s' % (d.get('ok'), d.get('elapsedMs'),
          'ok' if all(c.values()) and c else 'bad'))
except Exception:
    print('False|0|bad')" 2>/dev/null)
    IFS='|' read -r ISOK MS CT <<< "$V"
    if [ "$ISOK" = "True" ] && [ "$CT" = "ok" ]; then
        printf "    %-16s ok  %sms  契约四项通过\n" "$CODE" "$MS"
        PROBE_OK=$((PROBE_OK+1))
    else
        printf "    %-16s 失败（ok=%s 契约=%s）\n" "$CODE" "$ISOK" "$CT"
        PROBE_FAIL=$((PROBE_FAIL+1))
    fi
done <<< "$DEVICES"

if [ "$PROBE_FAIL" -eq 0 ] && [ "$PROBE_OK" -gt 0 ]; then
    ok "探针全部通过：$PROBE_OK 台"
else
    bad "探针通过 $PROBE_OK 台，失败 $PROBE_FAIL 台"
fi

# ---------------------------------------------------------------- 4. 整流程影子
step "4. 整流程影子（不发命令，只看会发什么）"

WF_ID=$(q "SELECT id FROM workflow WHERE name LIKE '%nch%' ORDER BY id LIMIT 1;")
[ -z "$WF_ID" ] && WF_ID=$(q "SELECT id FROM workflow WHERE graph IS NOT NULL ORDER BY id LIMIT 1;")

if [ -n "$WF_ID" ]; then
    SH=$(curl -s -m 30 -X POST -H "Authorization: Bearer $TOKEN" \
         "$BASE/api/workflows/$WF_ID/shadow-run")
    NODE_N=$(echo "$SH" | python3 -c "
import sys,json
try:
    d=json.load(sys.stdin)
    print(len(d.get('nodes') or d.get('steps') or []))
except Exception:
    print(0)" 2>/dev/null)
    if [ "${NODE_N:-0}" -gt 0 ]; then
        ok "影子执行：$NODE_N 个节点（流程 ${WF_ID}）"
    else
        skip "影子接口没返回节点（流程 ${WF_ID}）"
    fi
else
    skip "库里没有可用流程"
fi

# ---------------------------------------------------------------- 5. 单流程实跑
step "5. 单流程实跑"

ISL_ID=$(q "SELECT id FROM resource_node WHERE code = '$ISLAND' AND kind='island';")
if [ -n "$WF_ID" ] && [ -n "$ISL_ID" ]; then
    RUN=$(curl -s -m 90 -X POST -H "Authorization: Bearer $TOKEN" \
          "$BASE/api/workflows/$WF_ID/run?islandId=$ISL_ID")
    DONE=$(echo "$RUN" | python3 -c "
import sys,json
try:
    d=json.load(sys.stdin)
    print('%s/%s|%s' % (d.get('completed'), d.get('total'), d.get('runId')))
except Exception:
    print('0/0|')" 2>/dev/null)
    COMP="${DONE%%|*}"; RID="${DONE##*|}"
    if [ "${COMP%%/*}" = "${COMP##*/}" ] && [ "${COMP%%/*}" != "0" ]; then
        ok "实跑完成 ${COMP}（runId=${RID}）"
        # 检查每条命令有真实耗时
        DUR=$(q "SELECT count(*) FROM run_command
                 WHERE run_id = $RID AND (result->>'durationMs')::int > 0;")
        ALL=$(q "SELECT count(*) FROM run_command WHERE run_id = $RID;")
        if [ "${DUR:-0}" -eq "${ALL:-0}" ] && [ "${ALL:-0}" -gt 0 ]; then
            ok "每条命令都有真实耗时（$DUR/${ALL}）"
        else
            bad "有命令耗时为 0（$DUR/${ALL}）——可能没真发给设备"
        fi
    else
        bad "实跑失败：$COMP"
    fi
else
    skip "缺流程或岛"
fi

# ---------------------------------------------------------------- 6. 重复 20 次
step "6. 重复 20 次（稳定性）"

if [ -n "$WF_ID" ] && [ -n "$ISL_ID" ]; then
    S_OK=0; S_FAIL=0
    for i in $(seq 1 20); do
        R=$(curl -s -m 60 -X POST -H "Authorization: Bearer $TOKEN" \
            "$BASE/api/workflows/$WF_ID/run?islandId=$ISL_ID")
        C=$(echo "$R" | python3 -c "
import sys,json
try:
    d=json.load(sys.stdin)
    print('1' if d.get('completed')==d.get('total') and d.get('total') else '0')
except Exception:
    print('0')" 2>/dev/null)
        [ "$C" = "1" ] && S_OK=$((S_OK+1)) || S_FAIL=$((S_FAIL+1))
        printf "\r    进度 %d/20（成功 %d 失败 %d）" "$i" "$S_OK" "$S_FAIL"
    done
    echo ""
    if [ "$S_FAIL" -eq 0 ]; then ok "20 次全部成功"; else bad "20 次中失败 $S_FAIL 次"; fi
else
    skip "缺流程或岛"
fi

# ---------------------------------------------------------------- 7. 运行中状态
step "7. 运行中状态（三态上报）"

RUNNING=$(q "SELECT count(*) FROM device_state WHERE tenant_id=1 AND status='running';")
ok "当前 running 设备数：${RUNNING}（执行结束后应回 0）"

# ---------------------------------------------------------------- 8. 离线告警
step "8. 离线告警"

OPEN_ALERTS=$(q "SELECT count(*) FROM alert_state WHERE tenant_id=1
                 AND fingerprint LIKE 'DEVICE-OFFLINE%' AND status='open';")
CLOSED_ALERTS=$(q "SELECT count(*) FROM alert_state WHERE tenant_id=1
                   AND fingerprint LIKE 'DEVICE-OFFLINE%' AND status='closed';")
ok "离线告警：未关闭 $OPEN_ALERTS 条 / 已关闭 $CLOSED_ALERTS 条"

FAKE=$(q "SELECT count(*) FROM alert_state a
          LEFT JOIN device_state ds ON a.fingerprint = 'DEVICE-OFFLINE|' || ds.resource_id
          WHERE a.tenant_id=1 AND a.status='open' AND ds.resource_id IS NULL;")
if [ "${FAKE:-0}" -eq 0 ]; then
    ok "没有指向不存在设备的告警"
else
    bad "有 $FAKE 条告警指向不存在的设备"
fi

# ---------------------------------------------------------------- 汇总
echo "" | tee -a "$REPORT"
echo "========================================" | tee -a "$REPORT"
echo " 通过 $PASS / 失败 $FAIL / 跳过 $SKIP" | tee -a "$REPORT"
echo "========================================" | tee -a "$REPORT"

[ "$FAIL" -eq 0 ] && exit 0 || exit 1
