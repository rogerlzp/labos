-- =============================================================================
-- LabOS · 新一代 PostgreSQL Schema（全量单文件，自动生成请勿手改）
-- 目标版本: PostgreSQL 17（兼容 15+）
--
-- 执行:
--   psql -h <HOST> -U <USER> -d labdb -v ON_ERROR_STOP=1 -f 90_all_in_one.sql
--
-- 结构: 76 张表 + 5 个视图（纯 DDL，不含任何数据）
--   10 基础与平台  : 租户/用户/角色/权限/字典/配置          (8 表)
--   20 内核层      : 资源与能力/资源日历/驱动注册/工艺编排/
--                    执行内核/事件流/调度/错误恢复          (23 表)
--   30 业务层      : 样本/板/孔位/订单/耗材试剂批次/
--                    项目实验/设备/文件                    (18 表)
--   40 运维层      : 通知/审计/兼容视图/健康自检            (4 表 + 3 视图)
--   92/93/94/96/97/98/98b : 增量分片：实验室档案 / M1 并行基础设施 /
--                    M4 暂停补列 / schema 补齐 / M9.5 实验流程偏序 /
--                    M9.5 作用域扣留 / M9.5 分批执行 / 有人值守窗口 (26 表 + 2 视图)
--   99a/99b/99c/99d: 方法超时字典 / 可中断方法 / L-02 控制状态与生命周期分离
--                    (control_state + status 扩容，见 ADR-PAUSE-008) / V1-5 暂停请求
--                    幂等键 pause_request（见 ADR-PAUSE-009）
--
-- 幂等: 全部 DDL 使用 IF NOT EXISTS，可重复执行
-- 数据: **本文件不含任何 INSERT**。初始数据在 95_seed_data.sql，按需执行。
-- 依赖: plpgsql（内置）；btree_gist 可选（缺失时资源冲突约束降级并告警）
--
-- 已验证: PostgreSQL 17.10 (Ubuntu pgdg) 2026-10-29
--         81 表对象 / 2 分区父表 / 3 视图 / 205 索引 / 9 枚举
--         资源重叠约束生效（重叠预约被数据库拒绝）
-- =============================================================================

-- =============================================================================
-- LabOS · 新一代 schema  |  10 基础与平台
-- PostgreSQL 17
--
--   1. 统一审计字段：created_at/created_by/updated_at/updated_by/deleted_at
--      last_update+updated_by_id、is_valid），这里是最大的一处债务
--      多张 EAV 表）
-- =============================================================================

-- 扩展按需创建；失败只告警不中断（缺 btree_gist 时资源冲突约束会降级）
DO $$
BEGIN
  CREATE EXTENSION IF NOT EXISTS pgcrypto;
EXCEPTION WHEN others THEN
  RAISE WARNING 'pgcrypto 不可用（%）：PG13+ 已内置 gen_random_uuid，可忽略', SQLERRM;
END $$;

DO $$
BEGIN
  CREATE EXTENSION IF NOT EXISTS btree_gist;
EXCEPTION WHEN others THEN
  RAISE WARNING 'btree_gist 不可用（%）：resource_booking 的重叠约束将被跳过。'
                '请安装 postgresql-contrib 或由 DBA 执行 CREATE EXTENSION btree_gist;', SQLERRM;
END $$;

-- ---------------------------------------------------------------- 枚举类型
-- 用原生 enum 而非 smallint 魔法值：类型安全、可读、约束由库保证

DO $$ BEGIN
  CREATE TYPE run_status AS ENUM (
    'draft','pending','planned','queued','running','paused','completed','failed','aborted','skipped');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  CREATE TYPE node_status AS ENUM ('pending','ready','running','completed','failed','skipped','aborted');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  CREATE TYPE resource_kind AS ENUM (
    'island','station','device','robot','storage','transfer_point','incubator','operator','virtual');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  CREATE TYPE resource_status AS ENUM ('available','disabled','maintenance','fault','offline');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  CREATE TYPE booking_status AS ENUM ('planned','active','released','cancelled','expired');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  CREATE TYPE driver_protocol AS ENUM ('grpc','http','tcp','sila2','modbus','opcua','netdll','mock');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  CREATE TYPE recovery_strategy AS ENUM ('retry','skip','reassign','abort','manual','compensate');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  CREATE TYPE error_severity AS ENUM ('info','warn','error','fatal');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  CREATE TYPE run_mode AS ENUM ('normal','simulation','attended','unattended');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- ---------------------------------------------------------------- 通用函数
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
  NEW.updated_at := now();
  RETURN NEW;
END $$ LANGUAGE plpgsql;

-- 给表挂 updated_at 自动维护（幂等）
CREATE OR REPLACE FUNCTION attach_updated_at(tbl regclass) RETURNS void AS $$
BEGIN
  EXECUTE format('DROP TRIGGER IF EXISTS trg_%s_updated_at ON %s',
                 replace(tbl::text, 'public.', ''), tbl);
  EXECUTE format('CREATE TRIGGER trg_%s_updated_at BEFORE UPDATE ON %s
                  FOR EACH ROW EXECUTE FUNCTION set_updated_at()',
                 replace(tbl::text, 'public.', ''), tbl);
END $$ LANGUAGE plpgsql;

-- 资源树物化路径维护（parent 变更时重算子孙 path）
CREATE OR REPLACE FUNCTION resource_node_path() RETURNS trigger AS $$
DECLARE p_path text;
BEGIN
  IF NEW.parent_id IS NULL THEN
    NEW.path := '/' || NEW.code;
  ELSE
    SELECT path INTO p_path FROM resource_node WHERE id = NEW.parent_id;
    NEW.path := coalesce(p_path, '') || '/' || NEW.code;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;

-- =============================================================================
-- 平台：租户 / 用户 / 权限 / 字典 / 配置
-- ---------------------------------------------------------------------------
-- 以下 app_user / app_role / user_role 三表：**无任何代码读取**
-- （评审 20261102-05 复核确认：认证走 sys_user，grep 全仓无读方）。
--
-- 保留表结构是为了兼容可能的报表/外部系统；**新代码不要用**。
-- 账号与角色请统一走 sys_user：
--   - 账号由 AuthService.ensureSeedUser() 在应用启动时创建（PBKDF2）
--   - 角色是 sys_user.role 单列（admin / operator / viewer）
--
-- 历史教训：这三张表曾配了一套 RBAC 种子（app_role 种 admin/operator/viewer，
-- 而 user_role 装配 JOIN 找 ROLE1/ROLE2/ROLE4）——永不匹配，等于没生效，
-- 却让人以为"RBAC 配好了"。见评审记录 20261102-04 R1。
-- ---------------------------------------------------------------------------

-- =============================================================================

CREATE TABLE IF NOT EXISTS tenant (
  id          bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  code        varchar(64)  NOT NULL,
  name        varchar(128) NOT NULL,
  contact     varchar(128),
  status      smallint     NOT NULL DEFAULT 1,       -- 1 启用 0 停用
  expire_at   timestamptz,
  created_at  timestamptz  NOT NULL DEFAULT now(),
  deleted_at  timestamptz,
  CONSTRAINT uk_tenant_code UNIQUE (code)
);
COMMENT ON TABLE tenant IS '租户';

CREATE TABLE IF NOT EXISTS app_user (
  id            bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id     bigint       NOT NULL REFERENCES tenant(id),
  username      varchar(64)  NOT NULL,
  display_name  varchar(128),
  email         varchar(255),
  phone         varchar(32),
  password_hash varchar(255),
  auth_source   varchar(32)  NOT NULL DEFAULT 'local',  -- local / ldap / oidc
  avatar        varchar(255),
  status        smallint     NOT NULL DEFAULT 1,
  last_login_at timestamptz,
  created_at    timestamptz  NOT NULL DEFAULT now(),
  created_by    bigint,
  updated_at    timestamptz  NOT NULL DEFAULT now(),
  updated_by    bigint,
  deleted_at    timestamptz,
  CONSTRAINT uk_app_user UNIQUE (tenant_id, username)
);
COMMENT ON TABLE app_user IS '用户';
CREATE INDEX IF NOT EXISTS ix_app_user_tenant ON app_user (tenant_id) WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS app_role (
  id          bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id   bigint      NOT NULL REFERENCES tenant(id),
  code        varchar(64) NOT NULL,
  name        varchar(128) NOT NULL,
  description text,
  is_builtin  boolean     NOT NULL DEFAULT false,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  deleted_at  timestamptz,
  CONSTRAINT uk_role UNIQUE (tenant_id, code)
);

CREATE TABLE IF NOT EXISTS permission (
  id          bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  code        varchar(128) NOT NULL,          -- resource:read / run:execute / device:control
  name        varchar(128) NOT NULL,
  module      varchar(64),
  description text,
  deleted_at  timestamptz,
  CONSTRAINT uk_permission_code UNIQUE (code)
);
COMMENT ON TABLE permission IS '权限点';

CREATE TABLE IF NOT EXISTS user_role (
  user_id bigint NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  role_id bigint NOT NULL REFERENCES app_role(id)     ON DELETE CASCADE,
  PRIMARY KEY (user_id, role_id)
);

