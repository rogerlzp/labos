#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""建一套标准「物料角色」字典（幂等）。

背景：`node_binding.material_role` 与模板的 `manifest_items.role_code` 都要求填角色，
但**这个字典原本没有任何界面入口**（`GET/POST /api/material-roles` 有，页面没有），
新环境里它是空的 ⇒ 点位视角右上角的「物料角色」下拉是空的 ⇒ 绑定存下来 role=null
⇒ 预检 PF-23 一直提示、跨点位搬运不知道搬哪块板。

用法：
    python3 scripts/seed-material-roles.py                       # 用默认 API 与管理员账号
    LABOS_API=http://127.0.0.1:18080 python3 scripts/seed-material-roles.py
    python3 scripts/seed-material-roles.py --dry-run

幂等：按 roleCode 判存，已存在就跳过（不覆盖现场改过的显示名）。
"""
import json
import os
import sys
import urllib.error
import urllib.request

API = os.environ.get("LABOS_API", "http://127.0.0.1:18080").rstrip("/")
USER = os.environ.get("LABOS_USER", "admin")
PWD = os.environ.get("LABOS_PASS", "admin123")

# 角色集对应核酸提取线的物料分区（客户 §3 阶段用到的那几类）。
# physicalType 是**物理形态**（plate/lid/rack/reservoir/tip_box），不是业务名。
ROLES = [
    {"roleCode": "mainPlate", "displayName": "主反应板", "physicalType": "plate",
     "plateType": "96", "barcodeRegex": "^[A-Z0-9_-]{4,64}$"},
    {"roleCode": "magPlate", "displayName": "磁套板", "physicalType": "plate",
     "plateType": "96"},
    {"roleCode": "sample", "displayName": "样本板", "physicalType": "plate",
     "plateType": "96"},
    {"roleCode": "reagent", "displayName": "试剂槽", "physicalType": "reservoir"},
    {"roleCode": "tipBox", "displayName": "吸头盒", "physicalType": "tip_box"},
]


def call(path, method="GET", body=None, token=None):
    req = urllib.request.Request(API + path, method=method)
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, data=data, timeout=20) as r:
        return json.loads(r.read() or b"{}")


def main():
    dry = "--dry-run" in sys.argv
    token = call("/api/auth/login", "POST", {"username": USER, "password": PWD})["token"]
    existing = {r.get("roleCode") for r in call("/api/material-roles", token=token).get("items", [])}
    print(f"现有角色 {len(existing)} 个：{sorted(existing)}")
    created, skipped = [], []
    for role in ROLES:
        if role["roleCode"] in existing:
            skipped.append(role["roleCode"])
            continue
        if dry:
            print(f"  [dry-run] 将创建 {role['roleCode']} / {role['displayName']}")
            continue
        try:
            call("/api/material-roles", "POST", role, token=token)
            created.append(role["roleCode"])
            print(f"  + {role['roleCode']} / {role['displayName']}")
        except urllib.error.HTTPError as e:
            detail = e.read().decode(errors="ignore")[:200]
            print(f"  ! {role['roleCode']} 创建失败 {e.code}: {detail}")
    total = len(call("/api/material-roles", token=token).get("items", []))
    print(f"\n完成：新建 {len(created)}，跳过 {len(skipped)}，当前共 {total} 个角色")
    if skipped:
        print("  已存在（未覆盖）：" + "、".join(skipped))


if __name__ == "__main__":
    main()
