#!/usr/bin/env python3
"""补 6 类缺失设备（撕膜/PCR仪/液体工作站/磁力架/洗板/制冷载架）的模拟器 + 拖进 LAB-BASE-01 岛。

- 已有 device_instance + device_method 的（撕膜/液体工作站/洗板/板库/载架）：
  把 resource_node 挂到岛 1000005 + 建 mock driver + 起 DeviceSimulator + sidecar。
- 库里没有的（PCR仪 THERMOCYCLE、磁力架 MIX）：先建 device/device_instance/resource_node/
  resource_capability/device_method，再起模拟器。

用法：python3 scripts/s2-add-missing-sims.py
"""
import os
import re
import subprocess
import time

DB = ["psql", "-h", "192.0.2.2", "-p", "5432", "-U", "labos", "-d", "labdb"]
ISLAND = 1000005  # LAB-BASE-01
JAVA = "java"
CP = "out:lib/postgresql-42.7.3.jar:lib/spring/*"
SIM_MAIN = "com.labos.kernel.sim.DeviceSimulator"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KERNEL = os.path.join(ROOT, "labos-kernel")
SIDECAR = os.path.join(ROOT, "sidecar-dotnet", "Labos.Sidecar", "bin", "Debug", "net10.0",
                       "Labos.Sidecar.dll")


DB_TIMEOUT_SEC = 15  # 单条 SQL 上限（含锁等待）。psql 默认无超时，等锁会无限卡死。
# psql 的 -t 只吞掉 SELECT 的表头/行数，不吞命令标签。实测 psql 18.3：
#   -tA -c "BEGIN; ... INSERT ... RETURNING i; ROLLBACK" 会输出
#   BEGIN / CREATE TABLE / 7 / INSERT 0 1 / ROLLBACK —— 返回值被夹在标签中间。
# 根治办法是给 psql 加 -q（--quiet）：命令标签从源头不产生，RETURNING 只剩真实值。
# 下面的正则只是兜底（例如有人绕过 q() 直接调 psql），不要把它当主修。
_CMD_TAG = re.compile(
    r'^(?:INSERT \d+ \d+|UPDATE \d+|DELETE \d+|SELECT \d+|COPY \d+|MOVE \d+|FETCH \d+'
    r'|BEGIN|COMMIT|ROLLBACK|END|START TRANSACTION|SAVEPOINT|RELEASE'
    r'|CREATE|ALTER|DROP|TRUNCATE|GRANT|REVOKE|SET|RESET|SHOW|DISCARD|DO|CALL'
    r'|PREPARE|EXECUTE|DEALLOCATE|LISTEN|UNLISTEN|NOTIFY|COMMENT|ANALYZE|VACUUM'
    r'|REINDEX|CLUSTER|REFRESH|LOCK|CHECKPOINT)(?: [A-Z ]*)?$')


def q(sql):
    env = dict(os.environ, PGPASSWORD="REDACTED_DB_PASSWORD")
    # lock_timeout：拿不到锁就立刻报错；statement_timeout：SQL 本身执行太久也报错。
    # 两者都比 subprocess 的 timeout 先触发，能拿到更明确的报错信息。
    env["PGOPTIONS"] = (f"-c lock_timeout={DB_TIMEOUT_SEC * 1000} "
                        f"-c statement_timeout={DB_TIMEOUT_SEC * 1000}")
    try:
        r = subprocess.run(DB + ["-q", "-tAc", sql], capture_output=True, text=True, env=env,
                           timeout=DB_TIMEOUT_SEC + 5)
    except subprocess.TimeoutExpired:
        raise RuntimeError(f"SQL 超时（>{DB_TIMEOUT_SEC}s，疑似等数据库锁）: {sql}")
    if r.returncode != 0:
        raise RuntimeError(f"SQL 失败: {sql}\n{r.stderr}")
    lines = [ln for ln in r.stdout.splitlines()
             if ln.strip() and not _CMD_TAG.match(ln.strip())]
    return "\n".join(lines).strip()


