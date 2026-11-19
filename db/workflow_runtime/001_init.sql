-- LabOS Workflow Runtime v1 physical schema
-- PostgreSQL 17+. Fresh-database bootstrap only; no legacy tables or migration logic.
-- IDs: UUID. Application SHOULD generate UUIDv7; gen_random_uuid() is a safe UUIDv4 fallback.
-- All timestamps are timestamptz. All tenant-owned rows carry tenant_id.
-- Execute in an empty database with psql -v ON_ERROR_STOP=1.
BEGIN;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE SCHEMA IF NOT EXISTS workflow_runtime;
SET LOCAL search_path = workflow_runtime, public;

CREATE TABLE tenant (
  tenant_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_key text NOT NULL UNIQUE,
  display_name text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE definition_snapshot (
  tenant_id uuid NOT NULL REFERENCES tenant,
  definition_id uuid NOT NULL DEFAULT gen_random_uuid(),
  definition_key text NOT NULL,
  definition_version text NOT NULL,
  definition_hash bytea NOT NULL,
  compiler_version text NOT NULL,
  graph_plan jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, definition_id),
  UNIQUE (tenant_id, definition_key, definition_version, definition_hash),
  CHECK (octet_length(definition_hash) = 32)
);

CREATE TABLE workflow_run (
  tenant_id uuid NOT NULL REFERENCES tenant,
  run_id uuid NOT NULL DEFAULT gen_random_uuid(),
  definition_id uuid NOT NULL,
  status text NOT NULL CHECK (status IN
    ('CREATED','RUNNING','PAUSE_REQUESTED','PAUSING','PAUSED','STOP_REQUESTED','STOPPING',
     'SUCCEEDED','FAILED','STOPPED','MANUAL_REQUIRED','QUARANTINED')),
  control_gate text NOT NULL DEFAULT 'OPEN' CHECK (control_gate IN ('OPEN','PAUSE','STOP','CLOSED')),
  commit_version bigint NOT NULL DEFAULT 0 CHECK (commit_version >= 0),
  event_seq bigint NOT NULL DEFAULT 0 CHECK (event_seq >= 0),
  coordinator_id uuid,
  coordinator_epoch bigint NOT NULL DEFAULT 0 CHECK (coordinator_epoch >= 0),
  recovery_state text NOT NULL DEFAULT 'NOT_REQUIRED'
    CHECK (recovery_state IN ('NOT_REQUIRED','RECOVERING','RECOVERY_COMPLETED','BLOCKED')),
  input jsonb NOT NULL DEFAULT '{}'::jsonb,
  terminal_reason text,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  started_at timestamptz,
  finished_at timestamptz,
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, run_id),
  FOREIGN KEY (tenant_id, definition_id) REFERENCES definition_snapshot(tenant_id, definition_id)
);
CREATE INDEX workflow_run_scheduler_idx ON workflow_run (tenant_id, status, created_at)
  WHERE status IN ('CREATED','RUNNING','PAUSE_REQUESTED','STOP_REQUESTED');

CREATE TABLE run_request_dedup (
  tenant_id uuid NOT NULL REFERENCES tenant,
  request_id text NOT NULL,
  request_kind text NOT NULL,
  payload_hash bytea NOT NULL,
  run_id uuid,
  outcome jsonb,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, request_id),
  FOREIGN KEY (tenant_id, run_id) REFERENCES workflow_run(tenant_id, run_id),
  CHECK (octet_length(payload_hash) = 32)
);

CREATE TABLE execution_token (
  tenant_id uuid NOT NULL,
  token_id uuid NOT NULL DEFAULT gen_random_uuid(),
  run_id uuid NOT NULL,
  token_state text NOT NULL CHECK (token_state IN
    ('READY','RESERVED','RUNNING','WAITING','CONSUMED','INVALIDATED','FAILED')),
  node_key text NOT NULL,
  parent_token_id uuid,
  split_id uuid,
  branch_id uuid,
  loop_context_id uuid,
  token_version bigint NOT NULL DEFAULT 0,
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, token_id),
  FOREIGN KEY (tenant_id, run_id) REFERENCES workflow_run(tenant_id, run_id),
  FOREIGN KEY (tenant_id, parent_token_id) REFERENCES execution_token(tenant_id, token_id)
);
CREATE INDEX token_ready_idx ON execution_token(tenant_id, run_id, created_at) WHERE token_state='READY';
CREATE INDEX token_run_state_idx ON execution_token(tenant_id, run_id, token_state);

