-- =============================================================================
-- 20261117_device_method_instance_scope.sql
--
-- 目的：让「实例级方法覆盖」真正可用 —— 超时/可中断的解析链路要能读到
--       device_method 里 scope='instance' 的行。
--
-- 背景（2026-11-16 / BUG-20261116-02 的续做）：
--   P1 已经给 device_method 加了 device_instance_id / scope / position_id 三列
--   （见 99x_p2_rebuild.sql），约束也写了 ck_device_method_scope IN ('model','instance')，
--   但**唯一键仍是 UNIQUE (device_model_id, method)** ⇒ 同一型号同一方法只能有一行，
--   实例级覆盖行根本插不进去 ⇒ 那一层是死设计。
--
--   本脚本把唯一键按 scope 拆成两个部分唯一索引：
--     · uk_device_method_model    (tenant_id, device_model_id, method)    WHERE scope='model'
--     · uk_device_method_instance (tenant_id, device_instance_id, method) WHERE scope='instance'
--   并把"scope 与 device_instance_id 必须自洽"落成约束。
--
-- 幂等：全部 IF NOT EXISTS / DROP IF EXISTS；连跑两次都 exit 0。
-- 依赖：可单独执行（缺 scope 列会先补上）。
-- =============================================================================

BEGIN;

-- ---------------------------------------------------------------- 1. 前置列
-- 老库可能还没跑 99x_p2_rebuild；列不存在就补（与 99x 的定义一致）
ALTER TABLE device_method ADD COLUMN IF NOT EXISTS device_instance_id bigint;
ALTER TABLE device_method ADD COLUMN IF NOT EXISTS scope text NOT NULL DEFAULT 'model';

-- ---------------------------------------------------------------- 2. scope 取值
DO $$
BEGIN
  IF NOT EXISTS (
      SELECT 1 FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
       WHERE t.relname = 'device_method' AND c.conname = 'ck_device_method_scope') THEN
    ALTER TABLE device_method
      ADD CONSTRAINT ck_device_method_scope CHECK (scope IN ('model', 'instance'));
  END IF;
END $$;

-- ---------------------------------------------------------------- 3. 数据自洽
-- scope 与 device_instance_id 必须配对：
--   scope='model'    ⇒ device_instance_id 必须空（否则"型号级"的行被某台设备独占，无法再给同型号其他实例用）
--   scope='instance' ⇒ device_instance_id 必须非空（否则这一行谁也匹配不到）
DO $$
DECLARE
  v_n bigint;
BEGIN
  SELECT count(*) INTO v_n FROM device_method
   WHERE scope = 'instance' AND device_instance_id IS NULL;
  IF v_n > 0 THEN
    RAISE EXCEPTION '有 % 行 scope=instance 但 device_instance_id 为空，无法判断归属，请人工处理', v_n;
  END IF;

  SELECT count(*) INTO v_n FROM device_method
   WHERE scope = 'model' AND device_instance_id IS NOT NULL;
  IF v_n > 0 THEN
    -- 这类行原本被当成型号级（因为唯一键只认 device_model_id），
    -- 但既然明确写了实例号，按实例级处理才是本意 —— 就地规范化。
    UPDATE device_method SET scope = 'instance'
     WHERE scope = 'model' AND device_instance_id IS NOT NULL;
    RAISE NOTICE '已把 % 行「带实例号的型号级行」规范化为 scope=instance', v_n;
  END IF;
END $$;

DO $$
BEGIN
  IF NOT EXISTS (
      SELECT 1 FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
       WHERE t.relname = 'device_method' AND c.conname = 'ck_device_method_scope_pair') THEN
    ALTER TABLE device_method
      ADD CONSTRAINT ck_device_method_scope_pair CHECK (
        (scope = 'model'    AND device_instance_id IS NULL) OR
        (scope = 'instance' AND device_instance_id IS NOT NULL));
  END IF;
END $$;

-- ---------------------------------------------------------------- 4. 唯一键分离
-- 旧的 UNIQUE (device_model_id, method) 会挡住实例级覆盖行 ⇒ 删掉，换成两个部分索引。
DO $$
BEGIN
  IF EXISTS (
      SELECT 1 FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
       WHERE t.relname = 'device_method' AND c.conname = 'uk_device_method') THEN
    ALTER TABLE device_method DROP CONSTRAINT uk_device_method;
    RAISE NOTICE '已删除 uk_device_method（改为按 scope 分离的两个部分唯一索引）';
  END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uk_device_method_model
  ON device_method (tenant_id, device_model_id, method)
  WHERE scope = 'model';

CREATE UNIQUE INDEX IF NOT EXISTS uk_device_method_instance
  ON device_method (tenant_id, device_instance_id, method)
  WHERE scope = 'instance';

COMMENT ON INDEX uk_device_method_model IS
  '型号级方法唯一：同一型号同一方法只能一行（scope=''model''）。写入时 ON CONFLICT 必须带 WHERE scope=''model''';
COMMENT ON INDEX uk_device_method_instance IS
  '实例级方法覆盖唯一：同一台设备同一方法只能一行（scope=''instance''）；解析优先级高于型号级';

COMMENT ON COLUMN device_method.scope IS
  'model=型号级（默认，所有同型号实例共享）/ instance=实例级覆盖（device_instance_id 必填，优先级更高）';

-- ---------------------------------------------------------------- 5. 自检
DO $$
DECLARE
  v_model_idx  boolean;
  v_inst_idx   boolean;
  v_dup_model  bigint;
  v_dup_inst   bigint;
  v_old_uk     boolean;
BEGIN
  SELECT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                  WHERE c.relname = 'uk_device_method_model' AND n.nspname = current_schema())
    INTO v_model_idx;
  SELECT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                  WHERE c.relname = 'uk_device_method_instance' AND n.nspname = current_schema())
    INTO v_inst_idx;
  SELECT EXISTS (SELECT 1 FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
                  WHERE t.relname = 'device_method' AND c.conname = 'uk_device_method')
    INTO v_old_uk;

  IF NOT v_model_idx OR NOT v_inst_idx THEN
    RAISE EXCEPTION '部分唯一索引未建成功（model=% instance=%）', v_model_idx, v_inst_idx;
  END IF;
  IF v_old_uk THEN
    RAISE EXCEPTION '旧约束 uk_device_method 仍存在，实例级覆盖行会被挡住';
  END IF;

  SELECT count(*) INTO v_dup_model FROM (
    SELECT 1 FROM device_method WHERE scope = 'model'
     GROUP BY tenant_id, device_model_id, method HAVING count(*) > 1) d;
  SELECT count(*) INTO v_dup_inst FROM (
    SELECT 1 FROM device_method WHERE scope = 'instance'
     GROUP BY tenant_id, device_instance_id, method HAVING count(*) > 1) d;
  IF v_dup_model > 0 OR v_dup_inst > 0 THEN
    RAISE EXCEPTION '存在重复行（model=% instance=%）—— 唯一索引本不该允许，请人工核对',
      v_dup_model, v_dup_inst;
  END IF;

  RAISE NOTICE '迁移完成：unique(device_model_id,method) -> uk_device_method_model / uk_device_method_instance';
END $$;

COMMIT;
