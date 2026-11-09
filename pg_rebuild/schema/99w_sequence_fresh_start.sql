-- ============================================================================
-- ID 号段治理：所有 identity 序列统一抬到 1,000,000 起
--
-- 背景（2026-11-09 现场指示「为了防止有 ID 重复，可以把 ID 从某个新值开始」）：
--   库里现在混着好几套号段：
--     · resource_node 既有 100027/100033 这类岛 id，也有 504821/504831 这类；
--     · workflow.id ~3784、run.id ~209170、run_node.id ~131723、sample ~123865；
--     · 旧 workflow.attr.island_id 甚至用 27/31/33 这种两位数。
--   本次整改已经踩过一次「两套编号对不上」的坑（991 条悬空岛标签）。
--   全库业务表当前 max(id) = 504831；把**所有** identity 序列抬到 1,000,000，
--   此后新建的任何行 id 都 ≥ 1,000,000，与历史任何号段都不重叠，
--   一眼可分「新数据 / 历史数据」，也不会再和 1000xx 这类旧岛编号撞上。
--
-- 为什么用条件式而不是直接 RESTART：
--   直接 `RESTART WITH 1000000` 重复执行会把已经发到 1000123 的序列**退回**，
--   下一次插入立刻 duplicate key。所以只在 last_value < 1,000,000 时才抬；
--   已经在新号段里的序列保持不动（可重复执行、收敛）。
--   起点同时保证 > 该表 max(id)，避免抬完反而低于历史最大行。
--
-- 幂等；不改动任何已有行的 id，不改表结构。
-- ============================================================================

DO $$
DECLARE
    r          record;
    v_last     bigint;
    v_max      bigint;
    v_start    bigint;
    v_changed  int := 0;
BEGIN
    FOR r IN
        SELECT t.relname AS tbl,
               a.attname AS col,
               pg_get_serial_sequence(quote_ident(t.relname), a.attname) AS seq
          FROM pg_class t
          JOIN pg_attribute a ON a.attrelid = t.oid AND a.attidentity <> ''
         WHERE t.relnamespace = 'public'::regnamespace
           AND t.relkind = 'r'
           AND pg_get_serial_sequence(quote_ident(t.relname), a.attname) IS NOT NULL
         ORDER BY 1
    LOOP
        EXECUTE 'SELECT last_value FROM ' || r.seq INTO v_last;
        EXECUTE format('SELECT coalesce(max(%I), 0) FROM %I', r.col, r.tbl) INTO v_max;
        IF v_last < 1000000 THEN
            v_start := GREATEST(1000000::bigint, v_max + 1);
            EXECUTE format('ALTER SEQUENCE %s RESTART WITH %s', r.seq, v_start);
            v_changed := v_changed + 1;
            RAISE NOTICE '序列 % 抬到 %（原 last_value=%, 表 max(id)=%）',
                r.seq, v_start, v_last, v_max;
        END IF;
    END LOOP;
    RAISE NOTICE '共抬升 % 个序列；已在 1,000,000 之后的保持不变', v_changed;
END $$;
