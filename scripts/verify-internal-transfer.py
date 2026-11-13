#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""设备内部搬运（装载口 ↔ 内部格）冒烟：校验板型。

口径：机械臂把板放到装载口（{设备}-IN），设备内部机构（MoveInLabware 方法）把板搬进
内部格，并把「板 ↔ 内部格」关系通知中控；中控只在「搬入内部格」时才校验板型/高度
（checkPlateFits），不匹配则板留在装载口、不写内部格。

用例：
  ① 板型匹配（pcr96 ∈ 内部格允许集合）→ 板从装载口搬进内部格 R1C1；
  ② 板型不匹配（elisa96 ∉ {pcr96}）→ 被拒，板仍在装载口。

前置：ID-2011 已用 gen-grid-points.py --cols 1 --rows 2 --plate-types pcr96 --load-port 配好。
「放装载口」由机械臂完成，本脚本用 SQL 直接把板预放到装载口，再跑 MoveInLabware 测内部搬进。
用法：
  LABOS_DB_HOST=... LABOS_DB_NAME=... python3 scripts/verify-internal-transfer.py
"""
import json
import os
import subprocess
import sys
import time
import urllib.request

API = os.environ.get("LABOS_API", "http://127.0.0.1:18080")
DEV_CODE = "ID-2011"
LOAD = "ID-2011-IN"
SLOT = "ID-2011-R1C1"
PASS = FAIL = 0


def ok(m, d=""):
    global PASS
    PASS += 1
    print("  \033[32m✓\033[0m " + m + (("（%s）" % d) if d else ""))


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
        return {"error": e.read().decode()[:200], "_httpStatus": e.code}
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


def plate_on(code):
    v = q("SELECT coalesce(ps.plate_id,'') FROM position_state ps "
          "JOIN resource_node r ON r.id=ps.resource_id WHERE r.code='%s'" % code)
    return v or ""


XML = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
  id="Dit" targetNamespace="http://bpmn.io/schema/bpmn">
  <bpmn:process id="Pit" isExecutable="false">
    <bpmn:startEvent id="S" name="开始"/>
    <bpmn:task id="T1" name="内部搬进" protocolName="MoveInLabware" resource="%d"/>
    <bpmn:endEvent id="E" name="结束"/>
    <bpmn:sequenceFlow id="f1" sourceRef="S" targetRef="T1"/>
    <bpmn:sequenceFlow id="f2" sourceRef="T1" targetRef="E"/>
  </bpmn:process>
  <bpmndi:BPMNDiagram id="Dit2">
    <bpmndi:BPMNPlane id="Pit2" bpmnElement="Pit">
      <bpmndi:BPMNShape id="S_di" bpmnElement="S"><dc:Bounds x="60" y="160" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="T1_di" bpmnElement="T1"><dc:Bounds x="160" y="140" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="E_di" bpmnElement="E"><dc:Bounds x="330" y="160" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="f1_di" bpmnElement="f1"><di:waypoint x="96" y="178"/><di:waypoint x="160" y="180"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="f2_di" bpmnElement="f2"><di:waypoint x="260" y="180"/><di:waypoint x="330" y="178"/></bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>
"""


