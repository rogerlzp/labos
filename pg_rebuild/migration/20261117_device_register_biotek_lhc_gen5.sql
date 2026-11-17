-- ============================================================================
-- 登记两个有手册的 BioTek 型号：LHC 洗板/分液机、Gen5 酶标仪
-- 2026-11-17
--
-- 协议依据（原件在 private/协议文档/，不入库；我方解读在 docs/设备/）：
--   · 洗板机：LHC Runner User Application Interface Specification 5336208-SP Rev G
--            LHC SiLA Service Provider 5330207-SP Rev A（另一条 SOAP 通道，尚未实现）
--   · 酶标仪：Gen5 OLE Automation Programmer's Guide + BTIStatusCodes.h
-- 解读文档：docs/设备/洗板机-BioTek-LHC.md、docs/设备/酶标仪-BioTek-Gen5.md
--
-- **零 DDL**：全部落在既有表上，不新建、不改结构。
--   · 型号主数据   → public.device（attr.device_model 是型号库 id）
--   · 型号方法     → public.device_method（含 param_schema / timeout_sec / interruptible）
--                    ⚠ 列名是 **device_model_id**（20261116_device_model_id_rename 把
--                      device_id 改过名），唯一键是 scope='model' 的**部分索引**，
--                      所以 ON CONFLICT 必须带 WHERE scope='model'。
--   · 错误码/策略  → **不在这里写**：由 DeviceErrorSql 从型号库生成到
--                    pg_rebuild/schema/95_seed_data.sql（单一真源，避免两份漂移）
--   · 点位         → resource_node + device_point + position_state（`{设备}-BOTH`）
--   · 点位属性模板 → device_point_template（按型号带出，供后续同步点位时自动填）
--
-- 幂等：可反复执行；已存在的行不覆盖（现场改过的值不会被冲掉）。
-- ============================================================================

-- ---------------------------------------------------------------- 型号主数据
-- EL406：洗板/分液一体机（走 LHC 软件；一台机器可能被当作 washer 或 dispenser 用，
--        这里按 WASH 能力登记 —— 需要分液能力时再挂 ALIQUOT，不重复建型号）
INSERT INTO device (tenant_id, code, name, model, vendor, device_type, capability_id, icon, attr)
SELECT 1, 'EL406', 'BioTek EL406 洗板/分液一体机', 'EL406', 'BioTek（Agilent）',
       'Dispensers & Washers',
       (SELECT id FROM capability WHERE code = 'WASH'),
       '/devices/EL406.svg',
       jsonb_build_object(
         'device_model', 'biotek-lhc',
         'protocol', jsonb_build_object(
           'kind', 'com-lhc-runner',
           'progId', 'BTILHCRunner.ClassLHCRunner',
           'transport', 'COM（随 LHC 安装）；仪器走 USB 或串口，由 LHC 独占',
           'productName', 'EL406',
           'sampleCommunications', 'USB EL406 sn:220684',
           'needsInstallationInf', 'LHC_Installation_Folder.inf 必须与 BTILHCRunner.dll 同目录',
           'sila', jsonb_build_object(
             'available', true,
             'version', 'SiLA 1.1（Infoteam SP）',
             'uri', 'http://<SP主机>:8080/BioTekLHCDevice/wsdl',
             'note', 'SP 在同一台机器上跑；未实现的原因见 docs/设备/洗板机-BioTek-LHC.md §7'),
           'sequence', jsonb_build_array(
             'SetProductName', 'SetCommunications', 'TestCommunications',
             'LoadProtocol', 'ValidateProtocol', 'RunProtocol', 'GetProtocolStatus'),
           'runStatus', '0 eUninitialized / 1 eReady / 2 eNotReady / 3 eBusy / 4 eError / '
                        '5 eDone / 6 eIncomplete / 7 ePaused / 8 eStopRequested / '
                        '9 eStopping / 10 eNotRequired',
           'pollIntervalMs', 500,
           'bits', '96/384 孔；承载器单口进出；每次 LoadProtocol 会清空验证信息'),
         'manual', 'docs/设备/洗板机-BioTek-LHC.md',
         'evidence', 'MANUAL',
         'gaps', jsonb_build_array(
           '洗完一块板要多久手册没给（模拟器按 60 s）',
           'IS_ReturnCode（仪器接口软件码）手册未提供码表，只能原样透传',
           'ELx405 不支持 OverrideValidation/SelfCheck/GetProductSerialNumber/GetWellCount',
           '50 TS 的条范围参数只有该机型的固件支持，其他机型会返回错误',
           '协议里若含 Prompt 指令，无人值守时会停下来等人按键'))