CREATE TABLE IF NOT EXISTS role_permission (
  role_id       bigint NOT NULL REFERENCES app_role(id) ON DELETE CASCADE,
  permission_id bigint NOT NULL REFERENCES permission(id) ON DELETE CASCADE,
  PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE IF NOT EXISTS dict_item (
  id         bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id  bigint      NOT NULL REFERENCES tenant(id),
  dict_type  varchar(64) NOT NULL,
  dict_code  varchar(64) NOT NULL,
  dict_label varchar(128) NOT NULL,
  sort_order int         NOT NULL DEFAULT 0,
  attr       jsonb       NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  deleted_at timestamptz,
  CONSTRAINT uk_dict_item UNIQUE (tenant_id, dict_type, dict_code)
);
COMMENT ON TABLE dict_item IS '字典表（统一字典项）';

CREATE TABLE IF NOT EXISTS sys_setting (
  id          bigint       GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id   bigint       NOT NULL REFERENCES tenant(id),
  scope       varchar(32)  NOT NULL DEFAULT 'global',   -- global / island / device
  scope_id    bigint,
  setting_key varchar(128) NOT NULL,                    -- 不用 key/role 等关键字，避免 Java/ORM 侧转义
  value       jsonb        NOT NULL DEFAULT '{}'::jsonb,
  description varchar(255),
  updated_at  timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT uk_sys_setting UNIQUE (tenant_id, scope, scope_id, setting_key)
);
COMMENT ON TABLE sys_setting IS '系统配置';

-- =============================================================================
-- 触发器与初始数据
-- =============================================================================
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['app_user','app_role','sys_setting'] LOOP
    PERFORM attach_updated_at(t::regclass);
  END LOOP;
END $$;

SELECT setval(pg_get_serial_sequence('tenant','id'), GREATEST((SELECT max(id) FROM tenant), 1));

-- =============================================================================
-- LabOS · 新一代 schema  |  20 内核层
--
-- =============================================================================

-- =============================================================================
-- =============================================================================

CREATE TABLE IF NOT EXISTS capability (
  id             bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id      bigint       NOT NULL REFERENCES tenant(id),
  code           varchar(64)  NOT NULL,        -- ALIQUOT / CENTRIFUGE / INCUBATE / READ_PLATE / SCAN / TRANSFER / DECAP
  name           varchar(128) NOT NULL,
  category       varchar(32)  NOT NULL,        -- liquid / thermal / optical / transport / handling / separation
  param_schema   jsonb        NOT NULL DEFAULT '{}'::jsonb,   -- JSON Schema：参数定义
  duration_model jsonb        NOT NULL DEFAULT '{}'::jsonb,   -- 时长模型 {base:30, per_well:0.5, unit:'s'}
  description    text,
  created_at     timestamptz  NOT NULL DEFAULT now(),
  updated_at     timestamptz  NOT NULL DEFAULT now(),
  deleted_at     timestamptz,
  CONSTRAINT uk_capability UNIQUE (tenant_id, code)
);
COMMENT ON TABLE capability IS
  '能力字典：工艺只声明"要什么能力"，不声明"用哪台设备"。这是解耦核心。';

CREATE TABLE IF NOT EXISTS resource_node (
  id         bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id  bigint          NOT NULL REFERENCES tenant(id),
  parent_id  bigint          REFERENCES resource_node(id) ON DELETE SET NULL,
  code       varchar(64)     NOT NULL,
  name       varchar(128)    NOT NULL,
  kind       resource_kind   NOT NULL,
  status     resource_status NOT NULL DEFAULT 'available',
  capacity   smallint        NOT NULL DEFAULT 1,     -- 并发容量：离心机 1，堆板机 4
  path       text,                                   -- 物化路径 /island01/stationA/centrifuge
  location   jsonb           NOT NULL DEFAULT '{}'::jsonb,   -- 物理坐标 {site,x,y,z,orientation}
  attr       jsonb           NOT NULL DEFAULT '{}'::jsonb,   -- 差异化属性
  sort_order int             NOT NULL DEFAULT 0,
  created_at timestamptz     NOT NULL DEFAULT now(),
  created_by bigint,
  updated_at timestamptz     NOT NULL DEFAULT now(),
  updated_by bigint,
  deleted_at timestamptz,
  CONSTRAINT uk_resource_node UNIQUE (tenant_id, code),
  CONSTRAINT ck_resource_capacity CHECK (capacity > 0)
);
COMMENT ON TABLE resource_node IS
  '统一资源树：岛/站点/设备/机械臂/存储位/转运点/培养箱/人员。';
CREATE INDEX IF NOT EXISTS ix_resource_node_kind   ON resource_node (tenant_id, kind) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS ix_resource_node_parent ON resource_node (parent_id);
CREATE INDEX IF NOT EXISTS ix_resource_node_path   ON resource_node (path text_pattern_ops);
CREATE INDEX IF NOT EXISTS ix_resource_node_attr   ON resource_node USING gin (attr jsonb_path_ops);
CREATE INDEX IF NOT EXISTS ix_resource_node_status ON resource_node (tenant_id, status);

CREATE TABLE IF NOT EXISTS resource_capability (
  resource_id         bigint NOT NULL REFERENCES resource_node(id) ON DELETE CASCADE,
  capability_id       bigint NOT NULL REFERENCES capability(id)    ON DELETE CASCADE,
  params              jsonb  NOT NULL DEFAULT '{}'::jsonb,   -- 该资源的参数默认值/限制
  throughput_per_hour numeric(10,2),
  PRIMARY KEY (resource_id, capability_id)
);
COMMENT ON TABLE resource_capability IS '资源具备的能力（调度器据此做"能力→资源"匹配）';

CREATE TABLE IF NOT EXISTS resource_booking (
  id          bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id   bigint          NOT NULL REFERENCES tenant(id),
  resource_id bigint          NOT NULL REFERENCES resource_node(id),
  period      tstzrange       NOT NULL,
  status      booking_status  NOT NULL DEFAULT 'planned',
  source_type varchar(32)     NOT NULL,     -- run_node / maintenance / calibration / manual
  source_id   bigint,
  run_node_id bigint,
  note        text,
  created_at  timestamptz     NOT NULL DEFAULT now(),
  created_by  bigint,
  updated_at  timestamptz     NOT NULL DEFAULT now(),
  deleted_at  timestamptz,
  CONSTRAINT ck_booking_period CHECK (NOT isempty(period) AND lower_inc(period) AND NOT upper_inc(period))
);
-- 资源冲突约束：同一资源的有效预约时间段不得重叠。
-- 需要 btree_gist 扩展；若不可用则降级为应用层校验（只告警，不中断建表）
DO $$
BEGIN
  -- 先判断，避免重复执行时 EXCLUDE 底层索引与约束名冲突而误报
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ex_booking_overlap') THEN
    ALTER TABLE resource_booking ADD CONSTRAINT ex_booking_overlap
      EXCLUDE USING gist (resource_id WITH =, period WITH &&)
      WHERE (status IN ('planned','active') AND deleted_at IS NULL);
  END IF;
EXCEPTION
  WHEN duplicate_object THEN NULL;
  WHEN duplicate_table   THEN NULL;
  WHEN others THEN
    RAISE WARNING '资源冲突 EXCLUDE 约束创建失败（btree_gist 不可用？）：%。资源冲突将退化为应用层校验。', SQLERRM;
END $$;
COMMENT ON TABLE resource_booking IS
  '资源预约/日历。EXCLUDE USING gist 保证同一资源时间段不重叠——资源冲突由数据库保证，不依赖应用层判断。';
CREATE INDEX IF NOT EXISTS ix_booking_resource_period ON resource_booking USING gist (resource_id, period);
CREATE INDEX IF NOT EXISTS ix_booking_run_node        ON resource_booking (run_node_id);

-- =============================================================================
-- =============================================================================

CREATE TABLE IF NOT EXISTS driver (
  id          bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id   bigint          NOT NULL REFERENCES tenant(id),
  code        varchar(64)     NOT NULL,
  name        varchar(128)    NOT NULL,
  version     varchar(32),
  protocol    driver_protocol NOT NULL,
  endpoint    varchar(255),                       -- grpc://192.0.2.5:50051
  manifest    jsonb           NOT NULL DEFAULT '{}'::jsonb,  -- 能力声明 + 参数模式 + 限流
  vendor      varchar(64),                        -- labos / thermo / tecan / third-party
  resident    boolean         NOT NULL DEFAULT false,        -- 常驻进程 or 按需拉起
  status      smallint        NOT NULL DEFAULT 1,
  created_at  timestamptz     NOT NULL DEFAULT now(),
  updated_at  timestamptz     NOT NULL DEFAULT now(),
  deleted_at  timestamptz,
  CONSTRAINT uk_driver UNIQUE (tenant_id, code, version)
);
COMMENT ON TABLE driver IS
  '驱动注册表。新增设备只需注册 manifest，不改中控代码。';
CREATE INDEX IF NOT EXISTS ix_driver_protocol ON driver (protocol);

CREATE TABLE IF NOT EXISTS driver_capability (
  driver_id     bigint NOT NULL REFERENCES driver(id)     ON DELETE CASCADE,
  capability_id bigint NOT NULL REFERENCES capability(id) ON DELETE CASCADE,
  params        jsonb  NOT NULL DEFAULT '{}'::jsonb,
  PRIMARY KEY (driver_id, capability_id)
);

CREATE TABLE IF NOT EXISTS driver_health (
  driver_id  bigint      NOT NULL REFERENCES driver(id) ON DELETE CASCADE,
  checked_at timestamptz NOT NULL DEFAULT now(),
  healthy    boolean     NOT NULL,
  latency_ms int,
  message    text,
  PRIMARY KEY (driver_id, checked_at)
);
COMMENT ON TABLE driver_health IS '驱动心跳。落表持久化，可按时间查询历史';
CREATE INDEX IF NOT EXISTS ix_driver_health_time ON driver_health (checked_at DESC);

-- =============================================================================
-- 三、工艺与编排（工艺 ↔ 设备解耦）
-- =============================================================================

CREATE TABLE IF NOT EXISTS protocol (
  id           bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id    bigint       NOT NULL REFERENCES tenant(id),
  code         varchar(64)  NOT NULL,
  name         varchar(128) NOT NULL,
  category     varchar(32),                       -- liquid / thermal / optical / transport
  version      int          NOT NULL DEFAULT 1,
  status       smallint     NOT NULL DEFAULT 1,   -- 1 正常 0 失效
  description  text,
  content      jsonb        NOT NULL DEFAULT '{}'::jsonb,   -- 步骤参数快照
  estimate_sec int          NOT NULL DEFAULT 0,
  timeout_sec  int          NOT NULL DEFAULT 0,
  created_at   timestamptz  NOT NULL DEFAULT now(),
  created_by   bigint,
  updated_at   timestamptz  NOT NULL DEFAULT now(),
  updated_by   bigint,
  deleted_at   timestamptz,
  CONSTRAINT uk_protocol UNIQUE (tenant_id, code, version)
);
COMMENT ON TABLE protocol IS
  '工艺模板。★ 设计：protocol 不绑定具体设备（无 equipment_id），设备由调度器按能力匹配。';

CREATE TABLE IF NOT EXISTS protocol_step (
  id                   bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id            bigint       NOT NULL REFERENCES tenant(id),
  protocol_id          bigint       NOT NULL REFERENCES protocol(id) ON DELETE CASCADE,
  seq                  int          NOT NULL,
  name                 varchar(128),
  capability_id        bigint       REFERENCES capability(id),        -- ★ 声明能力
  pinned_resource_id   bigint       REFERENCES resource_node(id),     -- 例外：指定专用设备
  driver_method        varchar(64),                                   -- 对应驱动方法名
  params               jsonb        NOT NULL DEFAULT '{}'::jsonb,
  estimate_sec         int          NOT NULL DEFAULT 0,
  timeout_sec          int          NOT NULL DEFAULT 0,
  retry_policy         jsonb        NOT NULL DEFAULT '{}'::jsonb,
  created_at           timestamptz  NOT NULL DEFAULT now(),
  updated_at           timestamptz  NOT NULL DEFAULT now(),
  deleted_at           timestamptz,
  CONSTRAINT uk_protocol_step UNIQUE (protocol_id, seq)
);
COMMENT ON TABLE protocol_step IS '工艺步骤：声明所需能力 + 参数，由调度器挑选具体资源';

CREATE TABLE IF NOT EXISTS workflow (
  id         bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id  bigint       NOT NULL REFERENCES tenant(id),
  code       varchar(64)  NOT NULL,
  name       varchar(128) NOT NULL,
  category   varchar(32),
  version    int          NOT NULL DEFAULT 1,
  status     smallint     NOT NULL DEFAULT 1,
  graph      jsonb        NOT NULL DEFAULT '{}'::jsonb,   -- 原始画布快照（BPMN 2.0 XML）
  attr       jsonb        NOT NULL DEFAULT '{}'::jsonb,   -- 差异化属性（SOP/LIMS 关联）
  created_at timestamptz  NOT NULL DEFAULT now(),
  created_by bigint,
  updated_at timestamptz  NOT NULL DEFAULT now(),
  updated_by bigint,
  deleted_at timestamptz,
  CONSTRAINT uk_workflow UNIQUE (tenant_id, code, version)
);
COMMENT ON TABLE workflow IS '流程定义';

CREATE TABLE IF NOT EXISTS workflow_node (
  id            bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id     bigint       NOT NULL REFERENCES tenant(id),
  workflow_id   bigint       NOT NULL REFERENCES workflow(id) ON DELETE CASCADE,
  node_key      varchar(64)  NOT NULL,          -- 画布内唯一
  name          varchar(128),
  node_type     varchar(32)  NOT NULL,          -- task / subprocess / wait / condition / parallel / join / script
  protocol_id   bigint       REFERENCES protocol(id),
  capability_id bigint       REFERENCES capability(id),
  params        jsonb        NOT NULL DEFAULT '{}'::jsonb,
  condition     jsonb        NOT NULL DEFAULT '{}'::jsonb,   -- 分支条件表达式
  estimate_sec  int          NOT NULL DEFAULT 0,
  timeout_sec   int          NOT NULL DEFAULT 0,
  pos           jsonb        NOT NULL DEFAULT '{}'::jsonb,   -- 画布坐标 {x,y}
  -- 设备绑定与图内顺序：从 XML 落到表，读侧不再每次解析图（M9 前置）
  resource_id   bigint,
  seq           int,
  created_at    timestamptz  NOT NULL DEFAULT now(),
  updated_at    timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT uk_workflow_node UNIQUE (workflow_id, node_key)
);
COMMENT ON TABLE workflow_node IS '流程节点';

CREATE TABLE IF NOT EXISTS workflow_edge (
  id          bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id   bigint      NOT NULL REFERENCES tenant(id),
  workflow_id bigint      NOT NULL REFERENCES workflow(id) ON DELETE CASCADE,
  from_key    varchar(64) NOT NULL,
  to_key      varchar(64) NOT NULL,
  edge_type   varchar(16) NOT NULL DEFAULT 'sequence',   -- sequence / condition / parallel
  condition   jsonb       NOT NULL DEFAULT '{}'::jsonb,
  CONSTRAINT uk_workflow_edge UNIQUE (workflow_id, from_key, to_key, edge_type)
);
COMMENT ON TABLE workflow_edge IS
  '流程边。显式建边后可做 SQL 级校验：环检测、孤立节点、可达性。';
CREATE INDEX IF NOT EXISTS ix_workflow_edge_from ON workflow_edge (workflow_id, from_key);

-- =============================================================================
-- 四、执行内核
-- =============================================================================

CREATE TABLE IF NOT EXISTS run (
  id             bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id      bigint      NOT NULL REFERENCES tenant(id),
  run_no         varchar(64) NOT NULL,
  run_type       varchar(32) NOT NULL,          -- experiment / experiment_sub / process / workflow / project
  parent_run_id  bigint      REFERENCES run(id) ON DELETE CASCADE,  -- 层级：子实验/子流程挂在父执行下
  workflow_id    bigint      REFERENCES workflow(id),
  workflow_ver   int,
  mode           run_mode    NOT NULL DEFAULT 'normal',   -- ★ 四模式
  priority       smallint    NOT NULL DEFAULT 5,          -- 1 最高 9 最低（急诊插队）
  status         run_status  NOT NULL DEFAULT 'pending',
  planned_start  timestamptz,
  planned_end    timestamptz,
  actual_start   timestamptz,
  actual_end     timestamptz,
  deadline       timestamptz,
  progress       numeric(5,2) NOT NULL DEFAULT 0,
  input          jsonb       NOT NULL DEFAULT '{}'::jsonb,
  output         jsonb       NOT NULL DEFAULT '{}'::jsonb,
  error_code     varchar(64),
  external_ref   varchar(64),                   -- 外部单号
  trace_id       varchar(64),
  created_at     timestamptz NOT NULL DEFAULT now(),
  created_by     bigint,
  updated_at     timestamptz NOT NULL DEFAULT now(),
  updated_by     bigint,
  deleted_at     timestamptz,
  CONSTRAINT uk_run_no UNIQUE (tenant_id, run_no),
  CONSTRAINT ck_run_progress CHECK (progress >= 0 AND progress <= 100)
);
COMMENT ON TABLE run IS
  '执行实例（统一 experiment_instance / experiment_sub_instance / wf_process_instance / wf_proj_process_instance 四层）';
CREATE INDEX IF NOT EXISTS ix_run_status   ON run (tenant_id, status) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS ix_run_planned  ON run (planned_start) WHERE status IN ('pending','planned','queued');
CREATE INDEX IF NOT EXISTS ix_run_deadline ON run (deadline)      WHERE deadline IS NOT NULL AND status NOT IN ('completed','failed','aborted');
CREATE INDEX IF NOT EXISTS ix_run_created  ON run (created_at DESC);

CREATE TABLE IF NOT EXISTS run_node (
  id            bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id     bigint       NOT NULL REFERENCES tenant(id),
  run_id        bigint       NOT NULL REFERENCES run(id) ON DELETE CASCADE,
  node_key      varchar(64)  NOT NULL,
  name          varchar(128),
  seq           int          NOT NULL DEFAULT 0,
  node_type     varchar(32),
  protocol_id   bigint       REFERENCES protocol(id),
  capability_id bigint       REFERENCES capability(id),
  resource_id   bigint       REFERENCES resource_node(id),   -- ★ 调度器分配
  driver_id     bigint       REFERENCES driver(id),
  status        node_status  NOT NULL DEFAULT 'pending',
  attempt       smallint     NOT NULL DEFAULT 0,
  planned_start timestamptz,
  planned_end   timestamptz,
  actual_start  timestamptz,
  actual_end    timestamptz,
  estimate_sec  int          NOT NULL DEFAULT 0,
  progress      numeric(5,2) NOT NULL DEFAULT 0,
  input         jsonb        NOT NULL DEFAULT '{}'::jsonb,
  output        jsonb        NOT NULL DEFAULT '{}'::jsonb,
  error_code    varchar(64),
  created_at    timestamptz  NOT NULL DEFAULT now(),
  updated_at    timestamptz  NOT NULL DEFAULT now(),
  -- 循环圈次：0=汇总行（最终状态），1..N=第几圈的实际执行（复盘"第 3 圈为什么失败"）
  loop_index    smallint     NOT NULL DEFAULT 0,
  CONSTRAINT uk_run_node UNIQUE (run_id, node_key, attempt, loop_index)
);
COMMENT ON TABLE run_node IS
  '节点执行。★ resource_id 由调度器写入，是"资源预约"与"执行"的连接点。';
CREATE INDEX IF NOT EXISTS ix_run_node_run    ON run_node (run_id);
CREATE INDEX IF NOT EXISTS ix_run_node_status ON run_node (tenant_id, status);
CREATE INDEX IF NOT EXISTS ix_run_node_res    ON run_node (resource_id, planned_start);

CREATE TABLE IF NOT EXISTS run_command (
  id                  bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id           bigint      NOT NULL REFERENCES tenant(id),
  run_id              bigint      NOT NULL REFERENCES run(id) ON DELETE CASCADE,
  run_node_id         bigint      NOT NULL REFERENCES run_node(id) ON DELETE CASCADE,
  command_seq         int         NOT NULL DEFAULT 1,
  method              varchar(64) NOT NULL,
  payload             jsonb       NOT NULL DEFAULT '{}'::jsonb,
  status              node_status NOT NULL DEFAULT 'pending',
  attempt             smallint    NOT NULL DEFAULT 1,
  sent_at             timestamptz,
  ack_at              timestamptz,
  finished_at         timestamptz,
  ack_latency_ms      int,
  result              jsonb       NOT NULL DEFAULT '{}'::jsonb,
  device_raw_response jsonb,                                 -- ★ 设备回包原文
  error_code          varchar(64),
  CONSTRAINT uk_run_command UNIQUE (run_node_id, command_seq, attempt)
);
COMMENT ON TABLE run_command IS '指令执行';
CREATE INDEX IF NOT EXISTS ix_run_command_node ON run_command (run_node_id);
CREATE INDEX IF NOT EXISTS ix_run_command_time ON run_command (sent_at DESC);

-- ★ 事件流：唯一真相源，append-only
CREATE TABLE IF NOT EXISTS run_event (
  event_id       uuid        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id      bigint      NOT NULL,
  occurred_at    timestamptz NOT NULL DEFAULT now(),
  aggregate_type varchar(32) NOT NULL,      -- run / run_node / resource / driver / booking / system
  aggregate_id   bigint      NOT NULL,
  event_type     varchar(64) NOT NULL,      -- run.started / node.completed / driver.offline / booking.conflict
  severity       smallint    NOT NULL DEFAULT 0,
  actor          varchar(64),
  trace_id       varchar(64),
  payload        jsonb       NOT NULL DEFAULT '{}'::jsonb,
  PRIMARY KEY (occurred_at, event_id)
) PARTITION BY RANGE (occurred_at);
COMMENT ON TABLE run_event IS
  '事件流（append-only）：审计轨迹 + 仿真回放 + 分析的数据源。所有状态变更集中落此表，可完整回放。';

CREATE INDEX IF NOT EXISTS ix_run_event_agg  ON run_event (aggregate_type, aggregate_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS ix_run_event_type ON run_event (event_type, occurred_at DESC);
CREATE INDEX IF NOT EXISTS ix_run_event_trc  ON run_event (trace_id) WHERE trace_id IS NOT NULL;

-- 按月分区：预建当月前后 + default 兜底
DO $$
DECLARE
  m date;
  pname text;
BEGIN
  CREATE TABLE IF NOT EXISTS run_event_default PARTITION OF run_event DEFAULT;
  FOR i IN -1..12 LOOP
    m := date_trunc('month', current_date) + (i || ' month')::interval;
    pname := 'run_event_' || to_char(m, 'YYYYMM');
    EXECUTE format(
      'CREATE TABLE IF NOT EXISTS %I PARTITION OF run_event FOR VALUES FROM (%L) TO (%L)',
      pname, m, m + interval '1 month');
  END LOOP;
END $$;

-- =============================================================================
-- =============================================================================

CREATE TABLE IF NOT EXISTS schedule_plan (
  id             bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id      bigint      NOT NULL REFERENCES tenant(id),
  plan_no        varchar(64) NOT NULL,
  algorithm      varchar(32) NOT NULL DEFAULT 'heuristic',  -- heuristic / cpsat / genetic / manual
  objective      jsonb       NOT NULL DEFAULT '{}'::jsonb,  -- {metric:'makespan', weights:{...}}
  horizon        tstzrange,
  status         varchar(16) NOT NULL DEFAULT 'draft',      -- draft / adopted / discarded
  metrics        jsonb       NOT NULL DEFAULT '{}'::jsonb,  -- {makespan_sec, utilization, tardiness_sec}
  input_snapshot jsonb       NOT NULL DEFAULT '{}'::jsonb,  -- ★ 调度输入快照 → 可回放、可对比
  created_at     timestamptz NOT NULL DEFAULT now(),
  created_by     bigint,
  CONSTRAINT uk_schedule_plan UNIQUE (tenant_id, plan_no)
);
COMMENT ON TABLE schedule_plan IS
  '调度方案。★ input_snapshot 让每次调度可回放——这是"调度器是纯函数"的落地物证，也是仿真的基础。';

CREATE TABLE IF NOT EXISTS schedule_item (
  id             bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id      bigint      NOT NULL REFERENCES tenant(id),
  plan_id        bigint      NOT NULL REFERENCES schedule_plan(id) ON DELETE CASCADE,
  run_id         bigint      REFERENCES run(id),
  run_node_id    bigint      REFERENCES run_node(id),
  resource_id    bigint      NOT NULL REFERENCES resource_node(id),
  planned_period tstzrange   NOT NULL,
  seq            int         NOT NULL DEFAULT 0,
  CONSTRAINT uk_schedule_item UNIQUE (plan_id, run_node_id)
);
COMMENT ON TABLE schedule_item IS '调度项：plan 的展开结果';
CREATE INDEX IF NOT EXISTS ix_schedule_item_res ON schedule_item USING gist (resource_id, planned_period);

CREATE TABLE IF NOT EXISTS run_variable (
  id         bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id  bigint       NOT NULL REFERENCES tenant(id),
  run_id     bigint       REFERENCES run(id) ON DELETE CASCADE,
  scope      varchar(32)  NOT NULL DEFAULT 'run',   -- run / global / island
  name       varchar(64)  NOT NULL,
  value      jsonb        NOT NULL DEFAULT 'null'::jsonb,
  data_type  varchar(16)  NOT NULL DEFAULT 'number', -- number / string / boolean / array / json
  updated_at timestamptz  NOT NULL DEFAULT now()
);
-- 注意：UNIQUE 约束里不能写表达式，含 coalesce 的必须用唯一索引
CREATE UNIQUE INDEX IF NOT EXISTS uk_run_variable
  ON run_variable (tenant_id, coalesce(run_id, 0), scope, name);
COMMENT ON TABLE run_variable IS
  '执行变量。统一为 jsonb（承载 AND/OR 信号量触发）。';

CREATE TABLE IF NOT EXISTS run_artifact (
  id          bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id   bigint      NOT NULL REFERENCES tenant(id),
  run_id      bigint      NOT NULL REFERENCES run(id) ON DELETE CASCADE,
  run_node_id bigint      REFERENCES run_node(id),
  kind        varchar(32) NOT NULL,      -- well_data / image / report / instrument_file / raw
  name        varchar(255),
  file_id     bigint,                    -- → file_blob
  data        jsonb       NOT NULL DEFAULT '{}'::jsonb,
  created_at  timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE run_artifact IS '执行产物（统一 data_05/14/33/38、experiment_well_data、image 等来源）';
CREATE INDEX IF NOT EXISTS ix_run_artifact_run ON run_artifact (run_id, kind);

-- =============================================================================
-- =============================================================================

CREATE TABLE IF NOT EXISTS error_code (
  id          bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id   bigint         NOT NULL REFERENCES tenant(id),
  code        varchar(64)    NOT NULL,        -- EC-05-1006
  source      varchar(32)    NOT NULL DEFAULT 'device',   -- device / driver / engine / system
  severity    error_severity NOT NULL DEFAULT 'error',
  -- 恢复作用域：错误码的**属性**，不是"抛的时候随手写"。
  --   command 单条指令重发（EC-05-10xx 通讯/超时）
  --   node    整个节点重做（设备拒绝执行）
  --   run     整单停下（驱动没加载这类部署问题）
  --   device  设备维度（离线、驱动崩溃）
  -- 历史上这个值硬编码在抛出点，导致 recovery_policy 里 command/run 级的策略
  -- 一条都匹配不上（2026-11-02 修）。
  scope       varchar(16),
  title       varchar(255),
  detail      text,
  created_at  timestamptz    NOT NULL DEFAULT now(),
  deleted_at  timestamptz,
  CONSTRAINT uk_error_code UNIQUE (tenant_id, code)
);
COMMENT ON TABLE error_code IS '错误码字典';

CREATE TABLE IF NOT EXISTS recovery_policy (
  id            bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id     bigint            NOT NULL REFERENCES tenant(id),
  error_code_id bigint            NOT NULL REFERENCES error_code(id) ON DELETE CASCADE,
  scope         varchar(32)       NOT NULL DEFAULT 'node',   -- command / node / run / resource
  strategy      recovery_strategy NOT NULL,
  params        jsonb             NOT NULL DEFAULT '{}'::jsonb,  -- {max_attempts, backoff, reassign_to_capability}
  max_attempts  smallint          NOT NULL DEFAULT 3,
  priority      smallint          NOT NULL DEFAULT 100,       -- 小者优先
  enabled       boolean           NOT NULL DEFAULT true,
  created_at    timestamptz       NOT NULL DEFAULT now(),
  updated_at    timestamptz       NOT NULL DEFAULT now(),
  CONSTRAINT uk_recovery_policy UNIQUE (tenant_id, error_code_id, strategy, priority)
);
COMMENT ON TABLE recovery_policy IS
  '错误码 → 恢复策略（retry/skip/reassign/abort/manual/compensate）。这是"无人值守"能成立的前提。';

CREATE TABLE IF NOT EXISTS error_occurrence (
  id             bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id      bigint            NOT NULL REFERENCES tenant(id),
  error_code_id  bigint            REFERENCES error_code(id),
  fingerprint    varchar(64),                                   -- ★ 聚合去重键
  run_id         bigint            REFERENCES run(id) ON DELETE SET NULL,
  run_node_id    bigint            REFERENCES run_node(id) ON DELETE SET NULL,
  resource_id    bigint            REFERENCES resource_node(id) ON DELETE SET NULL,
  driver_id      bigint            REFERENCES driver(id) ON DELETE SET NULL,
  occurred_at    timestamptz       NOT NULL DEFAULT now(),
  resolved_at    timestamptz,
  attempt        smallint          NOT NULL DEFAULT 1,
  strategy       recovery_strategy,                             -- 实际采取了什么
  outcome        varchar(16),                                   -- resolved / escalated / ignored
  severity       error_severity    NOT NULL DEFAULT 'error',
  message        text,
  context        jsonb             NOT NULL DEFAULT '{}'::jsonb
);
COMMENT ON TABLE error_occurrence IS
  '错误发生记录。fingerprint 用于把同类错误聚合，解决生产环境 1:80 信噪比问题。';
CREATE INDEX IF NOT EXISTS ix_error_occ_fp      ON error_occurrence (tenant_id, fingerprint, occurred_at DESC);
CREATE INDEX IF NOT EXISTS ix_error_occ_time    ON error_occurrence (occurred_at DESC);
CREATE INDEX IF NOT EXISTS ix_error_occ_open    ON error_occurrence (tenant_id) WHERE resolved_at IS NULL;
CREATE INDEX IF NOT EXISTS ix_error_occ_run     ON error_occurrence (run_id);

-- ---------------------------------------------------------------- 触发器
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY[
    'capability','resource_node','resource_booking','driver','protocol','protocol_step',
    'workflow','workflow_node','run','run_node','run_variable','recovery_policy'
  ] LOOP
    PERFORM attach_updated_at(t::regclass);
  END LOOP;
  PERFORM attach_updated_at('protocol_step'::regclass);
END $$;

DROP TRIGGER IF EXISTS trg_resource_node_path ON resource_node;
CREATE TRIGGER trg_resource_node_path BEFORE INSERT OR UPDATE OF parent_id, code ON resource_node
  FOR EACH ROW EXECUTE FUNCTION resource_node_path();

-- ---------------------------------------------------------------- 种子数据



-- =============================================================================
-- LabOS · 新一代 schema  |  30 业务层
--
--   sample / sample_config / sample_plate / sample_plate_check → sample / sample_aliquot / plate / plate_well
--   consumable / consumable_result / dustbin / rubbish       → consumable / consumable_lot
--   reagent / reagent_resource                                → reagent / reagent_lot
--   equipment / equipment_type / equipment_attr(EAV)          → device / device_method
--   equipment_instance / *_attr(EAV) / *_component(EAV)       → device_instance (attr 用 jsonb)
--   project / project_instance / project_process              → project
--   experiment / experiment_instance / experiment_sub_instance → experiment
-- =============================================================================

-- =============================================================================
-- 一、样本
-- =============================================================================

CREATE TABLE IF NOT EXISTS sample (
  id             bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id      bigint       NOT NULL REFERENCES tenant(id),
  barcode        varchar(64)  NOT NULL,               -- 样本条码（唯一标识）
  name           varchar(128) NOT NULL,
  sample_type    varchar(32)  NOT NULL,
  batch_id       varchar(32),                          -- 批次号 20220403-001
  subject_code   varchar(64),                          -- 受试者/患者编号
  source_type    varchar(32),                          -- blood / tissue / swab / culture / control
  volume         numeric(12,3),
  volume_unit    varchar(16)  NOT NULL DEFAULT 'uL',
  concentration  numeric(12,3),
  priority       smallint     NOT NULL DEFAULT 5,
  status         varchar(24)  NOT NULL DEFAULT 'registered',
                                   -- registered / received / stored / in_use / consumed / discarded / rejected
  expire_at      timestamptz,
  attr           jsonb        NOT NULL DEFAULT '{}'::jsonb,   -- 差异化属性
  created_at     timestamptz  NOT NULL DEFAULT now(),
  created_by     bigint,
  updated_at     timestamptz  NOT NULL DEFAULT now(),
  updated_by     bigint,
  deleted_at     timestamptz,
  CONSTRAINT uk_sample_barcode UNIQUE (tenant_id, barcode)
);
COMMENT ON TABLE sample IS '样本主表';
CREATE INDEX IF NOT EXISTS ix_sample_batch  ON sample (tenant_id, batch_id);
CREATE INDEX IF NOT EXISTS ix_sample_status ON sample (tenant_id, status) WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS sample_aliquot (
  id          bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id   bigint       NOT NULL REFERENCES tenant(id),
  parent_id   bigint       REFERENCES sample(id),      -- 母样
  barcode     varchar(64)  NOT NULL,
  name        varchar(128),
  volume      numeric(12,3),
  volume_unit varchar(16)  NOT NULL DEFAULT 'uL',
  status      varchar(24)  NOT NULL DEFAULT 'available',
  created_at  timestamptz  NOT NULL DEFAULT now(),
  deleted_at  timestamptz,
  CONSTRAINT uk_aliquot_barcode UNIQUE (tenant_id, barcode)
);
COMMENT ON TABLE sample_aliquot IS '样本分装';
CREATE INDEX IF NOT EXISTS ix_aliquot_parent ON sample_aliquot (parent_id);

CREATE TABLE IF NOT EXISTS plate (
  id           bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id    bigint      NOT NULL REFERENCES tenant(id),
  barcode      varchar(64) NOT NULL,
  plate_type   varchar(32) NOT NULL DEFAULT '96',       -- 96 / 384 / 24 / tube_rack
  rows         smallint    NOT NULL DEFAULT 8,
  cols         smallint    NOT NULL DEFAULT 12,
  status       varchar(24) NOT NULL DEFAULT 'available', -- available / in_use / consumed / discarded
  location_id  bigint      REFERENCES resource_node(id),  -- 存放位置
  attr         jsonb       NOT NULL DEFAULT '{}'::jsonb,  -- 批次/实验关联等差异化属性
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now(),
  deleted_at   timestamptz,
  CONSTRAINT uk_plate_barcode UNIQUE (tenant_id, barcode)
);
COMMENT ON TABLE plate IS '板/管架';

CREATE TABLE IF NOT EXISTS plate_well (
  id            bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id     bigint      NOT NULL REFERENCES tenant(id),
  plate_id      bigint      NOT NULL REFERENCES plate(id) ON DELETE CASCADE,
  well_row      smallint    NOT NULL,
  well_col      smallint    NOT NULL,
  well_label    varchar(8)  NOT NULL,                 -- A1 / B12
  sample_id     bigint      REFERENCES sample(id),
  aliquot_id    bigint      REFERENCES sample_aliquot(id),
  volume        numeric(12,3),
  status        varchar(24) NOT NULL DEFAULT 'empty',  -- empty / filled / consumed / invalid
  updated_at    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uk_plate_well UNIQUE (plate_id, well_row, well_col)
);
COMMENT ON TABLE plate_well IS '孔位';
CREATE INDEX IF NOT EXISTS ix_well_sample ON plate_well (sample_id);

-- =============================================================================
-- 二、订单
-- =============================================================================

CREATE TABLE IF NOT EXISTS sample_order (
  id           bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id    bigint       NOT NULL REFERENCES tenant(id),
  order_no     varchar(64)  NOT NULL,
  customer     varchar(128),
  project_id   bigint,
  status       varchar(24)  NOT NULL DEFAULT 'created',
                                -- created / confirmed / in_progress / completed / cancelled
  priority     smallint     NOT NULL DEFAULT 5,
  expected_at  timestamptz,
  remark       text,
  created_at   timestamptz  NOT NULL DEFAULT now(),
  created_by   bigint,
  updated_at   timestamptz  NOT NULL DEFAULT now(),
  deleted_at   timestamptz,
  CONSTRAINT uk_sample_order UNIQUE (tenant_id, order_no)
);
COMMENT ON TABLE sample_order IS '样本订单';

CREATE TABLE IF NOT EXISTS sample_order_item (
  id         bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id  bigint      NOT NULL REFERENCES tenant(id),
  order_id   bigint      NOT NULL REFERENCES sample_order(id) ON DELETE CASCADE,
  sample_id  bigint      REFERENCES sample(id),
  barcode    varchar(64) NOT NULL,
  test_items jsonb       NOT NULL DEFAULT '[]'::jsonb,   -- 检测项列表
  status     varchar(24) NOT NULL DEFAULT 'pending',
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uk_order_item UNIQUE (order_id, barcode)
);

-- =============================================================================
-- 三、耗材与试剂
-- =============================================================================

CREATE TABLE IF NOT EXISTS consumable (
  id            bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id     bigint       NOT NULL REFERENCES tenant(id),
  code          varchar(64)  NOT NULL,
  name          varchar(128) NOT NULL,
  category      varchar(32),                          -- tip / plate / tube / reservoir / filter
  specification varchar(128),
  unit          varchar(16)  NOT NULL DEFAULT 'pcs',
  max_stack     smallint,                             -- 堆栈容量
  attr          jsonb        NOT NULL DEFAULT '{}'::jsonb,
  created_at    timestamptz  NOT NULL DEFAULT now(),
  updated_at    timestamptz  NOT NULL DEFAULT now(),
  deleted_at    timestamptz,
  CONSTRAINT uk_consumable UNIQUE (tenant_id, code)
);

CREATE TABLE IF NOT EXISTS consumable_lot (
  id             bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id      bigint       NOT NULL REFERENCES tenant(id),
  consumable_id  bigint       NOT NULL REFERENCES consumable(id),
  lot_no         varchar(64)  NOT NULL,
  barcode        varchar(64),
  quantity       int          NOT NULL DEFAULT 0,
  remaining      int          NOT NULL DEFAULT 0,
  location_id    bigint       REFERENCES resource_node(id),   -- 存放位置
  device_id      bigint       REFERENCES resource_node(id),   -- 装载到哪台设备
  expire_at      timestamptz,
  received_at    timestamptz,
  status         varchar(24)  NOT NULL DEFAULT 'available',
  created_at     timestamptz  NOT NULL DEFAULT now(),
  updated_at     timestamptz  NOT NULL DEFAULT now(),
  deleted_at     timestamptz,
  CONSTRAINT uk_consumable_lot UNIQUE (tenant_id, consumable_id, lot_no),
  CONSTRAINT ck_consumable_qty CHECK (remaining >= 0 AND remaining <= quantity)
);
CREATE INDEX IF NOT EXISTS ix_consumable_lot_loc ON consumable_lot (location_id) WHERE remaining > 0;

CREATE TABLE IF NOT EXISTS reagent (
  id            bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id     bigint       NOT NULL REFERENCES tenant(id),
  code          varchar(64)  NOT NULL,
  name          varchar(128) NOT NULL,
  category      varchar(32),
  specification varchar(128),
  unit          varchar(16)  NOT NULL DEFAULT 'uL',
  attr          jsonb        NOT NULL DEFAULT '{}'::jsonb,
  created_at    timestamptz  NOT NULL DEFAULT now(),
  updated_at    timestamptz  NOT NULL DEFAULT now(),
  deleted_at    timestamptz,
  CONSTRAINT uk_reagent UNIQUE (tenant_id, code)
);

CREATE TABLE IF NOT EXISTS reagent_lot (
  id            bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id     bigint       NOT NULL REFERENCES tenant(id),
  reagent_id    bigint       NOT NULL REFERENCES reagent(id),
  lot_no        varchar(64)  NOT NULL,
  volume        numeric(12,3) NOT NULL DEFAULT 0,
  remaining     numeric(12,3) NOT NULL DEFAULT 0,
  location_id   bigint       REFERENCES resource_node(id),
  device_id     bigint       REFERENCES resource_node(id),
  expire_at     timestamptz,
  opened_at     timestamptz,
  status        varchar(24)  NOT NULL DEFAULT 'available',
  created_at    timestamptz  NOT NULL DEFAULT now(),
  updated_at    timestamptz  NOT NULL DEFAULT now(),
  deleted_at    timestamptz,
  CONSTRAINT uk_reagent_lot UNIQUE (tenant_id, reagent_id, lot_no)
);

-- =============================================================================
-- 四、项目与实验
-- =============================================================================

CREATE TABLE IF NOT EXISTS project (
  id          bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id   bigint       NOT NULL REFERENCES tenant(id),
  project_no  varchar(64)  NOT NULL,
  parent_project_id bigint REFERENCES project(id),   -- 模板 vs 实例
  name        varchar(128) NOT NULL,
  customer    varchar(128),
  status      varchar(24)  NOT NULL DEFAULT 'active',
  start_at    date,
  end_at      date,
  remark      text,
  attr        jsonb        NOT NULL DEFAULT '{}'::jsonb,
  created_at  timestamptz  NOT NULL DEFAULT now(),
  created_by  bigint,
  updated_at  timestamptz  NOT NULL DEFAULT now(),
  deleted_at  timestamptz,
  CONSTRAINT uk_project UNIQUE (tenant_id, project_no)
);
COMMENT ON TABLE project IS '项目';

CREATE TABLE IF NOT EXISTS experiment (
  id           bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id    bigint       NOT NULL REFERENCES tenant(id),
  experiment_no varchar(64) NOT NULL,
  name         varchar(128) NOT NULL,
  project_id   bigint       REFERENCES project(id),
  order_id     bigint       REFERENCES sample_order(id),
  workflow_id  bigint       REFERENCES workflow(id),
  run_id       bigint       REFERENCES run(id),          -- 实际执行实例
  status       varchar(24)  NOT NULL DEFAULT 'created',
  priority     smallint     NOT NULL DEFAULT 5,
  planned_at   timestamptz,
  started_at   timestamptz,
  finished_at  timestamptz,
  remark       text,
  attr         jsonb        NOT NULL DEFAULT '{}'::jsonb,
  created_at   timestamptz  NOT NULL DEFAULT now(),
  created_by   bigint,
  updated_at   timestamptz  NOT NULL DEFAULT now(),
  deleted_at   timestamptz,
  CONSTRAINT uk_experiment UNIQUE (tenant_id, experiment_no)
);
COMMENT ON TABLE experiment IS '实验';
CREATE INDEX IF NOT EXISTS ix_experiment_status ON experiment (tenant_id, status) WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS experiment_sample (
  experiment_id bigint NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
  sample_id     bigint NOT NULL REFERENCES sample(id)     ON DELETE CASCADE,
  sample_role   varchar(32) NOT NULL DEFAULT 'test',      -- test / control / calibrator
  PRIMARY KEY (experiment_id, sample_id, sample_role)
);

-- =============================================================================
-- 五、设备（型号 / 方法 / 实例）
-- =============================================================================

CREATE TABLE IF NOT EXISTS device (
  id            bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id     bigint       NOT NULL REFERENCES tenant(id),
  code          varchar(64)  NOT NULL,
  name          varchar(128) NOT NULL,
  model         varchar(128),
  vendor        varchar(128),
  device_type   varchar(64),                          -- liquid_handler / centrifuge / incubator / reader / arm / decapper
  capability_id bigint       REFERENCES capability(id),
  icon          varchar(255),
  color         varchar(32),
  need_reagent boolean        NOT NULL DEFAULT false,
  tel           varchar(64),
  remark        text,
  attr          jsonb        NOT NULL DEFAULT '{}'::jsonb,
  created_at    timestamptz  NOT NULL DEFAULT now(),
  updated_at    timestamptz  NOT NULL DEFAULT now(),
  deleted_at    timestamptz,
  CONSTRAINT uk_device UNIQUE (tenant_id, code)
);
COMMENT ON TABLE device IS '设备型号定义';

CREATE TABLE IF NOT EXISTS device_method (
  id            bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id     bigint       NOT NULL REFERENCES tenant(id),
  device_id     bigint       NOT NULL REFERENCES device(id) ON DELETE CASCADE,
  method        varchar(64)  NOT NULL,                 -- 驱动方法名
  name          varchar(128),
  capability_id bigint       REFERENCES capability(id),
  param_schema  jsonb        NOT NULL DEFAULT '{}'::jsonb,
  estimate_sec  int          NOT NULL DEFAULT 0,
  timeout_sec   int          NOT NULL DEFAULT 0,
  created_at    timestamptz  NOT NULL DEFAULT now(),
  updated_at    timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT uk_device_method UNIQUE (device_id, method)
);
COMMENT ON TABLE device_method IS '设备方法/指令定义';

CREATE TABLE IF NOT EXISTS device_instance (
  id                bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id         bigint       NOT NULL REFERENCES tenant(id),
  code              varchar(64)  NOT NULL,
  name              varchar(128) NOT NULL,
  device_id         bigint       REFERENCES device(id),
  resource_id       bigint       REFERENCES resource_node(id),   -- ★ 关联资源树（调度用）
  driver_id         bigint       REFERENCES driver(id),          -- ★ 关联驱动
  serial_no         varchar(128),
  firmware          varchar(64),
  ip                varchar(64),
  status            resource_status NOT NULL DEFAULT 'available',
  last_heartbeat_at timestamptz,
  attr              jsonb        NOT NULL DEFAULT '{}'::jsonb,
  created_at        timestamptz  NOT NULL DEFAULT now(),
  updated_at        timestamptz  NOT NULL DEFAULT now(),
  deleted_at        timestamptz,
  CONSTRAINT uk_device_instance UNIQUE (tenant_id, code)
);
COMMENT ON TABLE device_instance IS '设备实例';
CREATE INDEX IF NOT EXISTS ix_device_instance_res ON device_instance (resource_id);

CREATE TABLE IF NOT EXISTS device_log (
  id           bigint      GENERATED BY DEFAULT AS IDENTITY,   -- 分区表主键必须含分区键，故此处不写 PRIMARY KEY
  tenant_id    bigint      NOT NULL,
  device_id    bigint      NOT NULL,
  logged_at    timestamptz NOT NULL DEFAULT now(),
  level        smallint    NOT NULL DEFAULT 0,
  category     varchar(32),                       -- command / status / error / maintenance
  message      text,
  payload      jsonb       NOT NULL DEFAULT '{}'::jsonb,
  run_id       bigint,
  run_node_id  bigint,
  PRIMARY KEY (logged_at, id)
) PARTITION BY RANGE (logged_at);
COMMENT ON TABLE device_log IS '设备日志';

DO $$
DECLARE
  m date;
  pname text;
BEGIN
  CREATE TABLE IF NOT EXISTS device_log_default PARTITION OF device_log DEFAULT;
  FOR i IN -1..12 LOOP
    m := date_trunc('month', current_date) + (i || ' month')::interval;
    pname := 'device_log_' || to_char(m, 'YYYYMM');
    EXECUTE format(
      'CREATE TABLE IF NOT EXISTS %I PARTITION OF device_log FOR VALUES FROM (%L) TO (%L)',
      pname, m, m + interval '1 month');
  END LOOP;
END $$;

-- =============================================================================
-- 六、文件
-- =============================================================================

CREATE TABLE IF NOT EXISTS file_blob (
  id            bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id     bigint       NOT NULL REFERENCES tenant(id),
  storage       varchar(16)  NOT NULL DEFAULT 'minio',   -- minio / local / s3
  bucket        varchar(64),
  object_key    varchar(512) NOT NULL,
  original_name varchar(255),
  content_type  varchar(128),
  size_bytes    bigint       NOT NULL DEFAULT 0,
  checksum      varchar(64),
  uploaded_at   timestamptz  NOT NULL DEFAULT now(),
  uploaded_by   bigint,
  deleted_at    timestamptz,
  CONSTRAINT uk_file_object UNIQUE (storage, bucket, object_key)
);
COMMENT ON TABLE file_blob IS '文件元数据';
CREATE INDEX IF NOT EXISTS ix_file_checksum ON file_blob (checksum);

-- ---------------------------------------------------------------- 触发器
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY[
    'sample','plate','sample_order','consumable','consumable_lot','reagent','reagent_lot',
    'project','experiment','device','device_method','device_instance'
  ] LOOP
    PERFORM attach_updated_at(t::regclass);
  END LOOP;
END $$;

-- =============================================================================
-- 业务补充表（内核运行必需）
--
-- 说明：这段 DDL 由 pg_dump 导出后人工规范化（加 IF NOT EXISTS、去 public. 前缀）。
-- 首次建库时全部生效；重复执行时，序列/触发器/唯一约束会报 "already exists"
-- ——这不影响表结构，可以忽略。
--
-- 这些表是内核与业务功能依赖的，不属于"平台底座"或"执行内核"两层，
-- 单独放在这里便于对照 docs/数据库设计说明.md 的板位/实验/告警章节。
-- =============================================================================

CREATE TABLE IF NOT EXISTS alert_state (
    tenant_id bigint NOT NULL,
    fingerprint character varying(160) NOT NULL,
    status character varying(16) DEFAULT 'open'::character varying NOT NULL,
    assignee character varying(64),
    note text,
    acked_at timestamp with time zone,
    closed_at timestamp with time zone,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);



COMMENT ON TABLE alert_state IS '告警处置状态：open=待处理 acked=已认领 closed=已关闭 silenced=已静音。fingerprint 与 Alert 一致（错误码|设备）。';



CREATE TABLE IF NOT EXISTS capability_recipe (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    capability_id bigint NOT NULL,
    seq integer NOT NULL,
    method character varying(128) NOT NULL,
    param_expr jsonb DEFAULT '{}'::jsonb NOT NULL,
    criteria jsonb DEFAULT '{}'::jsonb NOT NULL,
    origin character varying(32) DEFAULT 'mined'::character varying NOT NULL,
    sample_count integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);



COMMENT ON TABLE capability_recipe IS '能力编译模板：一个能力展开成哪些设备方法序列。从历史执行数据挖掘（origin=mined）或人工定义（origin=manual）。';



DO $$
BEGIN
  ALTER TABLE capability_recipe ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
      SEQUENCE NAME capability_recipe_id_seq
      START WITH 1
      INCREMENT BY 1
      NO MINVALUE
      NO MAXVALUE
      CACHE 1
  );
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（identity）：%', SQLERRM;
END $$;



CREATE TABLE IF NOT EXISTS device_state (
    tenant_id bigint NOT NULL,
    resource_id bigint NOT NULL,
    status character varying(16) DEFAULT 'idle'::character varying NOT NULL,
    has_plate boolean DEFAULT false NOT NULL,
    current_run_id bigint,
    ready_give boolean DEFAULT false NOT NULL,
    ready_take boolean DEFAULT true NOT NULL,
    last_error character varying(64),
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);



COMMENT ON TABLE device_state IS '设备实时状态。三态判定：busy→Busy；idle+有板→ReadyToGive；idle+无板→ReadyToTake。';



CREATE TABLE IF NOT EXISTS experiment_graph (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    experiment_id bigint NOT NULL,
    graph jsonb DEFAULT '{"edges": [], "nodes": []}'::jsonb NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);



COMMENT ON TABLE experiment_graph IS '实验编排图：nodes 含 type(workflow/variable/fork/parallel/loop)、x/y、配置；edges 含 from/to/label。';



DO $$
BEGIN
  ALTER TABLE experiment_graph ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
      SEQUENCE NAME experiment_graph_id_seq
      START WITH 1
      INCREMENT BY 1
      NO MINVALUE
      NO MAXVALUE
      CACHE 1
  );
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（identity）：%', SQLERRM;
END $$;



CREATE TABLE IF NOT EXISTS experiment_process (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    experiment_id bigint NOT NULL,
    workflow_id bigint NOT NULL,
    seq integer NOT NULL,
    run_mode character varying(16) DEFAULT 'sequential'::character varying NOT NULL,
    run_after integer DEFAULT 0 NOT NULL,
    remark text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    loop_count character varying(64),
    params_override jsonb DEFAULT '{}'::jsonb NOT NULL
);



COMMENT ON TABLE experiment_process IS '实验编排：一个实验由哪些 workflow 组成、顺序如何。run_mode: sequential=顺序（占 99.7%）。run_after: 依赖的 seq（0=立即启动）。';



COMMENT ON COLUMN experiment_process.loop_count IS 'null=不循环；数字或 ${var} 表示重复次数';



COMMENT ON COLUMN experiment_process.params_override IS '参数覆盖，值里支持 ${var}';



DO $$
BEGIN
  ALTER TABLE experiment_process ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
      SEQUENCE NAME experiment_process_id_seq
      START WITH 1
      INCREMENT BY 1
      NO MINVALUE
      NO MAXVALUE
      CACHE 1
  );
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（identity）：%', SQLERRM;
END $$;



