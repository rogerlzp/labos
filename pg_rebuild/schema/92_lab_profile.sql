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
ALTER TABLE ONLY lab_profile
ADD CONSTRAINT lab_profile_pkey PRIMARY KEY (tenant_id);
ALTER TABLE ONLY lab_profile
ADD CONSTRAINT lab_profile_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenant(id);
