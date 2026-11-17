#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""BioTek 两台设备（`biotek-lhc` 洗板机 / `biotek-gen5` 酶标仪）的**时序自检**。

为什么要有这个脚本：型号库只能证明"方法名登记了"，证明不了
"没初始化就洗板会不会被拒"。这两台设备的坑全在**顺序**上
（LHC：未初始化→未装载→未验证；Gen5：未配置→无实验→无板→读板中冲突），
所以这里按手册顺序把它们逐条打一遍，**断言的是被拒绝**，不是被接受。

两种模式：

    python3 scripts/verify-biotek-models.py            # --sim：直连 Java 模拟器（默认）
    python3 scripts/verify-biotek-models.py --chain    # 经 sidecar 转发（验别名分发）

两种都需要 `labos-kernel/out` 已编译（`./scripts/compile-kernel.sh`）；
`--chain` 还需要 `dotnet`（会自动 `dotnet run` 起 sidecar）。

退出码：0 = 全过；1 = 有失败（打印失败项）。
"""
import argparse
import json
import socket
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
KERNEL = ROOT / "labos-kernel"
SIDECAR = ROOT / "sidecar-dotnet" / "Labos.Sidecar"
CP = "out:lib/postgresql-42.7.3.jar:lib/spring/*"
SEP = "#!LABSEP!#"

PASS = []
FAIL = []


# ---------------------------------------------------------------- 进程管理

def wait_port(port, tries=120):
    for _ in range(tries):
        try:
            socket.create_connection(("127.0.0.1", port), timeout=0.3).close()
            return True
        except OSError:
            time.sleep(0.15)
    return False


def start_sim(port, profile, model, device):
    proc = subprocess.Popen(
        ["java", "-cp", CP, "com.labos.kernel.sim.DeviceSimulator",
         "--port", str(port), "--profile", profile, "--model", model,
         "--device", device, "--code", device, "--delay", "20"],
        cwd=str(KERNEL), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if not wait_port(port):
        proc.kill()
        sys.exit(f"模拟器没起来（端口 {port}）：先跑 ./scripts/compile-kernel.sh")
    return proc


def start_sidecar(model, sim_port, listen_port, device):
    proc = subprocess.Popen(
        ["dotnet", "run", "-v", "q", "--nologo", "--",
         "--model", model, "--transport", "sim",
         "--sim-host", "127.0.0.1", "--sim-port", str(sim_port),
         "--port", str(listen_port), "--device", device],
        cwd=str(SIDECAR), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if not wait_port(listen_port):
        proc.kill()
        sys.exit(f"sidecar 没起来（端口 {listen_port}）：确认 dotnet 可用")
    return proc


# ---------------------------------------------------------------- 发命令

def call(pno, device, method, **params):
    msg = json.dumps({"message_id": f"v-{time.time()}", "message_type": 5,
                      "method": method, "equipment_id": device,
                      "parameters": {k: str(v) for k, v in params.items()}},
                     ensure_ascii=False) + SEP
    sock = socket.create_connection(("127.0.0.1", pno), timeout=60)
    try:
        sock.sendall(msg.encode())
        buf = b""
        while SEP.encode() not in buf:
            chunk = sock.recv(65536)
            if not chunk:
                break
            buf += chunk
        return json.loads(buf.decode("utf-8", "replace").split(SEP)[0])
    finally:
        sock.close()


def check(name, reply, want_status, want_code=None, want_data=None):
    """断言一条回包：状态码 + 厂商码 +（可选）data 里的字段。"""
    status = reply.get("method_status")
    err = (reply.get("error") or {}).get("error_code")
    data = reply.get("data") or {}
    ok = status == want_status
    if want_code is not None:
        ok = ok and err == want_code
    if want_data:
        ok = ok and all(str(data.get(k)) == str(v) for k, v in want_data.items())
    (PASS if ok else FAIL).append(name)
    mark = "✓" if ok else "✗"
    extra = "" if ok else f"   ← 期望 status={want_status} code={want_code} data={data}"
    print(f"  {mark} {name}：status={status} code={err}{extra}")
    return data


# ---------------------------------------------------------------- LHC

def verify_lhc(port, device):
    print("== BioTek LHC（洗板机）：手册 §4.1/§4.3 的调用顺序")
    check("未初始化就 Wash", call(port, device, "Wash"), 3, "EC-05-2204")
    check("产品名拼错（EL 406）", call(port, device, "SetProductName", productName="EL 406"),
          3, "4")
    check("SetProductName(EL406)", call(port, device, "SetProductName", productName="EL406"), 2)
    check("SetCommunications", call(port, device, "SetCommunications", port="COM3"), 2)
    check("TestCommunications", call(port, device, "TestCommunications"), 2)
    check("没装协议就 Wash", call(port, device, "Wash"), 3, "EC-05-2205")
    check("协议后缀不是 .LHC", call(port, device, "LoadProtocol", protocolPath="/tmp/x.txt"), 3, "5")
    check("LoadProtocol", call(port, device, "LoadProtocol", protocolPath="/tmp/wash96.LHC"), 2)
    check("装了没验证就 Wash", call(port, device, "Wash"), 3, "EC-05-2206")
    check("ValidateProtocol(true)", call(port, device, "ValidateProtocol", getSettingsFirst=1), 2)
    check("Wash 启动（只有 eBusy 才算成功）",
          call(port, device, "Wash"), 2, want_data={"runStatus": 3})
    check("运行中再 Wash", call(port, device, "Wash"), 3, "EC-05-2210")
    # 轮询 3 次后完成：故意让"不轮询就当成功"暴露
    for i in (1, 2):
        check(f"轮询 #{i} 仍是 eBusy", call(port, device, "GetProtocolStatus"), 2,
              want_data={"runStatus": 3})
    check("轮询 #3 变 eReady", call(port, device, "GetProtocolStatus"), 2,
          want_data={"runStatus": 1})
    check("空闲时 Abort", call(port, device, "Abort"), 3, "EC-05-2201")
    check("EL406 不支持条范围", call(port, device, "SetFirstStrip", firstStrip=1), 3, "0")
    check("GetWellCount", call(port, device, "GetWellCount"), 2, want_data={"wellCount": 96})
    check("切 50 TS", call(port, device, "SetProductName", productName="50 TS"), 2)
    check("50TS 未通讯就设条", call(port, device, "SetFirstStrip", firstStrip=1), 3, "EC-05-2204")
    check("50TS TestCommunications", call(port, device, "TestCommunications"), 2)
    check("50TS SetFirstStrip(8)", call(port, device, "SetFirstStrip", firstStrip=8), 2)
    check("50TS 条数越界（8+6>12）",
          call(port, device, "SetNumberOfStrips", numberOfStrips=6), 3, "EC-05-2207")
    check("50TS 条数合法（8+5=13→修）",
          call(port, device, "SetNumberOfStrips", numberOfStrips=4), 2)


# ---------------------------------------------------------------- Gen5

def verify_gen5(port, device):
    print("== BioTek Gen5（酶标仪）：配置 → 实验 → 进板 → 读 → 取数")
    check("未配置就 Read", call(port, device, "Read"), 3, "EC-05-2301")
    check("ConfigureUSBReader", call(port, device, "ConfigureUSBReader", readerType=16), 2)
    check("TestReaderCommunication", call(port, device, "TestReaderCommunication"), 2)
    check("没开实验就进板", call(port, device, "CarrierIn"), 3, "EC-05-2302")
    check("NewExperiment 缺路径", call(port, device, "NewExperiment"), 3, "-100")
    check("NewExperiment", call(port, device, "NewExperiment",
                               protocolPathName="DB:\\Protocols\\abs96.prt"), 2)
    check("CarrierIn", call(port, device, "CarrierIn"), 2)
    check("Read", call(port, device, "Read"), 2, want_data={"readInProgress": "True"})
    check("读板中再 Read", call(port, device, "Read"), 3, "EC-05-2303")
    for i in (1, 2):
        check(f"读板进度 #{i} 仍在读", call(port, device, "GetReadProgress"), 2,
              want_data={"readInProgress": "True"})
    check("读板进度 #3 读完", call(port, device, "GetReadProgress"), 2,
          want_data={"readInProgress": "False"})
    data = check("GetResult", call(port, device, "GetResult",
                                   protocolId="P1", dataSetName="500 nm", indexes=""), 2)
    wells = data.get("wells") or []
    ok = len(wells) == 96
    (PASS if ok else FAIL).append("GetResult 返回 96 孔")
    print(f"  {'✓' if ok else '✗'} GetResult 返回 96 孔：实际 {len(wells)}")
    check("温度越界 60°C", call(port, device, "SetTemperature", incubate=1, setPoint=60), 3, "-302")
    check("温度 37°C", call(port, device, "SetTemperature", incubate=1, setPoint=37), 2)
    check("CarrierOut", call(port, device, "CarrierOut"), 2)
    check("CloseExperiment", call(port, device, "CloseExperiment"), 2)
    check("关掉实验后再 CarrierOut", call(port, device, "CarrierOut"), 3, "EC-05-2302")


# ---------------------------------------------------------------- 主流程

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--chain", action="store_true",
                    help="经 sidecar 转发（同时验证 models.json 的别名分发）")
    ap.add_argument("--sim-port-lhc", type=int, default=19671)
    ap.add_argument("--sim-port-gen5", type=int, default=19672)
    ap.add_argument("--side-port-lhc", type=int, default=8611)
    ap.add_argument("--side-port-gen5", type=int, default=8612)
    args = ap.parse_args()

    cases = [
        ("biotek-lhc", "washer", "SIM-LHC", args.sim_port_lhc, args.side_port_lhc, verify_lhc),
        ("biotek-gen5", "reader", "SIM-GEN5", args.sim_port_gen5, args.side_port_gen5, verify_gen5),
    ]
    for model, profile, device, sim_port, side_port, fn in cases:
        procs = []
        try:
            procs.append(start_sim(sim_port, profile, model, device))
            target = sim_port
            if args.chain:
                procs.append(start_sidecar(model, sim_port, side_port, device))
                target = side_port
                print(f"  （模式：经 sidecar :{side_port} → 模拟器 :{sim_port}）")
            fn(target, device)
        finally:
            for p in reversed(procs):
                p.kill()
                p.wait()

    print(f"\n通过 {len(PASS)}，失败 {len(FAIL)}")
    if FAIL:
        print("失败项：")
        for f in FAIL:
            print("  - " + f)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
