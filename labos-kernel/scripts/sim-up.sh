#!/bin/bash
# =============================================================================
# 一键起"每台设备一个模拟器"
#
# 做什么：
#   1. 从库里查出某座岛的所有设备
#   2. 为每台设备分配一个端口（19500 起）
#   3. 在库里建对应的 driver 记录，并把 device_instance.driver_id 指过去
#   4. 起 N 个 DeviceSimulator 进程
#
# 为什么要这样：
#   真实的实验室里每台设备是独立硬件，有独立的响应速度和故障模式。
#   一个进程模拟所有设备，测不出并发问题、也测不出"某台设备慢"的影响。
#
# 用法：
#   ./scripts/sim-up.sh                     # 用默认岛（端口模式：127.0.0.1 + 不同端口）
#   ./scripts/sim-up.sh LAB-DEMO-02         # 指定岛编号
#   ./scripts/sim-up.sh LAB-DEMO-02 300     # 指定每台模拟耗时（0=用设备类型默认）
#   ./scripts/sim-up.sh LAB-DEMO-02 0 fast   # 门禁快速档：每步 2–5 秒（T1/T2 用）
#   ./scripts/sim-up.sh LAB-DEMO-02 0 pause  # 暂停观测档：每步 8–15 秒（4.16–4.18 用）
#   ./scripts/sim-up.sh LAB-DEMO-02 0 slow   # 真机时序档：30–45 秒（默认慢档，仅压测/现场用）
#   ./scripts/sim-up.sh LAB-DEMO-02 0 30000:300000
#                                        # 也可自定义区间（毫秒，MIN:MAX[:SEED]）
#
#   # 多 IP 模式：每台设备一个独立 IP、**同一端口**，最接近真实现场
#   ./scripts/sim-up.sh --setup-ip 20       # 先打印加 IP 的命令（需 sudo，跑一次）
#   SIM_IP_MODE=ip ./scripts/sim-up.sh      # 再启动
#
#   # 起**桌面窗口**版（每台设备一个窗口，能看状态/改IP/看日志）
#   SIM_GUI=1 ./scripts/sim-up.sh LAB-DEMO-02
#
#   ./scripts/sim-down.sh                   # 全部停掉
#
# 两种组网方式的区别：
#   端口模式 —— 127.0.0.1:19500/19501/...  一台机器上跑得通，但中控只按端口区分设备，
#               测不出"IP 配错""跨网段"这类问题
#   多 IP 模式 —— 127.0.0.2:19500 / 127.0.0.3:19500 ... 每台设备独立地址，
#               和真实现场一致；中控的 endpoint（tcp://IP:PORT）也会按真地址写
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

IP_MODE="${SIM_IP_MODE:-port}"          # port | ip
IP_BASE="${SIM_IP_BASE:-127.0.0.2}"     # 多 IP 模式的起始地址
BASE_PORT="${SIM_BASE_PORT:-19500}"

# --setup-ip N：只打印加 IP 的命令，不启动
if [[ "${1:-}" == "--setup-ip" ]]; then
    N="${2:-20}"
    echo "为模拟器准备 $N 个环回地址（需要 sudo，跑一次即可）："
    echo
    base_last="${IP_BASE##*.}"
    base_prefix="${IP_BASE%.*}"
    if [[ "$(uname)" == "Darwin" ]]; then
        for i in $(seq 0 $((N - 1))); do
            echo "  sudo ifconfig lo0 alias ${base_prefix}.$((base_last + i)) up"
        done
    else
        for i in $(seq 0 $((N - 1))); do
            echo "  sudo ip addr add ${base_prefix}.$((base_last + i))/8 dev lo"
        done
    fi
    echo
    echo "然后跑：SIM_IP_MODE=ip ./scripts/sim-up.sh"
    echo "验证：  ping -c1 ${base_prefix}.${base_last}"
    exit 0
fi

ISLAND_CODE="${1:-LAB-DEMO-02}"
DELAY_MS="${2:-0}"     # 0 = 让模拟器按设备类型用默认耗时
# 第三个参数：随机耗时区间（毫秒，MIN:MAX[:SEED]）。空 = 不随机。
# 为什么需要：默认耗时只有几百毫秒，命令早回来了 —— 暂停信号根本来不及
# 在「设备正在执行」时到达，测不出动作中间的暂停/续跑语义。
RAND_DELAY="${3:-}"
# 预设档位：门禁用 fast/pause，不再让每个设备动作等 30–45 秒。
case "${RAND_DELAY}" in
    fast)  RAND_DELAY="2000:5000" ;;
    pause) RAND_DELAY="8000:15000" ;;
    slow)  RAND_DELAY="30000:45000" ;;
esac

: "${LABOS_DB_HOST:?请设置 LABOS_DB_HOST}"
: "${LABOS_DB_NAME:?请设置 LABOS_DB_NAME}"
: "${LABOS_DB_USER:?请设置 LABOS_DB_USER}"
: "${LABOS_DB_PASS:?请设置 LABOS_DB_PASS}"

PSQL=$(command -v psql || true)
[[ -z "${PSQL}" && -x /opt/homebrew/opt/libpq/bin/psql ]] && PSQL=/opt/homebrew/opt/libpq/bin/psql
[[ -n "${PSQL}" ]] || { echo "找不到 psql"; exit 1; }

q() { PGPASSWORD="${LABOS_DB_PASS}" "${PSQL}" -h "${LABOS_DB_HOST}" -U "${LABOS_DB_USER}" \
        -d "${LABOS_DB_NAME}" -tAc "$1"; }

echo "=== 岛 ${ISLAND_CODE}：查设备 ==="

