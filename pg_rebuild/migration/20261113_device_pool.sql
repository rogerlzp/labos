-- P0-1 · 设备池（device_pool + device_pool_member）
-- 语义对齐 外部厂商A/外部厂商B：operation 池成员必须同型号；storage 池可异构。
-- 幂等：IF NOT EXISTS / ON CONFLICT DO NOTHING，可反复执行。
BEGIN;

CREATE TABLE IF NOT EXISTS device_pool (
  id            bigserial PRIMARY KEY,
  tenant_id     bigint NOT NULL,
  island_id     bigint NOT NULL,
  code          text   NOT NULL,
  name          text   NOT NULL,
  pool_kind     text   NOT NULL DEFAULT 'operation',
  strategy      text   NOT NULL DEFAULT 'first_available',
  skip_on_error   boolean NOT NULL DEFAULT true,
  skip_on_offline boolean NOT NULL DEFAULT true,
  allow_iteration_on_error boolean NOT NULL DEFAULT true,
  min_nest_count   integer NOT NULL DEFAULT 0,
  enabled       boolean NOT NULL DEFAULT true,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  deleted_at    timestamptz,
  CONSTRAINT ck_device_pool_kind
    CHECK (pool_kind IN ('operation','storage')),
  CONSTRAINT ck_device_pool_strategy
    CHECK (strategy IN ('first_available','least_recently_used','round_robin','priority_commit'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_device_pool_tenant_code
  ON device_pool (tenant_id, code) WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS device_pool_member (
  id            bigserial PRIMARY KEY,
  tenant_id     bigint NOT NULL,
  pool_id       bigint NOT NULL REFERENCES device_pool(id) ON DELETE CASCADE,
  device_instance_id bigint NOT NULL REFERENCES device_instance(id) ON DELETE CASCADE,
  sub_group     text,
  priority      integer NOT NULL DEFAULT 100,
  last_used_at  timestamptz,
  use_count     integer NOT NULL DEFAULT 0,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uk_device_pool_member
    UNIQUE (pool_id, device_instance_id)
);
CREATE INDEX IF NOT EXISTS idx_device_pool_member_pool ON device_pool_member (pool_id, priority);

COMMENT ON TABLE device_pool IS '设备池：同型号 operation 池 / 异构 storage 池，策略对齐 外部厂商A';
COMMENT ON TABLE device_pool_member IS '设备池成员';

COMMIT;