CREATE TABLE node_activation (
  tenant_id uuid NOT NULL,
  activation_id uuid NOT NULL DEFAULT gen_random_uuid(),
  run_id uuid NOT NULL,
  token_id uuid NOT NULL,
  activation_no integer NOT NULL CHECK (activation_no > 0),
  node_key text NOT NULL,
  activation_state text NOT NULL CHECK (activation_state IN
    ('READY','RESERVED','RUNNING','WAITING','SUCCEEDED','FAILED','CANCELLED','ABANDONED_SAFE')),
  current_attempt_id uuid,
  activation_version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, activation_id),
  UNIQUE (tenant_id, token_id, activation_no),
  FOREIGN KEY (tenant_id, run_id) REFERENCES workflow_run(tenant_id, run_id),
  FOREIGN KEY (tenant_id, token_id) REFERENCES execution_token(tenant_id, token_id)
);
CREATE INDEX activation_live_idx ON node_activation(tenant_id, run_id, activation_state)
  WHERE activation_state IN ('READY','RESERVED','RUNNING','WAITING');
-- A Token may have historical activations, but only one may be live at a time.
CREATE UNIQUE INDEX activation_one_live_per_token_uq ON node_activation(tenant_id, token_id)
  WHERE activation_state IN ('READY','RESERVED','RUNNING','WAITING');

CREATE TABLE node_attempt (
  tenant_id uuid NOT NULL,
  attempt_id uuid NOT NULL DEFAULT gen_random_uuid(),
  run_id uuid NOT NULL,
  activation_id uuid NOT NULL,
  attempt_no integer NOT NULL CHECK (attempt_no > 0),
  dispatch_id uuid NOT NULL,
  dispatch_epoch bigint NOT NULL,
  attempt_state text NOT NULL CHECK (attempt_state IN
    ('CREATED','DISPATCHED','ACCEPTED','STARTED','SUCCEEDED','FAILED','CANCELLED',
     'ABANDONED_SAFE','UNKNOWN')),
  side_effect_class text NOT NULL DEFAULT 'PURE'
    CHECK (side_effect_class IN ('PURE','REVERSIBLE','IRREVERSIBLE','DEVICE')),
  worker_id text,
  attempt_version bigint NOT NULL DEFAULT 0,
  started_at timestamptz,
  finished_at timestamptz,
  result jsonb,
  error jsonb,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, attempt_id),
  UNIQUE (tenant_id, dispatch_id),
  UNIQUE (tenant_id, activation_id, attempt_no),
  FOREIGN KEY (tenant_id, run_id) REFERENCES workflow_run(tenant_id, run_id),
  FOREIGN KEY (tenant_id, activation_id) REFERENCES node_activation(tenant_id, activation_id)
);
CREATE INDEX attempt_active_idx ON node_attempt(tenant_id, run_id, attempt_state)
  WHERE attempt_state IN ('CREATED','DISPATCHED','ACCEPTED','STARTED','UNKNOWN');
-- An Activation may retain history, but may not have two simultaneously active Attempts.
CREATE UNIQUE INDEX attempt_one_live_per_activation_uq ON node_attempt(tenant_id, activation_id)
  WHERE attempt_state IN ('CREATED','DISPATCHED','ACCEPTED','STARTED','UNKNOWN');

CREATE TABLE scheduler_capacity (
  tenant_id uuid NOT NULL REFERENCES tenant,
  capacity_key text NOT NULL,
  capacity_limit integer NOT NULL CHECK (capacity_limit >= 0),
  reserved_count integer NOT NULL DEFAULT 0 CHECK (reserved_count >= 0),
  capacity_version bigint NOT NULL DEFAULT 0,
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, capacity_key),
  CHECK (reserved_count <= capacity_limit)
);