CREATE TABLE IF NOT EXISTS experiment_run (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    experiment_id bigint NOT NULL,
    status character varying(16) DEFAULT 'queued'::character varying NOT NULL,
    variables jsonb DEFAULT '{}'::jsonb NOT NULL,
    expanded_steps integer DEFAULT 0 NOT NULL,
    current_seq integer DEFAULT 0 NOT NULL,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    error text,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);



COMMENT ON TABLE experiment_run IS '实验执行：一次实验跑的记录。status: queued/running/paused/completed/failed/cancelled';



DO $$
BEGIN
  ALTER TABLE experiment_run ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
      SEQUENCE NAME experiment_run_id_seq
      START WITH 1
      INCREMENT BY 1
      NO MINVALUE
      NO MAXVALUE
      CACHE 1
  );
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（identity）：%', SQLERRM;
END $$;



CREATE TABLE IF NOT EXISTS experiment_run_step (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    experiment_run_id bigint NOT NULL,
    seq integer NOT NULL,
    loop_index integer DEFAULT 0 NOT NULL,
    workflow_id bigint NOT NULL,
    run_id bigint,
    status character varying(16) DEFAULT 'pending'::character varying NOT NULL,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    error text
);



COMMENT ON TABLE experiment_run_step IS '实验每一步：一个 workflow 的一次执行（循环时 loop_index 递增）';



