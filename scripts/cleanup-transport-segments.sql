-- =============================================================================
-- transport_segment 保留期清理（P2-10）
--
-- transport_segment 是搬运的可观测记录（甘特图/追溯），没有软删列、会持续累积。
-- 策略：保留最近 N 天（默认 30 天），更早的直接物理删除。
--
-- 用法（psql 变量传保留天数，缺省 30）：
--   psql -h <host> -U <user> -d labdb \
--        -v retention_days=30 \
--        -v ON_ERROR_STOP=1 -f scripts/cleanup-transport-segments.sql
--
-- 幂等：可重复执行；只删 created_at 早于保留窗口的历史段，
--       不影响任何 run / run_node / position_state（无外键指向 transport_segment）。
-- =============================================================================

\if :{?retention_days}
\else
  \set retention_days 30
\endif

SELECT '清理前 transport_segment 总数 = ' || count(*) AS notice
  FROM transport_segment;

DELETE FROM transport_segment
 WHERE created_at < now() - make_interval(days => :retention_days);

SELECT '清理后 transport_segment 总数 = ' || count(*) AS notice
  FROM transport_segment;
