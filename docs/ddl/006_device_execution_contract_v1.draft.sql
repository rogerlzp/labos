-- Draft 006_device_execution_contract_v1.draft.sql (for Phase 3B contract Draft v1.3 final review).
-- NOTE: This draft sits in docs/ddl/ and is NOT executed by PR #17 CI.
--       Real PostgreSQL 17 DDL & 002_schema_contract CI checks take effect only when
--       promoted to db/workflow_runtime/006_device_execution_contract_v1.sql in Phase 0.
--
-- Paired Phase 0 updates required alongside promotion:
--   1. db/workflow_runtime/002_schema_contract.sql (expected tables: 32 -> 35; document
--      workstation_sidecar.connection_generation as a single-Kernel TCP reconnect guard,
--      NOT a distributed lease/fencing token).
--   2. .github/workflows/workflow-runtime-db.yml (update all 3 table-count checks at
--      lines 86, 102, 111 from 32 to 35).

BEGIN;

-- 1. Table #33: Workstation Sidecar registry & connection dimension (single-Kernel scope).
--    Created first so command_ledger and device_state_projection can enforce FK integrity.
--    connection_generation is strictly a local TCP connection counter used under V1
--    SingleInstanceHostLock to reject stale frames from closed sockets of the same host.
CREATE TABLE IF NOT EXISTS workflow_runtime.workstation_sidecar (
  tenant_id               uuid        NOT NULL REFERENCES workflow_runtime.tenant,
  workstation_id          text        NOT NULL,
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

-- 2. Extend command_ledger with workstation FK, operation metadata, capability timeouts & outcomes.
--    command_ledger_v1_workstation_required_chk guarantees every Contract v1 command (which sets
--    operation_name) MUST bind a non-null workstation_id while keeping legacy/G2 mock rows valid.
ALTER TABLE workflow_runtime.command_ledger
  ADD COLUMN IF NOT EXISTS workstation_id text,
  ADD COLUMN IF NOT EXISTS operation_name text,
  ADD COLUMN IF NOT EXISTS timeout_ms integer NOT NULL DEFAULT 300000 CHECK (timeout_ms > 0),
  ADD COLUMN IF NOT EXISTS interruptible boolean NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS last_receipt_at timestamptz,
  ADD COLUMN IF NOT EXISTS command_result jsonb NOT NULL DEFAULT '{}'::jsonb,
  ADD COLUMN IF NOT EXISTS reconciliation_evidence jsonb NOT NULL DEFAULT '{}'::jsonb;

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
     WHERE conname = 'command_ledger_v1_workstation_required_chk'
       AND conrelid = 'workflow_runtime.command_ledger'::regclass
  ) THEN
    ALTER TABLE workflow_runtime.command_ledger
      ADD CONSTRAINT command_ledger_v1_workstation_required_chk
      CHECK (operation_name IS NULL OR workstation_id IS NOT NULL);
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
