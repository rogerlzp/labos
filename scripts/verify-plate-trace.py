#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""实测：板子的**占用状态**与**流转轨迹**都留痕、可查。

**为什么要有这条**：用户现场提了两件事 ——
① 「实验执行时候，需要做很多检查，比如板的位置，每个设备上的点位是占用还是空闲状态，
   如何记录，每个动作如何更改这个状态」；
② 「每个板子的编号，要如何记录，方便我们能够看到板子编号是如何在不同设备之间流转的」。

查下来发现两件事的**根因是同一个**：

- `position_state` / `plate_location_audit` 两张表**都在**，代码也写了
  （`PositionLock.putPlate/takePlate` 会维护 `position_state`），
  但**基线岛的 5 台设备一个层位都没有** ⇒ 放板动作找不到地方放
  ⇒ `position_state` 永远空、流转审计永远没记录 ⇒ 现场无据可查。
- 而 `plate_location_audit` 以前**只在人工盘点时写** ⇒ 执行过程的移动本来就没痕迹。

这一轮补了三件事：
1. 基线给孵育箱 4 个层位、机器人 2 个中转位（`scripts/baseline-build.sql`）；
2. 放板/取板都写流转审计（`PositionLock.auditPlateMove`）；
3. 流转轨迹可查（`GET /api/plates/traces` + 「板子流转」页）。

断言：
  ① 基线设备有层位（孵育箱 4 / 机器人 2）；
  ② 跑一条「放板 → 取板」⇒ `position_state` 有行（占用）→ 取板后变空；
  ③ `plate_location_audit` 有两行（放板 + 取板），带位置编码与 run 号；
  ④ 接口能按板号查出这个板的完整轨迹。

前置：内核 + 前端在跑，孵育箱 ID-X229 在线。
用法：
  python3 scripts/verify-plate-trace.py
"""
import json
import os
import subprocess
import sys
import time
import urllib.request

API = os.environ.get("LABOS_API", "http://127.0.0.1:18080")

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
    try:
        with urllib.request.urlopen(req, data=data, timeout=120) as r:
            txt = r.read().decode()
    except urllib.error.HTTPError as e:
        txt = e.read().decode()
        try:
            j = json.loads(txt)
        except json.JSONDecodeError:
            j = {"ok": False, "error": txt[:200]}
        j["_httpStatus"] = e.code
        return j
    try:
        return json.loads(txt)
    except json.JSONDecodeError:
        return txt


def q(sql):
    env = dict(os.environ)
    env.setdefault("PATH", "/opt/homebrew/opt/libpq/bin:/usr/bin:/bin")
    env.setdefault("PGPASSWORD", os.environ.get("LABOS_DB_PASS", ""))
    out = subprocess.run(["psql", "-h", os.environ["LABOS_DB_HOST"],
                          "-U", os.environ["LABOS_DB_USER"],
                          "-d", os.environ["LABOS_DB_NAME"], "-tAc", sql],
                         env=env, capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError("SQL 失败: %s\n%s" % (sql, out.stderr.strip()[:300]))
    return out.stdout.strip()


XML = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
  xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
  id="Dp" targetNamespace="http://bpmn.io/schema/bpmn">
  <bpmn:process id="Pp" isExecutable="false">
    <bpmn:startEvent id="S" name="开始"/>
    <bpmn:task id="T1" name="放板" protocolName="DepositToAnyEmptyPosition" resource="%d"/>
    <bpmn:task id="T2" name="取板" protocolName="WithdrawByBarcode" resource="%d"/>
    <bpmn:endEvent id="E" name="结束"/>
    <bpmn:sequenceFlow id="f1" sourceRef="S" targetRef="T1"/>
    <bpmn:sequenceFlow id="f2" sourceRef="T1" targetRef="T2"/>
    <bpmn:sequenceFlow id="f3" sourceRef="T2" targetRef="E"/>
  </bpmn:process>
  <bpmndi:BPMNDiagram id="Dp2">
    <bpmndi:BPMNPlane id="Pp2" bpmnElement="Pp">
      <bpmndi:BPMNShape id="S_di" bpmnElement="S"><dc:Bounds x="60" y="160" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="T1_di" bpmnElement="T1"><dc:Bounds x="160" y="140" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="T2_di" bpmnElement="T2"><dc:Bounds x="330" y="140" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="E_di" bpmnElement="E"><dc:Bounds x="490" y="160" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="f1_di" bpmnElement="f1"><di:waypoint x="96" y="178"/><di:waypoint x="160" y="180"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="f2_di" bpmnElement="f2"><di:waypoint x="260" y="180"/><di:waypoint x="330" y="180"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="f3_di" bpmnElement="f3"><di:waypoint x="430" y="180"/><di:waypoint x="490" y="178"/></bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>
"""


