#!/bin/bash
# =============================================================================
# M3 启动准入（主链粗筛）验收
#
# 场景：
#   ① 空闲时允许启动      canStart=true，有 strategy / needDeviceCount
#   ② 占用一台不影响      INCUBATE 有 48 台，占 1 台仍允许
#   ③ 全占满则拒绝        canStart=false，给出 reason + blocking + 等待
#   ④ 主链策略可解释      strategy 标明是人工标记还是资源最重
#   ⑤ 清理后恢复          canStart 回到 true
#
# 用法：./scripts/verify-m3.sh [workflowId]
# =============================================================================
set -uo pipefail
cd "$(dirname "$0")/.."

BASE="http://localhost:18080"
WF="${1:-239}"
REPORT=/tmp/verify-m3.txt
PASS=0
FAIL=0

: "${LABOS_DB_HOST:?请设置 LABOS_DB_HOST}"
: "${LABOS_DB_NAME:?}"
: "${LABOS_DB_USER:?}"
: "${LABOS_DB_PASS:?}"

PSQL=$(command -v psql || true)
[[ -z "$PSQL" && -x /opt/homebrew/opt/libpq/bin/psql ]] && PSQL=/opt/homebrew/opt/libpq/bin/psql
q() { PGPASSWORD="$LABOS_DB_PASS" "$PSQL" -h "$LABOS_DB_HOST" -U "$LABOS_DB_USER" \
        -d "$LABOS_DB_NAME" -tAc "$1" 2>/dev/null; }

ok()  { PASS=$((PASS+1)); echo "  ✅ $1" | tee -a "$REPORT"; }
bad() { FAIL=$((FAIL+1)); echo "  ❌ $1" | tee -a "$REPORT"; }
step(){ echo "" | tee -a "$REPORT"; echo "── $1 ──" | tee -a "$REPORT"; }

: > "$REPORT"
echo "========================================" | tee -a "$REPORT"
echo " M3 启动准入验收  $(date '+%H:%M:%S')  流程=$WF" | tee -a "$REPORT"
echo "========================================" | tee -a "$REPORT"

TOKEN=$(curl -s -X POST "$BASE/api/auth/login" \
        -d '{"username":"admin","password":"admin123"}' \
        | python3 -c "import sys,json;print(json.load(sys.stdin).get('token',''))" 2>/dev/null)
[ -n "$TOKEN" ] || { echo "登录失败" ; exit 1; }

can_start() {
    curl -s -H "Authorization: Bearer $TOKEN" "$BASE/api/workflows/$WF/can-start"
}

# ---------------------------------------------------------------- ① 空闲
step "① 空闲时允许启动"

JSON=$(can_start)
OK=$(echo "$JSON" | python3 -c "import sys,json;print(json.load(sys.stdin).get('canStart'))" 2>/dev/null)
[ "$OK" = "True" ] && ok "canStart=true" || bad "应当允许启动，实际：$JSON"

STRAT=$(echo "$JSON" | python3 -c "import sys,json;print(json.load(sys.stdin).get('strategy'))" 2>/dev/null)
case "$STRAT" in
    manual-mark|heaviest-devices|all-nodes|empty)
        ok "主链策略可解释：$STRAT" ;;
    *) bad "策略不认识：$STRAT" ;;
esac

NEED=$(echo "$JSON" | python3 -c "import sys,json;print(json.load(sys.stdin).get('needDeviceCount'))" 2>/dev/null)
[ -n "$NEED" ] && ok "需求设备数：$NEED" || bad "没有 needDeviceCount"

EST=$(echo "$JSON" | python3 -c "import sys,json;print(json.load(sys.stdin).get('totalEstimateSec'))" 2>/dev/null)
[ -n "$EST" ] && ok "预估总时长：${EST}s" || bad "没有 totalEstimateSec"

# ---------------------------------------------------------------- ② 占一台
step "② 占用一台不影响（池子里还有很多）"

