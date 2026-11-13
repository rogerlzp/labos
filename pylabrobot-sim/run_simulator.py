#!/usr/bin/env python3
"""PyLabRobot headless 仿真器：按 LabOS 设备契约执行移液工作站命令。

物理层用 PyLabRobot 的 LiquidHandler + 无硬件后端 + 体积/枪头追踪；对外用
LabOS 的 command/progress/result 模型与 TCP/JSON（#!LABSEP!#）帧协议。
"""

from __future__ import annotations

import argparse
import asyncio
import json
import signal
import sys
import time
import uuid
from datetime import datetime
from pathlib import Path
from typing import Any

import pylabrobot
from pylabrobot.liquid_handling import LiquidHandler
from pylabrobot.liquid_handling.backends import LiquidHandlerChatterboxBackend
from pylabrobot.resources import (
    STARDeck,
    Coordinate,
    cor_96_wellplate_360uL_Fb,
    hamilton_96_tiprack_300uL,
)
from pylabrobot.resources.tip_tracker import set_tip_tracking
from pylabrobot.resources.volume_tracker import set_volume_tracking
from pylabrobot.visualizer import Visualizer

ROOT = Path(__file__).resolve().parent
SEP = "#!LABSEP!#"
SEP_B = SEP.encode("utf-8")
VOLUME_MIN, VOLUME_MAX = 0.5, 5000.0
DEVICE_PROFILE = "liquid_handler"
# labos-kernel DeviceProfile 中 liquid_handler 的方法集
PROFILE_METHODS = (
    "Home", "GetTips", "DropTips", "Aspirate", "Dispense",
    "Transfer", "Mix", "SetTemp", "GetStatus", "Stop",
)


class SimError(Exception):
    def __init__(self, code: str, text: str, status: str = "failed"):
        super().__init__(text)
        self.code = code
        self.text = text
        self.status = status


