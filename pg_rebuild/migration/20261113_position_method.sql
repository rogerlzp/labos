-- P0-2 · 点位×方法×作用域（position_method）
-- 承载「同一点位在不同流程有不同用途/方法」；作用域解析 experiment > workflow > global。
-- 不软删：绑定关系删了就是删了；要"停用"用 enabled 列。
BEGIN;

CREATE TABLE IF NOT EXISTS position_method (
  id            bigserial PRIMARY KEY,
  tenant_id     bigint NOT NULL,
  position_id   bigint NOT NULL REFERENCES device_point(id) ON DELETE CASCADE,
  method_id     bigint NOT NULL REFERENCES device_method(id) ON DELETE CASCADE,
  scope_kind    text   NOT NULL DEFAULT 'global',
  scope_id      bigint,
  purpose       text,
  allocation    text   NOT NULL DEFAULT 'explicit',
  priority      integer NOT NULL DEFAULT 100,
  enabled       boolean NOT NULL DEFAULT true,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_position_method_scope
    CHECK (scope_kind IN ('global','workflow','experiment')),
  CONSTRAINT ck_position_method_allocation
    CHECK (allocation IN ('explicit','auto_first_free','auto_next_free'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_position_method_global
  ON position_method (tenant_id, position_id, method_id)
  WHERE scope_kind = 'global';
CREATE UNIQUE INDEX IF NOT EXISTS uk_position_method_scoped
  ON position_method (tenant_id, position_id, method_id, scope_kind, scope_id)
  WHERE scope_kind <> 'global';
CREATE INDEX IF NOT EXISTS idx_position_method_scope ON position_method (tenant_id, scope_kind, scope_id);

COMMENT ON TABLE position_method IS '点位×方法×作用域：流程编排的一等绑定对象，scope=experiment/workflow/global';

COMMIT;