WHERE NOT EXISTS (SELECT 1 FROM device WHERE tenant_id = 1 AND code = 'EL406');

-- GEN5：酶标仪（Gen5 是"软件宿主"，它独占仪器；中控只与 Gen5 对话）
INSERT INTO device (tenant_id, code, name, model, vendor, device_type, capability_id, icon, attr)
SELECT 1, 'GEN5', 'BioTek Gen5 酶标仪（OLE Automation）', 'Gen5', 'BioTek（Agilent）',
       'Readers & Imagers',
       (SELECT id FROM capability WHERE code = 'READ_PLATE'),
       '/devices/GEN5.svg',
       jsonb_build_object(
         'device_model', 'biotek-gen5',
         'protocol', jsonb_build_object(
           'kind', 'com-ole',
           'progId', 'Gen5.Application',
           'transport', 'OLE Automation（本机进程内 COM）；仪器由 Gen5 独占',
           'storageModes', jsonb_build_array(
             'DB:\\Protocols\\example1.prt（数据库模式）',
             'C:\\Protocols\\example1.prt（文件系统模式）'),
           'readerTypes', '2 ELx800 / 3 ELx808 / 6 Synergy HT / 7 FLx800 / 8 Powerwave / '
                          '10 Synergy2 / 11 PowerwaveXS 2 / 13 Synergy Mx / 14 Epoch / '
                          '15 Synergy H4 / 16 Synergy H1 / 17 Eon / 18 Synergy Neo / '
                          '19 Cytation3 / 20 Synergy HTX / 21 Cytation5 / 22 Epoch 2 / '
                          '23 Synergy Neo2 / 24 Lionheart FX / 25 800TS / 26 Cytation1；'
                          '-1 = 只接一台时自动选',
           'sequence', jsonb_build_array(
             'ConfigureUSBReader|ConfigureSerialReader', 'TestReaderCommunication',
             'NewExperiment', 'CarrierIn', 'Read', 'GetReadProgress', 'GetResult',
             'CarrierOut', 'CloseExperiment'),
           'releaseOrder', 'Plate → Plates → Experiment（手册硬约束）',
           'readStatus', '读板成功与否看 Plate.ReadStatus，不是看有没有抛异常',
           'bits', '96/384 孔；承载器单口进出；有前门（doorCount=1）'),
         'manual', 'docs/设备/酶标仪-BioTek-Gen5.md',
         'evidence', 'MANUAL',
         'gaps', jsonb_build_array(
           'Gen5ReadStatus 的 6 个数值手册正文未给全，sidecar 只原样回传整数',
           '-601 是"仪器报错"，必须再取 PlateReadMonitor.GetReaderError 才拿到仪器码',
           'Gen5 Secure/Clinical 若开启 OLE 登录，无人值守会卡在登录框',
           '动力学读板时长取决于协议，sidecar 超时按型号 actionTimeoutMs=600 s'))
WHERE NOT EXISTS (SELECT 1 FROM device WHERE tenant_id = 1 AND code = 'GEN5');

