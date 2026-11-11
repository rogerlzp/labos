#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""实测（页面层）：设计器里的流程变量能不能建、能不能进节点属性的下拉。

**为什么要有这条**：变量以前只能在「变量管理台」建 —— 那个页面的对象下拉
默认取列表第一条，于是变量会建到**别的流程**上；回到设计器一看，
"我刚建的变量呢" 就没有了（2026-11-11 现场）。

所以设计器右栏现在有「流程变量」卡片：就地建、就地删、就地看初值，
建完立刻能在节点属性的变量名下拉里选到。

断言（页面层，看用户真正看到的）：
  ① 设计器右栏有「流程变量」卡片；
  ② 卡片里能看到已声明的变量和它的初始值；
  ③ 点「新建变量」→ 填名 → 保存 ⇒ 卡片里立刻出现；
  ④ 选中一个逻辑节点 → 节点属性的变量名下拉里能选到这个新变量。

前置：前端 5173 在跑 + 内核 18080 在跑。
用法：
  python3 scripts/verify-vars-page.py
"""
import json
import os
import subprocess
import sys
import time
import urllib.request
from playwright.sync_api import sync_playwright

API = os.environ.get("LABOS_API", "http://127.0.0.1:18080")
WEB = os.environ.get("LABOS_WEB", "http://localhost:5173")

PASS = FAIL = 0


def ok(m):
    global PASS
    PASS += 1
    print("  \033[32m✓\033[0m " + m)


def bad(m, d=""):
    global FAIL
    FAIL += 1
    print("  \033[31m✗\033[0m " + m + (("（%s）" % d) if d else ""))


def api(path, method="GET", body=None, token=None, raw=None):
    req = urllib.request.Request(API + path, method=method)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = raw.encode() if raw is not None else (
        json.dumps(body).encode() if body is not None else None)
    if raw is not None:
        req.add_header("Content-Type", "application/xml")
    elif data is not None:
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, data=data, timeout=120) as r:
        t = r.read().decode()
        try:
            return json.loads(t)
        except Exception:
            return t


def q(sql):
    env = dict(os.environ)
    env.setdefault("PATH", "/opt/homebrew/opt/libpq/bin:/usr/bin:/bin")
    env.setdefault("PGPASSWORD", os.environ.get("LABOS_DB_PASS", ""))
    out = subprocess.run(["psql", "-h", os.environ["LABOS_DB_HOST"],
                          "-U", os.environ["LABOS_DB_USER"],
                          "-d", os.environ["LABOS_DB_NAME"], "-tAc", sql],
                         env=env, capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError(out.stderr[:300])
    return out.stdout.strip()


XML = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
  xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
  id="Dv" targetNamespace="x">
  <bpmn:process id="Pv" isExecutable="false">
    <bpmn:startEvent id="S" name="开始"/>
    <bpmn:task id="T1" name="变量节点" protocolName="__setVar" resource="__LOGIC__"/>
    <bpmn:endEvent id="E" name="结束"/>
    <bpmn:sequenceFlow id="f1" sourceRef="S" targetRef="T1"/>
    <bpmn:sequenceFlow id="f2" sourceRef="T1" targetRef="E"/>
  </bpmn:process>
  <bpmndi:BPMNDiagram id="Dg">
    <bpmndi:BPMNPlane id="Pl" bpmnElement="Pv">
      <bpmndi:BPMNShape id="S_di" bpmnElement="S"><dc:Bounds x="60" y="160" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="T1_di" bpmnElement="T1"><dc:Bounds x="170" y="140" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="E_di" bpmnElement="E"><dc:Bounds x="320" y="160" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="f1_di" bpmnElement="f1"><di:waypoint x="96" y="178"/><di:waypoint x="170" y="180"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="f2_di" bpmnElement="f2"><di:waypoint x="270" y="180"/><di:waypoint x="320" y="178"/></bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>
"""


