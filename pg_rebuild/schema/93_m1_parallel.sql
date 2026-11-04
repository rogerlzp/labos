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
