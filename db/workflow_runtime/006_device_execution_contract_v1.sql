-- 006_device_execution_contract_v1.sql
-- Promoted from docs/ddl/006_device_execution_contract_v1.draft.sql (Phase 3B Contract v1.8, Stage 0 freeze).

BEGIN;

-- 1. Table #33: Workstation Sidecar registry & connection dimension (single-Kernel scope).
--    Created first so command_ledger and device_state_projection can enforce FK integrity.
--    connection_generation is strictly a local TCP connection counter used under V1
--    SingleInstanceHostLock to reject stale frames from closed sockets of the same host.
CREATE TABLE IF NOT EXISTS workflow_runtime.workstation_sidecar (
  tenant_id               uuid        NOT NULL REFERENCES workflow_runtime.tenant,
  workstation_id          text        NOT NULL CHECK (workstation_id ~ '[^[:space:]]'),
  endpoint_uri            text        NOT NULL,
  connection_generation   bigint      NOT NULL DEFAULT 0 CHECK (connection_generation >= 0),
  sidecar_instance_id     text,
  storage_state           text        CHECK (storage_state IS NULL OR storage_state IN ('PERSISTED', 'FRESH_STORAGE', 'CORRUPTED')),
  security_mode           text        NOT NULL DEFAULT 'TLS13_MTLS'
                                      CHECK (security_mode IN ('DEV_LOOPBACK_PLAINTEXT', 'TLS13_MTLS')),
  cert_fingerprint_sha256 text        CHECK (cert_fingerprint_sha256 IS NULL OR cert_fingerprint_sha256 ~ '^[0-9a-f]{64}$'),
  connection_state        text        NOT NULL DEFAULT 'OFFLINE'
                                      CHECK (connection_state IN ('ONLINE', 'DEGRADED', 'OFFLINE')),
  heartbeat_interval_ms   integer     NOT NULL DEFAULT 3000 CHECK (heartbeat_interval_ms > 0),
  heartbeat_timeout_ms    integer     NOT NULL DEFAULT 10000 CHECK (heartbeat_timeout_ms > 0),
  last_heartbeat_at       timestamptz,
  updated_at              timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, workstation_id)
);

-- 2. Extend command_ledger with strict workstation ownership, operation metadata, timeouts & outcomes.
--    Under the zero-legacy-compatibility contract, workflow_runtime.command_ledger carries ONLY
--    Contract v1.x physical device commands (zero non-device ledger rows, zero legacy/mock nullable
--    bypass). Preflight fails closed if any pre-existing row lacks non-whitespace workstation_id
--    or operation_name; when blocked on a historical DB, operators MUST follow CORE §6.1 (clean-DB
--    isolation or read-only archival of verified terminal/Phase-2B drill rows — never delete
--    drill records, never backfill dummy placeholders, and never force-close in-flight commands),
--    before enforcing column-level NOT NULL + ~ '[^[:space:]]' CHECK + FK constraints.
ALTER TABLE workflow_runtime.command_ledger
  ADD COLUMN IF NOT EXISTS workstation_id text,
  ADD COLUMN IF NOT EXISTS operation_name text,
  ADD COLUMN IF NOT EXISTS timeout_ms integer NOT NULL DEFAULT 300000 CHECK (timeout_ms > 0),
  ADD COLUMN IF NOT EXISTS interruptible boolean NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS last_receipt_at timestamptz,
  ADD COLUMN IF NOT EXISTS command_result jsonb NOT NULL DEFAULT '{}'::jsonb,
  ADD COLUMN IF NOT EXISTS reconciliation_evidence jsonb NOT NULL DEFAULT '{}'::jsonb;

DO $command_ledger_v1_preflight$
DECLARE
  invalid_count bigint;
BEGIN
  SELECT count(*)
    INTO invalid_count
    FROM workflow_runtime.command_ledger
   WHERE workstation_id IS NULL OR workstation_id !~ '[^[:space:]]'
      OR operation_name IS NULL OR operation_name !~ '[^[:space:]]';
  IF invalid_count > 0 THEN
    RAISE EXCEPTION
      '006 migration blocked: % existing command_ledger row(s) missing non-whitespace workstation_id or operation_name; follow CORE §6.1 pre-006 verification & read-only archival policy (never delete drill data or backfill dummy placeholders)',
      invalid_count
      USING ERRCODE = '23502';
  END IF;
