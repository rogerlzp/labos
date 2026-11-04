-- =============================================================================
-- 种子数据（可选）
--
-- **建表（90_all_in_one.sql）不含任何数据。** 本文件是可选的初始数据，
-- 按需执行；不需要的段落可以直接跳过或注释掉。
--
-- 分三类：
--   A. 必需 —— tenant。所有业务表都有 tenant_id 外键，没有租户什么都插不进去。
--   B. 建议 —— capability / error_code / recovery_policy。
--      这些是字典，内核按 code 查；缺了会表现为"节点找不到设备能力"。
--   C. 可选 —— 初始账号。只在首次部署时用，已上线环境不要重复执行。
--
-- 全部语句都是"不存在才插入"（ON CONFLICT DO NOTHING），
-- **不会删除或覆盖已有数据**，可以安全地重复执行。
-- =============================================================================

INSERT INTO tenant (id, code, name) VALUES (1, 'default', '默认租户')
ON CONFLICT (code) DO NOTHING;


INSERT INTO capability (tenant_id, code, name, category, duration_model) VALUES
  (1,'ALIQUOT',   '移液分装',   'liquid',    '{"base":30,"per_well":0.6,"unit":"s"}'),
  (1,'CENTRIFUGE','离心',       'separation','{"base":300,"per_batch":60,"unit":"s"}'),
  (1,'INCUBATE',  '孵育',       'thermal',   '{"base":3600,"unit":"s"}'),
  (1,'READ_PLATE','读板成像',   'optical',   '{"base":20,"unit":"s"}'),
  (1,'SCAN',      '扫码贴标',   'handling',  '{"base":3,"unit":"s"}'),
  (1,'TRANSFER',  '转运',       'transport', '{"base":45,"unit":"s"}'),
  (1,'DECAP',     '开关盖',     'handling',  '{"base":10,"unit":"s"}'),
  (1,'THERMOCYCLE','扩增',      'thermal',   '{"base":5400,"unit":"s"}'),
  (1,'STORE',     '存储',       'storage',   '{"base":10,"unit":"s"}'),
  (1,'WASH',      '分液洗板',   'liquid',    '{"base":120,"unit":"s"}'),
  (1,'SEAL',      '封膜',       'sealing',   '{"base":30,"unit":"s"}'),
  (1,'PEEL',      '撕膜',       'sealing',   '{"base":20,"unit":"s"}'),
  (1,'PICK',      '挑克隆',     'handling',  '{"base":15,"per_colony":2,"unit":"s"}'),
  (1,'SENSE',     '光电检测',   'sensing',   '{"base":5,"unit":"s"}'),
  (1,'ANALYZE',   '流式分析',   'optical',   '{"base":60,"per_sample":5,"unit":"s"}'),
  (1,'MIX',       '混匀/振荡',  'handling',  '{"base":60,"unit":"s"}')
ON CONFLICT (tenant_id, code) DO NOTHING;

INSERT INTO error_code (tenant_id, code, source, severity, title) VALUES
  (1,'EC-05-1006','device','error','设备通讯超时'),
  (1,'EC-08-0001','device','error','设备执行失败'),
  (1,'EC-08-1005','device','warn', '设备状态异常')
ON CONFLICT (tenant_id, code) DO NOTHING;

INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'command', 'retry', '{"backoff":"exponential","base_ms":500}', 3, 10
FROM error_code WHERE tenant_id=1 AND code='EC-05-1006'
ON CONFLICT DO NOTHING;

INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'reassign', '{"reassign_to_capability":true}', 2, 20
FROM error_code WHERE tenant_id=1 AND code='EC-08-0001'
ON CONFLICT DO NOTHING;

