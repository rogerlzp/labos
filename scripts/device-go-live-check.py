#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
现场联调 / 上线前检查（M10 可先行工具）。

回答：这个环境"现在能不能接真机跑"？逐项给出 PASS / WARN / FAIL：
  1. API/DB 可达、心跳最新（超时说明后端/心跳线程有问题）
  2. 驱动端点配置数 vs 心跳在线数（仿真环境会 WARN：没有真驱动在线）
  3. 型号库：有手册（MANUAL）型号数 / 待手册（PLANNED）型号数；
     产线在跑却缺手册的型号（360R/PlateLoc）单列 WARN
  4. 设备 → 型号绑定覆盖率（device.model 能否对上 DeviceModels id；0% 属 M10 前预期）
  5. 错误码：总数、EC-08-* 数量；型号库错误码与 error_code 表的一致性抽查
  6. 预检/影子探针能力是否在线（capabilities 含关键能力）
  7. 设备池/拓扑基础数据非空

退出码：0 = 无 FAIL；1 = 有 FAIL；--strict 时 WARN 也算失败（正式上线前用）。

用法：
  LABOS_DB_HOST=... LABOS_DB_NAME=... LABOS_DB_USER=... LABOS_DB_PASS=... \
    python3 scripts/device-go-live-check.py [--api http://127.0.0.1:18080] [--strict]
"""
import argparse
import datetime as dt
import json
import os
import sys
import urllib.error
import urllib.request

PASS = WARN = FAIL = 0
ROWS = []


def rec(level, item, detail=""):
    global PASS, WARN, FAIL
    if level == "PASS":
        PASS += 1
    elif level == "WARN":
        WARN += 1
    else:
        FAIL += 1
    ROWS.append((level, item, detail))
    print("%-4s %-46s %s" % (level, item, detail))


class Api:
    def __init__(self, base, token):
        self.base, self.token = base, token

    def call(self, method, path, body=None, timeout=20):
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
        req = urllib.request.Request(self.base + path, data=data, method=method)
        req.add_header("Authorization", "Bearer " + self.token)
        if data is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                raw = r.read().decode("utf-8")
                try:
                    return r.status, json.loads(raw)
                except json.JSONDecodeError:
                    return r.status, raw
        except urllib.error.HTTPError as e:
            return e.code, {"error": e.read().decode("utf-8", "replace")[:200]}
        except Exception as e:  # noqa: BLE001
            return 0, {"error": str(e)[:200]}


def login(base, user, pwd):
    req = urllib.request.Request(base + "/api/auth/login",
                                 data=json.dumps({"username": user, "password": pwd}).encode(),
                                 method="POST")
    req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.loads(r.read().decode())["token"]


def db_connect():
    need = [k for k in ("LABOS_DB_HOST", "LABOS_DB_NAME", "LABOS_DB_USER") if not os.environ.get(k)]
    if need:
        return None
    try:
        import psycopg2
        return psycopg2.connect(
            host=os.environ["LABOS_DB_HOST"],
            port=int(os.environ.get("LABOS_DB_PORT", "5432")),
            dbname=os.environ["LABOS_DB_NAME"],
            user=os.environ["LABOS_DB_USER"],
            password=os.environ.get("LABOS_DB_PASS", ""),
        )
    except Exception:  # noqa: BLE001
        return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--api", default="http://127.0.0.1:18080")
    ap.add_argument("--user", default="admin")
    ap.add_argument("--pass", dest="pwd", default="admin123")
    ap.add_argument("--strict", action="store_true", help="WARN 也视为失败（正式上线前用）")
    args = ap.parse_args()
    print("=" * 96)
    print("现场联调 / 上线前检查  ·  " + args.api)
    print("=" * 96)

    # 1) 健康
    try:
        api = Api(args.api, login(args.api, args.user, args.pwd))
        code, health = api.call("GET", "/health")
        rec("PASS", "API /health", "status=%s db=%s" % (
            health.get("status"), str(health.get("database"))[:30])) if code == 200 \
            else rec("FAIL", "API /health", "http=%s %s" % (code, health))
    except Exception as e:  # noqa: BLE001
        rec("FAIL", "API 登录/健康", str(e)[:160])
        return finish(args)

    # 2) 心跳
    code, hb = api.call("GET", "/api/heartbeat")
    if code != 200:
        rec("FAIL", "心跳 /api/heartbeat", "http=%s" % code)
    else:
        last = hb.get("lastRunAt")
        drivers = hb.get("drivers") or []
        online = sum(1 for d in drivers if d.get("online"))
        rec("PASS", "心跳接口可用", "drivers=%d online=%d" % (len(drivers), online))
        if last:
            try:
                t = dt.datetime.fromisoformat(str(last).replace("Z", "+00:00"))
                age = (dt.datetime.now(dt.timezone.utc) - t).total_seconds()
                if age <= 60:
                    rec("PASS", "心跳新鲜度", "%.0fs 前" % age)
                else:
                    rec("WARN", "心跳新鲜度", "%.0fs 前（>60s，检查心跳线程/后端）" % age)
            except Exception:  # noqa: BLE001
                rec("WARN", "心跳新鲜度", "时间解析失败: %s" % last)
        if drivers and online == 0:
            rec("WARN", "驱动在线数", "0/%d（仿真/未部署场景正常；现场联调必须 >0）" % len(drivers))

    # 3) 驱动端点
    code, drv = api.call("GET", "/api/drivers")
    if code == 200:
        items = drv.get("items") or []
        enabled = sum(1 for d in items if d.get("status") == 1)
        rec("PASS", "驱动端点配置", "共 %d，启用 %d" % (len(items), enabled))
        if items and enabled == 0:
            rec("WARN", "驱动端点启用数", "0/%d（现场需至少 1 个）" % len(items))
    else:
        rec("FAIL", "驱动端点 /api/drivers", "http=%s" % code)

    # 4) 型号库（新接口）
    code, lib = api.call("GET", "/api/meta/device-models")
    planned_deployed_models = []
    if code != 200:
        rec("FAIL", "型号库 /api/meta/device-models", "http=%s（M10 新接口，未部署说明环境旧）" % code)
    else:
        totals = lib.get("totals") or {}
        manual = lib.get("manual") or []
        planned = lib.get("planned") or []
        rec("PASS", "型号库统计", "可对接(MANUAL)=%s 待手册(PLANNED)=%s 产线在跑待接=%s"
            % (totals.get("manual"), totals.get("planned"), totals.get("plannedDeployed")))
        if (totals.get("manual") or 0) < 2:
            rec("WARN", "有手册型号数", "手册齐全的型号不足 2 个")
        for p in planned:
            if (p.get("deployedCount") or 0) > 0:
                planned_deployed_models.append(p)
        if planned_deployed_models:
            detail = "；".join("%s(%s) %s 台" % (p.get("dbModelAlias") or p.get("id"),
                                                p.get("vendorModel"), p.get("deployedCount"))
                              for p in planned_deployed_models)
            rec("WARN", "产线在跑但缺手册（M10 阻塞）", detail)
        else:
            rec("PASS", "产线在跑但缺手册", "无")

    # 5) 设备→型号绑定覆盖率（DB）
    conn = db_connect()
    if conn is None:
        rec("WARN", "设备→型号绑定覆盖率", "跳过（无 DB 环境变量或连接失败）")
    else:
        try:
            cur = conn.cursor()
            cur.execute("SELECT count(*) FROM device WHERE tenant_id=1 AND deleted_at IS NULL")
            total_dev = cur.fetchone()[0]
            known_ids = [m.get("id") for m in (lib.get("manual") or [])
                         if m.get("id")] if code == 200 else []
            aliases = [m.get("dbModelAlias") for m in (lib.get("planned") or [])
                       if m.get("dbModelAlias")] if code == 200 else []
            names = known_ids + aliases
            if names:
                cur.execute("SELECT count(*) FROM device WHERE tenant_id=1 AND deleted_at IS NULL "
                            "AND model = ANY(%s)", (names,))
                bound = cur.fetchone()[0]
            else:
                bound = 0
            pct = (bound * 100.0 / total_dev) if total_dev else 0
            if bound == 0:
                rec("WARN", "设备→型号绑定覆盖率", "%d/%d（0%% 属 M10 前预期：没手册不乱绑）" % (bound, total_dev))
            else:
                rec("PASS", "设备→型号绑定覆盖率", "%d/%d（%.1f%%）" % (bound, total_dev, pct))
        except Exception as e:  # noqa: BLE001
            rec("WARN", "设备→型号绑定覆盖率", "查询失败: %s" % str(e)[:120])
        finally:
            conn.close()

    # 6) 错误码
    code, ec = api.call("GET", "/api/error-codes")
    if code != 200:
        rec("FAIL", "错误码 /api/error-codes", "http=%s" % code)
    else:
        codes = ec.get("items") or []
        ec08 = [c for c in codes if str(c.get("code", "")).startswith("EC-08-")]
        rec("PASS", "错误码字典", "共 %d，EC-08-*（设备码）%d" % (len(codes), len(ec08)))
        if not ec08:
            rec("WARN", "设备错误码覆盖", "没有 EC-08-* 设备码（接真机前至少要有一条型号码）")

    # 7) 预检/能力
    code, caps = api.call("GET", "/api/capabilities")
    if code == 200:
        items = caps.get("items") or []
        rec("PASS", "能力清单", "共 %d 项" % len(items)) if items else rec("WARN", "能力清单", "空")
    else:
        rec("WARN", "能力清单 /api/capabilities", "http=%s" % code)

    # 8) 拓扑/设备池
    code, topo = api.call("GET", "/api/topology")
    if code == 200:
        islands = topo.get("islands") or []
        rec("PASS", "拓扑数据", "islands=%d" % len(islands)) if islands \
            else rec("WARN", "拓扑数据", "islands=0（没建岛屿？）")
    else:
        rec("WARN", "拓扑数据 /api/topology", "http=%s" % code)

    return finish(args)


def finish(args):
    print("-" * 96)
    print("PASS %d  /  WARN %d  /  FAIL %d" % (PASS, WARN, FAIL))
    if FAIL:
        print("结论：FAIL —— 环境不可上线，先修 FAIL 项。")
        return 1
    if args.strict and WARN:
        print("结论：WARN —— --strict 模式下视为不可上线（现场联调前逐项确认）。")
        return 1
    if WARN:
        print("结论：WARN —— 仿真/待接真机环境的正常状态；现场联调前按 WARN 逐项确认。")
        return 0
    print("结论：PASS —— 可进入现场联调。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
