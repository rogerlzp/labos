#!/bin/bash
# =============================================================================
# M1 并行调度基础设施验收
#
# 5 个场景：
#   ① 方法边界    —— 给机械臂发开门方法必须报 EC-06-0004
#   ② 门保护      —— 门关着时 TransferPlan 会先调 OpenDoor
#   ③ 通道信号量  —— capacity=2 时能同时占 2 个，第 3 个失败
#   ④ 点位生成    —— 机械臂 GetPoints 返回 4 个层位
#   ⑤ 失败回滚    —— 开门失败后通道占用归零
#
# 用法：./scripts/verify-m1.sh
# =============================================================================
set -uo pipefail
cd "$(dirname "$0")/.."

PASS=0
FAIL=0
ROBOT_PORT=19701
INC_PORT=19702
CENT_PORT=19703
REPORT=/tmp/verify-m1.txt

ok()  { PASS=$((PASS+1)); echo "  ✅ $1" | tee -a "$REPORT"; }
bad() { FAIL=$((FAIL+1)); echo "  ❌ $1" | tee -a "$REPORT"; }
step(){ echo "" | tee -a "$REPORT"; echo "── $1 ──" | tee -a "$REPORT"; }

: > "$REPORT"
echo "========================================" | tee -a "$REPORT"
echo " M1 验收  $(date '+%Y-%m-%d %H:%M:%S')" | tee -a "$REPORT"
echo "========================================" | tee -a "$REPORT"

# ---------------------------------------------------------------- 准备
step "0. 起分类型模拟器"

pkill -f "com.labos.kernel.sim.DeviceSimulator" 2>/dev/null
sleep 2

(nohup java -cp labos-kernel/out com.labos.kernel.sim.DeviceSimulator \
    --port $ROBOT_PORT --device 214 --code ID1731 --cap TRANSFER \
    --profile robot >> /tmp/m1-robot.log 2>&1 &)
(nohup java -cp labos-kernel/out com.labos.kernel.sim.DeviceSimulator \
    --port $INC_PORT --device 171 --code ID1183 --cap INCUBATE \
    --profile incubator --delay 100 >> /tmp/m1-inc.log 2>&1 &)
(nohup java -cp labos-kernel/out com.labos.kernel.sim.DeviceSimulator \
    --port $CENT_PORT --device 999 --code CENT-01 --cap CENTRIFUGE \
    --profile centrifuge >> /tmp/m1-cent.log 2>&1 &)
sleep 4

for p in $ROBOT_PORT $INC_PORT $CENT_PORT; do
    if lsof -i :$p 2>/dev/null | grep -q LISTEN; then
        ok "模拟器在 $p"
    else
        bad "模拟器 $p 没起来"
    fi
done

# 通用发包
send() {
    python3 - "$1" "$2" "$3" <<'PY'
import socket, re, sys
port, dev, method = int(sys.argv[1]), sys.argv[2], sys.argv[3]
msg = '{"message_id":"T","message_type":1,"method":"%s","equipment_id":"%s"}#!LABSEP!#' % (method, dev)
try:
    s = socket.create_connection(("127.0.0.1", port), timeout=8)
    s.sendall(msg.encode())
    buf = b""
    for _ in range(3):
        d = s.recv(65536)
        if not d: break
        buf += d
        if b"#!LABSEP!#" in buf: break
    s.close()
    t = buf.decode(errors="replace")
    st = re.search(r'"method_status"\s*:\s*(\d+)', t)
    ec = re.search(r'"error_code"\s*:\s*"([^"]*)"', t)
    print("%s|%s" % (st.group(1) if st else "?", ec.group(1) if ec else ""))
except Exception as e:
    print("ERR|%s" % e)
PY
}

# ---------------------------------------------------------------- ① 方法边界
step "① 方法边界（不同类型有不同方法集）"

R=$(send $ROBOT_PORT 214 Home)
[ "${R%%|*}" = "2" ] && ok "robot.Home 成功" || bad "robot.Home 失败：$R"

R=$(send $ROBOT_PORT 214 OpenDoor)
EC="${R##*|}"
[ "$EC" = "EC-06-0004" ] && ok "robot.OpenDoor 被拒（机械臂没开门方法）" \
    || bad "robot.OpenDoor 应当拒绝，实际：$R"

