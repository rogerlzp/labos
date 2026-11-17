-- ============================================================================
-- 登记两个有手册的型号：PAuto（磁珠法核酸提取仪）、9608（加液/分液设备）
-- 2026-11-15
--
-- **零 DDL**：全部落在既有表上，不新建、不改结构。
--   · 型号主数据   → public.device（model/vendor/device_type/capability_id/icon/attr jsonb）
--   · 型号专属方法 → public.device_method（device_id + method + param_schema + estimate_sec，
--                   已有 UNIQUE(device_id, method)，用 ON CONFLICT DO NOTHING 幂等）
--   · 厂商错误码   → public.error_code（code/source/severity/title/detail/attr）
--   · 错误处置     → public.recovery_policy（按 error_code_id 挂，strategy 枚举）
--
-- 不写 duration_model：那是**实测统计**（p50/p90/sample_count），编初值等于造假；
-- 未跑过的设备就让它空着，模拟器用 device_method.estimate_sec 兜底。
--
-- 协议级字段（帧格式/波特率/证据等级/手册路径）落 device.attr->'protocol'：
-- attr 本来就是 jsonb 扩展位（ASPIN_2K 那行用它存迁移元数据），零 DDL 且前端可直接渲染。
--
-- 幂等：可反复执行，已存在的行不覆盖（现场改过的值不会被冲掉）。
-- ============================================================================

-- ★ 2026-11-17 守卫：本脚本用的是**旧列名** device_method.device_id /
--   device_instance.device_id，2026-11-16 已改名为 device_model_id（BUG-20261116-02）。
--   历史脚本按当时的形态保留、不回改（它记录的是首次执行的形态，数据早已在库里）；
--   但在改名后的库上重跑会撞一句难懂的 `column does not exist`，这里提前给人话。
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.columns
              WHERE table_schema = current_schema()
                AND table_name = 'device_method' AND column_name = 'device_model_id') THEN
    RAISE EXCEPTION '本脚本使用旧列名 device_id，而库已在 2026-11-16 改名为 device_model_id。
它的数据（型号 + 方法集）在首次执行时已入库，改名后无需也不应重跑。
若要新增/更新型号，请走 DeviceRegistrar 或设备池 API。';
  END IF;
END $$;

-- ---------------------------------------------------------------- 型号主数据
-- PAuto：磁珠法核酸提取仪（RS232 9600 8N1；帧 0xFA 0xFB + Cmd + Len(2) + Data(25) + Checksum）
INSERT INTO device (tenant_id, code, name, model, vendor, device_type, capability_id, icon, attr)
SELECT 1, 'P_AUTO', 'PAuto 磁珠法核酸提取仪', 'PAuto', '天隆科技', 'Extractors',
       (SELECT id FROM capability WHERE code = 'EXTRACT'),
       '/devices/PAUTO.svg',
       jsonb_build_object(
         -- 中控认的型号 id（= DeviceModels 的 key，也是 GET /api/devices/registry 的 modelId）
         'device_model', 'pauto',
         'protocol', jsonb_build_object(
           'kind', 'binary-serial',
           'baud', 9600, 'dataBits', 8, 'stopBits', 1, 'parity', 'none',
           'frame', jsonb_build_object(
             'sof', jsonb_build_array('0xFA', '0xFB'),
             'cmdBytes', 1, 'lenBytes', 2, 'dataBytes', 25, 'checksumBytes', 1,
             'checksum', 'cmd+len+data 之和取低 8 位',
             'endian', 'little'),
           'actions', jsonb_build_array(
             'Bind', 'Elute', 'DiscardBeads', 'CollectBeads', 'Incubate', 'Wash',
             'PickTipComb', 'DropTipComb', 'RotatePlate'),
           'bits', '2 板位（0/1）；体积索引 0-8；加热 0-99℃；磁化循环 1-10'),
         'manual', 'docs/设备/PAuto-磁珠法核酸提取仪.md',
         'evidence', 'MANUAL',
         'gaps', jsonb_build_array(
           '无厂商错误码表（只有适配层码）',
           '无应答帧字节布局（按同帧回显推断）',
           '体积单位 mL/µL 存疑',
           '单动作超时上限未给'))
WHERE NOT EXISTS (SELECT 1 FROM device WHERE tenant_id = 1 AND code = 'P_AUTO');

