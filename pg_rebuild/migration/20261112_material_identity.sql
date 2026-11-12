-- =============================================================================
-- S1 正式迁移脚本（幂等形态）· 包装自 docs/物料身份与状态-设计-v4.md §4
-- =============================================================================
-- 设计稿快照绑定：
--   文件   docs/物料身份与状态-设计-v4.md
--   §4 SQL 围栏 = 199 行 / 21 条 CREATE/ALTER（第 10 轮复跑抽取；文档行号会随正文编辑漂移，
--                 故以 sha256 为准，不以行号为准）
--   sha256(section4_ddl.sql) = a3dd715554e603c312be319f2fb0e2d9cdcc0d37b765224a29d23a4360b8a674
--   §5.3 巡检围栏 sha256      = 909b797d6b64f44d29bc8de980bff3aeda246b03b625ca14f71379f2be8298e4
--   v4.2 变化：ck_action_type 白名单新增 'REBASE'（§3.4 要求人工 rebase 写 action REBASE）
-- 本脚本的「期望定义」全部按上面这个 sha256 钉死；设计稿 §4 一旦变化，本脚本即失效，
-- 必须重新生成期望定义（否则会按设计意图主动报错）。
--
-- 幂等策略：
--   每个对象一个 DO 块 → 对象不存在则创建；对象已存在则做 catalog 校验
--   （列/类型/NOT NULL/DEFAULT、约束名+contype+语义 token、索引名+列序+唯一性+谓词），
--   定义不一致时 RAISE EXCEPTION 'MIGRATION DRIFT'，使迁移**失败而不是静默吞掉 drift**。
--
-- 用法：psql -v ON_ERROR_STOP=1 -f migration_s1.sql   （必须连跑两次，两次都必须 exit 0）
-- =============================================================================

\set ON_ERROR_STOP on
BEGIN;
SET LOCAL lock_timeout = '30s';

-- ---------- helpers（pg_temp，事务结束即销毁，不落库） ----------

-- 对象存在性/定义不一致 → 直接失败
CREATE OR REPLACE FUNCTION pg_temp.mig_fail(p_msg text) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'MIGRATION DRIFT: %', p_msg;
END $$;

-- 列校验：类型 / NOT NULL / DEFAULT
CREATE OR REPLACE FUNCTION pg_temp.mig_col(
  p_table text, p_col text, p_type text, p_notnull boolean, p_default text
) RETURNS void LANGUAGE plpgsql AS $$
DECLARE v_type text; v_nn boolean; v_hasdef boolean; v_def text;
BEGIN
  SELECT format_type(a.atttypid, a.atttypmod), a.attnotnull, a.atthasdef
    INTO v_type, v_nn, v_hasdef
    FROM pg_attribute a
   WHERE a.attrelid = to_regclass(p_table) AND a.attname = p_col AND a.attnum > 0
     AND NOT a.attisdropped;
  IF NOT FOUND THEN
    PERFORM pg_temp.mig_fail(format('column %s.%s missing', p_table, p_col));
  END IF;
  IF v_type <> p_type THEN
    PERFORM pg_temp.mig_fail(format('column %s.%s type drift: catalog=%s expected=%s',
                                    p_table, p_col, v_type, p_type));
  END IF;
  IF v_nn <> p_notnull THEN
    PERFORM pg_temp.mig_fail(format('column %s.%s NOT NULL drift: catalog=%s expected=%s',
                                    p_table, p_col, v_nn, p_notnull));
  END IF;
  IF p_default IS NULL THEN
    IF v_hasdef THEN
      PERFORM pg_temp.mig_fail(format('column %s.%s has unexpected DEFAULT', p_table, p_col));
    END IF;
  ELSE
    IF NOT v_hasdef THEN
      PERFORM pg_temp.mig_fail(format('column %s.%s missing DEFAULT %s', p_table, p_col, p_default));
    END IF;
    SELECT pg_get_expr(d.adbin, d.adrelid) INTO v_def
      FROM pg_attrdef d
      JOIN pg_attribute a ON a.attrelid = d.adrelid AND a.attnum = d.adnum
     WHERE d.adrelid = to_regclass(p_table) AND a.attname = p_col;
    IF v_def IS DISTINCT FROM p_default THEN
      PERFORM pg_temp.mig_fail(format('column %s.%s DEFAULT drift: catalog=%s expected=%s',
                                      p_table, p_col, coalesce(v_def,'<null>'), p_default));
    END IF;
  END IF;
END $$;

-- 约束校验：存在性 + contype + 定义语义 token（token 必须逐个出现在 pg_get_constraintdef 中）
CREATE OR REPLACE FUNCTION pg_temp.mig_con(
  p_table text, p_name text, p_contype text, p_tokens text[] DEFAULT '{}'
) RETURNS void LANGUAGE plpgsql AS $$
DECLARE v_ct text; v_def text; t text;
BEGIN
  SELECT c.contype, regexp_replace(pg_get_constraintdef(c.oid), '\s+', ' ', 'g')
    INTO v_ct, v_def
    FROM pg_constraint c
   WHERE c.conrelid = to_regclass(p_table) AND c.conname = p_name;
  IF NOT FOUND THEN
    PERFORM pg_temp.mig_fail(format('constraint %s on %s missing', p_name, p_table));
  END IF;
  IF v_ct <> p_contype THEN
    PERFORM pg_temp.mig_fail(format('constraint %s contype drift: catalog=%s expected=%s',
                                    p_name, v_ct, p_contype));
  END IF;
  FOREACH t IN ARRAY p_tokens LOOP
    IF position(t in v_def) = 0 THEN
      PERFORM pg_temp.mig_fail(format('constraint %s definition drift: token [%s] not found in [%s]',
                                      p_name, t, v_def));
    END IF;
  END LOOP;
END $$;