class LabOSSimulator:
    def __init__(
        self,
        device_id: str = "LH-SIM-01",
        ws_port: int = 2121,
        fs_port: int = 1337,
        open_browser: bool = False,
        no_visualizer: bool = False,
    ):
        self.device_id = device_id
        self.ws_port = ws_port
        self.fs_port = fs_port
        self.open_browser = open_browser and not no_visualizer
        self.no_visualizer = no_visualizer
        self.deck: Any = None
        self.lh: Any = None
        self.vis: Any = None
        self.src: Any = None
        self.dst: Any = None
        self.tips: Any = None
        self._ledger: dict[str, dict] = {}
        self.events: list[dict] = []
        self._seq = 0
        self._mounted: list[Any] = []
        self._tip_idx = 0
        self._stopped = False
        self._state = "idle"

    async def start(self) -> None:
        # 这两个是模块级全局开关，默认关闭；不开则吸分液后液位恒为 0，仿真无意义
        set_volume_tracking(True)
        set_tip_tracking(True)

        self.deck = STARDeck()
        if not self.no_visualizer:
            self.vis = Visualizer(
                self.deck,
                host="127.0.0.1",
                ws_port=self.ws_port,
                fs_port=self.fs_port,
                open_browser=self.open_browser,
                name="labos-pylabrobot-sim",
            )
            await self.vis.setup()

        self.lh = LiquidHandler(
            backend=LiquidHandlerChatterboxBackend(num_channels=8), deck=self.deck
        )
        await self.lh.setup()

        self.src = cor_96_wellplate_360uL_Fb(name="src_plate")
        self.dst = cor_96_wellplate_360uL_Fb(name="dst_plate")
        self.tips = hamilton_96_tiprack_300uL(name="tips_300ul")
        self.deck.assign_child_resource(self.src, location=Coordinate(100, 100, 0))
        self.deck.assign_child_resource(self.dst, location=Coordinate(250, 100, 0))
        self.deck.assign_child_resource(self.tips, location=Coordinate(400, 100, 0))
        for ident in ("A1", "A2", "A3"):
            self.src.get_well(ident).set_volume(200)

    async def close(self) -> None:
        if self.lh is not None:
            await self.lh.stop()
        if self.vis is not None and self.vis.setup_finished:
            await self.vis.stop()

    def _event(self, type_: str, status: str, command_id: str, detail: str) -> None:
        self._seq += 1
        self.events.append({
            "seq": self._seq,
            "at": datetime.now().isoformat(timespec="milliseconds"),
            "type": type_,
            "status": status,
            "command_id": command_id,
            "detail": detail,
        })

    def _frame(self, req: dict, method_status: int, error: Any, data: dict) -> str:
        # 与 labos-kernel DeviceCore.frame / sidecar Protocol.Build 逐字段一致
        return json.dumps({
            "message_id": req.get("message_id", ""),
            "message_type": 5,
            "method": req.get("method", ""),
            "equipment_id": req.get("equipment_id") or self.device_id,
            "equipment_status": 1,
            "method_status": method_status,
            "error": error,
            "data": data,
            "process_info": {},
            "timestamp": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            "checksum": "",
            "connected": 1,
        }, ensure_ascii=False, separators=(",", ":"))

    async def execute(self, request: dict) -> dict:
        req = dict(request)
        cid = str(req.get("message_id") or uuid.uuid4())
        req["message_id"] = cid
        if cid in self._ledger:
            self._event("NODE_SKIPPED", "skipped", cid, "command_id 幂等命中，返回历史结果")
            return self._ledger[cid]

        method = str(req.get("method") or "")
        self._event("NODE_STARTED", "running", cid, method)
        self._state = "running"
        started = time.perf_counter()
        status, error, outputs, message = "completed", None, {}, "ok"
        try:
            equip = str(req.get("equipment_id") or self.device_id)
            if equip != self.device_id:
                raise SimError("EC-06-0001", f"设备不属于本模拟器：{equip}")
            outputs = await self._dispatch(method, req)
        except SimError as e:
            status, error, message = e.status, {"error_code": e.code, "error_text": e.text}, e.text
        except Exception as e:
            # TooLittleLiquidError / TooLittleVolumeError 等是仿真器的物理约束，
            # 不是代码故障：转成设备侧失败并保留原始异常文本
            text = f"{type(e).__name__}: {e}"
            status, error, message = "failed", {"error_code": "EC-06-0005", "error_text": text}, text

        duration_ms = round((time.perf_counter() - started) * 1000, 2)
        self._state = "offline" if self._stopped else "idle"
        frame = self._frame(req, 2 if status == "completed" else 3, error, outputs)
        result = {
            "command_id": cid,
            "method": method,
            "status": status,
            "error_code": error["error_code"] if error else None,
            "message": message,
            "device_raw_response": frame,
            "duration_ms": duration_ms,
            "outputs": outputs,
            "trace_id": req.get("trace_id"),
            "equipment_id": req.get("equipment_id") or self.device_id,
        }
        self._ledger[cid] = result
        event_type = {"completed": "NODE_COMPLETED", "aborted": "RUN_ABORTED"}.get(status, "STEP_FAILED")
        self._event(event_type, status, cid, message)
        return result

    def _params(self, req: dict) -> dict:
        p = req.get("parameters", req.get("params", {}))
        return p if isinstance(p, dict) else {}

    def _volume(self, p: dict) -> float:
        try:
            v = float(p.get("volume", 0))
        except (TypeError, ValueError):
            raise SimError("EC-06-0005", "volume 非法") from None
        if not VOLUME_MIN <= v <= VOLUME_MAX:
            raise SimError("EC-06-0005", f"volume {v} µL 超出量程 {VOLUME_MIN}~{VOLUME_MAX} µL")
        return v

    def _resolve(self, p: dict, labware_key: str = "labware", well_key: str = "well") -> tuple[Any, str]:
        name = str(p.get(labware_key) or p.get("labware") or "")
        plate = {"src_plate": self.src, "dst_plate": self.dst}.get(name)
        if plate is None:
            raise SimError("EC-06-0005", f"板位不存在：{name}")
        ident = str(p.get(well_key) or p.get("well") or "")
        try:
            well = plate.get_well(ident)
        except Exception:
            raise SimError("EC-06-0005", f"孔位不存在：{name}/{ident}") from None
        return well, f"{name}/{ident}"

    def _require_tip(self) -> None:
        if not self._mounted or not self.lh.head[0].has_tip:
            raise SimError("EC-06-0005", "未 GetTips：无枪头，禁止吸分液")

    def _status_payload(self) -> dict:
        # 执行 GetStatus 时 _state 会被临时置为 running，那是"本条命令"而非设备常驻状态；
        # 停机用 online=0 表达，status 只保留 idle/running/error 三个 LabOS 词表值。
        device_status = "idle" if self._state in ("running", "offline") else self._state
        return {
            "device_state": {"status": device_status, "online": 0 if self._stopped else 1},
            "head": [{"channel": i, "has_tip": t.has_tip} for i, t in self.lh.head.items()],
            "mounted_tips": len(self._mounted),
            "wells_ul": {
                "src_plate": {w: self.src.get_well(w).tracker.volume for w in ("A1", "A2", "A3")},
                "dst_plate": {w: self.dst.get_well(w).tracker.volume for w in ("B1", "B2")},
            },
        }

    def snapshot(self) -> dict:
        return {
            "src_plate": {w: self.src.get_well(w).tracker.volume for w in ("A1", "A2", "A3")},
            "dst_plate": {w: self.dst.get_well(w).tracker.volume for w in ("B1", "B2")},
            "mounted_tips": len(self._mounted),
            "state": self._state,
            "stopped": self._stopped,
        }

    async def _dispatch(self, method: str, req: dict) -> dict:
        p = self._params(req)
        if method not in PROFILE_METHODS:
            raise SimError("EC-06-0004", f"型号 {DEVICE_PROFILE} 不支持指令 {method}")
        if self._stopped and method not in ("GetStatus", "Stop"):
            raise SimError("EC-06-0005", "设备已 Stop，命令被中止", status="aborted")

        if method == "Home":
            return {"homed": True, "channels": self.lh.backend.num_channels}

        if method == "GetTips":
            box = str(p.get("tipBox") or "tips_300ul")
            if box != self.tips.name:
                raise SimError("EC-06-0005", f"板位不存在：{box}")
            try:
                count = int(p.get("count", 1))
            except (TypeError, ValueError):
                raise SimError("EC-06-0005", "count 非法") from None
            if not 1 <= count <= 8:
                raise SimError("EC-06-0005", f"count={count} 超出 1~8 通道")
            if self._mounted:
                raise SimError("EC-06-0005", "枪头已装载，请先 DropTips")
            spots = []
            for _ in range(count):
                idx = self._tip_idx % 8 + 1
                self._tip_idx += 1
                spot = self.tips.get_item(f"A{idx}")
                if not spot.has_tip():
                    raise SimError("EC-06-0005", f"枪头位 A{idx} 已空")
                spots.append(spot)
            await self.lh.pick_up_tips(spots)
            self._mounted = spots
            return {"tipBox": box, "count": count, "channels": list(range(count))}

        if method == "DropTips":
            if not self._mounted:
                raise SimError("EC-06-0005", "没有可退的枪头")
            await self.lh.drop_tips(self._mounted)
            self._mounted = []
            return {"dropped": True}

        if method == "Aspirate":
            well, label = self._resolve(p)
            v = self._volume(p)
            self._require_tip()
            await self.lh.aspirate([well], vols=[v], use_channels=[0])
            return {"labware": label, "volume": v, "remaining_ul": well.tracker.volume}

        if method == "Dispense":
            well, label = self._resolve(p)
            v = self._volume(p)
            self._require_tip()
            await self.lh.dispense([well], vols=[v], use_channels=[0])
            return {"labware": label, "volume": v, "in_well_ul": well.tracker.volume}

        if method == "Transfer":
            src, src_label = self._resolve(p, "srcLabware", "srcWell")
            dst, dst_label = self._resolve(p, "dstLabware", "dstWell")
            v = self._volume(p)
            self._require_tip()
            await self.lh.aspirate([src], vols=[v], use_channels=[0])
            await self.lh.dispense([dst], vols=[v], use_channels=[0])
            return {"from": src_label, "to": dst_label, "volume": v,
                    "src_remaining_ul": src.tracker.volume, "dst_in_well_ul": dst.tracker.volume}

        if method == "Mix":
            well, label = self._resolve(p)
            v = self._volume(p)
            try:
                cycles = int(p.get("cycles", 3))
            except (TypeError, ValueError):
                raise SimError("EC-06-0005", "cycles 非法") from None
            if not 1 <= cycles <= 20:
                raise SimError("EC-06-0005", f"cycles={cycles} 超出 1~20")
            self._require_tip()
            for _ in range(cycles):
                await self.lh.aspirate([well], vols=[v], use_channels=[0])
                await self.lh.dispense([well], vols=[v], use_channels=[0])
            return {"labware": label, "volume": v, "cycles": cycles, "in_well_ul": well.tracker.volume}

        if method == "GetStatus":
            return self._status_payload()

        if method == "SetTemp":
            raise SimError("EC-06-0004", "PyLabRobot 仿真后端未实现台面温控（SetTemp）")

        self._stopped = True
        self._state = "offline"
        return {"stopped": True}


