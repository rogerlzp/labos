#!/usr/bin/env bash
# ============================================================================
# 设备链路验收：设备池 → 拖进岛（部署实例）→ 绑驱动 → 中控发命令 → 设备错误码 → 恢复策略
#
# 为什么要有这一步：
#   `test-sidecar.sh` 验的是"sidecar ↔ 模拟器"，没验**中控真的能通过库里的配置
#   找到这台设备、把命令发出去**。中间隔着一整套库表（device / device_instance /
#   resource_node / driver），任何一张接错，现象都是"界面上有这台设备，但点不动"。
#
# 流程：
#   1. 设备型号入池（DeviceRegistrar，幂等）
#   2. 部署到岛（等价于前端拖拽：device-models/{id}/deploy?islandId=）
#   3. 绑驱动端点（tcp://127.0.0.1:<sidecar 端口>）
#   4. 起 模拟器×2 + sidecar×2
#   5. 走中控的 /api/shadow/probe 发真实命令，断言返回的**中控错误码**
#   6. 回查 recovery_policy，确认这个码在库里真有策略
#
# 用法：
#   ./scripts/verify-device-chain.sh                # 全跑
#   ./scripts/verify-device-chain.sh --keep         # 跑完不停模拟器/sidecar（方便手工点）
#   API=http://127.0.0.1:18080 ISLAND=Device_Test ./scripts/verify-device-chain.sh
# ============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
API=${API:-http://127.0.0.1:18080}
API_USER=${API_USER:-admin}
API_PASS=${API_PASS:-admin123}
ISLAND=${ISLAND:-Device_Test}
UNIT=${UNIT:-设备的链路验证}
KEEP=0
[[ "${1:-}" == "--keep" ]] && KEEP=1

PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ✓ $1"; }
bad() { FAIL=$((FAIL+1)); echo "  ✗ $1${2:+（$2）}"; }

# 本次运行开始时 API 日志的行数：后面查「步骤落库失败」只看这之后的增量。
# 不能 grep 整个累积日志 —— /tmp/labos-api.log 长期累积，历史上有过一次
# duplicate key，这条断言就会永远红（2026-11-06 实测踩到）。
LOG_BASE_LINE=0
[[ -f /tmp/labos-api.log ]] && LOG_BASE_LINE=$(wc -l < /tmp/labos-api.log | tr -d ' ')
step(){ echo; echo "════════ $1 ════════"; }

free_port() { python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()'; }

need() { [[ -n "${!1:-}" ]] || { echo "缺环境变量 $1（见 README 环境变量表）"; exit 1; }; }
need LABOS_DB_HOST; need LABOS_DB_NAME; need LABOS_DB_USER

q() { PGPASSWORD="${LABOS_DB_PASS:-}" psql -h "$LABOS_DB_HOST" -p "${LABOS_DB_PORT:-5432}" \
        -U "$LABOS_DB_USER" -d "$LABOS_DB_NAME" -tAc "$1" 2>/dev/null; }

SIM_S100=$(free_port); SIDE_S100=$(free_port)
SIM_ASPIN=$(free_port); SIDE_ASPIN=$(free_port)
PIDS=()
# 用 'dotnet <dll>' 启动（不是 apphost、也不走 dotnet run）：
#   1) dotnet run 会再起子进程，kill 父进程后子进程变孤儿 —— 2026-11-03 泄漏根因之一；
#   2) apphost 二进制依赖 DOTNET_ROOT/固定安装路径，本机 Homebrew dotnet 找不到运行时会拒绝启动。
# 'dotnet xxx.dll' 由宿主进程直接执行，PID 就是真进程。
SIDECAR_DLL="$ROOT/sidecar-dotnet/Labos.Sidecar/bin/Debug/net10.0/Labos.Sidecar.dll"
[[ -f "$SIDECAR_DLL" ]] || {
    echo "缺 sidecar 产物：先 ( cd sidecar-dotnet/Labos.Sidecar && dotnet build )"
    exit 1
}
cleanup() {
    if [[ $KEEP -eq 0 ]]; then
        for p in "${PIDS[@]:-}"; do pkill -P "$p" 2>/dev/null; kill "$p" 2>/dev/null; done
        # 把驱动端点**还原到固定端口**：脚本用的是随机端口，跑完 sidecar 也停了，
        # 端点要是不还原，界面上这两台设备就永远"连不上"，
        # 下次谁来排查都会先怀疑代码，其实是上次验收留下的坑。
        q "UPDATE driver SET endpoint='tcp://127.0.0.1:18501', updated_at=now()
            WHERE tenant_id=1 AND code='DRV-SIDECAR-S100'" >/dev/null 2>&1
        q "UPDATE driver SET endpoint='tcp://127.0.0.1:18502', updated_at=now()
            WHERE tenant_id=1 AND code='DRV-SIDECAR-ASPIN'" >/dev/null 2>&1
        echo "  （驱动端点已还原到 18501/18502；sidecar 已停，要用请先起 sidecar）"
    else
        echo "（--keep：模拟器/sidecar 仍在跑，端口 s100=$SIDE_S100 aspin=${SIDE_ASPIN}）"
    fi
}
trap cleanup EXIT

# ---------------------------------------------------------------- 0. 登录
step "0. 登录中控 API"
TOKEN=$(curl -s -X POST "$API/api/auth/login" -H 'Content-Type: application/json' \
        -d "{\"username\":\"$API_USER\",\"password\":\"$API_PASS\"}" \
        | python3 -c 'import sys,json;print(json.load(sys.stdin).get("token",""))' 2>/dev/null)
if [[ -z "$TOKEN" ]]; then
    bad "登录失败" "$API 不可达或账号密码不对"
    exit 1
fi
ok "登录成功"

api() {  # api <METHOD> <PATH> [BODY]
    local m=$1 p=$2 b=${3:-}
    if [[ -n "$b" ]]; then
        curl -s -X "$m" "$API$p" -H "Authorization: Bearer $TOKEN" \
             -H 'Content-Type: application/json' -d "$b"
    else
        curl -s -X "$m" "$API$p" -H "Authorization: Bearer $TOKEN"
    fi
}

# ---------------------------------------------------------------- 1. 入池
step "1. 设备型号入池（幂等）"
cd "$ROOT/labos-kernel" || exit 1
for m in s100 aspin-2k; do
    if java -cp "out:lib/postgresql-42.7.3.jar:lib/spring/*" com.labos.kernel.device.DeviceRegistrar \
            --model "$m" >/tmp/registrar-$m.log 2>&1; then
        ok "$m 在设备池"
    else
        bad "$m 入池失败" "见 /tmp/registrar-$m.log"
    fi
done

# ---------------------------------------------------------------- 2. 岛
step "2. 找岛并部署实例（= 前端拖拽那一下）"
# 岛从库里取（没有 GET /api/islands，界面走的是拓扑接口；这里直接查更稳）
ISL_ID=$(q "SELECT id FROM resource_node WHERE tenant_id=1 AND kind='island' AND code='$ISLAND' LIMIT 1")
if [[ -z "$ISL_ID" ]]; then
    resp=$(api POST /api/islands "{\"code\":\"$ISLAND\",\"name\":\"$UNIT\"}")
    ISL_ID=$(echo "$resp" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("id",""))' 2>/dev/null)
    [[ -n "$ISL_ID" ]] && ok "新建岛 $ISLAND = id $ISL_ID" || { bad "建岛失败" "$resp"; exit 1; }
else
    ok "岛 $ISLAND = id $ISL_ID"
fi

# 已有实例就复用（部署接口不幂等，重复调会建 S100-2、S100-3…）
existing_instance() {  # $1=device 池 code
    q "SELECT di.id, di.resource_id FROM device_instance di
         JOIN device d ON d.id = di.device_id
        WHERE di.deleted_at IS NULL AND d.tenant_id = 1 AND d.code = '$1'
        ORDER BY di.id LIMIT 1"
}
# 结果放全局变量：不要用 echo 往外传，否则 ✓/✗ 的日志会被一起捕获
G_INST=""; G_RID=""
deploy_if_needed() {  # $1=modelId $2=device code $3=标签
    local mid=$1 code=$2 label=$3 inst rid resp
    local row; row=$(existing_instance "$code")
    if [[ -n "$row" ]]; then
        inst=${row%%|*}; rid=${row##*|}
        ok "$label 已存在实例（resourceId=$rid instanceId=${inst}），复用"
    else
        resp=$(api POST "/api/device-models/$mid/deploy?islandId=$ISL_ID")
        rid=$(echo "$resp" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("resourceId",""))' 2>/dev/null)
        if [[ -z "$rid" ]]; then
            bad "$label 部署失败" "$resp"; G_INST=""; G_RID=""; return 1
        fi
        inst=$(q "SELECT id FROM device_instance WHERE resource_id = $rid")
        ok "$label 已部署到岛（resourceId=$rid instanceId=${inst}）"
    fi
    G_INST=$inst; G_RID=$rid
}

S100_MODEL=$(q "SELECT id FROM device WHERE tenant_id=1 AND code='S100'")
ASPIN_MODEL=$(q "SELECT id FROM device WHERE tenant_id=1 AND code='ASPIN_2K'")
deploy_if_needed "$S100_MODEL" S100 "S100 封膜机";  S100_INST=$G_INST; S100_RID=$G_RID
deploy_if_needed "$ASPIN_MODEL" ASPIN_2K "ASPin 2K 离心机"; ASPIN_INST=$G_INST; ASPIN_RID=$G_RID
[[ -n "${S100_INST:-}" && -n "${ASPIN_INST:-}" ]] || { echo "部署没成功，后面不用跑了"; exit 1; }

# ---------------------------------------------------------------- 3. 驱动
step "3. 绑驱动端点（每个 sidecar 一个 driver）"
bind_driver() {  # $1=driver code $2=port $3=instance id
    # 唯一键是 (tenant_id, code, version)，不是 (tenant_id, code) —— 不能用 ON CONFLICT (tenant_id, code)，
    # 所以走"没有就插、有就更新"两步（幂等）。
    local n; n=$(q "SELECT count(*) FROM driver WHERE tenant_id=1 AND code='$1' AND version='1.0'")
    if [[ "${n:-0}" == "0" ]]; then
        q "INSERT INTO driver (tenant_id, code, name, version, protocol, endpoint, status, manifest)
           VALUES (1, '$1', '$1', '1.0', 'tcp', 'tcp://127.0.0.1:$2', 1,
                   '{\"tcp_delimiter\":\"#!LABSEP!#\",\"driver_kind\":\"labos-sidecar\"}'::jsonb)" >/dev/null
    fi
    q "UPDATE driver SET endpoint='tcp://127.0.0.1:$2', status=1, updated_at=now()
        WHERE tenant_id=1 AND code='$1' AND version='1.0'" >/dev/null
    local did; did=$(q "SELECT id FROM driver WHERE tenant_id=1 AND code='$1' AND version='1.0'")
    q "UPDATE device_instance SET driver_id = $did, updated_at = now() WHERE id = $3" >/dev/null
    echo "$did"
}
# 能力行：驱动注册表按 resource_capability 分能力，缺了整条驱动会被跳过
# （deployModel 已修成部署时自动写，这里兜底存量实例）
q "INSERT INTO resource_capability (resource_id, capability_id)
   SELECT di.resource_id, d.capability_id
     FROM device_instance di JOIN device d ON d.id = di.device_id
    WHERE di.deleted_at IS NULL AND d.capability_id IS NOT NULL
   ON CONFLICT (resource_id, capability_id) DO NOTHING" >/dev/null
ok "实例能力行已补齐（resource_capability）"

S100_DRV=$(bind_driver DRV-SIDECAR-S100 "$SIDE_S100" "$S100_INST")
ASPIN_DRV=$(bind_driver DRV-SIDECAR-ASPIN "$SIDE_ASPIN" "$ASPIN_INST")
[[ -n "$S100_DRV" && -n "$ASPIN_DRV" ]] && ok "驱动已绑定（S100→$SIDE_S100 / ASPIN→${SIDE_ASPIN}）" \
    || bad "驱动绑定失败"

# ---------------------------------------------------------------- 4. 起服务
step "4. 起模拟器 + sidecar"
( cd "$ROOT/labos-kernel" && exec java -cp "out:lib/postgresql-42.7.3.jar:lib/spring/*" com.labos.kernel.sim.DeviceSimulator \
    --model s100 --device "$S100_INST" --code S100V --port "$SIM_S100" --delay 1 \
    > /tmp/verify-sim-s100.log 2>&1 ) & SIM_S100_PID=$!; PIDS+=($!)
( cd "$ROOT/labos-kernel" && exec java -cp "out:lib/postgresql-42.7.3.jar:lib/spring/*" com.labos.kernel.sim.DeviceSimulator \
    --model aspin-2k --device "$ASPIN_INST" --code ASPINV --port "$SIM_ASPIN" --delay 1 \
    > /tmp/verify-sim-aspin.log 2>&1 ) & PIDS+=($!)
sleep 1.5
( exec dotnet "$SIDECAR_DLL" \
    --model s100 --transport sim --sim-port "$SIM_S100" --port "$SIDE_S100" --device "$S100_INST" \
    > /tmp/verify-sidecar-s100.log 2>&1 ) & PIDS+=($!)
( exec dotnet "$SIDECAR_DLL" \
    --model aspin-2k --transport sim --sim-port "$SIM_ASPIN" --port "$SIDE_ASPIN" --device "$ASPIN_INST" \
    > /tmp/verify-sidecar-aspin.log 2>&1 ) & PIDS+=($!)
sleep 4
for pair in "模拟器(S100):$SIM_S100" "sidecar(S100):$SIDE_S100" \
            "模拟器(ASPin):$SIM_ASPIN" "sidecar(ASPin):$SIDE_ASPIN"; do
    name=${pair%%:*}; port=${pair##*:}
    if (echo > "/dev/tcp/127.0.0.1/$port") 2>/dev/null; then ok "$name 监听 $port"
    else bad "$name 没起来" "见 /tmp/verify-*.log"; fi
done

# 驱动注册表有 60s 缓存（REGISTRY_TTL_MS），刚绑的 driver 要等它过期
echo "  等驱动注册表刷新（60s 缓存）…"
sleep 62

# ---------------------------------------------------------------- 5. 探测
step "5. 走中控 /api/shadow/probe 发命令"
probe() {  # probe <rid> <method> [params-json]
    local rid=$1 m=$2 p=${3:-}
    local body='{}'
    [[ -n "$p" ]] && body="{\"params\":$p}"
    api POST "/api/shadow/probe?resourceId=$rid&method=$m" "$body"
}

# 断言：ok / errorCode
expect() {  # expect <描述> <json> <ok:true|false> [期望错误码]
    local label=$1 json=$2 wantOk=$3 wantCode=${4:-}
    local gotOk gotCode
    gotOk=$(echo "$json" | python3 -c 'import sys,json;print(str(json.load(sys.stdin).get("ok")).lower())' 2>/dev/null)
    gotCode=$(echo "$json" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("errorCode") or "")' 2>/dev/null)
    if [[ "$gotOk" == "$wantOk" ]] && { [[ -z "$wantCode" ]] || [[ "$gotCode" == "$wantCode" ]]; }; then
        ok "$label${gotCode:+ → $gotCode}"
    else
        bad "$label" "期望 ok=$wantOk code=${wantCode:-任意}，实际 ok=$gotOk code=$gotCode"
    fi
}

# S100：托盘原点 → 封膜 → 移走 → 封膜被拒（0x8000001A → EC-08-401A）
expect "S100 托盘回原点"        "$(probe "$S100_RID" MoveTray '{"value":0}')" true
expect "S100 原点封膜"          "$(probe "$S100_RID" Seal)" true
expect "S100 托盘移到 10000"    "$(probe "$S100_RID" MoveTray '{"value":10000}')" true
expect "S100 不在原点封膜被拒"  "$(probe "$S100_RID" Seal)" false "EC-08-401A"
expect "S100 回原点后能封膜"    "$(probe "$S100_RID" MoveTray '{"value":0}')" true
expect "S100 再次封膜"          "$(probe "$S100_RID" Seal)" true

# ASPin：建模块 → 初始化 → 离心 → 查转速
expect "ASPin 建模块实例"       "$(probe "$ASPIN_RID" CreateModules)" true
expect "ASPin 初始化"           "$(probe "$ASPIN_RID" Home)" true
expect "ASPin 启动离心"         "$(probe "$ASPIN_RID" RunProgram '{"time":60,"speed":1500,"ramp":150}')" true
expect "ASPin 查当前转速"       "$(probe "$ASPIN_RID" CurrSpeed)" true

# ---------------------------------------------------------------- 6. 多节点续跑
step "6. 多节点续跑（3 步流程，第 2 步停住，换纸后继续）"

# 重启 S100 模拟器（第 4 步起的那个），日志清空——后面要数"续跑时发了几条命令"
restart_sim_s100() {  # $1 = 额外参数（如 "--fail 1 --fail-from 2 …"）
    kill "$SIM_S100_PID" 2>/dev/null
    sleep 1
    # shellcheck disable=SC2086
    ( cd "$ROOT/labos-kernel" && exec java -cp "out:lib/postgresql-42.7.3.jar:lib/spring/*" com.labos.kernel.sim.DeviceSimulator \
        --model s100 --device "$S100_INST" --code S100V --port "$SIM_S100" --delay 1 $1 \
        > /tmp/verify-sim-s100.log 2>&1 ) & SIM_S100_PID=$!; PIDS+=($!)
    sleep 2
}

WF3=$(q "SELECT id FROM workflow WHERE tenant_id=1 AND name='S100 三步续跑验证' ORDER BY id DESC LIMIT 1")
if [[ -z "$WF3" ]]; then
    resp=$(api POST /api/choreography \
        "{\"name\":\"S100 三步续跑验证\",\"steps\":[{\"resourceId\":$S100_RID,\"method\":\"Seal\"},{\"resourceId\":$S100_RID,\"method\":\"Seal\"},{\"resourceId\":$S100_RID,\"method\":\"Seal\"}]}")
    WF3=$(echo "$resp" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("workflowId",""))' 2>/dev/null)
fi
[[ -n "$WF3" ]] && ok "三步流程 id=$WF3" || { bad "建三步流程失败"; WF3=""; }

if [[ -n "$WF3" ]]; then
    # 第 2 条动作起报"无纸"（MANUAL，不重试）→ 第 2 步停住，第 3 步不执行
    restart_sim_s100 "--fail 1 --fail-from 2 --fail-method Seal --fail-code 0x8000000C"
    api POST "/api/workflows/$WF3/run?async=1" >/dev/null
    sleep 8
    RID=$(q "SELECT max(id) FROM run")
    st1=$(q "SELECT status FROM run_node WHERE run_id=$RID AND node_key='N1' AND level=0")
    st2=$(q "SELECT status FROM run_node WHERE run_id=$RID AND node_key='N2' AND level=0")
    st3=$(q "SELECT status FROM run_node WHERE run_id=$RID AND node_key='N3' AND level=0")
    [[ "$st1" == "completed" ]] && ok "第 1 步完成" || bad "第 1 步状态不对" "$st1"
    [[ "$st2" == "manual" ]] && ok "第 2 步转人工（无纸）" || bad "第 2 步状态不对" "$st2"
    [[ "$st3" != "completed" ]] && ok "第 3 步没执行（图停在人工处）" || bad "第 3 步不该完成"
    [[ "$(q "SELECT status FROM run WHERE id=$RID")" == "paused" ]] && ok "整单暂停" || bad "整单没暂停"

    # 换纸：干净重启 → 点继续
    restart_sim_s100 ""
    api POST "/api/runs/$RID/resume?action=continue" >/dev/null
    sleep 10
    [[ "$(q "SELECT status FROM run WHERE id=$RID")" == "completed" ]] && ok "继续后整单完成" \
        || bad "继续后没完成" "$(q "SELECT status FROM run WHERE id=$RID")"
    [[ "$(q "SELECT count(*) FROM run_node WHERE run_id=$RID AND level=0 AND status='completed'")" == "3" ]] \
        && ok "三个节点都完成" || bad "节点没全完成"

    # 关键断言：续跑只该发 N2、N3 两条；N1 已完成，绝不能再发一次
    seals=$(grep -c "收到 Seal" /tmp/verify-sim-s100.log)
    [[ "$seals" == "2" ]] && ok "续跑只发了 2 条命令（N1 没重复执行）" \
        || bad "续跑发了 $seals 条命令，应该 2 条"

    # **审计记录不能丢**（评审 20261103-01 · H1）。
    # 上面那条只看"命令发出去了没"，不看"落库了没" —— 曾经续跑时步骤行撞
    # uk_run_node、异常被 catch 成一条 WARN，于是这一段执行在库里完全没有记录，
    # 而所有断言依然全绿。这里补上：步骤行与命令行都要跟着涨。
    steps=$(q "SELECT count(*) FROM run_node WHERE run_id=$RID AND level=1")
    cmds=$(q "SELECT count(*) FROM run_command WHERE run_id=$RID")
    [[ "$steps" == "3" ]] && ok "步骤行 3 条（S1 保留 + N2/N3 各 1）" \
        || bad "步骤行=$steps，应 3 条（审计记录可能丢失）"
    [[ "$cmds" == "3" ]] && ok "命令行 3 条（1 暂停前 + 2 续跑）" \
        || bad "命令行=$cmds，应 3 条（续跑的命令没落库）"
    # 注意 grep -c 在 0 匹配时退出码是 1，直接 `|| echo 0` 会拼成 "0\n0" —— 断言会假失败
    # ⚠ 只查**本次运行新增**的日志行，不能 grep 整个累积日志：
    #   /tmp/labos-api.log 是长期累积的，一旦历史上出现过一次 duplicate key，
    #   这条断言就**永远红**（2026-11-06 实测：日志里 7 条历史记录导致门禁持续失败）。
    dup=$(tail -n "+$((LOG_BASE_LINE + 1))" /tmp/labos-api.log 2>/dev/null \
          | grep -c "步骤落库失败" || true)
    dup=${dup:-0}
    [[ "$dup" == "0" ]] && ok "没有 uk_run_node 冲突（本次运行新增 $dup 条）" \
        || bad "本次运行日志里有 $dup 条步骤落库失败（duplicate key）"
fi

# ---------------------------------------------------------------- 7. 策略
step "7. 回查库里的恢复策略（错误码不是白翻译的）"
policy_of() { q "SELECT p.scope || '|' || p.strategy || '|' || p.max_attempts
                 FROM recovery_policy p JOIN error_code e ON e.id = p.error_code_id
                WHERE e.tenant_id = 1 AND e.code = '$1' LIMIT 1"; }
for code in EC-08-401A EC-08-400C EC-08-4001; do
    real=$(policy_of "$code")
    if [[ -n "$real" ]]; then ok "$code 有策略：$real"; else bad "$code 库里没有策略"; fi
done

[[ "$(policy_of EC-08-401A)" == node\|manual\|* ]] && ok "托盘不在原点 → 人工（不是盲目重试）" \
    || bad "托盘不在原点的策略不对" "$(policy_of EC-08-401A)"

# ============================================================================
# 9. 节点绑定与图 XML 一致性（M9 前置：resource_id / seq 已落表）
# ============================================================================
step "9. 节点绑定表与图 XML 一致"

if ( cd "$ROOT/labos-kernel" && java -cp "out:lib/postgresql-42.7.3.jar:lib/spring/*" \
        com.labos.kernel.workflow.BindingBackfill --check ) > /tmp/verify-bind.log 2>&1; then
    ok "全库 workflow_node.resource_id/seq 与图 XML 一致"
else
    bad "存在不一致（前 5 行）："
    grep '✗' /tmp/verify-bind.log | head -5
fi

# ============================================================================
# 10. 保存实验编排的防护（2026-11-03 踩过：参数传错 → 编排被静默清空）
#
# 事故经过：前端把 workflowIds 放 body、后端只读 query → 解析成空数组 →
# 某实验的两条流程被删光，而接口返回 200。
# 后续方案升级（见 docs/设计-实验编排统一-*）：这条写路径**整个只读** ——
#   真源是画布编译，保存图时同步落 experiment_process；本接口只读，写一律 409。
# 断言：无论怎么传参（缺参 / 非法 id / 不存在的 id），都必须 409 且条数不变。
# ============================================================================
step "10. 实验编排表只读（改编排一律 409，且不清空）"

EID=$(q "SELECT experiment_id FROM experiment_process
          GROUP BY 1 HAVING count(*) > 0 ORDER BY 1 DESC LIMIT 1")
VID=$(q "SELECT id FROM workflow WHERE tenant_id = 1 AND deleted_at IS NULL ORDER BY id DESC LIMIT 1")

if [[ -z "$EID" ]]; then
    bad "库里没有可用的实验编排样本，无法验证防护"
else
    BEFORE=$(q "SELECT count(*) FROM experiment_process WHERE experiment_id = $EID")

    code=$(curl -s -o /tmp/orch-noparam.json -w '%{http_code}' -X POST \
        "$API/api/experiments/$EID/processes" -H "Authorization: Bearer $TOKEN")
    after=$(q "SELECT count(*) FROM experiment_process WHERE experiment_id = $EID")
    [[ "$code" == "409" && "$after" == "$BEFORE" ]] \
        && ok "缺 workflowIds → 409（只读镜像），编排未被清空（${BEFORE} 条）" \
        || bad "缺参数没被拦住" "HTTP ${code}，条数 ${BEFORE}→${after}"

    code=$(curl -s -o /tmp/orch-badid.json -w '%{http_code}' -X POST \
        "$API/api/experiments/$EID/processes?workflowIds=-1" -H "Authorization: Bearer $TOKEN")
    after=$(q "SELECT count(*) FROM experiment_process WHERE experiment_id = $EID")
    [[ "$code" == "409" && "$after" == "$BEFORE" ]] \
        && ok "非法流程 id（-1，哨兵值）→ 409，编排未被清空" \
        || bad "非法 id 没被拦住" "HTTP ${code}，条数 ${BEFORE}→${after}"

    if [[ -n "$VID" ]]; then
        code=$(curl -s -o /tmp/orch-missing.json -w '%{http_code}' -X POST \
            "$API/api/experiments/$EID/processes?workflowIds=$VID,999999" \
            -H "Authorization: Bearer $TOKEN")
        after=$(q "SELECT count(*) FROM experiment_process WHERE experiment_id = $EID")
        [[ "$code" == "409" && "$after" == "$BEFORE" ]] \
            && ok "引用不存在的流程 → 409（只读，删除风险已消除），编排未被清空" \
            || bad "不存在的 id 没被拦住" "HTTP ${code}，条数 ${BEFORE}→${after}"
    else
        bad "库里没有流程样本，跳过「不存在的 id」这条"
    fi
fi

step "结果"
echo "  设备池实例：S100 resourceId=$S100_RID / ASPIN_2K resourceId=${ASPIN_RID}（已留在岛上，可在界面看到）"
echo "  驱动端点：DRV-SIDECAR-S100 → :${SIDE_S100}、DRV-SIDECAR-ASPIN → :$SIDE_ASPIN"
echo
echo "──────────────────────────────────────────"
echo "通过 $PASS / 失败 $FAIL"
echo "──────────────────────────────────────────"
[[ $FAIL -eq 0 ]]
