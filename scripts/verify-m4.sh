#!/bin/bash
# =============================================================================
# M4 暂停 / 恢复 / 故障处理 验收
#
# 场景：
#   ① 三种暂停态    —— paused_fault / waiting_human / paused_unknown
#   ② 暂停释放资源  —— 非豁免的清掉，回程承诺保持
#   ③ UNKNOWN 必须校正 —— 没 corrections 拒绝恢复
#   ④ 盘点后解封    —— 只解因这块板被封的位置
#   ⑤ 暂停列表可见  —— GET /api/runs/paused
#
# 用法：./scripts/verify-m4.sh
# =============================================================================
set -uo pipefail
cd "$(dirname "$0")/.."

BASE="http://localhost:18080"
REPORT=/tmp/verify-m4.txt
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
echo " M4 暂停/恢复验收  $(date '+%H:%M:%S')" | tee -a "$REPORT"
echo "========================================" | tee -a "$REPORT"

TOKEN=$(curl -s -X POST "$BASE/api/auth/login" \
        -d '{"username":"admin","password":"admin123"}' \
        | python3 -c "import sys,json;print(json.load(sys.stdin).get('token',''))" 2>/dev/null)
[ -n "$TOKEN" ] || { echo "登录失败"; exit 1; }

api() { curl -s -H "Authorization: Bearer $TOKEN" "$@"; }

# 造一个 running 的 run
RUN_ID=$(q "INSERT INTO run (tenant_id, run_no, run_type, workflow_id, workflow_ver,
                             mode, status, actual_start, progress)
            SELECT 1, 'M4-VERIFY', 'workflow', 239, 1, mode, 'running', now(), 20
            FROM run ORDER BY id DESC LIMIT 1 RETURNING id;" | head -1 | tr -d '[:space:]')

cleanup() {
    q "UPDATE position_state SET reserved_by_run=NULL, block_reason=NULL, reserved_until=NULL
       WHERE tenant_id=1 AND block_reason='PLATE_UNKNOWN' AND reserved_by_run=-1;" >/dev/null
    q "DELETE FROM plate_location_audit WHERE run_id=$RUN_ID;" >/dev/null
    q "DELETE FROM plate WHERE tenant_id=1 AND barcode='PL-VERIFY-M4';" >/dev/null
    if [ "${CREATED_POS:-0}" = "1" ] && [ -n "${POS_ID:-}" ]; then
        q "DELETE FROM position_state WHERE tenant_id=1 AND resource_id=$POS_ID AND block_reason IS NULL;" >/dev/null
    fi
    q "DELETE FROM run WHERE id=$RUN_ID;" >/dev/null
}
trap cleanup EXIT

[ -n "$RUN_ID" ] && ok "造测试 run id=$RUN_ID" || { bad "造 run 失败"; exit 1; }

# ---------------------------------------------------------------- ① 三种暂停态
step "① 三种暂停态"

for KIND in paused_fault waiting_human paused_unknown; do
    q "UPDATE run SET status='running', pause_kind=NULL WHERE id=$RUN_ID;" >/dev/null
    RESP=$(api -X POST "$BASE/api/runs/$RUN_ID/pause?kind=$KIND&reason=verify")
    GOT=$(echo "$RESP" | python3 -c "import sys,json;print(json.load(sys.stdin).get('pauseKind'))" 2>/dev/null)
    DBK=$(q "SELECT pause_kind FROM run WHERE id=$RUN_ID;")
    if [ "$GOT" = "$KIND" ] && [ "$DBK" = "$KIND" ]; then
        ok "$KIND 暂停成功（API 与库一致）"
    else
        bad "$KIND 暂停失败：api=$GOT db=$DBK resp=$RESP"
    fi
done

# ---------------------------------------------------------------- ⑤ 暂停列表
step "⑤ 暂停列表可见"

