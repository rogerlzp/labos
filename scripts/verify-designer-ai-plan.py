#!/usr/bin/env python3
"""验证「AI 修改流程」的**顺序图（plan）模式**：结构改动不能把图连断。

背景（2026-11-13 用户实测）：让模型直接吐 connect/disconnect 改结构，
它**能连线，但保证不了图还连着** —— 实测把主链拆成两截、留下一堆走不到的孤儿节点。
⇒ 结构改动一律走顺序图：模型只负责"想清楚长什么样"，**连线由代码做**。

本脚本用真实需求走一遍：
  ① 页面上提需求（拆成并行分支）；
  ② AI 给出**顺序图**，编译器展开成操作；
  ③ 应用后图必须**连通**（无孤儿）、**有并行网关**、**预检通过**。

用法：
  python3 scripts/verify-designer-ai-plan.py
  python3 scripts/verify-designer-ai-plan.py --headed
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request

from playwright.sync_api import sync_playwright

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASE = os.environ.get("LABOS_WEB", "http://localhost:5173")
API = os.environ.get("LABOS_API", "http://127.0.0.1:18080")
USER = os.environ.get("LABOS_USER", "admin")
PWD = os.environ.get("LABOS_PASS", "admin123")
OUT = os.path.join(ROOT, "private", "s2-screenshots")

DEFAULT_WF = "1000853"

# 底子：一条串行流程（取板 → 搬运 → 离心 → 搬运 → 封膜 → 搬运 → 孵育）
BASE_SPEC = {
    "nodes": [
        {"id": "S", "type": "start"},
        {"id": "T1", "type": "method", "code": "ID-1021", "method": "ReadPlate"},
        {"id": "T2", "type": "method", "code": "ID-X231", "method": "Transfer",
         "params": {"source": "ID-1021", "target": "ID-ASPIN_2K-1"}},
        {"id": "T3", "type": "method", "code": "ID-ASPIN_2K-1", "method": "RunProgram"},
        {"id": "T4", "type": "method", "code": "ID-X231", "method": "Transfer",
         "params": {"source": "ID-ASPIN_2K-1", "target": "ID-S100-1"}},
        {"id": "T5", "type": "method", "code": "ID-S100-1", "method": "Seal"},
        {"id": "T6", "type": "method", "code": "ID-X231", "method": "Transfer",
         "params": {"source": "ID-S100-1", "target": "ID-X229"}},
        {"id": "T7", "type": "method", "code": "ID-X229", "method": "Incubate"},
        {"id": "E", "type": "end"},
    ],
    "edges": [{"from": a, "to": b} for a, b in [
        ("S", "T1"), ("T1", "T2"), ("T2", "T3"), ("T3", "T4"),
        ("T4", "T5"), ("T5", "T6"), ("T6", "T7"), ("T7", "E")]],
}
INSTRUCTION = ("把离心那一步（RunProgram）拆成与封膜（Seal）并行的分支："
               "加一个并行网关开始和一个并行网关结束，把离心和封膜两条分支放进去")

FAILED: list[str] = []


def check(cond: bool, label: str, detail: str = "") -> bool:
    mark = "✓" if cond else "✗"
    print(f"  {mark} {label}" + (f"  ({detail})" if detail else ""))
    if not cond:
        FAILED.append(label)
    return cond


def api(path: str, method: str = "GET", body=None, token=None, timeout=60):
    req = urllib.request.Request(API + path, method=method)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, data=data, timeout=timeout) as resp:
            text = resp.read().decode()
    except urllib.error.HTTPError as exc:
        raise RuntimeError(f"{method} {path} → HTTP {exc.code}: {exc.read().decode()[:300]}")
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        return text


def ui_login(pg) -> None:
    pg.goto(BASE + "/login", wait_until="domcontentloaded")
    pg.fill('input[autocomplete="username"]', USER)
    pg.fill('input[autocomplete="current-password"]', PWD)
    pg.click('button[type="submit"]')
    pg.wait_for_timeout(1500)


def wait_designer(pg) -> None:
    pg.wait_for_function("window.__bpmnModeler != null", timeout=25000)
    pg.wait_for_function(
        "() => { const m=window.__bpmnModeler; if(!m) return false;"
        " const r=m.get('canvas').getRootElement();"
        " return r && r.type==='bpmn:Process'; }",
        timeout=25000,
    )
    pg.wait_for_timeout(900)


def graph_of(pg) -> dict:
    """读画布：节点与边。"""
    return pg.evaluate(
        """() => {
          const reg = window.__bpmnModeler.get('elementRegistry');
          const all = reg.getAll().filter(e => e.type !== 'label');
          // ⚠ 要排除 **bpmn:Process 根元素**：它是容器（名字 = 流程名），
          //    不是节点 —— 算连通性时它永远"走不到"，会把测试判成图断了。
          const nodes = all.filter(e => e.type !== 'bpmn:SequenceFlow'
            && e.type !== 'bpmn:Process').map(e => ({
            id: e.id, type: e.type,
            name: (e.businessObject && e.businessObject.name) || '',
            proto: (e.businessObject && e.businessObject.$attrs || {}).protocolName || '',
          }));
          const edges = all.filter(e => e.type === 'bpmn:SequenceFlow').map(e => ({
            from: (e.businessObject.sourceRef || {}).id,
            to: (e.businessObject.targetRef || {}).id,
          }));
          return { nodes, edges };
        }"""
    )


def reachable(g: dict, start: str | None, reverse: bool = False) -> set[str]:
    succ: dict[str, list[str]] = {}
    for e in g["edges"]:
        if reverse:
            succ.setdefault(e["to"], []).append(e["from"])
        else:
            succ.setdefault(e["from"], []).append(e["to"])
    seen: set[str] = set()
    stack = [start] if start else []
    while stack:
        cur = stack.pop()
        if cur in seen:
            continue
        seen.add(cur)
        stack.extend(succ.get(cur, []))
    return seen


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--workflow", default="")
    ap.add_argument("--headed", action="store_true")
    args = ap.parse_args()
    os.makedirs(OUT, exist_ok=True)
    token = api("/api/auth/login", "POST",
                {"username": USER, "password": PWD})["token"]

    # ---- 准备底子：一条**串行**流程 ----
    # 测试必须自建底子：上一轮跑完图已经是并行的，AI 会正确地回答
    # "不需要改动" ⇒ 反而测不到应用环节。
    if args.workflow:
        wf_id = args.workflow
    else:
        made = api("/api/workflows", "POST",
                   {"name": "验证-顺序图拆并行分支", "category": "verify"}, token=token)
        wf_id = int(made.get("id") or made.get("workflowId"))
    base = os.path.join(OUT, "ai-plan-base-spec.json")
    with open(base, "w", encoding="utf-8") as fh:
        json.dump(BASE_SPEC, fh, ensure_ascii=False)
    subprocess.run([sys.executable,
                    os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                 "ai-build-flow.py"),
                    "--spec", base, "--workflow", str(wf_id),
                    "--island", "LAB-BASE-01"], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    print(f"[0] 底子已就绪：流程 {wf_id}（串行 7 步）")

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=not args.headed)
        pg = browser.new_page(viewport={"width": 1700, "height": 950})
        errors: list[str] = []
        pg.on("pageerror", lambda e: errors.append(str(e)))

        ui_login(pg)
        pg.goto(f"{BASE}/designer/{wf_id}", wait_until="domcontentloaded")
        wait_designer(pg)

        before = graph_of(pg)
        print(f"[1] 当前图：{len(before['nodes'])} 节点 / {len(before['edges'])} 连线")

        # ---- 页面上提需求 ----
        print("[2] 页面上提需求 → AI 出顺序图")
        pg.get_by_role("button", name="AI 修改流程").click()
        pg.wait_for_timeout(800)
        pg.fill('[data-testid="ai-instruction"]', INSTRUCTION)
        pg.get_by_role("button", name="生成修改建议").click()
        try:
            pg.wait_for_selector(".ant-modal-content .ant-card", timeout=180000)
        except Exception:
            pass
        modal = re.sub(r"\s+", " ", pg.locator(".ant-modal-content").last.inner_text())
        print("    " + modal[-320:])
        pg.screenshot(path=os.path.join(OUT, "ai-plan-preview.png"))

        check("修改建议" in modal, "给出了修改建议")
        check("顺序图" in modal or "结构重排" in modal,
              "走的是**顺序图（plan）模式**，不是裸 connect/disconnect")
        check("JSON 不合法" not in modal and "生成修改建议失败" not in modal,
              "没有 JSON 截断/解析错误")

        # ---- 应用 ----
        print("[3] 应用到画布")
        btn = pg.get_by_role("button", name="应用到画布")
        if not check(btn.count() > 0, "有可应用的操作"):
            browser.close()
            print("\n" + f"失败 {len(FAILED)} 项 ❌: {FAILED}")
            return 1
        pg.wait_for_timeout(800)   # 等弹窗动画结束（动画期间遮罩会挡住按钮）
        # 记录当前"已保存"标记，等它变化 = 落库真的完成了。
        # 固定 sleep 可能读到"操作应用了一半"的画布 ⇒ 误判成图断了。
        saved_before = pg.evaluate(
            "() => document.querySelector('[data-graph-saved]')"
            "?.dataset.graphSaved || ''")
        btn.click()
        try:
            pg.wait_for_function(
                """(before) => {
                  const e = document.querySelector('[data-graph-saved]');
                  return !!e && e.dataset.graphSaved
                    && e.dataset.graphSaved !== before;
                }""",
                arg=saved_before,
                timeout=60000,
            )
        except Exception:
            print("    ! 没等到保存标记（可能保存失败）")
        pg.wait_for_timeout(1200)

        after = graph_of(pg)
        print(f"    改完：{len(after['nodes'])} 节点 / {len(after['edges'])} 连线")

        # ---- 图形结构检查（页面上的）----
        pgw = [n for n in after["nodes"] if "ParallelGateway" in n["type"]]
        check(len(pgw) == 2, "有 2 个并行网关（开始 + 结束）",
              str([(n["name"], n["type"]) for n in pgw]))

        start = next((n["id"] for n in after["nodes"]
                      if n["type"] == "bpmn:StartEvent"), None)
        end = next((n["id"] for n in after["nodes"]
                    if n["type"] == "bpmn:EndEvent"), None)
        reach = reachable(after, start)
        print("    start=", start, "reach=", sorted(reach))
        orphan = [n for n in after["nodes"]
                  if n["id"] not in reach and n["id"] != end]
        check(not orphan, "没有走不到的孤儿节点（主线没被连断）",
              str([(o["name"], o["proto"]) for o in orphan[:5]]))

        # "能否走到结束" = **反向**遍历（正向从 end 出发只会走到 end 自己）
        back = reachable(after, end, reverse=True)
        noend = [n for n in after["nodes"]
                 if n["id"] not in back and n["id"] != start]
        check(not noend, "每个节点都能走到结束节点",
              str([(o["name"], o["proto"]) for o in noend[:5]]))

        # 两条分支：并行分叉的出边 > 1，并行汇合的入边 > 1
        outs: dict[str, int] = {}
        ins: dict[str, int] = {}
        for e in after["edges"]:
            outs[e["from"]] = outs.get(e["from"], 0) + 1
            ins[e["to"]] = ins.get(e["to"], 0) + 1
        check(any(v > 1 for v in outs.values()), "有节点分出多条边（分叉）")
        check(any(v > 1 for v in ins.values()), "有节点汇入多条边（汇合）")

        pg.get_by_role("button", name="适应画布").click()
        pg.wait_for_timeout(900)
        pg.screenshot(path=os.path.join(OUT, "ai-plan-applied.png"))

        # ---- 落库与预检 ----
        print("[4] 落库与预检")
        xml = api(f"/api/workflows/{wf_id}/graph", token=token)
        if not isinstance(xml, str):
            xml = json.dumps(xml, ensure_ascii=False)
        check("parallelGateway" in xml, "并行网关已落库")
        comp = api(f"/api/workflows/{wf_id}/compile", token=token)
        check(len(comp.get("nodes") or []) > 0, "改完还能编译",
              f"{len(comp.get('nodes') or [])} 节点")
        pre = api(f"/api/workflows/{wf_id}/preflight", token=token)
        check(not pre.get("blocking"), "预检无阻断",
              f"errors={pre.get('errors')} warnings={pre.get('warnings')}")
        unreachable = [i for i in (pre.get("issues") or [])
                       if i.get("code") in ("PF-03", "PF-04", "PF-05")]
        check(not unreachable, "预检没有「走不到 / 到不了结束」的告警",
              str([i.get("message") for i in unreachable[:3]]))

        real = [e for e in errors if "Static function can not consume context" not in e]
        check(not real, "页面无 JS 报错", "; ".join(real[:3]))
        browser.close()

    print("\n" + ("全部通过 ✅" if not FAILED else f"失败 {len(FAILED)} 项 ❌: {FAILED}"))
    return 0 if not FAILED else 1


if __name__ == "__main__":
    sys.exit(main())
