-- P0-1 · deck_in（台面内工位）机器人不可达口径修正（2026-11-14 独立评审）
--
-- 问题：ID-2011 的 4 个 deck_in（ID-2011-WS1..4）此前 robot_reachable=true、
--   point_role='both'、is_entry/is_exit=true，与「deck_in=台面内工位」语义矛盾，
--   也与既有 272 个 internal 点位（robot_reachable=f, role=internal, 非进出）不一致。
--
-- 风险：调度器若按 robot_reachable=true 派活，可能把「搬进台面内工位」派给机械臂，
--   而机械臂够不到台面内部（台面内板由工作站内部机构取放）。
--
-- 修正：deck_in 统一为 internal 口径（机器人不可达、非机器人进出位）。
-- 幂等：可重复执行。

UPDATE device_point
   SET robot_reachable = false,
       point_role      = 'internal',
       is_entry        = false,
       is_exit         = false,
       updated_at      = now()
 WHERE point_kind = 'deck_in'
   AND (robot_reachable = true OR point_role <> 'internal' OR is_entry = true OR is_exit = true);