-- 9608：加液（分液）设备（串口 115200 8N1；12 字节帧 0xAA…0xFF，应答 0xAC，故障 0xEE）
INSERT INTO device (tenant_id, code, name, model, vendor, device_type, capability_id, icon, attr)
SELECT 1, 'D9608', '9608 加液分液设备', '9608', '天隆科技', 'Dispensers & Washers',
       (SELECT id FROM capability WHERE code = 'ALIQUOT'),
       '/devices/D9608.svg',
       jsonb_build_object(
         'device_model', 'd9608',
         'protocol', jsonb_build_object(
           'kind', 'binary-serial',
           'baud', 115200, 'dataBits', 8, 'stopBits', 1, 'parity', 'none',
           'frame', jsonb_build_object(
             'sof', jsonb_build_array('0xAA'),
             'replySof', '0xAC', 'faultSof', '0xEE', 'eof', '0xFF',
             'bytes', 12, 'checksumByte', 11,
             'checksum', '第 2..10 字节之和取低 8 位',
             'dataOffset', 5, 'endian', 'little'),
           'bits', 'A/B 两板位；96/384 孔；0x40/0x41 为变长报文（与 12 字节总述矛盾，待现场确认）'),
         'manual', 'docs/设备/9608-加液分液设备.md',
         'evidence', 'MANUAL',
         'gaps', jsonb_build_array(
           '0x40/0x41 是变长帧还是多帧续传未明确',
           '管路类型 0/2、1/3 重复（疑笔误）',
           '单次加液耗时未给',
           '加液量上下限未给（按同类设备 0.5-2500µL 软校验）'))
WHERE NOT EXISTS (SELECT 1 FROM device WHERE tenant_id = 1 AND code = 'D9608');

-- ---------------------------------------------------------------- PAuto 方法
WITH d AS (SELECT id FROM device WHERE tenant_id = 1 AND code = 'P_AUTO'),
     c AS (SELECT id AS cap FROM capability WHERE code = 'EXTRACT')
INSERT INTO device_method (tenant_id, device_id, method, name, capability_id,
                           param_schema, estimate_sec, timeout_sec, scope, interruptible)
