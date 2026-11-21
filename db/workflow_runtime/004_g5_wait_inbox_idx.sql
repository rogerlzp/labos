-- G5 §6.3: bounded Wait Inbox pending replay & Timer due arbitration partial index.
-- Note: transactional migration (matches 001/003 pattern for CI bootstrap); large live
-- production tables should apply CREATE INDEX CONCURRENTLY outside a transaction block.
BEGIN;

CREATE INDEX IF NOT EXISTS inbox_wait_signal_pending_idx
  ON workflow_runtime.inbox_receipt (tenant_id, run_id, received_at)
  WHERE message_type = 'WORKFLOW_WAIT_SIGNAL'
    AND receipt_state IN ('RECEIVED', 'PROCESSING');

COMMIT;