CREATE TABLE dispatch_reservation (
  tenant_id uuid NOT NULL,
  reservation_id uuid NOT NULL DEFAULT gen_random_uuid(),
  run_id uuid NOT NULL,
  attempt_id uuid NOT NULL,
  capacity_key text NOT NULL,
  reservation_state text NOT NULL CHECK (reservation_state IN ('HELD','RELEASED','EXPIRED_UNVERIFIED')),
  expires_at timestamptz NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  released_at timestamptz,
  PRIMARY KEY (tenant_id, reservation_id),
  UNIQUE (tenant_id, attempt_id),
  FOREIGN KEY (tenant_id, run_id) REFERENCES workflow_run(tenant_id, run_id),
  FOREIGN KEY (tenant_id, attempt_id) REFERENCES node_attempt(tenant_id, attempt_id)
);
CREATE INDEX reservation_expiry_idx ON dispatch_reservation(expires_at)
  WHERE reservation_state='HELD';

CREATE TABLE split_activation (
  tenant_id uuid NOT NULL,
  split_id uuid NOT NULL DEFAULT gen_random_uuid(),
  run_id uuid NOT NULL,
  source_activation_id uuid NOT NULL,
  split_state text NOT NULL CHECK (split_state IN ('OPEN','CLOSED','CANCELLED')),
  expected_branch_count integer NOT NULL CHECK (expected_branch_count >= 0),
  arrived_branch_count integer NOT NULL DEFAULT 0 CHECK (arrived_branch_count >= 0),
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  closed_at timestamptz,
  PRIMARY KEY (tenant_id, split_id),
  FOREIGN KEY (tenant_id, run_id) REFERENCES workflow_run(tenant_id, run_id),
  FOREIGN KEY (tenant_id, source_activation_id) REFERENCES node_activation(tenant_id, activation_id),
  CHECK (arrived_branch_count <= expected_branch_count)
);

CREATE TABLE branch_activation (
  tenant_id uuid NOT NULL,
  branch_id uuid NOT NULL DEFAULT gen_random_uuid(),
  run_id uuid NOT NULL,
  split_id uuid NOT NULL,
  branch_key text NOT NULL,
  branch_state text NOT NULL CHECK (branch_state IN ('ACTIVE','WAITING','ARRIVED','CANCELLED')),
  arrival_activation_id uuid,
  arrived_at timestamptz,
  PRIMARY KEY (tenant_id, branch_id),
  UNIQUE (tenant_id, split_id, branch_key),
  FOREIGN KEY (tenant_id, run_id) REFERENCES workflow_run(tenant_id, run_id),
  FOREIGN KEY (tenant_id, split_id) REFERENCES split_activation(tenant_id, split_id),
  FOREIGN KEY (tenant_id, arrival_activation_id) REFERENCES node_activation(tenant_id, activation_id)
);

CREATE TABLE join_activation (
  tenant_id uuid NOT NULL,
  join_id uuid NOT NULL DEFAULT gen_random_uuid(),
  run_id uuid NOT NULL,
  split_id uuid NOT NULL,
  join_key text NOT NULL,
  join_state text NOT NULL CHECK (join_state IN ('WAITING','RELEASED','CANCELLED')),
  released_at timestamptz,
  version bigint NOT NULL DEFAULT 0,
  PRIMARY KEY (tenant_id, join_id),
  UNIQUE (tenant_id, split_id, join_key),
  FOREIGN KEY (tenant_id, run_id) REFERENCES workflow_run(tenant_id, run_id),
  FOREIGN KEY (tenant_id, split_id) REFERENCES split_activation(tenant_id, split_id)
);

CREATE TABLE loop_context (
  tenant_id uuid NOT NULL,
  loop_context_id uuid NOT NULL DEFAULT gen_random_uuid(),
  run_id uuid NOT NULL,
  loop_key text NOT NULL,
  iteration bigint NOT NULL DEFAULT 0 CHECK (iteration >= 0),
  loop_state text NOT NULL CHECK (loop_state IN ('ACTIVE','COMPLETED','CANCELLED')),
  version bigint NOT NULL DEFAULT 0,
  context_data jsonb NOT NULL DEFAULT '{}'::jsonb,
  PRIMARY KEY (tenant_id, loop_context_id),
  FOREIGN KEY (tenant_id, run_id) REFERENCES workflow_run(tenant_id, run_id)
);

