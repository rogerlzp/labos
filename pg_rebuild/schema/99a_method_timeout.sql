-- ============================================================================
-- 超时按方法取：补齐各能力默认超时（数据回填，幂等）
--
-- 背景（2026-11-06）：
--   `DriverStepExecutor` 改动前用**执行器级固定 30 秒**超时，
--   而 `device_method.timeout_sec` / `workflow_node.timeout_sec`
--   两张表里**存了超时值，驱动层从来不读**
--   （`grep timeout_sec` 在 driver/ 下零命中）——
--   后果是**任何耗时超过 30 秒的真机方法都被中控判超时**：
--   离心 5 分钟、PCR 几十分钟、孵育 30 分钟全部必挂，与"暂停"无关，是纯 bug。
--
--   改动后取值优先级（见 MethodTimeoutPolicy）：
--     设备方法级 → 设备能力级 → 流程节点级 → **能力默认** → 兜底 30 秒
--   本分片负责补齐第三层缺失的一环：**能力默认**。
--
-- 为什么只填 `timeout_sec = 0` 的行：
--   设备库里那批 3s / 5s / 20s / 302s 是**真机契约**（按「预计耗时 × 2」算的），
--   抬高等于把"扫码机死了"从 3 秒才发现变成 10 分钟才发现 —— 必须原样保留。
--
-- 为什么保底 600 秒：
--   超时是「设备不回话」的兜底，不是正常耗时。
--   10 分钟够覆盖真机长工艺，又不会把整单无限期挂死；
--   同时兜住演示岛仿真机「方法耗时随机 30–300 秒」的量级（留足余量不撞线）。
--   长工艺另给更长：孵育 30 分钟、扩增/PCR 60 分钟、离心/洗板 15 分钟。
--
-- 全部幂等：可重复执行。
-- ============================================================================

-- ① 按能力补齐空缺（只动 timeout_sec = 0 的行，显式值不动）
--
-- ⚠ 为什么用 CTE 而不是 UPDATE ... FROM (VALUES) AS d JOIN capability c：
--   Postgres **不允许在 FROM 子句的 JOIN 条件里引用目标表**（dm），
--   那样写会报 "invalid reference to FROM-clause entry for table dm"。
--   所以先在 CTE 里把「能力编码 → 超时」和「能力编码 → capability.id」接好，
--   再拿它去 UPDATE。
WITH defs(cap_code, timeout_sec) AS (VALUES
        ('INCUBATE', 1800),      -- 孵育：30 分钟
        ('THERMOCYCLE', 3600),  -- 扩增/PCR：60 分钟
        ('CENTRIFUGE', 900),    -- 离心：15 分钟
        ('WASH', 900),          -- 分液洗板：15 分钟
        ('DECAP', 600),         -- 开盖
        ('PEEL', 600),          -- 撕膜
        ('SEAL', 600),          -- 封膜
        ('ALIQUOT', 600),       -- 移液分装
        ('ANALYZE', 600),       -- 流式分析
        ('TRANSFER', 600),      -- 转运
        ('READ_PLATE', 600),    -- 读板
        ('STORE', 600),         -- 存储
        ('PICK', 600),          -- 挑克隆
        ('SCAN', 600),          -- 扫码
        ('SENSE', 600),         -- 光电检测
        ('MIX', 600)            -- 混匀/振荡
     ),
     cap AS (
         SELECT c.tenant_id, c.id AS capability_id, d.timeout_sec
           FROM capability c
           JOIN defs d ON d.cap_code = c.code
     )
UPDATE device_method dm
   SET timeout_sec = cap.timeout_sec,
       updated_at  = now()
  FROM cap
 WHERE dm.tenant_id = cap.tenant_id
   AND dm.capability_id = cap.capability_id
   AND dm.timeout_sec = 0;

-- ② 没有能力登记的行（capability_id 为空）给通用保底 600 秒
UPDATE device_method dm
   SET timeout_sec = 600,
       updated_at  = now()
 WHERE dm.timeout_sec = 0
   AND dm.capability_id IS NULL;
