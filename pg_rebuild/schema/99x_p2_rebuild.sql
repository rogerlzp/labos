-- =============================================================================
-- P2 重构（分支 `系统重构1`）· 新增表 + 既有表改造（DDL，纯结构）
-- 与 pg_rebuild/migration/20261113_*.sql 同源。
-- 只含 DDL；seed（工位/角色）与数据修正（device_positions_fix 等）在迁移脚本里。
-- =============================================================================

-- resource_kind 加 'workstation'（PG 12+；ADD VALUE 不能在事务块内「使用」新值）
DO $$ BEGIN
  ALTER TYPE resource_kind ADD VALUE IF NOT EXISTS 'workstation';
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- ---------------------------------------------------------------- 点位配置层
BEGIN;

CREATE TABLE IF NOT EXISTS device_point (
  id                 bigserial PRIMARY KEY,
  tenant_id          bigint NOT NULL,
  device_resource_id bigint NOT NULL REFERENCES resource_node(id),
  point_resource_id  bigint NOT NULL REFERENCES resource_node(id),
  point_role         varchar(8)  NOT NULL DEFAULT 'both',
  robot_reachable    boolean     NOT NULL DEFAULT true,
  is_entry           boolean     NOT NULL DEFAULT false,
  is_exit            boolean     NOT NULL DEFAULT false,
  approach_axis      varchar(16) NOT NULL DEFAULT 'front',
  access_id          bigint,
  port_id            bigint,
  order_in_device    int         NOT NULL DEFAULT 0,
  max_stack          smallint    NOT NULL DEFAULT 1,
  requires_door      boolean     NOT NULL DEFAULT false,
  note               text,
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uk_device_point UNIQUE (tenant_id, point_resource_id),
  CONSTRAINT ck_device_point_role CHECK (point_role IN ('entry','exit','both','internal')),
  CONSTRAINT ck_device_point_flags CHECK (
      (point_role <> 'internal' AND (is_entry OR is_exit))
      OR (point_role = 'internal' AND NOT is_entry AND NOT is_exit))
);

COMMENT ON TABLE device_point IS
  '设备点位配置层：中控持有哪个位置归哪台设备、机械臂能否直达、进口/出口；机械臂只提供坐标与点位 code';
COMMENT ON COLUMN device_point.point_role IS
  'entry=进口 exit=出口 both=进出共用 internal=设备内部位（机械臂不可达）';

CREATE INDEX IF NOT EXISTS ix_device_point_device
  ON device_point (tenant_id, device_resource_id, order_in_device);

-- 型号级模板：补角色/可达/顺序字段
ALTER TABLE device_point_template
  ADD COLUMN IF NOT EXISTS point_role      varchar(8)  NOT NULL DEFAULT 'both',
  ADD COLUMN IF NOT EXISTS robot_reachable boolean     NOT NULL DEFAULT true,
  ADD COLUMN IF NOT EXISTS is_entry        boolean     NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS is_exit         boolean     NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS order_in_device int         NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS point_code_suffix varchar(32);

-- 运行态补「放的是什么」
ALTER TABLE position_state
  ADD COLUMN IF NOT EXISTS content_kind varchar(16),
  ADD COLUMN IF NOT EXISTS content_id   bigint;

CREATE TABLE IF NOT EXISTS run_position_snapshot (
  id                 bigserial PRIMARY KEY,
  tenant_id          bigint NOT NULL,
  run_id             bigint NOT NULL,
  taken_at           timestamptz NOT NULL DEFAULT now(),
  resource_id        bigint NOT NULL,
  point_code         varchar(64) NOT NULL,
  device_resource_id bigint,
  point_role         varchar(8),
  robot_reachable    boolean,
  occupied           boolean NOT NULL,
  plate_barcode      varchar(64),
  plate_uid          bigint,
  content_kind       varchar(16),
  content_id         bigint,
  reserved_by_run    bigint,
  payload            jsonb NOT NULL DEFAULT '{}'::jsonb,
  CONSTRAINT uk_run_position_snapshot UNIQUE (tenant_id, run_id, resource_id)
);
CREATE INDEX IF NOT EXISTS ix_run_position_snapshot_run
  ON run_position_snapshot (tenant_id, run_id, taken_at DESC);

