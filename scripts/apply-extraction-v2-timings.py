#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把客户《高通量核酸提取工作站描述 **v2**》里的**现场标定时间**落进中控时长模型。

**为什么要换口径（2026-11-19）**
中控登记的方法时长原来是**厂商公开规格**（KingFisher 协议 PDF：结合孵育 8 min / 洗涤 3 min /
风干 10 min），洗脱更是 ★估算（协议未给，取 5 min）。
客户 v2 说明书给了**这台机器的现场口径**工序时间，比公开规格更贴近实际 ⇒
**现场标定值优先，公开规格降级为兜底**。

**v2 的时间（§3 单批自动化时序流程）**
| 方法 | 现行登记 | v2 现场口径 | 依据 |
|---|---|---|---|
| Bind（结合＝内部裂解计时） | 480 s | **200 s** | 阶段2 第5条「启动内部裂解计时（200s）」 |
| Wash（漂洗＝W1/W2/W3 洗涤） | 180 s | **150 s** | 阶段4/5/6「W1/W2/W3 洗涤（150s）」 |
| Elute（洗脱，含晾干） | 300 s | **600 s** | 阶段7「执行晾干和洗脱工序（600s）」 |
| Transfer（机械臂取放板） | 10 s | **15 s** | 阶段1 第2条「15s完成取放板操作，以下取放板都按此估算」 |
| Run（分液器分液） | 10 s | 10 s（已一致） | 阶段2 第2条「分液完成…（10s，以下分液都按10s计算）」 |

**两处口径澄清（同时写进需求确认清单）**
① 阶段4 把「150s」标在 *W1 洗涤液分液* 上，但阶段2 明说「以下分液都按照10s计算」，
   且阶段5/6/7 把「150s」标在 *洗涤* 上 ⇒ 按对称结构读作 **W1 洗涤 = 150s**（笔误）。
② 洗脱 600s 含**晾干** —— 与 KingFisher「风干 10 min」同量级 ⇒ 反而**更接近**公开规格。

**没落进方法时长的：初始化 60s**
v2 阶段1 第1条有「初始化（60s初始化）」，但 PAuto 协议帧**只有一个命令码 0 + 9 个动作**
（D0=0~8：结合/洗脱/丢磁珠/取磁珠/孵育/漂洗/取磁套/卸磁套/换板位）
**没有「启动提取流程」指令** ⇒ 这 60s 是「等【等待交互】」的等待期，不是设备动作时长。
平台侧的通用抽象已经就绪（carousel_extractor 画像的 StartProcess / Continue，
durationParam 60~3600），等客户确认【启动提取流程】/【继续信号】走哪条通道后再建模
（需求确认清单 甲-1 第 4 问）。

**用法**
    python3 scripts/apply-extraction-v2-timings.py           # 只看会改什么（dry-run）
    python3 scripts/apply-extraction-v2-timings.py --apply   # 真写

⚠ 改了 device_method.estimate_sec 之后，**模拟器要重启才生效**
   （模拟器在启动时读一次中控登记的时长：--method-estimates / 设备清单）。