def run_case(token, res, plate, plate_type):
    """预放板到装载口 → 跑 MoveInLabware → 返回 (在装载口, 在内部格, 报错)。"""
    # 预建板 + 预放到装载口（机械臂的「放装载口」由 SQL 模拟）
    q("INSERT INTO plate (tenant_id, barcode, plate_type, rows, cols) "
      "VALUES (1, '%s', '%s', 8, 12) "
      "ON CONFLICT (tenant_id, barcode) DO UPDATE SET plate_type=EXCLUDED.plate_type"
      % (plate, plate_type))
    q("DELETE FROM plate_location_audit WHERE barcode='%s'" % plate)
    # 清 ID-2011 所有点位的占用，再把板放到装载口
    q("UPDATE position_state SET plate_id=NULL, content_kind=NULL, content_id=NULL, "
      "occupied_by_run=NULL, occupied_since=NULL, updated_at=now() "
      "WHERE resource_id IN (SELECT id FROM resource_node WHERE parent_id=%d)" % res)
    q("UPDATE position_state SET plate_id='%s', content_kind='plate', "
      "content_id=(SELECT id FROM plate WHERE barcode='%s'), updated_at=now() "
      "WHERE resource_id=(SELECT id FROM resource_node WHERE code='%s')"
      % (plate, plate, LOAD))

    wf = api("/api/workflows", "POST", {"name": "实测·内部搬运"}, token)["id"]
    api("/api/workflows/%d/graph" % wf, "PUT", raw=XML % res, token=token)
    api("/api/workflows/%d/nodes/T1" % wf, "PUT", token=token,
        body={"method": "MoveInLabware",
              "params": {"plate": plate, "from_position": LOAD,
                         "to_position": SLOT, "plate_type": plate_type},
              "resourceId": res})
    r = api("/api/workflows/%d/run" % wf, "POST", {}, token)
    if isinstance(r, dict) and r.get("error"):
        return None, None, r.get("error")
    time.sleep(4)
    return plate_on(LOAD), plate_on(SLOT), None


def main():
    token = api("/api/auth/login", "POST",
                {"username": "admin", "password": "admin123"})["token"]

    res = q("SELECT id FROM resource_node WHERE code='%s' AND kind='device'" % DEV_CODE)
    if not res:
        bad("找不到设备 %s" % DEV_CODE)
        return 1
    res = int(res)
    slot_cfg = q("SELECT array_to_string(plate_types, ',') FROM device_point dp "
                 "JOIN resource_node r ON r.id=dp.point_resource_id "
                 "WHERE r.code='%s'" % SLOT)
    load_std = q("SELECT count(*) FROM device_point dp JOIN resource_node r "
                 "ON r.id=dp.point_resource_id WHERE r.code='%s' AND dp.is_standalone" % LOAD)
    if slot_cfg and int(load_std) >= 1:
        ok("前置：%s 装载口（单独位置）+ 内部格 %s（允许板型 %s）就绪"
           % (DEV_CODE, SLOT, slot_cfg))
    else:
        bad("前置不满足：装载口/内部格未配置",
            "slot=%s load_standalone=%s" % (slot_cfg, load_std))
        return 1

    print("── 用例① 板型匹配（pcr96）→ 搬进内部格 ──")
    p1 = "PL-IT-%d" % int(time.time() % 100000)
    on_load, on_slot, err = run_case(token, res, p1, "pcr96")
    if err:
        bad("用例① 跑流程失败", err)
    elif on_slot == p1 and on_load == "":
        ok("板 %s 从装载口搬进内部格 %s" % (p1, SLOT), "内部格=%s" % on_slot)
    else:
        bad("用例① 板型匹配却未搬进内部格", "装载口=%s 内部格=%s" % (on_load, on_slot))

    print("── 用例② 板型不匹配（elisa96）→ 被拒留在装载口 ──")
    p2 = "PL-IT-%d" % (int(time.time() % 100000) + 1)
    on_load, on_slot, err = run_case(token, res, p2, "elisa96")
    if err:
        bad("用例② 跑流程失败", err)
    elif on_load == p2 and on_slot == "":
        ok("板型 elisa96 ∉ {%s} 被拒，板 %s 仍在装载口 %s" % (slot_cfg, p2, LOAD),
           "内部格空=%s" % (on_slot == ""))
    else:
        bad("用例② 板型不匹配却被放行", "装载口=%s 内部格=%s" % (on_load, on_slot))

    # 清理测试板
    for p in (p1, p2):
        q("DELETE FROM plate_location_audit WHERE barcode='%s'" % p)
        q("DELETE FROM plate WHERE barcode='%s'" % p)
    q("UPDATE position_state SET plate_id=NULL, content_kind=NULL, content_id=NULL, "
      "occupied_by_run=NULL, occupied_since=NULL, updated_at=now() "
      "WHERE resource_id IN (SELECT id FROM resource_node WHERE parent_id=%d)" % res)

    print("\n通过 %d / 失败 %d" % (PASS, FAIL))
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