END
$command_ledger_v1_preflight$;

ALTER TABLE workflow_runtime.command_ledger
  ALTER COLUMN workstation_id SET NOT NULL,
  ALTER COLUMN operation_name SET NOT NULL;

DO $add_command_workstation_fk_and_chk$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
     WHERE conname = 'command_ledger_workstation_fk'
       AND conrelid = 'workflow_runtime.command_ledger'::regclass
  ) THEN
    ALTER TABLE workflow_runtime.command_ledger
      ADD CONSTRAINT command_ledger_workstation_fk
      FOREIGN KEY (tenant_id, workstation_id)
      REFERENCES workflow_runtime.workstation_sidecar (tenant_id, workstation_id);
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
     WHERE conname = 'command_ledger_workstation_nonblank_chk'
       AND conrelid = 'workflow_runtime.command_ledger'::regclass
  ) THEN
    ALTER TABLE workflow_runtime.command_ledger
      ADD CONSTRAINT command_ledger_workstation_nonblank_chk
      CHECK (workstation_id ~ '[^[:space:]]' AND operation_name ~ '[^[:space:]]');
  END IF;
END
$add_command_workstation_fk_and_chk$;

-- 3. Extend command_dispatch_attempt to distinguish SEND / QUERY / CANCEL dispatches
--    and track wire rejection / delivery error codes cleanly.
ALTER TABLE workflow_runtime.command_dispatch_attempt
  ADD COLUMN IF NOT EXISTS dispatch_kind text NOT NULL DEFAULT 'SEND'
    CHECK (dispatch_kind IN ('SEND', 'QUERY', 'CANCEL')),
  ADD COLUMN IF NOT EXISTS error_code text;

CREATE INDEX IF NOT EXISTS dispatch_attempt_command_kind_idx
  ON workflow_runtime.command_dispatch_attempt (tenant_id, command_id, dispatch_kind, dispatch_no);

-- 4. Bind quarantine_record to origin & offending UNKNOWN command_id so command-induced
--    quarantines ALWAYS carry command_id (group settle), while non-command faults remain valid.
ALTER TABLE workflow_runtime.quarantine_record
  ADD COLUMN IF NOT EXISTS quarantine_origin text NOT NULL DEFAULT 'MANUAL_OR_LEGACY'
    CHECK (quarantine_origin IN ('COMMAND_UNKNOWN', 'TRANSFER_IN_FLIGHT', 'HARDWARE_FAULT', 'MANUAL_OR_LEGACY')),
  ADD COLUMN IF NOT EXISTS command_id uuid;

DO $add_quarantine_command_fk_and_chk$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
     WHERE conname = 'quarantine_record_command_fk'
       AND conrelid = 'workflow_runtime.quarantine_record'::regclass
  ) THEN
    ALTER TABLE workflow_runtime.quarantine_record
      ADD CONSTRAINT quarantine_record_command_fk
      FOREIGN KEY (tenant_id, command_id)
      REFERENCES workflow_runtime.command_ledger (tenant_id, command_id);
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
     WHERE conname = 'quarantine_command_origin_chk'
       AND conrelid = 'workflow_runtime.quarantine_record'::regclass
  ) THEN
    ALTER TABLE workflow_runtime.quarantine_record
      ADD CONSTRAINT quarantine_command_origin_chk
      CHECK (
        (quarantine_origin IN ('COMMAND_UNKNOWN', 'TRANSFER_IN_FLIGHT') AND command_id IS NOT NULL)
        OR quarantine_origin IN ('HARDWARE_FAULT', 'MANUAL_OR_LEGACY')
      );
  END IF;
END
$add_quarantine_command_fk_and_chk$;

CREATE INDEX IF NOT EXISTS quarantine_open_by_command_idx
  ON workflow_runtime.quarantine_record (tenant_id, command_id)
  WHERE quarantine_state IN ('ACTIVE', 'CLEAR_PENDING') AND command_id IS NOT NULL;

