-- ============================================================================
-- 测试基线重置 · 第三步：建新基线（岛 + 5 台设备）
--
-- 基线岛 LAB-BASE-01，刻意只放 5 台设备，覆盖 5 类能力 ——
-- 目的是让门禁"每条用例只测一件事"，失败时立刻知道是哪一类能力坏了：
--
--   X231        机器人   TRANSFER   机器人画像  可用方法：Home / Transfer
--   X229        孵育箱   INCUBATE   孵育箱画像  可用方法：Deposit1Ex
--   ASPIN_2K-1 离心机   CENTRIFUGE 离心机画像  可用方法：Home/OpenDoor/CloseDoor/RunProgram/GetStatus
--   ID1021      读板机   READ_PLATE 读板机画像  可用方法：OpenDoor / CloseDoor
--   S100-1      封膜机   SEAL       封膜机画像  可用方法：Seal / GetStatus
--
-- 选设备的硬标准：**设备的方法集必须落在模拟器画像的方法集里**。
-- 模拟器按画像校验方法名，对不上直接回"不支持该方法"⇒ 节点必失败。
-- （库里 ID1183 / ID0000 两台的方法集与画像**完全无交集**，已排除。）
--
-- 端口由 labos-kernel/scripts/sim-up.sh 按岛内 code 排序统一分配（19500 起），
-- 本脚本不写端口 —— 端口是模拟器的事，写两遍就会不一致。
--
-- 用法：psql -f scripts/baseline-build.sql
-- ============================================================================
\set ON_ERROR_STOP on

BEGIN;

-- ---------------------------------------------------------------------------
-- ① 基线岛
-- ---------------------------------------------------------------------------
INSERT INTO resource_node
    (tenant_id, parent_id, code, name, kind, status, capacity, path, location, attr)
VALUES
    (1, NULL, 'LAB-BASE-01', '基线岛', 'island', 'available', 1,
     '/LAB-BASE-01', '{}'::jsonb,
     jsonb_build_object('island_desc',
         '测试基线：机器人 / 孵育箱 / 离心机 / 读板机 / 封膜机，各一台'));

-- ---------------------------------------------------------------------------
-- ② 5 台设备拖进岛
--     设备节点之前随旧岛删除被 SET NULL 成"设备池"，这里重新挂到新岛下。
--     path 同步成 /<岛code>/<设备code>（前端按 path 画树）。
-- ---------------------------------------------------------------------------
UPDATE resource_node r
   SET parent_id = (SELECT id FROM resource_node WHERE code='LAB-BASE-01' AND kind='island'),
       path      = '/LAB-BASE-01/' || r.code,
       updated_at = now()
 WHERE r.id IN (200231,   -- X231        机器人
                200229,   -- X229        孵育箱
                504831,   -- ASPIN_2K-1  离心机
                200200,   -- ID1021      读板机
                504830);  -- S100-1      封膜机

-- ---------------------------------------------------------------------------
-- ③ 读板机启用
--     ID1021 原先是停用状态（历史设备）。读板机能力要覆盖 READ_PLATE，
--     库里有三台读板类设备都是停用的 —— 启用这台最省（方法集与读板机画像
--     有交集：OpenDoor / CloseDoor）。
--     driver_id 先摘掉：旧驱动指向 192.168.10.196:8501 的老网关，
--     端点由 sim-up.sh 按新端口重建。
-- ---------------------------------------------------------------------------
UPDATE device_instance
   SET status = 'available', driver_id = NULL, updated_at = now()
 WHERE id = 200;

-- ---------------------------------------------------------------------------
-- ④ 非基线的在役设备一律停用
--     为什么：设备清单要能一眼看出"基线在哪"。留着 7 台不在岛里的在役设备，
--     门禁选设备时可能挑到它们（挑不到可用方法的那些只会制造噪音）。
--     停用 = status='disabled' + driver_id=NULL（数据保留，可随时启用）。
-- ---------------------------------------------------------------------------
UPDATE device_instance
   SET status = 'disabled', driver_id = NULL, updated_at = now()
 WHERE tenant_id = 1 AND deleted_at IS NULL
   AND id NOT IN (229, 231, 413, 414, 200)
   AND status <> 'disabled';

-- ---------------------------------------------------------------------------
-- ⑤ 顺带清干净这些设备的在线状态（心跳已停用它们，别留"在线"的假象）
-- ---------------------------------------------------------------------------
UPDATE device_state ds
   SET online = false, updated_at = now()
  FROM device_instance di
 WHERE di.resource_id = ds.resource_id
   AND di.tenant_id = 1 AND di.deleted_at IS NULL
   AND di.status = 'disabled';

COMMIT;

-- ---------------------------------------------------------------------------
-- 自检：基线岛 + 5 台设备
-- ---------------------------------------------------------------------------
SELECT r.code AS island, d.code AS device, c.code AS capability,
       di.status, di.id AS device_instance_id, r.id AS resource_id
  FROM resource_node r
  JOIN resource_node d ON d.parent_id = r.id AND d.kind = 'device'
  JOIN device_instance di ON di.resource_id = d.id AND di.deleted_at IS NULL
  JOIN device dev ON dev.id = di.device_id
  JOIN capability c ON c.id = dev.capability_id
 WHERE r.code = 'LAB-BASE-01'
 ORDER BY d.code;