def make_sim(args: argparse.Namespace) -> LabOSSimulator:
    return LabOSSimulator(
        device_id=args.device,
        ws_port=args.ws_port,
        fs_port=args.fs_port,
        open_browser=args.browser,
        no_visualizer=args.no_visualizer,
    )


def sim_meta(sim: LabOSSimulator) -> dict:
    return {
        "pylabrobot": pylabrobot.__version__,
        "device_profile": DEVICE_PROFILE,
        "volume_tracking": True,
        "tip_tracking": True,
        "visualizer": None if sim.vis is None else {
            "url": f"http://127.0.0.1:{sim.vis.fs_port}/",
            "ws_port": sim.vis.ws_port,
            "fs_port": sim.vis.fs_port,
            "open_browser": sim.open_browser,
        },
    }


def write_report(relative: str, report: dict) -> Path:
    path = ROOT / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    return path


async def run_job(args: argparse.Namespace) -> int:
    job_path = Path(args.job)
    if not job_path.is_absolute():
        job_path = ROOT / job_path
    job = json.loads(job_path.read_text(encoding="utf-8"))
    commands = [{**c, "equipment_id": c.get("equipment_id", job.get("equipment_id", args.device)),
                 "trace_id": c.get("trace_id", job.get("trace_id"))} for c in job["commands"]]

    sim = make_sim(args)
    await sim.start()
    hold = (args.browser or args.hold) and sim.vis is not None
    if sim.vis is not None:
        label = "已自动打开浏览器" if sim.open_browser else "headless 服务已启动，未自动弹浏览器"
        print(f"仿真界面（{label}）: http://127.0.0.1:{sim.vis.fs_port}/", flush=True)
    try:
        steps: list[dict] = []
        for cmd in commands:
            res = await sim.execute(cmd)
            steps.append(res)
            print(f"  [{res['status']:<9}] {res['method']:<10} {res['duration_ms']:>7.2f}ms  {res['message'][:64]}")
            if res["status"] != "completed":
                break
        status = "completed" if steps and all(s["status"] == "completed" for s in steps) else "failed"
        report = {
            "run_id": f"run-{datetime.now().strftime('%Y%m%d_%H%M%S')}",
            "job_id": job.get("job_id"),
            "device_id": args.device,
            "status": status,
            "simulator": sim_meta(sim),
            "steps": steps,
            "events": sim.events,
            "state": sim.snapshot(),
        }
        path = write_report(args.out or f"out/{report['run_id']}.json", report)
        print(f"\n运行结论: {status} | 液位快照: {report['state']['src_plate']} / {report['state']['dst_plate']}")
        print(f"报告: {path}")
        if hold:
            # 作业本身是毫秒级的，若立刻关服务，浏览器刚打开就断；保持到退出信号。
            # 后台/CI 里 SIGINT 常被忽略，故同时接 SIGTERM，两者都走 finally 优雅关服务。
            print("仿真服务保持中：可继续查看/刷新台面，按 Ctrl+C 退出", flush=True)
            hold_event = asyncio.Event()
            loop = asyncio.get_running_loop()
            for sig in (signal.SIGINT, signal.SIGTERM):
                try:
                    loop.add_signal_handler(sig, hold_event.set)
                except (NotImplementedError, RuntimeError):
                    pass
            try:
                await hold_event.wait()
            except asyncio.CancelledError:
                pass
    finally:
        await sim.close()
    return 0 if status == "completed" else 1