def create_driver(code, name, port):
    endpoint = f"tcp://127.0.0.1:{port}"
    existing = q(f"SELECT id FROM driver WHERE tenant_id = 1 AND code = '{code}' "
                 f"AND deleted_at IS NULL ORDER BY id LIMIT 1")
    if existing:
        # 已存在就原地更新，避免 DELETE 触发 device_instance_driver_id_fkey 外键冲突
        q(f"UPDATE driver SET name = '{name}', endpoint = '{endpoint}', "
          f"manifest = '{{}}'::jsonb, status = 1 WHERE id = {existing}")
    else:
        q(f"INSERT INTO driver (tenant_id, code, name, protocol, endpoint, manifest, status) "
          f"VALUES (1, '{code}', '{name}', 'mock', '{endpoint}', '{{}}'::jsonb, 1)")


def move_to_island(res, code, cap, profile, port, dev_id, methods):
    """已有设备：挂岛 + driver + 模拟器。"""
    q(f"UPDATE resource_node SET parent_id = {ISLAND} WHERE id = {res}")
    q(f"UPDATE device_instance SET ip = '127.0.0.1' WHERE id = {dev_id}")
    drv = f"DRV-SIM-{code}"
    create_driver(drv, f"模拟-{code}", port)
    q(f"UPDATE device_instance SET driver_id = (SELECT id FROM driver WHERE code='{drv}') "
      f"WHERE id = {dev_id}")
    launch_sim(port, dev_id, code, cap, profile, methods)


def create_new_device(code, name, cap_id, cap_code, profile, port, methods):
    """库里没有的设备：全量建。"""
    res = q(f"INSERT INTO resource_node (tenant_id, code, name, parent_id, kind) "
            f"VALUES (1, '{code}', '{name}', {ISLAND}, 'device') "
            f"ON CONFLICT (tenant_id, code) DO UPDATE SET name = EXCLUDED.name, "
            f"parent_id = EXCLUDED.parent_id, kind = EXCLUDED.kind RETURNING id")
    dev = q(f"INSERT INTO device (tenant_id, code, name, model, device_type, capability_id) "
            f"VALUES (1, '{code}', '{name}', '{name}', '{profile}', {cap_id}) "
            f"ON CONFLICT (tenant_id, code) DO UPDATE SET name = EXCLUDED.name, "
            f"model = EXCLUDED.model, device_type = EXCLUDED.device_type, "
            f"capability_id = EXCLUDED.capability_id RETURNING id")
    di = q(f"INSERT INTO device_instance (tenant_id, code, name, device_id, resource_id, ip) "
           f"VALUES (1, '{code}', '{name}', {dev}, {res}, '127.0.0.1') "
           f"ON CONFLICT (tenant_id, code) DO UPDATE SET name = EXCLUDED.name, "
           f"device_id = EXCLUDED.device_id, resource_id = EXCLUDED.resource_id, "
           f"ip = EXCLUDED.ip RETURNING id")
    q(f"INSERT INTO resource_capability (resource_id, capability_id, params) "
      f"SELECT {res}, {cap_id}, '{{}}' WHERE NOT EXISTS "
      f"(SELECT 1 FROM resource_capability WHERE resource_id={res} AND capability_id={cap_id})")
    for m in methods.split(","):
        q(f"INSERT INTO device_method (tenant_id, device_id, method, name, capability_id, "
          f"param_schema, estimate_sec, timeout_sec) "
          f"SELECT 1, {dev}, '{m}', '{m}', {cap_id}, '{{}}'::jsonb, 5, 600 "
          f"WHERE NOT EXISTS (SELECT 1 FROM device_method WHERE device_id={dev} AND method='{m}')")
    drv = f"DRV-SIM-{code}"
    create_driver(drv, f"模拟-{code}", port)
    q(f"UPDATE device_instance SET driver_id = (SELECT id FROM driver WHERE code='{drv}') "
      f"WHERE id = {di}")
    launch_sim(port, int(di), code, cap_code, profile, methods)


def port_open(port):
    r = subprocess.run(["nc", "-z", "127.0.0.1", str(port)], capture_output=True)
    return r.returncode == 0


