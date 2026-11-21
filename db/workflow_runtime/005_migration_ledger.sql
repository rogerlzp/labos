-- Phase 3 Migration Version Ledger: tracks applied workflow_runtime schema migrations
-- and SHA-256 checksums for idempotent re-runs and tamper / drift detection.
BEGIN;

CREATE TABLE IF NOT EXISTS workflow_runtime.schema_migration (
  version      text PRIMARY KEY,
  filename     text NOT NULL,
  sha256       text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
  mode         text NOT NULL DEFAULT 'STANDARD'
               CHECK (mode IN ('STANDARD', 'ONLINE_CONCURRENTLY', 'BASELINE_ADOPTED')),
  applied_at   timestamptz NOT NULL DEFAULT clock_timestamp(),
  applied_by   text NOT NULL DEFAULT current_user
);

COMMIT;
