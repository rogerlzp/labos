-- =============================================================================
-- 20261116 · 设备 ID 空间收口：把两处歧义列名 device_id 改成 device_model_id
--
-- 背景（BUG 台账 2026-11-16）：
--   device          .id = 型号行号（表注释就是「设备型号定义」）
--   device_instance .id = 实例号（运行期 ctx.resourceId()、设备锁、心跳都用它）
--   resource_node   .id = 资源号（拓扑树/点位/能力绑定用它）
--   三套 id 都是 bigint ⇒ 混用不报错、只查错行；再被 COALESCE(MAX(..),0) 吞成 0 就完全静默。
--
--   实锤：DriverStepExecutor.resolveTimeoutMs 把**实例号**传给 device_method.device_id
--   （该列存的是 device.id）。提取线 DISP3 实例号 1000039 恰好撞上旧演示岛
--   GANTRY_ROBOT_GENERIC 的型号行号 ⇒ 分液被套上「机械臂 7 秒」上限 ⇒ EC-05-1007。
--
--   列名改成 device_model_id 后，`WHERE device_id = <实例号>` 这类误用在 SQL 层直接报
--   「column does not exist」而不是静默查错行。
--
-- 幂等：连跑两次都 exit 0。已迁移（新列在、旧列不在）⇒ 跳过。
--       半迁移（两列都在 / 都不在）⇒ 抛错，不要硬改。
-- =============================================================================
\set ON_ERROR_STOP on

DO $$
DECLARE
  v_old_method  int;
  v_new_method  int;
  v_old_inst    int;
  v_new_inst    int;
BEGIN
  SELECT count(*) INTO v_old_method FROM information_schema.columns
   WHERE table_schema = current_schema() AND table_name = 'device_method'
     AND column_name = 'device_id';
  SELECT count(*) INTO v_new_method FROM information_schema.columns
   WHERE table_schema = current_schema() AND table_name = 'device_method'
     AND column_name = 'device_model_id';
  SELECT count(*) INTO v_old_inst FROM information_schema.columns
   WHERE table_schema = current_schema() AND table_name = 'device_instance'
     AND column_name = 'device_id';
  SELECT count(*) INTO v_new_inst FROM information_schema.columns
   WHERE table_schema = current_schema() AND table_name = 'device_instance'
     AND column_name = 'device_model_id';

  -- ---- device_method ----
  IF v_old_method = 1 AND v_new_method = 0 THEN
    ALTER TABLE device_method RENAME COLUMN device_id TO device_model_id;
    RAISE NOTICE 'device_method.device_id -> device_model_id（已改名）';
  ELSIF v_old_method = 1 AND v_new_method = 1 THEN
    RAISE EXCEPTION 'device_method 同时存在 device_id 与 device_model_id，拒绝猜测，请人工核对';
  ELSIF v_old_method = 0 AND v_new_method = 0 THEN
    RAISE EXCEPTION 'device_method 既没有 device_id 也没有 device_model_id，表结构不符预期';
  ELSE
    RAISE NOTICE 'device_method.device_model_id 已就位（跳过）';
  END IF;

  -- ---- device_instance ----
  IF v_old_inst = 1 AND v_new_inst = 0 THEN
    ALTER TABLE device_instance RENAME COLUMN device_id TO device_model_id;
    RAISE NOTICE 'device_instance.device_id -> device_model_id（已改名）';
  ELSIF v_old_inst = 1 AND v_new_inst = 1 THEN
    RAISE EXCEPTION 'device_instance 同时存在 device_id 与 device_model_id，拒绝猜测，请人工核对';
  ELSIF v_old_inst = 0 AND v_new_inst = 0 THEN
    RAISE EXCEPTION 'device_instance 既没有 device_id 也没有 device_model_id，表结构不符预期';
  ELSE
    RAISE NOTICE 'device_instance.device_model_id 已就位（跳过）';
  END IF;
END $$;

