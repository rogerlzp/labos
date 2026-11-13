#!/usr/bin/env python3
"""验证设计器上的「AI 修改流程」：**在页面上提需求 → AI 给修改清单 → 应用到画布**。

这条链路是这个功能的核心 —— 页面能提需求、AI 能定位到具体节点、
改完真的落到图上并落库、预检还过。

用法：
  python3 scripts/verify-designer-ai-edit.py
  python3 scripts/verify-designer-ai-edit.py --headed
  python3 scripts/verify-designer-ai-edit.py --workflow 1000853
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
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
    if "/login" in pg.url:
        raise RuntimeError("页面登录失败")


def wait_designer(pg) -> None:
    pg.wait_for_function("window.__bpmnModeler != null", timeout=25000)
    pg.wait_for_function(
        "() => { const m=window.__bpmnModeler; if(!m) return false;"
        " const r=m.get('canvas').getRootElement();"
        " return r && r.type==='bpmn:Process'; }",
        timeout=25000,
    )
    pg.wait_for_timeout(800)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--workflow", default=DEFAULT_WF)
    ap.add_argument("--headed", action="store_true")
    args = ap.parse_args()
    os.makedirs(OUT, exist_ok=True)

    # 需求里带一个**每次不同**的板号：否则第二次跑时要求已经满足，
    # AI 会正确地回答"不需要改动" ⇒ 就没有可应用的操作了（测不到应用环节）。
    plate_id = "PL" + str(int(time.time()))[-6:]
    instruction = (f"把读板那一步的 plate_id 改成 {plate_id}，"
                   f"把最后一次搬运的 target 改成 ID-X229")

    token = api("/api/auth/login", "POST",
                {"username": USER, "password": PWD})["token"]
    wf_id = args.workflow

    print(f"[1] 打开流程 {wf_id}")
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=not args.headed)
        pg = browser.new_page(viewport={"width": 1600, "height": 950})
        errors: list[str] = []
        pg.on("pageerror", lambda e: errors.append(str(e)))
        pg.on("console", lambda m: errors.append(f"console.{m.type}: {m.text}")
              if m.type == "error" else None)

        ui_login(pg)
        pg.goto(f"{BASE}/designer/{wf_id}", wait_until="domcontentloaded")
        wait_designer(pg)

        before_xml = api(f"/api/workflows/{wf_id}/graph", token=token)
        if not isinstance(before_xml, str):
            before_xml = json.dumps(before_xml)

        # ---- 打开 AI 面板 ----
        print("[2] 打开「AI 修改流程」")
        pg.get_by_role("button", name="AI 修改流程").click()
        pg.wait_for_timeout(800)
        check(pg.locator('[data-testid="ai-instruction"]').is_visible(),
              "AI 面板已打开")
        pg.fill('[data-testid="ai-instruction"]', instruction)

        # ---- 生成修改建议 ----
        print("[3] 生成修改建议（后端调 LLM）")
        pg.get_by_role("button", name="生成修改建议").click()
        pg.wait_for_timeout(2500)
        # 等预览出现（或错误提示）
        try:
            pg.wait_for_selector(".ant-modal-content .ant-card", timeout=120000)
        except Exception:
            pass
        modal_text = pg.locator(".ant-modal-content").last.inner_text()
        print("    " + re.sub(r"\s+", " ", modal_text)[-260:])
        pg.screenshot(path=os.path.join(OUT, "designer-ai-preview.png"))

        check("修改建议" in modal_text, "给出了修改清单")
        check(not re.search(r"未配置|生成修改建议失败", modal_text),
              "AI 链路没报错")

        # ---- 应用到画布 ----
        print("[4] 应用到画布")
        apply_btn = pg.get_by_role("button", name="应用到画布")
        if apply_btn.count() == 0:
            check(False, "有可应用的修改（没找到「应用到画布」按钮）")
        else:
            apply_btn.click()
            pg.wait_for_timeout(4000)
            after_xml = api(f"/api/workflows/{wf_id}/graph", token=token)
            if not isinstance(after_xml, str):
                after_xml = json.dumps(after_xml)
            check(plate_id in after_xml, "读板节点的 plate_id 已改并落库", plate_id)
            check("ID-X229" in after_xml,
                  "搬运节点的 target 已改并落库")
            check(after_xml != before_xml, "图确实变了")

            comp = api(f"/api/workflows/{wf_id}/compile", token=token)
            check(len(comp.get("nodes") or []) > 0,
                  "改完还能编译", f"{len(comp.get('nodes') or [])} 节点")
            pre = api(f"/api/workflows/{wf_id}/preflight", token=token)
            check(not pre.get("blocking"), "改完预检无阻断",
                  f"errors={pre.get('errors')} warnings={pre.get('warnings')}")

        pg.screenshot(path=os.path.join(OUT, "designer-ai-applied.png"))
        real = [e for e in errors if "Static function can not consume context" not in e]
        check(not real, "页面无 JS 报错", "; ".join(real[:3]))
        browser.close()

    print("\n" + ("全部通过 ✅" if not FAILED else f"失败 {len(FAILED)} 项 ❌: {FAILED}"))
    return 0 if not FAILED else 1


if __name__ == "__main__":
    sys.exit(main())