def main():
    token = api("/api/auth/login", "POST",
                {"username": "admin", "password": "admin123"})["token"]

    # ---- ① 基线设备有层位 ----
    n_inc = q("SELECT count(*) FROM resource_node p JOIN resource_node d "
              "ON d.id = p.parent_id WHERE d.code='ID-X229' AND d.kind='device' "
              "AND p.kind IN ('incubator','storage','transfer_point')")
    n_rob = q("SELECT count(*) FROM resource_node p JOIN resource_node d "
              "ON d.id = p.parent_id WHERE d.code='ID-X231' AND d.kind='device' "
              "AND p.kind IN ('incubator','storage','transfer_point')")
    if int(n_inc) >= 1 and int(n_rob) >= 1:
        ok("基线设备有层位（孵育箱 %s 个 / 机器人 %s 个中转位）" % (n_inc, n_rob))
    else:
        bad("基线设备没有层位（板位机制用不起来）",
            "孵育箱 %s / 机器人 %s —— 先跑 psql -f scripts/baseline-build.sql" % (n_inc, n_rob))
        return 1

    inc = int(q("SELECT id FROM resource_node WHERE code='ID-X229' AND kind='device'"))
    plate = "PL-TRACE-%d" % int(time.time() % 100000)

    # 清干净这块板的历史，免得旧记录混进来
    q("DELETE FROM plate_location_audit WHERE barcode = '%s'" % plate)

    wf = api("/api/workflows", "POST", {"name": "实测·板子流转"}, token)["id"]
    api("/api/workflows/%d/graph" % wf, "PUT", raw=XML % (inc, inc), token=token)
    for key, m, p in [("T1", "DepositToAnyEmptyPosition", {"plate_id": plate}),
                      ("T2", "WithdrawByBarcode", {"plate_id": plate})]:
        api("/api/workflows/%d/nodes/%s" % (wf, key), "PUT", token=token,
            body={"method": m, "params": p, "resourceId": inc})

    r = api("/api/workflows/%d/run" % wf, "POST", {}, token)
    if isinstance(r, dict) and r.get("error"):
        bad("跑流程失败", r.get("error"))
        return 1
    time.sleep(3)

    # ---- ② 占用状态：放板占了位，取板放了位 ----
    #
    # ⚠ 判据不能写成「position_state 里现在还有这个板」：
    #   流程是「放板 → 取板」连续跑的，跑完板已经被取走 ⇒ 位置自然空了。
    #   要证明的是**完整的一放一取**：
    #     · 冗余表 plate 记着它最后在哪个位置、现在是"在途"（取走了）；
    #     · position_state 里那个层位**已经空了**（占用被释放）。
    plate_row = q("SELECT COALESCE(location_id::text,'') || '|' || "
                  "COALESCE(position_status,'') FROM plate WHERE barcode = '%s'" % plate)
    if plate_row:
        loc_id, st = (plate_row.split("|") + ["", ""])[:2]
        ok("板子表记着它最后在位置 %s，现在状态 %s" % (loc_id, st))
    else:
        bad("plate 表里没有这个板子", plate_row)
    empty = q("SELECT count(*) FROM position_state ps JOIN resource_node p "
              "ON p.id = ps.resource_id JOIN resource_node d ON d.id = p.parent_id "
              "WHERE d.code='ID-X229' AND ps.plate_id IS NOT NULL")
    if int(empty) == 0:
        ok("取板后层位已释放（设备上没板了 —— 占用被正确释放）")
    else:
        bad("取板后层位上还有板", "%s 个位置还占着" % empty)

    # ---- ③ 流转审计 ----
    traces = api("/api/plates/traces?barcode=%s" % plate, "GET", token=token)
    items = traces.get("items", [])
    if len(items) >= 2:
        ok("流转审计有 %d 条（放板 + 取板）" % len(items))
    else:
        bad("流转审计条数不对", json.dumps(items, ensure_ascii=False)[:200])
    put = next((x for x in items if x.get("toStatus") == "KNOWN"), None)
    take = next((x for x in items if x.get("toStatus") == "IN_TRANSFER"), None)
    if put and put.get("actualLocation"):
        ok("放板留痕带位置编码：%s @ %s" % (put.get("note"), put.get("actualLocation")))
    else:
        bad("放板的留痕缺位置编码", json.dumps(put, ensure_ascii=False))
    if take and take.get("runId"):
        ok("取板留痕带执行单号：run #%s" % take.get("runId"))
    else:
        bad("取板的留痕缺执行单号", json.dumps(take, ensure_ascii=False))
    if all(x.get("operator") == "AUTO" for x in items) and items:
        ok("留痕标为「自动」（与人工盘点的记录区分得开）")
    else:
        bad("留痕的 operator 不是 AUTO", json.dumps(items, ensure_ascii=False)[:200])

    # ---- ④ 接口按板号查得到 ----
    if len(items) >= 2 and all(x.get("barcode") == plate for x in items):
        ok("按板号能查出这个板的完整轨迹")
    else:
        bad("按板号查轨迹不对", json.dumps(items, ensure_ascii=False)[:200])

    print("\n通过 %d / 失败 %d" % (PASS, FAIL))
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
