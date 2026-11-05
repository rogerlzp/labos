-- ============================================================================
-- 历史脏数据修复：把 name 里的**字面量** \uXXXX 还原成真正的中文字符
--
-- 现象：/experiments 列表里名称显示成 "verify-\u5e76\u53d1-1"，看起来像乱码。
-- 根因：早期内核的 jsonField 没有解码 JSON 的 Unicode 转义，标准 JSON 客户端
--       （Python json.dumps 默认 ensure_ascii=True）会把中文发成 \uXXXX，
--       后端当时把转义序列当普通字符串存进了库。
-- 现状：ApiServer.jsonField → unescapeJson 已解码（2026-11-04 起），新写入正常；
--       本脚本只修 2026-11-03 及更早留下的存量。
--
-- 幂等：只匹配 name 里含 \u[0-9a-fA-F]{4} 的行，重复执行无副作用。
-- 用法：psql -v ON_ERROR_STOP=1 -h <host> -U <user> -d labdb -f scripts/fix-unicode-escapes.sql
-- 建议：执行前先导出受影响行（脚本末尾有查询），执行后复核。
-- ============================================================================

CREATE OR REPLACE FUNCTION pg_temp.unescape_unicode(s text) RETURNS text AS $$
DECLARE
  r    text := s;
  m    text[];
  code int;
BEGIN
  LOOP
    m := regexp_match(r, '\\u([0-9a-fA-F]{4})');
    EXIT WHEN m IS NULL;
    code := ('x' || m[1])::bit(16)::int;
    -- chr(0) 与代理区（D800–DFFF）会报错或产生非法 UTF-8；正常业务名称不会出现，
    -- 真遇到就停下，交给人判断，不猜。
    IF code <= 0 OR (code BETWEEN 55296 AND 57343) OR code > 1114111 THEN
      EXIT;
    END IF;
    r := regexp_replace(r, '\\u' || m[1], chr(code), 1);
  END LOOP;
  RETURN r;
END $$ LANGUAGE plpgsql;

-- 执行前：受影响行（人工留证）
SELECT 'experiment' AS tbl, id, name
  FROM experiment WHERE name ~ '\\u[0-9a-fA-F]{4}'
UNION ALL
SELECT 'workflow', id, name
  FROM workflow WHERE name ~ '\\u[0-9a-fA-F]{4}'
 ORDER BY 1, 2;

BEGIN;

UPDATE experiment SET name = pg_temp.unescape_unicode(name)
 WHERE name ~ '\\u[0-9a-fA-F]{4}';

UPDATE workflow SET name = pg_temp.unescape_unicode(name)
 WHERE name ~ '\\u[0-9a-fA-F]{4}';

COMMIT;

-- 执行后：残留必须为 0，抽样看真实中文
SELECT count(*) AS remaining
  FROM experiment WHERE name ~ '\\u[0-9a-fA-F]{4}';
SELECT count(*) AS remaining
  FROM workflow WHERE name ~ '\\u[0-9a-fA-F]{4}';
SELECT id, name FROM experiment WHERE id IN (829, 830, 832, 833, 990) ORDER BY id;
SELECT id, name FROM workflow WHERE id IN (1442, 1445) ORDER BY id;