# 设备列表：resource_node.id / device_instance.id / code / capability
DEVICES=$(q "
SELECT di.id || '|' || r.id || '|' || r.code || '|' ||
       COALESCE((SELECT c.code FROM resource_capability rc
                 JOIN capability c ON c.id = rc.capability_id
                 WHERE rc.resource_id = r.id LIMIT 1), 'UNKNOWN')
FROM device_instance di
JOIN resource_node r ON r.id = di.resource_id
WHERE di.deleted_at IS NULL
  AND r.parent_id = (SELECT id FROM resource_node WHERE code = '${ISLAND_CODE}')
ORDER BY r.code;
")

if [[ -z "${DEVICES}" ]]; then
    echo "岛 ${ISLAND_CODE} 下没有设备，或岛编号不对"
    exit 1
fi

# 能力 → 模拟器画像。模拟器按画像校验方法名与参数范围（见 docs/模拟器设备库.md）
capToProfile() {
    case "$1" in
        TRANSFER)      echo "robot" ;;
        ALIQUOT)       echo "liquid_handler" ;;
        CENTRIFUGE)    echo "centrifuge" ;;
        READ_PLATE)    echo "reader" ;;
        INCUBATE)      echo "incubator" ;;
        WASH)          echo "washer" ;;
        STORE)         echo "plate_hotel" ;;
        SEAL)          echo "sealer" ;;
        PEEL)          echo "peeler" ;;
        SCAN)          echo "barcode" ;;
        THERMOCYCLE)   echo "thermocycler" ;;
        MIX)           echo "shaker" ;;
        ANALYZE)       echo "reader" ;;          # 流式暂按读板机处理
        DECAP|PICK|SENSE) echo "generic" ;;      # 还没建画像
        *)             echo "generic" ;;
    esac
}

echo "=== 为每台设备分配端点并登记 driver（模式：${IP_MODE}）==="
i=0
while IFS='|' read -r DEV_ID RES_ID CODE CAP; do
    [[ -z "${DEV_ID}" ]] && continue

    if [[ "${IP_MODE}" == "ip" ]]; then
        # 多 IP：每台设备独立地址、同一端口
        base_last="${IP_BASE##*.}"
        base_prefix="${IP_BASE%.*}"
        HOST="${base_prefix}.$((base_last + i))"
        PORT="${BASE_PORT}"
    else
        HOST="127.0.0.1"
        PORT=$((BASE_PORT + i))
    fi
    PROFILE=$(capToProfile "${CAP}")

    # 建/更新一条 driver 记录（每台设备一条独立端点）
    DRV_CODE="DRV-SIM-${CODE}"
    # driver.code 没有唯一约束，先删再插（模拟驱动每次重建）
    # 先摘掉引用再删，否则违反外键（device_instance.driver_id）
    q "UPDATE device_instance SET driver_id = NULL WHERE driver_id IN
       (SELECT id FROM driver WHERE code = '${DRV_CODE}');" >/dev/null
    q "DELETE FROM driver WHERE code = '${DRV_CODE}';" >/dev/null
    q "INSERT INTO driver (tenant_id, code, name, protocol, endpoint, manifest, status)
       VALUES (1, '${DRV_CODE}', '模拟-${CODE}', 'mock', 'tcp://${HOST}:${PORT}', '{}'::jsonb, 1);" >/dev/null
    DRV_ID=$(q "SELECT id FROM driver WHERE code = '${DRV_CODE}';")

    # 设备指向这个驱动
    q "UPDATE device_instance SET driver_id = ${DRV_ID} WHERE id = ${DEV_ID};" >/dev/null

    # 起模拟器
    SIM_MAIN="com.labos.kernel.sim.DeviceSimulator"
    if [[ "${SIM_GUI:-0}" == "1" ]]; then
        SIM_MAIN="com.labos.kernel.sim.SimulatorApp"
    fi

    RAND_ARGS=()
    if [[ -n "${RAND_DELAY}" ]]; then
        RAND_ARGS=(--rand-delay "${RAND_DELAY}")
    fi
    nohup java -cp "out:lib/postgresql-42.7.3.jar:lib/spring/*" ${SIM_MAIN} \
        --port "${PORT}" --device "${DEV_ID}" --code "${CODE}" --cap "${CAP}" \
        --profile "${PROFILE}" --delay "${DELAY_MS}" "${RAND_ARGS[@]}" \
        >> "/tmp/sim-${CODE}.log" 2>&1 &
    # 脱离当前 shell 的作业控制：否则脚本退出时某些执行环境会把子进程一起收掉
    # （表现为"启动日志有了，一会儿进程全没了，日志只剩一句模拟器退出"）
    disown 2>/dev/null || true

    printf "  %-16s 实例=%-6s %s:%-6s 能力=%-12s 类型=%s\n" \
        "${CODE}" "${DEV_ID}" "${HOST}" "${PORT}" "${CAP}" "${PROFILE}"
    i=$((i + 1))
done <<< "${DEVICES}"

echo
echo "=== 已启动 ${i} 个模拟器（${IP_MODE} 模式，$([[ "${SIM_GUI:-0}" == "1" ]] && echo 桌面窗口 || echo 无界面)）==="
if [[ "${IP_MODE}" == "port" ]]; then
    echo "  想让地址更接近现场？先 ./scripts/sim-up.sh --setup-ip ${i}，再 SIM_IP_MODE=ip 启动"
fi
echo "  日志：/tmp/sim-<设备编号>.log"
if [[ "${SIM_GUI:-0}" == "1" ]]; then
    echo "  每台设备一个窗口；窗口里可以改绑定地址、启停监听、看实时日志"
fi
echo "  停止：./scripts/sim-down.sh"
echo
echo "下一步验证："
echo "  curl -X POST 'http://localhost:18080/api/shadow/probe?resourceId=<设备resource_id>&method=Home'"