-- ---------------------------------------------------------------- 方法（EL406 / LHC）
-- 为什么 capability 用**标量子查询**而不是 join：
--   join 时"库里没有 WASH 这一行"会让整条 INSERT 静默插入 0 行（方法一条都没登记，
--   但脚本 exit=0，看不出来）。标量子查询只会让 capability_id 为 NULL，
--   方法照常登记，然后由下面的自检 SQL 把 NULL 暴露出来。
WITH d AS (SELECT id FROM device WHERE tenant_id = 1 AND code = 'EL406'),
     c AS (SELECT (SELECT id FROM capability WHERE code = 'WASH') AS cap)
INSERT INTO device_method (tenant_id, device_model_id, method, name, capability_id,
                           param_schema, estimate_sec, timeout_sec, scope, interruptible)
SELECT 1, d.id, v.method, v.cname, c.cap, v.schema::jsonb, v.est, v.tmo, 'model', v.intr
  FROM d, c, (VALUES
    ('SetProductName', '设置产品名（必须第一步）', 1, 10, false,
     '{"params":[{"seq":1,"name":"productName","type":"string","required":true,"enum":["EL406","405 TS/LS","ELx405","MultiFlo","MultiFloFX","MicroFlo Select","50 TS"],"desc":"手册 §6.5；50 与 TS 之间必须有空格"}]}'),
    ('SetCommunications', '设置通讯口', 1, 10, false,
     '{"params":[{"seq":1,"name":"port","type":"string","required":true,"desc":"COMn 或 \"USB <产品> sn:<序列号>\""}]}'),
    ('TestCommunications', '通讯测试（不通过就不能跑协议）', 3, 15, false,
     '{"params":[]}'),
    ('SelfCheck', '仪器自检', 30, 120, false, '{"params":[]}'),
    ('Home', '回原点（等价自检，语义近似）', 30, 120, false, '{"params":[]}'),
    ('LoadProtocol', '装载 .LHC 协议文件', 2, 30, false,
     '{"params":[{"seq":1,"name":"protocolPath","type":"string","required":true,"desc":".LHC 文件绝对路径"}]}'),
    ('LoadProtocolFromFlash', '装载机内程序', 2, 30, false,
     '{"params":[{"seq":1,"name":"protocolName","type":"string","required":true,"desc":"机内程序名；50 TS 维护程序加 @@ 前缀"}]}'),
    ('ValidateProtocol', '验证协议能否在本机跑（每次 Load 后都要）', 5, 60, false,
     '{"params":[{"seq":1,"name":"getSettingsFirst","type":"int","required":true,"enum":[0,1],"desc":"必须传 1：false 只按协议文件里的设置校验，不保证跑得通"}]}'),
    ('OverrideValidation', '跳过后续验证（ELx405 不支持）', 1, 10, false,
     '{"params":[{"seq":1,"name":"override","type":"int","required":true,"enum":[0,1],"desc":"0=每次验证 1=跳过"}]}'),
    ('LeaveVacuumPumpOn', '协议结束后是否保持真空泵', 1, 10, false,
     '{"params":[{"seq":1,"name":"vacPumpOn","type":"int","required":true,"enum":[0,1],"desc":"1=连续多板时省掉泵重启等待"}]}'),
    ('RunProtocol', '启动协议并等它跑完（阻塞）', 60, 600, true, '{"params":[]}'),
    ('Wash', '洗板（跑已装载协议；体积/次数在协议里）', 60, 600, true, '{"params":[]}'),
    ('Dispense', '分液（跑已装载协议）', 10, 600, true, '{"params":[]}'),
    ('Prime', '预填充（跑已装载协议）', 30, 600, true, '{"params":[]}'),
    ('GetProtocolStatus', '查协议状态（非阻塞轮询）', 1, 10, false, '{"params":[]}'),
    ('GetStatus', '查设备状态（框架统一名）', 1, 10, false, '{"params":[]}'),
    ('Pause', '暂停（停在哪一步由固件决定）', 1, 30, false, '{"params":[]}'),
    ('Resume', '继续（须先暂停成功）', 1, 30, false, '{"params":[]}'),
    ('Abort', '中止（运行中或暂停后都可）', 5, 60, false, '{"params":[]}'),
    ('Stop', '停止（Abort 的别名）', 5, 60, false, '{"params":[]}'),
    ('GetLastErrorCode', '取最近一次错误码', 1, 10, false, '{"params":[]}'),
    ('GetErrorString', '错误码翻译成文字', 1, 10, false,
     '{"params":[{"seq":1,"name":"errorCode","type":"int","required":false,"desc":"要翻译的码；不传取当前"}]}'),
    ('GetProductName', '读产品名', 1, 10, false, '{"params":[]}'),
    ('GetSerialNumber', '读序列号（ELx405 不支持）', 1, 10, false, '{"params":[]}'),
    ('GetWellCount', '读板型孔数（须先 LoadProtocol）', 1, 10, false, '{"params":[]}'),
    ('GetOnBoardProtocolCount', '机内程序数量', 1, 10, false, '{"params":[]}'),
    ('GetOnBoardProtocolName', '机内程序名', 1, 10, false,
     '{"params":[{"seq":1,"name":"index","type":"int","required":true,"min":0,"desc":"0 起的索引，须先取 Count"}]}'),
    ('SetFirstStrip', '起始条（仅 50 TS）', 1, 10, false,
     '{"params":[{"seq":1,"name":"firstStrip","type":"int","required":true,"min":1,"max":12,"desc":"起始列；与 SetNumberOfStrips 成对使用"}]}'),
    ('SetNumberOfStrips', '条数（仅 50 TS）', 1, 10, false,
     '{"params":[{"seq":1,"name":"numberOfStrips","type":"int","required":true,"min":1,"max":12,"desc":"firstStrip + 条数 - 1 不得超过板宽"}]}'),
    ('RunVerifyManifoldTest', '管路验证（仅带液面检测的 405 TS/LS）', 60, 600, true, '{"params":[]}'),
    ('GetVerifyManifoldRunStatus', '管路验证状态', 1, 10, false, '{"params":[]}'),
    ('GetVerifyManifoldResults', '管路验证结果（PASSED / FAILED_Step_x / ERROR xxxx）', 5, 30, false, '{"params":[]}'),
    ('CreateVerifyManifoldReport', '生成管路验证报告', 5, 30, false, '{"params":[]}')
  ) AS v(method, cname, est, tmo, intr, schema)