-- 索引校验：存在性 + 所属表 + 列序 + 唯一性 + （部分索引的）谓词语义 token
CREATE OR REPLACE FUNCTION pg_temp.mig_idx(
  p_name text, p_table text, p_cols text[], p_unique boolean,
  p_pred_tokens text[] DEFAULT '{}', p_has_pred boolean DEFAULT false
) RETURNS void LANGUAGE plpgsql AS $$
DECLARE v_tbl text; v_cols text[]; v_uniq boolean; v_pred text; t text;
BEGIN
  SELECT c.relname, ARRAY(
           SELECT a.attname FROM unnest(i.indkey) WITH ORDINALITY AS k(attnum, ord)
             JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum
            ORDER BY k.ord),
         i.indisunique, NULLIF(regexp_replace(pg_get_expr(i.indpred, i.indrelid), '\s+', ' ', 'g'), '')
    INTO v_tbl, v_cols, v_uniq, v_pred
    FROM pg_index i JOIN pg_class c ON c.oid = i.indrelid
   WHERE i.indexrelid = to_regclass(p_name);
  IF NOT FOUND THEN
    PERFORM pg_temp.mig_fail(format('index %s missing', p_name));
  END IF;
  IF v_tbl <> p_table THEN
    PERFORM pg_temp.mig_fail(format('index %s table drift: catalog=%s expected=%s', p_name, v_tbl, p_table));
  END IF;
  IF v_cols IS DISTINCT FROM p_cols THEN
    PERFORM pg_temp.mig_fail(format('index %s column drift: catalog=%s expected=%s',
                                    p_name, array_to_string(v_cols,','), array_to_string(p_cols,',')));
  END IF;
  IF v_uniq <> p_unique THEN
    PERFORM pg_temp.mig_fail(format('index %s uniqueness drift: catalog=%s expected=%s', p_name, v_uniq, p_unique));
  END IF;
  IF p_has_pred AND v_pred IS NULL THEN
    PERFORM pg_temp.mig_fail(format('index %s expected to be PARTIAL but has no WHERE predicate', p_name));
  END IF;
  IF NOT p_has_pred AND v_pred IS NOT NULL THEN
    PERFORM pg_temp.mig_fail(format('index %s expected to be NON-PARTIAL but has predicate [%s]', p_name, v_pred));
  END IF;
  FOREACH t IN ARRAY p_pred_tokens LOOP
    IF v_pred IS NULL OR position(t in v_pred) = 0 THEN
      PERFORM pg_temp.mig_fail(format('index %s predicate drift: token [%s] not found in [%s]', p_name, t, coalesce(v_pred,'<none>')));
    END IF;
  END LOOP;
END $$;

-- =============================================================================
-- ① plate_process_state
-- =============================================================================
DO $$
BEGIN
  IF to_regclass('public.plate_process_state') IS NULL THEN
    CREATE TABLE plate_process_state (
      tenant_id    bigint      NOT NULL,
      code         varchar(32) NOT NULL,
      display_name varchar(64) NOT NULL,
      sort_order   int         NOT NULL DEFAULT 0,
      enabled      boolean     NOT NULL DEFAULT true,
      created_at   timestamptz NOT NULL DEFAULT now(),
      updated_at   timestamptz NOT NULL DEFAULT now(),
      PRIMARY KEY (tenant_id, code),
      CONSTRAINT ck_plate_process_state_code CHECK (code <> '')
    );
    RAISE NOTICE 'CREATE  plate_process_state';
  ELSE
    PERFORM pg_temp.mig_col('plate_process_state','tenant_id','bigint',true,NULL);
    PERFORM pg_temp.mig_col('plate_process_state','code','character varying(32)',true,NULL);
    PERFORM pg_temp.mig_col('plate_process_state','display_name','character varying(64)',true,NULL);
    PERFORM pg_temp.mig_col('plate_process_state','sort_order','integer',true,'0');
    PERFORM pg_temp.mig_col('plate_process_state','enabled','boolean',true,'true');
    PERFORM pg_temp.mig_col('plate_process_state','created_at','timestamp with time zone',true,'now()');
    PERFORM pg_temp.mig_col('plate_process_state','updated_at','timestamp with time zone',true,'now()');
    PERFORM pg_temp.mig_con('plate_process_state','plate_process_state_pkey','p',ARRAY['tenant_id','code']);
    PERFORM pg_temp.mig_con('plate_process_state','ck_plate_process_state_code','c',ARRAY['code']);
    RAISE NOTICE 'VALIDATE plate_process_state';
  END IF;
END $$;

-- =============================================================================
-- ② plate 加列
-- =============================================================================
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid='public.plate'::regclass
                   AND attname='process_state' AND attnum>0 AND NOT attisdropped) THEN
    ALTER TABLE plate ADD COLUMN process_state varchar(32);
    RAISE NOTICE 'ALTER  plate ADD process_state';
  ELSE
    PERFORM pg_temp.mig_col('plate','process_state','character varying(32)',false,NULL);
    RAISE NOTICE 'VALIDATE plate.process_state';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid='public.plate'::regclass
                   AND attname='seal_state' AND attnum>0 AND NOT attisdropped) THEN
    ALTER TABLE plate ADD COLUMN seal_state varchar(16);
    RAISE NOTICE 'ALTER  plate ADD seal_state';
  ELSE
    PERFORM pg_temp.mig_col('plate','seal_state','character varying(16)',false,NULL);
    RAISE NOTICE 'VALIDATE plate.seal_state';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid='public.plate'::regclass
                   AND attname='state_version' AND attnum>0 AND NOT attisdropped) THEN
    ALTER TABLE plate ADD COLUMN state_version bigint NOT NULL DEFAULT 0;
    RAISE NOTICE 'ALTER  plate ADD state_version';
  ELSE
    PERFORM pg_temp.mig_col('plate','state_version','bigint',true,'0');
    RAISE NOTICE 'VALIDATE plate.state_version';
  END IF;
