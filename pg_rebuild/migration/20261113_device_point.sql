-- =============================================================================
-- 点位初始化配置层与物料身份链路（2026-11-13）
-- 设计：docs/architecture/点位初始化与物料身份-设计_20261113.md
--
-- 幂等：全部 CREATE IF NOT EXISTS / ADD COLUMN IF NOT EXISTS，可反复执行。
-- 只新增表与列，不删改既有数据；老系统迁移的 2563 个点位不受影响。
--   P0 · device_point 配置层 + device_point_template 补列 + position_state 内容列
--   P2 · run_position_snapshot（实验前清单快照）
--   P3 · plate_barcode_alias + plate.identity_status
-- =============================================================================

-- ---------------------------------------------------------------- P0 配置层
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

-- 型号级模板：现表 0 行，补上角色/可达/顺序字段供初始化展开
ALTER TABLE device_point_template
  ADD COLUMN IF NOT EXISTS point_role      varchar(8)  NOT NULL DEFAULT 'both',
  ADD COLUMN IF NOT EXISTS robot_reachable boolean     NOT NULL DEFAULT true,
  ADD COLUMN IF NOT EXISTS is_entry        boolean     NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS is_exit         boolean     NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS order_in_device int         NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS point_code_suffix varchar(32);

-- 运行态补「放的是什么」：板 / 耗材 / 样品 / 试剂 / 空
ALTER TABLE position_state
  ADD COLUMN IF NOT EXISTS content_kind varchar(16),
  ADD COLUMN IF NOT EXISTS content_id   bigint;

-- ---------------------------------------------------------------- P2 快照
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

COMMENT ON TABLE run_position_snapshot IS
  '实验开始前的点位与物料清单快照，append-only、可回放';

CREATE INDEX IF NOT EXISTS ix_run_position_snapshot_run
  ON run_position_snapshot (tenant_id, run_id, taken_at DESC);

-- ---------------------------------------------------------------- P3 身份层
-- 临时板号与真 barcode 都只是 alias，指向同一个不可变 plate_uid（= plate.id）。
-- 扫码后只新增 canonical alias，绝不改写历史证据表里的字符串。
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

COMMENT ON TABLE plate_barcode_alias IS
  '板身份别名表：TMP- 临时号与真 barcode 指向同一 plate_uid；历史证据字符串不改写';

ALTER TABLE plate
  ADD COLUMN IF NOT EXISTS identity_status varchar(16) NOT NULL DEFAULT 'CONFIRMED';

COMMENT ON COLUMN plate.identity_status IS
  'PROVISIONAL=仅临时板号未扫码；CONFIRMED=已读到真 barcode（历史扫码结果不改字符串）';