def main():
    token = api("/api/auth/login", "POST",
                {"username": "admin", "password": "admin123"})["token"]

    # 一条带逻辑节点的流程（逻辑节点才有「变量名」下拉）
    wf = api("/api/workflows", "POST", {"name": "实测·设计器变量"}, token)["id"]
    api("/api/workflows/%d/graph" % wf, "PUT", raw=XML, token=token)
    api("/api/workflows/%d/nodes/T1" % wf, "PUT", token=token, body={
        "method": "__setVar", "params": {"var": "demoVar", "value": "1"},
    })
    # 先声明一个带初值的变量（卡片里应当能看到它和初值）
    api("/api/workflows/%d/variables" % wf, "POST", token=token, body={
        "name": "seedVar", "type": "number", "defaultValue": "7",
        "description": "设计器页面实测",
    })

    with sync_playwright() as pw:
        b = pw.chromium.launch()
        pg = b.new_page(viewport={"width": 1600, "height": 1000})
        pg.goto(WEB + "/login", wait_until="domcontentloaded")
        pg.wait_for_timeout(1200)
        pg.fill('input[type="text"]', "admin")
        pg.fill('input[type="password"]', "admin123")
        pg.get_by_role("button", name="登").first.click()
        pg.wait_for_timeout(2500)

        pg.goto("%s/designer/%d" % (WEB, wf), wait_until="domcontentloaded")
        pg.wait_for_timeout(5000)

        # ---- ① 卡片在不在 ----
        card = pg.locator(".ant-card", has_text="流程变量")
        if card.count() == 0:
            bad("设计器右栏没有「流程变量」卡片")
            pg.screenshot(path="/tmp/vars-card-missing.png", full_page=False)
            b.close()
            return 1
        ok("设计器右栏有「流程变量」卡片")

        # ---- ② 已有变量和初值看得见 ----
        if card.get_by_text("seedVar").count() > 0:
            ok("卡片里能看到已声明的变量 seedVar")
        else:
            bad("卡片里看不到 seedVar")
        # 卡片上的措辞是「初值 7」（不是 "= 7"）——「=」会让人以为是当前值
        if card.get_by_text("初值 7").count() > 0:
            ok("卡片里能看到初始值（seedVar 初值 7）")
        else:
            bad("卡片里看不到 seedVar 的初始值", card.inner_text()[:120].replace("\n", " | "))

        # ---- ③ 就地新建 ----
        new_name = "uiVar%d" % int(time.time() % 100000)
        card.get_by_role("button", name="新建变量").first.click()
        pg.wait_for_timeout(800)
        # 按 placeholder 定位（输入框顺序会被 antd 的 Select 搜索框插队，别用 nth）
        pg.get_by_placeholder("变量名（字母/数字/下划线，不能以数字开头）").first.fill(new_name)
        pg.get_by_placeholder("初始值").first.fill("42")
        # ⚠ 必须限定在弹窗里：设计器画布上也有「保存」按钮，.first 会点到被遮住的那个
        pg.locator(".ant-modal-footer").get_by_role("button", name="保").first.click()
        pg.wait_for_timeout(2500)
        if card.get_by_text(new_name).count() > 0:
            ok("在设计器里新建的变量 %s 立刻出现在卡片里" % new_name)
        else:
            bad("新建后卡片里看不到 %s" % new_name)
            pg.screenshot(path="/tmp/vars-new-missing.png", full_page=False)

        # ---- ④ 节点属性的变量名下拉里能选到 ----
        # 选中逻辑节点（画布上点那个 task）→ 右栏出现「节点设置」
        node = pg.locator(".djs-element[data-element-id='T1']").first
        if node.count() == 0:
            bad("画布上找不到逻辑节点 T1")
        else:
            node.click()
            pg.wait_for_timeout(2500)
            # 变量名选择器 = 节点设置卡里那个 **multiple** 的 Select
            #（节点类型那个是 single 的；multiple 的另一个特征是 mode="tags"）。
            # 不用 placeholder：值不为空时 antd 不挂 placeholder 属性。
            vname = pg.locator(".ant-select-multiple .ant-select-selector").first
            if vname.count() == 0:
                bad("节点设置里没有变量名下拉（逻辑节点才有）")
                pg.screenshot(path="/tmp/vars-no-picker.png", full_page=False)
            else:
                vname.click()
                pg.wait_for_timeout(1200)
                opt = pg.locator(".ant-select-item-option", has_text=new_name)
                if opt.count() > 0:
                    ok("节点属性的变量名下拉里能选到 %s" % new_name)
                    pg.screenshot(path="/tmp/vars-dropdown.png", full_page=False)
                else:
                    bad("节点属性的变量名下拉里没有 %s" % new_name)
                    pg.screenshot(path="/tmp/vars-dropdown-missing.png", full_page=False)
                pg.keyboard.press("Escape")

        pg.screenshot(path="/tmp/designer-vars-card.png", full_page=False)
        b.close()

    # ---------- 变量管理台：owner 必须显式选，别再默认第一条 ----------
    with sync_playwright() as pw:
        b = pw.chromium.launch()
        pg = b.new_page(viewport={"width": 1500, "height": 950})
        pg.goto(WEB + "/login", wait_until="domcontentloaded")
        pg.wait_for_timeout(1200)
        pg.fill('input[type="text"]', "admin")
        pg.fill('input[type="password"]', "admin123")
        pg.get_by_role("button", name="登").first.click()
        pg.wait_for_timeout(2500)

        # ① 不带参数 ⇒ 必须提示"先选一条"，且不许建
        pg.goto(WEB + "/variables", wait_until="domcontentloaded")
        pg.wait_for_timeout(2500)
        if pg.get_by_text("先选一条流程或实验").count() > 0:
            ok("变量管理台不再默认选第一条流程（提示先选）")
        else:
            bad("变量管理台仍然默认选了某条流程")
        newbtn = pg.get_by_role("button", name="新建变量").first
        if newbtn.is_disabled():
            ok("没选对象时「新建变量」是禁用的")
        else:
            bad("没选对象时「新建变量」仍可点")

        # ② 带 ?workflowId= ⇒ 定位到那条流程
        pg.goto("%s/variables?workflowId=%d" % (WEB, wf), wait_until="domcontentloaded")
        pg.wait_for_timeout(2500)
        if pg.get_by_text("seedVar").count() > 0:
            ok("从设计器带 workflowId 进变量管理台，直接看到那条流程的变量")
        else:
            bad("带 workflowId 进变量管理台 没看到那条流程的变量")
        pg.screenshot(path="/tmp/vars-console.png", full_page=False)
        b.close()

    # ---------- 实验页：流程用到的变量要看得见、改得动 ----------
    with sync_playwright() as pw:
        b = pw.chromium.launch()
        pg = b.new_page(viewport={"width": 1500, "height": 950})
        pg.goto(WEB + "/login", wait_until="domcontentloaded")
        pg.wait_for_timeout(1200)
        pg.fill('input[type="text"]', "admin")
        pg.fill('input[type="password"]', "admin123")
        pg.get_by_role("button", name="登").first.click()
        pg.wait_for_timeout(2500)

        # 建一个实验，编排上面那条流程（流程里有 seedVar）
        isl = int(q("SELECT id FROM resource_node WHERE code='LAB-BASE-01' AND kind='island'"))
        exp = api("/api/experiments", "POST",
                  {"name": "实测·实验用到的变量", "workflowIds": [wf], "islandId": isl},
                  token)["id"]
        pg.goto("%s/experiments/%d" % (WEB, exp), wait_until="domcontentloaded")
        pg.wait_for_timeout(5000)
        if pg.get_by_text("实验用到的变量").count() > 0:
            ok("实验页有「实验用到的变量」区块")
        else:
            bad("实验页没有「实验用到的变量」区块")
            pg.screenshot(path="/tmp/exp-used-vars-missing.png", full_page=False)
        if pg.get_by_text("seedVar").count() > 0:
            ok("实验页能看到流程里声明的变量 seedVar")
        else:
            bad("实验页看不到流程里声明的 seedVar")
            pg.screenshot(path="/tmp/exp-used-vars-no-seed.png", full_page=False)
        # 改值：改的是**这个实验的取值**
        btn = pg.locator(".ant-card", has_text="实验用到的变量").get_by_role("button", name="改").first
        if btn.count() == 0:
            bad("实验变量行上没有「改」按钮")
        else:
            btn.click()
            pg.wait_for_timeout(700)
            box = pg.locator(".ant-card", has_text="实验用到的变量").locator("input.ant-input").first
            box.fill("66")
            box.press("Enter")
            pg.wait_for_timeout(2500)
            used = api("/api/experiments/%d/used-variables" % exp, "GET", token=token)
            item = next((i for i in used.get("items", []) if i["name"] == "seedVar"), None)
            if item and item.get("value") == "66" and item.get("scope") == "experiment":
                ok("改值生效：本实验取值 = 66，标为实验覆盖")
            else:
                bad("改值没生效", json.dumps(item, ensure_ascii=False))
            pg.screenshot(path="/tmp/exp-used-vars.png", full_page=False)
        b.close()
    # 清理这个实验上的变量（PUT 是整表替换 ⇒ 空数组即清空）
    api("/api/experiments/%d/variables" % exp, "PUT", token=token, body=[])

    # 清理：删掉测试变量（别把垃圾留在库里）
    api("/api/workflows/%d/variables/%s" % (wf, new_name), "DELETE", token=token)
    api("/api/workflows/%d/variables/seedVar" % wf, "DELETE", token=token)

    print("\n通过 %d / 失败 %d" % (PASS, FAIL))
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