-- ============================================================================
-- P0-11 / A1 修复（评审 20261102-04 Trae 指出，核实成立）
--
-- 这里原来灌了 app_user（admin/operator/viewer，bcrypt 口令）、
-- app_role（admin/operator/viewer）、以及 user_role 装配
-- （JOIN `r.code = 'ROLE1'/'ROLE2'/'ROLE4'`）。
--
-- 三处都是错的：
--   ① user_role 的 JOIN 找 ROLE1/2/4，而 app_role 种的是 admin/operator/viewer
--      —— **永不匹配**，一条 user_role 也插不进去（P0-11）。
--   ② 认证链路读的是 `sys_user`（AuthService.java:73,130），
--      app_user 那套**没有任何 Java 代码读**（grep 已确认）。
--      种子灌进去的账号在登录页根本用不了（A1）。
--   ③ sys_user 的口令是 PBKDF2(salt)（AuthService.java:244），
--      **SQL 生成不了**，所以账号只能由 ensureSeedUser() 在应用启动时创建。
--
-- 处置：删掉这三段种子，改为注释说明。
-- 表结构保留（可能有报表/外部系统引用），只是不再灌错数据。
-- ============================================================================

-- 账号由应用启动时的 AuthService.ensureSeedUser() 创建：
--   开发环境默认 admin / admin123（LABOS_ENV!=prod 时）
--   生产环境必须显式提供 LABOS_ADMIN_PASS，否则拒绝启动

-- ============================================================================
-- 设备专属错误码（S100 封膜机 / ASpin 2K 适配层）
-- 编号规则：EC-08-<型号块><手册字节>，块在 DeviceModels 里按型号分配
--   40 = S100        0x8000000C 无纸 → EC-08-400C
--   41 = ASpin 2K    （手册未提供错误码表，块已预留）
-- **不要手改本段**：生成命令见下一行，改型号库后整体替换。
-- 生成：cd labos-kernel && java -cp out com.labos.kernel.device.DeviceErrorSql --emit-sql
-- 自检：DeviceModelSelfTest 会检查本节是否与型号库同步。
-- ============================================================================
-- ─────────────────────────────────────────────────────────────
-- 设备专属错误码 → 中控错误码 → 恢复策略
-- **由 com.labos.kernel.device.DeviceErrorSql --emit-sql 生成，不要手改**
-- 改了型号库（DeviceModels）就重跑一次，整体替换本节。
-- 说明：scope 用 node —— 设备调用失败在 DriverStepExecutor 里
--       一律以 RecoveryScope.NODE 抛出，写成 command 永远匹配不上。
-- ─────────────────────────────────────────────────────────────
INSERT INTO error_code (tenant_id, code, source, severity, title, detail) VALUES
  (1,'EC-05-2001','device','error','SendData 返回 0（设备拒绝或通讯异常）（aspin-2k EC-05-2001）','建议：看 AOutResult 里的 XML 错误；核对 CAN/串口与模块节点配置'),
  (1,'EC-05-2002','device','fatal','未创建模块实例就下发了指令（aspin-2k EC-05-2002）','建议：流程/适配层先发 PcCreateModules，再发其它指令'),
  (1,'EC-05-2003','device','fatal','模块未初始化就下发了动作指令（aspin-2k EC-05-2003）','建议：动作前先发 Init（回原点），否则设备状态未知'),
  (1,'EC-08-4001','device','warn','信号量溢出（s100 0x80000001）','建议：自动重试 3 次，仍失败转人工'),
  (1,'EC-08-4002','device','fatal','错误消息类型（s100 0x80000002）','建议：协议版本不匹配：核对帧格式与固件版本，不要重试'),
  (1,'EC-08-4003','device','error','参数范围有误（s100 0x80000003）','建议：检查板型/参数值是否在 1–8 或手册范围内'),
  (1,'EC-08-4004','device','warn','CAN 通信错误（s100 0x80000004）','建议：重试 2 次，持续出现检查串口线/CAN 卡'),
  (1,'EC-08-4005','device','error','电机堵转（s100 0x80000005）','建议：人工排查堵转原因，排除后关机重启设备'),
  (1,'EC-08-4006','device','error','未检测到孔板（s100 0x80000006）','建议：上板后发 0x06 清除故障，再重跑该节点'),
  (1,'EC-08-4007','device','error','刀片脱落（s100 0x80000007）','建议：故障维护界面进刀换刀片，完成后重启设备'),
  (1,'EC-08-4008','device','fatal','收到错误指令（s100 0x80000008）','建议：指令码/索引不被支持：核对协议版本，不要重试'),
  (1,'EC-08-4009','device','error','数值错误（s100 0x80000009）','建议：检查参数数值（位置/板型）'),
  (1,'EC-08-400A','device','error','存储参数出错（s100 0x8000000A）','建议：人工确认参数存储，必要时复位后重新存参数'),
  (1,'EC-08-400B','device','error','传感器失效（s100 0x8000000B）','建议：人工检查传感器（原点/到位信号）'),
  (1,'EC-08-400C','device','error','无纸（s100 0x8000000C）','建议：更换耗材；新卷装上仍报错时弯折从动轮固定金属片'),
  (1,'EC-08-400E','device','warn','事件等待超时（s100 0x8000000E）','建议：自动重试 3 次（固定间隔 1 秒）'),
  (1,'EC-08-401A','device','error','托盘不在原点，无法封膜（s100 0x8000001A）','建议：先发 0x0C 回原点（流程里封膜前加 MoveTray(0)）')