CREATE TABLE wait_registration (
  tenant_id uuid NOT NULL,
  wait_id uuid NOT NULL DEFAULT gen_random_uuid(),
  run_id uuid NOT NULL,
  activation_id uuid NOT NULL,
  wait_state text NOT NULL CHECK (wait_state IN ('ACTIVE','SATISFIED','EXPIRED','CANCELLED')),
  wait_kind text NOT NULL,
  correlation_key text,
  deadline_at timestamptz,
  version bigint NOT NULL DEFAULT 0,
  continuation jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  closed_at timestamptz,
  PRIMARY KEY (tenant_id, wait_id),
  FOREIGN KEY (tenant_id, run_id) REFERENCES workflow_run(tenant_id, run_id),
  FOREIGN KEY (tenant_id, activation_id) REFERENCES node_activation(tenant_id, activation_id)
);
CREATE INDEX wait_due_idx ON wait_registration(deadline_at) WHERE wait_state='ACTIVE';

CREATE TABLE retry_registration (
  tenant_id uuid NOT NULL,
  retry_id uuid NOT NULL DEFAULT gen_random_uuid(),
  run_id uuid NOT NULL,
  activation_id uuid NOT NULL,
  retry_state text NOT NULL CHECK (retry_state IN ('SCHEDULED','FIRED','CANCELLED')),
  due_at timestamptz NOT NULL,
  attempt_no integer NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  retry_policy jsonb NOT NULL,
  PRIMARY KEY (tenant_id, retry_id),
  FOREIGN KEY (tenant_id, run_id) REFERENCES workflow_run(tenant_id, run_id),
  FOREIGN KEY (tenant_id, activation_id) REFERENCES node_activation(tenant_id, activation_id)
);
CREATE INDEX retry_due_idx ON retry_registration(due_at) WHERE retry_state='SCHEDULED';

CREATE TABLE flow_variable (
  tenant_id uuid NOT NULL,
  run_id uuid NOT NULL,
  variable_key text NOT NULL,
  value jsonb NOT NULL,
  value_version bigint NOT NULL DEFAULT 0,
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, run_id, variable_key),
  FOREIGN KEY (tenant_id, run_id) REFERENCES workflow_run(tenant_id, run_id)
);

CREATE TABLE branch_output (
  tenant_id uuid NOT NULL,
  output_id uuid NOT NULL DEFAULT gen_random_uuid(),
  run_id uuid NOT NULL,
  activation_id uuid NOT NULL,
  output_key text NOT NULL,
  value jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, output_id),
  UNIQUE (tenant_id, activation_id, output_key),
  FOREIGN KEY (tenant_id, run_id) REFERENCES workflow_run(tenant_id, run_id),
  FOREIGN KEY (tenant_id, activation_id) REFERENCES node_activation(tenant_id, activation_id)
);

CREATE TABLE runtime_event (
  tenant_id uuid NOT NULL,
  run_id uuid NOT NULL,
  event_seq bigint NOT NULL,
  event_id uuid NOT NULL DEFAULT gen_random_uuid(),
  event_type text NOT NULL,
  aggregate_type text NOT NULL,
  aggregate_id uuid,
  aggregate_version bigint,
  coordinator_epoch bigint NOT NULL,
  causation_id uuid,
  correlation_id uuid,
  payload jsonb NOT NULL,
  occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, run_id, event_seq),
  UNIQUE (tenant_id, event_id),
  FOREIGN KEY (tenant_id, run_id) REFERENCES workflow_run(tenant_id, run_id)
);
CREATE INDEX runtime_event_type_idx ON runtime_event(tenant_id, run_id, event_type, event_seq);

