-- 设备事件类型字典（2026-11-15）
--
-- **为什么要有这张表**：事件类型字串（waiting_interaction / process_done / …）
-- 之前只活在**代码里**：
--   · 侧车 `ISpontaneousTranslator` 的码表（翻译层把厂商帧转成这个字串）
--   · 内核 `GraphRunner.__awaitEvent` 的参数（流程节点里手打的自由文本）
-- 两边各写各的 ⇒ **字串对不上就是静默失效**：上位机永远等不到那个事件，
-- 现场看到的只是"卡住不动"，没有任何报错指向真凶。
--
-- ⇒ 把它变成**配置**：字串存在库里，设计器从字典下拉选，
-- 侧车与现场配置人员按这张表约定。改一个字串不用改代码。
--
-- 可重复执行（IF NOT EXISTS）。

CREATE TABLE IF NOT EXISTS device_event_type (
    id            bigserial PRIMARY KEY,
    tenant_id     bigint       NOT NULL REFERENCES tenant(id),
    -- 事件类型字串：侧车产出、内核匹配、流程节点引用，三方对齐的唯一键
    event_code    varchar(64)  NOT NULL,
    display_name  varchar(128) NOT NULL,
    -- 语义分类（界面分组用，不是行为开关）：
    --   interaction = 需要上位机响应（等待交互类）
    --   progress    = 进度播报（不打断）
    --   terminal    = 终态（完成/结束）
    --   fault       = 故障
    category      varchar(16)  NOT NULL DEFAULT 'progress',
    -- 是否需要人工决策（true = 上位机收到后应开人工窗口）
    needs_action  boolean      NOT NULL DEFAULT false,
    -- 展示排序（小的在前）
    sort_order    int          NOT NULL DEFAULT 100,
    enabled       boolean      NOT NULL DEFAULT true,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uk_device_event_type UNIQUE (tenant_id, event_code),
    CONSTRAINT ck_device_event_type_category
        CHECK (category IN ('interaction','progress','terminal','fault'))
);

COMMENT ON TABLE device_event_type IS
  '设备事件类型字典。event_code 是侧车产出、内核匹配、流程节点引用三方对齐的唯一键 ——'
  ' 改字串不改代码。';
COMMENT ON COLUMN device_event_type.needs_action IS
  'true = 上位机收到后应开人工窗口等决策（interaction 类一般是 true）。';

CREATE INDEX IF NOT EXISTS ix_device_event_type_tenant
    ON device_event_type (tenant_id, category, sort_order);
