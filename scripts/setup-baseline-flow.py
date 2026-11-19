#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""建「基准流程 · 串行+并行」——并行网关与准入门禁的对照基线。

形状（3 段串行 + 1 组 2 路并行）::

    S ──> 机械臂回零(ARM1/Home)
      ──> 查料栈(STACK1/CheckStackState)
      ──> [并行分叉]
            ├──> 分液(DISP6/Run)
            └──> 机械臂移动(ARM1/Move)
          [并行汇合] ──> 回安全位(ARM1/ReturnToSafe) ──> E

**为什么用脚本而不是手写 SQL 塞 XML**：
    BPMN 的 `incoming` / `outgoing` 必须与 `sequenceFlow` 严格对应，还要有
    DI 坐标（`bpmndi:BPMNPlane`）。少一个引用就会被预检判成
    「该流程没有 BPMN 图，无法执行」。本脚本复用 `setup-extraction-flow.py`
    里的 `Bpmn` 生成器（它已经在提取线上跑通），通过 `/api/workflows/{id}/graph`
    落库，因此产出的图与页面拖出来的是同一套结构。

**两条分支为什么用不同设备**：
    并行改造后要验证「两个分支同时执行」。若都绑 ARM1，即使真并发也会在
    设备锁上排队 ⇒ 看不出差别。所以 A 用 DISP6、B 用 ARM1。

用法::

    python3 scripts/setup-baseline-flow.py            # 建 / 重建
    python3 scripts/setup-baseline-flow.py --run      # 建完执行一遍

依赖：API 已在 18080 运行（`LABOS_API` 可覆盖）；
      账号 admin/admin123（`LABOS_USER` / `LABOS_PASS` 可覆盖）。
"""

from __future__ import annotations

import argparse
import importlib.util
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent

# 复用 setup-extraction-flow.py 的 Bpmn / call / login（文件名带连字符，走 importlib）
_spec = importlib.util.spec_from_file_location(
    "extraction_setup", HERE / "setup-extraction-flow.py")
ext = importlib.util.module_from_spec(_spec)
sys.modules["extraction_setup"] = ext
_spec.loader.exec_module(ext)

WF_CODE = "BASELINE-SERPAR-01"
WF_NAME = "基准流程 · 串行+并行"
ISLAND_CODE = "LAB-EXT-02"

# 串行段（顺序固定）
SERIAL_HEAD = [
    ("机械臂回零", "ARM1", "Home"),
    ("查料栈", "STACK1", "CheckStackState"),
]
# 并行两路（必须用不同设备，否则看不出并发）
BRANCH_A = ("分液", "DISP6", "Run")
BRANCH_B = ("机械臂移动", "ARM1", "Move")
# 汇合后的尾巴
TAIL = ("回安全位", "ARM1", "ReturnToSafe")


def main() -> int:
    ap = argparse.ArgumentParser(description="建基准流程（串行+并行）")
    ap.add_argument("--run", action="store_true", help="建完执行一遍")
    args = ap.parse_args()

    ext.login()

    # ---- 1. 岛与设备
    st, topo = ext.call("GET", "/api/topology")
    if st != 200:
        sys.exit(f"✘ 取拓扑失败（{st}）：{topo}")
    island = next((i for i in topo.get("islands", []) if i.get("code") == ISLAND_CODE), None)
    if island is None:
        sys.exit(f"✘ 找不到岛 {ISLAND_CODE}")
    island_id = island["id"]
    devs = {d["code"]: d for d in island.get("devices", [])}
    needed = {code for _, code, _ in SERIAL_HEAD} | {BRANCH_A[1], BRANCH_B[1], TAIL[1]}
    missing = [c for c in sorted(needed) if c not in devs]
    if missing:
        sys.exit(f"✘ 岛 {ISLAND_CODE} 下缺设备：{missing}")
    ext.ok(f"岛 {ISLAND_CODE}（id={island_id}），设备就位：{sorted(needed)}")

    # ---- 2. 找 / 建流程（幂等）
    st, lst = ext.call("GET", "/api/workflows", query={"limit": "300", "q": WF_CODE})
    wf_id = None
    for w in (lst.get("items") or []):
        if w.get("code") == WF_CODE:
            wf_id = w["id"]
    if wf_id is None:
        st, j = ext.call("POST", "/api/workflows", {
            "code": WF_CODE, "name": WF_NAME,
            "category": "test", "islandId": island_id,
        })
        if st != 200:
            sys.exit(f"✘ 建流程失败（{st}）：{j}")
        wf_id = j.get("id") or j.get("workflow", {}).get("id")
        ext.ok(f"流程已建：{WF_CODE}（id={wf_id}）")
    else:
        ext.ok(f"流程已存在：{WF_CODE}（id={wf_id}）")

    # ---- 3. 构图（用 Bpmn 生成器，不手写 XML）
    b = ext.Bpmn(f"Proc_{WF_CODE.replace('-', '_')}", WF_NAME)

    n_start = b.start("开始")
    prev = n_start
    for name, code, method in SERIAL_HEAD:
        nid = b.task(name, protocol=method, resource=devs[code]["resourceId"], params={})
        b.flow(prev, nid)
        prev = nid

    n_split = b.parallel_gateway("并行分叉")
    b.flow(prev, n_split)

    n_a = b.task(BRANCH_A[0], protocol=BRANCH_A[2],
                 resource=devs[BRANCH_A[1]]["resourceId"], params={})
    n_b = b.task(BRANCH_B[0], protocol=BRANCH_B[2],
                 resource=devs[BRANCH_B[1]]["resourceId"], params={})
    b.flow(n_split, n_a)
    b.flow(n_split, n_b)

    n_join = b.parallel_gateway("并行汇合")
    b.flow(n_a, n_join)
    b.flow(n_b, n_join)

    n_tail = b.task(TAIL[0], protocol=TAIL[2], resource=devs[TAIL[1]]["resourceId"], params={})
    b.flow(n_join, n_tail)

    n_end = b.end("结束")
    b.flow(n_tail, n_end)

    xml = b.to_xml()

    # ---- 4. 落库
    st, j = ext.call("PUT", f"/api/workflows/{wf_id}/graph",
                     raw=xml, content_type="application/xml")
    if st != 200:
        sys.exit(f"✘ 落图失败（{st}）：{j}")
    ext.ok(f"图已落库（{len(xml)} 字节，{len(b.nodes)} 节点 / {len(b.flows)} 条边）")

    # ---- 5. 预检（能编译 + 节点/边数对得上）
    st, pf = ext.call("GET", f"/api/workflows/{wf_id}/preflight")
    if st == 200:
        errs = [i for i in (pf.get("issues") or []) if i.get("level") in ("ERROR", "error")]
        if errs:
            for e in errs:
                print(f"  ✘ [{e.get('code')}] {e.get('message')}")
            sys.exit("✘ 预检未通过")
        ext.ok("预检通过")
    else:
        print(f"  ! 预检接口返回 {st}，跳过（不影响落库）")

    print(f"\n流程：{WF_NAME}（id={wf_id}，code={WF_CODE}）")

    # ---- 6. 可选：跑一遍
    if args.run:
        st, j = ext.call("POST", f"/api/workflows/{wf_id}/run", {})
        print(f"  运行返回（{st}）：{j}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