CREATE TABLE inbox_receipt (
  tenant_id uuid NOT NULL REFERENCES tenant,
  inbox_id uuid NOT NULL DEFAULT gen_random_uuid(),
  source text NOT NULL,
  source_message_id text NOT NULL,
  payload_hash bytea NOT NULL,
  message_type text NOT NULL,
  message_payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  run_id uuid,
  received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  receipt_state text NOT NULL DEFAULT 'RECEIVED'
    CHECK (receipt_state IN ('RECEIVED','PROCESSING','PROCESSED','REJECTED','PROTOCOL_VIOLATION')),
  processed_at timestamptz,
  outcome jsonb,
  PRIMARY KEY (tenant_id, inbox_id),
  UNIQUE (tenant_id, source, source_message_id),
  FOREIGN KEY (tenant_id, run_id) REFERENCES workflow_run(tenant_id, run_id),
  CHECK (octet_length(payload_hash) = 32)
);
CREATE INDEX inbox_pending_idx ON inbox_receipt(tenant_id, received_at) WHERE receipt_state='RECEIVED';

-- Preserve conflicting payload identities without overwriting the first receipt.
CREATE TABLE inbox_protocol_violation (
  tenant_id uuid NOT NULL,
  violation_id uuid NOT NULL DEFAULT gen_random_uuid(),
  inbox_id uuid NOT NULL,
  conflicting_payload_hash bytea NOT NULL,
  conflicting_payload jsonb,
  observed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, violation_id),
  FOREIGN KEY (tenant_id, inbox_id) REFERENCES inbox_receipt(tenant_id, inbox_id),
  CHECK (octet_length(conflicting_payload_hash) = 32)
);

CREATE TABLE runtime_outbox (
  tenant_id uuid NOT NULL REFERENCES tenant,
  outbox_id uuid NOT NULL DEFAULT gen_random_uuid(),
  run_id uuid,
  message_kind text NOT NULL,
  dedup_key text NOT NULL,
  payload jsonb NOT NULL,
  outbox_state text NOT NULL DEFAULT 'PENDING'
    CHECK (outbox_state IN ('PENDING','CLAIMED','DELIVERED','DEAD_LETTER')),
  claim_owner text,
  claim_epoch bigint NOT NULL DEFAULT 0,
  claim_until timestamptz,
  delivery_attempts integer NOT NULL DEFAULT 0,
  available_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  delivered_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, outbox_id),
  UNIQUE (tenant_id, dedup_key),
  FOREIGN KEY (tenant_id, run_id) REFERENCES workflow_run(tenant_id, run_id)
);
CREATE INDEX outbox_pending_idx ON runtime_outbox(available_at, created_at)
  WHERE outbox_state IN ('PENDING','CLAIMED');

CREATE TABLE command_ledger (
  tenant_id uuid NOT NULL,
  command_id uuid NOT NULL,
  run_id uuid NOT NULL,
  activation_id uuid NOT NULL,
  attempt_id uuid NOT NULL,
  device_id text NOT NULL,
  command_kind text NOT NULL,
  command_state text NOT NULL CHECK (command_state IN
    ('CREATED','DISPATCH_PENDING','SEND_STARTED','ACKNOWLEDGED','RUNNING','SUCCEEDED',
     'FAILED','CANCEL_REQUESTED','CANCELLED','DELIVERY_UNKNOWN','UNKNOWN','MANUAL_REQUIRED')),
  idempotency_class text NOT NULL CHECK (idempotency_class IN ('IDEMPOTENT','QUERYABLE','NON_IDEMPOTENT')),
  command_payload jsonb NOT NULL,
  payload_hash bytea NOT NULL,
  device_sequence text,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  completed_at timestamptz,
  PRIMARY KEY (tenant_id, command_id),
  UNIQUE (tenant_id, attempt_id),
  FOREIGN KEY (tenant_id, run_id) REFERENCES workflow_run(tenant_id, run_id),
  FOREIGN KEY (tenant_id, activation_id) REFERENCES node_activation(tenant_id, activation_id),
  FOREIGN KEY (tenant_id, attempt_id) REFERENCES node_attempt(tenant_id, attempt_id),
  CHECK (octet_length(payload_hash) = 32)
);
CREATE INDEX command_unresolved_idx ON command_ledger(tenant_id, run_id, command_state)
  WHERE command_state NOT IN ('SUCCEEDED','FAILED','CANCELLED');

