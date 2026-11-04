-- =============================================================================
-- Schema 自检脚本：建表完成后执行，用于确认 52 张表/视图/约束都在
--   psql -h <HOST> -U <USER> -d labos -f 99_verify.sql
-- =============================================================================
\echo '========== 1. 对象计数 =========='
SELECT
  (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
     WHERE n.nspname='public' AND c.relkind='r')                        AS 普通表,
  (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
     WHERE n.nspname='public' AND c.relkind='p')                        AS 分区表,
  (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
     WHERE n.nspname='public' AND c.relkind='v')                        AS 视图,
  (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
     WHERE n.nspname='public' AND c.relkind='i')                        AS 索引,
  (SELECT count(*) FROM pg_constraint WHERE contype='f')                AS 外键,
  (SELECT count(*) FROM pg_constraint WHERE contype='c')                AS 检查约束,
  (SELECT count(*) FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace
     WHERE n.nspname='public' AND t.typtype='e')                        AS 枚举类型;
-- 期望：普通表+分区表 ≈ 51/52，视图 3，枚举 9

\echo '========== 2. 分区情况 =========='
SELECT parent.relname AS 父表, count(*) AS 分区数
FROM pg_inherits i
JOIN pg_class parent ON parent.oid = i.inhparent
GROUP BY 1 ORDER BY 1;

\echo '========== 3. 按域统计表数量 =========='
SELECT CASE
    WHEN c.relname LIKE 'run%'      THEN '01 执行内核'
    WHEN c.relname LIKE 'workflow%' OR c.relname LIKE 'protocol%' THEN '02 工艺编排'
    WHEN c.relname IN ('resource_node','resource_capability','resource_booking','capability') THEN '03 资源与能力'
    WHEN c.relname LIKE 'driver%'   THEN '04 驱动'
    WHEN c.relname LIKE 'schedule%' THEN '05 调度'
    WHEN c.relname LIKE 'error%' OR c.relname LIKE 'recovery%' THEN '06 错误恢复'
    WHEN c.relname IN ('sample','sample_aliquot','plate','plate_well','sample_order','sample_order_item') THEN '07 样本'
    WHEN c.relname LIKE 'consumable%' OR c.relname LIKE 'reagent%' THEN '08 耗材试剂'
    WHEN c.relname IN ('project','experiment','experiment_sample') THEN '09 项目实验'
    WHEN c.relname LIKE 'device%'   THEN '10 设备'
    WHEN c.relname IN ('tenant','app_user','app_role','permission','user_role','role_permission','dict_item','sys_setting') THEN '11 平台'
    WHEN c.relname LIKE 'notify%' OR c.relname IN ('notification','audit_log','file_blob') THEN '12 运维'
    ELSE '99 分区子表/其它' END AS 域, count(*) AS 表数
FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE n.nspname='public' AND c.relkind IN ('r','p')
GROUP BY 1 ORDER BY 1;

\echo '========== 4. 关键约束检查（应全部 exists） =========='
SELECT '资源重叠约束 ex_booking_overlap' AS 检查项,
       CASE WHEN EXISTS (SELECT 1 FROM pg_constraint WHERE conname='ex_booking_overlap')
            THEN 'exists' ELSE 'MISSING (btree_gist 缺失时属预期)' END AS 结果
UNION ALL
SELECT '唯一索引 uk_run_variable',
       CASE WHEN EXISTS (SELECT 1 FROM pg_indexes WHERE indexname='uk_run_variable')
            THEN 'exists' ELSE 'MISSING' END
UNION ALL
SELECT '分区表 run_event',
       CASE WHEN EXISTS (SELECT 1 FROM pg_class WHERE relname='run_event' AND relkind='p')
            THEN 'exists' ELSE 'MISSING' END
UNION ALL
SELECT '分区表 device_log',
       CASE WHEN EXISTS (SELECT 1 FROM pg_class WHERE relname='device_log' AND relkind='p')
            THEN 'exists' ELSE 'MISSING' END
UNION ALL
SELECT '触发器 trg_resource_node_path',
       CASE WHEN EXISTS (SELECT 1 FROM pg_trigger WHERE tgname='trg_resource_node_path')
            THEN 'exists' ELSE 'MISSING' END
UNION ALL
SELECT 'updated_at 触发器数量',
       (SELECT count(*)::text FROM pg_trigger WHERE tgname LIKE 'trg_%_updated_at')
UNION ALL
SELECT '种子数据 capability',
       (SELECT count(*)::text || ' 条' FROM capability)
UNION ALL
SELECT '种子数据 tenant',
       (SELECT count(*)::text || ' 条' FROM tenant);

\echo '========== 5. 功能验证：资源冲突是否被数据库拒绝 =========='
DO $$
DECLARE
  v_res bigint;
  v_ok1 boolean := false;
  v_ok2 boolean := false;
BEGIN
  INSERT INTO resource_node (tenant_id, code, name, kind)
  VALUES (1, 'SELFTEST_RES', '自检资源', 'device')
  ON CONFLICT (tenant_id, code) DO UPDATE SET name = EXCLUDED.name
  RETURNING id INTO v_res;

  INSERT INTO resource_booking (tenant_id, resource_id, period, status, source_type)
  VALUES (1, v_res, tstzrange(now(), now() + interval '1 hour'), 'planned', 'manual');
  v_ok1 := true;

  BEGIN
    INSERT INTO resource_booking (tenant_id, resource_id, period, status, source_type)
    VALUES (1, v_res, tstzrange(now() + interval '30 min', now() + interval '90 min'), 'planned', 'manual');
    v_ok2 := false;   -- 不应走到这里
  EXCEPTION WHEN exclusion_violation THEN
    v_ok2 := true;    -- 正确：重叠被拒绝
  END;

  -- P1-26 修复（评审 20261102-03）：原来只 RAISE NOTICE，
  -- 校验失败时脚本照样"成功退出"，CI/部署看不出问题。
  -- 现在失败即抛异常（明确报错 > 静默通过）。
  IF v_ok1 IS NOT TRUE OR v_ok2 IS NOT TRUE THEN
    RAISE EXCEPTION '资源防冲突校验失败：首次预约成功=% 重叠预约被拒绝=%（应均为 true）',
      v_ok1, v_ok2;
  END IF;
  RAISE NOTICE '资源防冲突校验通过：首次预约成功=% 重叠预约被拒绝=%', v_ok1, v_ok2;

  DELETE FROM resource_booking WHERE resource_id = v_res;
  DELETE FROM resource_node   WHERE id = v_res;
END $$;

\echo '========== 6. 完成 =========='
