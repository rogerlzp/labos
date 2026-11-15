-- P2 阶段 B · L2「一板一位置」数据库硬约束（2026-11-15）
-- 可重复执行：先查重复板号（有则失败，不能盲目建唯一索引），再建唯一索引。
-- 原 ix_pos_plate 是普通部分索引，只能加速查询，不能阻止同一块板写进两个位置。

DO $$
DECLARE
    dup_count BIGINT;
BEGIN
    SELECT count(*) INTO dup_count FROM (
        SELECT plate_id
          FROM position_state
         WHERE plate_id IS NOT NULL
         GROUP BY tenant_id, plate_id
        HAVING count(*) > 1
    ) d;
    IF dup_count > 0 THEN
        RAISE EXCEPTION
            'position_state 存在 % 个重复板号，禁止建唯一索引；请先人工处置（SELECT plate_id, count(*) … GROUP BY plate_id HAVING count(*)>1）',
            dup_count;
    END IF;
END
$$;

-- 一个位置最多一块板由主键 (tenant_id, resource_id) 保证；
-- 一块板最多一个位置由本唯一索引保证。
CREATE UNIQUE INDEX IF NOT EXISTS uk_pos_plate
    ON position_state (tenant_id, plate_id)
    WHERE plate_id IS NOT NULL;

COMMENT ON INDEX uk_pos_plate IS
  'P2 L2：一块板同一时间只有一个有效位置（板为空的位置不参与）。';