"""
import argparse
import json
import math
import os
import subprocess
import sys
import urllib.error
import urllib.request

API = os.environ.get("LABOS_API", "http://127.0.0.1:18080").rstrip("/")
USER = os.environ.get("LABOS_USER", "admin")
PASSWORD = os.environ.get("LABOS_PASS", "admin123")
DB_HOST = os.environ.get("LABOS_DB_HOST", "192.168.124.2")
DB_PORT = os.environ.get("LABOS_DB_PORT", "5432")
DB_NAME = os.environ.get("LABOS_DB_NAME", "labdb")
DB_USER = os.environ.get("LABOS_USER_DB", "labos")
DB_PASS = os.environ.get("LABOS_DB_PASS", "Labos@Lab2026")

# (型号码, 方法名, v2 秒, 依据)
V2 = [
    ("P_AUTO", "Bind", 200, "§3 阶段2 第5条：启动内部裂解计时（200s）"),
    ("P_AUTO", "Wash", 150, "§3 阶段4/5/6：W1/W2/W3 洗涤（150s）"),
    ("P_AUTO", "Elute", 600, "§3 阶段7：执行晾干和洗脱工序（600s）"),
    ("EQ145", "Transfer", 15, "§3 阶段1 第2条：15s 完成取放板操作，以下取放板都按此估算"),
    ("EQ165", "Run", 10, "§3 阶段2 第2条：分液完成（10s，以下分液都按 10s 计算）"),
]

TOKEN = None


def psql(sql):
    env = dict(os.environ, PGPASSWORD=DB_PASS)
    p = subprocess.run(["psql", "-h", DB_HOST, "-p", DB_PORT, "-U", DB_USER, "-d", DB_NAME,
                        "-A", "-t", "-F", "|", "-v", "ON_ERROR_STOP=1", "-c", sql],
                       capture_output=True, text=True, env=env)
    if p.returncode != 0:
        sys.exit("✘ SQL 失败：\n" + p.stderr.strip())
    return [ln for ln in p.stdout.strip().splitlines() if ln]


def call(method, path, body=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(API + path, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if TOKEN:
        req.add_header("Authorization", "Bearer " + TOKEN)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            t = r.read().decode("utf-8")
            return r.status, (json.loads(t) if t.strip() else {})
    except urllib.error.HTTPError as e:
        return e.code, {"error": e.read().decode("utf-8", "replace")[:300]}
    except Exception as e:
        return 0, {"error": str(e)}


def login():
    global TOKEN
    st, j = call("POST", "/api/auth/login", {"username": USER, "password": PASSWORD})
    if st != 200 or not j.get("token"):
        sys.exit("✘ 登录失败（%s）：%s" % (st, j))
    TOKEN = j["token"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true")
    args = ap.parse_args()
    login()
    print("=" * 72)
    print("客户 v2 现场标定时间 → 中控方法时长（dry-run=%s）" % args.apply)
    print("=" * 72)

    plan = []
    for model_code, method, sec, why in V2:
        rows = psql("SELECT dm.id, dm.estimate_sec, dm.timeout_sec FROM device_method dm "
                    "JOIN device d ON d.id = dm.device_model_id "
                    "WHERE d.code = '%s' AND dm.method = '%s'" % (model_code, method))
        if not rows:
            print("✘ 型号 %s 上没有方法 %s —— 先登记设备方法" % (model_code, method))
            continue
        mid, old_est, old_to = rows[0].split("|")
        old_est, old_to = int(old_est), int(old_to)
        new_to = max(old_to, int(math.ceil(sec * 1.25)))
        plan.append(dict(mid=int(mid), model=model_code, method=method, old=old_est,
                         new=sec, old_to=old_to, new_to=new_to, why=why))
        print("%-8s %-10s 登记 %4d s → %4d s   超时 %4d s → %4d s" %
              (model_code, method, old_est, sec, old_to, new_to))
        print("         依据：%s" % why)

    if not plan:
        sys.exit("✘ 没有可改的项")

    if not args.apply:
        print("\n（dry-run：%d 项待改，加 --apply 才写；改完记得重启模拟器）" % len(plan))
        return

    for p in plan:
        psql("UPDATE device_method SET estimate_sec = %d, timeout_sec = %d "
             "WHERE id = %d" % (p["new"], p["new_to"], p["mid"]))
        # 时长模型（页面可改的那张表）：p50=标定值 / p90=×1.5 / min=×0.5 / max=×3
        # sample_count = 1（客户 v2 给了一组现场标定值，不是我们自己的实测样本）
        p50 = p["new"] * 1000
        st, r = call("PUT", "/api/durations", {
            "method": p["method"], "p50Ms": p50, "p90Ms": int(p50 * 1.5),
            "minMs": int(p50 * 0.5), "maxMs": int(p50 * 3), "sampleCount": 1})
        print("  ✔ %s 时长登记 %d s（模型 %s）" % (p["method"], p["new"], p["model"]))

    print("\n✔ 已写 %d 项。**模拟器需重启才生效**（启动时读一次中控登记的时长）。" % len(plan))


if __name__ == "__main__":
    main()