-- 5. Table #34: Device observation state & capability projection (orthogonal to connection & command ledger).
--    PRIMARY KEY (tenant_id, device_id) with workstation_id NOT NULL enforces single-workstation
--    ownership per device at the database level; active_command_id is FK-checked against command_ledger.
CREATE TABLE IF NOT EXISTS workflow_runtime.device_state_projection (
  tenant_id           uuid        NOT NULL,
  device_id           text        NOT NULL,
  workstation_id      text        NOT NULL,
  device_model        text,
  device_state        text        NOT NULL DEFAULT 'UNKNOWN_OBS'
                                  CHECK (device_state IN ('IDLE', 'BUSY', 'FAULTED', 'WAITING_INTERACTION', 'UNKNOWN_OBS')),
  active_command_id   uuid,
  device_sequence     text,
  fault_code          text,
  capabilities        jsonb       NOT NULL DEFAULT '{}'::jsonb,
  observation_ttl_ms  integer     NOT NULL DEFAULT 12000 CHECK (observation_ttl_ms > 0),
  observed_at         timestamptz,
  updated_at          timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, device_id),
  FOREIGN KEY (tenant_id, workstation_id)
    REFERENCES workflow_runtime.workstation_sidecar (tenant_id, workstation_id),
  FOREIGN KEY (tenant_id, active_command_id)
    REFERENCES workflow_runtime.command_ledger (tenant_id, command_id)
);

CREATE INDEX IF NOT EXISTS device_projection_workstation_idx
  ON workflow_runtime.device_state_projection (tenant_id, workstation_id, device_state);

-- 6. Table #35: G5.1 Early Wait Signal Queue strictly referencing authoritative inbox_receipt 1:1.
--    Adding UNIQUE (tenant_id, inbox_id, run_id) on inbox_receipt enables a composite FK
--    from wait_early_signal_buffer(tenant_id, inbox_id, run_id), proving at PG schema level
--    that buffer.run_id ALWAYS equals inbox_receipt.run_id (non-null) with zero copied payload.
DO $add_inbox_run_uq$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
     WHERE conname = 'inbox_receipt_tenant_inbox_run_uq'
       AND conrelid = 'workflow_runtime.inbox_receipt'::regclass
  ) THEN
    ALTER TABLE workflow_runtime.inbox_receipt
      ADD CONSTRAINT inbox_receipt_tenant_inbox_run_uq
      UNIQUE (tenant_id, inbox_id, run_id);
  END IF;
END
$add_inbox_run_uq$;

CREATE TABLE IF NOT EXISTS workflow_runtime.wait_early_signal_buffer (
  tenant_id         uuid        NOT NULL,
  buffer_id         uuid        NOT NULL DEFAULT gen_random_uuid(),
  inbox_id          uuid        NOT NULL,
  run_id            uuid        NOT NULL,
  correlation_key   text        NOT NULL,
  buffer_state      text        NOT NULL DEFAULT 'BUFFERED'
                                CHECK (buffer_state IN ('BUFFERED', 'CONSUMED', 'EXPIRED')),
  expires_at        timestamptz NOT NULL,
  consumed_wait_id  uuid,
  created_at        timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, buffer_id),
  UNIQUE (tenant_id, inbox_id),
  FOREIGN KEY (tenant_id, inbox_id, run_id)
    REFERENCES workflow_runtime.inbox_receipt (tenant_id, inbox_id, run_id),
  FOREIGN KEY (tenant_id, run_id)
    REFERENCES workflow_runtime.workflow_run (tenant_id, run_id)
);

CREATE INDEX IF NOT EXISTS wait_early_signal_pending_idx
  ON workflow_runtime.wait_early_signal_buffer (tenant_id, run_id, correlation_key, created_at)
  WHERE buffer_state = 'BUFFERED';

CREATE INDEX IF NOT EXISTS wait_early_signal_expiry_idx
  ON workflow_runtime.wait_early_signal_buffer (expires_at)
  WHERE buffer_state = 'BUFFERED';

COMMIT;