DO $$
BEGIN
  ALTER TABLE experiment_run_step ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
      SEQUENCE NAME experiment_run_step_id_seq
      START WITH 1
      INCREMENT BY 1
      NO MINVALUE
      NO MAXVALUE
      CACHE 1
  );
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（identity）：%', SQLERRM;
END $$;






CREATE TABLE IF NOT EXISTS experiment_variable (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    experiment_id bigint NOT NULL,
    name character varying(64) NOT NULL,
    type character varying(16) DEFAULT 'number'::character varying NOT NULL,
    default_value text,
    description text,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);



COMMENT ON TABLE experiment_variable IS '实验变量：实验层的输入，流程节点用 ${name} 引用。';



DO $$
BEGIN
  ALTER TABLE experiment_variable ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
      SEQUENCE NAME experiment_variable_id_seq
      START WITH 1
      INCREMENT BY 1
      NO MINVALUE
      NO MAXVALUE
      CACHE 1
  );
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（identity）：%', SQLERRM;
END $$;



CREATE TABLE IF NOT EXISTS island_flow (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    island_id bigint NOT NULL,
    graph jsonb DEFAULT '{"edges": [], "nodes": []}'::jsonb NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);



COMMENT ON TABLE island_flow IS '岛屿编排动线：nodes 存设备坐标（可拖），edges 存搬运箭头。刷新后还原。';



DO $$
BEGIN
  ALTER TABLE island_flow ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
      SEQUENCE NAME island_flow_id_seq
      START WITH 1
      INCREMENT BY 1
      NO MINVALUE
      NO MAXVALUE
      CACHE 1
  );
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（identity）：%', SQLERRM;
END $$;






COMMENT ON TABLE plate_well IS '孔位（把位置塞在 sample.plate_barcode + plate_well_no 无法表达空孔与板布局，故独立成表）';



DO $$
BEGIN
  ALTER TABLE plate_well ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
      SEQUENCE NAME plate_well_id_seq
      START WITH 1
      INCREMENT BY 1
      NO MINVALUE
      NO MAXVALUE
      CACHE 1
  );
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（identity）：%', SQLERRM;
END $$;



CREATE TABLE IF NOT EXISTS position_state (
    tenant_id bigint NOT NULL,
    resource_id bigint NOT NULL,
    plate_id character varying(64),
    occupied_since timestamp with time zone,
    occupied_by_run bigint,
    reserved_until timestamp with time zone,
    reserved_by_run bigint,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);



COMMENT ON TABLE position_state IS '位置占用：一个位置同一时刻只能放一块板。plate_id 非空=实际有板；reserved_until 未过期=有人预约搬运中。';






COMMENT ON TABLE resource_booking IS '资源预约/日历。EXCLUDE USING gist 保证同一资源时间段不重叠——资源冲突由数据库保证，不依赖应用层判断。';



DO $$
BEGIN
  ALTER TABLE resource_booking ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
      SEQUENCE NAME resource_booking_id_seq
      START WITH 1
      INCREMENT BY 1
      NO MINVALUE
      NO MAXVALUE
      CACHE 1
  );
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（identity）：%', SQLERRM;
END $$;



CREATE TABLE IF NOT EXISTS robot_point (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    island_id bigint NOT NULL,
    robot_resource_id bigint NOT NULL,
    code character varying(64) NOT NULL,
    name character varying(128),
    x numeric(10,3),
    y numeric(10,3),
    z numeric(10,3),
    device_resource_id bigint,
    action character varying(32),
    synced_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);



COMMENT ON TABLE robot_point IS '机械臂点位：每个岛上的机械臂有若干点位（对应设备/工位）。Transfer 的 source/target 用 code 而不是设备编号。';



DO $$
BEGIN
  ALTER TABLE robot_point ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
      SEQUENCE NAME robot_point_id_seq
      START WITH 1
      INCREMENT BY 1
      NO MINVALUE
      NO MAXVALUE
      CACHE 1
  );
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（identity）：%', SQLERRM;
END $$;






COMMENT ON TABLE run_artifact IS '执行产物（统一 data_05/14/33/38、experiment_well_data、image 等来源）';



DO $$
BEGIN
  ALTER TABLE run_artifact ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
      SEQUENCE NAME run_artifact_id_seq
      START WITH 1
      INCREMENT BY 1
      NO MINVALUE
      NO MAXVALUE
      CACHE 1
  );
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（identity）：%', SQLERRM;
END $$;






COMMENT ON TABLE run_variable IS '执行变量（对应 variable + variable_instance 语义）。过去靠它做 AND/OR 信号量触发，这里统一为 jsonb。';



DO $$
BEGIN
  ALTER TABLE run_variable ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
      SEQUENCE NAME run_variable_id_seq
      START WITH 1
      INCREMENT BY 1
      NO MINVALUE
      NO MAXVALUE
      CACHE 1
  );
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（identity）：%', SQLERRM;
END $$;



CREATE TABLE IF NOT EXISTS workflow_graph_history (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    workflow_id bigint NOT NULL,
    xml text NOT NULL,
    node_count integer DEFAULT 0 NOT NULL,
    edge_count integer DEFAULT 0 NOT NULL,
    saved_by character varying(64),
    saved_at timestamp with time zone DEFAULT now() NOT NULL
);



COMMENT ON TABLE workflow_graph_history IS '流程图保存历史：每次 PUT /graph 前把上一版 XML 存一份，误操作可回滚。';



DO $$
BEGIN
  ALTER TABLE workflow_graph_history ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
      SEQUENCE NAME workflow_graph_history_id_seq
      START WITH 1
      INCREMENT BY 1
      NO MINVALUE
      NO MAXVALUE
      CACHE 1
  );
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（identity）：%', SQLERRM;
END $$;



DO $$
BEGIN
  ALTER TABLE ONLY alert_state
      ADD CONSTRAINT alert_state_pkey PRIMARY KEY (tenant_id, fingerprint);
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



DO $$
BEGIN
  ALTER TABLE ONLY capability_recipe
      ADD CONSTRAINT capability_recipe_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



DO $$
BEGIN
  ALTER TABLE ONLY device_state
      ADD CONSTRAINT device_state_pkey PRIMARY KEY (tenant_id, resource_id);
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



-- P1-27 修复（评审 20261102-03）：ex_booking_overlap 已在 20_kernel.sql 里
-- 用 DO $$ ... IF NOT EXISTS ... $$ 幂等创建，这里再 ADD 一次：
--   1) 重复执行必报 "constraint already exists"
--   2) 两处定义漂移时会出现"到底哪个生效"的疑问
-- 删除本处，只保留 20_kernel.sql 的幂等版本。



DO $$
BEGIN
  ALTER TABLE ONLY experiment_graph
      ADD CONSTRAINT experiment_graph_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



DO $$
BEGIN
  ALTER TABLE ONLY experiment_process
      ADD CONSTRAINT experiment_process_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



DO $$
BEGIN
  ALTER TABLE ONLY experiment_run
      ADD CONSTRAINT experiment_run_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



DO $$
BEGIN
  ALTER TABLE ONLY experiment_run_step
      ADD CONSTRAINT experiment_run_step_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



DO $$
BEGIN
  ALTER TABLE ONLY experiment_sample
      ADD CONSTRAINT experiment_sample_pkey PRIMARY KEY (experiment_id, sample_id, sample_role);
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



DO $$
BEGIN
  ALTER TABLE ONLY experiment_variable
      ADD CONSTRAINT experiment_variable_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



DO $$
BEGIN
  ALTER TABLE ONLY island_flow
      ADD CONSTRAINT island_flow_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



DO $$
BEGIN
  ALTER TABLE ONLY plate_well
      ADD CONSTRAINT plate_well_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



DO $$
BEGIN
  ALTER TABLE ONLY position_state
      ADD CONSTRAINT position_state_pkey PRIMARY KEY (tenant_id, resource_id);
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



DO $$
BEGIN
  ALTER TABLE ONLY resource_booking
      ADD CONSTRAINT resource_booking_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



DO $$
BEGIN
  ALTER TABLE ONLY robot_point
      ADD CONSTRAINT robot_point_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



DO $$
BEGIN
  ALTER TABLE ONLY run_artifact
      ADD CONSTRAINT run_artifact_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



DO $$
BEGIN
  ALTER TABLE ONLY run_variable
      ADD CONSTRAINT run_variable_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



DO $$
BEGIN
  ALTER TABLE ONLY experiment_graph
      ADD CONSTRAINT uk_exp_graph UNIQUE (experiment_id);
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



DO $$
BEGIN
  ALTER TABLE ONLY experiment_process
      ADD CONSTRAINT uk_exp_proc UNIQUE (experiment_id, seq);
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



DO $$
BEGIN
  ALTER TABLE ONLY experiment_variable
      ADD CONSTRAINT uk_exp_var UNIQUE (experiment_id, name);
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



DO $$
BEGIN
  ALTER TABLE ONLY island_flow
      ADD CONSTRAINT uk_island_flow UNIQUE (island_id);
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



DO $$
BEGIN
  ALTER TABLE ONLY capability_recipe
      ADD CONSTRAINT uk_recipe UNIQUE (tenant_id, capability_id, seq);
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



DO $$
BEGIN
  ALTER TABLE ONLY robot_point
      ADD CONSTRAINT uk_robot_point UNIQUE (tenant_id, robot_resource_id, code);
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



DO $$
BEGIN
  ALTER TABLE ONLY workflow_graph_history
      ADD CONSTRAINT workflow_graph_history_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



CREATE INDEX IF NOT EXISTS ix_booking_resource_period ON resource_booking USING gist (resource_id, period);



CREATE INDEX IF NOT EXISTS ix_booking_run_node ON resource_booking USING btree (run_node_id);