SELECT 1, d.id, v.method, v.cname, c.cap, v.schema::jsonb, v.est, v.tmo, 'model', v.intr
  FROM d, c, (VALUES
    ('Bind', '结合', 480, 600, false,
     '{"params":[{"seq":1,"name":"plateIndex","type":"int","required":true,"min":0,"max":1,"desc":"板位 0/1（D1）"},
                 {"seq":2,"name":"volumeIndex","type":"int","required":false,"min":0,"max":8,"desc":"体积索引 0-8（D2）"},
                 {"seq":3,"name":"heatTemp","type":"int","required":false,"min":0,"max":99,"desc":"加热温度℃（D3）"},
                 {"seq":4,"name":"heatTime","type":"int","required":false,"min":0,"max":9999,"desc":"加热时间（D4）"},
                 {"seq":5,"name":"heatMode","type":"int","required":false,"min":0,"max":2,"desc":"0内加热/1外加热/2加热后振荡（D6）"},
                 {"seq":6,"name":"magnetHeight","type":"int","required":false,"min":0,"max":2,"desc":"进入深孔板磁套高度（D7）"},
                 {"seq":7,"name":"magnetHoldMs","type":"int","required":false,"min":0,"max":9999,"desc":"进入深孔板停留时间（D8）"},
                 {"seq":8,"name":"shakeAmp","type":"int","required":false,"min":0,"max":2,"desc":"振荡幅度（D10）"},
                 {"seq":9,"name":"shakeFreq","type":"int","required":false,"min":0,"max":4,"desc":"振荡频率（D11）"},
                 {"seq":10,"name":"shakeMs","type":"int","required":false,"min":0,"max":9999,"desc":"振荡时间（D12）"},
                 {"seq":11,"name":"shakeMode","type":"int","required":false,"min":0,"max":1,"desc":"振荡模式（D14）"},
                 {"seq":12,"name":"shakeHeight","type":"int","required":false,"min":0,"max":5,"desc":"振荡磁套高度（D15）"},
                 {"seq":13,"name":"shakeDelayMs","type":"int","required":false,"min":0,"max":9999,"desc":"振荡后延时（D16）"},
                 {"seq":14,"name":"magnetPos","type":"int","required":false,"min":0,"max":2,"desc":"磁化位置（D18）"},
                 {"seq":15,"name":"magnetMs","type":"int","required":false,"min":0,"max":9999,"desc":"磁化时间（D19）"},
                 {"seq":16,"name":"magnetMode","type":"int","required":false,"min":0,"max":1,"desc":"磁化模式（D21）"},
                 {"seq":17,"name":"magnetCycles","type":"int","required":false,"min":1,"max":10,"desc":"磁化循环次数 1-10（D22）"},
                 {"seq":18,"name":"exitDelayMs","type":"int","required":false,"min":0,"max":9999,"desc":"磁套退出后延时（D23）"}]}'),
    ('Elute', '洗脱', 300, 400, false,
     '{"params":[{"seq":1,"name":"plateIndex","type":"int","required":true,"min":0,"max":1,"desc":"板位 0/1"},
                 {"seq":2,"name":"volumeIndex","type":"int","required":false,"min":0,"max":8,"desc":"体积索引"},
                 {"seq":3,"name":"heatTemp","type":"int","required":false,"min":0,"max":99,"desc":"加热温度"},
                 {"seq":4,"name":"heatTime","type":"int","required":false,"min":0,"max":9999,"desc":"加热时间"},
                 {"seq":5,"name":"magnetCycles","type":"int","required":false,"min":1,"max":10,"desc":"磁化循环次数"}]}'),
    ('DiscardBeads', '丢磁珠', 60, 120, false,
     '{"params":[{"seq":1,"name":"plateIndex","type":"int","required":true,"min":0,"max":1,"desc":"板位"},
                 {"seq":2,"name":"shakeAmp","type":"int","required":false,"min":0,"max":2,"desc":"振荡幅度"},
                 {"seq":3,"name":"shakeFreq","type":"int","required":false,"min":0,"max":4,"desc":"振荡频率"},
                 {"seq":4,"name":"shakeMs","type":"int","required":false,"min":0,"max":9999,"desc":"振荡时间"}]}'),
    ('CollectBeads', '取磁珠', 60, 120, false,
     '{"params":[{"seq":1,"name":"plateIndex","type":"int","required":true,"min":0,"max":1,"desc":"板位"},
                 {"seq":2,"name":"magnetPos","type":"int","required":false,"min":0,"max":2,"desc":"磁化位置"},
                 {"seq":3,"name":"magnetMs","type":"int","required":false,"min":0,"max":9999,"desc":"磁化时间"},
                 {"seq":4,"name":"magnetCycles","type":"int","required":false,"min":1,"max":10,"desc":"磁化循环次数"}]}'),
    ('Incubate', '孵育', 300, 400, false,
     '{"params":[{"seq":1,"name":"plateIndex","type":"int","required":true,"min":0,"max":1,"desc":"板位"},
                 {"seq":2,"name":"magnetHeight","type":"int","required":false,"min":0,"max":2,"desc":"磁套进入高度"},
                 {"seq":3,"name":"shakeMs","type":"int","required":false,"min":0,"max":9999,"desc":"振荡时间"}]}'),
    ('Wash', '漂洗', 180, 300, false,
     '{"params":[{"seq":1,"name":"plateIndex","type":"int","required":true,"min":0,"max":1,"desc":"板位"},
                 {"seq":2,"name":"volumeIndex","type":"int","required":false,"min":0,"max":8,"desc":"体积索引"},
                 {"seq":3,"name":"shakeMs","type":"int","required":false,"min":0,"max":9999,"desc":"振荡时间"},
                 {"seq":4,"name":"magnetCycles","type":"int","required":false,"min":1,"max":10,"desc":"磁化循环次数"}]}'),
    ('PickTipComb', '取磁套', 15, 60, false,
     '{"params":[{"seq":1,"name":"plateIndex","type":"int","required":true,"min":0,"max":1,"desc":"板位"}]}'),
    ('DropTipComb', '卸磁套', 15, 60, false,
     '{"params":[{"seq":1,"name":"plateIndex","type":"int","required":true,"min":0,"max":1,"desc":"板位"}]}'),
    ('RotatePlate', '换板位', 2, 30, false,
     '{"params":[{"seq":1,"name":"plateIndex","type":"int","required":true,"min":0,"max":1,"desc":"目标板位"}]}'),
    ('Home', '回原点', 3, 30, false,
     '{"params":[]}'),
    ('Stop', '停止', 2, 30, true,
     '{"params":[]}'),
    ('GetStatus', '查状态', 1, 10, false,
     '{"params":[]}')
  ) AS v(method, cname, est, tmo, intr, schema)
ON CONFLICT (device_id, method) DO NOTHING;