CREATE TABLE IF NOT EXISTS plate_barcode_alias (
  id            bigserial PRIMARY KEY,
  tenant_id     bigint NOT NULL,
  plate_uid     bigint NOT NULL,
  alias         varchar(64) NOT NULL,
  alias_kind    varchar(12) NOT NULL,
  is_canonical  boolean NOT NULL DEFAULT false,
  first_seen_at timestamptz NOT NULL DEFAULT now(),
  seen_run_id   bigint,
  note          text,
  CONSTRAINT uk_plate_alias UNIQUE (tenant_id, alias),
  CONSTRAINT ck_plate_alias_kind CHECK (alias_kind IN ('TEMP','SCANNED','LEGACY'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_plate_alias_canonical
  ON plate_barcode_alias (tenant_id, plate_uid) WHERE is_canonical;

ALTER TABLE plate ADD COLUMN IF NOT EXISTS identity_status varchar(16) NOT NULL DEFAULT 'CONFIRMED';

-- 点位属性扩展（v2）
ALTER TABLE device_point
  ADD COLUMN IF NOT EXISTS grid_row smallint,
  ADD COLUMN IF NOT EXISTS grid_col smallint,
  ADD COLUMN IF NOT EXISTS max_height_mm numeric,
  ADD COLUMN IF NOT EXISTS plate_types varchar(32)[],
  ADD COLUMN IF NOT EXISTS position_label varchar(64),
  ADD COLUMN IF NOT EXISTS is_standalone boolean NOT NULL DEFAULT false;

COMMIT;

-- ---------------------------------------------------------------- 节点执行阶段
BEGIN;
ALTER TABLE run_node ADD COLUMN IF NOT EXISTS execution_phase varchar(32);
ALTER TABLE run_node ADD COLUMN IF NOT EXISTS phase_since timestamptz;
ALTER TABLE run_node ADD COLUMN IF NOT EXISTS blocked_by_run_id bigint;
ALTER TABLE run_node ADD COLUMN IF NOT EXISTS blocked_reason text;
ALTER TABLE run_node ADD COLUMN IF NOT EXISTS wait_started_at timestamptz;
ALTER TABLE run_node ADD COLUMN IF NOT EXISTS lock_acquired_at timestamptz;
ALTER TABLE run_node ADD COLUMN IF NOT EXISTS dispatch_started_at timestamptz;
ALTER TABLE run_node ADD COLUMN IF NOT EXISTS response_at timestamptz;
ALTER TABLE run_node ADD COLUMN IF NOT EXISTS wait_resource_ms bigint NOT NULL DEFAULT 0;
ALTER TABLE run_node ADD COLUMN IF NOT EXISTS device_execution_ms bigint NOT NULL DEFAULT 0;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint
                 WHERE conrelid = 'run_node'::regclass
                   AND conname = 'ck_run_node_execution_phase') THEN
    ALTER TABLE run_node ADD CONSTRAINT ck_run_node_execution_phase
      CHECK (execution_phase IS NULL OR execution_phase IN
             ('WAITING_RESOURCE', 'DISPATCHING', 'FINALIZING'));
  END IF;
END $$;
CREATE INDEX IF NOT EXISTS ix_run_node_execution_phase
  ON run_node (tenant_id, execution_phase) WHERE execution_phase IS NOT NULL;
COMMIT;

-- ---------------------------------------------------------------- P0-1 设备池
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

-- ---------------------------------------------------------------- P0-2 点位×方法×作用域
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

-- ---------------------------------------------------------------- P0-3 流程节点真绑定
BEGIN;

CREATE TABLE IF NOT EXISTS node_binding (
  id            bigserial PRIMARY KEY,
  tenant_id     bigint NOT NULL,
  node_id       bigint NOT NULL REFERENCES workflow_node(id) ON DELETE CASCADE,
  seq           integer NOT NULL DEFAULT 1,
  position_id   bigint REFERENCES device_point(id),
  device_instance_id bigint REFERENCES device_instance(id),
  pool_id       bigint REFERENCES device_pool(id),
  method_id     bigint REFERENCES device_method(id),
  material_role text,
  loading_pattern text NOT NULL DEFAULT 'load_single',
  leave_at_station boolean NOT NULL DEFAULT false,
  barcode_policy text NOT NULL DEFAULT 'none',
  params        jsonb,
  enabled       boolean NOT NULL DEFAULT true,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_node_binding_loading
    CHECK (loading_pattern IN ('do_not_load','load_single','load_selected','load_all_remaining')),
  CONSTRAINT ck_node_binding_barcode
    CHECK (barcode_policy IN ('none','read','update','validate_in','validate_out','validate_both')),
  CONSTRAINT ck_node_binding_target
    CHECK (num_nonnulls(position_id, device_instance_id, pool_id) >= 1)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_node_binding_seq ON node_binding (node_id, seq);
CREATE INDEX IF NOT EXISTS idx_node_binding_node ON node_binding (node_id);
CREATE INDEX IF NOT EXISTS idx_node_binding_position ON node_binding (position_id);

ALTER TABLE workflow_node ADD COLUMN IF NOT EXISTS binding_mode text NOT NULL DEFAULT 'legacy';
DO $$ BEGIN
  ALTER TABLE workflow_node ADD CONSTRAINT ck_workflow_node_binding_mode
    CHECK (binding_mode IN ('legacy','point','mixed'));
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

COMMENT ON TABLE node_binding IS '流程节点真绑定：点位+方法+物料角色，与 workflow 同生命周期，不塞 BPMN XML';
COMMENT ON COLUMN workflow_node.binding_mode IS 'legacy=只 XML 设备绑定；point=有 node_binding；mixed=混合';

COMMIT;

-- ---------------------------------------------------------------- P1 执行层
BEGIN;

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

-- 既有表改造
ALTER TABLE device_point ADD COLUMN IF NOT EXISTS point_kind text NOT NULL DEFAULT 'nest';
ALTER TABLE device_point ADD COLUMN IF NOT EXISTS workstation_slot integer;
ALTER TABLE device_point ADD COLUMN IF NOT EXISTS is_primary boolean NOT NULL DEFAULT false;
ALTER TABLE device_point ADD COLUMN IF NOT EXISTS device_instance_id bigint;
DO $$ BEGIN
  ALTER TABLE device_point ADD CONSTRAINT ck_device_point_kind
    CHECK (point_kind IN ('nest','deck_in','deck_out','waste'));
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN
  ALTER TABLE device_point ADD CONSTRAINT ck_device_point_deck_in_unreachable
    CHECK (point_kind <> 'deck_in'
           OR (robot_reachable = false AND point_role = 'internal'));
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

ALTER TABLE device_method ADD COLUMN IF NOT EXISTS device_instance_id bigint;
ALTER TABLE device_method ADD COLUMN IF NOT EXISTS scope text NOT NULL DEFAULT 'model';
ALTER TABLE device_method ADD COLUMN IF NOT EXISTS position_id bigint;
DO $$ BEGIN
  ALTER TABLE device_method ADD CONSTRAINT ck_device_method_scope
    CHECK (scope IN ('model','instance'));
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

ALTER TABLE recovery_policy ADD COLUMN IF NOT EXISTS match_type text NOT NULL DEFAULT 'exact';
ALTER TABLE recovery_policy ADD COLUMN IF NOT EXISTS rule_weight integer NOT NULL DEFAULT 50;
ALTER TABLE recovery_policy ADD COLUMN IF NOT EXISTS device_filter bigint;
ALTER TABLE recovery_policy ADD COLUMN IF NOT EXISTS retry_delay_sec integer NOT NULL DEFAULT 0;
DO $$ BEGIN
  ALTER TABLE recovery_policy ADD CONSTRAINT ck_recovery_policy_match
    CHECK (match_type IN ('exact','contains','starts_with'));
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

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

ALTER TABLE experiment ADD COLUMN IF NOT EXISTS template_id bigint;
ALTER TABLE experiment ADD COLUMN IF NOT EXISTS spec jsonb NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE experiment ADD COLUMN IF NOT EXISTS manifest_id bigint;

COMMIT;