CREATE INDEX IF NOT EXISTS ix_exp_proc_exp ON experiment_process USING btree (experiment_id, seq);



CREATE INDEX IF NOT EXISTS ix_exp_run_exp ON experiment_run USING btree (tenant_id, experiment_id, id DESC);



CREATE INDEX IF NOT EXISTS ix_exp_step_run ON experiment_run_step USING btree (experiment_run_id, seq, loop_index);



CREATE INDEX IF NOT EXISTS ix_pos_plate ON position_state USING btree (tenant_id, plate_id) WHERE (plate_id IS NOT NULL);



CREATE INDEX IF NOT EXISTS ix_robot_point_isl ON robot_point USING btree (tenant_id, island_id);



CREATE INDEX IF NOT EXISTS ix_run_artifact_run ON run_artifact USING btree (run_id, kind);



CREATE INDEX IF NOT EXISTS ix_well_sample ON plate_well USING btree (sample_id);



CREATE INDEX IF NOT EXISTS ix_wgh_wf ON workflow_graph_history USING btree (tenant_id, workflow_id, saved_at DESC);



CREATE UNIQUE INDEX IF NOT EXISTS uk_run_variable ON run_variable USING btree (tenant_id, COALESCE(run_id, (0)::bigint), scope, name);



DROP TRIGGER IF EXISTS trg_resource_booking_updated_at ON resource_booking;
CREATE TRIGGER trg_resource_booking_updated_at BEFORE UPDATE ON resource_booking FOR EACH ROW EXECUTE FUNCTION set_updated_at();



DROP TRIGGER IF EXISTS trg_run_variable_updated_at ON run_variable;
CREATE TRIGGER trg_run_variable_updated_at BEFORE UPDATE ON run_variable FOR EACH ROW EXECUTE FUNCTION set_updated_at();



DO $$
BEGIN
  ALTER TABLE ONLY capability_recipe
      ADD CONSTRAINT capability_recipe_capability_id_fkey FOREIGN KEY (capability_id) REFERENCES capability(id);
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



DO $$
BEGIN
  ALTER TABLE ONLY device_state
      ADD CONSTRAINT device_state_resource_id_fkey FOREIGN KEY (resource_id) REFERENCES resource_node(id) ON DELETE CASCADE;
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



DO $$
BEGIN
  ALTER TABLE ONLY experiment_graph
      ADD CONSTRAINT experiment_graph_experiment_id_fkey FOREIGN KEY (experiment_id) REFERENCES experiment(id) ON DELETE CASCADE;
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



DO $$
BEGIN
  ALTER TABLE ONLY experiment_process
      ADD CONSTRAINT experiment_process_experiment_id_fkey FOREIGN KEY (experiment_id) REFERENCES experiment(id) ON DELETE CASCADE;
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



DO $$
BEGIN
  ALTER TABLE ONLY experiment_process
      ADD CONSTRAINT experiment_process_workflow_id_fkey FOREIGN KEY (workflow_id) REFERENCES workflow(id);
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



DO $$
BEGIN
  ALTER TABLE ONLY experiment_run
      ADD CONSTRAINT experiment_run_experiment_id_fkey FOREIGN KEY (experiment_id) REFERENCES experiment(id);
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



DO $$
BEGIN
  ALTER TABLE ONLY experiment_run_step
      ADD CONSTRAINT experiment_run_step_experiment_run_id_fkey FOREIGN KEY (experiment_run_id) REFERENCES experiment_run(id) ON DELETE CASCADE;
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



DO $$
BEGIN
  ALTER TABLE ONLY experiment_sample
      ADD CONSTRAINT experiment_sample_experiment_id_fkey FOREIGN KEY (experiment_id) REFERENCES experiment(id) ON DELETE CASCADE;
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



DO $$
BEGIN
  ALTER TABLE ONLY experiment_sample
      ADD CONSTRAINT experiment_sample_sample_id_fkey FOREIGN KEY (sample_id) REFERENCES sample(id) ON DELETE CASCADE;
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



DO $$
BEGIN
  ALTER TABLE ONLY experiment_variable
      ADD CONSTRAINT experiment_variable_experiment_id_fkey FOREIGN KEY (experiment_id) REFERENCES experiment(id) ON DELETE CASCADE;
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



DO $$
BEGIN
  ALTER TABLE ONLY plate_well
      ADD CONSTRAINT plate_well_aliquot_id_fkey FOREIGN KEY (aliquot_id) REFERENCES sample_aliquot(id);
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



DO $$
BEGIN
  ALTER TABLE ONLY plate_well
      ADD CONSTRAINT plate_well_plate_id_fkey FOREIGN KEY (plate_id) REFERENCES plate(id) ON DELETE CASCADE;
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



DO $$
BEGIN
  ALTER TABLE ONLY plate_well
      ADD CONSTRAINT plate_well_sample_id_fkey FOREIGN KEY (sample_id) REFERENCES sample(id);
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



DO $$
BEGIN
  ALTER TABLE ONLY plate_well
      ADD CONSTRAINT plate_well_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenant(id);
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



DO $$
BEGIN
  ALTER TABLE ONLY resource_booking
      ADD CONSTRAINT resource_booking_resource_id_fkey FOREIGN KEY (resource_id) REFERENCES resource_node(id);
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



DO $$
BEGIN
  ALTER TABLE ONLY resource_booking
      ADD CONSTRAINT resource_booking_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenant(id);
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



DO $$
BEGIN
  ALTER TABLE ONLY run_artifact
      ADD CONSTRAINT run_artifact_run_id_fkey FOREIGN KEY (run_id) REFERENCES run(id) ON DELETE CASCADE;
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



DO $$
BEGIN
  ALTER TABLE ONLY run_artifact
      ADD CONSTRAINT run_artifact_run_node_id_fkey FOREIGN KEY (run_node_id) REFERENCES run_node(id);
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



DO $$
BEGIN
  ALTER TABLE ONLY run_artifact
      ADD CONSTRAINT run_artifact_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenant(id);
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



DO $$
BEGIN
  ALTER TABLE ONLY run_variable
      ADD CONSTRAINT run_variable_run_id_fkey FOREIGN KEY (run_id) REFERENCES run(id) ON DELETE CASCADE;
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



DO $$
BEGIN
  ALTER TABLE ONLY run_variable
      ADD CONSTRAINT run_variable_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenant(id);
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;



-- =============================================================================
-- LabOS · 新一代 schema  |  40 运维层与兼容视图
-- =============================================================================

-- =============================================================================
-- =============================================================================

CREATE TABLE IF NOT EXISTS notify_channel (
  id         bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id  bigint       NOT NULL REFERENCES tenant(id),
  code       varchar(64)  NOT NULL,
  name       varchar(128) NOT NULL,
  kind       varchar(32)  NOT NULL,      -- wecom / dingtalk / feishu / email / sms / mqtt / webhook
  endpoint   varchar(512),               -- webhook url / smtp / gateway
  secret_enc bytea,                      -- 加密存储
  options    jsonb        NOT NULL DEFAULT '{}'::jsonb,
  enabled    boolean      NOT NULL DEFAULT true,
  created_at timestamptz  NOT NULL DEFAULT now(),
  updated_at timestamptz  NOT NULL DEFAULT now(),
  deleted_at timestamptz,
  CONSTRAINT uk_notify_channel UNIQUE (tenant_id, code)
);
COMMENT ON TABLE notify_channel IS
  '通知渠道';

CREATE TABLE IF NOT EXISTS notification (
  id          bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id   bigint       NOT NULL REFERENCES tenant(id),
  code        varchar(64)  NOT NULL,
  name        varchar(128) NOT NULL,
  category    varchar(32)  NOT NULL DEFAULT 'alert',   -- alert / progress / digest
  severity    error_severity NOT NULL DEFAULT 'warn',
  template    text,
  channels    jsonb        NOT NULL DEFAULT '[]'::jsonb,   -- ["wecom","email"]
  aggregation jsonb        NOT NULL DEFAULT '{}'::jsonb,   -- {window_sec:300, by:["fingerprint"]}
  enabled     boolean      NOT NULL DEFAULT true,
  created_at  timestamptz  NOT NULL DEFAULT now(),
  updated_at  timestamptz  NOT NULL DEFAULT now(),
  deleted_at  timestamptz,
  CONSTRAINT uk_notification UNIQUE (tenant_id, code)
);
COMMENT ON TABLE notification IS
  '通知规则。★ aggregation 解决"一台离线设备一天刷 5928 条告警"的告警洪水问题。';

CREATE TABLE IF NOT EXISTS notify_log (
  id           bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id    bigint      NOT NULL,
  channel_id   bigint      REFERENCES notify_channel(id),
  notification_id bigint   REFERENCES notification(id),
  sent_at      timestamptz NOT NULL DEFAULT now(),
  success      boolean     NOT NULL DEFAULT false,
  status_code  int,
  error        text,
  subject      varchar(255),
  body         text,
  ref_type     varchar(32),          -- error_occurrence / run / system
  ref_id       bigint,
  suppressed   int         NOT NULL DEFAULT 0     -- 被聚合抑制的条数
);
COMMENT ON TABLE notify_log IS '通知发送记录';
CREATE INDEX IF NOT EXISTS ix_notify_log_time ON notify_log (sent_at DESC);

-- =============================================================================
-- 二、审计
-- =============================================================================

CREATE TABLE IF NOT EXISTS audit_log (
  id          bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id   bigint      NOT NULL,
  occurred_at timestamptz NOT NULL DEFAULT now(),
  user_id     bigint,
  username    varchar(64),
  action      varchar(64) NOT NULL,     -- create / update / delete / execute / login / export
  entity_type varchar(64) NOT NULL,
  entity_id   bigint,
  before      jsonb,
  after       jsonb,
  ip          inet,
  user_agent  varchar(255),
  trace_id    varchar(64),
  result      varchar(16) NOT NULL DEFAULT 'success'
);
COMMENT ON TABLE audit_log IS
  '操作审计';
CREATE INDEX IF NOT EXISTS ix_audit_time   ON audit_log (occurred_at DESC);
CREATE INDEX IF NOT EXISTS ix_audit_entity ON audit_log (entity_type, entity_id);
CREATE INDEX IF NOT EXISTS ix_audit_user   ON audit_log (user_id, occurred_at DESC);

-- =============================================================================
-- 三、只读映射视图（外部只读查询沿用现有表名，底层一律走新表）
-- =============================================================================

CREATE OR REPLACE VIEW v_wf_process_instance_ro AS
SELECT
  r.id                           AS id,
  r.workflow_id                  AS process_id,
  r.run_no                       AS name,
  0                              AS is_experiment_process_group,
  NULL::bigint                   AS experiment_process_group_instance_id,
  CASE r.mode WHEN 'simulation' THEN 1 ELSE 0 END AS is_simulation,
  r.actual_start                 AS start_time,
  r.actual_end                   AS end_time,
  CASE r.status
    WHEN 'pending' THEN 0 WHEN 'planned' THEN 0 WHEN 'queued' THEN 0
    WHEN 'running' THEN 1 WHEN 'paused' THEN 1
    WHEN 'completed' THEN 2
    ELSE 3 END                   AS status,
  r.created_at                   AS create_time,
  r.created_by                   AS create_by,
  r.updated_at                   AS update_time,
  r.updated_by                   AS update_by
FROM run r
WHERE r.run_type IN ('experiment','workflow','project');
COMMENT ON VIEW v_wf_process_instance_ro IS
  '只读映射视图：以 wf_process_instance 的列语义暴露等价查询，写入须走新表';

CREATE OR REPLACE VIEW v_equipment_instance_ro AS
SELECT
  di.id,
  di.code                        AS code,
  di.name                        AS name,
  d.name                         AS equipment_name,
  di.device_id                   AS equipment_id,
  rn.code                        AS island_code,
  di.status::text                AS status,
  di.attr,
  di.created_at                  AS create_time,
  di.updated_at                  AS update_time
FROM device_instance di
LEFT JOIN device d        ON d.id  = di.device_id
LEFT JOIN resource_node rn ON rn.id = di.resource_id;
COMMENT ON VIEW v_equipment_instance_ro IS '只读映射视图：以 equipment_instance 的列语义暴露等价查询';

-- =============================================================================
-- 四、健康自检
-- =============================================================================

CREATE OR REPLACE VIEW v_schema_health AS
SELECT
  n.nspname                                              AS schema,
  count(*) FILTER (WHERE c.relkind = 'r')                AS tables,
  count(*) FILTER (WHERE c.relkind = 'v')                AS views,
  count(*) FILTER (WHERE c.relkind = 'i')                AS indexes,
  count(*) FILTER (WHERE c.relkind = 'S')                AS sequences,
  count(*) FILTER (WHERE c.relkind = 'p')                AS partitioned_tables
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public'
GROUP BY n.nspname;
-- ============================================================================
-- 92：实验室画像（lab_profile）
--
-- 为什么单独成片：这张表最初是**直接改 90_all_in_one.sql** 加进去的，
-- 没有对应分片。2026-11-02 修 P0-17（merge.sh 补齐分片）时，
-- 重新 merge 把直接改的内容冲掉了 —— 这就是"直接改产物"的危害。
-- 现补成分片，从此 merge 可重复执行。
-- ============================================================================

CREATE TABLE IF NOT EXISTS lab_profile (
tenant_id bigint NOT NULL,
template character varying(32),
max_parallel smallint DEFAULT 1 NOT NULL,
objective character varying(16) DEFAULT 'fairness'::character varying NOT NULL,
unknown_policy character varying(24) DEFAULT 'stop_related'::character varying NOT NULL,
night_mode jsonb DEFAULT '{}'::jsonb NOT NULL,
retry jsonb DEFAULT '{}'::jsonb NOT NULL,
audit jsonb DEFAULT '{}'::jsonb NOT NULL,
tuning jsonb DEFAULT '{}'::jsonb NOT NULL,
created_at timestamp with time zone DEFAULT now() NOT NULL,
updated_at timestamp with time zone DEFAULT now() NOT NULL,
updated_by bigint
);
DO $$
BEGIN
  ALTER TABLE ONLY lab_profile
  ADD CONSTRAINT lab_profile_pkey PRIMARY KEY (tenant_id);
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;
DO $$
BEGIN
  ALTER TABLE ONLY lab_profile
  ADD CONSTRAINT lab_profile_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenant(id);
EXCEPTION WHEN duplicate_object OR duplicate_table OR invalid_table_definition THEN
  RAISE NOTICE '跳过重复声明（constraint）：%', SQLERRM;
END $$;
-- ============================================================================
-- M1：并行调度基础设施
--
-- 包含：
--   1. run_result          异步结果（设备完成上传，流程后续查询）
--   2. device_port         出入口（1~N 个，有方向，支持 capacity>1）
--   3. device_port_usage   通道信号量（capacity=1 时等价互斥）
--   4. device_access       门/闸（状态靠动作推断，不查询设备）
--   5. device_point_template 点位属性模板（按设备型号自动带出）
--   6. robot_point 补 9 字段 + fill_status
--   7. plate / position_state 补板位状态字段
--
-- 全部幂等（IF NOT EXISTS），可重复执行。
-- ============================================================================

-- ============================================================================
-- 1. run_result：异步结果
-- ============================================================================
CREATE TABLE IF NOT EXISTS run_result (
    id          bigserial PRIMARY KEY,
    tenant_id   bigint       NOT NULL REFERENCES tenant(id),
    run_id      bigint       NOT NULL,
    node_id     bigint,
    result_key  varchar(64)  NOT NULL,
    payload     jsonb        NOT NULL DEFAULT '{}'::jsonb,
    source      varchar(16)  NOT NULL DEFAULT 'device',
    valid_until timestamptz,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uk_run_result UNIQUE (tenant_id, run_id, result_key)
);

CREATE INDEX IF NOT EXISTS idx_run_result_lookup
    ON run_result (tenant_id, run_id, result_key);

COMMENT ON TABLE run_result IS
    '异步结果：设备完成动作后上传，流程后续节点通过 ${results.<key>.<field>} 引用';
COMMENT ON COLUMN run_result.result_key IS
    '结果标识，如 readPlate。同一 run 内唯一';
COMMENT ON COLUMN run_result.source IS
    'device=设备回传 manual=人工填 external=外部系统推送';
COMMENT ON COLUMN run_result.valid_until IS
    '有效期。过期的结果 has() 返回 false——用过期的读板结果判定阳性/阴性可能出错';

-- ============================================================================
-- 2. device_port：出入口
-- ============================================================================
CREATE TABLE IF NOT EXISTS device_port (
    id          bigserial PRIMARY KEY,
    tenant_id   bigint      NOT NULL REFERENCES tenant(id),
    resource_id bigint      NOT NULL REFERENCES resource_node(id),
    code        varchar(32) NOT NULL,
    direction   varchar(8)  NOT NULL DEFAULT 'both',
    port_type   varchar(16) NOT NULL DEFAULT 'slot',
    capacity    smallint    NOT NULL DEFAULT 1,
    position_id bigint,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_device_port UNIQUE (tenant_id, resource_id, code),
    CONSTRAINT ck_device_port_direction CHECK (direction IN ('in', 'out', 'both')),
    CONSTRAINT ck_device_port_capacity CHECK (capacity >= 1)
);

CREATE INDEX IF NOT EXISTS idx_device_port_resource
    ON device_port (tenant_id, resource_id);

COMMENT ON TABLE device_port IS
    '设备的出入口。三种形态：分离(IN-1+OUT-1)、多进多出、单一(BOTH)';
COMMENT ON COLUMN device_port.direction IS
    'in=只进 out=只出 both=进出共用（一次只能过一块）';
COMMENT ON COLUMN device_port.capacity IS
    '同时能过几块板。1=串行；>1=信号量（宽传送带、多层托盘）';

-- ============================================================================
-- 3. device_port_usage：通道信号量
-- ============================================================================
CREATE TABLE IF NOT EXISTS device_port_usage (
    tenant_id    bigint      NOT NULL REFERENCES tenant(id),
    port_id      bigint      NOT NULL REFERENCES device_port(id) ON DELETE CASCADE,
    in_use       smallint    NOT NULL DEFAULT 0,
    max_capacity smallint    NOT NULL DEFAULT 1,
    updated_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, port_id),
    CONSTRAINT ck_port_usage CHECK (in_use >= 0 AND in_use <= max_capacity)
);

COMMENT ON TABLE device_port_usage IS
    '通道占用计数。capacity=1 时等价互斥；>1 时是信号量。
     抢通道：UPDATE ... SET in_use=in_use+1 WHERE in_use < max_capacity RETURNING；
     返回行=抢到，无返回=满了';