-- 补充 device_model（中控型号 id）：只在缺失时写，避免覆盖现场改过的值。
-- 为什么必须补：GET /api/devices/registry 的 modelId 取的是 attr->>'device_model'，
-- 缺它 ⇒ 模拟器/适配层认不出型号，退回通用画像。
UPDATE device SET attr = attr || jsonb_build_object('device_model', 'pauto'), updated_at = now()
 WHERE tenant_id = 1 AND code = 'P_AUTO'
   AND (attr->>'device_model') IS DISTINCT FROM 'pauto';

-- ---------------------------------------------------------------- 9608 方法
WITH d AS (SELECT id FROM device WHERE tenant_id = 1 AND code = 'D9608'),
     c AS (SELECT id AS cap FROM capability WHERE code = 'ALIQUOT')
INSERT INTO device_method (tenant_id, device_id, method, name, capability_id,
                           param_schema, estimate_sec, timeout_sec, scope, interruptible)
SELECT 1, d.id, v.method, v.cname, c.cap, v.schema::jsonb, v.est, v.tmo, 'model', v.intr
  FROM d, c, (VALUES
    ('SetParam', '设置参数（0x00）', 1, 10, false,
     '{"params":[{"seq":1,"name":"index","type":"int","required":true,"min":0,"max":255,"desc":"参数序号（0x1F/0x20/0x21/0x22/0x23/0x24）"},
                 {"seq":2,"name":"value","type":"int","required":true,"min":0,"max":65535,"desc":"参数值（u16 小端）"}]}'),
    ('ReadParam', '查询参数（0x01）', 1, 10, false,
     '{"params":[{"seq":1,"name":"index","type":"int","required":true,"min":0,"max":255,"desc":"参数序号"}]}'),
    ('SaveParam', '保存参数（0x02）', 1, 10, false,
     '{"params":[]}'),
    ('PrintParams', '打印全部参数（0x03）', 1, 10, false,
     '{"params":[]}'),
    ('Dispense', '开始加液（0x04）', 8, 120, true,
     '{"params":[{"seq":1,"name":"mode","type":"string","required":true,"enum":["a","b","ab","tube_single_h","tube_single_v","tube_double_h","tube_double_v","plate96","plate384","standard","vary_volume","gradient","complex"],"desc":"加液模式（第4字节 + 附加标志）"},
                 {"seq":2,"name":"volumeUl","type":"number","required":false,"min":0.5,"max":2500,"desc":"加液量 µL（软校验，手册未给范围）"},
                 {"seq":3,"name":"plateHeightMm","type":"number","required":false,"desc":"板高 mm（参数 0x20）"},
                 {"seq":4,"name":"nozzleHeightMm","type":"number","required":false,"desc":"喷头加液高度 mm"},
                 {"seq":5,"name":"pumpSpeed","type":"int","required":false,"min":0,"max":2,"desc":"0低速/1中速/2高速"},
                 {"seq":6,"name":"plateType","type":"int","required":false,"enum":[96,384],"desc":"孔板类型"}]}'),
    ('PauseDispense', '暂停加液（0x04 01）', 1, 10, false,
     '{"params":[]}'),
    ('AbortDispense', '放弃加液（0x04 02）', 1, 10, false,
     '{"params":[]}'),
    ('Reset', '复位（0x05）', 5, 30, true,
     '{"params":[]}'),
    ('Home', '回原点（同 0x05）', 5, 30, true,
     '{"params":[]}'),
    ('ClearFault', '清除错误状态（0x06）', 1, 10, false,
     '{"params":[]}'),
    ('PrimePipe', '开始填充管路（0x30 00）', 15, 300, true,
     '{"params":[]}'),
    ('StopPrime', '结束填充管路（0x30 01）', 1, 10, false,
     '{"params":[]}'),
    ('RecoverReagent', '开始回收试剂（0x31 00）', 15, 300, true,
     '{"params":[]}'),
    ('StopRecover', '结束试剂回收（0x31 01）', 1, 10, false,
     '{"params":[]}'),
    ('CleanPipe', '管路清洗（0x32）', 30, 600, true,
     '{"params":[{"seq":1,"name":"times","type":"int","required":true,"min":1,"max":99,"desc":"清洗次数 xx"},
                 {"seq":2,"name":"secondsPerRinse","type":"int","required":true,"min":1,"max":255,"desc":"每次冲洗时间 SS"}]}'),
    ('SelectProgram', '选择机内程序（0x36）', 1, 10, false,
     '{"params":[{"seq":1,"name":"programIndex","type":"int","required":true,"min":0,"max":255,"desc":"机内第 xx 套程序"}]}'),
    ('Dispense96', '96 孔整板加液（0x40）', 8, 120, true,
     '{"params":[{"seq":1,"name":"wells","type":"array","required":true,"desc":"96 个 2 字节孔值（变长报文，待现场确认）"}]}'),
    ('Dispense384', '384 孔整板加液（0x41）', 30, 300, true,
     '{"params":[{"seq":1,"name":"wells","type":"array","required":true,"desc":"384 个 2 字节孔值（变长报文，待现场确认）"}]}'),
    ('GetStatus', '查状态（框架，手册无）', 1, 10, false,
     '{"params":[]}')
  ) AS v(method, cname, est, tmo, intr, schema)