ON CONFLICT (tenant_id, device_model_id, method) WHERE scope = 'model' DO NOTHING;

-- ---------------------------------------------------------------- 方法（GEN5）
WITH d AS (SELECT id FROM device WHERE tenant_id = 1 AND code = 'GEN5'),
     c AS (SELECT (SELECT id FROM capability WHERE code = 'READ_PLATE') AS cap)
INSERT INTO device_method (tenant_id, device_model_id, method, name, capability_id,
                           param_schema, estimate_sec, timeout_sec, scope, interruptible)
SELECT 1, d.id, v.method, v.cname, c.cap, v.schema::jsonb, v.est, v.tmo, 'model', v.intr
  FROM d, c, (VALUES
    ('ConfigureUSBReader', '配置 USB 读板机（推荐）', 1, 15, false,
     '{"params":[{"seq":1,"name":"readerType","type":"int","required":false,"desc":"机型号（16=Synergy H1，22=Epoch 2…）；-1=只接一台时自动选"},{"seq":2,"name":"serialNumber","type":"string","required":false,"desc":"多台同型号时必须给；单台可空"}]}'),
    ('ConfigureSerialReader', '配置串口读板机', 1, 15, false,
     '{"params":[{"seq":1,"name":"readerType","type":"int","required":true,"desc":"机型号"},{"seq":2,"name":"comPort","type":"int","required":true,"desc":"串口号（1=COM1）"},{"seq":3,"name":"baudRate","type":"int","required":true,"desc":"波特率"}]}'),
    ('TestReaderCommunication', '读板机连通性测试', 5, 30, false, '{"params":[]}'),
    ('Connect', '连通性测试（别名）', 5, 30, false, '{"params":[]}'),
    ('GetReaderStatus', '读板机状态', 1, 10, false, '{"params":[]}'),
    ('GetStatus', '设备状态（框架统一名）', 1, 10, false, '{"params":[]}'),
    ('GetLastReaderError', '最近一次读板机错误', 1, 10, false, '{"params":[]}'),
    ('GetReaderCharacteristics', '读板机能力（波长/温控/序列号…）', 1, 10, false,
     '{"params":[{"seq":1,"name":"id","type":"int","required":true,"min":0,"max":11,"desc":"0 吸收支持/1 最小波长/2 最大波长/3 读法数/5 是否可温控/6 最低温/7 最高温/8 梯度/9 振荡/10 序列号/11 机型名"},{"seq":2,"name":"index","type":"int","required":false,"desc":"带索引的特征（如读法名）"}]}'),
    ('SelfCheck', '系统测试', 60, 300, true, '{"params":[]}'),
    ('StartSystemTest', '系统测试（真名）', 60, 300, true, '{"params":[]}'),
    ('NewExperiment', '按协议新建实验', 2, 60, false,
     '{"params":[{"seq":1,"name":"protocolPathName","type":"string","required":true,"desc":"DB:\\\\Protocols\\\\x.prt 或 C:\\\\...（按 Gen5 存储模式）"}]}'),
    ('OpenExperiment', '打开已有实验', 2, 60, false,
     '{"params":[{"seq":1,"name":"experimentPathName","type":"string","required":true,"desc":"实验文件路径（同上两种模式）"}]}'),
    ('CloseExperiment', '关闭实验（先释放 Plate/Plates）', 2, 30, false, '{"params":[]}'),
    ('SaveExperiment', '保存实验', 2, 60, false, '{"params":[]}'),
    ('SaveExperimentAs', '另存实验', 2, 60, false,
     '{"params":[{"seq":1,"name":"path","type":"string","required":true,"desc":"目标路径"}]}'),
    ('SetPlateLayout', '设置板布局', 1, 30, false,
     '{"params":[{"seq":1,"name":"plateLayoutXml","type":"string","required":true,"desc":"BTIPlateLayout XML"}]}'),
    ('GetPlateLayout', '读板布局', 1, 30, false,
     '{"params":[{"seq":1,"name":"protocolId","type":"string","required":false}]}'),
    ('GetPlateTypeNames', '支持的板型名', 1, 10, false,
     '{"params":[{"seq":1,"name":"selectionFlag","type":"int","required":false}]}'),
    ('CarrierIn', '承载器进板', 4, 60, false,
     '{"params":[{"seq":1,"name":"barcode","type":"string","required":false,"desc":"可选：用于回填板身份"}]}'),
    ('Load', '进板（别名）', 4, 60, false,
     '{"params":[{"seq":1,"name":"barcode","type":"string","required":false}]}'),
    ('CarrierInEx', '承载器进板（带板型/带盖信息）', 4, 60, false,
     '{"params":[{"seq":1,"name":"plateTypeName","type":"string","required":false,"desc":"空=承载器上没有板；机型老于 Synergy2 时忽略"},{"seq":2,"name":"lidOnPlate","type":"int","required":false,"enum":[0,1]}]}'),
    ('CarrierOut', '承载器出板', 4, 60, false, '{"params":[]}'),
    ('Unload', '出板（别名）', 4, 60, false, '{"params":[]}'),
    ('RunReaderControl', '面板动作（门/振荡/灯/预填充…）', 3, 60, false,
     '{"params":[{"seq":1,"name":"command","type":"int","required":true,"desc":"0 面板/2 门与承载器/4 振荡/8 分液/16 预热/32 灯/64 预填充/512 承载器进/1024 承载器出/2048 清洁物镜"}]}'),
    ('Read', '读板（阻塞到读完）', 15, 600, true, '{"params":[]}'),
    ('ReadPlate', '读板（别名）', 15, 600, true, '{"params":[]}'),
    ('ReadEx', '读板（可 180° 旋转）', 15, 600, true,
     '{"params":[{"seq":1,"name":"rotate180","type":"int","required":false,"enum":[0,1]}]}'),
    ('SimulatedRead', '模拟读板（不占用仪器，联调用）', 3, 60, false, '{"params":[]}'),
    ('AbortRead', '中止读板', 3, 30, false, '{"params":[]}'),
    ('Stop', '停止（AbortRead 的别名）', 3, 30, false, '{"params":[]}'),
    ('GetReadProgress', '读板进度（readInProgress）', 1, 10, false, '{"params":[]}'),
    ('KeepPlateInAfterRead', '读完不吐板', 1, 10, false, '{"params":[]}'),
    ('SetPartialPlate', '运行时选孔', 1, 30, false,
     '{"params":[{"seq":1,"name":"partialPlateXml","type":"string","required":true,"desc":"BTIPartialPlate XML；协议须允许运行时选孔"}]}'),
    ('ImportSampleIDs', '导入样本 ID', 2, 60, false,
     '{"params":[{"seq":1,"name":"filePath","type":"string","required":true}]}'),
    ('GetDataSetNames', '读数集名列表', 1, 30, false,
     '{"params":[{"seq":1,"name":"protocolId","type":"string","required":false}]}'),
    ('GetDataSetInfo', '读数集信息', 1, 30, false, '{"params":[]}'),
    ('GetResult', '取读数集内容', 1, 60, false,
     '{"params":[{"seq":1,"name":"protocolId","type":"string","required":false},{"seq":2,"name":"dataSetName","type":"string","required":false,"desc":"空=默认数据集"},{"seq":3,"name":"indexes","type":"string","required":false,"desc":"空=全部孔"}]}'),
    ('GetRawData', '取原始数据（须先开 DataExportEnabled）', 1, 60, false, '{"params":[]}'),
    ('ExportFile', '按导出器导出文本', 5, 120, false,
     '{"params":[{"seq":1,"name":"builderName","type":"string","required":false,"desc":"导出器名（协议里定义）"},{"seq":2,"name":"path","type":"string","required":false,"desc":"空=按协议设置"}]}'),
    ('PrintReport', '打印报告', 5, 120, false,
     '{"params":[{"seq":1,"name":"builderName","type":"string","required":false},{"seq":2,"name":"showPrintDlg","type":"int","required":false,"enum":[0,1]}]}'),
    ('GetTemperature', '读当前温度与状态', 1, 10, false, '{"params":[]}'),
    ('SetTemperature', '设孵育温度', 3, 60, false,
     '{"params":[{"seq":1,"name":"incubate","type":"int","required":false,"enum":[0,1],"desc":"0=关孵育"},{"seq":2,"name":"setPoint","type":"number","required":false,"min":20,"max":50,"desc":"目标温度 °C"},{"seq":3,"name":"gradient","type":"number","required":false,"desc":"梯度；0=不启用"}]}'),
    ('GetTemperatureSetPoint', '读设定温度与梯度', 1, 10, false, '{"params":[]}')
  ) AS v(method, cname, est, tmo, intr, schema)
