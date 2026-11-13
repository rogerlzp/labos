#!/usr/bin/env python3
"""把 LabOS 作业 JSON 逐条下发给常驻的 PyLabRobot 仿真器。

用法：先在另一个终端用 `run_simulator.py --tcp-port 19501 --browser` 启动服务，
再用本脚本下发流程；每一步都走真实的 TCP/JSON 帧，浏览器里可同步看到台面变化。
"""

from __future__ import annotations

import argparse
import json
import socket
import sys
import uuid
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SEP = b"#!LABSEP!#"


def read_frame(sock: socket.socket, buf: bytes) -> tuple[dict, bytes]:
    while SEP not in buf:
        chunk = sock.recv(4096)
        if not chunk:
            raise ConnectionError("仿真服务已关闭连接")
        buf += chunk
    raw, buf = buf.split(SEP, 1)
    return json.loads(raw.decode("utf-8")), buf


def main() -> int:
    p = argparse.ArgumentParser(description="向常驻仿真器下发一条流程")
    p.add_argument("--job", default="jobs/dilution.json", help="流程 JSON（相对本目录）")
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=19501)
    p.add_argument("--device", default=None, help="覆盖流程里的 equipment_id")
    p.add_argument("--timeout", type=float, default=10.0)
    p.add_argument("--keep-going", action="store_true", help="某步失败后继续下发后续命令")
    p.add_argument("--allow-stop", action="store_true", help="允许把 Stop 发给常驻服务（默认跳过）")
    p.add_argument("--out", default=None, help="报告路径（相对本目录）")
    args = p.parse_args()

    job_path = Path(args.job)
    if not job_path.is_absolute():
        job_path = ROOT / job_path
    job = json.loads(job_path.read_text(encoding="utf-8"))

    # command_id 是 LabOS 的幂等键：每次运行加唯一前缀，重复跑同一流程才会真正再执行一遍
    run_id = f"{datetime.now():%Y%m%d_%H%M%S}-{uuid.uuid4().hex[:6]}"
    device = args.device or job.get("equipment_id") or "LH-SIM-01"
    trace_id = job.get("trace_id") or run_id

    sock = socket.create_connection((args.host, args.port), timeout=args.timeout)
    sock.settimeout(args.timeout)
    buf = b""
    steps: list[dict] = []
    final: dict = {}
    try:
        for i, c in enumerate(job["commands"], 1):
            method = str(c.get("method") or "")
            if method == "Stop" and not args.allow_stop:
                print(f"  [skipped ] 第{i}步 Stop：常驻服务停机后不再接收流程（要停机加 --allow-stop）")
                continue
            req = {
                "message_id": f"{run_id}-{c.get('message_id') or i}",
                "message_type": 1,
                "method": method,
                "equipment_id": c.get("equipment_id") or device,
                "parameters": c.get("parameters", c.get("params", {})),
                "trace_id": c.get("trace_id") or trace_id,
            }
            sock.sendall(json.dumps(req, ensure_ascii=False).encode("utf-8") + SEP)
            frame, buf = read_frame(sock, buf)
            ok = frame.get("method_status") == 2
            err = frame.get("error") or {}
            status = "completed" if ok else "failed"
            steps.append({
                "command_id": req["message_id"], "method": method, "status": status,
                "error_code": err.get("error_code"), "error_text": err.get("error_text"),
                "data": frame.get("data", {}), "device_raw_response": frame,
            })
            print(f"  [{status:<9}] {method:<10} {(err.get('error_text') or '')[:64]}")
            if not ok and not args.keep_going:
                break

        status_req = {
            "message_id": f"{run_id}-final-status", "message_type": 1,
            "method": "GetStatus", "equipment_id": device,
            "parameters": {}, "trace_id": trace_id,
        }
        sock.sendall(json.dumps(status_req, ensure_ascii=False).encode("utf-8") + SEP)
        frame, buf = read_frame(sock, buf)
        final = frame.get("data", {})
    finally:
        sock.close()

    status = "completed" if steps and all(s["status"] == "completed" for s in steps) else "failed"
    report = {
        "run_id": run_id, "job_id": job.get("job_id"), "mode": "tcp",
        "device_id": device, "status": status, "steps": steps, "final_state": final,
    }
    out_path = ROOT / (args.out or f"out/tcp_job_{run_id}.json")
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n流程结论: {status} | 末态液位: {final.get('wells_ul', {})}")
    print(f"报告: {out_path}")
    return 0 if status == "completed" else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