CREATE TABLE command_dispatch_attempt (
  tenant_id uuid NOT NULL,
  dispatch_id uuid NOT NULL,
  command_id uuid NOT NULL,
  dispatch_no integer NOT NULL CHECK (dispatch_no > 0),
  dispatch_state text NOT NULL CHECK (dispatch_state IN
    ('READY','CLAIMED','SEND_STARTED','DELIVERED','DELIVERY_UNKNOWN','REJECTED')),
  dispatcher_id text,
  fencing_token bigint NOT NULL DEFAULT 0,
  started_at timestamptz,
  finished_at timestamptz,
  result jsonb,
  PRIMARY KEY (tenant_id, dispatch_id),
  UNIQUE (tenant_id, command_id, dispatch_no),
  FOREIGN KEY (tenant_id, command_id) REFERENCES command_ledger(tenant_id, command_id)
);

-- Monotonic fencing-token allocator. Increment and read under row lock before lease grant.
CREATE TABLE resource_fence_counter (
  tenant_id uuid NOT NULL REFERENCES tenant,
  resource_id text NOT NULL,
  last_fencing_token bigint NOT NULL DEFAULT 0 CHECK (last_fencing_token >= 0),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, resource_id)
);

CREATE TABLE resource_lease (
  tenant_id uuid NOT NULL,
  lease_id uuid NOT NULL DEFAULT gen_random_uuid(),
  run_id uuid NOT NULL,
  resource_id text NOT NULL,
  lease_state text NOT NULL CHECK (lease_state IN ('ACTIVE','RELEASING','RELEASED','QUARANTINED','EXPIRED_UNVERIFIED')),
  owner_kind text NOT NULL,
  owner_id uuid NOT NULL,
  fencing_token bigint NOT NULL,
  lease_version bigint NOT NULL DEFAULT 0,
  acquired_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  expires_at timestamptz,
  released_at timestamptz,
  evidence jsonb,
  PRIMARY KEY (tenant_id, lease_id),
  FOREIGN KEY (tenant_id, run_id) REFERENCES workflow_run(tenant_id, run_id),
  UNIQUE (tenant_id, resource_id, fencing_token)
);
CREATE UNIQUE INDEX resource_one_live_lease_uq ON resource_lease(tenant_id, resource_id)
  WHERE lease_state IN ('ACTIVE','RELEASING','QUARANTINED','EXPIRED_UNVERIFIED');

CREATE TABLE drain_record (
  tenant_id uuid NOT NULL,
  drain_id uuid NOT NULL DEFAULT gen_random_uuid(),
  run_id uuid NOT NULL,
  drain_kind text NOT NULL CHECK (drain_kind IN ('PAUSE','STOP','FAILURE')),
  drain_state text NOT NULL CHECK (drain_state IN ('REQUESTED','DRAINING','SAFE_POINT','COMPLETED','MANUAL_REQUIRED')),
  scan_cursor jsonb,
  scan_generation bigint NOT NULL DEFAULT 0,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  completed_at timestamptz,
  PRIMARY KEY (tenant_id, drain_id),
  FOREIGN KEY (tenant_id, run_id) REFERENCES workflow_run(tenant_id, run_id)
);
CREATE UNIQUE INDEX drain_one_open_per_run_uq ON drain_record(tenant_id, run_id)
  WHERE drain_state IN ('REQUESTED','DRAINING','SAFE_POINT','MANUAL_REQUIRED');

CREATE TABLE drain_item (
  tenant_id uuid NOT NULL,
  drain_id uuid NOT NULL,
  item_kind text NOT NULL,
  item_id uuid NOT NULL,
  item_state text NOT NULL CHECK (item_state IN ('PENDING','IN_PROGRESS','SAFE','MANUAL_REQUIRED')),
  last_observed_version bigint,
  evidence jsonb,
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, drain_id, item_kind, item_id),
  FOREIGN KEY (tenant_id, drain_id) REFERENCES drain_record(tenant_id, drain_id)
);