async def run_self_test(args: argparse.Namespace) -> int:
    sim = make_sim(args)
    await sim.start()
    checks: list[dict] = []

    def check(name: str, ok: bool, detail: str = "") -> None:
        checks.append({"name": name, "ok": bool(ok), "detail": detail})

    def cmd(cid: str, method: str, **params: Any) -> dict:
        return {"message_id": cid, "method": method, "parameters": params}

    try:
        normal = [
            cmd("t-01", "Home"),
            cmd("t-02", "GetTips", tipBox="tips_300ul", count=1),
            cmd("t-03", "Aspirate", volume=100, labware="src_plate", well="A1"),
            cmd("t-04", "Dispense", volume=100, labware="dst_plate", well="B1"),
            cmd("t-05", "Transfer", srcLabware="src_plate", srcWell="A2",
                dstLabware="dst_plate", dstWell="B2", volume=50),
            cmd("t-06", "Mix", labware="dst_plate", well="B1", volume=20, cycles=3),
            cmd("t-07", "GetStatus"),
            cmd("t-08", "DropTips"),
        ]
        results = [await sim.execute(c) for c in normal]
        check("正常流程 8 步全部 completed", all(r["status"] == "completed" for r in results),
              str([r["status"] for r in results]))

        frame = json.loads(results[2]["device_raw_response"])
        check("回包形状符合 LabOS 帧协议",
              frame["message_type"] == 5 and frame["method_status"] == 2
              and frame["equipment_status"] == 1 and frame["connected"] == 1,
              str(frame))

        snap = sim.snapshot()
        check("吸液 100µL 后 src_plate/A1 = 100", snap["src_plate"]["A1"] == 100.0, str(snap))
        check("Transfer 50µL 后 src_plate/A2 = 150", snap["src_plate"]["A2"] == 150.0, str(snap))
        check("分液 100µL 后 dst_plate/B1 = 100", snap["dst_plate"]["B1"] == 100.0, str(snap))
        check("Transfer 后 dst_plate/B2 = 50", snap["dst_plate"]["B2"] == 50.0, str(snap))
        check("Mix 净液位不变", snap["dst_plate"]["B1"] == 100.0, str(snap))
        check("DropTips 后无挂载枪头", snap["mounted_tips"] == 0, str(snap))

        bad = {
            "volume": cmd("t-b1", "Aspirate", volume=6000, labware="src_plate", well="A1"),
            "no_tip": cmd("t-b2", "Aspirate", volume=10, labware="src_plate", well="A3"),
            "slot": cmd("t-b3", "Aspirate", volume=10, labware="plate_99", well="A1"),
            "method": cmd("t-b4", "Foo"),
            "equip": {**cmd("t-b5", "Home"), "equipment_id": "OTHER-DEVICE"},
        }
        bad_results = {k: await sim.execute(v) for k, v in bad.items()}
        check("量程越界被拒 EC-06-0005", bad_results["volume"]["error_code"] == "EC-06-0005",
              bad_results["volume"]["message"])
        check("无枪头被拒", "无枪头" in bad_results["no_tip"]["message"], bad_results["no_tip"]["message"])
        check("板位不存在被拒", "板位不存在" in bad_results["slot"]["message"], bad_results["slot"]["message"])
        check("未知方法被拒 EC-06-0004", bad_results["method"]["error_code"] == "EC-06-0004")
        check("设备号不匹配被拒 EC-06-0001", bad_results["equip"]["error_code"] == "EC-06-0001")

        before = len(sim._ledger)
        dup = await sim.execute(cmd("t-01", "Home"))
        check("command_id 幂等（不重复执行）",
              dup["status"] == "completed" and len(sim._ledger) == before
              and sim.events[-1]["type"] == "NODE_SKIPPED")
        check("失败命令带设备原始码与回包原文",
              all(json.loads(r["device_raw_response"])["method_status"] == 3
                  for r in bad_results.values()))

        stop = await sim.execute(cmd("t-09", "Stop"))
        after_stop = await sim.execute(cmd("t-10", "Home"))
        check("Stop 后命令 aborted（状态词表）",
              stop["status"] == "completed" and after_stop["status"] == "aborted",
              after_stop["message"])
    finally:
        await sim.close()

    passed = sum(1 for c in checks if c["ok"])
    for c in checks:
        print(f"  [{'PASS' if c['ok'] else 'FAIL'}] {c['name']}" + (f"  {c['detail'][:80]}" if not c["ok"] else ""))
    report = {"status": "completed" if passed == len(checks) else "failed",
              "passed": passed, "total": len(checks), "checks": checks,
              "simulator": sim_meta(sim)}
    path = write_report(args.out or f"out/selftest_{datetime.now().strftime('%Y%m%d_%H%M%S')}.json", report)
    print(f"\n自检: {passed}/{len(checks)} PASS | 报告: {path}")
    return 0 if passed == len(checks) else 1