def launch_sim(port, dev_id, code, cap, profile, methods):
    if port_open(port):
        print(f"  {code} 端口 {port} 已有模拟器在监听，跳过启动")
        return
    args = (f"{JAVA} -cp \"{CP}\" {SIM_MAIN} --port {port} --device {dev_id} "
            f"--code {code} --cap {cap} --profile {profile} --delay 0 "
            f"--methods {methods} --rand-delay 2000:5000 "
            f">> /tmp/sim-{code}.log 2>&1")
    subprocess.Popen(args, shell=True, cwd=KERNEL, start_new_session=True,
                     stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                     stderr=subprocess.DEVNULL)
    time.sleep(1.5)
    # sidecar
    sargs = (f"dotnet \"{SIDECAR}\" --link-only --transport sim --sim 127.0.0.1:{port} "
             f"--redis-host 127.0.0.1 --redis-port 6379 --registry-url http://127.0.0.1:18080 "
             f">> /tmp/sidecar-{port}.log 2>&1")
    subprocess.Popen(sargs, shell=True, cwd=ROOT, start_new_session=True,
                     stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                     stderr=subprocess.DEVNULL)


def main():
    print("=== 1. 已有设备：挂岛 + 模拟器 ===")
    # 撕膜机 PEEL
    move_to_island(200388, "ID-2002", "PEEL", "peeler", 19506, 388,
                   "EnablePlateCheck,Reset,SetSealThreshold,StartPeel")
    print("  撕膜机 ID-2002 → 19506")
    # 液体工作站 ALIQUOT
    move_to_island(200387, "ID-2011", "ALIQUOT", "liquid_handler", 19507, 387,
                   "LiquidHandlingFinished,LoadFile,LoadFiles,LoadLabware,LoadLabwareEx,"
                   "MoveInLabware,MoveOutLabware,PrepareToLoadLabware,PrepareToUnloadLabware,"
                   "RunMethod,RunSequence,Stop,Transfer1Ex,Transfer2Ex,TrySyncMethodList,"
                   "UnloadLabware,UnloadLabwareEx")
    print("  液体工作站 ID-2011 → 19507")
    # 洗板机 WASH
    move_to_island(200183, "ID-1371", "WASH", "washer", 19508, 183, "Run")
    print("  洗板机 ID-1371 → 19508")
    # 常温堆栈 STORE (plate_hotel)
    move_to_island(504827, "ID-Hotel12H-1", "STORE", "plate_hotel", 19509, 410,
                   "CheckStackState,CheckStackUsed,CheckType,GetStackRecord,GetStackSetting,"
                   "InputByIndex,InputByType,InputByZone,OutputByBarcode,OutputByIndex,"
                   "OutputByType,TrySyncRecords")
    print("  常温堆栈 ID-Hotel12H-1 → 19509")
    # 制冷载架 STORE (freezer)
    move_to_island(504828, "ID-Hotel12H-2", "STORE", "freezer", 19510, 411,
                   "CheckStackState,CheckStackUsed,CheckType,GetStackRecord,GetStackSetting,"
                   "InputByIndex,InputByType,InputByZone,OutputByBarcode,OutputByIndex,"
                   "OutputByType,TrySyncRecords")
    print("  制冷载架 ID-Hotel12H-2 → 19510")

    print("=== 2. 新建设备：PCR仪 + 磁力架 ===")
    # PCR仪 THERMOCYCLE (cap 8)
    create_new_device("ID-PCR-1", "PCR仪", 8, "THERMOCYCLE", "thermocycler", 19511,
                      "Home,OpenDoor,CloseDoor,RunProgram,SetTemp,GetStatus,Stop")
    print("  PCR仪 ID-PCR-1 → 19511")
    # 磁力架 MIX (cap 16, shaker 画像)
    create_new_device("ID-MAG-1", "磁力架", 16, "MIX", "shaker", 19512,
                      "Home,Start,Stop,GetStatus")
    print("  磁力架 ID-MAG-1 → 19512")

    print("\n=== 3. 校验 ===")
    time.sleep(3)
    for port in (19506, 19507, 19508, 19509, 19510, 19511, 19512):
        r = subprocess.run(["nc", "-z", "127.0.0.1", str(port)], capture_output=True)
        print(f"  {port}: {'OPEN' if r.returncode == 0 else 'CLOSED'}")


if __name__ == "__main__":
    main()
