#!/usr/bin/env python3
"""流程规格 → **真的建到设计器页面上** → 落库 → 编译/预检验证。

这是「AI 建流程」链路的**第二步**：拿一份 Flow Spec（或一句自然语言），
打开设计器页面，调用页面自己的建模通道把图画出来，落库，再校验这张图
能不能编译、能不能跑。

为什么走页面而不是直接写库：
  画布怎么摆、图标怎么来、网关叫什么、设备节点该带哪些属性 —— 这些规则
  都在前端（webapp/src/bpmn/）。走页面就等于复用同一套规则，不会出现
  "脚本建的图和手工拖的图长得不一样"。

页面侧提供两个钩子（见 webapp/src/pages/Designer.tsx）：
  window.__bpmnModeler          —— bpmn-js 实例本身
  window.__labosFlowSpec.import(json) —— 按规格建图 + 落库（返回 {created, errors}）

用法：
  # 用现成规格建到某条流程（覆盖画布）
  python3 scripts/ai-build-flow.py --spec /tmp/spec.json --workflow 1000830

  # 自然语言 → 规格 → 新建流程 → 建图
  python3 scripts/ai-build-flow.py --text "读码 → 离心 → 封膜" \
      --new "离心封膜流程" --island LAB-BASE-01

  # 建完顺手跑一遍
  python3 scripts/ai-build-flow.py --spec /tmp/spec.json --workflow 1000830 --run

环境变量：
  LABOS_WEB  前端地址（默认 http://localhost:5173）
  LABOS_API  后端地址（默认 http://127.0.0.1:18080）
  LABOS_USER / LABOS_PASS
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
from pathlib import Path
from typing import Any

from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
BASE = os.environ.get("LABOS_WEB", "http://localhost:5173")
API = os.environ.get("LABOS_API", "http://127.0.0.1:18080")
USER = os.environ.get("LABOS_USER", "admin")
PWD = os.environ.get("LABOS_PASS", "admin123")
OUT = ROOT / "private" / "s2-screenshots"

SPEC_SCRIPT = Path(__file__).resolve().parent / "ai-flow-spec.py"

# 复用 ai-flow-spec 的目录/校验逻辑（同一份口径，别写两遍）
sys.path.insert(0, str(Path(__file__).resolve().parent))
import importlib.util as _ilu
_spec_mod = _ilu.spec_from_file_location("ai_flow_spec", SPEC_SCRIPT)
ai_flow_spec = _ilu.module_from_spec(_spec_mod)
assert _spec_mod.loader is not None
_spec_mod.loader.exec_module(ai_flow_spec)


# ---------------------------------------------------------------- API 基础


def api(path: str, method: str = "GET", body: Any = None,
        token: str | None = None, timeout: int = 60) -> Any:
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
        raise RuntimeError(
            f"{method} {path} → HTTP {exc.code}: {exc.read().decode()[:300]}"
        ) from exc
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        return text


def login() -> str:
    out = api("/api/auth/login", "POST",
              {"username": USER, "password": PWD}, timeout=30)
    if not isinstance(out, dict) or not out.get("token"):
        raise RuntimeError(f"登录失败：{out}")
    return out["token"]


def island_id_by_code(token: str, code: str | None) -> int | None:
    if not code:
        return None
    topo = api("/api/topology", token=token)
    for isl in topo.get("islands") or []:
        if isl.get("code") == code:
            return isl.get("id")
    raise RuntimeError(f"找不到岛：{code}")


# ---------------------------------------------------------------- 规格获取


def resolve_spec(args: argparse.Namespace) -> dict[str, Any]:
    """拿到 Flow Spec：优先 --spec 文件，其次 --text 走模型生成。"""
    if args.spec:
        spec = json.loads(Path(args.spec).read_text())
    elif args.text:
        cmd = [sys.executable, str(SPEC_SCRIPT), "--text", args.text,
               "--model", args.model, "--out", str(OUT / "ai-spec.json")]
        if args.island:
            cmd += ["--island", args.island]
        print("→ 生成流程规格…", file=sys.stderr)
        proc = subprocess.run(cmd, capture_output=True, text=True)
        if proc.returncode != 0:
            print(proc.stderr, file=sys.stderr)
            raise RuntimeError("规格生成失败")
        spec = json.loads((OUT / "ai-spec.json").read_text())
    else:
        raise RuntimeError("需要 --spec 或 --text")
    return spec


# ---------------------------------------------------------------- 页面驱动


def ui_login(pg) -> None:
    pg.goto(BASE + "/login", wait_until="domcontentloaded")
    pg.fill('input[autocomplete="username"]', USER)
    pg.fill('input[autocomplete="current-password"]', PWD)
    pg.click('button[type="submit"]')
    pg.wait_for_timeout(1500)
    if "/login" in pg.url:
        raise RuntimeError("页面登录失败")


def wait_designer(pg) -> None:
    """等 modeler 就绪 + 图导入完成 + 规格导入钩子挂上。"""
    pg.wait_for_function("window.__bpmnModeler != null", timeout=25000)
    pg.wait_for_function(
        "() => { const m=window.__bpmnModeler; if(!m) return false;"
        " const r=m.get('canvas').getRootElement();"
        " return r && r.type==='bpmn:Process'; }",
        timeout=25000,
    )
    pg.wait_for_function("window.__labosFlowSpec != null", timeout=10000)
    pg.wait_for_timeout(500)


def import_spec(pg, spec: dict[str, Any]) -> dict[str, Any]:
    """调页面自己的导入钩子：建图 + 落库。"""
    raw = json.dumps(spec, ensure_ascii=False)
    res = pg.evaluate("(raw) => window.__labosFlowSpec.import(raw)", raw)
    pg.wait_for_timeout(1200)
    return res or {}


def canvas_nodes(pg) -> list[dict[str, Any]]:
    """读画布上的节点（用于回报建了什么）。"""
    return pg.evaluate(
        """() => window.__bpmnModeler.get('elementRegistry').getAll()
          .filter(e => e.type !== 'label')
          .map(e => ({
            id: e.id,
            type: e.type,
            name: e.businessObject && e.businessObject.name,
            resource: e.businessObject && e.businessObject.$attrs
              ? e.businessObject.$attrs.resource : null,
            protocol: e.businessObject && e.businessObject.$attrs
              ? e.businessObject.$attrs.protocolName : null,
          }))"""
    )


# ---------------------------------------------------------------- 校验


def verify(token: str, wf_id: int) -> tuple[bool, list[str]]:
    """编译 + 预检：这张图到底能不能跑。"""
    notes: list[str] = []
    ok = True
    comp = api(f"/api/workflows/{wf_id}/compile", token=token)
    nodes = comp.get("nodes") or []
    notes.append(f"编译出 {len(nodes)} 个节点 / {comp.get('totalSteps', '?')} 步")
    if not nodes:
        ok = False
        notes.append("✗ 编译不出任何节点")

    pre = api(f"/api/workflows/{wf_id}/preflight", token=token)
    if pre.get("blocking"):
        ok = False
        notes.append(f"✗ 预检阻断：{pre.get('errors')} 个错误")
        for issue in (pre.get("issues") or [])[:10]:
            notes.append(f"    · {issue.get('code')} {issue.get('message')}")
    elif pre.get("warnings"):
        notes.append(f"! 预检有 {pre.get('warnings')} 条警告")
        for issue in (pre.get("issues") or [])[:10]:
            notes.append(f"    · {issue.get('code')} {issue.get('message')}")
    else:
        notes.append("✓ 预检通过")
    return ok, notes


# ---------------------------------------------------------------- 主流程


def main() -> int:
    ap = argparse.ArgumentParser(description="流程规格 → 建到设计器页面 → 落库 → 校验")
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--spec", help="Flow Spec JSON 文件")
    src.add_argument("--text", help="自然语言描述（内部会先调模型生成规格）")
    ap.add_argument("--workflow", type=int, help="建到已有流程 id（覆盖画布）")
    ap.add_argument("--new", dest="new_name", help="新建流程并建图")
    ap.add_argument("--island", help="岛 code（如 LAB-BASE-01）")
    ap.add_argument("--model", default="minimax/MiniMax-M3", help="provider/model")
    ap.add_argument("--run", action="store_true", help="建完顺手执行一遍")
    ap.add_argument("--headed", action="store_true", help="显示浏览器窗口")
    ap.add_argument("--force", action="store_true",
                    help="规格没通过校验也照样建（默认拦下来）")
    ap.add_argument("--print-spec", action="store_true",
                    help="只打印规格，不建图")
    args = ap.parse_args()

    if not args.workflow and not args.new_name and not args.print_spec:
        ap.error("需要 --workflow <id> 或 --new <流程名>")

    OUT.mkdir(parents=True, exist_ok=True)
    token = login()

    spec = resolve_spec(args)
    print(f"规格：{len(spec.get('nodes') or [])} 节点 / "
          f"{len(spec.get('edges') or [])} 连线", file=sys.stderr)

    # 建图**之前**先校验：设备/方法真实存在、必填参数齐、能力不歧义。
    # 宁可现在说清楚，也不要建出一张看着对、跑不起来的图。
    catalog = ai_flow_spec.fetch_catalog(token, args.island)
    problems, warnings = ai_flow_spec.validate_spec(spec, catalog)
    for w in warnings:
        print("  !", w, file=sys.stderr)
    if problems and not args.force:
        print("✗ 规格没通过校验（--force 可跳过）：", file=sys.stderr)
        for p in problems:
            print("  ·", p, file=sys.stderr)
        return 1
    for p in problems:
        print("  !", p, file=sys.stderr)

    if args.print_spec:
        print(json.dumps(spec, ensure_ascii=False, indent=2))
        return 0

    # 新建流程：直接走 API（比点页面稳，也快）
    wf_id = args.workflow
    if args.new_name:
        isl = island_id_by_code(token, args.island)
        created = api("/api/workflows", "POST", {
            "name": args.new_name,
            "category": "ai",
            "islandId": isl,
        }, token=token)
        wf_id = int(created.get("id") or created.get("workflowId") or 0)
        if not wf_id:
            raise RuntimeError(f"新建流程失败：{created}")
        print(f"新建流程 id={wf_id}", file=sys.stderr)

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=not args.headed)
        pg = browser.new_page(viewport={"width": 1680, "height": 1000})
        ui_login(pg)
        pg.goto(f"{BASE}/designer/{wf_id}", wait_until="domcontentloaded")
        wait_designer(pg)

        res = import_spec(pg, spec)
        created_n = res.get("created", 0)
        errors = res.get("errors") or []
        print(f"建图：{created_n} 个节点", file=sys.stderr)
        for e in errors:
            print("  !", e, file=sys.stderr)

        shot = OUT / f"ai-build-{wf_id}.png"
        pg.screenshot(path=str(shot))
        print(f"截图：{shot}", file=sys.stderr)

        browser.close()

    ok, notes = verify(token, wf_id)
    for n in notes:
        print(n, file=sys.stderr)

    if args.run and ok:
        print("→ 执行流程…", file=sys.stderr)
        out = api(f"/api/workflows/{wf_id}/run", "POST", {}, token=token)
        print(json.dumps(out, ensure_ascii=False)[:400], file=sys.stderr)

    return 0 if ok and not errors else 1


if __name__ == "__main__":
    sys.exit(main())
