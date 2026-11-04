#!/usr/bin/env bash
# 中控 → sidecar → 设备 的链路自检（**不需要真机**）。
#
# 链路：test-sidecar.sh 里的客户端 → Labos.Sidecar(:18501) → Java 模拟器(:19501)
#
# 为什么值得单独跑：sidecar 是"中控唯一认的端点"，它要是把参数转错、
# 或者把错误码吞掉，中控这边只看到"设备失败"，查起来要连现场。
# 这里用模拟器顶替设备，把 sidecar 的转发与回包形状先钉死。
#
# 用法：./scripts/test-sidecar.sh          （自动起停，退出码 0=通过）
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# 端口默认随机挑空闲的：写死 19501 会和"手工留着的模拟器"撞，
# 撞上之后现象极隐蔽 —— 新模拟器 bind 失败退出，sidecar 连到残留的那个，
# 而残留模拟器的幂等缓存里已经有同样的 message_id，于是"重复命令→直接回过期结果"。
SIM_PORT=${SIM_PORT:-$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()')}
SIDE_PORT=${SIDE_PORT:-$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()')}
DEVICE=${DEVICE:-501}
PASS=0
FAIL=0

ok()   { PASS=$((PASS+1)); echo "  ✓ $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  ✗ $1${2:+（$2）}"; }

cleanup() {
    # 子 shell 已用 exec 启动（PID 就是真进程）；pkill -P 再兜底一层 dotnet 的子进程，
    # 否则 kill 掉父进程后子进程会被 launchd 接管、变成孤儿模拟器/sidecar（2026-11-03 实测泄漏）。
    [[ -n "${SIM_PID:-}" ]] && { pkill -P "$SIM_PID" 2>/dev/null; kill "$SIM_PID" 2>/dev/null; }
    [[ -n "${SIDE_PID:-}" ]] && { pkill -P "$SIDE_PID" 2>/dev/null; kill "$SIDE_PID" 2>/dev/null; }
    sleep 0.2
    wait 2>/dev/null
}
trap cleanup EXIT

echo "════════ 0. 准备 ════════"
if ! command -v dotnet >/dev/null; then
    echo "  没有 dotnet，跳过（sidecar 是 .NET 程序）"
    exit 0
fi
# 版本判定：sidecar 目标框架是 net10.0，需要 SDK 10.x。
# 只判"命令在不在"是不够的 —— 有 dotnet 8/9 的机器上会走进注定编译失败的
# 路径，把"环境不满足"报成"测试失败"。不满足就明确跳过（退出码 0）。
# 用 awk 取版本号：BSD sed（macOS）不认 \+ 这个 GNU 扩展。
DOTNET_SDK_MAJOR=$(dotnet --list-sdks 2>/dev/null | awk '{print $1}' | cut -d. -f1 | sort -rn | head -1)
if [[ -z "$DOTNET_SDK_MAJOR" ]] || (( DOTNET_SDK_MAJOR < 10 )); then
    echo "  dotnet SDK 版本不满足（需要 10.x，当前 ${DOTNET_SDK_MAJOR:-无}），跳过"
    exit 0
fi

echo "  编译 sidecar…"
( cd "$ROOT/sidecar-dotnet/Labos.Sidecar" \
  && dotnet build -v q --nologo > /tmp/sidecar-build.log 2>&1 ) \
  || { echo "  编译失败，见 /tmp/sidecar-build.log"; exit 1; }
ok "sidecar 编译通过"

# 型号库必须是"从型号库导出的最新版"，否则 sidecar 会拿过期的指令表
# md5 命令 macOS 有、Linux 没有（Linux 只有 md5sum）——两处都必须降级，
# 否则 CI 上 CUR 为空 → 误判"models.json 过期" → sidecar 链路整条失败。
md5_of() {
  if command -v md5 >/dev/null 2>&1; then
    md5 -q "$1" 2>/dev/null || md5sum "$1" | cut -d' ' -f1
  else
    md5sum "$1" | cut -d' ' -f1
  fi
}
md5_of_stdin() {
  if command -v md5 >/dev/null 2>&1; then
    md5 2>/dev/null || md5sum | cut -d' ' -f1
  else
    md5sum | cut -d' ' -f1
  fi
}
CUR=$(cd "$ROOT/labos-kernel" && java -cp out com.labos.kernel.device.DeviceModelExport | md5_of_stdin)
REG=$(md5_of "$ROOT/sidecar-dotnet/models.json")
if [[ "$CUR" == "$REG" ]]; then
    ok "models.json 与型号库一致"
else
    bad "models.json 过期" "重跑：java -cp out com.labos.kernel.device.DeviceModelExport > ../sidecar-dotnet/models.json"
fi

echo
echo "════════ 1. 起模拟器 + sidecar ════════"
# exec：让 $! 就是 java 本身。写成 ( cd … && java … ) & 时 $! 是子 shell，
# kill 子 shell 不会杀 java —— 每次跑完都留一个模拟器孤儿（2026-11-03 清出 150 个进程时定位到）。
( cd "$ROOT/labos-kernel" \
  && exec java -cp out com.labos.kernel.sim.DeviceSimulator \
       --model s100 --device "$DEVICE" --code S100T --port "$SIM_PORT" --delay 1 \
       > /tmp/sidecar-sim.log 2>&1 ) &
SIM_PID=$!
sleep 1.5
if ! (echo > "/dev/tcp/127.0.0.1/$SIM_PORT") 2>/dev/null; then
    bad "模拟器没起来（端口 $SIM_PORT 可能被占）" "见 /tmp/sidecar-sim.log"
    exit 1
fi
ok "模拟器监听 $SIM_PORT"

# 直接跑构建产物，不用 dotnet run：dotnet run 会再起一个子进程（真 sidecar），
# kill 掉 dotnet 后子进程继续活着 —— 这正是泄漏的另一半。产物在上面的 dotnet build 里已生成。
# 用 'dotnet <dll>' 而不是 apphost 二进制：apphost 依赖 DOTNET_ROOT/固定安装路径，
# 本机 Homebrew dotnet 会报 "You must install .NET"；而 'dotnet xxx.dll' 由宿主直接加载、
# 不另起子进程，kill $! 就是杀真进程（配合上面的 exec 彻底不漏）。
( exec dotnet "$ROOT/sidecar-dotnet/Labos.Sidecar/bin/Debug/net10.0/Labos.Sidecar.dll" \
       --model s100 --transport sim \
       --sim-host 127.0.0.1 --sim-port "$SIM_PORT" --port "$SIDE_PORT" --device "$DEVICE" \
       > /tmp/sidecar-run.log 2>&1 ) &
SIDE_PID=$!
sleep 3

if (echo > "/dev/tcp/127.0.0.1/$SIDE_PORT") 2>/dev/null; then
    ok "sidecar 监听 $SIDE_PORT"
else
    bad "sidecar 没起来" "见 /tmp/sidecar-run.log"
    exit 1
fi

echo
echo "════════ 2. 按手册时序跑一遍 ════════"
python3 - "$SIDE_PORT" "$DEVICE" <<'PY'
import json, os, socket, sys

port, dev = int(sys.argv[1]), sys.argv[2]
RUN = os.urandom(2).hex()   # 模拟器按 message_id 幂等，跨进程复用同一批 id 会拿到上一次的回包
SEP = "#!LABSEP!#"
ok = fail = 0

s = socket.create_connection(("127.0.0.1", port), timeout=10)
s.settimeout(20)

def call(mid, method, params=None):
    msg = {"message_id": f"{RUN}-{mid}", "message_type": 1, "method": method,
           "equipment_id": dev, "parameters": params or {}}
    s.sendall((json.dumps(msg) + SEP).encode())
    buf = b""
    while SEP.encode() not in buf:
        chunk = s.recv(4096)
        if not chunk:
            raise RuntimeError("连接断了")
        buf += chunk
    r = json.loads(buf.decode().split(SEP)[0])
    err = (r.get("error") or {})
    return r.get("method_status"), err.get("error_code"), r.get("data") or {}

def check(name, got, want_status, want_code=None):
    global ok, fail
    st, code, _ = got
    if st == want_status and (want_code is None or code == want_code):
        ok += 1
        print(f"  ✓ {name}" + (f" → {code}" if code else ""))
    else:
        fail += 1
        print(f"  ✗ {name}：期望 status={want_status} code={want_code}，实际 {st}/{code}")

check("托盘回原点", call("1", "MoveTray", {"value": "0"}), 2)
check("原点封膜", call("2", "Seal"), 2)
check("托盘移走", call("3", "MoveTray", {"value": "10000"}), 2)
check("不在原点封膜被拒", call("4", "Seal"), 3, "0x8000001A")
check("板型越界先报参数错", call("5", "SealWithType", {"plateType": "9"}), 3, "0x80000003")
check("回原点", call("6", "MoveTray", {"value": "0"}), 2)
check("改板型", call("7", "SealWithType", {"plateType": "3"}), 2)
check("查板型", call("8", "GetPlateType"), 2)
check("型号外指令被拒", call("9", "FooBar"), 3, "EC-06-0004")
check("查询状态", call("10", "GetStatus"), 2)
s.close()
sys.exit(0 if fail == 0 else 1)
PY
RC=$?
if [[ $RC -eq 0 ]]; then ok "链路断言全过"; else bad "链路断言有失败"; fi

echo
echo "════════ 3. 命令行工具 ════════"
( cd "$ROOT/sidecar-dotnet/Labos.Sidecar" && dotnet run --no-build -- --list > /tmp/sidecar-list.log 2>&1 )
grep -q "s100" /tmp/sidecar-list.log && ok "--list 能列出型号" || bad "--list 输出异常"

( cd "$ROOT/sidecar-dotnet/Labos.Sidecar" \
  && dotnet run --no-build -- --model s100 --transport sim \
       --sim-port "$SIM_PORT" --device "$DEVICE" --once "MoveTray{\"value\":0}" \
       > /tmp/sidecar-once.log 2>&1 )
grep -q "^OK" /tmp/sidecar-once.log && ok "--once 单跑一条" || bad "--once 失败" "$(tail -1 /tmp/sidecar-once.log)"

echo
echo "──────────────────────────────────────────"
echo "通过 $PASS / 失败 $FAIL"
echo "──────────────────────────────────────────"
[[ $FAIL -eq 0 ]]