ON CONFLICT (tenant_id, code) DO NOTHING;

INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-05-2001'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'abort', '{}'::jsonb, 1, 5
FROM error_code WHERE tenant_id=1 AND code='EC-05-2002'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'abort', '{}'::jsonb, 1, 5
FROM error_code WHERE tenant_id=1 AND code='EC-05-2003'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'retry', '{"backoff":"exponential","base_ms":500}'::jsonb, 3, 10
FROM error_code WHERE tenant_id=1 AND code='EC-08-4001'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'abort', '{}'::jsonb, 1, 5
FROM error_code WHERE tenant_id=1 AND code='EC-08-4002'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-08-4003'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'retry', '{"backoff":"exponential","base_ms":500}'::jsonb, 3, 10
FROM error_code WHERE tenant_id=1 AND code='EC-08-4004'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-08-4005'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-08-4006'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-08-4007'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'abort', '{}'::jsonb, 1, 5
FROM error_code WHERE tenant_id=1 AND code='EC-08-4008'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-08-4009'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-08-400A'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-08-400B'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-08-400C'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'retry', '{"backoff":"exponential","base_ms":500}'::jsonb, 3, 10
FROM error_code WHERE tenant_id=1 AND code='EC-08-400E'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-08-401A'
ON CONFLICT DO NOTHING;

-- ============================================================================
-- 错误码的恢复作用域（error_code.scope）
--
-- 为什么需要：作用域原来是**硬编码在抛出点**的，所有设备失败都抛 NODE。
--   结果库里 command 级（EC-05-1006 retry×5）、run 级（EC-06-0001 abort）
--   的策略一条都匹配不上，全部落到通配 node/manual：
--     · 一次网络抖动 → 直接进人工队列（本该重试 5 次）
--     · 驱动没加载 → 一台台转人工（本该整单停下）
--
-- 数据来源：**优先用恢复策略的 scope**（那是当初写策略时的真实意图），
--   没有策略的码按前缀兜底。这样不用维护第二份"意图表"。
-- 幂等：只填空值，不覆盖已有配置。
-- ============================================================================
ALTER TABLE error_code ADD COLUMN IF NOT EXISTS scope varchar(16);

UPDATE error_code e
   SET scope = p.scope
  FROM (SELECT error_code_id, min(scope) AS scope
          FROM recovery_policy GROUP BY error_code_id) p
 WHERE p.error_code_id = e.id AND e.scope IS NULL;

UPDATE error_code SET scope = CASE
    WHEN code LIKE 'EC-05-10%' THEN 'command'   -- 通讯失败/回包超时：单条重发
    WHEN code LIKE 'EC-99-%'   THEN 'command'   -- 发不出去
    WHEN code LIKE 'EC-06-%'   THEN 'device'    -- 驱动/部署问题
    ELSE 'node' END
 WHERE scope IS NULL;
