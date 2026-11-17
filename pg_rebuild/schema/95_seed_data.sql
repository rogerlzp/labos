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
  (1,'MIX',       '混匀/振荡',  'handling',  '{"base":60,"unit":"s"}'),
  -- EXTRACT（核酸提取）：2026-11-15 由 P1 补进 capability 表（338e529），
  -- 但**没同步进这个 seed 分片** ⇒ 重建库时会整条丢失 ⇒ 新环境又变回
  -- "提取仪只能硬套 MIX/ANALYZE"。这里补齐。
  -- duration_model 留空 {}：真实时长取决于洗涤液配方与设备规格，
  -- **没有实测数据就不编数字**（与 338e529 落库时的值保持一致）。
  (1,'EXTRACT',   '核酸提取',   'extraction','{}')
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
-- 设备专属错误码（S100 封膜机 / ASpin 2K / BioTek LHC / BioTek Gen5 + 适配层）
-- 编号规则：
--   EC-08-<型号块><手册字节>  —— 厂商码是 **十六进制且带结构** 的（S100 0x8000000C）
--   EC-08-<型号块>-<显式码>   —— 厂商码是 **十进制/负数** 的（BioTek LHC 0..6、
--                                Gen5 -1..-700），取低字节会撞码，故显式编号
-- 块在 DeviceModels 里按型号分配：
--   40 = S100        0x8000000C 无纸      → EC-08-400C
--   41 = ASpin 2K    （手册未提供错误码表，块已预留）
--   42 = BioTek LHC  Runner_ReturnCode 4  → EC-08-42-04
--   43 = BioTek Gen5 BTI 码 -526          → EC-08-43-526
-- **不要手改本段**：改型号库（DeviceModels）后整体替换。
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
  (1,'EC-08-401A','device','error','托盘不在原点，无法封膜（s100 0x8000001A）','建议：先发 0x0C 回原点（流程里封膜前加 MoveTray(0)）'),
  (1,'EC-08-42-00','device','error','eError：LHC Runner 通用失败（biotek-lhc 0）','建议：看 GetErrorString 的原文；多为前置条件没满足'),
  (1,'EC-08-42-02','device','error','eRegistration_Failure：LHC 软件未注册/试用期已过（biotek-lhc 2）','建议：现场注册 LHC（试用期过后功能受限，不是通讯问题）'),
  (1,'EC-08-42-03','device','error','eInterface_Failure：仪器接口软件失败（biotek-lhc 3）','建议：查 LHCInterface_*.INF 是否存在、对应型号驱动是否安装'),
  (1,'EC-08-42-04','device','error','eInvalid_Product_Type：产品名不被识别（biotek-lhc 4）','建议：核对 SetProductName 的字符串（50 TS 要带空格）'),
  (1,'EC-08-42-05','device','error','eOpen_File_Error：协议文件打不开（biotek-lhc 5）','建议：核对 .LHC 路径与文件名（SiLA 模式下还要看 SpConfig 的 ProtocolFilePath）'),
  (1,'EC-08-42-06','device','error','ePre_Run_Error：运行前校验失败（biotek-lhc 6）','建议：协议与本机不匹配：重新 ValidateProtocol(true) 看具体原因'),
  (1,'EC-05-2201','device','fatal','RunStatus=eNotReady(2)：新步骤没能启动（biotek-lhc EC-05-2201）','建议：Run 已判定失败，不要再轮询；查 GetLastErrorCode'),
  (1,'EC-05-2202','device','error','RunStatus=eError(4)：协议运行中报错（biotek-lhc EC-05-2202）','建议：调 GetLastErrorCode → GetErrorString 取原文，按码处置'),
  (1,'EC-05-2203','device','fatal','RunStatus 是 eUninitialized(0)/eIncomplete(6)：协议未正常结束（biotek-lhc EC-05-2203）','建议：手册标注这两个值「不应出现」；先 Abort 再 Reset 通道，不要重试'),
  (1,'EC-05-2204','device','fatal','没做初始化就下了命令（缺 SetProductName/SetCommunications/TestCommunications）（biotek-lhc EC-05-2204）','建议：按运行场景先走完初始化序列'),
  (1,'EC-05-2205','device','fatal','没有装载协议就 Run/Wash/Dispense（biotek-lhc EC-05-2205）','建议：先 LoadProtocol（或 LoadProtocolFromFlash）'),
  (1,'EC-05-2206','device','error','装载后未验证就 Run，且未设 OverrideValidation(1)（biotek-lhc EC-05-2206）','建议：先 ValidateProtocol(true)，或显式 OverrideValidation(1)'),
  (1,'EC-05-2207','device','error','50 TS 的条（strip）范围越界（biotek-lhc EC-05-2207）','建议：firstStrip + numberOfStrips - 1 不得超过板宽（96 孔=12 列）'),
  (1,'EC-05-2208','device','error','找不到 LHC Runner COM 组件（BTILHCRunner.dll 未注册）（biotek-lhc EC-05-2208）','建议：在装 LHC 的机器上跑 sidecar，或用 regsvr32 注册；核对 LHC 版本 ≥ 2.12'),
  (1,'EC-05-2209','device','error','LHC_Installation_Folder.inf 缺失或指向错误目录（biotek-lhc EC-05-2209）','建议：该文件必须与 BTILHCRunner.dll 同目录，且内容是 LHC 的安装路径'),
  (1,'EC-05-2210','device','warn','上一条协议还在运行，拒绝了新命令（biotek-lhc EC-05-2210）','建议：等 GetProtocolStatus 变成 eReady 后重试；或用 Abort 中止'),
  (1,'EC-08-43-001','device','error','BTI_FAIL：通用失败（biotek-gen5 -1）','建议：看异常文本；多为前置条件没满足'),
  (1,'EC-08-43-003','device','error','BTI_USER_CANCELLED_OPERATION：用户取消（biotek-gen5 -3）','建议：有人在 Gen5 界面上点了取消；确认后重跑'),
  (1,'EC-08-43-005','device','fatal','BTI_INVALID_REQUEST：请求不合法（biotek-gen5 -5）','建议：调用序列/参数类型不对：核对文档，不要重试'),
  (1,'EC-08-43-013','device','error','BTI_INSUFFICIENT_PERMISSIONS：权限不足（biotek-gen5 -13）','建议：Gen5 Secure/Clinical 的账号权限或未登录'),
  (1,'EC-08-43-014','device','error','BTI_DATABASE_ERROR：Gen5 数据库错误（biotek-gen5 -14）','建议：检查 Gen5 的 SharedDB 连通性；重启 Gen5 后重试'),
  (1,'EC-08-43-100','device','error','BTI_FILE_DOES_NOT_EXIST：文件不存在（biotek-gen5 -100）','建议：核对协议/实验路径（注意 DB:\ 与 C:\ 两种模式）'),
  (1,'EC-08-43-101','device','error','BTI_UNABLE_TO_OPEN_FILE：文件打不开（biotek-gen5 -101）','建议：文件被占用或权限不足'),
  (1,'EC-08-43-103','device','error','BTI_FILE_READ_ONLY：文件只读（biotek-gen5 -103）','建议：换路径 SaveAs，或由人解除只读'),
  (1,'EC-08-43-105','device','warn','BTI_FILE_LOCKED：文件被锁（biotek-gen5 -105）','建议：另一个 Gen5 实例持有；等几秒重试'),
  (1,'EC-08-43-106','device','warn','BTI_FILE_SHARING_VIOLOATION：共享冲突（biotek-gen5 -106）','建议：同上；持续出现则关闭多余 Gen5 实例'),
  (1,'EC-08-43-200','device','error','BTI_INVALID_RECORD_FORMAT：记录格式不合法（biotek-gen5 -200）','建议：传进去的 XML 与 schema 不符'),
  (1,'EC-08-43-301','device','error','BTI_FIELD_BELOW_MIN_RANGE：字段低于下限（biotek-gen5 -301）','建议：参数越界（波长/体积/温度）'),
  (1,'EC-08-43-302','device','error','BTI_FIELD_ABOVE_MAX_RANGE：字段超上限（biotek-gen5 -302）','建议：参数越界'),
  (1,'EC-08-43-505','device','warn','BTI_SERIAL_WRITE_ERROR：串口写失败（biotek-gen5 -505）','建议：重试 2 次；持续出现查线缆/USB 转换器'),
  (1,'EC-08-43-506','device','warn','BTI_SERIAL_READ_ERROR：串口读超时（biotek-gen5 -506）','建议：重试 2 次；确认仪器没被别的软件占用'),
  (1,'EC-08-43-507','device','warn','BTI_CHECKSUM_ERROR：校验和错（biotek-gen5 -507）','建议：线路干扰；重试后仍错查屏蔽/接地'),
  (1,'EC-08-43-513','device','error','BTI_PORT_NOT_OPEN：端口未打开（biotek-gen5 -513）','建议：核对串口号与波特率'),
  (1,'EC-08-43-522','device','error','BTI_COM_PORT_ALREADY_IN_USE：串口被占用（biotek-gen5 -522）','建议：先关掉 Gen5 之外的串口占用程序（含上次残留的 Gen5）'),
  (1,'EC-08-43-526','device','error','BTI_CREATE_FILE_NO_PORT_ERROR：端口不存在（biotek-gen5 -526）','建议：核对通讯设置里的串口号（USB 机型改用 ConfigureUSBReader）'),
  (1,'EC-08-43-527','device','error','BTI_CREATE_FILE_PORT_IN_USE：端口被别的用户锁住（biotek-gen5 -527）','建议：另一实例已占用该端口；同一端口只能有一个 Application'),
  (1,'EC-08-43-529','device','warn','BTI_READER_NOT_CONNECTED：仪器未连接（biotek-gen5 -529）','建议：查电源与线缆；恢复后 TestReaderCommunication 再继续'),
  (1,'EC-08-43-531','device','error','BTI_USB_DEVICE_ALREADY_IN_USE：USB 设备被占用（biotek-gen5 -531）','建议：关掉其它 Gen5/读板软件'),
  (1,'EC-08-43-600','device','error','BTI_UNKNOWN_READER：未知机型（biotek-gen5 -600）','建议：ReaderType 不在支持列表里'),
  (1,'EC-08-43-601','device','error','BTI_READER_STATUS_ERROR：仪器报错（biotek-gen5 -601）','建议：必须再取 PlateReadMonitor.GetReaderError 拿仪器原始码，按那个码处置'),
  (1,'EC-08-43-602','device','warn','BTI_READER_NO_ACKNOWLEDGE：仪器不应答（biotek-gen5 -602）','建议：重试 2 次；持续出现查线缆/固件'),
  (1,'EC-08-43-606','device','warn','BTI_READ_TIMEOUT_ERROR：读板超时（biotek-gen5 -606）','建议：先 AbortRead；确认不是动力学读板（长读板要放大 sidecar 超时）'),
  (1,'EC-08-43-608','device','error','BTI_READER_INV_WAVELENGTH：波长不被支持（biotek-gen5 -608）','建议：用 GetReaderCharacteristics(1/2) 取本机实际波长范围'),
  (1,'EC-05-2301','device','fatal','没配置读板机就调了读板/进出板（biotek-gen5 EC-05-2301）','建议：先 ConfigureSerialReader/ConfigureUSBReader + TestReaderCommunication'),
  (1,'EC-05-2302','device','fatal','没有打开的 Experiment 就操作 Plate（biotek-gen5 EC-05-2302）','建议：先 NewExperiment/OpenExperiment'),
  (1,'EC-05-2303','device','error','读板进行中还调了会冲突的方法（删板/导出/CarrierIn/配置）（biotek-gen5 EC-05-2303）','建议：等 ReadInProgress=false 再调'),
  (1,'EC-05-2304','device','error','找不到 Gen5 的 COM 组件（Gen5 未安装或未启动过一次）（biotek-gen5 EC-05-2304）','建议：装 Gen5 的机器上跑 sidecar；先手工启动一次 Gen5 完成初始化与注册'),
  (1,'EC-05-2305','device','error','Gen5 弹出了交互对话框（登录/选板型/文件选择），无人应答（biotek-gen5 EC-05-2305）','建议：Gen5 Secure/Clinical 请关闭 OLE 登录要求；或用 SetClientWindow 指定父窗口'),
  (1,'EC-05-2306','device','error','DataExportEnabled 未开启，取不到原始数据（biotek-gen5 EC-05-2306）','建议：读板前置 Application.DataExportEnabled = true'),
  (1,'EC-05-2307','device','error','承载器上没有板就启动读板（biotek-gen5 EC-05-2307）','建议：读板前先 CarrierIn（或 CarrierInEx 带板型）')
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
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-08-42-00'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-08-42-02'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-08-42-03'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-08-42-04'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-08-42-05'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-08-42-06'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'abort', '{}'::jsonb, 1, 5
FROM error_code WHERE tenant_id=1 AND code='EC-05-2201'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-05-2202'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'abort', '{}'::jsonb, 1, 5
FROM error_code WHERE tenant_id=1 AND code='EC-05-2203'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'abort', '{}'::jsonb, 1, 5
FROM error_code WHERE tenant_id=1 AND code='EC-05-2204'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'abort', '{}'::jsonb, 1, 5
FROM error_code WHERE tenant_id=1 AND code='EC-05-2205'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-05-2206'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-05-2207'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-05-2208'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-05-2209'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'retry', '{"backoff":"exponential","base_ms":500}'::jsonb, 3, 10
FROM error_code WHERE tenant_id=1 AND code='EC-05-2210'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-08-43-001'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-08-43-003'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'abort', '{}'::jsonb, 1, 5
FROM error_code WHERE tenant_id=1 AND code='EC-08-43-005'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-08-43-013'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-08-43-014'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-08-43-100'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-08-43-101'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-08-43-103'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'retry', '{"backoff":"exponential","base_ms":500}'::jsonb, 3, 10
FROM error_code WHERE tenant_id=1 AND code='EC-08-43-105'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'retry', '{"backoff":"exponential","base_ms":500}'::jsonb, 3, 10
FROM error_code WHERE tenant_id=1 AND code='EC-08-43-106'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-08-43-200'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-08-43-301'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-08-43-302'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'retry', '{"backoff":"exponential","base_ms":500}'::jsonb, 3, 10
FROM error_code WHERE tenant_id=1 AND code='EC-08-43-505'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'retry', '{"backoff":"exponential","base_ms":500}'::jsonb, 3, 10
FROM error_code WHERE tenant_id=1 AND code='EC-08-43-506'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'retry', '{"backoff":"exponential","base_ms":500}'::jsonb, 3, 10
FROM error_code WHERE tenant_id=1 AND code='EC-08-43-507'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-08-43-513'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-08-43-522'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-08-43-526'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-08-43-527'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'retry', '{"backoff":"exponential","base_ms":500}'::jsonb, 3, 10
FROM error_code WHERE tenant_id=1 AND code='EC-08-43-529'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-08-43-531'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-08-43-600'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-08-43-601'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'retry', '{"backoff":"exponential","base_ms":500}'::jsonb, 3, 10
FROM error_code WHERE tenant_id=1 AND code='EC-08-43-602'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'retry', '{"backoff":"exponential","base_ms":500}'::jsonb, 3, 10
FROM error_code WHERE tenant_id=1 AND code='EC-08-43-606'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-08-43-608'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'abort', '{}'::jsonb, 1, 5
FROM error_code WHERE tenant_id=1 AND code='EC-05-2301'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'abort', '{}'::jsonb, 1, 5
FROM error_code WHERE tenant_id=1 AND code='EC-05-2302'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-05-2303'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-05-2304'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-05-2305'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-05-2306'
ON CONFLICT DO NOTHING;
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params, max_attempts, priority)
SELECT 1, id, 'node', 'manual', '{}'::jsonb, 1, 20
FROM error_code WHERE tenant_id=1 AND code='EC-05-2307'
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