ON CONFLICT (tenant_id, device_model_id, method) WHERE scope = 'model' DO NOTHING;

-- ---------------------------------------------------------------- 点位属性模板
-- 两台都是"单口进出"：一个 `{设备}-BOTH` 点，进/出共用，机械臂可达。
-- 用途：后续 sync/同步点位时按型号自动带出属性（现在是 0 行的空表，这里补头两行）。
INSERT INTO device_point_template (tenant_id, device_model, point_type, approach_axis,
                                   safe_z_offset, max_stack, height_mm, requires_door,
                                   port_direction, notes)
VALUES
  (1, 'biotek-lhc', 'generic', 'front', 50, 1, 45.0, false, 'both',
   '{"points":[{"code":"BOTH","role":"both","note":"承载器单口进出"}],"source":"20261117_device_register_biotek_lhc_gen5"}'),
  (1, 'biotek-gen5', 'generic', 'front', 60, 1, 45.0, false, 'both',
   '{"points":[{"code":"BOTH","role":"both","note":"承载器单口进出；门由 Gen5 软件控制（CarrierIn/Out）"}],"source":"20261117_device_register_biotek_lhc_gen5"}')
ON CONFLICT (tenant_id, device_model) DO NOTHING;

-- ---------------------------------------------------------------- 点位（单口 BOTH）
-- 设备节点由 `DeviceService.deployModel` 在"部署到岛"时创建（code = 设备 code），
-- 所以这里**只在节点已存在时**建点位；不存在就跳过并打印提示，
-- 这样本文件既能先于部署执行，也能在部署后补点位。
DO $$
DECLARE
    d      record;
    dev_id bigint;
    pt_id  bigint;
    pt_code text;