async def _handle_tcp(reader: asyncio.StreamReader, writer: asyncio.StreamWriter, sim: LabOSSimulator) -> None:
    buf = b""
    try:
        while True:
            chunk = await reader.read(4096)
            if not chunk:
                break
            buf += chunk
            while SEP_B in buf:
                raw, buf = buf.split(SEP_B, 1)
                if not raw.strip():
                    continue
                try:
                    req = json.loads(raw.decode("utf-8"))
                except (UnicodeDecodeError, json.JSONDecodeError):
                    continue
                res = await sim.execute(req)
                writer.write(res["device_raw_response"].encode("utf-8") + SEP_B)
                await writer.drain()
                print(f"[tcp] {res['command_id']} {res['method']} -> {res['status']} ({res['duration_ms']}ms)", flush=True)
    except (ConnectionResetError, BrokenPipeError):
        pass
    finally:
        writer.close()


async def run_tcp(args: argparse.Namespace) -> int:
    sim = make_sim(args)
    await sim.start()
    server = await asyncio.start_server(
        lambda r, w: _handle_tcp(r, w, sim), "127.0.0.1", args.tcp_port
    )
    port = server.sockets[0].getsockname()[1]
    print(f"LabOS 设备协议服务: 127.0.0.1:{port}（一行 JSON + {SEP}）", flush=True)
    print(f"sidecar 可用 --transport sim --host 127.0.0.1 --port {port} 直接连接本机仿真器", flush=True)
    if sim.vis is not None:
        print(f"仿真界面: http://127.0.0.1:{sim.vis.fs_port}/", flush=True)
    try:
        async with server:
            await server.serve_forever()
    finally:
        server.close()
        await sim.close()
    return 0