-- 给已有通道初始化计数行（幂等）
INSERT INTO device_port_usage (tenant_id, port_id, in_use, max_capacity)
SELECT p.tenant_id, p.id, 0, p.capacity
FROM device_port p
ON CONFLICT (tenant_id, port_id) DO NOTHING;

-- ============================================================================
-- 4. device_access：门/闸
-- ============================================================================
CREATE TABLE IF NOT EXISTS device_access (
    id               bigserial PRIMARY KEY,
    tenant_id        bigint      NOT NULL REFERENCES tenant(id),
    resource_id      bigint      NOT NULL REFERENCES resource_node(id),
    code             varchar(32) NOT NULL,
    access_type      varchar(16) NOT NULL DEFAULT 'door',
    state            varchar(16) NOT NULL DEFAULT 'closed',
    open_method      varchar(64),
    close_method     varchar(64),
    open_timeout_ms  integer     NOT NULL DEFAULT 30000,
    state_checked_at timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_device_access UNIQUE (tenant_id, resource_id, code),
    CONSTRAINT ck_device_access_state
        CHECK (state IN ('closed', 'open', 'opening', 'closing', 'unknown'))
);

CREATE INDEX IF NOT EXISTS idx_device_access_resource
    ON device_access (tenant_id, resource_id);

COMMENT ON TABLE device_access IS
    '门/闸/盖。**门没开机械臂伸进去会撞机**——这是四层资源里最容易漏的一层';
COMMENT ON COLUMN device_access.state IS
    '靠动作结果推断，不查询设备：
     默认 closed → OpenDoor 成功即 open → CloseDoor 成功即 closed；
     动作失败或恢复后未确认 → unknown';
COMMENT ON COLUMN device_access.open_method IS
    '开门方法名。为 NULL 说明是手动门，必须人工打开后在中控确认';

-- ============================================================================
-- 5. device_point_template：点位属性模板
-- ============================================================================
CREATE TABLE IF NOT EXISTS device_point_template (
    tenant_id      bigint      NOT NULL REFERENCES tenant(id),
    device_model   varchar(64) NOT NULL,
    point_type     varchar(16) NOT NULL DEFAULT 'generic',
    approach_axis  varchar(8)  NOT NULL DEFAULT 'front',
    safe_z_offset  numeric(10, 3) NOT NULL DEFAULT 50,
    max_stack      smallint    NOT NULL DEFAULT 1,
    height_mm      numeric(8, 2),
    requires_door  boolean     NOT NULL DEFAULT false,
    port_direction varchar(8)  NOT NULL DEFAULT 'both',
    notes          text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, device_model)
);

COMMENT ON TABLE device_point_template IS
    '按设备型号带出点位属性。同步点位时自动填，缺的人工补，下次核验';

-- ============================================================================
-- 6. robot_point：补 9 个属性 + fill_status
-- ============================================================================
ALTER TABLE robot_point ADD COLUMN IF NOT EXISTS point_type varchar(16) DEFAULT 'generic';
ALTER TABLE robot_point ADD COLUMN IF NOT EXISTS port_id bigint;
ALTER TABLE robot_point ADD COLUMN IF NOT EXISTS position_id bigint;
ALTER TABLE robot_point ADD COLUMN IF NOT EXISTS access_id bigint;
ALTER TABLE robot_point ADD COLUMN IF NOT EXISTS approach_axis varchar(8) DEFAULT 'front';
ALTER TABLE robot_point ADD COLUMN IF NOT EXISTS safe_z_offset numeric(10, 3) DEFAULT 50;
ALTER TABLE robot_point ADD COLUMN IF NOT EXISTS max_stack smallint DEFAULT 1;
ALTER TABLE robot_point ADD COLUMN IF NOT EXISTS height_mm numeric(8, 2);
ALTER TABLE robot_point ADD COLUMN IF NOT EXISTS order_in_device smallint;
ALTER TABLE robot_point ADD COLUMN IF NOT EXISTS fill_status varchar(16) DEFAULT 'ok';

COMMENT ON COLUMN robot_point.point_type IS
    'pick=抓取点 place=放置点 generic=通用 dock=停靠 safe_z=安全高度';
COMMENT ON COLUMN robot_point.position_id IS
    '对应哪个层位。多板位设备必需——否则不知道板放哪一层';
COMMENT ON COLUMN robot_point.access_id IS
    '到访前必须先打开哪个门。没填会撞机';
COMMENT ON COLUMN robot_point.approach_axis IS
    '接近方向 front/left/right/top/bottom。从错误方向伸进去会撞';
COMMENT ON COLUMN robot_point.safe_z_offset IS
    '横移前抬到这个高度（z + offset）再动，避免撞架子';
COMMENT ON COLUMN robot_point.order_in_device IS
    '机械臂访问顺序（1=先取）。**现场示教确定**，系统不假设 L1 是底层还是外层';
COMMENT ON COLUMN robot_point.fill_status IS
    'ok=属性完整可调度 pending=待人工补填 verified=已核验。
     pending 的点位不参与动态规划';

CREATE INDEX IF NOT EXISTS idx_robot_point_position
    ON robot_point (position_id) WHERE position_id IS NOT NULL;

-- ============================================================================
-- 7. plate / position_state：板位状态
-- ============================================================================
ALTER TABLE plate ADD COLUMN IF NOT EXISTS position_status varchar(16) DEFAULT 'KNOWN';
ALTER TABLE plate ADD COLUMN IF NOT EXISTS possible_locations bigint[];

COMMENT ON COLUMN plate.position_status IS
    'KNOWN=明确在某位置 UNKNOWN=搬运中异常，不知道在哪 LOST=确认丢了。
     UNKNOWN 必须人工盘点，系统不做任何猜测';
COMMENT ON COLUMN plate.possible_locations IS
    'UNKNOWN 时的可能位置集合：源设备、目标设备、机械臂。这些位置全部封锁';

ALTER TABLE position_state ADD COLUMN IF NOT EXISTS port_id bigint;
ALTER TABLE position_state ADD COLUMN IF NOT EXISTS block_reason varchar(32);

COMMENT ON COLUMN position_state.port_id IS
    '这个层位通过哪个通道可达';
COMMENT ON COLUMN position_state.block_reason IS
    'PLATE_UNKNOWN=板位置未知时系统封锁，等人工盘点后解封';

-- ============================================================================
-- 8. 权限（视图，便于排查）
-- ============================================================================
CREATE OR REPLACE VIEW v_device_access_status AS
SELECT a.tenant_id,
       r.code       AS device_code,
       a.code       AS access_code,
       a.access_type,
       a.state,
       a.open_method,
       a.open_method IS NULL AS is_manual,
       a.state_checked_at
FROM device_access a
JOIN resource_node r ON r.id = a.resource_id;

COMMENT ON VIEW v_device_access_status IS
    '门状态一览。is_manual=true 的必须人工打开';

CREATE OR REPLACE VIEW v_port_usage AS
SELECT u.tenant_id,
       r.code        AS device_code,
       p.code        AS port_code,
       p.direction,
       u.in_use,
       u.max_capacity,
       u.max_capacity - u.in_use AS free_slots
FROM device_port_usage u
JOIN device_port p ON p.id = u.port_id
JOIN resource_node r ON r.id = p.resource_id;

COMMENT ON VIEW v_port_usage IS
    '通道占用一览。free_slots=0 表示满了';
-- ============================================================================
-- M4：暂停 / 恢复 / 故障处理
--
-- 三种暂停态（用 pause_kind 区分，不动 run_status 枚举）：
--   paused_fault    设备故障，心跳恢复后自动继续
--   waiting_human   需人工确认（重试耗尽、板位不一致）
--   paused_unknown  板位置未知，必须人工盘点 + 校正
--
-- 为什么用列不用枚举值：
--   加枚举值要 ALTER TYPE，且 PG 不允许在事务里加；
--   而暂停类型以后可能还会增加（比如 pause_overload）。
--
-- 全部幂等。
-- ============================================================================

ALTER TABLE run ADD COLUMN IF NOT EXISTS pause_kind varchar(24);
ALTER TABLE run ADD COLUMN IF NOT EXISTS pause_reason text;
ALTER TABLE run ADD COLUMN IF NOT EXISTS paused_at timestamptz;
ALTER TABLE run ADD COLUMN IF NOT EXISTS resumed_at timestamptz;
ALTER TABLE run ADD COLUMN IF NOT EXISTS pause_node_key varchar(128);

COMMENT ON COLUMN run.pause_kind IS
    '暂停类型：paused_fault=设备故障 waiting_human=需人工确认 paused_unknown=板位未知';
COMMENT ON COLUMN run.pause_reason IS
    '暂停原因（给人看的，含错误码和设备编号）';
COMMENT ON COLUMN run.pause_node_key IS
    '停在哪个节点，恢复时从这里继续';

CREATE INDEX IF NOT EXISTS idx_run_paused
    ON run (tenant_id, pause_kind)
    WHERE status = 'paused' AND deleted_at IS NULL;

-- ---------------------------------------------------------------------------
-- 板位盘点记录：UNKNOWN → 人工找到 → 校正
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS plate_location_audit (
    id           bigserial PRIMARY KEY,
    tenant_id    bigint      NOT NULL REFERENCES tenant(id),
    barcode      varchar(64) NOT NULL,
    from_status  varchar(16) NOT NULL,
    to_status    varchar(16) NOT NULL,
    actual_location varchar(128),
    operator     varchar(64),
    note         text,
    run_id       bigint,
    created_at   timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE plate_location_audit IS
    '板位盘点审计：每次 UNKNOWN→KNOWN / LOST 都要留痕。
     为什么必须留痕：板在哪是人去现场看的，系统只是记录结论，出了问题要能追。';

CREATE INDEX IF NOT EXISTS idx_plate_audit_barcode
    ON plate_location_audit (tenant_id, barcode, created_at DESC);

-- ---------------------------------------------------------------------------
-- 资源释放豁免标记
--
-- 暂停时要释放"已占但未用"的资源，但以下几类不能放：
--   position-return  回程承诺（放了别人的板就占进去，回程时死锁）
--   活动设备锁        板可能还在里面
--   抓着板的机械臂    不能松手
-- ---------------------------------------------------------------------------
ALTER TABLE resource_booking ADD COLUMN IF NOT EXISTS release_exempt boolean
    DEFAULT false;

COMMENT ON COLUMN resource_booking.release_exempt IS
    '暂停时是否豁免释放。true=保持占用（回程承诺/活动设备/抓着板的机械臂）';

-- 回程承诺的预约默认豁免
UPDATE resource_booking SET release_exempt = true
WHERE source_type = 'position-return' AND release_exempt = false;
-- ============================================================================
-- 96：schema 补齐（评审 20261102-03 的 P0-3/12/13/14/15）
--
-- 背景：真实库来自开发直连库，**手工加过表和列**，从未回写分片文件。
-- 结果是"仓库 schema 建出的库跑不了代码"——空库建出 101 表，
-- 真实库 107 表，缺 75 个列。
--
-- 本片补：
--   1. 三张从未入仓库的表：sys_user / duration_model / workflow_node_step
--   2. 若干代码在用但 schema 没有的列（device_state.online 等）
--   5. S3 分批执行：experiment.batch_split + experiment_batch / experiment_batch_run
--
-- **幂等**：表用 CREATE TABLE IF NOT EXISTS，列用 ADD COLUMN IF NOT EXISTS，
-- 主键/唯一/外键都写在 CREATE TABLE 内联（不用独立的 ALTER ADD CONSTRAINT，
-- 那种写法在表已存在时会报 "already exists"）。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. sys_user：认证与账号管理（P0-3）
--
-- 代码引用：AuthService.java:73,87,110,138 / QueryService.java:775,807,831
-- 缺这张表 = 登录不可用。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_user (
    id            bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id     bigint       NOT NULL REFERENCES tenant(id),
    username      varchar(64)  NOT NULL,
    display_name  varchar(64),
    role          varchar(32)  NOT NULL DEFAULT 'viewer',
    password_hash text         NOT NULL,
    salt          text         NOT NULL,
    enabled       boolean      NOT NULL DEFAULT true,
    last_login_at timestamptz,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uk_sys_user UNIQUE (tenant_id, username),
    CONSTRAINT ck_sys_user_role CHECK (role IN ('admin', 'operator', 'viewer'))
);

COMMENT ON TABLE sys_user IS
    '系统用户。role: admin=全部 / operator=可执行与处置 / viewer=只读。';
COMMENT ON COLUMN sys_user.password_hash IS
    '口令哈希。**不存明文**——明文只允许出现在初始化脚本的临时变量里';

-- ---------------------------------------------------------------------------
-- 2. duration_model：命令时长统计（仿真预测/甘特都读）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS duration_model (
    tenant_id    bigint       NOT NULL REFERENCES tenant(id),
    method       varchar(128) NOT NULL,
    p50_ms       integer      NOT NULL,
    p90_ms       integer      NOT NULL,
    min_ms       integer      NOT NULL,
    max_ms       integer      NOT NULL,
    sample_count integer      NOT NULL,
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, method)
);

COMMENT ON TABLE duration_model IS
    '命令时长模型：从 run_command 的 sent_at/finished_at 统计。
     仿真预测与甘特预测都读它。';

-- ---------------------------------------------------------------------------
-- 3. workflow_node_step：节点的多步展开
--
-- 一个节点展开成几步（如"取板→移动→放板"），执行监控界面按此展示子步骤。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS workflow_node_step (
    id          bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id   bigint       NOT NULL REFERENCES tenant(id),
    workflow_id bigint       NOT NULL REFERENCES workflow(id) ON DELETE CASCADE,
    node_id     bigint       NOT NULL REFERENCES workflow_node(id) ON DELETE CASCADE,
    seq         integer      NOT NULL,
    method      varchar(128) NOT NULL,
    param_expr  jsonb,
    criteria    jsonb,
    source      varchar(16)  NOT NULL DEFAULT 'compile',
    created_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uk_node_step UNIQUE (workflow_id, node_id, seq)
);

COMMENT ON TABLE workflow_node_step IS
    '节点展开的多步。seq 从 1 开始；执行时 run_node.level=1 与其对应。';
COMMENT ON COLUMN workflow_node_step.source IS
    'compile=编译器展开 / manual=人工补';

-- ---------------------------------------------------------------------------
-- 4. 补列（代码在用、schema 没有）
-- ---------------------------------------------------------------------------

-- device_state：心跳链路（P0-12）。缺这三列心跳完全落不了库。
ALTER TABLE device_state ADD COLUMN IF NOT EXISTS online boolean NOT NULL DEFAULT false;
ALTER TABLE device_state ADD COLUMN IF NOT EXISTS last_heartbeat timestamptz;
ALTER TABLE device_state ADD COLUMN IF NOT EXISTS current_method varchar(128);

-- run：值守模式（P0-13）。QueryService / WorkflowService / ApiServer 都在读。
ALTER TABLE run ADD COLUMN IF NOT EXISTS attendance varchar(16) NOT NULL DEFAULT 'attended';

-- run_command：命令落在哪台设备（P0-14）。verify-m5 与排障都依赖。
ALTER TABLE run_command ADD COLUMN IF NOT EXISTS device_id bigint;

-- run_node：层级与步骤序号。执行监控界面的"子步骤"靠这三列。
ALTER TABLE run_node ADD COLUMN IF NOT EXISTS level smallint NOT NULL DEFAULT 0;
ALTER TABLE run_node ADD COLUMN IF NOT EXISTS parent_node_id bigint;

-- 步骤行（level=1）靠 parent_node_id 指向业务节点，**必须有外键**才级联删除。
-- 线上库早就手工加过，但建库脚本一直漏了 —— 新环境装出来的库没有 FK，
-- 删业务节点时步骤行与 run_command 会变成孤儿（评审 20261103-01 · H1 的同类问题）。
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint
                  WHERE conrelid = 'run_node'::regclass
                    AND conname = 'run_node_parent_node_id_fkey') THEN
    ALTER TABLE run_node
      ADD CONSTRAINT run_node_parent_node_id_fkey
      FOREIGN KEY (parent_node_id) REFERENCES run_node(id) ON DELETE CASCADE;
  END IF;
END $$;

-- 实验版本号：每次「保存编排」+1，列表页显示 v3 能看出改过几版
ALTER TABLE experiment ADD COLUMN IF NOT EXISTS version int NOT NULL DEFAULT 1;

-- 节点的设备绑定与图内顺序落表（M9 前置）：读侧不再每次解析图 XML。
-- 存量数据用 com.labos.kernel.workflow.BindingBackfill 回填（幂等）。
ALTER TABLE workflow_node ADD COLUMN IF NOT EXISTS resource_id bigint;
ALTER TABLE workflow_node ADD COLUMN IF NOT EXISTS seq int;

-- 循环按圈次落行（loop_index=0 汇总 / 1..N 每圈）
ALTER TABLE run_node ADD COLUMN IF NOT EXISTS loop_index smallint NOT NULL DEFAULT 0;
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_constraint
              WHERE conrelid = 'run_node'::regclass AND conname = 'uk_run_node'
                AND pg_get_constraintdef(oid) NOT LIKE '%loop_index%') THEN
    ALTER TABLE run_node DROP CONSTRAINT uk_run_node;
    ALTER TABLE run_node ADD CONSTRAINT uk_run_node UNIQUE (run_id, node_key, attempt, loop_index);
  END IF;
END $$;
ALTER TABLE run_node ADD COLUMN IF NOT EXISTS step_seq integer;

-- experiment：岛与截止时间。调度与甘特都读。
ALTER TABLE experiment ADD COLUMN IF NOT EXISTS island_id bigint;
ALTER TABLE experiment ADD COLUMN IF NOT EXISTS deadline timestamptz;

-- error_code：附加属性。
ALTER TABLE error_code ADD COLUMN IF NOT EXISTS attr jsonb;

COMMENT ON COLUMN device_state.online IS
    '最近一次探活是否通。与 status 是两个维度：online=通不通，status=在干什么';
COMMENT ON COLUMN run.attendance IS
    'attended=有人值守 / unattended=无人值守。无人值守下 MANUAL 升级为 error';
COMMENT ON COLUMN run_node.level IS
    '0=业务节点 1=子步骤。执行监控界面按此分层展示';

