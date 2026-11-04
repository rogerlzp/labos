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
