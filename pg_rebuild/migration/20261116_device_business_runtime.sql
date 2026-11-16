-- 设备业务状态字典 · 运行时口径修正 + 触发方式（2026-11-16）
--
-- **背景**：上一版（9b39fae）把字典的 `device_kind` 取成了 `extractor/dispenser/
-- stirrer/robot/plate_hotel`（来自 DeviceModels.PLANNED 的 type 字符串）。
-- ⚠ **但库里 `device.device_type` 的实际值是厂商登记的原始分类**
-- （"Extractors" / "Robots" / "Storage" / "Dispensers & Washers" …）——
-- 复数、含空格、还有 29 个 MISC 与 4 个空值 ⇒ **按 device_type 查字典会一条都查不到**。
-- ⇒ **口径改正**：改用**能力**（capability）。
--
-- **为什么能力是对的**（三个理由）：
-- ① 能力是平台**自己**的分类（EXTRACT/WASH/MIX/TRANSFER/STORE），
--    正好与客户文档 §1.5 的 **5 类设备**一一对应：
--      提取仪 EXTRACT · 分液器 WASH · 搅拌单元 MIX · 机械臂 TRANSFER · 耗材栈 STORE；
-- ② 能力在**每个设备上都有**（resource_capability），
--    而 device_type 有空值、有 MISC、写法随登记人变；
-- ③ 写站脚本时本来就是按**能力**找型号的
--    （setup-extraction-station.py：「用 capability 匹配更稳：deviceType 的写法随登记人变化」）
--    ⇒ 这次是把**字典**口径对齐到**既有**口径，不是新造一套。
--
-- ⚠ **已知粗糙**：洗板机与分液器都落在 WASH 组（能力相同）。
--   客户 §1.5 只要求这 5 类设备有业务状态 ⇒ 可接受；
--   真要区分，现场改配置即可（字典是配置，不是写死的）。
--
-- **同时补三列：业务状态怎么被触发**（运行时派生用）：
--   trigger     = command（命令驱动）/ event（事件驱动）/ fault（故障）/ idle（静止常态）
--   source_key  = command ⇒ 方法名（逗号分隔）；event ⇒ 事件码（逗号分隔）；其余为空
--   is_idle     = 该设备类别的**静止常态**（命令结束后回落到的那个态）
--
-- 可重复执行。

-- ---------------------------------------------------------------- 口径改正：device_type → capability

UPDATE device_business_state SET device_kind = 'EXTRACT'   WHERE device_kind = 'extractor';
UPDATE device_business_state SET device_kind = 'WASH'     WHERE device_kind = 'dispenser';
UPDATE device_business_state SET device_kind = 'MIX'      WHERE device_kind = 'stirrer';
UPDATE device_business_state SET device_kind = 'TRANSFER' WHERE device_kind = 'robot';
UPDATE device_business_state SET device_kind = 'STORE'    WHERE device_kind = 'plate_hotel';

-- ---------------------------------------------------------------- 触发方式三列

ALTER TABLE device_business_state
    ADD COLUMN IF NOT EXISTS trigger    varchar(16);
ALTER TABLE device_business_state
    ADD COLUMN IF NOT EXISTS source_key varchar(256);
ALTER TABLE device_business_state
    ADD COLUMN IF NOT EXISTS is_idle    boolean;

COMMENT ON COLUMN device_business_state.device_kind IS
  '设备类别：**能力口径**（EXTRACT/WASH/MIX/TRANSFER/STORE）—— 与客户文档 §1.5 的 5 类设备一一对应。'
  '⚠ 不是 device.device_type：那个是厂商登记的原始分类（"Extractors"/"Robots"/…，复数、含空格、有空值），'
  '按它查字典会一条都查不到。';
COMMENT ON COLUMN device_business_state.trigger IS
  '该状态怎么被触发：command=命令驱动（跑某方法时）· event=事件驱动（设备主动上报）· '
  'fault=故障 · idle=静止常态（兜底）。';
COMMENT ON COLUMN device_business_state.source_key IS
  '触发源：command ⇒ 方法名（逗号分隔）；event ⇒ 事件码（逗号分隔）；其余为空。';
COMMENT ON COLUMN device_business_state.is_idle IS
  'true = 该设备类别的静止常态（没有命令在跑、也没有待处理事件时回落到的状态）。每类设备有且仅有一个。';
