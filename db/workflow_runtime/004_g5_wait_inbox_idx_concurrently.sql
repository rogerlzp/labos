-- G5 §6.3 online production variant: non-transactional concurrent index build.
-- Run outside a transaction block (psql autocommit default, NO --single-transaction / -1).
-- Produces the exact same index definition as 004_g5_wait_inbox_idx.sql without blocking writes.
CREATE INDEX CONCURRENTLY IF NOT EXISTS inbox_wait_signal_pending_idx
  ON workflow_runtime.inbox_receipt (tenant_id, run_id, received_at)
  WHERE message_type = 'WORKFLOW_WAIT_SIGNAL'
    AND receipt_state IN ('RECEIVED', 'PROCESSING');
