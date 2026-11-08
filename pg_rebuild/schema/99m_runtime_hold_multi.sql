-- ============================================================================
-- P-03 收尾：runtime_hold 多 Hold 并存（2026-11-09）
-- 旧唯一索引只允许 (tenant,scope,scope_id) 一条生效；设备故障 + 人工暂停等
-- 不同原因同时存在时会被吞掉。改为同一 scope 每个 kind 各一条生效。
-- ============================================================================

DROP INDEX IF EXISTS uk_runtime_hold_active;

CREATE UNIQUE INDEX IF NOT EXISTS uk_runtime_hold_active
    ON runtime_hold (tenant_id, scope, COALESCE(scope_id, 0), kind)
    WHERE released_at IS NULL;
