-- =============================================================================
-- LabOS Workflow Runtime V1 · PostgreSQL physical schema
-- Architecture: Workflow Runtime v2.2 / T01-T23 engineering spec v1.1
-- PostgreSQL 14+ recommended. Additive namespace: wf_*
-- This file is both clean-schema input and additive migration input.
-- No legacy GraphRunner tables are altered.
-- =============================================================================

BEGIN;

CREATE TABLE IF NOT EXISTS wf_workflow_definition_version (
    tenant_id               bigint NOT NULL,
    definition_id           text NOT NULL,
    definition_version      text NOT NULL,
    definition_hash         char(64) NOT NULL,
    compiler_version        text NOT NULL,
    definition_payload      jsonb NOT NULL,
    graph_plan_payload      jsonb NOT NULL,
    published_at            timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, definition_id, definition_version),
    CONSTRAINT uq_wf_definition_hash UNIQUE (tenant_id, definition_hash),
    CONSTRAINT uq_wf_definition_pin UNIQUE
      (tenant_id, definition_id, definition_version, definition_hash, compiler_version)
);

CREATE OR REPLACE FUNCTION wf_reject_definition_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $body$
BEGIN
  RAISE EXCEPTION 'published Workflow Definition versions are immutable';
END;
$body$;

DROP TRIGGER IF EXISTS tr_wf_definition_immutable ON wf_workflow_definition_version;
CREATE TRIGGER tr_wf_definition_immutable
BEFORE UPDATE OR DELETE ON wf_workflow_definition_version
FOR EACH ROW EXECUTE FUNCTION wf_reject_definition_mutation();

CREATE TABLE IF NOT EXISTS wf_workflow_run (
    run_id                  uuid PRIMARY KEY,
    tenant_id               bigint NOT NULL,
    request_id              text NOT NULL,
    definition_id           text NOT NULL,
    definition_version      text NOT NULL,
    definition_hash         char(64) NOT NULL,
    compiler_version        text NOT NULL,
    status                  varchar(24) NOT NULL,
    run_commit_version      bigint NOT NULL DEFAULT 0 CHECK (run_commit_version >= 0),
    event_seq               bigint NOT NULL DEFAULT 0 CHECK (event_seq >= 0),
    coordinator_epoch       bigint NOT NULL DEFAULT 0 CHECK (coordinator_epoch >= 0),
    owner_id                text,
    owner_lease_version     bigint NOT NULL DEFAULT 0 CHECK (owner_lease_version >= 0),
    owner_lease_until       timestamptz,
    dispatch_gate_open      boolean NOT NULL DEFAULT false,
    initial_variables       jsonb NOT NULL DEFAULT '{}'::jsonb,
    failure_code            text,
    failure_detail          jsonb,
    created_at              timestamptz NOT NULL DEFAULT clock_timestamp(),
    started_at              timestamptz,
    completed_at            timestamptz,
    updated_at              timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_wf_run_request UNIQUE (tenant_id, request_id),
    CONSTRAINT fk_wf_run_definition FOREIGN KEY
      (tenant_id, definition_id, definition_version, definition_hash, compiler_version)
      REFERENCES wf_workflow_definition_version
      (tenant_id, definition_id, definition_version, definition_hash, compiler_version),
    CONSTRAINT ck_wf_run_status CHECK (status IN (
      'CREATED','STARTING','RUNNING','PAUSE_REQUESTED','PAUSE_DRAINING',
      'PAUSED','RESUME_REQUESTED','STOP_REQUESTED','STOP_DRAINING',
      'SUCCEEDED','FAILED','STOPPED','MANUAL_REQUIRED','QUARANTINED'
    ))
);
CREATE INDEX IF NOT EXISTS ix_wf_run_recovery
    ON wf_workflow_run (status, updated_at)
    WHERE status NOT IN ('SUCCEEDED','FAILED','STOPPED');