-- ---------------------------------------------------------------------------
-- 5. S3 分批执行（M9.5）：实验级批次 + 每批多次尝试
--
-- 背景：一个实验 96 个样品、设备一次只处理 24 个（或一块板），要分 4 批。
-- 批次与 run **不能一对一覆盖**（评审 20261103-03 · B2）：同一批失败后重跑
-- 必须保留历史，所以数据分两层：
--   experiment_batch      = 逻辑批次（第几批、样品快照、当前汇总状态）
--   experiment_batch_run  = 这一批实际跑过的每一次（run_id / attempt_no / status）
--
-- 语义边界（实施清单 §8 · D1-D7）：
--   · experiment.batch_split = 每批样品数；NULL = 不分批（走非分批路径）
--   · 样品快照在**首次执行时**按 experiment_sample.sample_id 升序切分，
--     把该批 [{sampleId,barcode}] 写进 experiment_batch.samples；
--     此后重跑只用快照（样品集合不漂移，§4.6 第二风险）
--   · 每批一个 experiment_run + 一条 experiment_batch_run，attempt_no 从 1 递增；
--     某批失败继续后续批，成功/失败都**不覆盖**历史尝试
--   · 批次之间串行（D7）；批边界检查 RuntimeHold，有扣留就停在下一边界
--
-- 幂等：ADD COLUMN IF NOT EXISTS + CREATE TABLE IF NOT EXISTS + 内联唯一约束，
-- 可重复执行（与 97/98 分片同口径）。
-- ---------------------------------------------------------------------------

-- 批次开关：每批样品数；NULL = 不分批（D1：加列即可开关，无需数据迁移）
ALTER TABLE experiment ADD COLUMN IF NOT EXISTS batch_split int;

COMMENT ON COLUMN experiment.batch_split IS
    '每批样品数；NULL=不分批（走非分批路径）。首次执行时按 sample_id 升序切成 N 个逻辑批；'
    '改这个值不会重切已建批次（快照固化，D2）。';

-- ---------------------------------------------------------------------------
-- 5.1 逻辑批次
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS experiment_batch (
    id            bigint       GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id     bigint       NOT NULL REFERENCES tenant(id),
    experiment_id bigint       NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    batch_no      int          NOT NULL,                        -- 从 1 开始
    sample_count  int          NOT NULL DEFAULT 0,
    samples       jsonb        NOT NULL DEFAULT '[]'::jsonb,     -- [{sampleId,barcode}] 快照
    status        varchar(24)  NOT NULL DEFAULT 'pending',       -- 见下方 COMMENT
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uk_experiment_batch UNIQUE (tenant_id, experiment_id, batch_no)
);

COMMENT ON TABLE experiment_batch IS
    '实验逻辑批次（M9.5 S3）。一个实验按 batch_split 切成 N 批，每批可单独重跑；
     批次是**主语**，实际跑没跑、跑了几次看 experiment_batch_run。
     状态词表沿用现有：pending/queued/running/paused/completed/failed/aborted。';

COMMENT ON COLUMN experiment_batch.samples IS
    '首次执行时的样品快照：[{sampleId,barcode}, ...]（按 sample_id 升序）。
     重跑只用这份快照，不再读 experiment_sample —— 样品集合不漂移（D2）。';

COMMENT ON COLUMN experiment_batch.status IS
    '当前汇总状态：取该批**最近一次尝试**的终态；还没跑过 = pending。
     重跑不覆盖历史尝试，历史在 experiment_batch_run。';

-- 按实验整体读取批次（批次视图/建批幂等）走这个索引。
CREATE INDEX IF NOT EXISTS ix_experiment_batch_exp
    ON experiment_batch (tenant_id, experiment_id, batch_no);

-- ---------------------------------------------------------------------------
-- 5.2 每次尝试
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS experiment_batch_run (
    id                bigint       GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id         bigint       NOT NULL REFERENCES tenant(id),
    batch_id          bigint       NOT NULL REFERENCES experiment_batch(id) ON DELETE CASCADE,
    experiment_run_id bigint       NOT NULL REFERENCES experiment_run(id) ON DELETE CASCADE,
    attempt_no        int          NOT NULL,                    -- 从 1 递增
    status            varchar(24)  NOT NULL DEFAULT 'pending',
    started_at        timestamptz,
    finished_at       timestamptz,
    error             text,
    CONSTRAINT uk_experiment_batch_run UNIQUE (batch_id, attempt_no)
);

COMMENT ON TABLE experiment_batch_run IS
    '批次的一次实际执行（M9.5 S3）。同一批重跑会新增一行，attempt_no 递增，
     历史行及其 experiment_run_id 不动 —— 这是"重跑不覆盖历史"的落库形态（评审 B2）。';

COMMENT ON COLUMN experiment_batch_run.experiment_run_id IS
    '本次尝试对应的 experiment_run。一个 run 只属于一条尝试（每批一次 run）。';

CREATE INDEX IF NOT EXISTS ix_experiment_batch_run_batch
    ON experiment_batch_run (tenant_id, batch_id, attempt_no);
-- 运行控制（暂停/停止/恢复）拿到 run 时要能反查它属于哪一批。
CREATE INDEX IF NOT EXISTS ix_experiment_batch_run_run
    ON experiment_batch_run (tenant_id, experiment_run_id);
-- ============================================================================
-- M9.5：实验编排统一 —— 流程之间偏序的迁移边界
--
-- 背景（为什么必须现在加）：
--   `experiment_process.seq` 只能表达**线性顺序**，而画布上已经能画分叉/并行。
--   S1 已把执行收口到 `experiment_process`（唯一事实来源），但"流程之间到底是什么
--   结构"仍然只能靠 seq 猜。要让执行器从"按 seq 逐个跑"改成"按依赖派发"，
--   必须先有一条**真被读的**依赖数据，而不是再加一个没人读的字段。
--
--   反面教材就在本表旁边：`experiment_process.run_after`（依赖的 seq）写了、
--   API 也返回了，但执行器从来没读过 —— "模型先行、执行没跟上"。
--   所以这张表的落地口径是：**加了就必须被读**（ExperimentExecutor 读它求偏序）。
--
-- 语义边界（S2-A）：
--   · 只表达**偏序**（谁必须在谁之前），不表达并发度、不表达资源。
--   · 执行器当前按拓扑序**物理串行**推进：一次只跑一个可执行节点，
--     不承诺墙钟并发。要真并发必须先接"三层资源 + 等待图"，否则会互相锁死。
--   · 边是"前驱 -> 后继"（from 完成后 to 才可派发）；汇聚 = 所有前驱都完成。
--   · 线性实验 = 一条链（n 个流程 = n-1 条边）。没有边的数据按 seq 线性处理。
--
-- 幂等：CREATE TABLE IF NOT EXISTS + 内联约束，可重复执行。
-- ============================================================================

CREATE TABLE IF NOT EXISTS experiment_process_edge (
    tenant_id     bigint      NOT NULL,
    experiment_id bigint      NOT NULL,
    from_id       bigint      NOT NULL REFERENCES experiment_process(id) ON DELETE CASCADE,
    to_id         bigint      NOT NULL REFERENCES experiment_process(id) ON DELETE CASCADE,
    created_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (from_id, to_id)
);

COMMENT ON TABLE experiment_process_edge IS
    '实验内流程之间的偏序（前驱 -> 后继）。from 完成后 to 才可派发；
     汇聚 = 所有前驱都完成。线性实验 = 一条链（n 个流程 n-1 条边）。
     S2-A 只承诺偏序，执行仍物理串行；真并发需另接三层资源 + 等待图。';
COMMENT ON COLUMN experiment_process_edge.from_id IS
    '前驱流程（experiment_process.id）。整体替换编排时随 process 行级联删除';
COMMENT ON COLUMN experiment_process_edge.to_id IS
    '后继流程（experiment_process.id）';

-- 按实验整体读取边（执行器求拓扑序）与整体替换（画布保存）都走这个索引。
CREATE INDEX IF NOT EXISTS ix_exp_proc_edge_exp
    ON experiment_process_edge (tenant_id, experiment_id);
-- ============================================================================
-- M9.5：实验级暂停 / 停止 / 恢复 —— 作用域扣留（runtime hold）
--
-- 背景（为什么不能只做"单实验暂停"）：
--   一个岛通常只有一台机械臂，多个实验并行时共用同一批设备。现场任何一次
--   暂停（设备故障、板位未知、转人工）都是**安全事件**，不是某一个实验的私事：
--   此时其他在跑的实验必须一起停在动作边界，否则机械臂会分不清板子归属。
--
--   所以暂停不能挂在单个 `experiment_run` 上就算了，必须有一个**作用域**概念：
--     site       全场（最严重，比如公共气路故障）
--     island     岛级（默认：一个实验暂停 -> 同岛其他实验一起停）
--     experiment 仅该实验
--
-- 为什么要单独一张表而不是往 run / experiment_run 加列：
--   扣留是"现场状态"，不是"某条记录的字段"。一个岛被扣留期间，可能有 0 条、
--   1 条或多条 experiment_run 正在跑，也可能没人在跑（只是禁止新派发）。
--   挂在 run 上就表达不了"这段时间不许再派发新实验"。
--
-- 语义边界：
--   · 扣留**只协作式生效**：执行器在**动作边界**（下一步开始前）检查，
--     正在执行的动作不会被硬杀 —— 机械臂抓着板不能松手。
--   · 扣留**不释放资源**（与流程级 PauseManager 的"释放已占未用"相反）：
--     现场被扣住，板位必须原样保留，否则恢复时全是错的。
--   · 解除扣留必须由人显式触发（处置完事故），系统不猜"是不是好了"。
--
-- 幂等：CREATE TABLE IF NOT EXISTS + ADD COLUMN IF NOT EXISTS，可重复执行。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1) 作用域扣留
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS runtime_hold (
    id           bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id    bigint      NOT NULL REFERENCES tenant(id),
    scope        varchar(16) NOT NULL,          -- site | island | experiment
    scope_id     bigint,                        -- island_id / experiment_id；site 为 NULL
    kind         varchar(24) NOT NULL,          -- 扣留类型（见 COMMENT）
    reason       text,                          -- 给人看的原因（含错误码/设备编号）
    held_at      timestamptz NOT NULL DEFAULT now(),
    held_by      bigint,                        -- 谁扣的（用户 id；系统自动扣留为 NULL）
    released_at  timestamptz,                   -- 解除时间；NULL = 仍生效
    released_by  bigint,
    release_note text
);

COMMENT ON TABLE runtime_hold IS
    '作用域扣留：现场级暂停。scope=site/island/experiment 决定影响范围，
     同一 scope 同时最多一条生效（released_at IS NULL）。执行器在动作边界检查，
     只协作式停止，不硬杀在执行的动作；扣留期间不释放资源、不派发新实验。';

COMMENT ON COLUMN runtime_hold.scope IS
    '生效范围：site=全场 island=岛级（默认） experiment=仅该实验';

COMMENT ON COLUMN runtime_hold.scope_id IS
    '范围 id：island 时是 island.id，experiment 时是 experiment.id，site 时为 NULL';

COMMENT ON COLUMN runtime_hold.kind IS
    '扣留类型：fault=设备故障 plate_unknown=板位未知 human=需人工确认 manual=人工主动暂停';

COMMENT ON COLUMN runtime_hold.released_at IS
    '解除时间；NULL 表示仍生效。解除只能由人显式触发，系统不自动猜。';

-- 同一 (租户, 范围, 范围 id) 同时只能有一条生效的扣留。
-- scope_id 可空，用 COALESCE 让 site 级扣留（scope_id=NULL）也能进唯一索引。
CREATE UNIQUE INDEX IF NOT EXISTS uk_runtime_hold_active
    ON runtime_hold (tenant_id, scope, COALESCE(scope_id, 0))
    WHERE released_at IS NULL;

-- 执行器/调度器每步都要查"我这条实验被扣了吗"，走这个索引。
CREATE INDEX IF NOT EXISTS ix_runtime_hold_active
    ON runtime_hold (tenant_id, released_at);

-- ---------------------------------------------------------------------------
-- 2) experiment_run 控制列
--
-- 状态取值（experiment_run.status，varchar(16)，不改成枚举）：
--   queued     已提交/已放行，等调度
--   running    正在执行
--   paused     被扣留/人工暂停，保留现场，可恢复
--   completed  全部流程完成（终态）
--   failed     某步失败，整单停（**可恢复**：人处理完后从失败步重试）
--   aborted    人工主动停止（终态）
--   cancelled  排队中被取消，从未开始（终态）
-- ---------------------------------------------------------------------------
ALTER TABLE experiment_run ADD COLUMN IF NOT EXISTS pause_kind varchar(24);
ALTER TABLE experiment_run ADD COLUMN IF NOT EXISTS pause_reason text;
ALTER TABLE experiment_run ADD COLUMN IF NOT EXISTS paused_at timestamptz;
ALTER TABLE experiment_run ADD COLUMN IF NOT EXISTS resumed_at timestamptz;
ALTER TABLE experiment_run ADD COLUMN IF NOT EXISTS stop_requested boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN experiment_run.pause_kind IS
    '暂停类型：fault / plate_unknown / human / manual；与 runtime_hold.kind 同口径';
COMMENT ON COLUMN experiment_run.pause_reason IS
    '暂停原因（给人看的，与所属 runtime_hold.reason 一致）';
COMMENT ON COLUMN experiment_run.stop_requested IS
    '人工停止请求：执行器在下一个动作边界看到它就把实验置 aborted。协作式，不硬杀';

COMMENT ON TABLE experiment_run IS
    '实验执行：一次实验跑的记录。status: queued/running/paused/completed/failed/aborted/cancelled。
     暂停是协作式的：执行器只在步骤边界停，正在执行的动作（如机械臂抓板）会等它跑完。
     断点续跑：已有的 experiment_run_step(status=completed) 记录会被跳过，从失败步继续。';
-- ============================================================================
-- 台账 6.5：有人值守窗口（manual window）
--
-- 背景：规格 §1.1 门槛 2 / §6 要求「有人值守出错时弹窗口，默认项来自策略」。
-- 2.0 的 run 落 paused / waiting_human 后，只靠暂停队列页等人，窗口本身
-- 没有落库状态 —— 页面刷新就没了、进程重启也丢了，更记不下「谁在什么时候
-- 按哪个默认项做的决定」。这张表就是那段状态。
--
-- 语义边界（见 决策记录.md 2026-11-05 条）：
--   · 窗口只对应「该 run 的 waiting_human 暂停态」，不是全场闸门；
--     执行线程在 settleRun 后即返回，不占岛锁、不阻塞调度器。
--   · 无人值守不写窗口（只记 error 告警）。
--   · 超时只把 status 置 timed_out，run 仍保持 paused，绝不自动替人决定。
--   · 默认项来自 recovery_policy.strategy，不是恒为 MANUAL 的决策结果。
--
-- run_id 故意不加外键：窗口可能指向 run 或 experiment_run，两者是**不同
-- 的自增 id 空间**（见 RunControlDbSelfTest 注释里的 23503 事故）。
--
-- 幂等：CREATE TABLE / INDEX IF NOT EXISTS，可重复执行。
-- ============================================================================

CREATE TABLE IF NOT EXISTS manual_window (
    id              bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id       bigint       NOT NULL REFERENCES tenant(id),
    run_id          bigint       NOT NULL,
    window_key      varchar(255) NOT NULL,   -- runId|errorCode|nodeKey，幂等键
    error_code      varchar(64),
    node_key        varchar(64),
    strategy        varchar(32),             -- 命中的 recovery_policy.strategy
    default_action  varchar(32)  NOT NULL,   -- 弹窗默认项
    allowed_actions jsonb        NOT NULL DEFAULT '[]'::jsonb,
    decision_reason text         NOT NULL,   -- 为什么停着（必须可解释）
    status          varchar(16)  NOT NULL DEFAULT 'pending',
    opened_at       timestamptz  NOT NULL DEFAULT now(),
    timeout_at      timestamptz,
    decided_at      timestamptz,
    decided_by      varchar(64),
    decided_action  varchar(32),
    note            text
);

COMMENT ON TABLE manual_window IS
    '有人值守窗口：出错转人工时落一条待决记录，前端轮询后弹出；status: pending/decided/timed_out/expired。超时只标记，不改变 run 的 paused 状态。';
COMMENT ON COLUMN manual_window.default_action IS
    '默认项来自 recovery_policy.strategy；无匹配策略或策略被禁用时为 MANUAL。';
COMMENT ON COLUMN manual_window.window_key IS
    'runId|errorCode|nodeKey；同一 run 的同一错误只开一扇 pending 窗口。';

-- 部分唯一索引：并发 tick 重复开窗时由库兜底（Store 里另有 WHERE NOT EXISTS）
CREATE UNIQUE INDEX IF NOT EXISTS uk_manual_window_open
    ON manual_window (tenant_id, window_key) WHERE status = 'pending';
CREATE INDEX IF NOT EXISTS ix_manual_window_pending
    ON manual_window (tenant_id, opened_at DESC) WHERE status = 'pending';
-- ============================================================================
-- 超时按方法取：补齐各能力默认超时（数据回填，幂等）
--
-- 背景（2026-11-06）：
--   `DriverStepExecutor` 改动前用**执行器级固定 30 秒**超时，
--   而 `device_method.timeout_sec` / `workflow_node.timeout_sec`
--   两张表里**存了超时值，驱动层从来不读**
--   （`grep timeout_sec` 在 driver/ 下零命中）——
--   后果是**任何耗时超过 30 秒的真机方法都被中控判超时**：
--   离心 5 分钟、PCR 几十分钟、孵育 30 分钟全部必挂，与"暂停"无关，是纯 bug。
--
--   改动后取值优先级（见 MethodTimeoutPolicy）：
--     设备方法级 → 设备能力级 → 流程节点级 → **能力默认** → 兜底 30 秒
--   本分片负责补齐第三层缺失的一环：**能力默认**。
--
-- 为什么只填 `timeout_sec = 0` 的行：
--   设备库里那批 3s / 5s / 20s / 302s 是**真机契约**（按「预计耗时 × 2」算的），
--   抬高等于把"扫码机死了"从 3 秒才发现变成 10 分钟才发现 —— 必须原样保留。
--
-- 为什么保底 600 秒：
--   超时是「设备不回话」的兜底，不是正常耗时。
--   10 分钟够覆盖真机长工艺，又不会把整单无限期挂死；
--   同时兜住演示岛仿真机「方法耗时随机 30–300 秒」的量级（留足余量不撞线）。
--   长工艺另给更长：孵育 30 分钟、扩增/PCR 60 分钟、离心/洗板 15 分钟。
--
-- 全部幂等：可重复执行。
-- ============================================================================

