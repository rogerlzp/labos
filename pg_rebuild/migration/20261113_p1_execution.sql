-- P1 · 执行层 DDL：实验模板 / 物料清单 / 物料角色 / 搬运段 / 既有表改造
-- 全部幂等（IF NOT EXISTS / ADD COLUMN IF NOT EXISTS / DO $$ 包裹 ADD CONSTRAINT）。
BEGIN;

-- ============================================================ 实验模板（P1-1）
CREATE TABLE IF NOT EXISTS experiment_template (
  id            bigserial PRIMARY KEY,
  tenant_id     bigint NOT NULL,
  template_no   text   NOT NULL,
  name          text   NOT NULL,
  island_id     bigint NOT NULL,
  project_id    bigint,
  workflow_ids  bigint[] NOT NULL DEFAULT '{}',
  spec          jsonb  NOT NULL DEFAULT '{}'::jsonb,
  point_whitelist jsonb NOT NULL DEFAULT '{}'::jsonb,
  status        text   NOT NULL DEFAULT 'draft',
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  created_by    bigint,
  updated_by    bigint,
  deleted_at    timestamptz,
  CONSTRAINT ck_experiment_template_status
    CHECK (status IN ('draft','published','archived'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_experiment_template_no
  ON experiment_template (tenant_id, template_no) WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS experiment_template_process (
  id            bigserial PRIMARY KEY,
  tenant_id     bigint NOT NULL,
  template_id   bigint NOT NULL REFERENCES experiment_template(id) ON DELETE CASCADE,
  seq           integer NOT NULL,
  workflow_id   bigint NOT NULL,
  loop_count    text,
  params        jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uk_experiment_template_process UNIQUE (template_id, seq)
);

-- ============================================================ 物料清单（P1-2）
CREATE TABLE IF NOT EXISTS material_role (
  id            bigserial PRIMARY KEY,
  tenant_id     bigint NOT NULL,
  role_code     text   NOT NULL,
  display_name  text   NOT NULL,
  physical_type text   NOT NULL DEFAULT 'plate',
  plate_type    text,
  max_height_mm numeric,
  lid_role_code text,
  barcode_regex text,
  auto_barcode_prefix text,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_material_role_physical
    CHECK (physical_type IN ('plate','lid','rack','reservoir','tip_box')),
  CONSTRAINT uk_material_role_code UNIQUE (tenant_id, role_code)
);

CREATE TABLE IF NOT EXISTS manifest_item (
  id            bigserial PRIMARY KEY,
  tenant_id     bigint NOT NULL,
  template_id   bigint NOT NULL REFERENCES experiment_template(id) ON DELETE CASCADE,
  seq           integer NOT NULL DEFAULT 1,
  role_code     text   NOT NULL,
  material_kind text   NOT NULL,
  material_ref  bigint,
  quantity      numeric NOT NULL DEFAULT 1,
  required      boolean NOT NULL DEFAULT true,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_manifest_item_kind
    CHECK (material_kind IN ('sample','reagent','consumable','plate','lid')),
  CONSTRAINT ck_manifest_item_qty CHECK (quantity > 0)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_manifest_item_role
  ON manifest_item (template_id, role_code);

CREATE TABLE IF NOT EXISTS manifest_placement (
  id            bigserial PRIMARY KEY,
  tenant_id     bigint NOT NULL,
  run_id        bigint NOT NULL REFERENCES experiment_run(id) ON DELETE CASCADE,
  manifest_item_id bigint NOT NULL REFERENCES manifest_item(id) ON DELETE CASCADE,
  position_id   bigint REFERENCES device_point(id),
  status        text   NOT NULL DEFAULT 'unassigned',
  reserve_same_nest boolean NOT NULL DEFAULT false,
  verified_by   bigint,
  verified_at   timestamptz,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_manifest_placement_status
    CHECK (status IN ('unassigned','assigned','verified','loaded','consumed','unloaded','absent')),
  CONSTRAINT uk_manifest_placement UNIQUE (run_id, manifest_item_id)
);
CREATE INDEX IF NOT EXISTS idx_manifest_placement_position ON manifest_placement (position_id);

-- ============================================================ 搬运段（P1 隐式 Transport）
CREATE TABLE IF NOT EXISTS transport_segment (
  id            bigserial PRIMARY KEY,
  tenant_id     bigint NOT NULL,
  run_id        bigint NOT NULL REFERENCES run(id) ON DELETE CASCADE,
  run_node_id   bigint REFERENCES run_node(id) ON DELETE SET NULL,
  seq           integer NOT NULL,
  robot_resource_id bigint REFERENCES resource_node(id),
  from_position_id  bigint REFERENCES device_point(id),
  to_position_id    bigint REFERENCES device_point(id),
  carrier       text,
  status        text   NOT NULL DEFAULT 'pending',
  started_at    timestamptz,
  finished_at   timestamptz,
  error         text,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_transport_segment_status
    CHECK (status IN ('pending','running','done','failed','skipped')),
  CONSTRAINT ck_transport_segment_from_to CHECK (from_position_id <> to_position_id)
);
CREATE INDEX IF NOT EXISTS idx_transport_segment_run ON transport_segment (run_id, seq);
CREATE INDEX IF NOT EXISTS idx_transport_segment_robot ON transport_segment (robot_resource_id, status);

-- ============================================================ 既有表改造（P1-6/7）
-- device_point：加 point_kind（物理位类型）+ 工位槽 + 主坑位 + 实例 id
ALTER TABLE device_point ADD COLUMN IF NOT EXISTS point_kind text NOT NULL DEFAULT 'nest';
ALTER TABLE device_point ADD COLUMN IF NOT EXISTS workstation_slot integer;
ALTER TABLE device_point ADD COLUMN IF NOT EXISTS is_primary boolean NOT NULL DEFAULT false;
ALTER TABLE device_point ADD COLUMN IF NOT EXISTS device_instance_id bigint;
DO $$ BEGIN
  ALTER TABLE device_point ADD CONSTRAINT ck_device_point_kind
    CHECK (point_kind IN ('nest','deck_in','deck_out','waste'));
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- device_method：补实例维度（实例专有方法 / 点位级方法）
ALTER TABLE device_method ADD COLUMN IF NOT EXISTS device_instance_id bigint;
ALTER TABLE device_method ADD COLUMN IF NOT EXISTS scope text NOT NULL DEFAULT 'model';
ALTER TABLE device_method ADD COLUMN IF NOT EXISTS position_id bigint;
DO $$ BEGIN
  ALTER TABLE device_method ADD CONSTRAINT ck_device_method_scope
    CHECK (scope IN ('model','instance'));
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- recovery_policy：权重匹配（对齐 外部厂商B 错误库）
ALTER TABLE recovery_policy ADD COLUMN IF NOT EXISTS match_type text NOT NULL DEFAULT 'exact';
ALTER TABLE recovery_policy ADD COLUMN IF NOT EXISTS rule_weight integer NOT NULL DEFAULT 50;
ALTER TABLE recovery_policy ADD COLUMN IF NOT EXISTS device_filter bigint;
ALTER TABLE recovery_policy ADD COLUMN IF NOT EXISTS retry_delay_sec integer NOT NULL DEFAULT 0;
DO $$ BEGIN
  ALTER TABLE recovery_policy ADD CONSTRAINT ck_recovery_policy_match
    CHECK (match_type IN ('exact','contains','starts_with'));
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- 容器生命周期二分（inventory 永久库存 / process 本次实验专属）
ALTER TABLE plate       ADD COLUMN IF NOT EXISTS lifecycle text NOT NULL DEFAULT 'inventory';
ALTER TABLE consumable ADD COLUMN IF NOT EXISTS lifecycle text NOT NULL DEFAULT 'inventory';
ALTER TABLE reagent    ADD COLUMN IF NOT EXISTS lifecycle text NOT NULL DEFAULT 'inventory';
ALTER TABLE plate       ADD COLUMN IF NOT EXISTS role_code text;
ALTER TABLE consumable ADD COLUMN IF NOT EXISTS role_code text;
ALTER TABLE reagent    ADD COLUMN IF NOT EXISTS role_code text;
DO $$ BEGIN
  ALTER TABLE plate ADD CONSTRAINT ck_plate_lifecycle CHECK (lifecycle IN ('inventory','process'));
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN
  ALTER TABLE consumable ADD CONSTRAINT ck_consumable_lifecycle CHECK (lifecycle IN ('inventory','process'));
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN
  ALTER TABLE reagent ADD CONSTRAINT ck_reagent_lifecycle CHECK (lifecycle IN ('inventory','process'));
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- experiment：拆模板侧（template_id 非空 = 新实验，派生自模板）
ALTER TABLE experiment ADD COLUMN IF NOT EXISTS template_id bigint;
ALTER TABLE experiment ADD COLUMN IF NOT EXISTS spec jsonb NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE experiment ADD COLUMN IF NOT EXISTS manifest_id bigint;

COMMIT;