BEGIN
    FOR d IN
        SELECT code, model FROM (VALUES ('EL406', 'EL406'), ('GEN5', 'Gen5')) AS t(code, model)
    LOOP
        SELECT id INTO dev_id FROM resource_node
         WHERE tenant_id = 1 AND code = d.code AND kind = 'device'
         LIMIT 1;
        IF dev_id IS NULL THEN
            RAISE NOTICE '跳过点位：设备节点 % 还不存在（先部署到岛，再重跑本文件）', d.code;
            CONTINUE;
        END IF;

        pt_code := d.code || '-BOTH';
        INSERT INTO resource_node (tenant_id, parent_id, code, name, kind, location, attr, sort_order)
        VALUES (1, dev_id, pt_code, pt_code, 'storage',
                jsonb_build_object('zone', d.code, 'point', pt_code, 'physical_point', ''),
                jsonb_build_object('source', '20261117_device_register_biotek_lhc_gen5',
                                   'role', 'both'),
                1)
        ON CONFLICT (tenant_id, code) DO UPDATE
           SET name = EXCLUDED.name, parent_id = EXCLUDED.parent_id,
               kind = EXCLUDED.kind, location = EXCLUDED.location, updated_at = now()
        RETURNING id INTO pt_id;

        INSERT INTO device_point (tenant_id, device_resource_id, point_resource_id,
                                  point_role, robot_reachable, is_entry, is_exit,
                                  approach_axis, order_in_device, max_stack, requires_door, note)
        VALUES (1, dev_id, pt_id, 'both', true, true, true, 'front', 1, 1, false,
                '承载器单口进出（' || d.code || '）')
        ON CONFLICT (tenant_id, point_resource_id) DO NOTHING;

        INSERT INTO position_state (tenant_id, resource_id, plate_id, updated_at)
        VALUES (1, pt_id, NULL, now())
        ON CONFLICT (tenant_id, resource_id) DO NOTHING;

        RAISE NOTICE '点位就绪：% (device=% id=%)', pt_code, d.code, pt_id;
    END LOOP;
END $$;

-- ---------------------------------------------------------------- 验收 SQL（手工跑）
-- ① 型号与方法数：
--   SELECT d.code, d.model, d.capability_id, count(m.id) AS methods
--     FROM device d LEFT JOIN device_method m ON m.device_model_id = d.id
--    WHERE d.tenant_id = 1 AND d.code IN ('EL406','GEN5') GROUP BY 1,2,3;
-- ② 点位与配置：
--   SELECT d.code, r.code AS point, dp.point_role, dp.is_entry, dp.is_exit, ps.plate_id
--     FROM resource_node d
--     JOIN resource_node r ON r.parent_id = d.id AND r.tenant_id = d.tenant_id
--     LEFT JOIN device_point dp ON dp.point_resource_id = r.id
--     LEFT JOIN position_state ps ON ps.resource_id = r.id
--    WHERE d.tenant_id = 1 AND d.code IN ('EL406','GEN5');
-- ③ 错误码（本文件不建，来自 95_seed_data.sql）：
--   SELECT count(*) FROM error_code WHERE code LIKE 'EC-08-42-%' OR code LIKE 'EC-08-43-%';