-- ① 按能力补齐空缺（只动 timeout_sec = 0 的行，显式值不动）
--
-- ⚠ 为什么用 CTE 而不是 UPDATE ... FROM (VALUES) AS d JOIN capability c：
--   Postgres **不允许在 FROM 子句的 JOIN 条件里引用目标表**（dm），
--   那样写会报 "invalid reference to FROM-clause entry for table dm"。
--   所以先在 CTE 里把「能力编码 → 超时」和「能力编码 → capability.id」接好，
--   再拿它去 UPDATE。
WITH defs(cap_code, timeout_sec) AS (VALUES
        ('INCUBATE', 1800),      -- 孵育：30 分钟
        ('THERMOCYCLE', 3600),  -- 扩增/PCR：60 分钟
        ('CENTRIFUGE', 900),    -- 离心：15 分钟
        ('WASH', 900),          -- 分液洗板：15 分钟
        ('DECAP', 600),         -- 开盖
        ('PEEL', 600),          -- 撕膜
        ('SEAL', 600),          -- 封膜
        ('ALIQUOT', 600),       -- 移液分装
        ('ANALYZE', 600),       -- 流式分析
        ('TRANSFER', 600),      -- 转运
        ('READ_PLATE', 600),    -- 读板
        ('STORE', 600),         -- 存储
        ('PICK', 600),          -- 挑克隆
        ('SCAN', 600),          -- 扫码
        ('SENSE', 600),         -- 光电检测
        ('MIX', 600)            -- 混匀/振荡
     ),
     cap AS (
         SELECT c.tenant_id, c.id AS capability_id, d.timeout_sec
           FROM capability c
           JOIN defs d ON d.cap_code = c.code
     )
UPDATE device_method dm
   SET timeout_sec = cap.timeout_sec,
       updated_at  = now()
  FROM cap
 WHERE dm.tenant_id = cap.tenant_id
   AND dm.capability_id = cap.capability_id
   AND dm.timeout_sec = 0;

-- ② 没有能力登记的行（capability_id 为空）给通用保底 600 秒
UPDATE device_method dm
   SET timeout_sec = 600,
       updated_at  = now()
 WHERE dm.timeout_sec = 0
   AND dm.capability_id IS NULL;
-- ============================================================================
-- A1「设备执行中真停」：device_method 增 interruptible（可中断）标志
--
-- 背景（2026-11-06）：
--   现状是**暂停只停在动作边界**（GraphRunner 的 pauseChecker 在 executeNode 之前查），
--   设备方法执行期间线程阻塞在等回包 ⇒ 暂停对正在执行的设备动作**完全无效**。
--   用户现场诉求：「设备正在执行的方法会继续，只有机械臂收到暂停指令后会当前停止」。
--   查清了：**机械臂也不会停**（那是对「资源豁免」的误读，见方案文档 §2.3）。
--
--   真正的实现分两层：
--   ① **能不能停**由方法自己声明（这张表的 interruptible 列）——
--      机械臂搬运（Transfer/Grip/Release/Load/Unload）**不能停**（板会掉），
--      长工艺（离心/孵育/PCR/读板…）**可以停**。
--   ② 执行侧在等回包时轮询「是否该停」，命中就调 driver.abort(commandId)。
--
-- 为什么默认 false（安全默认）：
--   **没有现场清单之前，一律按"不可中断"处理** —— 行为与改动前完全一致，
--   绝不会因为这个开关把正在搬板的机械臂停掉。
--   现场确认清单后，把对应方法置 true 即可生效（不必改代码）。
--
-- 全部幂等。
-- ============================================================================

ALTER TABLE device_method ADD COLUMN IF NOT EXISTS interruptible boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN device_method.interruptible IS
    '该方法在设备执行中能否被暂停打断：false=不可中断（机械臂搬运/会掉板的动作，默认）；'
    'true=可中断（离心/孵育/PCR 等长工艺，中断后从这一步重跑）';
-- ============================================================================
-- L-02：控制状态与生命周期分离（ADR-PAUSE-008）
--
-- 背景：experiment_run.status 现在同时承载「生命周期」与「暂停过程」两件事，
--   paused 还被复用为「等待人工」。把中间态（PAUSE_REQUESTED / PAUSING /
--   RESUMING）塞进同一列，会让 status 变成随时间累积的多义字段，而它的读方
--   （页面、聚合器、门禁）远多于新的 control_state（S-04B §3.1）。
--
-- 决策（ADR-PAUSE-008，方案 B）：
--   · status 只表达生命周期，取值不变：queued/running/paused/completed/failed/aborted/cancelled
--   · 新增 control_state 表达暂停控制过程：NONE/PAUSE_REQUESTED/PAUSING/RESUMING
--   · status 列长扩容：WAITING_INTERVENTION（20 字符）现有 varchar(16) 放不下
--
-- 语义边界（本批次只授予「状态表达能力」，不改行为）：
--   本分片不改变 status 何时变为 paused（legacy 立即投影保持），故 PR-S04-A /
--   4.17 / 4.18 判据不退化；「请求已接受但未收敛时不得显示 PAUSED」的行为切换
--   属 PR-S04-B。
--
-- 幂等：类型扩容与 ADD COLUMN IF NOT EXISTS 均可重复执行。
-- ============================================================================

-- 1) 生命周期列扩容（varchar(16) -> varchar(32)）
ALTER TABLE experiment_run ALTER COLUMN status TYPE varchar(32);

-- 2) 控制状态列（默认 NONE；取值由 PR-S04-B 接线）
ALTER TABLE experiment_run ADD COLUMN IF NOT EXISTS control_state varchar(24) NOT NULL DEFAULT 'NONE';

COMMENT ON COLUMN experiment_run.control_state IS
    '暂停控制过程（与 status 生命周期分离）：NONE=无 / PAUSE_REQUESTED=请求已受理 / PAUSING=等待在途动作收敛 / RESUMING=恢复中。默认 NONE；取值由 PR-S04-B 接线。';

COMMENT ON COLUMN experiment_run.status IS
    '实验生命周期：queued=待调度 / running=执行中 / paused=已暂停（含等待人工）/ completed=完成 / failed=失败（可续跑）/ aborted=人工停止（终态）/ cancelled=排队取消（终态）。列长 32 以容纳 WAITING_INTERVENTION 等新码；暂停控制过程见 control_state（ADR-PAUSE-008）。';
-- ============================================================================
-- V1-5：暂停请求幂等键（ADR-PAUSE-009）
--
-- 背景：`PauseCoordinator`（S-02 / P-06）把暂停/恢复/停止/忽略统一收敛到一个适配层。
--   这些动作不是「改一个字段」那么简单：暂停会抬一道 `runtime_hold` 并置
--   `experiment_run.paused`，恢复会解除扣留并重新入队。客户端在网络抖动时重发
--   同一个请求（HTTP 重试、双击、上游超时重放）不能把扣留抬两遍、把恢复下发两次。
--
-- 决策（ADR-PAUSE-009，取更完善方案，非「复用 runtime_hold + 审计」）：
--   独立 `pause_request` 表承载幂等键。语义：同 `request_id` 重复请求
--   ⇒ 不重复建 Hold / 不重复下发，直接回放首次结果（幂等可查）。
--   幂等键来自 `X-Request-Id` 头；取不到时该请求不参与去重（保持旧行为，语义不变）。
--
-- 与 ADR-PAUSE-005 同源：`pause_request` 是「幂等键去重」的落库载体。
--
-- 幂等：CREATE TABLE IF NOT EXISTS + CREATE UNIQUE INDEX IF NOT EXISTS，可重复执行。
-- ============================================================================

CREATE TABLE IF NOT EXISTS pause_request (
    id            bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id     bigint      NOT NULL REFERENCES tenant(id),
    request_id    varchar(64) NOT NULL,           -- 幂等键（来自 X-Request-Id）
    target_type   varchar(24) NOT NULL,           -- WORKFLOW_RUN | EXPERIMENT_RUN
    target_id     bigint      NOT NULL,           -- run.id / experiment_run.id
    action        varchar(16) NOT NULL,           -- PAUSE | RESUME | STOP | DISMISS
    source        varchar(32),                    -- 请求来源（api / ...）
    kind          varchar(32),                    -- 动作子类型码（PAUSE: 暂停类型；RESUME: 恢复动作）
    reason        text,                           -- 给人看的原因
    requested_by  varchar(64),                    -- 操作者（V1 未接会话，可空）
    requested_at  timestamptz NOT NULL DEFAULT now(),
    result_state  varchar(16) NOT NULL DEFAULT 'ACCEPTED',  -- ACCEPTED|COMPLETED|REJECTED|UNKNOWN
    result_detail text,                           -- 回放首次结果的关键字段（k=v&k=v 编码）
    created_at    timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE pause_request IS
    '暂停/恢复/停止/忽略 请求的幂等键台账（ADR-PAUSE-009）。同 (tenant_id, request_id)
     只允许一条：首次请求执行并回写 result_state；重复请求直接回放首次结果，
     不重复建 runtime_hold、不重复下发设备动作。request_id 取自 X-Request-Id。';

COMMENT ON COLUMN pause_request.request_id IS
    '幂等键，来自 X-Request-Id 头；缺省时该请求不入表（等价旧行为，不参与去重）。';

COMMENT ON COLUMN pause_request.target_type IS
    '目标运行模型：WORKFLOW_RUN=流程级 run，EXPERIMENT_RUN=实验级 experiment_run。';

COMMENT ON COLUMN pause_request.action IS
    '控制动作：PAUSE / RESUME / STOP / DISMISS（与 PauseCommand.Action 同口径）。';

COMMENT ON COLUMN pause_request.result_state IS
    '首次请求的处置结果：ACCEPTED=已受理 / COMPLETED=已完成 / REJECTED=被拒 / UNKNOWN=无法确认。';

COMMENT ON COLUMN pause_request.result_detail IS
    '回放首次结果的关键字段（k=v&k=v 编码，如 releasedResources=2&pauseKind=waiting_human），
     供重复请求原样回放；缺失键由回放侧取安全缺省。';

-- 幂等键唯一：同一租户下同一个 request_id 只允许一条。
CREATE UNIQUE INDEX IF NOT EXISTS uk_pause_request
    ON pause_request (tenant_id, request_id);

-- 按目标与时间排障用（排查「某条 run 被谁、什么时候、以什么 request_id 操作过」）。
CREATE INDEX IF NOT EXISTS ix_pause_request_target
    ON pause_request (tenant_id, target_type, target_id, created_at DESC);
-- ============================================================================
-- P-06 V2 / P-07 第一切片：暂停事件 Outbox（单事务写入，定时派发）
--
-- 目标（ADR-PAUSE-007 / LABOS_PAUSE_COORDINATOR_V1_DESIGN §5）：
--   暂停受理事务里除了 runtime_hold / control_state / pause_request 之外，
--   还把「发生了什么事件」写进本表（同一事务），由 PauseEventOutbox 异步派发。
--   这样外部观察者/审计不会出现「Hold 在、事件丢」的不一致。
--
-- 本切片只保证「单事务 + 幂等派发」；对接真实事件总线/审计中心是后续 V2.1。
-- ============================================================================

CREATE TABLE IF NOT EXISTS pause_event (
    id            BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL,
    request_id    VARCHAR(128),
    event_type    VARCHAR(32)  NOT NULL,
    target_type   VARCHAR(16)  NOT NULL,
    target_id     BIGINT       NOT NULL,
    scope         VARCHAR(16),
    payload       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    dispatched_at TIMESTAMPTZ,
    attempts      INT          NOT NULL DEFAULT 0,
    last_error    VARCHAR(256)
);

COMMENT ON TABLE pause_event IS
    '暂停控制事件 Outbox（P-06 V2 第一切片）：与 Hold/control_state/pause_request 同事务写入，PauseEventOutbox 异步派发';

CREATE INDEX IF NOT EXISTS ix_pause_event_pending
    ON pause_event (tenant_id, id)
    WHERE dispatched_at IS NULL;
-- ============================================================================
-- P-07 第一切片：设备命令账本（DISPATCH_STARTED / ACKED / FAILED / UNKNOWN）
--
-- 目标（P-07）：派发前先落账，派发后回写结果；崩溃重启后把「派发中断」的
-- 行标 UNKNOWN，禁止在没有人工确认的情况下重复下发同一条物理命令。
-- 本切片只做「单条指令的账本 + 重启对账」；多指令尝试/Outbox 传输层是 P-07.2。
-- ============================================================================

CREATE TABLE IF NOT EXISTS command_ledger (
    id              BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id       BIGINT       NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    run_id          BIGINT       NOT NULL,
    node_id         BIGINT,
    attempt         INT,
    step_seq        INT,
    device_id       BIGINT,
    method          VARCHAR(64),
    command_id      VARCHAR(160),
    trace_id        VARCHAR(64),
    state           VARCHAR(16)  NOT NULL,
    error_code      VARCHAR(32),
    error_message   VARCHAR(256),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_command_ledger_key UNIQUE (tenant_id, idempotency_key)
);

COMMENT ON TABLE command_ledger IS
    '设备命令账本（P-07 第一切片）：派发前落 DISPATCH_STARTED、回包后 ACKED/FAILED，重启把陈旧派发标 UNKNOWN';

CREATE INDEX IF NOT EXISTS ix_command_ledger_run
    ON command_ledger (tenant_id, run_id);

CREATE INDEX IF NOT EXISTS ix_command_ledger_open
    ON command_ledger (tenant_id, state)
    WHERE state IN ('DISPATCH_STARTED', 'UNKNOWN');
-- ============================================================================
-- P-06 V2 收尾：PAUSING 异步中间态（2026-11-08 第四切片）
--
-- 受理时写 control_state=PAUSE_REQUESTED + pause_requested_at=now()；
-- 调度器每 tick 把「已受理超过 5 秒、仍在跑」的 run 标成 PAUSING（可观测中间态），
-- 真正收敛（paused/NONE）仍由执行器在动作边界 settle 完成。
-- ============================================================================

ALTER TABLE experiment_run
    ADD COLUMN IF NOT EXISTS pause_requested_at TIMESTAMPTZ;

COMMENT ON COLUMN experiment_run.pause_requested_at IS
    '暂停受理时间（P-06 V2 PAUSING 中间态判据；settle/resume/converge 时清空）';

CREATE INDEX IF NOT EXISTS ix_experiment_run_pausing
    ON experiment_run (tenant_id, pause_requested_at)
    WHERE control_state = 'PAUSE_REQUESTED';
-- ============================================================================
-- P-07.2：UNKNOWN 命令的人工核对处置（2026-11-08）
-- 账本行被启动对账标 UNKNOWN 后，必须由人确认物理结果，再落 ACKED/FAILED。
-- ============================================================================

ALTER TABLE command_ledger
    ADD COLUMN IF NOT EXISTS resolved_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS resolved_by BIGINT;
-- ============================================================================
-- N-10：Experiment↔Workcell 显式绑定（2026-11-08）
--
-- ADR-ISLAND-002 要求跨 Workcell 实验必须有显式绑定与 Handoff；此前只能从资源树
-- 推导。本表为显式绑定，存在时作为权威来源（为空时回退资源树推导 + experiment.island_id）。
-- ============================================================================

CREATE TABLE IF NOT EXISTS experiment_workcell (
    id            BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id     BIGINT      NOT NULL,
    experiment_id BIGINT      NOT NULL,
    workcell_id   BIGINT      NOT NULL,
    source        VARCHAR(16) NOT NULL DEFAULT 'binding',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_experiment_workcell UNIQUE (tenant_id, experiment_id, workcell_id),
    CONSTRAINT experiment_workcell_experiment_fkey
        FOREIGN KEY (experiment_id) REFERENCES experiment(id) ON DELETE CASCADE
);

COMMENT ON TABLE experiment_workcell IS
    'N-10：实验显式 Workcell 绑定；存在时作为故障影响闭包权威来源，为空回退资源树推导';

CREATE INDEX IF NOT EXISTS ix_experiment_workcell_exp
    ON experiment_workcell (tenant_id, experiment_id);
-- ============================================================================
-- N-10：跨 Workcell Handoff 配置（2026-11-09）
--
-- ADR-ISLAND-002/004：跨 Workcell 实验必须显式声明 Handoff（源/目标 Workcell），
-- 且运行时确认缺失/UNKNOWN 必须阻断。本表先落配置；执行门禁由 HandoffGate 检查。
-- ============================================================================

CREATE TABLE IF NOT EXISTS experiment_handoff (
    id              BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id       BIGINT      NOT NULL,
    experiment_id   BIGINT      NOT NULL,
    from_workcell_id BIGINT     NOT NULL,
    to_workcell_id   BIGINT     NOT NULL,
    carrier         VARCHAR(32) NOT NULL DEFAULT 'manual',
    status          VARCHAR(16) NOT NULL DEFAULT 'configured',
    remark          TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_experiment_handoff
        UNIQUE (tenant_id, experiment_id, from_workcell_id, to_workcell_id),
    CONSTRAINT experiment_handoff_experiment_fkey
        FOREIGN KEY (experiment_id) REFERENCES experiment(id) ON DELETE CASCADE
);

COMMENT ON TABLE experiment_handoff IS
    'N-10 跨 Workcell Handoff 配置；执行前由 HandoffGate 校验，missing/unknown 阻断';

CREATE INDEX IF NOT EXISTS ix_experiment_handoff_exp
    ON experiment_handoff (tenant_id, experiment_id);
-- ============================================================================
-- L-01：人工介入记录（InterventionRecord，2026-11-09）
--
-- 每次人工窗口决策/Handoff 确认都落一条：确认人、时间、板/样本标识、源/目标
-- 交接点、决策动作与结果。与 manual_window 关联但不做硬外键（允许独立补录）。
-- ============================================================================

CREATE TABLE IF NOT EXISTS intervention_record (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id        BIGINT      NOT NULL,
    manual_window_id BIGINT,
    run_id           BIGINT,
    experiment_run_id BIGINT,
    operator         VARCHAR(64) NOT NULL,
    decided_action   VARCHAR(32),
    plate_barcode    VARCHAR(64),
    sample_id        VARCHAR(64),
    from_location    VARCHAR(128),
    to_location      VARCHAR(128),
    result           VARCHAR(32) NOT NULL,
    note             TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE intervention_record IS 'L-01 人工介入记录：人/时间/板样本标识/交接点/结果';

CREATE INDEX IF NOT EXISTS ix_intervention_record_window
    ON intervention_record (tenant_id, manual_window_id);
CREATE INDEX IF NOT EXISTS ix_intervention_record_run
    ON intervention_record (tenant_id, run_id);