CREATE TABLE IF NOT EXISTS wf_execution_token (
    token_id                uuid PRIMARY KEY,
    run_id                  uuid NOT NULL REFERENCES wf_workflow_run(run_id) ON DELETE CASCADE,
    parent_token_id         uuid REFERENCES wf_execution_token(token_id),
    current_node_id         text NOT NULL,
    incoming_edge_id        text,
    split_activation_id     uuid,
    branch_activation_id    uuid,
    loop_context_id         uuid,
    status                  varchar(20) NOT NULL,
    wait_kind               varchar(24),
    wait_owner_type         varchar(24),
    wait_owner_id           uuid,
    object_version          bigint NOT NULL DEFAULT 0 CHECK (object_version >= 0),
    created_at              timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at              timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_wf_token_status CHECK (status IN (
      'CREATED','READY','RESERVED','IN_FLIGHT','WAITING','CONSUMED','TERMINAL','INVALIDATED'
    )),
    CONSTRAINT ck_wf_token_wait_owner CHECK (
      (status = 'WAITING' AND wait_kind IN ('RETRY','EXTERNAL_EVENT','TIMER','MANUAL','JOIN','RESOURCE') AND wait_owner_type IN ('WAIT_REGISTRATION','RETRY_REGISTRATION','JOIN_ACTIVATION') AND wait_owner_id IS NOT NULL)
      OR
      (status <> 'WAITING' AND wait_kind IS NULL AND wait_owner_type IS NULL AND wait_owner_id IS NULL)
    )
);
CREATE INDEX IF NOT EXISTS ix_wf_token_run_status
    ON wf_execution_token (run_id, status, current_node_id);
CREATE INDEX IF NOT EXISTS ix_wf_token_split
    ON wf_execution_token (split_activation_id, status)
    WHERE split_activation_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS wf_node_activation (
    activation_id           uuid PRIMARY KEY,
    run_id                  uuid NOT NULL REFERENCES wf_workflow_run(run_id) ON DELETE CASCADE,
    token_id                uuid NOT NULL UNIQUE REFERENCES wf_execution_token(token_id),
    node_id                 text NOT NULL,
    status                  varchar(24) NOT NULL,
    current_attempt_id      uuid,
    activation_ordinal      bigint NOT NULL DEFAULT 1 CHECK (activation_ordinal > 0),
    output_payload          jsonb,
    output_hash             char(64),
    wait_id                 uuid,
    retry_id                uuid,
    object_version          bigint NOT NULL DEFAULT 0 CHECK (object_version >= 0),
    created_at              timestamptz NOT NULL DEFAULT clock_timestamp(),
    activated_at            timestamptz,
    completed_at            timestamptz,
    updated_at              timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_wf_activation_status CHECK (status IN (
      'CREATED','READY','ACTIVE','SUSPENDED','RETRY_WAIT','COMPLETED',
      'FAILED','MANUAL_REQUIRED','UNKNOWN','CANCELLED','QUARANTINED'
    ))
);
CREATE INDEX IF NOT EXISTS ix_wf_activation_run_status
    ON wf_node_activation (run_id, status, node_id);

