#!/usr/bin/env bash
# 设备主动上报事件 · 端到端链路自检（**不需要真机**）。
#
# 链路：模拟器（会主动推帧） → sidecar（转发 + 翻译） → 内核入站端点
#
# 为什么值得单独跑：这条链跨了三种语言、两套协议和一个"判据"——
#   ① 模拟器能不能推**没有 message_id** 的帧；
#   ② sidecar 能不能把它和"回包"分开（判据漂移一次，后果是"事件被当回包丢掉"）；
#   ③ sidecar 能不能翻译成中控认的统一形状 POST 上去。
# 任一环断了，现场只看到"流程卡住等不到事件"，极难定位。
#
# 中控那端用**桩服务**顶替（只实现 GET /api/devices/registry 与 POST /api/device-events），
# 把收到的 body 落盘 ⇒ 断言"中控确实收到了这个事件、且形状对"。
#
# 用法：./scripts/test-sidecar-device-event.sh    （自动起停，退出码 0=通过）
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0
FAIL=0

ok()   { PASS=$((PASS+1)); echo "  ✓ $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  ✘ $1${2:+（$2）}"; }

# 端口随机挑空闲的：写死端口会和"手工留着的模拟器"撞，
# 撞上之后现象极隐蔽（连到残留的那个，回包全是旧的）。
free_port() { python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()'; }

SIM_PORT=${SIM_PORT:-$(free_port)}
SIDE_PORT=${SIDE_PORT:-$(free_port)}
STUB_PORT=${STUB_PORT:-$(free_port)}
DEVICE=${DEVICE:-501}
CODE=${CODE:-S100T}
# ⚠ macOS 的 mktemp 要求 X 必须在**结尾**（BSD 与 GNU 不一样）——
# 写成 .XXXXXX.jsonl 会得到空路径，桩服务写文件时直接 FileNotFoundError。
EVENTS_FILE=$(mktemp /tmp/labos-device-events.XXXXXX)
OUTBOX_DIR=$(mktemp -d /tmp/labos-device-outbox.XXXXXX)
STUB_PID=""

cleanup() {
    [[ -n "${SIM_PID:-}"  ]] && { pkill -P "$SIM_PID"  2>/dev/null; kill "$SIM_PID"  2>/dev/null; }
    [[ -n "${SIDE_PID:-}" ]] && { pkill -P "$SIDE_PID" 2>/dev/null; kill "$SIDE_PID" 2>/dev/null; }
    [[ -n "$STUB_PID"     ]] && { pkill -P "$STUB_PID" 2>/dev/null; kill "$STUB_PID" 2>/dev/null; }
    sleep 0.2
    wait 2>/dev/null
    rm -f "$EVENTS_FILE"
    rm -rf "$OUTBOX_DIR"
}
trap cleanup EXIT

echo "════════ 0. 准备 ════════"
if ! command -v dotnet >/dev/null 2>&1; then
    echo "  没有 dotnet，跳过（sidecar 是 .NET 程序）"
    exit 0
fi
DOTNET_SDK_MAJOR=$(dotnet --list-sdks 2>/dev/null | awk '{print $1}' | cut -d. -f1 | sort -rn | head -1)
if [[ -z "$DOTNET_SDK_MAJOR" ]] || (( DOTNET_SDK_MAJOR < 10 )); then
    echo "  dotnet SDK 版本不满足（需要 10.x，当前 ${DOTNET_SDK_MAJOR:-无}），跳过"
    exit 0
fi

echo "  编译 sidecar…"
( cd "$ROOT/sidecar-dotnet/Labos.Sidecar" \
  && dotnet build -v q --nologo > /tmp/sidecar-devbuild.log 2>&1 ) \
  || { echo "  编译失败，见 /tmp/sidecar-devbuild.log"; exit 1; }
ok "sidecar 编译通过"

echo "  编译内核（模拟器在里面）…"
( cd "$ROOT" && bash scripts/build-kernel.sh > /tmp/kernel-devbuild.log 2>&1 ) \
  || { echo "  编译失败，见 /tmp/kernel-devbuild.log"; exit 1; }
ok "内核编译通过"

echo
echo "════════ 1. 起桩服务（顶替中控的入站端点）════════"
python3 - "$STUB_PORT" "$EVENTS_FILE" <<'PYEOF' > /tmp/labos-stub.log 2>&1 &
import http.server, json, sys
port, out = int(sys.argv[1]), sys.argv[2]
class H(http.server.BaseHTTPRequestHandler):
    def _send(self, body, status=200):
        b = body.encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)
    def do_GET(self):
        # 设备清单：空清单 ⇒ sidecar 认领不到设备 ⇒ 转发时用帧里的 equipment_id
        self._send(json.dumps({"items": []}))
    def do_POST(self):
        n = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(n).decode("utf-8", "replace")
        try:
            with open(out, encoding="utf-8") as f:
                attempt = sum(1 for line in f if line.strip()) + 1
        except FileNotFoundError:
            attempt = 1
        with open(out, "a", encoding="utf-8") as f:
            f.write(raw + "\n")
        # 第一次明确失败：sidecar 必须保留 outbox，并以同一 sourceEventId 补发。
        status = 500 if attempt == 1 else 200
        self._send(json.dumps({"ok": status == 200}), status)
    def log_message(self, *a):
        pass
http.server.ThreadingHTTPServer(("127.0.0.1", port), H).serve_forever()
PYEOF
STUB_PID=$!
sleep 1
if (echo > "/dev/tcp/127.0.0.1/$STUB_PORT") 2>/dev/null; then
    ok "桩服务监听 $STUB_PORT"
