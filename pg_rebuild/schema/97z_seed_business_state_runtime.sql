-- 设备业务状态字典 · 触发方式种子（2026-11-16）
--
-- 配合 20261116_device_business_runtime.sql（口径改成能力 + 补 trigger/source_key/is_idle）。
--
-- **运行时派生规则**（读时算，不存列）：
--   ① 设备故障（device_state.last_error / status='error'）⇒ 该类别的 **fault** 态
--   ② 有命令在跑 ⇒ 匹配 (kind, trigger='command', source_key 含该方法) 的状态
--   ③ 无命令在跑、但**最后一条命令成功结束** ⇒ 匹配 (kind, trigger='command_done') 的状态
--      （典型：分液器"分液完成"—— 分完了、板还没被机械臂取走的那几秒）
--   ④ 无命令在跑、但**最近一条设备事件**在最后一次命令结束之后
--      ⇒ 匹配 (kind, trigger='event', source_key 含该事件码) 的状态
--      （典型：提取仪"等待交互"—— 设备自己停下来喊了一声，一直保持到下一个命令开始）
--   ⑤ 以上都不是 ⇒ 该类别的 **is_idle** 态（静止常态）
--   ⑥ 耗材栈另有派生规则：按可用板数（≥6 充足 / >0 预警 / =0 不足）
--
-- ⚠ **PAuto 口径的诚实说明**（客户 §1.5 的 7 个提取仪状态，当前机型只能部分出现）：
--   · **初始化中**：PAuto 没有【启动提取流程】指令（一条命令跑完整个动作）
--     ⇒ 无方法可挂 ⇒ 这一态在当前机型下**不会出现**（等现场确认机型后再定）。
--   · **转盘运行中**：只能挂在 RotatePlate（转盘切换）上；
--     若真机是"分段自驱"（§1.2 那种），这一态应当由**转盘标志位**驱动，
--     不由方法名驱动 ⇒ 到时候改这一行配置即可（不用改代码）。
--   · **操作位执行工序**：挂在 Incubate/Bind/Wash/Elute/CollectBeads/DiscardBeads 上。
--
-- 可重复执行（按 (tenant, kind, code) 定位，只更新 trigger/source_key/is_idle）。

-- ---------------------------------------------------------------- 提取仪 EXTRACT
UPDATE device_business_state SET trigger='idle', source_key=NULL, is_idle=true
 WHERE tenant_id=1 AND device_kind='EXTRACT' AND state_code='idle';
UPDATE device_business_state SET trigger='command', source_key=NULL, is_idle=false
 WHERE tenant_id=1 AND device_kind='EXTRACT' AND state_code='initializing';
UPDATE device_business_state SET trigger='event', source_key='waiting_interaction', is_idle=false
 WHERE tenant_id=1 AND device_kind='EXTRACT' AND state_code='waiting_interaction';
UPDATE device_business_state SET trigger='command', source_key='RotatePlate', is_idle=false
 WHERE tenant_id=1 AND device_kind='EXTRACT' AND state_code='turntable_running';
UPDATE device_business_state SET trigger='command',
       source_key='Incubate,Bind,Wash,Elute,CollectBeads,DiscardBeads', is_idle=false
 WHERE tenant_id=1 AND device_kind='EXTRACT' AND state_code='operating';
UPDATE device_business_state SET trigger='event', source_key='process_done,flow_completed', is_idle=false
 WHERE tenant_id=1 AND device_kind='EXTRACT' AND state_code='finished';
UPDATE device_business_state SET trigger='fault', source_key=NULL, is_idle=false
 WHERE tenant_id=1 AND device_kind='EXTRACT' AND state_code='fault';

-- ---------------------------------------------------------------- 分液器 WASH
-- ⚠ WASH 组同时装着洗板机（同一能力）⇒ 「分液中」对洗板机读作"处理中"也能接受。
UPDATE device_business_state SET trigger='idle', source_key=NULL, is_idle=true
 WHERE tenant_id=1 AND device_kind='WASH' AND state_code='ready';
UPDATE device_business_state SET trigger='command',
       source_key='Run,RunProtocol,Dispense,Aliquot,TransferLiquid', is_idle=false
 WHERE tenant_id=1 AND device_kind='WASH' AND state_code='dispensing';
UPDATE device_business_state SET trigger='command_done', source_key=NULL, is_idle=false
 WHERE tenant_id=1 AND device_kind='WASH' AND state_code='dispensed';
UPDATE device_business_state SET trigger='fault', source_key=NULL, is_idle=false
 WHERE tenant_id=1 AND device_kind='WASH' AND state_code='fault';

-- ---------------------------------------------------------------- 搅拌单元 MIX
-- ⚠ 当前口径：混匀单元**不做独立设备**（是分液器的搅拌工位）⇒ 这一组暂挂不上设备。
UPDATE device_business_state SET trigger='command',
       source_key='Stir,Mix,MagneticStir,Mixing,Stirrer', is_idle=false
 WHERE tenant_id=1 AND device_kind='MIX' AND state_code='running';
UPDATE device_business_state SET trigger='idle', source_key=NULL, is_idle=true
 WHERE tenant_id=1 AND device_kind='MIX' AND state_code='stopped';
UPDATE device_business_state SET trigger='fault', source_key=NULL, is_idle=false
 WHERE tenant_id=1 AND device_kind='MIX' AND state_code='fault';

-- ---------------------------------------------------------------- 机械臂 TRANSFER
UPDATE device_business_state SET trigger='idle', source_key=NULL, is_idle=true
 WHERE tenant_id=1 AND device_kind='TRANSFER' AND state_code='home';
UPDATE device_business_state SET trigger='command',
       source_key='Move,ReturnToSafe,Rotation,ServoSetPosition,ServoZero', is_idle=false
 WHERE tenant_id=1 AND device_kind='TRANSFER' AND state_code='moving';
UPDATE device_business_state SET trigger='command',
       source_key='Transfer,Pick,Place,GripAction,GripperAction,Flip,Perform,SwitchEOAT,'
                 || 'BarcodeScan,BarcodeScanEx,TrySyncAreas', is_idle=false
 WHERE tenant_id=1 AND device_kind='TRANSFER' AND state_code='handling';
-- 「占位」：动作做完了（板放下了），但机械臂仍占着这条轨道 ⇒ 做完之后的状态
UPDATE device_business_state SET trigger='command_done', source_key=NULL, is_idle=false
 WHERE tenant_id=1 AND device_kind='TRANSFER' AND state_code='occupied';
UPDATE device_business_state SET trigger='fault', source_key=NULL, is_idle=false
 WHERE tenant_id=1 AND device_kind='TRANSFER' AND state_code='fault';

-- ---------------------------------------------------------------- 耗材栈 STORE
-- ⚠ 耗材栈的三态是**派生型**（按可用板数），不由命令/事件驱动。
UPDATE device_business_state SET trigger='derived', source_key=NULL, is_idle=false
 WHERE tenant_id=1 AND device_kind='STORE' AND state_code='sufficient';
UPDATE device_business_state SET trigger='derived', source_key=NULL, is_idle=false
 WHERE tenant_id=1 AND device_kind='STORE' AND state_code='warning';
UPDATE device_business_state SET trigger='derived', source_key=NULL, is_idle=false
 WHERE tenant_id=1 AND device_kind='STORE' AND state_code='insufficient';
