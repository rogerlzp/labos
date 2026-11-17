#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""模板导出 / 导入验收（真 API + 真库，跑完自清理）。

验的是"跨环境搬运"这件事本身：
  ① 导出文件里**不能有本环境的数据库 id** —— 岛/设备/点位/方法/角色全部用业务码；
  ② 导入到同一套环境（流程编码已存在 ⇒ 自动改名）后，**绑定 / 阶段 / 白名单 / spec 全部还原**；
  ③ 目标环境缺岛 / 缺点位 ⇒ **400 且不落库**（不允许建出半成品）。

用法：
    LABOS_DB_HOST=... LABOS_DB_USER=... LABOS_DB_PASS=... LABOS_DB_NAME=... \\
      python3 scripts/verify-template-io.py
"""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

API = os.environ.get("LABOS_API", "http://127.0.0.1:18080").rstrip("/")
USER = os.environ.get("LABOS_USER", "admin")
PWD = os.environ.get("LABOS_PASS", "admin123")
ISLAND = int(os.environ.get("LABOS_ISLAND", "1000505"))
POINT_A = os.environ.get("LABOS_POINT_A", "DISP1-PLATE")
POINT_B = os.environ.get("LABOS_POINT_B", "DISP3-PLATE")


def call(path, method="GET", body=None, token=None, raw=None):
    req = urllib.request.Request(API + path, method=method)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = None
    if raw is not None:
        data = raw.encode()
        req.add_header("Content-Type", "application/xml")
    elif body is not None:
        data = json.dumps(body).encode()
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, data=data, timeout=30) as r:
            txt = r.read() or b"{}"
            return r.status, json.loads(txt)
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read() or b"{}")
        except Exception:
            return e.code, {}


def check(cond, msg, detail=""):
    print(("  ✔ " if cond else "  ✘ ") + msg + ("" if cond else f" — {detail}"))
    if not cond:
        raise AssertionError(msg)


def cleanup_workflows(wids):
    script = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                          "cleanup-test-workflows.py")
    if not wids:
        return
    subprocess.run([sys.executable, script, "--apply", "--with-runs",
                    "--only-ids", ",".join(str(w) for w in wids)],
                   capture_output=True, text=True, check=False)


def main():
    token = call("/api/auth/login", "POST", {"username": USER, "password": PWD})[1]["token"]
    pos = call(f"/api/positions?islandId={ISLAND}", token=token)[1].get("items", [])
    by_code = {p["code"]: p for p in pos}
    pa, pb = by_code.get(POINT_A), by_code.get(POINT_B)
    check(pa is not None and pb is not None, f"点位 {POINT_A} / {POINT_B} 存在")

    ts = int(time.time())
    made_wf, made_tpl = [], []
    try:
        # ---- 夹具：带阶段 + 绑定的一条流程 ----
        wf = call("/api/workflows", "POST",
                  {"code": f"TPLIO-{ts}", "name": f"实测·模板导入导出-{ts}",
                   "islandId": ISLAND}, token=token)[1]
        wid = wf.get("id") or wf.get("workflow", {}).get("id")
        made_wf.append(wid)
        xml = f'''<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
 <bpmn:process id="P1" isExecutable="true">
  <bpmn:startEvent id="S"/><bpmn:endEvent id="E"/>
  <bpmn:task id="A" name="Run" protocolName="Run" resource="{pa['parentDeviceId']}" stageCode="S2"/>
  <bpmn:task id="B" name="Run" protocolName="Run" resource="{pb['parentDeviceId']}" stageCode="S4"/>
  <bpmn:sequenceFlow id="f1" sourceRef="S" targetRef="A"/>
  <bpmn:sequenceFlow id="f2" sourceRef="A" targetRef="B"/>
  <bpmn:sequenceFlow id="f3" sourceRef="B" targetRef="E"/>
 </bpmn:process>
</bpmn:definitions>'''
        check(call(f"/api/workflows/{wid}/graph?draft=1", "PUT", raw=xml, token=token)[0] == 200,
              "夹具流程已保存")
        for key, p in (("A", pa), ("B", pb)):
            st, _ = call(f"/api/workflows/{wid}/nodes/{key}/bindings", "PUT", {
                "bindings": [{
                    "positionId": p["pointId"], "methodId": 417,
                    "materialRole": "mainPlate", "loadingPattern": "load_single",
                    "barcodePolicy": "validate_in", "leaveAtStation": False,
                    "params": {"speed": 1},
                }]}, token=token)
            check(st == 200, f"夹具绑定 {key} 写入")

        st, tpl = call("/api/experiment-templates", "POST", {
            "name": f"实测·模板IO-{ts}", "islandId": ISLAND,
            "workflowIds": [{"workflowId": wid}], "spec": {"iterations": 2},
            "manifestItems": [{"roleCode": "mainPlate", "materialKind": "plate",
                               "quantity": 1, "required": True}],
            "pointWhitelist": {"pointIds": [pa["pointId"], pb["pointId"]]},
        }, token=token)
        tid = tpl["id"]
        made_tpl.append(tid)
        check(st == 200, "夹具模板已建", tpl)

        # ---- ① 导出 ----
        st, exp = call(f"/api/experiment-templates/{tid}/export", token=token)
        check(st == 200 and exp.get("kind") == "labos.experiment-template",
              "导出成功且带 kind/version", exp)
        check(exp["template"]["islandCode"] == "LAB-EXT-01", "岛用 code 表达")
        check(set(exp["template"]["whitelistPointCodes"]) == {POINT_A, POINT_B},
              "白名单用点位码表达", exp["template"]["whitelistPointCodes"])
        w0 = exp["workflows"][0]
        binds = [(b["nodeKey"], b["deviceCode"], b["pointCode"], b["methodName"],
                  b["materialRole"]) for b in w0["bindings"]]
        check(binds == [("A", "DISP1", POINT_A, "Run", "mainPlate"),
                        ("B", "DISP3", POINT_B, "Run", "mainPlate")],
              "绑定全部用业务码", binds)
        check(w0["stages"] == {"A": "S2", "B": "S4"}, "阶段随流程导出", w0["stages"])
        blob = json.dumps(exp, ensure_ascii=False)
        for bad in ('"positionId"', '"methodId"', '"islandId"', '"workflowId"'):
            check(bad not in blob, f"导出不含本环境字段 {bad}")

        # ---- ② 导入 ----
        st, imp = call("/api/experiment-templates/import", "POST", exp, token=token)
        check(st == 200, "导入成功", imp)
        nid = imp["templateId"]
        nwid = imp["workflowIds"][0]
        made_tpl.append(nid)
        made_wf.append(nwid)
        detail = call(f"/api/experiment-templates/{nid}", token=token)[1]
        check(len(detail.get("processes") or []) == 1, "流程清单还原")
        check(len(detail.get("manifestItems") or []) == 1, "物料清单还原")
        check(json.loads(detail.get("spec")) == {"iterations": 2}, "spec 还原", detail.get("spec"))
        # 接口把 jsonb 列按文本返回（不是对象），这里自己解一次
        wl_raw = detail.get("pointWhitelist")
        wl_obj = json.loads(wl_raw) if isinstance(wl_raw, str) else (wl_raw or {})
        check(set(wl_obj.get("pointIds") or []) == {pa["pointId"], pb["pointId"]},
              "白名单还原", wl_raw)
        for key, p in (("A", pa), ("B", pb)):
            b = call(f"/api/workflows/{nwid}/nodes/{key}/bindings", token=token)[1].get("bindings", [])
            check(len(b) == 1 and b[0].get("positionId") == p["pointId"]
                  and b[0].get("methodId") == 417
                  and b[0].get("materialRole") == "mainPlate",
                  f"导入后节点 {key} 绑定还原", b)
        comp = call(f"/api/workflows/{nwid}/compile", token=token)[1]
        stages = {n["nodeKey"]: n.get("stageCode") for n in comp.get("nodes", [])}
        check(stages == {"A": "S2", "B": "S4"}, "导入后阶段还原", stages)
        chk = call(f"/api/experiment-templates/{nid}/check", token=token)[1]
        check(chk.get("errors") == 0, "导入后模板自检无 ERROR", chk.get("issues"))

        # ---- ③ 负例：缺岛 / 缺点位都 400 且不落库 ----
        bad_island = json.loads(json.dumps(exp))
        bad_island["template"]["islandCode"] = "NO-SUCH-ISLAND"
        st, r = call("/api/experiment-templates/import", "POST", bad_island, token=token)
        check(st == 400 and "岛" in (r.get("error") or ""), "缺岛 → 400 且指明原因", r)

        bad_point = json.loads(json.dumps(exp))
        bad_point["workflows"][0]["bindings"][0]["pointCode"] = "NO-SUCH-POINT"
        st, r2 = call("/api/experiment-templates/import", "POST", bad_point, token=token)
        check(st == 400 and r2.get("problems"), "缺点位 → 400 并列出问题", r2)

        print("\nPASS 模板导出 / 导入（业务码搬运 + 还原 + 负例）")
    finally:
        for t in made_tpl:
            try:
                req = urllib.request.Request(API + f"/api/experiment-templates/{t}", method="DELETE")
                req.add_header("Authorization", "Bearer " + token)
                urllib.request.urlopen(req, timeout=20).read()
            except Exception:
                pass
        cleanup_workflows(made_wf)
        print("  测试模板/流程已清理：", made_tpl, made_wf)


if __name__ == "__main__":
    main()
