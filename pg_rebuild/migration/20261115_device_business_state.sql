-- 设备业务状态字典 + 板状态字典补强（2026-11-15）
--
-- **背景**：客户《高通量核酸提取工作站 · 软件控制流程说明书》
--   §1.4 板状态枚举（6 态）：空板 / 待分液 / 实验中 / 待交互（等待送入提取仪）/
--                        已完成（产物板）/ 废弃耗材板
--   §1.5 设备状态枚举（按设备类别 5 组）：
--        提取仪：空闲、初始化中、等待交互、转盘运行中、
--                操作位执行工序（裂解/磁吸/W1/W2/W3/洗脱）、流程结束、故障报错
--        分液器：就绪、分液中、分液完成、故障
--        搅拌单元：运行、停止、故障
--        机械臂：原点、移动中、取放板中、占位、故障
--        耗材栈：充足、预警、不足
--
-- **为什么要做字典（口径配置）**：
--   这些枚举是**业务状态机**（设备"在干什么"），而平台已有的
--   `device_state.status` 是**运行时健康/占用标记**（idle/running/error）
--   —— 两者不是一回事，不能混用。
--   枚举写死在代码里 ⇒ 界面文案、流程节点、侧车翻译各写各的 ⇒ 静默失配。
--   ⇒ 存库；界面与流程从这张表取；厂商手册/工艺变更时改表即可。
--
-- ⚠ **这一版只做「口径配置层」**：运行时业务状态列（device_state.business_state）
--   **本轮不接**（`device_state` 相关文件正在被另一条线改动，撞不起）。
--   落点与接法见交付清单；字典本身已可用（管理页 + 端点 + seed）。
--
-- 可重复执行（IF NOT EXISTS / ON CONFLICT DO NOTHING）。

-- ---------------------------------------------------------------- 设备业务状态字典

CREATE TABLE IF NOT EXISTS device_business_state (
    id            bigserial PRIMARY KEY,
    tenant_id     bigint       NOT NULL REFERENCES tenant(id),
    -- 设备类别：**用平台自己的 device_type 口径**（extractor / dispenser /
    -- stirrer / robot / plate_hotel），不自造一套 —— 否则字典和设备对不上。
    -- ⚠ 这里**故意不加 CHECK 枚举值**：平台的 device_type 词表会长大，
    --   字典不该比词表更早封死（加 CHECK 的后果是将来加一种设备类型就插不进状态）。
    device_kind   varchar(32)  NOT NULL,
    -- 业务状态码（界面与流程引用的键）
    state_code    varchar(64)  NOT NULL,
    state_name    varchar(128) NOT NULL,
    -- 展示排序（小的在前）
    sort_order    int          NOT NULL DEFAULT 100,
    enabled       boolean      NOT NULL DEFAULT true,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uk_device_business_state UNIQUE (tenant_id, device_kind, state_code),
    CONSTRAINT ck_device_business_state_kind CHECK (btrim(device_kind) <> ''),
    CONSTRAINT ck_device_business_state_code CHECK (btrim(state_code) <> '')
);

COMMENT ON TABLE device_business_state IS
  '设备业务状态字典（客户文档 §1.5 的口径）。'
  '与 device_state.status（运行时 idle/running/error 健康标记）不是一回事：'
  '前者是业务状态机（设备在干什么），后者是运行时占用与健康。'
  'device_kind 用平台 device_type 口径（extractor/dispenser/stirrer/robot/plate_hotel）。';

CREATE INDEX IF NOT EXISTS ix_device_business_state_tenant
    ON device_business_state (tenant_id, device_kind, sort_order);

-- ---------------------------------------------------------------- 板状态：补「客户文档叫法」列

-- plate_process_state 是**全平台通用**的物料状态字典（raw/ready/in_progress/
-- completed/discarded），已被运行时消费（MaterialEffectService 校验
-- processState 必须在启用字典里）⇒ **不能改名、不能删**（会打断既有物料效果）。
--
-- doc_label：客户/工艺文档里对同一状态的叫法（如 §1.4 的「空板」= 平台的 raw）。
-- 加这一列是为了让"客户口径 ↔ 平台口径"的对照**可配置**，而不是写在注释里。
-- 别的流程留空即可。
ALTER TABLE plate_process_state
    ADD COLUMN IF NOT EXISTS doc_label varchar(128);

COMMENT ON COLUMN plate_process_state.doc_label IS
  '客户/工艺文档里对该状态的叫法（客户文档 §1.4 口径）。通用流程可留空。';
