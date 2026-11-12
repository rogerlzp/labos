\set ON_ERROR_STOP on
BEGIN;
UPDATE run_node
   SET execution_resolution_status = 'completed',
       physical_evidence_level     = 'legacy',
       physical_completed_at       = COALESCE(actual_end, updated_at),
       physical_evidence           = '{"source":"legacy status=completed","not_physical_time":true}'::jsonb
 WHERE status = 'completed'
   AND execution_resolution_status = 'unresolved';
COMMIT;
