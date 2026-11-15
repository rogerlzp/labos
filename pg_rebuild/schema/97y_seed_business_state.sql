-- 设备业务状态字典 · 初始数据（2026-11-15）
--
-- 口径来源：客户《高通量核酸提取工作站 · 软件控制流程说明书》§1.5 设备状态枚举。
--
-- ⚠ **device_kind 用平台自己的 device_type 口径**，与文档 §1.5 的 5 类设备一一对应：
--   提取仪 extractor / 分液器 dispenser / 搅拌单元 stirrer /
--   机械臂 robot / 耗材栈 plate_hotel
--
-- ⚠ **「搅拌单元」这一组是"预置但暂不挂设备"**：
--   客户口径把混匀单元列为独立设备（§1.1「6 套，与分液器一一绑定」），
--   但当前建模口径是**混匀单元不做独立设备**（它是分液器的搅拌工位）
--   ⇒ 它没有设备实例可挂状态。
--   ⇒ 先把三态备好；等"混匀单元是否独立设备"这条口径定了，
--      要么建独立设备实例，要么把这组状态标 enabled=false。

INSERT INTO device_business_state (tenant_id, device_kind, state_code, state_name, sort_order, enabled)
VALUES
  -- 提取仪（§1.5：空闲、初始化中、等待交互、转盘运行中、操作位执行工序、流程结束、故障报错）
  (1, 'extractor', 'idle',                 '空闲',                     10, true),
  (1, 'extractor', 'initializing',         '初始化中',                 20, true),
  (1, 'extractor', 'waiting_interaction',  '等待交互',                 30, true),
  (1, 'extractor', 'turntable_running',    '转盘运行中',               40, true),
  (1, 'extractor', 'operating',            '操作位执行工序（裂解/磁吸/W1/W2/W3/洗脱）', 50, true),
  (1, 'extractor', 'finished',             '流程结束',                 60, true),
  (1, 'extractor', 'fault',                '故障报错',                 70, true),
  -- 分液器（§1.5：就绪、分液中、分液完成、故障）
  (1, 'dispenser', 'ready',      '就绪',       10, true),
  (1, 'dispenser', 'dispensing', '分液中',     20, true),
  (1, 'dispenser', 'dispensed',  '分液完成',   30, true),
  (1, 'dispenser', 'fault',      '故障',       40, true),
  -- 搅拌单元（§1.5：运行、停止、故障）—— 预置，暂不挂设备实例
  (1, 'stirrer', 'running', '运行', 10, true),
  (1, 'stirrer', 'stopped',  '停止', 20, true),
  (1, 'stirrer', 'fault',    '故障', 30, true),
  -- 机械臂（§1.5：原点、移动中、取放板中、占位、故障）
  (1, 'robot', 'home',      '原点',     10, true),
  (1, 'robot', 'moving',    '移动中',   20, true),
  (1, 'robot', 'handling',  '取放板中', 30, true),
  (1, 'robot', 'occupied',  '占位',     40, true),
  (1, 'robot', 'fault',     '故障',     50, true),
  -- 耗材栈（§1.5：充足、预警、不足）
  (1, 'plate_hotel', 'sufficient',   '充足', 10, true),
  (1, 'plate_hotel', 'warning',      '预警', 20, true),
  (1, 'plate_hotel', 'insufficient', '不足', 30, true)
ON CONFLICT (tenant_id, device_kind, state_code) DO NOTHING;

-- ---------------------------------------------------------------- 板状态：补 §1.4 的缺口 + 叫法对照

-- 客户 §1.4 的 6 态与平台现有 5 条的对照：
--   空板           → raw
--   待分液         → ready
--   实验中         → in_progress
--   待交互（等待送入提取仪）→ **平台没有** ⇒ 新增 waiting_transfer
--   已完成（产物板）→ completed
--   废弃耗材板      → discarded
--
-- ⚠ **只新增、不改名、不删**：plate_process_state 已被运行时消费
--   （MaterialEffectService 校验 processState 必须在启用字典里），
--   改名会打断既有物料效果。
-- ⇒ 用 doc_label 列承载「客户叫法」，两边对照在管理页里看。

-- ① 新增「待交互（等待送入提取仪）」
--    code 用 waiting_transfer 而不是 waiting_interaction：
--    后者已经是**设备事件**字典里的码（device_event_type），
--    同字面量在两张表里含义不同（设备报了 vs 板在等）⇒ 会让人搞混。
INSERT INTO plate_process_state (tenant_id, code, display_name, sort_order, enabled, doc_label)
VALUES (1, 'waiting_transfer', '待交互（等待送入提取仪）', 40, true, '待交互（等待送入提取仪）')
ON CONFLICT (tenant_id, code) DO NOTHING;

-- ② 补齐 5 条既有状态的「客户叫法」（doc_label 为空时才填 ⇒ 不覆盖人工改过的）
UPDATE plate_process_state SET doc_label = '空板'    WHERE tenant_id = 1 AND code = 'raw'         AND (doc_label IS NULL OR btrim(doc_label) = '');
UPDATE plate_process_state SET doc_label = '待分液'  WHERE tenant_id = 1 AND code = 'ready'       AND (doc_label IS NULL OR btrim(doc_label) = '');
UPDATE plate_process_state SET doc_label = '实验中'  WHERE tenant_id = 1 AND code = 'in_progress' AND (doc_label IS NULL OR btrim(doc_label) = '');
UPDATE plate_process_state SET doc_label = '已完成（产物板）' WHERE tenant_id = 1 AND code = 'completed' AND (doc_label IS NULL OR btrim(doc_label) = '');
UPDATE plate_process_state SET doc_label = '废弃耗材板'     WHERE tenant_id = 1 AND code = 'discarded' AND (doc_label IS NULL OR btrim(doc_label) = '');
