#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""补三条「型号方法集 ∩ 模拟器画像」缺口的方法别名（幂等）。

背景：模拟器**按画像校验方法名**（`sim-up.sh` 把 `device_method` 里该设备的
全部方法用 `--methods` 传给模拟器；画像里没有的方法 ⇒ 模拟器拒收 ⇒
`EC-06-0004` 节点转人工，现象像"流程配错了"）。

而 `device_method` 表里，这三个型号的方法名与对应画像**对不上**：

| 型号 | 能力 | 型号表里的方法 | 模拟器画像要的方法 | 结果 |
|---|---|---|---|---|
| EQ162 | PEEL  | StartPeel / SetSealThreshold / EnablePlateCheck / Reset | **Peel** / Load / Unload / GetStatus | 撕膜跑不了 |
| EQ160 | SCAN  | Capture | **Scan** / Home / GetStatus | 扫码跑不了 |
| EQ173 | READ_PLATE | ReadPlate / ReadPlateEx / ReadBarcode … | **Read** / Load / Unload / GetResult … | 读板只能用 ReadPlate |

本脚本给这三个型号各补一条**别名方法行**（不改原有行、不覆盖现场配置），
补完对应设备重启模拟器即可用。

用法：
    python3 scripts/seed-device-method-alias.py            # 执行
    python3 scripts/seed-device-method-alias.py --dry-run  # 只看会做什么

幂等：按 (device_model_id, method) 判存，已存在就跳过。
依赖：LABOS_DB_HOST / LABOS_DB_PORT / LABOS_DB_USER / LABOS_DB_PASS / LABOS_DB_NAME
"""
import json
import os
import subprocess
import sys

DRY = "--dry-run" in sys.argv


def q(sql):
    """跑一条 SQL，返回去空行后的列表。"""
    env = dict(os.environ)
    env["PGPASSWORD"] = os.environ.get("LABOS_DB_PASS", "")
    cmd = [
        "psql",
        "-h", os.environ.get("LABOS_DB_HOST", "127.0.0.1"),
        "-p", os.environ.get("LABOS_DB_PORT", "5432"),
        "-U", os.environ.get("LABOS_DB_USER", "labos"),
        "-d", os.environ.get("LABOS_DB_NAME", "labdb"),
        "-tA", "-v", "ON_ERROR_STOP=1", "-c", sql,
    ]
    r = subprocess.run(cmd, capture_output=True, text=True, env=env)
    if r.returncode != 0:
        raise RuntimeError("SQL 失败: " + r.stderr.strip()[:400])
    return [x for x in (ln.strip() for ln in r.stdout.splitlines()) if x]


def param(name, desc=None, default=None, required=None, rng=None, ctype="input"):
    return {
        "seq": 0, "code": "0", "desc": desc, "name": name, "type": "General",
        "unit": "string", "range": rng, "value": None, "default": default,
        "required": required, "value_type": None, "control_type": ctype,
    }


# (型号 code, 方法名, 能力码, 参数, 估时秒, 超时秒)
ALIASES = [
    (
        "EQ162", "Peel", "PEEL",
        [], 20, 600,
        "撕膜（画像 peeler 支持 Peel / Load / Unload）；原型号只有 StartPeel，"
        "两者在模拟器里是不同方法名 ⇒ 缺别名就发不出去。",
    ),
    (
        "EQ160", "Scan", "SCAN",
        [param("barcode", "要读的条码；不填则模拟器按 'SCAN-<设备>-<序号>' 生成", ctype="input")],
        10, 600,
        "扫码（画像 barcode 支持 Scan）；请求里带 barcode 会原样回显，便于验收注入指定值。",
    ),
    (
        "EQ173", "Read", "READ_PLATE",
        [
            param("plate_id", "板位/板号"),
            param("protocol", "读板协议名"),
            param("barcode", "板条码"),
            param("wavelength", "波长 nm（模拟器校验 190–850，超范围会报错）", rng="190;850"),
        ],
        15, 600,
        "读板（画像 reader 支持 Read，模拟器按 15 s 计时）；**读之前门必须已关**，"
        "否则模拟器回「门未关闭，不能执行 Read」。",
    ),
]


def main():
    print("=== 方法别名补齐" + ("（dry-run）" if DRY else "") + " ===")
    for model_code, method, cap_code, params, est, timeout, note in ALIASES:
        models = q("SELECT id FROM device WHERE code = '%s';" % model_code)
        if not models:
            print("  ⚠ 型号不存在: %s（跳过）" % model_code)
            continue
        model_id = models[0]
        caps = q("SELECT id FROM capability WHERE code = '%s';" % cap_code)
        cap_id = caps[0] if caps else "NULL"

        exist = q(
            "SELECT id FROM device_method WHERE device_model_id = %s AND method = '%s';"
            % (model_id, method)
        )
        if exist:
            print("  = %s / %s 已存在（id=%s，跳过）" % (model_code, method, exist[0]))
            continue

        schema = {
            "params": params,
            "legacy_status": "1",
            "protocol_type": "action",
            "capability_code": cap_code,
            "legacy_protocol_id": None,
        }
        sql = (
            "INSERT INTO device_method (tenant_id, device_model_id, method, name, "
            "capability_id, param_schema, estimate_sec, timeout_sec, interruptible, scope) "
            "VALUES (1, %s, '%s', '%s', %s, '%s'::jsonb, %d, %d, false, 'model');"
            % (model_id, method, method, cap_id,
               json.dumps(schema, ensure_ascii=False).replace("'", "''"), est, timeout)
        )
        if DRY:
            print("  · 将新增: %s / %s（cap=%s, est=%ds, timeout=%ds）"
                  % (model_code, method, cap_code, est, timeout))
            print("    %s" % note)
            continue
        q(sql)
        got = q("SELECT id FROM device_method WHERE device_model_id = %s AND method = '%s';"
                % (model_id, method))
        print("  ✓ 新增 %s / %s（id=%s, cap=%s, est=%ds, timeout=%ds）"
              % (model_code, method, got[0] if got else "?", cap_code, est, timeout))
        print("    %s" % note)

    print()
    print("提示：device_method 是**型号级**的，改完要重启对应设备的模拟器，")
    print("      否则模拟器仍按启动时收到的 --methods 名单拒收新方法。")
    print("      重启方式：SIM_BASE_PORT=<段> ./labos-kernel/scripts/sim-up.sh <岛编号> 0 fast")


if __name__ == "__main__":
    main()