CREATE TABLE IF NOT EXISTS wf_node_attempt (
    attempt_id              uuid PRIMARY KEY,
    run_id                  uuid NOT NULL REFERENCES wf_workflow_run(run_id) ON DELETE CASCADE,
    activation_id           uuid NOT NULL REFERENCES wf_node_activation(activation_id) ON DELETE CASCADE,
    attempt_no              integer NOT NULL CHECK (attempt_no > 0),
    status                  varchar(24) NOT NULL,
    dispatch_id             uuid NOT NULL UNIQUE,
    dispatch_epoch          bigint NOT NULL DEFAULT 0 CHECK (dispatch_epoch >= 0),
    worker_id               text,
    side_effect_class       varchar(16) NOT NULL,
    retry_of_attempt_id     uuid REFERENCES wf_node_attempt(attempt_id),
    continuation_of_attempt_id uuid REFERENCES wf_node_attempt(attempt_id),
    output_payload          jsonb,
    output_hash             char(64),
    failure_code            text,
    failure_detail          jsonb,
    object_version          bigint NOT NULL DEFAULT 0 CHECK (object_version >= 0),
    created_at              timestamptz NOT NULL DEFAULT clock_timestamp(),
    accepted_at             timestamptz,
    started_at              timestamptz,
    ended_at                timestamptz,
    CONSTRAINT uq_wf_attempt_number UNIQUE (activation_id, attempt_no),
    CONSTRAINT ck_wf_attempt_status CHECK (status IN (
      'CREATED','DISPATCH_PENDING','DISPATCHED','ACCEPTED','STARTED','SUSPENDED',
      'SUCCEEDED','RETRYABLE_FAILED','PERMANENT_FAILED','UNKNOWN','ABANDONED_SAFE'
    )),
    CONSTRAINT ck_wf_attempt_effect CHECK (side_effect_class IN ('PURE','IDEMPOTENT','EXTERNAL'))
);
CREATE INDEX IF NOT EXISTS ix_wf_attempt_run_status
    ON wf_node_attempt (run_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_wf_attempt_activation
    ON wf_node_attempt (activation_id, attempt_no DESC);

CREATE TABLE IF NOT EXISTS wf_split_activation (
    split_activation_id     uuid PRIMARY KEY,
    run_id                  uuid NOT NULL REFERENCES wf_workflow_run(run_id) ON DELETE CASCADE,
    parent_token_id         uuid NOT NULL REFERENCES wf_execution_token(token_id),
    parent_activation_id    uuid NOT NULL REFERENCES wf_node_activation(activation_id),
    split_node_id           text NOT NULL,
    join_node_id            text NOT NULL,
    execution_version       bigint NOT NULL DEFAULT 0,
    expected_branch_ids     text[] NOT NULL,
    status                  varchar(20) NOT NULL,
    object_version          bigint NOT NULL DEFAULT 0 CHECK (object_version >= 0),
    created_at              timestamptz NOT NULL DEFAULT clock_timestamp(),
    closed_at               timestamptz,
    CONSTRAINT uq_wf_split_identity UNIQUE (parent_activation_id, split_node_id, execution_version),
    CONSTRAINT ck_wf_split_expected_nonempty CHECK (cardinality(expected_branch_ids) > 0),
    CONSTRAINT ck_wf_split_status CHECK (status IN (
      'OPENING','OPEN','JOIN_READY','CLOSED_SUCCESS','CLOSED_FAILED','QUARANTINED'
    ))
);
CREATE INDEX IF NOT EXISTS ix_wf_split_run_status
    ON wf_split_activation (run_id, status);

CREATE TABLE IF NOT EXISTS wf_branch_activation (
    branch_activation_id    uuid PRIMARY KEY,
    run_id                  uuid NOT NULL REFERENCES wf_workflow_run(run_id) ON DELETE CASCADE,
    split_activation_id     uuid NOT NULL REFERENCES wf_split_activation(split_activation_id) ON DELETE CASCADE,
    branch_id               text NOT NULL,
    token_id                uuid NOT NULL UNIQUE REFERENCES wf_execution_token(token_id),
    status                  varchar(20) NOT NULL,
    arrival_outcome         varchar(16),
    output_hash             char(64),
    object_version          bigint NOT NULL DEFAULT 0 CHECK (object_version >= 0),
    created_at              timestamptz NOT NULL DEFAULT clock_timestamp(),
    arrived_at              timestamptz,
    closed_at               timestamptz,
    CONSTRAINT uq_wf_branch_split_id UNIQUE (split_activation_id, branch_id),
    CONSTRAINT ck_wf_branch_status CHECK (status IN (
      'ACTIVE','WAITING_JOIN','ARRIVED','SKIPPED','FAILED','CANCEL_PENDING',
      'CANCELLED','UNKNOWN','QUARANTINED','CLOSED','CLOSED_FAILED'
    )),
    CONSTRAINT ck_wf_branch_arrival CHECK (
      arrival_outcome IS NULL OR arrival_outcome IN ('ARRIVED','SKIPPED')
    )
);
CREATE INDEX IF NOT EXISTS ix_wf_branch_split_status
    ON wf_branch_activation (split_activation_id, status);

CREATE TABLE IF NOT EXISTS wf_join_activation (
    join_activation_id      uuid PRIMARY KEY,
    run_id                  uuid NOT NULL REFERENCES wf_workflow_run(run_id) ON DELETE CASCADE,
    split_activation_id     uuid NOT NULL UNIQUE REFERENCES wf_split_activation(split_activation_id) ON DELETE CASCADE,
    join_node_id            text NOT NULL,
    status                  varchar(20) NOT NULL,
    downstream_token_id     uuid UNIQUE REFERENCES wf_execution_token(token_id),
    object_version          bigint NOT NULL DEFAULT 0 CHECK (object_version >= 0),
    created_at              timestamptz NOT NULL DEFAULT clock_timestamp(),
    released_at             timestamptz,
    CONSTRAINT ck_wf_join_status CHECK (status IN (
      'CREATED','WAITING','RELEASED','CLOSED_FAILED','QUARANTINED'
    )),
    CONSTRAINT ck_wf_join_downstream CHECK (
      (status = 'RELEASED' AND downstream_token_id IS NOT NULL)
      OR (status <> 'RELEASED' AND downstream_token_id IS NULL)
    )
);
CREATE INDEX IF NOT EXISTS ix_wf_join_run_status
    ON wf_join_activation (run_id, status);

CREATE TABLE IF NOT EXISTS wf_loop_context (
    loop_context_id         uuid PRIMARY KEY,
    run_id                  uuid NOT NULL REFERENCES wf_workflow_run(run_id) ON DELETE CASCADE,
    activation_id           uuid NOT NULL REFERENCES wf_node_activation(activation_id),
    loop_node_id            text NOT NULL,
    iteration               integer NOT NULL DEFAULT 0 CHECK (iteration >= 0),
    max_iterations          integer NOT NULL CHECK (max_iterations > 0),
    status                  varchar(16) NOT NULL,
    object_version          bigint NOT NULL DEFAULT 0 CHECK (object_version >= 0),
    created_at              timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at              timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_wf_loop_status CHECK (status IN ('ACTIVE','EXITED','FAILED','QUARANTINED')),
    CONSTRAINT ck_wf_loop_bound CHECK (iteration <= max_iterations)
);
CREATE INDEX IF NOT EXISTS ix_wf_loop_run_status
    ON wf_loop_context (run_id, status);

CREATE TABLE IF NOT EXISTS wf_wait_registration (
    wait_id                 uuid PRIMARY KEY,
    run_id                  uuid NOT NULL REFERENCES wf_workflow_run(run_id) ON DELETE CASCADE,
    activation_id           uuid NOT NULL REFERENCES wf_node_activation(activation_id),
    token_id                uuid NOT NULL REFERENCES wf_execution_token(token_id),
    attempt_id              uuid NOT NULL REFERENCES wf_node_attempt(attempt_id),
    wait_kind               varchar(24) NOT NULL,
    status                  varchar(16) NOT NULL,
    owner_correlation_key   text,
    command_id              uuid,
    source_event_cursor     text,
    timeout_at              timestamptz,
    payload                 jsonb NOT NULL DEFAULT '{}'::jsonb,
    object_version          bigint NOT NULL DEFAULT 0 CHECK (object_version >= 0),
    created_at              timestamptz NOT NULL DEFAULT clock_timestamp(),
    satisfied_at            timestamptz,
    CONSTRAINT ck_wf_wait_kind CHECK (wait_kind IN ('EXTERNAL_EVENT','TIMER','MANUAL','JOIN','RESOURCE')),
    CONSTRAINT ck_wf_wait_status CHECK (status IN ('ACTIVE','SATISFIED','EXPIRED','CANCELLED','REJECTED'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_wf_wait_active_activation
    ON wf_wait_registration (activation_id)
    WHERE status = 'ACTIVE';
CREATE INDEX IF NOT EXISTS ix_wf_wait_due
    ON wf_wait_registration (timeout_at, run_id)
    WHERE status = 'ACTIVE';

CREATE TABLE IF NOT EXISTS wf_retry_registration (
    retry_id                uuid PRIMARY KEY,
    run_id                  uuid NOT NULL REFERENCES wf_workflow_run(run_id) ON DELETE CASCADE,
    activation_id           uuid NOT NULL REFERENCES wf_node_activation(activation_id),
    token_id                uuid NOT NULL REFERENCES wf_execution_token(token_id),
    source_attempt_id       uuid NOT NULL REFERENCES wf_node_attempt(attempt_id),
    retry_count             integer NOT NULL CHECK (retry_count > 0),
    retry_after             timestamptz NOT NULL,
    status                  varchar(16) NOT NULL,
    object_version          bigint NOT NULL DEFAULT 0 CHECK (object_version >= 0),
    created_at              timestamptz NOT NULL DEFAULT clock_timestamp(),
    satisfied_at            timestamptz,
    CONSTRAINT ck_wf_retry_status CHECK (status IN ('ACTIVE','SATISFIED','CANCELLED','REJECTED'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_wf_retry_active_activation
    ON wf_retry_registration (activation_id)
    WHERE status = 'ACTIVE';
CREATE INDEX IF NOT EXISTS ix_wf_retry_due
    ON wf_retry_registration (retry_after, run_id)
    WHERE status = 'ACTIVE';

CREATE TABLE IF NOT EXISTS wf_resource_acquisition_group (
    group_id                uuid PRIMARY KEY,
    run_id                  uuid NOT NULL REFERENCES wf_workflow_run(run_id) ON DELETE CASCADE,
    owner_type              varchar(24) NOT NULL,
    owner_id                uuid NOT NULL,
    status                  varchar(16) NOT NULL,
    expected_resource_ids   text[] NOT NULL,
    object_version          bigint NOT NULL DEFAULT 0 CHECK (object_version >= 0),
    created_at              timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at              timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_wf_resource_group_status CHECK (status IN ('REQUESTED','ACQUIRING','ACQUIRED','RELEASE_PENDING','RELEASED','QUARANTINED'))
);

CREATE TABLE IF NOT EXISTS wf_resource_lease (
    lease_id                uuid PRIMARY KEY,
    run_id                  uuid NOT NULL REFERENCES wf_workflow_run(run_id) ON DELETE CASCADE,
    group_id                uuid REFERENCES wf_resource_acquisition_group(group_id),
    resource_id             text NOT NULL,
    owner_type              varchar(24) NOT NULL,
    owner_id                uuid NOT NULL,
    status                  varchar(24) NOT NULL,
    pause_policy            varchar(24) NOT NULL DEFAULT 'RELEASE_ON_PAUSE',
    fencing_token           bigint NOT NULL DEFAULT 0 CHECK (fencing_token >= 0),
    lease_expires_at        timestamptz,
    last_renewed_at         timestamptz,
    physical_evidence_ref   text,
    physical_evidence_hash  char(64),
    object_version          bigint NOT NULL DEFAULT 0 CHECK (object_version >= 0),
    created_at              timestamptz NOT NULL DEFAULT clock_timestamp(),
    acquired_at             timestamptz,
    released_at             timestamptz,
    CONSTRAINT ck_wf_lease_status CHECK (status IN (
      'REQUESTED','ACQUIRING','ACQUIRED','RELEASE_PENDING','RELEASED',
      'EXPIRED_UNVERIFIED','QUARANTINED'
    )),
    CONSTRAINT ck_wf_lease_pause_policy CHECK (pause_policy IN ('RETAIN_ON_PAUSE','RELEASE_ON_PAUSE'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_wf_resource_active_owner
    ON wf_resource_lease (resource_id)
    WHERE status IN ('REQUESTED','ACQUIRING','ACQUIRED','RELEASE_PENDING','EXPIRED_UNVERIFIED','QUARANTINED');
CREATE INDEX IF NOT EXISTS ix_wf_lease_run_status
    ON wf_resource_lease (run_id, status);

CREATE TABLE IF NOT EXISTS wf_command_ledger (
    command_id              uuid PRIMARY KEY,
    run_id                  uuid NOT NULL REFERENCES wf_workflow_run(run_id) ON DELETE CASCADE,
    activation_id           uuid NOT NULL REFERENCES wf_node_activation(activation_id),
    attempt_id              uuid NOT NULL UNIQUE REFERENCES wf_node_attempt(attempt_id),
    lease_id                uuid REFERENCES wf_resource_lease(lease_id),
    device_id               text NOT NULL,
    operation               text NOT NULL,
    payload                 jsonb NOT NULL,
    payload_hash            char(64) NOT NULL,
    status                  varchar(28) NOT NULL,
    adapter_capabilities    jsonb NOT NULL DEFAULT '{}'::jsonb,
    device_sequence         bigint,
    result_payload          jsonb,
    result_hash             char(64),
    object_version          bigint NOT NULL DEFAULT 0 CHECK (object_version >= 0),
    created_at              timestamptz NOT NULL DEFAULT clock_timestamp(),
    dispatch_started_at     timestamptz,
    acknowledged_at         timestamptz,
    completed_at            timestamptz,
    CONSTRAINT ck_wf_command_status CHECK (status IN (
      'CREATED','PREPARED','DISPATCHING','DELIVERY_UNKNOWN','ACKNOWLEDGED','RUNNING',
      'CANCEL_REQUESTED','SUCCEEDED','FAILED','REJECTED','CANCELLED_CONFIRMED','QUARANTINED'
    ))
);
CREATE INDEX IF NOT EXISTS ix_wf_command_run_status
    ON wf_command_ledger (run_id, status, created_at);
CREATE INDEX IF NOT EXISTS ix_wf_command_device
    ON wf_command_ledger (device_id, created_at DESC);

CREATE TABLE IF NOT EXISTS wf_command_outbox (
    outbox_id               uuid PRIMARY KEY,
    command_id              uuid NOT NULL UNIQUE REFERENCES wf_command_ledger(command_id) ON DELETE CASCADE,
    status                  varchar(20) NOT NULL,
    claim_owner             text,
    claim_until             timestamptz,
    attempts                integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at         timestamptz NOT NULL DEFAULT clock_timestamp(),
    last_error              text,
    created_at              timestamptz NOT NULL DEFAULT clock_timestamp(),
    delivered_at            timestamptz,
    CONSTRAINT ck_wf_command_outbox_status CHECK (status IN (
      'PENDING','CLAIMED','SEND_STARTED','DELIVERY_CONFIRMED','DELIVERY_UNKNOWN','CLOSED','DEAD_LETTER'
    ))
);
CREATE INDEX IF NOT EXISTS ix_wf_command_outbox_pending
    ON wf_command_outbox (next_attempt_at, created_at)
    WHERE status IN ('PENDING','CLAIMED');

CREATE TABLE IF NOT EXISTS wf_worker_dispatch_outbox (
    outbox_id               uuid PRIMARY KEY,
    run_id                  uuid NOT NULL REFERENCES wf_workflow_run(run_id) ON DELETE CASCADE,
    activation_id           uuid NOT NULL REFERENCES wf_node_activation(activation_id),
    attempt_id              uuid NOT NULL UNIQUE REFERENCES wf_node_attempt(attempt_id),
    dispatch_id             uuid NOT NULL UNIQUE,
    dispatch_epoch          bigint NOT NULL,
    status                  varchar(20) NOT NULL,
    claim_owner             text,
    claim_until             timestamptz,
    attempts                integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    payload                 jsonb NOT NULL,
    created_at              timestamptz NOT NULL DEFAULT clock_timestamp(),
    sent_at                 timestamptz,
    CONSTRAINT ck_wf_worker_outbox_status CHECK (status IN ('PENDING','CLAIMED','SEND_STARTED','DELIVERED','DELIVERY_UNKNOWN','CLOSED','DEAD_LETTER'))
);
CREATE INDEX IF NOT EXISTS ix_wf_worker_outbox_pending
    ON wf_worker_dispatch_outbox (created_at)
    WHERE status IN ('PENDING','CLAIMED');

CREATE TABLE IF NOT EXISTS wf_inbox_message (
    inbox_id                uuid PRIMARY KEY,
    source                  text NOT NULL,
    message_id              text NOT NULL,
    run_id                  uuid REFERENCES wf_workflow_run(run_id) ON DELETE CASCADE,
    payload_hash            char(64) NOT NULL,
    raw_payload             jsonb NOT NULL,
    status                  varchar(16) NOT NULL DEFAULT 'RECEIVED',
    claim_owner             text,
    claim_until             timestamptz,
    claim_version           bigint NOT NULL DEFAULT 0,
    outcome                 jsonb,
    rejection_reason        text,
    received_at             timestamptz NOT NULL DEFAULT clock_timestamp(),
    processed_at            timestamptz,
    CONSTRAINT uq_wf_inbox_identity UNIQUE (source, message_id),
    CONSTRAINT ck_wf_inbox_status CHECK (status IN ('RECEIVED','PROCESSING','PROCESSED','REJECTED'))
);
CREATE INDEX IF NOT EXISTS ix_wf_inbox_received
    ON wf_inbox_message (received_at)
    WHERE status = 'RECEIVED';

CREATE TABLE IF NOT EXISTS wf_runtime_event (
    event_id                uuid PRIMARY KEY,
    run_id                  uuid NOT NULL REFERENCES wf_workflow_run(run_id) ON DELETE CASCADE,
    event_seq               bigint NOT NULL CHECK (event_seq > 0),
    run_commit_version      bigint NOT NULL CHECK (run_commit_version > 0),
    event_type              text NOT NULL,
    aggregate_type          text NOT NULL,
    aggregate_id            uuid NOT NULL,
    message_id              text,
    payload                 jsonb NOT NULL DEFAULT '{}'::jsonb,
    occurred_at             timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_wf_event_run_seq UNIQUE (run_id, event_seq)
);
CREATE INDEX IF NOT EXISTS ix_wf_event_run_time
    ON wf_runtime_event (run_id, event_seq);
CREATE INDEX IF NOT EXISTS ix_wf_event_aggregate
    ON wf_runtime_event (aggregate_type, aggregate_id, event_seq);

CREATE TABLE IF NOT EXISTS wf_runtime_checkpoint (
    checkpoint_id           uuid PRIMARY KEY,
    run_id                  uuid NOT NULL REFERENCES wf_workflow_run(run_id) ON DELETE CASCADE,
    run_commit_version      bigint NOT NULL,
    event_seq_high_watermark bigint NOT NULL,
    coordinator_epoch       bigint NOT NULL,
    definition_hash         char(64) NOT NULL,
    payload                 jsonb NOT NULL,
    payload_hash            char(64) NOT NULL,
    created_at              timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_wf_checkpoint_run_version UNIQUE (run_id, run_commit_version),
    CONSTRAINT ck_wf_checkpoint_cursors CHECK (run_commit_version >= 0 AND event_seq_high_watermark >= 0)
);
CREATE INDEX IF NOT EXISTS ix_wf_checkpoint_latest
    ON wf_runtime_checkpoint (run_id, run_commit_version DESC);

CREATE TABLE IF NOT EXISTS wf_run_variable (
    run_id                  uuid NOT NULL REFERENCES wf_workflow_run(run_id) ON DELETE CASCADE,
    variable_key            text NOT NULL,
    value_payload           jsonb NOT NULL,
    value_hash              char(64) NOT NULL,
    version                 bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    updated_at              timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (run_id, variable_key)
);

CREATE TABLE IF NOT EXISTS wf_branch_output (
    branch_activation_id    uuid NOT NULL REFERENCES wf_branch_activation(branch_activation_id) ON DELETE CASCADE,
    output_key              text NOT NULL,
    value_payload           jsonb NOT NULL,
    value_hash              char(64) NOT NULL,
    created_at              timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (branch_activation_id, output_key)
);

CREATE TABLE IF NOT EXISTS wf_scheduler_capacity (
    capacity_key            text PRIMARY KEY,
    capacity_limit           integer NOT NULL CHECK (capacity_limit > 0),
    reserved_count           integer NOT NULL DEFAULT 0 CHECK (reserved_count >= 0),
    object_version           bigint NOT NULL DEFAULT 0 CHECK (object_version >= 0),
    updated_at               timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_wf_capacity_not_overbooked CHECK (reserved_count <= capacity_limit)
);

CREATE TABLE IF NOT EXISTS wf_reservation (
    reservation_id          uuid PRIMARY KEY,
    run_id                  uuid NOT NULL REFERENCES wf_workflow_run(run_id) ON DELETE CASCADE,
    token_id                uuid NOT NULL REFERENCES wf_execution_token(token_id),
    activation_id           uuid NOT NULL REFERENCES wf_node_activation(activation_id),
    attempt_id              uuid REFERENCES wf_node_attempt(attempt_id),
    capacity_key            text NOT NULL REFERENCES wf_scheduler_capacity(capacity_key),
    status                  varchar(16) NOT NULL,
    expires_at              timestamptz NOT NULL,
    object_version          bigint NOT NULL DEFAULT 0 CHECK (object_version >= 0),
    created_at              timestamptz NOT NULL DEFAULT clock_timestamp(),
    released_at             timestamptz,
    CONSTRAINT ck_wf_reservation_status CHECK (status IN ('RESERVED','COMMITTED','RELEASED','CANCELLED','EXPIRED'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_wf_reservation_active_token
    ON wf_reservation (token_id)
    WHERE status IN ('RESERVED','COMMITTED');
CREATE INDEX IF NOT EXISTS ix_wf_reservation_expiry
    ON wf_reservation (expires_at, run_id)
    WHERE status = 'RESERVED';

CREATE TABLE IF NOT EXISTS wf_drain (
    drain_id                uuid PRIMARY KEY,
    run_id                  uuid NOT NULL REFERENCES wf_workflow_run(run_id) ON DELETE CASCADE,
    request_id              text NOT NULL,
    drain_kind              varchar(8) NOT NULL,
    status                  varchar(16) NOT NULL,
    scan_cursor             jsonb,
    snapshot_run_version    bigint NOT NULL,
    snapshot_event_seq      bigint NOT NULL,
    object_version          bigint NOT NULL DEFAULT 0,
    created_at              timestamptz NOT NULL DEFAULT clock_timestamp(),
    completed_at            timestamptz,
    CONSTRAINT uq_wf_drain_request UNIQUE (run_id, request_id),
    CONSTRAINT ck_wf_drain_kind CHECK (drain_kind IN ('PAUSE','STOP','FAILURE')),
    CONSTRAINT ck_wf_drain_status CHECK (status IN ('STARTED','RECONCILING','COMPLETED','MANUAL_REQUIRED','QUARANTINED','SUPERSEDED'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_wf_drain_active_run
    ON wf_drain (run_id)
    WHERE status IN ('STARTED','RECONCILING');

CREATE TABLE IF NOT EXISTS wf_drain_item (
    drain_item_id           uuid PRIMARY KEY,
    drain_id                uuid NOT NULL REFERENCES wf_drain(drain_id) ON DELETE CASCADE,
    object_type             text NOT NULL,
    object_id               uuid NOT NULL,
    disposition             varchar(20) NOT NULL,
    object_version          bigint NOT NULL,
    updated_at              timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_wf_drain_item UNIQUE (drain_id, object_type, object_id),
    CONSTRAINT ck_wf_drain_item_disposition CHECK (disposition IN ('PENDING','SAFE','WAITING','MANUAL','QUARANTINED','CANCELLED'))
);
CREATE INDEX IF NOT EXISTS ix_wf_drain_item_pending
    ON wf_drain_item (drain_id, disposition)
    WHERE disposition = 'PENDING';

CREATE TABLE IF NOT EXISTS wf_protocol_violation (
    violation_id            uuid PRIMARY KEY,
    run_id                  uuid REFERENCES wf_workflow_run(run_id) ON DELETE CASCADE,
    source                  text NOT NULL,
    message_id              text NOT NULL,
    original_payload_hash   char(64) NOT NULL,
    conflicting_payload_hash char(64) NOT NULL,
    raw_payload             jsonb NOT NULL,
    detected_at             timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_wf_protocol_violation UNIQUE (source, message_id, conflicting_payload_hash)
);

CREATE TABLE IF NOT EXISTS wf_manual_work_item (
    work_item_id            uuid PRIMARY KEY,
    run_id                  uuid NOT NULL REFERENCES wf_workflow_run(run_id) ON DELETE CASCADE,
    object_type             text NOT NULL,
    object_id               uuid NOT NULL,
    status                  varchar(16) NOT NULL,
    reason_code             text NOT NULL,
    evidence_ref            text,
    evidence_hash           char(64),
    created_at              timestamptz NOT NULL DEFAULT clock_timestamp(),
    resolved_at             timestamptz,
    CONSTRAINT ck_wf_manual_status CHECK (status IN ('OPEN','CLAIMED','RESOLVED','REJECTED'))
);
CREATE INDEX IF NOT EXISTS ix_wf_manual_open
    ON wf_manual_work_item (run_id, created_at)
    WHERE status IN ('OPEN','CLAIMED');

CREATE TABLE IF NOT EXISTS wf_intervention_record (
    intervention_id         uuid PRIMARY KEY,
    run_id                  uuid NOT NULL REFERENCES wf_workflow_run(run_id) ON DELETE CASCADE,
    work_item_id            uuid REFERENCES wf_manual_work_item(work_item_id),
    object_type             text NOT NULL,
    object_id               uuid NOT NULL,
    expected_object_version bigint NOT NULL,
    decision                text NOT NULL,
    evidence_ref            text NOT NULL,
    evidence_hash           char(64) NOT NULL,
    actor_id                text NOT NULL,
    authorization_ref       text NOT NULL,
    reason                  text NOT NULL,
    postcondition_result    jsonb NOT NULL,
    created_at              timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS ix_wf_intervention_run
    ON wf_intervention_record (run_id, created_at);

-- Circular identity references are added after both sides exist.
-- Guard each constraint so the additive migration can be re-applied safely.
DO $
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_wf_activation_current_attempt') THEN
    ALTER TABLE wf_node_activation
      ADD CONSTRAINT fk_wf_activation_current_attempt
      FOREIGN KEY (current_attempt_id) REFERENCES wf_node_attempt(attempt_id)
      DEFERRABLE INITIALLY DEFERRED;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_wf_token_split') THEN
    ALTER TABLE wf_execution_token
      ADD CONSTRAINT fk_wf_token_split
      FOREIGN KEY (split_activation_id) REFERENCES wf_split_activation(split_activation_id)
      DEFERRABLE INITIALLY DEFERRED;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_wf_token_branch') THEN
    ALTER TABLE wf_execution_token
      ADD CONSTRAINT fk_wf_token_branch
      FOREIGN KEY (branch_activation_id) REFERENCES wf_branch_activation(branch_activation_id)
      DEFERRABLE INITIALLY DEFERRED;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_wf_token_loop') THEN
    ALTER TABLE wf_execution_token
      ADD CONSTRAINT fk_wf_token_loop
      FOREIGN KEY (loop_context_id) REFERENCES wf_loop_context(loop_context_id)
      DEFERRABLE INITIALLY DEFERRED;
  END IF;
END $body$;

COMMIT;
