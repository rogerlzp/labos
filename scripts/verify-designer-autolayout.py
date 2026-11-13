#!/usr/bin/env python3
"""验证设计器的「自动对齐」排版口径。

口径（用户 2026-11-13 定的）：
  ① 先按节点顺序一个接一个摆 —— 串行的；
  ② 遇到并行网关，分支并列、**前后端对齐**；
  ③ 整条太长 ⇒ **折行**，从下一行继续往后排；
  ④ 网关对（分叉+它的分支+汇合）是**不可拆**的整体，换行也不许从中间断开。

用法：
  python3 scripts/verify-designer-autolayout.py
  python3 scripts/verify-designer-autolayout.py --headed
"""
from __future__ import annotations

import argparse
import os
import sys

from playwright.sync_api import sync_playwright

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASE = os.environ.get("LABOS_WEB", "http://localhost:5173")
OUT = os.path.join(ROOT, "private", "s2-screenshots")

FAILED: list[str] = []


def check(cond: bool, label: str, detail: str = "") -> bool:
    mark = "✓" if cond else "✗"
    print(f"  {mark} {label}" + (f"  ({detail})" if detail else ""))
    if not cond:
        FAILED.append(label)
    return cond


def ui_login(pg) -> None:
    pg.goto(BASE + "/login", wait_until="domcontentloaded")
    pg.fill('input[autocomplete="username"]', os.environ.get("LABOS_USER", "admin"))
    pg.fill('input[autocomplete="current-password"]', os.environ.get("LABOS_PASS", "admin123"))
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


def cluster_rows(ys: list[int], tol: int = 40) -> list[int]:
    """把 y 值按容差聚成"行"（同中心的不同高度算同一行）。"""
    out: list[int] = []
    for y in sorted(ys):
        if out and y - out[-1] <= tol:
            continue
        out.append(y)
    return out


def layout(pg) -> list[dict]:
    """读画布上每个节点的类型/名字/坐标。"""
    return pg.evaluate(
        """() => window.__bpmnModeler.get('elementRegistry').getAll()
          .filter(e => e.type !== 'label' && e.x != null)
          .map(e => ({
            id: e.id,
            type: e.type,
            name: (e.businessObject && e.businessObject.name) || '',
            x: Math.round(e.x),
            y: Math.round(e.y),
            w: Math.round(e.width || 0),
          }))"""
    )


def align(pg) -> None:
    pg.get_by_role("button", name="自动对齐").click()
    pg.wait_for_timeout(1600)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--headed", action="store_true")
    args = ap.parse_args()
    os.makedirs(OUT, exist_ok=True)

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=not args.headed)
        pg = browser.new_page(viewport={"width": 1700, "height": 950})
        errors: list[str] = []
        pg.on("pageerror", lambda e: errors.append(str(e)))

        # ---------- ① 串行链：全在一行，间距均匀 ----------
        print("[1] 串行链（1000854）")
        ui_login(pg)
        pg.goto(f"{BASE}/designer/1000854", wait_until="domcontentloaded")
        wait_designer(pg)
        align(pg)
        pos = layout(pg)
        tasks = [n for n in pos if n["type"] == "bpmn:Task"]
        # 行按 y 聚类（容差 40px）：起止事件与任务**同中心**但高度不同，
        # 直接数不同的 y 会把同一行算成两行。
        ys = cluster_rows([n["y"] for n in pos])
        check(len(ys) <= 1, "串行链只有一行", f"行={ys}")
        # 间距按**中心**算：起止事件宽 36、任务宽 100，左边缘间距自然不一样。
        cs = sorted(n["x"] + n["w"] / 2 for n in pos)
        gaps = {cs[i + 1] - cs[i] for i in range(len(cs) - 1)}
        check(len(gaps) == 1, "节点间距均匀（按中心）", f"间距={gaps}")
        check(len(tasks) == 6, "6 个任务都在", f"{len(tasks)} 个")
        pg.get_by_role("button", name="适应画布").click()
        pg.wait_for_timeout(800)
        pg.screenshot(path=os.path.join(OUT, "autolayout-serial.png"))

        # ---------- ② 长流程：折行 ----------
        print("[2] 长流程折行（1000830，18 列）")
        pg.goto(f"{BASE}/designer/1000830", wait_until="domcontentloaded")
        wait_designer(pg)
        align(pg)
        pos = layout(pg)
        rows: dict[int, list[dict]] = {}
        for n in pos:
            rows.setdefault(n["y"], []).append(n)
        rowYs = cluster_rows([n["y"] for n in pos])
        check(len(rowYs) >= 2, "长流程折成了多行", f"{len(rowYs)} 行 {rowYs}")
        # 每一行的节点数不超过一行的容量（10 列）
        widest = max(len(v) for v in rows.values())
        check(widest <= 11, "没有哪一行超出容量", f"最宽一行 {widest} 个节点")

        # ---------- ③ 网关对不可拆：分叉与汇合必须在同一行 ----------
        print("[3] 并行网关：分支并列、首尾对齐、网关对不拆")
        forks = [n for n in pos if n["type"] == "bpmn:ExclusiveGateway"]
        joins = [n for n in pos if n["type"] == "bpmn:ExclusiveGateway"]
        # 分叉（出边>1）与汇合（入边>1）分别取
        detail = pg.evaluate(
            """() => {
              const reg = window.__bpmnModeler.get('elementRegistry');
              const out = reg.getAll().filter(e => e.type === 'bpmn:ExclusiveGateway')
                .map(e => ({
                  id: e.id, name: e.businessObject.name,
                  outs: (e.businessObject.outgoing || []).length,
                  ins: (e.businessObject.incoming || []).length,
                  x: Math.round(e.x), y: Math.round(e.y),
                }));
              return out;
            }"""
        )
        fork = next((d for d in detail if d["outs"] > 1), None)
        jn = next((d for d in detail if d["ins"] > 1), None)
        if check(fork is not None and jn is not None, "找得到分叉与汇合"):
            check(fork["y"] == jn["y"], "分叉与汇合在同一行（网关对没被拆开）",
                  f"fork.y={fork['y']} join.y={jn['y']}")
            # 分支上的节点：与分叉同行 ⇒ 说明分叉的出边在同一行展开
            lane = [n for n in pos if n["y"] == fork["y"]
                    and n["type"] == "bpmn:Task"]
            others = [n for n in pos if n["y"] != fork["y"]
                      and n["type"] == "bpmn:Task"]
            check(len(others) >= 2, "分支节点排在别的行（并列展开）",
                  f"另外 {len(others)} 个任务在不同行")
            # 分支节点首尾对齐：同一行的多个分支节点 x 相同
            by_y: dict[int, set[int]] = {}
            for n in others:
                by_y.setdefault(n["y"], set()).add(n["x"])
            aligned = any(len(v) == 1 for v in by_y.values())
            check(aligned, "同一条分支上的节点 x 对齐（前后端对齐）",
                  str({y: sorted(v) for y, v in by_y.items()}))
        pg.get_by_role("button", name="适应画布").click()
        pg.wait_for_timeout(800)
        pg.screenshot(path=os.path.join(OUT, "autolayout-wrapped.png"))

        real = [e for e in errors if "Static function can not consume context" not in e]
        check(not real, "页面无 JS 报错", "; ".join(real[:3]))
        browser.close()

    print("\n" + ("全部通过 ✅" if not FAILED else f"失败 {len(FAILED)} 项 ❌: {FAILED}"))
    return 0 if not FAILED else 1


if __name__ == "__main__":
    sys.exit(main())
