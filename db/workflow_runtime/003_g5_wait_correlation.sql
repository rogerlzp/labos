-- G5 A1-1: one active Wait per Run/correlationKey.
-- Apply once after 001_init.sql. Existing active duplicates fail closed; they are
-- intentionally not auto-cancelled or merged because that would alter live Runs.
BEGIN;

DO $g5_wait_correlation_preflight$
DECLARE
  duplicate_key record;
BEGIN
  SELECT tenant_id, run_id, correlation_key, count(*) AS active_count
    INTO duplicate_key
    FROM workflow_runtime.wait_registration
   WHERE wait_state = 'ACTIVE'
     AND correlation_key IS NOT NULL
   GROUP BY tenant_id, run_id, correlation_key
  HAVING count(*) > 1
   ORDER BY tenant_id, run_id, correlation_key
   LIMIT 1;

  IF FOUND THEN
    RAISE EXCEPTION
      'G5 migration blocked: duplicate ACTIVE Wait correlation key (tenant_id=%, run_id=%, correlation_key=%, count=%)',
      duplicate_key.tenant_id, duplicate_key.run_id,
      duplicate_key.correlation_key, duplicate_key.active_count
      USING ERRCODE = '23505';
  END IF;
END
$g5_wait_correlation_preflight$;

CREATE UNIQUE INDEX wait_active_correlation_uq
  ON workflow_runtime.wait_registration (tenant_id, run_id, correlation_key)
  WHERE wait_state = 'ACTIVE' AND correlation_key IS NOT NULL;

COMMIT;