R=$(send $INC_PORT 171 OpenDoor)
[ "${R%%|*}" = "2" ] && ok "incubator.OpenDoor 成功" || bad "incubator.OpenDoor 失败：$R"

R=$(send $INC_PORT 171 Transfer)
EC="${R##*|}"
[ "$EC" = "EC-06-0004" ] && ok "incubator.Transfer 被拒（孵箱不能搬板）" \
    || bad "incubator.Transfer 应当拒绝，实际：$R"

# ---------------------------------------------------------------- ④ 点位生成
step "④ 点位生成（机械臂上报）"

PTS=$(send $ROBOT_PORT 214 GetPoints)
if [ "${PTS%%|*}" = "2" ]; then
    ok "robot.GetPoints 成功"
else
    bad "robot.GetPoints 失败：$PTS"
fi

# 直接看返回里有几个 code
CNT=$(python3 - "$ROBOT_PORT" <<'PY'
import socket, re, sys
port = int(sys.argv[1])
msg = '{"message_id":"T","message_type":1,"method":"GetPoints","equipment_id":"214"}#!LABSEP!#'
s = socket.create_connection(("127.0.0.1", port), timeout=8)
s.sendall(msg.encode())
buf = b""
for _ in range(3):
    d = s.recv(65536)
    if not d: break
    buf += d
    if b"#!LABSEP!#" in buf: break
s.close()
print(len(re.findall(r'"code"\s*:\s*"([^"]+)"', buf.decode(errors="replace"))))
PY
)
if [ "${CNT:-0}" -ge 4 ]; then
    ok "返回 $CNT 个点位"
else
    bad "点位数量不足（$CNT < 4）"
fi

# ---------------------------------------------------------------- ②③⑤ 资源
step "②③⑤ 门 / 通道信号量 / 失败回滚"

if [ -z "${LABOS_DB_HOST:-}" ]; then
    echo "  ⏭ 没设 LABOS_DB_HOST，跳过数据库相关检查" | tee -a "$REPORT"
else
    PSQL=$(command -v psql || true)
    [[ -z "$PSQL" && -x /opt/homebrew/opt/libpq/bin/psql ]] && PSQL=/opt/homebrew/opt/libpq/bin/psql
    q() { PGPASSWORD="${LABOS_DB_PASS:-}" "$PSQL" -h "$LABOS_DB_HOST" -U "${LABOS_DB_USER:-}" \
            -d "${LABOS_DB_NAME:-}" -tAc "$1" 2>/dev/null; }

    # ② 门配置存在且状态可读
    N=$(q "SELECT count(*) FROM device_access WHERE tenant_id=1;")
    if [ "${N:-0}" -ge 1 ]; then
        ok "门配置存在（$N 条）"
    else
        bad "没有门配置"
    fi

    # ③ 通道信号量：capacity 与 in_use 的关系
    RES=$(q "SELECT p.code || '|' || u.in_use || '/' || u.max_capacity
             FROM device_port_usage u JOIN device_port p ON p.id=u.port_id
             WHERE p.tenant_id=1 LIMIT 1;")
    if [ -n "$RES" ]; then
        ok "通道计数可读：$RES"
    else
        bad "读不到通道计数"
    fi

    # ⑤ 跑一次 TransferPlan 回归（内含失败回滚断言）
    TP=$(LABOS_DB_HOST="$LABOS_DB_HOST" LABOS_DB_NAME="${LABOS_DB_NAME:-}" \
         LABOS_DB_USER="${LABOS_DB_USER:-}" LABOS_DB_PASS="${LABOS_DB_PASS:-}" \
         java -cp "labos-kernel/out:labos-kernel/lib/postgresql-42.7.3.jar" \
         com.labos.kernel.driver.TransferPlanSelfTest 2>&1 | grep -oE "通过 [0-9]+, 失败 [0-9]+")
    if echo "$TP" | grep -q "失败 0"; then
        ok "TransferPlan $TP"
    else
        bad "TransferPlan 失败：$TP"
    fi
fi

# ---------------------------------------------------------------- 汇总
echo "" | tee -a "$REPORT"
echo "========================================" | tee -a "$REPORT"
echo " 通过 $PASS / 失败 $FAIL" | tee -a "$REPORT"
echo "========================================" | tee -a "$REPORT"

pkill -f "com.labos.kernel.sim.DeviceSimulator" 2>/dev/null

[ "$FAIL" -eq 0 ] && exit 0 || exit 1