else
    bad "桩服务没起来" "见 /tmp/labos-stub.log"
    exit 1
fi

echo
echo "════════ 2. 起模拟器（会主动推帧）+ sidecar ════════"
# --spontaneous：模拟器处理完 Home 之后 300ms 主动推一帧【waiting_interaction】
( cd "$ROOT/labos-kernel" \
  && exec java -cp "out:lib/postgresql-42.7.3.jar:lib/spring/*" com.labos.kernel.sim.DeviceSimulator \
       --model s100 --device "$DEVICE" --code "$CODE" --port "$SIM_PORT" --delay 1 \
       --spontaneous "GetStatus=waiting_interaction@300" \
       > /tmp/sidecar-dev-sim.log 2>&1 ) &
SIM_PID=$!
sleep 1.5
if ! (echo > "/dev/tcp/127.0.0.1/$SIM_PORT") 2>/dev/null; then
    bad "模拟器没起来（端口 $SIM_PORT 可能被占）" "见 /tmp/sidecar-dev-sim.log"
    exit 1
fi
ok "模拟器监听 $SIM_PORT"

( exec dotnet "$ROOT/sidecar-dotnet/Labos.Sidecar/bin/Debug/net10.0/Labos.Sidecar.dll" \
       --model s100 --transport sim \
       --sim-host 127.0.0.1 --sim-port "$SIM_PORT" --port "$SIDE_PORT" --device "$DEVICE" \
       --registry-url "http://127.0.0.1:$STUB_PORT" \
       --device-event-outbox "$OUTBOX_DIR" \
       > /tmp/sidecar-dev-run.log 2>&1 ) &
SIDE_PID=$!
sleep 3
if (echo > "/dev/tcp/127.0.0.1/$SIDE_PORT") 2>/dev/null; then
    ok "sidecar 监听 $SIDE_PORT"
else
    bad "sidecar 没起来" "见 /tmp/sidecar-dev-run.log"
    exit 1
fi

echo
echo "════════ 3. 发一条命令，触发设备主动上报 ════════"
python3 - "$SIDE_PORT" "$DEVICE" <<'PYEOF'
import socket, sys, time
port, device = int(sys.argv[1]), sys.argv[2]
SEP = "#!LABSEP!#"
s = socket.create_connection(("127.0.0.1", port), timeout=10)
req = ('{"message_id":"dev-event-1","method":"GetStatus","equipment_id":"%s",'
       '"parameters":{}}' % device)
s.sendall((req + SEP).encode())
s.settimeout(10)
buf = b""
try:
    while SEP.encode() not in buf:
        d = s.recv(4096)
        if not d:
            break
        buf += d
except socket.timeout:
    pass
print(buf.split(SEP.encode())[0].decode("utf-8", "replace")[:200])
s.close()
PYEOF
ok "命令已发出（模拟器会顺手推一帧）"

# 等第一次 500 和随后补发的 2xx 都发生（不能只看到第一条失败请求就开始断言）。
for _ in $(seq 1 50); do
    [[ -s "$EVENTS_FILE" ]] && [[ $(wc -l < "$EVENTS_FILE") -ge 2 ]] && break
    sleep 0.2
done

echo
echo "════════ 4. 断言 ════════"
if [[ ! -s "$EVENTS_FILE" ]]; then
    bad "中控入站端点没收到任何设备事件" "见 /tmp/sidecar-dev-run.log / /tmp/sidecar-dev-sim.log"
else
    ok "中控入站端点收到了设备事件"
    python3 - "$EVENTS_FILE" "$CODE" <<'PYEOF'
import json, sys
path, code = sys.argv[1], sys.argv[2]
rows = [json.loads(l) for l in open(path, encoding="utf-8") if l.strip()]
bad = 0
if len(rows) < 2:
    print("  ✘ 第一次 HTTP 500 后没有补发成功，尝试次数：%d" % len(rows))
    sys.exit(1)
ev = rows[1]
if ev.get("device") != code:
    print("  ✘ 设备码不对：%r（期望 %r）" % (ev.get("device"), code)); bad += 1
else:
    print("  ✔ 设备码对：%s" % code)
if ev.get("eventType") != "waiting_interaction":
    print("  ✘ 事件类型不对：%r" % ev.get("eventType")); bad += 1
else:
    print("  ✔ 事件类型对：waiting_interaction")
if not isinstance(ev.get("payload"), dict):
    print("  ✘ 载荷不是对象：%r" % ev.get("payload")); bad += 1
else:
    print("  ✔ 载荷是对象：%s" % json.dumps(ev.get("payload"), ensure_ascii=False))
source_id = ev.get("sourceEventId")
if not source_id or rows[0].get("sourceEventId") != source_id:
    print("  ✘ 重试没有复用同一个 sourceEventId：%r / %r" %
          (rows[0].get("sourceEventId"), source_id)); bad += 1
else:
    print("  ✔ HTTP 500 后以同一 sourceEventId 补发成功：%s" % source_id)
sys.exit(1 if bad else 0)
PYEOF
    if [[ $? -eq 0 ]]; then
        ok "事件形状与中控的入站契约一致"
    else
        bad "事件形状与中控的入站契约不一致"
    fi
fi

echo
echo "通过 $PASS, 失败 $FAIL"
[[ $FAIL -eq 0 ]] || exit 1
exit 0
