-- 设备事件类型字典 · 初始数据（2026-11-15）
--
-- ⚠ **这些字串是"约定"，不是"事实"**：
--   真机的状态字串要按**厂商手册**定，侧车的 ISpontaneousTranslator 产出它，
--   本表与流程节点引用它 ⇒ 三方对齐。
--
--   ⇒ 手册到手后字串若不同，**改本表 + 改侧车 translator** 两处即可，
--     不要改内核（内核只按字串匹配，不认具体字面量）。
--
-- 初始值覆盖「分段自驱」那类设备（客户文档 §1.2）会用到的事件，
-- 语义分类（category）只用于界面分组，**不是行为开关**。

INSERT INTO device_event_type (tenant_id, event_code, display_name, category, needs_action, sort_order, enabled)
VALUES
  -- interaction：需要上位机响应（等待交互类）—— needs_action=true ⇒ 应开人工窗口
  (1, 'waiting_interaction', '等待交互（主动暂停）', 'interaction', true,  10, true),
  (1, 'plate_ready',          '板已就位',             'interaction', false, 20, true),
  (1, 'plate_removed',        '板已取走',             'interaction', false, 30, true),
  -- terminal：终态
  (1, 'process_done',         '流程完成',             'terminal',    false, 40, true),
  (1, 'flow_completed',       '整套流程结束',         'terminal',    false, 50, true),
  -- progress：进度播报（不打断）
  (1, 'idle',                 '空闲',                 'progress',    false, 60, true),
  (1, 'plate_detected',       '检测到板',             'progress',    false, 70, true),
  (1, 'carousel_running',     '转盘运行中',           'progress',    false, 80, true),
  (1, 'stirrer_running',      '搅拌运行中',           'progress',    false, 90, true),
  (1, 'stirrer_fault',        '搅拌故障',             'fault',       true, 100, true),
  (1, 'fault',                '设备故障',             'fault',       true, 110, true)
ON CONFLICT (tenant_id, event_code) DO NOTHING;