def main() -> int:
    p = argparse.ArgumentParser(description="PyLabRobot headless 仿真器（LabOS 设备契约）")
    p.add_argument("--device", default="LH-SIM-01", help="设备实例 id，需与中控配置一致")
    p.add_argument("--job", default="jobs/demo.json", help="作业 JSON 路径")
    p.add_argument("--out", default=None, help="报告输出路径（相对本目录）")
    p.add_argument("--ws-port", type=int, default=2121, help="可视化 websocket 端口")
    p.add_argument("--fs-port", type=int, default=1337, help="可视化网页端口")
    p.add_argument("--browser", action="store_true", help="自动打开浏览器，并保持服务直到 Ctrl+C")
    p.add_argument("--hold", action="store_true", help="作业跑完保持可视化服务不退出（--browser 自动隐含）")
    p.add_argument("--no-visualizer", action="store_true", help="完全不启动可视化服务，适合纯 CI")
    p.add_argument("--tcp-port", type=int, default=0, help=">0 时以 LabOS TCP/JSON 设备协议常驻")
    p.add_argument("--self-test", action="store_true", help="跑自检（正常流程 + 负例 + 幂等）")
    args = p.parse_args()
    if args.self_test and args.tcp_port:
        p.error("--self-test 与 --tcp-port 不能同时使用")

    if args.self_test:
        return asyncio.run(run_self_test(args))
    if args.tcp_port:
        return asyncio.run(run_tcp(args))
    return asyncio.run(run_job(args))


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
