-- F3 · deck_in 可达性约束兜底（2026-11-14 独立评审）
--
-- 背景：P0-1 的 deck_in 修复是纯数据 UPDATE，没有约束防止「deck_in + robot_reachable=true」
-- 再次被插入（评审实测可复现语义冲突）。
--
-- 约束：deck_in（台面内工位）必须机器人不可达 + internal 角色（机械臂只放 deck_out）。
-- 幂等：DO $$ + duplicate_object 保护。

DO $$ BEGIN
  ALTER TABLE device_point
    ADD CONSTRAINT ck_device_point_deck_in_unreachable
    CHECK (point_kind <> 'deck_in'
           OR (robot_reachable = false AND point_role = 'internal'));
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

COMMENT ON CONSTRAINT ck_device_point_deck_in_unreachable ON device_point IS
  'deck_in=台面内工位：机械臂不可达、internal 角色（与 272 个 internal 点位同口径；评审 F3）';
