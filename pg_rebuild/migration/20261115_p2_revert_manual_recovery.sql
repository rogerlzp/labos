-- 回退 20261115_p2_manual_recovery.sql（2026-11-15）
-- 决策：心跳自动恢复是正确行为（网络闪断→离线→用到才暂停→恢复在线自动继续），
-- 不应被 needs_manual_recovery 掐断。物理故障的恢复由操作员手动触发，可绕过重取证。

ALTER TABLE run DROP COLUMN IF EXISTS needs_manual_recovery;