-- PG 的 RENAME COLUMN 会让唯一约束（uk_device_method）与视图定义自动跟随（按 attnum 存），
-- 这里再显式确认一遍，避免"约束没跟上"导致 ON CONFLICT (device_model_id, method) 失败。
DO $$
DECLARE
  v_def   text;
  v_split boolean;
BEGIN
  -- 2026-11-17 起唯一键按 scope 分离（uk_device_method_model / _instance，见
  -- 20261117_device_method_instance_scope.sql），那时 uk_device_method 已被删除。
  -- 这里先看"新索引是否就位"，就位则本段跳过 —— 否则重跑本脚本会误报。
  SELECT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                  WHERE c.relname = 'uk_device_method_model' AND n.nspname = current_schema())
    INTO v_split;
  IF v_split THEN
    RAISE NOTICE 'uk_device_method 已由 scope 分离索引取代（uk_device_method_model/_instance），跳过本段';
    RETURN;
  END IF;

  SELECT pg_get_constraintdef(c.oid) INTO v_def
    FROM pg_constraint c
    JOIN pg_class t ON t.oid = c.conrelid
   WHERE t.relname = 'device_method' AND c.conname = 'uk_device_method';
  IF v_def IS NULL THEN
    RAISE EXCEPTION 'device_method 缺 uk_device_method 唯一约束，无法支撑 ON CONFLICT (device_model_id, method)';
  END IF;
  IF position('device_model_id' IN v_def) = 0 THEN
    RAISE EXCEPTION 'uk_device_method 仍是旧列定义（%），拒绝继续', v_def;
  END IF;
  RAISE NOTICE 'uk_device_method = %', v_def;
END $$;

-- 型号外键索引：按实例号解析型号（device_instance.id -> device_model_id）是运行期热路径
CREATE INDEX IF NOT EXISTS ix_device_instance_model ON device_instance (device_model_id);

-- 视图对外列名仍是 equipment_id（兼容旧消费者），只是内部来源列改名
CREATE OR REPLACE VIEW v_equipment_instance_ro AS
SELECT
  di.id,
  di.code                        AS code,
  di.name                        AS name,
  d.name                         AS equipment_name,
  di.device_model_id             AS equipment_id,
  rn.code                        AS island_code,
  di.status::text                AS status,
  di.attr,
  di.created_at                  AS create_time,
  di.updated_at                  AS update_time
FROM device_instance di
LEFT JOIN device d        ON d.id  = di.device_model_id
LEFT JOIN resource_node rn ON rn.id = di.resource_id;

COMMENT ON COLUMN device_method.device_model_id IS
  'device.id（型号行），**不是** device_instance.id；按实例取方法必须经 device_instance.device_model_id 换算';
COMMENT ON COLUMN device_instance.device_model_id IS
  'device.id（型号行）；为空表示型号未登记，按实例解析方法/超时会落空';
COMMENT ON COLUMN device_instance.id IS
  '实例号：运行期 ctx.resourceId() / 设备锁 / 心跳用的就是它';
COMMENT ON COLUMN device_instance.resource_id IS
  'resource_node.id（资源号）：拓扑树/点位/能力绑定用它';

-- ---------------------------------------------------------------------------
-- 收尾自检：两列必须存在，data_type 必须是 bigint（外键目标 device.id）
-- ---------------------------------------------------------------------------
DO $$
DECLARE
  v_bad int;
BEGIN
  SELECT count(*) INTO v_bad
    FROM information_schema.columns
   WHERE table_schema = current_schema()
     AND ((table_name = 'device_method'   AND column_name = 'device_model_id')
       OR (table_name = 'device_instance' AND column_name = 'device_model_id'))
     AND data_type <> 'bigint';
  IF v_bad > 0 THEN
    RAISE EXCEPTION 'device_model_id 类型不是 bigint（% 处），外键会失效', v_bad;
  END IF;
  RAISE NOTICE '迁移完成：device_method / device_instance 的型号列统一为 device_model_id';
END $$;