ON CONFLICT (device_id, method) DO NOTHING;

-- 9608 的 device_model 同理（缺它 → registry 的 modelId 为空）
UPDATE device SET attr = attr || jsonb_build_object('device_model', 'd9608'), updated_at = now()
 WHERE tenant_id = 1 AND code = 'D9608'
   AND (attr->>'device_model') IS DISTINCT FROM 'd9608';

-- ---------------------------------------------------------------- 9608 故障码
-- 帧：ee 00 00 00 xx ** ** ** 00 00 00 ff，第 5 字节 = 错误识别码
-- 中控码沿用既有风格：型号前缀进 title，code 保持厂商原始码（便于日志对照）
INSERT INTO error_code (tenant_id, code, source, severity, title, detail, scope, attr)
SELECT 1, v.code, 'device', v.sev::error_severity,
       '（d9608 ' || v.code || '）' || v.text,
       '建议：' || v.advice,
       'device',
       jsonb_build_object('model', '9608', 'vendorCode', v.code)
  FROM (VALUES
    ('0x02', 'error', '错误消息类型', '协议版本不符，中止不要重试'),
    ('0x03', 'error', '参数范围有误', '检查加液量/板高/喷头高度是否在允许范围'),
    ('0x04', 'error', 'CAN 通信错误', '重试 2 次；持续出现查串口线与驱动板'),
    ('0x05', 'error', '电机堵转', '转人工：机械卡住，重试会加剧'),
    ('0x06', 'error', '电机走步误差偏大', '转人工：需重新校准'),
    ('0x08', 'error', '收到错误指令', '指令码/模式不被支持，核对协议版本'),
    ('0x09', 'error', '指令参数数值错误', '改参数后重跑'),
    ('0x0A', 'error', '存储参数出错', '转人工：复位后重新保存参数'),
    ('0x0B', 'error', '传感器失效', '转人工：检查到位/原点传感器'),
    ('0x0E', 'error', '卡扣脱落', '转人工：机械故障'),
    ('0x10', 'warn',  '等待超时', '瞬时问题，可重试'),
    ('0x12', 'info',  '放弃本次实验', '用户主动放弃，不要重试')
  ) AS v(code, sev, text, advice)
 WHERE NOT EXISTS (
   SELECT 1 FROM error_code e
    WHERE e.tenant_id = 1 AND e.code = v.code AND e.title LIKE '（d9608 %');

-- ---------------------------------------------------------------- 9608 处置策略
-- 映射：RETRY→retry / FIX_PARAM|MANUAL→manual / ABORT→abort
INSERT INTO recovery_policy (tenant_id, error_code_id, scope, strategy, params,
                             max_attempts, priority, enabled, match_type, rule_weight)
SELECT 1, e.id, 'command', v.strategy::recovery_strategy, v.params::jsonb,
       v.attempts, 10, true, 'exact', 5
  FROM error_code e
  JOIN (VALUES
    ('0x02', 'abort',  '{}', 0),
    ('0x03', 'manual', '{}', 0),
    ('0x04', 'retry',  '{"backoff":"fixed","base_ms":2000}', 2),
    ('0x05', 'manual', '{}', 0),
    ('0x06', 'manual', '{}', 0),
    ('0x08', 'abort',  '{}', 0),
    ('0x09', 'manual', '{}', 0),
    ('0x0A', 'manual', '{}', 0),
    ('0x0B', 'manual', '{}', 0),
    ('0x0E', 'manual', '{}', 0),
    ('0x10', 'retry',  '{"backoff":"fixed","base_ms":1000}', 3),
    ('0x12', 'skip',   '{}', 0)
  ) AS v(code, strategy, params, attempts) ON e.code = v.code
 WHERE e.tenant_id = 1 AND e.title LIKE '（d9608 %' AND e.deleted_at IS NULL
   AND NOT EXISTS (
     SELECT 1 FROM recovery_policy rp
      WHERE rp.tenant_id = 1 AND rp.error_code_id = e.id);