END $$;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='public.plate'::regclass
                   AND conname='ck_plate_seal_state') THEN
    ALTER TABLE plate ADD CONSTRAINT ck_plate_seal_state
      CHECK (seal_state IS NULL OR seal_state IN ('none','sealed'));
    RAISE NOTICE 'ALTER  plate ADD CONSTRAINT ck_plate_seal_state';
  ELSE
    PERFORM pg_temp.mig_con('plate','ck_plate_seal_state','c',ARRAY['seal_state','none','sealed']);
    RAISE NOTICE 'VALIDATE plate.ck_plate_seal_state';
  END IF;
END $$;

-- =============================================================================
-- ③ workflow_node 加列
-- =============================================================================
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid='public.workflow_node'::regclass
                   AND attname='material_effect' AND attnum>0 AND NOT attisdropped) THEN
    ALTER TABLE workflow_node ADD COLUMN material_effect jsonb;
    RAISE NOTICE 'ALTER  workflow_node ADD material_effect';
  ELSE
    PERFORM pg_temp.mig_col('workflow_node','material_effect','jsonb',false,NULL);
    RAISE NOTICE 'VALIDATE workflow_node.material_effect';
  END IF;
END $$;

-- =============================================================================
-- ④ run_node 加列（3 个维度）
-- =============================================================================
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid='public.run_node'::regclass
                   AND attname='physical_completed_at' AND attnum>0 AND NOT attisdropped) THEN
    ALTER TABLE run_node ADD COLUMN physical_completed_at timestamptz;
    RAISE NOTICE 'ALTER  run_node ADD physical_completed_at';
  ELSE
    PERFORM pg_temp.mig_col('run_node','physical_completed_at','timestamp with time zone',false,NULL);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid='public.run_node'::regclass
                   AND attname='physical_evidence_level' AND attnum>0 AND NOT attisdropped) THEN
    ALTER TABLE run_node ADD COLUMN physical_evidence_level varchar(24);
    RAISE NOTICE 'ALTER  run_node ADD physical_evidence_level';
  ELSE
    PERFORM pg_temp.mig_col('run_node','physical_evidence_level','character varying(24)',false,NULL);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid='public.run_node'::regclass
                   AND attname='physical_evidence' AND attnum>0 AND NOT attisdropped) THEN
    ALTER TABLE run_node ADD COLUMN physical_evidence jsonb NOT NULL DEFAULT '{}'::jsonb;
    RAISE NOTICE 'ALTER  run_node ADD physical_evidence';
  ELSE
    PERFORM pg_temp.mig_col('run_node','physical_evidence','jsonb',true,'''{}''::jsonb');
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid='public.run_node'::regclass
                   AND attname='execution_resolution_status' AND attnum>0 AND NOT attisdropped) THEN
    ALTER TABLE run_node ADD COLUMN execution_resolution_status varchar(32) NOT NULL DEFAULT 'unresolved';
    RAISE NOTICE 'ALTER  run_node ADD execution_resolution_status';
  ELSE
    PERFORM pg_temp.mig_col('run_node','execution_resolution_status','character varying(32)',true,'''unresolved''::character varying');
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid='public.run_node'::regclass
                   AND attname='material_sync_status' AND attnum>0 AND NOT attisdropped) THEN
    ALTER TABLE run_node ADD COLUMN material_sync_status varchar(24) NOT NULL DEFAULT 'not_required';
    RAISE NOTICE 'ALTER  run_node ADD material_sync_status';
  ELSE
    PERFORM pg_temp.mig_col('run_node','material_sync_status','character varying(24)',true,'''not_required''::character varying');
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid='public.run_node'::regclass
                   AND attname='material_error_code' AND attnum>0 AND NOT attisdropped) THEN
    ALTER TABLE run_node ADD COLUMN material_error_code varchar(64);
    RAISE NOTICE 'ALTER  run_node ADD material_error_code';
  ELSE
    PERFORM pg_temp.mig_col('run_node','material_error_code','character varying(64)',false,NULL);
  END IF;
END $$;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='public.run_node'::regclass
                   AND conname='ck_run_node_exec_resolution') THEN
    ALTER TABLE run_node
      ADD CONSTRAINT ck_run_node_exec_resolution
        CHECK (execution_resolution_status IN ('unresolved','completed','failed_confirmed','reconciliation_required'));
    RAISE NOTICE 'ALTER  run_node ADD CONSTRAINT ck_run_node_exec_resolution';
  ELSE
    PERFORM pg_temp.mig_con('run_node','ck_run_node_exec_resolution','c',
      ARRAY['execution_resolution_status','unresolved','completed','failed_confirmed','reconciliation_required']);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='public.run_node'::regclass
                   AND conname='ck_run_node_physical_evidence_level') THEN
    ALTER TABLE run_node
      ADD CONSTRAINT ck_run_node_physical_evidence_level
        CHECK (physical_evidence_level IS NULL OR physical_evidence_level IN ('device_ack','physical_confirmed','manual_confirmed','legacy'));
    RAISE NOTICE 'ALTER  run_node ADD CONSTRAINT ck_run_node_physical_evidence_level';
  ELSE
    PERFORM pg_temp.mig_con('run_node','ck_run_node_physical_evidence_level','c',
      ARRAY['physical_evidence_level','device_ack','physical_confirmed','manual_confirmed','legacy']);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='public.run_node'::regclass
                   AND conname='ck_run_node_physical_completion') THEN
    ALTER TABLE run_node
      ADD CONSTRAINT ck_run_node_physical_completion
        CHECK ( (execution_resolution_status = 'completed'
                  AND physical_completed_at IS NOT NULL
                  AND physical_evidence_level IS NOT NULL
                  AND physical_evidence <> '{}'::jsonb)
             OR (execution_resolution_status <> 'completed' AND physical_completed_at IS NULL) );
    RAISE NOTICE 'ALTER  run_node ADD CONSTRAINT ck_run_node_physical_completion';
  ELSE
    PERFORM pg_temp.mig_con('run_node','ck_run_node_physical_completion','c',
      ARRAY['execution_resolution_status','completed','physical_completed_at','physical_evidence_level','physical_evidence']);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='public.run_node'::regclass
                   AND conname='ck_run_node_material_sync') THEN
    ALTER TABLE run_node
      ADD CONSTRAINT ck_run_node_material_sync
        CHECK (material_sync_status IN ('not_required','pending','applied','failed','rejected','needs_reconciliation','cancelled'));
    RAISE NOTICE 'ALTER  run_node ADD CONSTRAINT ck_run_node_material_sync';
  ELSE
    PERFORM pg_temp.mig_con('run_node','ck_run_node_material_sync','c',
      ARRAY['material_sync_status','not_required','pending','applied','failed','rejected','needs_reconciliation','cancelled']);
  END IF;
END $$;

-- v4.1：普通（非部分）索引
DO $$
BEGIN
  IF to_regclass('public.ix_run_node_exec_resolution') IS NULL THEN
    CREATE INDEX ix_run_node_exec_resolution ON run_node (tenant_id, execution_resolution_status);
    RAISE NOTICE 'CREATE  ix_run_node_exec_resolution';
  ELSE
    PERFORM pg_temp.mig_idx('ix_run_node_exec_resolution','run_node',
      ARRAY['tenant_id','execution_resolution_status'], false, '{}', false);
  END IF;
  IF to_regclass('public.ix_run_node_material_sync') IS NULL THEN
    CREATE INDEX ix_run_node_material_sync ON run_node (tenant_id, material_sync_status);
    RAISE NOTICE 'CREATE  ix_run_node_material_sync';
  ELSE
    PERFORM pg_temp.mig_idx('ix_run_node_material_sync','run_node',
      ARRAY['tenant_id','material_sync_status'], false, '{}', false);
  END IF;
END $$;

-- =============================================================================
-- ⑤ run_material_effect_declaration
-- =============================================================================
DO $$
BEGIN
  IF to_regclass('public.run_material_effect_declaration') IS NULL THEN
    CREATE TABLE run_material_effect_declaration (
      id               bigserial PRIMARY KEY,
      tenant_id        bigint      NOT NULL,
      run_id           bigint      NOT NULL,
      node_key         varchar(64) NOT NULL,
      effect_index     int         NOT NULL DEFAULT 0,
      declaration      jsonb       NOT NULL,
      declaration_hash varchar(64) NOT NULL,
      workflow_ver     int,
      created_at       timestamptz NOT NULL DEFAULT now(),
      CONSTRAINT uk_run_material_declaration UNIQUE (tenant_id, run_id, node_key, effect_index),
      CONSTRAINT ck_run_material_declaration_effect_idx CHECK (effect_index = 0),
      CONSTRAINT ck_run_material_declaration_mode CHECK (declaration ? 'mode'),
      CONSTRAINT ck_run_material_declaration_hash CHECK (length(declaration_hash) = 64)
    );
    RAISE NOTICE 'CREATE  run_material_effect_declaration';
  ELSE
    PERFORM pg_temp.mig_col('run_material_effect_declaration','id','bigint',true,'nextval(''run_material_effect_declaration_id_seq''::regclass)');
    PERFORM pg_temp.mig_col('run_material_effect_declaration','tenant_id','bigint',true,NULL);
    PERFORM pg_temp.mig_col('run_material_effect_declaration','run_id','bigint',true,NULL);
    PERFORM pg_temp.mig_col('run_material_effect_declaration','node_key','character varying(64)',true,NULL);
    PERFORM pg_temp.mig_col('run_material_effect_declaration','effect_index','integer',true,'0');
    PERFORM pg_temp.mig_col('run_material_effect_declaration','declaration','jsonb',true,NULL);
    PERFORM pg_temp.mig_col('run_material_effect_declaration','declaration_hash','character varying(64)',true,NULL);
    PERFORM pg_temp.mig_col('run_material_effect_declaration','workflow_ver','integer',false,NULL);
    PERFORM pg_temp.mig_col('run_material_effect_declaration','created_at','timestamp with time zone',true,'now()');
    PERFORM pg_temp.mig_con('run_material_effect_declaration','run_material_effect_declaration_pkey','p',ARRAY['id']);
    PERFORM pg_temp.mig_con('run_material_effect_declaration','uk_run_material_declaration','u',ARRAY['tenant_id','run_id','node_key','effect_index']);
    PERFORM pg_temp.mig_con('run_material_effect_declaration','ck_run_material_declaration_effect_idx','c',ARRAY['effect_index']);
    PERFORM pg_temp.mig_con('run_material_effect_declaration','ck_run_material_declaration_mode','c',ARRAY['declaration','mode']);
    PERFORM pg_temp.mig_con('run_material_effect_declaration','ck_run_material_declaration_hash','c',ARRAY['declaration_hash','64']);
    RAISE NOTICE 'VALIDATE run_material_effect_declaration';
  END IF;
END $$;

DO $$
BEGIN
  IF to_regclass('public.ix_run_material_declaration_run') IS NULL THEN
    CREATE INDEX ix_run_material_declaration_run ON run_material_effect_declaration (tenant_id, run_id);
    RAISE NOTICE 'CREATE  ix_run_material_declaration_run';
  ELSE
    PERFORM pg_temp.mig_idx('ix_run_material_declaration_run','run_material_effect_declaration',
      ARRAY['tenant_id','run_id'], false, '{}', false);
  END IF;
END $$;

-- =============================================================================
-- ⑥ plate_material_effect
-- =============================================================================
DO $$
BEGIN
  IF to_regclass('public.plate_material_effect') IS NULL THEN
    CREATE TABLE plate_material_effect (
      id                      bigserial PRIMARY KEY,
      tenant_id               bigint      NOT NULL,
      source_run_id           bigint      NOT NULL,
      source_node_key         varchar(64) NOT NULL,
      source_loop_index       int         NOT NULL DEFAULT 0,
      effect_index            int         NOT NULL DEFAULT 0,
      source_attempt          smallint    NOT NULL DEFAULT 0,
      plate_id                bigint,
      declaration             jsonb       NOT NULL,
      declaration_hash        varchar(64) NOT NULL,
      payload_hash            varchar(64) NOT NULL,
      target_snapshot         jsonb       NOT NULL,
      resolution_evidence     jsonb       NOT NULL DEFAULT '{}'::jsonb,
      expected_state_version  bigint,
      expected_process_state  varchar(32),
      expected_seal_state     varchar(16),
      status                  varchar(24) NOT NULL,
      apply_attempt_count     int         NOT NULL DEFAULT 0,
      next_retry_at           timestamptz,
      last_error_code         varchar(64),
      last_error_message      varchar(500),
      last_attempt_at         timestamptz,
      applied_at              timestamptz,
      created_at              timestamptz NOT NULL DEFAULT now(),
      updated_at              timestamptz NOT NULL DEFAULT now(),
      CONSTRAINT uk_plate_material_effect_key
        UNIQUE (tenant_id, source_run_id, source_node_key, source_loop_index, effect_index),
      CONSTRAINT ck_effect_effect_index CHECK (effect_index = 0),
      CONSTRAINT ck_effect_status CHECK (status IN
        ('PENDING','APPLIED','REJECTED','FAILED','NEEDS_RECONCILIATION','CANCELLED')),
      CONSTRAINT ck_effect_applicable_target CHECK
        (status NOT IN ('PENDING','APPLIED','FAILED')
          OR (plate_id IS NOT NULL AND expected_state_version IS NOT NULL)),
      CONSTRAINT ck_effect_applied CHECK (status <> 'APPLIED' OR applied_at IS NOT NULL),
      CONSTRAINT ck_effect_hash_format CHECK (length(declaration_hash) = 64 AND length(payload_hash) = 64),
      CONSTRAINT ck_effect_declaration_mode CHECK (declaration ? 'mode')
    );
    RAISE NOTICE 'CREATE  plate_material_effect';
  ELSE
    PERFORM pg_temp.mig_col('plate_material_effect','id','bigint',true,'nextval(''plate_material_effect_id_seq''::regclass)');
    PERFORM pg_temp.mig_col('plate_material_effect','tenant_id','bigint',true,NULL);
    PERFORM pg_temp.mig_col('plate_material_effect','source_run_id','bigint',true,NULL);
    PERFORM pg_temp.mig_col('plate_material_effect','source_node_key','character varying(64)',true,NULL);
    PERFORM pg_temp.mig_col('plate_material_effect','source_loop_index','integer',true,'0');
    PERFORM pg_temp.mig_col('plate_material_effect','effect_index','integer',true,'0');
    PERFORM pg_temp.mig_col('plate_material_effect','source_attempt','smallint',true,'0');
    PERFORM pg_temp.mig_col('plate_material_effect','plate_id','bigint',false,NULL);
    PERFORM pg_temp.mig_col('plate_material_effect','declaration','jsonb',true,NULL);
    PERFORM pg_temp.mig_col('plate_material_effect','declaration_hash','character varying(64)',true,NULL);
    PERFORM pg_temp.mig_col('plate_material_effect','payload_hash','character varying(64)',true,NULL);
    PERFORM pg_temp.mig_col('plate_material_effect','target_snapshot','jsonb',true,NULL);
    PERFORM pg_temp.mig_col('plate_material_effect','resolution_evidence','jsonb',true,'''{}''::jsonb');
    PERFORM pg_temp.mig_col('plate_material_effect','expected_state_version','bigint',false,NULL);
    PERFORM pg_temp.mig_col('plate_material_effect','expected_process_state','character varying(32)',false,NULL);
    PERFORM pg_temp.mig_col('plate_material_effect','expected_seal_state','character varying(16)',false,NULL);
    PERFORM pg_temp.mig_col('plate_material_effect','status','character varying(24)',true,NULL);
    PERFORM pg_temp.mig_col('plate_material_effect','apply_attempt_count','integer',true,'0');
    PERFORM pg_temp.mig_col('plate_material_effect','next_retry_at','timestamp with time zone',false,NULL);
    PERFORM pg_temp.mig_col('plate_material_effect','last_error_code','character varying(64)',false,NULL);
    PERFORM pg_temp.mig_col('plate_material_effect','last_error_message','character varying(500)',false,NULL);
    PERFORM pg_temp.mig_col('plate_material_effect','last_attempt_at','timestamp with time zone',false,NULL);
    PERFORM pg_temp.mig_col('plate_material_effect','applied_at','timestamp with time zone',false,NULL);
    PERFORM pg_temp.mig_col('plate_material_effect','created_at','timestamp with time zone',true,'now()');
    PERFORM pg_temp.mig_col('plate_material_effect','updated_at','timestamp with time zone',true,'now()');
    PERFORM pg_temp.mig_con('plate_material_effect','plate_material_effect_pkey','p',ARRAY['id']);
    PERFORM pg_temp.mig_con('plate_material_effect','uk_plate_material_effect_key','u',ARRAY['tenant_id','source_run_id','source_node_key','source_loop_index','effect_index']);
    PERFORM pg_temp.mig_con('plate_material_effect','ck_effect_effect_index','c',ARRAY['effect_index']);
    PERFORM pg_temp.mig_con('plate_material_effect','ck_effect_status','c',ARRAY['status','PENDING','APPLIED','REJECTED','FAILED','NEEDS_RECONCILIATION','CANCELLED']);
    PERFORM pg_temp.mig_con('plate_material_effect','ck_effect_applicable_target','c',ARRAY['status','plate_id','expected_state_version']);
    PERFORM pg_temp.mig_con('plate_material_effect','ck_effect_applied','c',ARRAY['status','applied_at']);
    PERFORM pg_temp.mig_con('plate_material_effect','ck_effect_hash_format','c',ARRAY['declaration_hash','payload_hash','64']);
    PERFORM pg_temp.mig_con('plate_material_effect','ck_effect_declaration_mode','c',ARRAY['declaration','mode']);
    RAISE NOTICE 'VALIDATE plate_material_effect';
  END IF;
END $$;

DO $$
BEGIN
  IF to_regclass('public.ix_plate_material_effect_worker') IS NULL THEN
    CREATE INDEX ix_plate_material_effect_worker
      ON plate_material_effect (tenant_id, next_retry_at, created_at)
      WHERE status = 'PENDING';
    RAISE NOTICE 'CREATE  ix_plate_material_effect_worker (PARTIAL)';
  ELSE
    PERFORM pg_temp.mig_idx('ix_plate_material_effect_worker','plate_material_effect',
      ARRAY['tenant_id','next_retry_at','created_at'], false, ARRAY['status','PENDING'], true);
  END IF;
  IF to_regclass('public.ix_plate_material_effect_attention') IS NULL THEN
    CREATE INDEX ix_plate_material_effect_attention
      ON plate_material_effect (tenant_id, status, updated_at)
      WHERE status IN ('FAILED','REJECTED','NEEDS_RECONCILIATION');
    RAISE NOTICE 'CREATE  ix_plate_material_effect_attention (PARTIAL)';
  ELSE
    PERFORM pg_temp.mig_idx('ix_plate_material_effect_attention','plate_material_effect',
      ARRAY['tenant_id','status','updated_at'], false, ARRAY['status','FAILED','REJECTED','NEEDS_RECONCILIATION'], true);
  END IF;
  IF to_regclass('public.ix_plate_material_effect_run') IS NULL THEN
    CREATE INDEX ix_plate_material_effect_run
      ON plate_material_effect (tenant_id, source_run_id);
    RAISE NOTICE 'CREATE  ix_plate_material_effect_run';
  ELSE
    PERFORM pg_temp.mig_idx('ix_plate_material_effect_run','plate_material_effect',
      ARRAY['tenant_id','source_run_id'], false, '{}', false);
  END IF;
END $$;

-- =============================================================================
-- ⑦ plate_material_effect_action
-- =============================================================================
DO $$
BEGIN
  IF to_regclass('public.plate_material_effect_action') IS NULL THEN
    CREATE TABLE plate_material_effect_action (
      id                bigserial PRIMARY KEY,
      tenant_id         bigint      NOT NULL,
      effect_id         bigint      NOT NULL,
      action_type       varchar(16) NOT NULL,
      from_status       varchar(24),
      to_status         varchar(24) NOT NULL,
      actor_type        varchar(16) NOT NULL,
      operator_user_id  bigint,
      reason            varchar(500),
      evidence_snapshot jsonb       NOT NULL DEFAULT '{}'::jsonb,
      error_code        varchar(64),
      created_at        timestamptz NOT NULL DEFAULT now(),
      CONSTRAINT ck_action_type  CHECK (action_type IN ('DECLARE','APPLY','RETRY','RECONCILE','CANCEL','REBASE')),
      CONSTRAINT ck_action_actor CHECK (actor_type IN ('AUTO','MANUAL')),
      CONSTRAINT ck_action_manual_identity CHECK
        (actor_type <> 'MANUAL' OR (operator_user_id IS NOT NULL AND reason IS NOT NULL AND btrim(reason) <> '')),
      CONSTRAINT ck_action_status CHECK
        ((from_status IS NULL OR from_status IN ('PENDING','APPLIED','REJECTED','FAILED','NEEDS_RECONCILIATION','CANCELLED'))
          AND to_status IN ('PENDING','APPLIED','REJECTED','FAILED','NEEDS_RECONCILIATION','CANCELLED'))
    );
    RAISE NOTICE 'CREATE  plate_material_effect_action';
  ELSE
    PERFORM pg_temp.mig_col('plate_material_effect_action','id','bigint',true,'nextval(''plate_material_effect_action_id_seq''::regclass)');
    PERFORM pg_temp.mig_col('plate_material_effect_action','tenant_id','bigint',true,NULL);
    PERFORM pg_temp.mig_col('plate_material_effect_action','effect_id','bigint',true,NULL);
    PERFORM pg_temp.mig_col('plate_material_effect_action','action_type','character varying(16)',true,NULL);
    PERFORM pg_temp.mig_col('plate_material_effect_action','from_status','character varying(24)',false,NULL);
    PERFORM pg_temp.mig_col('plate_material_effect_action','to_status','character varying(24)',true,NULL);
    PERFORM pg_temp.mig_col('plate_material_effect_action','actor_type','character varying(16)',true,NULL);
    PERFORM pg_temp.mig_col('plate_material_effect_action','operator_user_id','bigint',false,NULL);
    PERFORM pg_temp.mig_col('plate_material_effect_action','reason','character varying(500)',false,NULL);
    PERFORM pg_temp.mig_col('plate_material_effect_action','evidence_snapshot','jsonb',true,'''{}''::jsonb');
    PERFORM pg_temp.mig_col('plate_material_effect_action','error_code','character varying(64)',false,NULL);
    PERFORM pg_temp.mig_col('plate_material_effect_action','created_at','timestamp with time zone',true,'now()');
    PERFORM pg_temp.mig_con('plate_material_effect_action','plate_material_effect_action_pkey','p',ARRAY['id']);
    PERFORM pg_temp.mig_con('plate_material_effect_action','ck_action_type','c',ARRAY['action_type','DECLARE','APPLY','RETRY','RECONCILE','CANCEL','REBASE']);
    PERFORM pg_temp.mig_con('plate_material_effect_action','ck_action_actor','c',ARRAY['actor_type','AUTO','MANUAL']);
    PERFORM pg_temp.mig_con('plate_material_effect_action','ck_action_manual_identity','c',ARRAY['actor_type','MANUAL','operator_user_id','reason']);
    PERFORM pg_temp.mig_con('plate_material_effect_action','ck_action_status','c',ARRAY['from_status','to_status','PENDING','APPLIED','REJECTED','FAILED','NEEDS_RECONCILIATION','CANCELLED']);
    RAISE NOTICE 'VALIDATE plate_material_effect_action';
  END IF;
END $$;

DO $$
BEGIN
  IF to_regclass('public.ix_material_action_effect') IS NULL THEN
    CREATE INDEX ix_material_action_effect ON plate_material_effect_action (tenant_id, effect_id, created_at, id);
    RAISE NOTICE 'CREATE  ix_material_action_effect';
  ELSE
    PERFORM pg_temp.mig_idx('ix_material_action_effect','plate_material_effect_action',
      ARRAY['tenant_id','effect_id','created_at','id'], false, '{}', false);
  END IF;
END $$;

-- =============================================================================
-- ⑧ plate_material_event
-- =============================================================================
DO $$
BEGIN
  IF to_regclass('public.plate_material_event') IS NULL THEN
    CREATE TABLE plate_material_event (
      id                 bigserial PRIMARY KEY,
      tenant_id          bigint      NOT NULL,
      plate_id           bigint      NOT NULL,
      effect_id          bigint,
      event_type         varchar(32) NOT NULL,
      from_process_state varchar(32),
      to_process_state   varchar(32),
      from_seal_state    varchar(16),
      to_seal_state      varchar(16),
      changed_fields     jsonb       NOT NULL,
      from_state_version bigint      NOT NULL,
      to_state_version   bigint      NOT NULL,
      source_run_id      bigint,
      source_node_key    varchar(64),
      source_loop_index  int         NOT NULL DEFAULT 0,
      command_id         varchar(160),
      evidence           varchar(24) NOT NULL,
      evidence_level     varchar(24) NOT NULL,
      evidence_snapshot  jsonb       NOT NULL DEFAULT '{}'::jsonb,
      operator_type      varchar(16) NOT NULL,
      operator_user_id   bigint,
      note               varchar(255),
      occurred_at        timestamptz,
      recorded_at        timestamptz NOT NULL DEFAULT now(),
      CONSTRAINT ck_pme_evidence CHECK (evidence IN
        ('DEVICE_CONFIRMED','EXECUTION_DECLARED','MANUAL_INVENTORY','MANUAL_CORRECTION')),
      CONSTRAINT ck_pme_evidence_level CHECK (evidence_level IN
        ('device_ack','physical_confirmed','manual_confirmed','legacy')),
      CONSTRAINT ck_pme_operator CHECK (operator_type IN ('AUTO','MANUAL')),
      CONSTRAINT ck_pme_changed_fields CHECK
        (jsonb_typeof(changed_fields) = 'array'
          AND changed_fields <@ '["process_state","seal_state"]'::jsonb),
      CONSTRAINT ck_pme_changed_process CHECK
        ((changed_fields ? 'process_state') = (from_process_state IS DISTINCT FROM to_process_state)),
      CONSTRAINT ck_pme_changed_seal CHECK
        ((changed_fields ? 'seal_state') = (from_seal_state IS DISTINCT FROM to_seal_state)),
      CONSTRAINT ck_pme_version_step CHECK (to_state_version = from_state_version + 1),
      CONSTRAINT ck_pme_auto_source CHECK
        (operator_type <> 'AUTO' OR (effect_id IS NOT NULL AND source_run_id IS NOT NULL AND source_node_key IS NOT NULL)),
      CONSTRAINT ck_pme_manual_actor CHECK
        (operator_type <> 'MANUAL' OR (operator_user_id IS NOT NULL AND note IS NOT NULL AND btrim(note) <> '')),
      CONSTRAINT ck_pme_event_type CHECK (event_type IN
        ('state_changed','state_confirmed','manual_inventory','manual_correction')),
      CONSTRAINT ck_pme_seal_domain CHECK
        ((from_seal_state IS NULL OR from_seal_state IN ('none','sealed'))
          AND (to_seal_state IS NULL OR to_seal_state IN ('none','sealed'))),
      CONSTRAINT ck_pme_dimensions CHECK (source_loop_index >= 0),
      CONSTRAINT ck_pme_versions_nonneg CHECK (from_state_version >= 0 AND to_state_version > from_state_version)
    );
    RAISE NOTICE 'CREATE  plate_material_event';
  ELSE
    PERFORM pg_temp.mig_col('plate_material_event','id','bigint',true,'nextval(''plate_material_event_id_seq''::regclass)');
    PERFORM pg_temp.mig_col('plate_material_event','tenant_id','bigint',true,NULL);
    PERFORM pg_temp.mig_col('plate_material_event','plate_id','bigint',true,NULL);
    PERFORM pg_temp.mig_col('plate_material_event','effect_id','bigint',false,NULL);
    PERFORM pg_temp.mig_col('plate_material_event','event_type','character varying(32)',true,NULL);
    PERFORM pg_temp.mig_col('plate_material_event','from_process_state','character varying(32)',false,NULL);
    PERFORM pg_temp.mig_col('plate_material_event','to_process_state','character varying(32)',false,NULL);
    PERFORM pg_temp.mig_col('plate_material_event','from_seal_state','character varying(16)',false,NULL);
    PERFORM pg_temp.mig_col('plate_material_event','to_seal_state','character varying(16)',false,NULL);
    PERFORM pg_temp.mig_col('plate_material_event','changed_fields','jsonb',true,NULL);
    PERFORM pg_temp.mig_col('plate_material_event','from_state_version','bigint',true,NULL);
    PERFORM pg_temp.mig_col('plate_material_event','to_state_version','bigint',true,NULL);
    PERFORM pg_temp.mig_col('plate_material_event','source_run_id','bigint',false,NULL);
    PERFORM pg_temp.mig_col('plate_material_event','source_node_key','character varying(64)',false,NULL);
    PERFORM pg_temp.mig_col('plate_material_event','source_loop_index','integer',true,'0');
    PERFORM pg_temp.mig_col('plate_material_event','command_id','character varying(160)',false,NULL);
    PERFORM pg_temp.mig_col('plate_material_event','evidence','character varying(24)',true,NULL);
    PERFORM pg_temp.mig_col('plate_material_event','evidence_level','character varying(24)',true,NULL);
    PERFORM pg_temp.mig_col('plate_material_event','evidence_snapshot','jsonb',true,'''{}''::jsonb');
    PERFORM pg_temp.mig_col('plate_material_event','operator_type','character varying(16)',true,NULL);
    PERFORM pg_temp.mig_col('plate_material_event','operator_user_id','bigint',false,NULL);
    PERFORM pg_temp.mig_col('plate_material_event','note','character varying(255)',false,NULL);
    PERFORM pg_temp.mig_col('plate_material_event','occurred_at','timestamp with time zone',false,NULL);
    PERFORM pg_temp.mig_col('plate_material_event','recorded_at','timestamp with time zone',true,'now()');
    PERFORM pg_temp.mig_con('plate_material_event','plate_material_event_pkey','p',ARRAY['id']);
    PERFORM pg_temp.mig_con('plate_material_event','ck_pme_evidence','c',ARRAY['evidence','DEVICE_CONFIRMED','EXECUTION_DECLARED','MANUAL_INVENTORY','MANUAL_CORRECTION']);
    PERFORM pg_temp.mig_con('plate_material_event','ck_pme_evidence_level','c',ARRAY['evidence_level','device_ack','physical_confirmed','manual_confirmed','legacy']);
    PERFORM pg_temp.mig_con('plate_material_event','ck_pme_operator','c',ARRAY['operator_type','AUTO','MANUAL']);
    PERFORM pg_temp.mig_con('plate_material_event','ck_pme_changed_fields','c',ARRAY['changed_fields','process_state','seal_state']);
    PERFORM pg_temp.mig_con('plate_material_event','ck_pme_changed_process','c',ARRAY['changed_fields','process_state','from_process_state','to_process_state']);
    PERFORM pg_temp.mig_con('plate_material_event','ck_pme_changed_seal','c',ARRAY['changed_fields','seal_state','from_seal_state','to_seal_state']);
    PERFORM pg_temp.mig_con('plate_material_event','ck_pme_version_step','c',ARRAY['to_state_version','from_state_version']);
    PERFORM pg_temp.mig_con('plate_material_event','ck_pme_auto_source','c',ARRAY['operator_type','AUTO','effect_id','source_run_id','source_node_key']);
    PERFORM pg_temp.mig_con('plate_material_event','ck_pme_manual_actor','c',ARRAY['operator_type','MANUAL','operator_user_id','note']);
    PERFORM pg_temp.mig_con('plate_material_event','ck_pme_event_type','c',ARRAY['event_type','state_changed','state_confirmed','manual_inventory','manual_correction']);
    PERFORM pg_temp.mig_con('plate_material_event','ck_pme_seal_domain','c',ARRAY['from_seal_state','to_seal_state','none','sealed']);
    PERFORM pg_temp.mig_con('plate_material_event','ck_pme_dimensions','c',ARRAY['source_loop_index']);
    PERFORM pg_temp.mig_con('plate_material_event','ck_pme_versions_nonneg','c',ARRAY['from_state_version','to_state_version']);
    RAISE NOTICE 'VALIDATE plate_material_event';
  END IF;
END $$;

DO $$
BEGIN
  IF to_regclass('public.uk_pme_effect') IS NULL THEN
    CREATE UNIQUE INDEX uk_pme_effect ON plate_material_event (tenant_id, effect_id) WHERE effect_id IS NOT NULL;
    RAISE NOTICE 'CREATE  uk_pme_effect (PARTIAL UNIQUE)';
  ELSE
    PERFORM pg_temp.mig_idx('uk_pme_effect','plate_material_event',
      ARRAY['tenant_id','effect_id'], true, ARRAY['effect_id'], true);
  END IF;
  IF to_regclass('public.uk_pme_version') IS NULL THEN
    CREATE UNIQUE INDEX uk_pme_version ON plate_material_event (tenant_id, plate_id, to_state_version);
    RAISE NOTICE 'CREATE  uk_pme_version';
  ELSE
    PERFORM pg_temp.mig_idx('uk_pme_version','plate_material_event',
      ARRAY['tenant_id','plate_id','to_state_version'], true, '{}', false);
  END IF;
  IF to_regclass('public.ix_pme_plate') IS NULL THEN
    CREATE INDEX ix_pme_plate ON plate_material_event (tenant_id, plate_id, to_state_version, id);
    RAISE NOTICE 'CREATE  ix_pme_plate';
  ELSE
    PERFORM pg_temp.mig_idx('ix_pme_plate','plate_material_event',
      ARRAY['tenant_id','plate_id','to_state_version','id'], false, '{}', false);
  END IF;
  IF to_regclass('public.ix_pme_run') IS NULL THEN
    CREATE INDEX ix_pme_run ON plate_material_event (tenant_id, source_run_id);
    RAISE NOTICE 'CREATE  ix_pme_run';
  ELSE
    PERFORM pg_temp.mig_idx('ix_pme_run','plate_material_event',
      ARRAY['tenant_id','source_run_id'], false, '{}', false);
  END IF;
END $$;

COMMIT;