CREATE TABLE runtime_checkpoint (
  tenant_id uuid NOT NULL,
  checkpoint_id uuid NOT NULL DEFAULT gen_random_uuid(),
  run_id uuid NOT NULL,
  checkpoint_kind text NOT NULL,
  run_commit_version bigint NOT NULL,
  event_seq bigint NOT NULL,
  coordinator_epoch bigint NOT NULL,
  snapshot_payload jsonb NOT NULL,
  snapshot_hash bytea NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, checkpoint_id),
  UNIQUE (tenant_id, run_id, run_commit_version),
  FOREIGN KEY (tenant_id, run_id) REFERENCES workflow_run(tenant_id, run_id),
  CHECK (octet_length(snapshot_hash) = 32)
);

CREATE TABLE manual_intervention (
  tenant_id uuid NOT NULL,
  intervention_id uuid NOT NULL DEFAULT gen_random_uuid(),
  run_id uuid NOT NULL,
  subject_kind text NOT NULL,
  subject_id uuid NOT NULL,
  intervention_state text NOT NULL CHECK (intervention_state IN ('OPEN','DECIDED','CANCELLED')),
  reason_code text NOT NULL,
  evidence jsonb NOT NULL DEFAULT '{}'::jsonb,
  decision jsonb,
  decided_by text,
  decided_at timestamptz,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, intervention_id),
  FOREIGN KEY (tenant_id, run_id) REFERENCES workflow_run(tenant_id, run_id)
);
CREATE INDEX intervention_open_idx ON manual_intervention(tenant_id, run_id, created_at)
  WHERE intervention_state='OPEN';

CREATE TABLE quarantine_record (
  tenant_id uuid NOT NULL,
  quarantine_id uuid NOT NULL DEFAULT gen_random_uuid(),
  resource_id text NOT NULL,
  run_id uuid,
  lease_id uuid,
  quarantine_state text NOT NULL CHECK (quarantine_state IN ('ACTIVE','CLEAR_PENDING','CLEARED')),
  reason_code text NOT NULL,
  evidence jsonb NOT NULL,
  cleared_by text,
  cleared_at timestamptz,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, quarantine_id),
  FOREIGN KEY (tenant_id, run_id) REFERENCES workflow_run(tenant_id, run_id),
  FOREIGN KEY (tenant_id, lease_id) REFERENCES resource_lease(tenant_id, lease_id)
);
CREATE UNIQUE INDEX quarantine_one_active_resource_uq ON quarantine_record(tenant_id, resource_id)
  WHERE quarantine_state IN ('ACTIVE','CLEAR_PENDING');

CREATE TABLE runtime_audit (
  tenant_id uuid NOT NULL REFERENCES tenant,
  audit_id uuid NOT NULL DEFAULT gen_random_uuid(),
  run_id uuid,
  actor_kind text NOT NULL,
  actor_id text,
  action text NOT NULL,
  subject_kind text NOT NULL,
  subject_id uuid,
  before_state jsonb,
  after_state jsonb,
  reason text,
  correlation_id uuid,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, audit_id),
  FOREIGN KEY (tenant_id, run_id) REFERENCES workflow_run(tenant_id, run_id)
);
CREATE INDEX audit_run_time_idx ON runtime_audit(tenant_id, run_id, created_at);

-- Deferred FKs close the activation <-> attempt current pointer and token context links.
ALTER TABLE node_activation ADD CONSTRAINT activation_current_attempt_fk
  FOREIGN KEY (tenant_id, current_attempt_id) REFERENCES node_attempt(tenant_id, attempt_id)
  DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE execution_token ADD CONSTRAINT token_split_fk
  FOREIGN KEY (tenant_id, split_id) REFERENCES split_activation(tenant_id, split_id)
  DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE execution_token ADD CONSTRAINT token_branch_fk
  FOREIGN KEY (tenant_id, branch_id) REFERENCES branch_activation(tenant_id, branch_id)
  DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE execution_token ADD CONSTRAINT token_loop_fk
  FOREIGN KEY (tenant_id, loop_context_id) REFERENCES loop_context(tenant_id, loop_context_id)
  DEFERRABLE INITIALLY DEFERRED;

COMMIT;