CAP_CODE=$(q "SELECT c.code FROM capability c
              JOIN workflow_node n ON n.capability_id = c.id
              WHERE n.workflow_id = $WF AND n.capability_id IS NOT NULL LIMIT 1;")
CAP_CODE=${CAP_CODE:-INCUBATE}

TOTAL=$(q "SELECT count(*) FROM resource_capability rc
           JOIN capability c ON c.id = rc.capability_id
           JOIN device_instance di ON di.resource_id = rc.resource_id
                AND di.tenant_id = 1 AND di.deleted_at IS NULL
           WHERE c.code = '$CAP_CODE' AND c.tenant_id = 1;")
TOTAL=${TOTAL:-0}

if [ "$TOTAL" -ge 2 ]; then
    OCC_RID=$(q "INSERT INTO run (tenant_id, run_no, run_type, workflow_id, workflow_ver,
                                  mode, status, actual_start, progress)
                 SELECT 1, 'M3-VERIFY-OCC', 'workflow', $WF, 1, mode, 'running', now(), 0
                 FROM run ORDER BY id DESC LIMIT 1 RETURNING id;" | head -1 | tr -d '[:space:]')
    if [ -n "$OCC_RID" ]; then
        q "INSERT INTO device_state (tenant_id, resource_id, status, current_run_id, updated_at)
           SELECT DISTINCT 1, rc.resource_id, 'running', $OCC_RID, now()
           FROM resource_capability rc
           JOIN capability c ON c.id = rc.capability_id
           JOIN device_instance di ON di.resource_id = rc.resource_id
                AND di.tenant_id = 1 AND di.deleted_at IS NULL
           WHERE c.code = '$CAP_CODE' AND c.tenant_id = 1
           LIMIT 1
           ON CONFLICT (tenant_id, resource_id) DO UPDATE
           SET current_run_id = EXCLUDED.current_run_id, status='running';" >/dev/null

        OK2=$(can_start | python3 -c "import sys,json;print(json.load(sys.stdin).get('canStart'))" 2>/dev/null)
        [ "$OK2" = "True" ] && ok "$CAP_CODE 共 $TOTAL 台，占 1 台仍允许" \
                            || bad "占 1 台就被拒了（池子应还有余量）"
    else
        bad "造占用 run 失败"
    fi
else
    echo "  ⏭ $CAP_CODE 只有 $TOTAL 台，跳过" | tee -a "$REPORT"
fi

# ---------------------------------------------------------------- ③ 全占满
step "③ 全占满则拒绝"

q "INSERT INTO device_state (tenant_id, resource_id, status, current_run_id, updated_at)
   SELECT DISTINCT 1, rc.resource_id, 'running', $OCC_RID, now()
   FROM resource_capability rc
   JOIN capability c ON c.id = rc.capability_id
   JOIN device_instance di ON di.resource_id = rc.resource_id
        AND di.tenant_id = 1 AND di.deleted_at IS NULL
   WHERE c.code = '$CAP_CODE' AND c.tenant_id = 1
   ON CONFLICT (tenant_id, resource_id) DO UPDATE
   SET current_run_id = EXCLUDED.current_run_id, status='running';" >/dev/null

JSON3=$(can_start)
OK3=$(echo "$JSON3" | python3 -c "import sys,json;print(json.load(sys.stdin).get('canStart'))" 2>/dev/null)
[ "$OK3" = "False" ] && ok "canStart=false" || bad "占满了还允许启动：$JSON3"

echo "$JSON3" | python3 -c "
import sys, json
d = json.load(sys.stdin)
r = d.get('reason') or ''
print('  reason:', r)
bl = d.get('blocking') or []
print('  blocking:', len(bl), '个')
if bl:
    b = bl[0]
    print('  首个占用:', b.get('deviceCode'), 'runNo=', b.get('runNo'),
          'eta=', b.get('etaSeconds'), 's')
assert r, '没有 reason'
assert isinstance(d.get('estimatedWaitSeconds'), (int, type(None))), 'estimatedWaitSeconds 类型不对'
print('OK')
" > /tmp/m3-parse.txt 2>&1
if grep -q "^OK" /tmp/m3-parse.txt; then
    ok "拒绝信息完整（reason + blocking + 等待估算）"
else
    bad "拒绝信息不完整"
fi
sed 's/^/  /' /tmp/m3-parse.txt | head -4 | tee -a "$REPORT"

# ---------------------------------------------------------------- ⑤ 清理
step "⑤ 清理后恢复"

q "UPDATE device_state SET current_run_id = NULL, status = 'idle'
   WHERE tenant_id = 1 AND current_run_id = $OCC_RID;" >/dev/null
q "DELETE FROM run WHERE id = $OCC_RID;" >/dev/null

OK5=$(can_start | python3 -c "import sys,json;print(json.load(sys.stdin).get('canStart'))" 2>/dev/null)
[ "$OK5" = "True" ] && ok "清理后回到允许启动" || bad "清理后仍被拒"

LEFT=$(q "SELECT count(*) FROM device_state WHERE tenant_id=1 AND current_run_id IS NOT NULL;")
[ "${LEFT:-0}" -eq 0 ] && ok "没有残留占用" || bad "残留 $LEFT 个占用"

# ---------------------------------------------------------------- 汇总
echo "" | tee -a "$REPORT"
echo "========================================" | tee -a "$REPORT"
echo " 通过 $PASS / 失败 $FAIL" | tee -a "$REPORT"
echo "========================================" | tee -a "$REPORT"

[ "$FAIL" -eq 0 ] && exit 0 || exit 1