LIST=$(api "$BASE/api/runs/paused")
CNT=$(echo "$LIST" | python3 -c "
import sys,json
d=json.load(sys.stdin)
print(sum(1 for x in d if x.get('runNo')=='M4-VERIFY'))
" 2>/dev/null)
[ "${CNT:-0}" -ge 1 ] && ok "列表里能看到测试 run" || bad "列表里找不到（cnt=${CNT}）"

# ---------------------------------------------------------------- ③ UNKNOWN 必须校正
step "③ paused_unknown 没有校正必须拒绝恢复"

RESP=$(api -X POST "$BASE/api/runs/$RUN_ID/resume?action=continue")
OK3=$(echo "$RESP" | python3 -c "import sys,json;print(json.load(sys.stdin).get('ok'))" 2>/dev/null)
[ "$OK3" = "False" ] && ok "无校正被拒绝" || bad "无校正居然恢复成功：$RESP"

ERR=$(echo "$RESP" | python3 -c "import sys,json;print(json.load(sys.stdin).get('error') or '')" 2>/dev/null)
echo "$ERR" | grep -q "corrections" && ok "拒绝原因提到 corrections" || bad "原因不清：$ERR"

STILL=$(q "SELECT status FROM run WHERE id=$RUN_ID;")
[ "$STILL" = "paused" ] && ok "被拒后仍停在 paused" || bad "被拒后状态变了：$STILL"

# ---------------------------------------------------------------- ④ 盘点解封
step "④ 板位 UNKNOWN 封锁 + 盘点解封"

# 造一块板 + 一个位置。位置优先取一台在线设备，且 position_state 可能为空
# （演示库不保证有位置行），测试自己补一行再在 cleanup 里删，避免依赖残留数据。
POS_ID=$(q "SELECT resource_id FROM device_state WHERE tenant_id=1 AND online=true ORDER BY resource_id LIMIT 1;")
CREATED_POS=0
if [ -z "$POS_ID" ]; then
    echo "  ⏭ 没有在线设备可作为盘点位置，跳过板位封锁/解封" | tee -a "$REPORT"
else
    NOS=$(q "SELECT count(*) FROM position_state WHERE tenant_id=1 AND resource_id=$POS_ID;")
    if [ "$NOS" = "0" ]; then
        q "INSERT INTO position_state (tenant_id, resource_id, updated_at) VALUES (1,$POS_ID,now());" >/dev/null
        CREATED_POS=1
    fi
# 真实流程里 markPlateUnknown 会同时写 possible_locations；
# 测试要模拟完整，否则解封时找不到"可能位置"
q "INSERT INTO plate (tenant_id, barcode, status, position_status, possible_locations)
   VALUES (1, 'PL-VERIFY-M4', 'active', 'UNKNOWN', ARRAY[$POS_ID]::bigint[])
   ON CONFLICT (tenant_id, barcode) DO UPDATE
   SET position_status='UNKNOWN', possible_locations=ARRAY[$POS_ID]::bigint[];" >/dev/null
q "UPDATE position_state SET reserved_by_run=-1, block_reason='PLATE_UNKNOWN',
       reserved_until=now()+interval '24 hours'
   WHERE tenant_id=1 AND resource_id=$POS_ID;" >/dev/null

UNK=$(api "$BASE/api/plates/unknown")
echo "$UNK" | grep -q "PL-VERIFY-M4" && ok "unknown 列表里有这块板" || bad "unknown 列表里没有"

BLOCKED=$(q "SELECT count(*) FROM position_state
             WHERE tenant_id=1 AND resource_id=$POS_ID AND block_reason='PLATE_UNKNOWN';")
[ "$BLOCKED" = "1" ] && ok "位置已封锁" || bad "位置没封锁"

# 盘点
LOC=$(api -X POST -d '{"actualLocation":"ID1183-L2"}' \
        "$BASE/api/plates/PL-VERIFY-M4/locate")
OK4=$(echo "$LOC" | python3 -c "import sys,json;print(json.load(sys.stdin).get('ok'))" 2>/dev/null)
[ "$OK4" = "True" ] && ok "盘点成功" || bad "盘点失败：$LOC"

PST=$(q "SELECT position_status FROM plate WHERE tenant_id=1 AND barcode='PL-VERIFY-M4';")
[ "$PST" = "KNOWN" ] && ok "板状态回到 KNOWN" || bad "板状态是 $PST"

AFTER=$(q "SELECT count(*) FROM position_state
           WHERE tenant_id=1 AND resource_id=$POS_ID AND block_reason='PLATE_UNKNOWN';")
[ "$AFTER" = "0" ] && ok "位置已解封" || bad "位置没解封"

AUDIT=$(q "SELECT count(*) FROM plate_location_audit
           WHERE tenant_id=1 AND barcode='PL-VERIFY-M4';")
[ "${AUDIT:-0}" -ge 1 ] && ok "盘点留痕（$AUDIT 条审计）" || bad "没有审计记录"
fi

# ---------------------------------------------------------------- ⑥ 故障自动恢复
step "⑥ paused_fault 心跳恢复自动续跑（只看本流程设备）"

q "UPDATE run SET status='running', pause_kind=NULL, pause_reason=NULL WHERE id=$RUN_ID;" >/dev/null
PAUSE_RESP=$(api -X POST "$BASE/api/runs/$RUN_ID/pause?kind=paused_fault&reason=verify-auto-resume")
PK=$(echo "$PAUSE_RESP" | python3 -c "import sys,json;print(json.load(sys.stdin).get('pauseKind',''))" 2>/dev/null)
[ "$PK" = "paused_fault" ] && ok "paused_fault 暂停成功（API 与库一致）" \
    || bad "paused_fault 暂停失败：api=$PK resp=$PAUSE_RESP"

# 心跳默认 10s 一轮；自动恢复只看「本 run 所属流程用到的设备」是否在线，
# 不再被无关离线设备挡住（S-04-B 修复）。最多等 45 秒。
RESUMED=0
for i in $(seq 1 45); do
    ST=$(q "SELECT status::text FROM run WHERE id=$RUN_ID;")
    if [ "$ST" = "running" ]; then RESUMED=1; break; fi
    sleep 1
done
[ "$RESUMED" = "1" ] && ok "心跳检测到本流程设备在线后自动继续（paused_fault → running）" \
    || bad "paused_fault 未被心跳自动恢复"

# ---------------------------------------------------------------- ② 豁免
step "② 回程承诺暂停不释放"

RET=$(q "SELECT count(*) FROM resource_booking
         WHERE tenant_id=1 AND source_type='position-return';")
BADX=$(q "SELECT count(*) FROM resource_booking
          WHERE tenant_id=1 AND source_type='position-return'
            AND COALESCE(release_exempt,false)=false;")
if [ "${RET:-0}" -eq 0 ]; then
    echo "  ⏭ 库里没有回程承诺预约，跳过" | tee -a "$REPORT"
else
    [ "$BADX" = "0" ] && ok "回程承诺都标了豁免（$RET 条）" || bad "$BADX 条没标豁免"
fi

# ---------------------------------------------------------------- 汇总
echo "" | tee -a "$REPORT"
echo "========================================" | tee -a "$REPORT"
echo " 通过 $PASS / 失败 $FAIL" | tee -a "$REPORT"
echo "========================================" | tee -a "$REPORT"

[ "$FAIL" -eq 0 ] && exit 0 || exit 1
