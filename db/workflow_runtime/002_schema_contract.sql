DO $$
DECLARE
  expected text[] := ARRAY[
    'tenant','definition_snapshot','workflow_run','run_request_dedup','execution_token',
    'node_activation','node_attempt','worker_execution_fact','scheduler_capacity',
    'dispatch_reservation','split_activation','branch_activation','join_activation',
    'loop_context','wait_registration','retry_registration','flow_variable','branch_output',
    'runtime_event','inbox_receipt','inbox_protocol_violation','runtime_outbox',
    'command_ledger','command_dispatch_attempt','resource_lease','drain_record','drain_item',
    'runtime_checkpoint','manual_intervention','quarantine_record','runtime_audit',
    'schema_migration',
    'workstation_sidecar','device_state_projection','wait_early_signal_buffer'
  ];
  forbidden text[] := ARRAY['worker_execution_lease','resource_fence_counter'];
  missing text[];
  present_forbidden text[];
BEGIN
  SELECT array_agg(x.table_name) INTO missing
  FROM unnest(expected) AS x(table_name)
  WHERE NOT EXISTS (
    SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
    WHERE n.nspname='workflow_runtime' AND c.relname=x.table_name AND c.relkind='r'
  );
  IF missing IS NOT NULL THEN RAISE EXCEPTION 'Missing tables: %',missing; END IF;

  SELECT array_agg(x.table_name) INTO present_forbidden
  FROM unnest(forbidden) AS x(table_name)
  WHERE EXISTS (
    SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
    WHERE n.nspname='workflow_runtime' AND c.relname=x.table_name AND c.relkind='r'
  );
  IF present_forbidden IS NOT NULL THEN
    RAISE EXCEPTION 'V1 single-instance schema contains forbidden distributed tables: %',present_forbidden;
  END IF;

  IF (SELECT count(*) FROM pg_indexes WHERE schemaname='workflow_runtime') < 17 THEN
    RAISE EXCEPTION 'Insufficient indexes';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname='workflow_runtime' AND indexname='activation_one_live_per_token_uq') THEN
    RAISE EXCEPTION 'Missing live activation partial unique index';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname='workflow_runtime' AND indexname='attempt_one_live_per_activation_uq') THEN
    RAISE EXCEPTION 'Missing live attempt partial unique index';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname='workflow_runtime' AND indexname='resource_one_live_lease_uq') THEN
    RAISE EXCEPTION 'Missing live resource lease partial unique index';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname='workflow_runtime' AND indexname='wait_one_active_per_activation_uq') THEN
    RAISE EXCEPTION 'Missing one-active-Wait-per-Activation index';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname='workflow_runtime' AND indexname='wait_active_correlation_uq'
      AND indexdef LIKE '%(tenant_id, run_id, correlation_key)%'
      AND indexdef LIKE '%WHERE ((wait_state = ''ACTIVE''::text) AND (correlation_key IS NOT NULL))%') THEN
    RAISE EXCEPTION 'Missing one-active-Wait-per-Run/correlationKey partial unique index';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname='workflow_runtime' AND indexname='inbox_wait_signal_pending_idx'
      AND indexdef LIKE '%(tenant_id, run_id, received_at)%'
      AND indexdef LIKE '%WORKFLOW_WAIT_SIGNAL%') THEN
    RAISE EXCEPTION 'Missing G5 Wait Inbox partial index inbox_wait_signal_pending_idx';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname='workflow_runtime' AND indexname='retry_activation_attempt_uq') THEN
    RAISE EXCEPTION 'Missing unique Retry activation/attempt key';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname='workflow_runtime' AND indexname='split_one_open_per_activation_uq') THEN
    RAISE EXCEPTION 'Missing one-open-Split-per-source-Activation index';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname='workflow_runtime' AND indexname='token_parent_idx') THEN
    RAISE EXCEPTION 'Missing parent_token_id lookup index on execution_token';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname='workflow_runtime' AND indexname='worker_fact_one_start_per_se_uq') THEN
    RAISE EXCEPTION 'Missing one-start-per-side-effect partial unique index';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname='workflow_runtime' AND indexname='worker_fact_one_complete_per_se_uq') THEN
    RAISE EXCEPTION 'Missing one-completion-per-side-effect partial unique index';
  END IF;
  -- Whitelist note: workstation_sidecar.connection_generation is a single-Kernel TCP reconnect
  -- guard under SingleInstanceHostLock (NOT a distributed lease/fencing token).
  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema='workflow_runtime' AND (
      (table_name='workflow_run' AND column_name IN ('coordinator_id','coordinator_epoch')) OR
      (table_name='node_attempt' AND column_name='dispatch_epoch') OR
      (table_name='worker_execution_fact' AND column_name IN ('dispatch_epoch','lease_epoch')) OR
      (table_name='runtime_event' AND column_name='coordinator_epoch') OR
      (table_name='runtime_checkpoint' AND column_name='coordinator_epoch') OR
      (table_name='command_dispatch_attempt' AND column_name='fencing_token') OR
      (table_name='resource_lease' AND column_name='fencing_token') OR
      (table_name='runtime_outbox' AND column_name IN ('claim_owner','claim_epoch','claim_until')) OR
      (table_name='workstation_sidecar' AND column_name IN ('coordinator_id','coordinator_epoch','fencing_token','claim_owner'))
    )
  ) THEN RAISE EXCEPTION 'Forbidden distributed coordination column remains in V1 schema'; END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='workflow_runtime'
      AND table_name='inbox_receipt' AND column_name='message_payload') THEN
    RAISE EXCEPTION 'Inbox original message payload is not persisted';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='workflow_runtime'
      AND table_name='node_attempt' AND column_name='exported_variables') THEN
    RAISE EXCEPTION 'Attempt exported_variables manifest column is missing';
  END IF;
  IF (SELECT count(*) FROM information_schema.columns WHERE table_schema='workflow_runtime'
      AND table_name='flow_variable'
      AND column_name IN ('source_activation_id','source_split_id','source_branch_id')) <> 3 THEN
    RAISE EXCEPTION 'flow_variable branch lineage columns are missing';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='workflow_runtime'
      AND table_name='worker_execution_fact' AND column_name='side_effect_key') THEN
    RAISE EXCEPTION 'worker_execution_fact.side_effect_key identity column is missing';
  END IF;
  IF EXISTS (
    SELECT 1 FROM pg_index i
    JOIN pg_class c ON c.oid = i.indexrelid
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'workflow_runtime' AND NOT i.indisvalid
  ) THEN
    RAISE EXCEPTION 'Invalid index found in workflow_runtime schema';
  END IF;
  IF (SELECT count(*) FROM information_schema.columns WHERE table_schema='workflow_runtime'
      AND table_name='schema_migration'
      AND column_name IN ('version','filename','sha256','mode','applied_at','applied_by')) <> 6 THEN
    RAISE EXCEPTION 'schema_migration ledger columns are incomplete';
  END IF;
  IF COALESCE((
    SELECT array_agg(cn.nspname || '.' || cl.relname ORDER BY cn.nspname || '.' || cl.relname)
    FROM pg_constraint con
    JOIN pg_class cl ON cl.oid = con.conrelid
    JOIN pg_namespace cn ON cn.oid = cl.relnamespace
    WHERE con.contype = 'f'
      AND con.confrelid = 'workflow_runtime.command_ledger'::regclass
  ), ARRAY[]::text[]) <> ARRAY[
    'workflow_runtime.command_dispatch_attempt',
    'workflow_runtime.device_state_projection',
    'workflow_runtime.quarantine_record'
  ] THEN
    RAISE EXCEPTION 'Post-006 inbound FK set on workflow_runtime.command_ledger drifted from contract';
  END IF;
END $$;

BEGIN;
SET LOCAL search_path=workflow_runtime,public;
DO $$
DECLARE
 t uuid:=gen_random_uuid(); d uuid:=gen_random_uuid(); r uuid:=gen_random_uuid(); r2 uuid:=gen_random_uuid();
 tok uuid:=gen_random_uuid(); act uuid:=gen_random_uuid(); tok2 uuid:=gen_random_uuid(); act2 uuid:=gen_random_uuid();
 att uuid:=gen_random_uuid(); cmd uuid:=gen_random_uuid(); l1 uuid:=gen_random_uuid(); wfact uuid:=gen_random_uuid();
 dispatch uuid:=gen_random_uuid();
 tok_neg uuid:=gen_random_uuid(); act_neg uuid:=gen_random_uuid(); att_neg uuid:=gen_random_uuid();
 inbox_neg uuid:=gen_random_uuid(); bad_ws text;
BEGIN
 INSERT INTO tenant(tenant_id,tenant_key,display_name) VALUES(t,'contract-'||t::text,'contract');
 INSERT INTO workstation_sidecar(tenant_id,workstation_id,endpoint_uri,security_mode)
 VALUES(t,'ws-contract-test','tls://127.0.0.1:20060','TLS13_MTLS');
 INSERT INTO definition_snapshot(tenant_id,definition_id,definition_key,definition_version,definition_hash,compiler_version,graph_plan)
 VALUES(t,d,'contract','1',decode(repeat('ab',32),'hex'),'test','{}');
 INSERT INTO workflow_run(tenant_id,run_id,definition_id,status) VALUES(t,r,d,'CREATED');
 INSERT INTO workflow_run(tenant_id,run_id,definition_id,status) VALUES(t,r2,d,'CREATED');
 INSERT INTO execution_token(tenant_id,token_id,run_id,token_state,node_key) VALUES(t,tok,r,'READY','n1');
 INSERT INTO node_activation(tenant_id,activation_id,run_id,token_id,activation_no,node_key,activation_state)
 VALUES(t,act,r,tok,1,'n1','RUNNING');
 INSERT INTO wait_registration(tenant_id,run_id,activation_id,wait_state,wait_kind,correlation_key,continuation)
 VALUES(t,r,act,'ACTIVE','MESSAGE','g5-correlation-contract','{"nodeKey":"next"}');
 INSERT INTO execution_token(tenant_id,token_id,run_id,token_state,node_key)
 VALUES(t,tok2,r,'WAITING','n2');
 INSERT INTO node_activation(tenant_id,activation_id,run_id,token_id,activation_no,node_key,activation_state)
 VALUES(t,act2,r,tok2,1,'n2','WAITING');
 BEGIN
  INSERT INTO wait_registration(tenant_id,run_id,activation_id,wait_state,wait_kind,correlation_key,continuation)
  VALUES(t,r,act2,'ACTIVE','MESSAGE','g5-correlation-contract','{"nodeKey":"next2"}');
  RAISE EXCEPTION 'expected one ACTIVE Wait per Run/correlationKey';
 EXCEPTION WHEN unique_violation THEN NULL; END;
 INSERT INTO node_attempt(tenant_id,attempt_id,run_id,activation_id,attempt_no,dispatch_id,attempt_state,side_effect_class)
 VALUES(t,att,r,act,1,dispatch,'STARTED','PURE');
 INSERT INTO worker_execution_fact(tenant_id,fact_id,run_id,attempt_id,fact_seq,fact_key,fact_type,actor_kind,actor_id,dispatch_id,payload)
 VALUES(t,wfact,r,att,1,'handler-start','HANDLER_STARTED','WORKER','worker-contract',dispatch,'{}');
 BEGIN
  UPDATE worker_execution_fact SET payload='{"tampered":true}' WHERE tenant_id=t AND fact_id=wfact;
  RAISE EXCEPTION 'expected immutable Worker fact trigger';
 EXCEPTION WHEN raise_exception THEN
  IF SQLERRM <> 'worker_execution_fact is append-only' THEN RAISE; END IF;
 END;
 INSERT INTO command_ledger(tenant_id,command_id,run_id,activation_id,attempt_id,workstation_id,device_id,command_kind,operation_name,command_state,idempotency_class,command_payload,payload_hash)
 VALUES(t,cmd,r,act,att,'ws-contract-test','dev','test','test_op','CREATED','IDEMPOTENT','{}',decode(repeat('cd',32),'hex'));
 BEGIN
  INSERT INTO command_ledger(tenant_id,command_id,run_id,activation_id,attempt_id,workstation_id,device_id,command_kind,operation_name,command_state,idempotency_class,command_payload,payload_hash)
  VALUES(t,gen_random_uuid(),r,act,att,'ws-contract-test','dev','duplicate','test_op','CREATED','IDEMPOTENT','{}',decode(repeat('ef',32),'hex'));
  RAISE EXCEPTION 'expected unique command per attempt';
 EXCEPTION WHEN unique_violation THEN NULL; END;
 -- Contract v1 negative DDL checks using fresh token/activation/attempt (no attempt_id collision)
 INSERT INTO execution_token(tenant_id,token_id,run_id,token_state,node_key) VALUES(t,tok_neg,r,'READY','n_neg');
 INSERT INTO node_activation(tenant_id,activation_id,run_id,token_id,activation_no,node_key,activation_state)
 VALUES(t,act_neg,r,tok_neg,1,'n_neg','RUNNING');
 INSERT INTO node_attempt(tenant_id,attempt_id,run_id,activation_id,attempt_no,dispatch_id,attempt_state,side_effect_class)
 VALUES(t,att_neg,r,act_neg,1,gen_random_uuid(),'STARTED','PURE');
 BEGIN
  INSERT INTO command_ledger(tenant_id,command_id,run_id,activation_id,attempt_id,workstation_id,device_id,command_kind,operation_name,command_state,idempotency_class,command_payload,payload_hash)
  VALUES(t,gen_random_uuid(),r,act_neg,att_neg,NULL,'dev','neg','test_op','CREATED','IDEMPOTENT','{}',decode(repeat('a1',32),'hex'));
  RAISE EXCEPTION 'expected NOT NULL violation on command_ledger.workstation_id';
 EXCEPTION WHEN not_null_violation THEN NULL; END;
 BEGIN
  INSERT INTO command_ledger(tenant_id,command_id,run_id,activation_id,attempt_id,workstation_id,device_id,command_kind,operation_name,command_state,idempotency_class,command_payload,payload_hash)
  VALUES(t,gen_random_uuid(),r,act_neg,att_neg,'ws-contract-test','dev','neg',NULL,'CREATED','IDEMPOTENT','{}',decode(repeat('a2',32),'hex'));
  RAISE EXCEPTION 'expected NOT NULL violation on command_ledger.operation_name';
 EXCEPTION WHEN not_null_violation THEN NULL; END;
 FOREACH bad_ws IN ARRAY ARRAY['', '   ', E'\t', E'\n'] LOOP
  BEGIN
   INSERT INTO command_ledger(tenant_id,command_id,run_id,activation_id,attempt_id,workstation_id,device_id,command_kind,operation_name,command_state,idempotency_class,command_payload,payload_hash)
   VALUES(t,gen_random_uuid(),r,act_neg,att_neg,bad_ws,'dev','neg','test_op','CREATED','IDEMPOTENT','{}',decode(repeat('a3',32),'hex'));
   RAISE EXCEPTION 'expected non-whitespace check violation on workstation_id';
  EXCEPTION WHEN check_violation THEN NULL; END;
  BEGIN
   INSERT INTO command_ledger(tenant_id,command_id,run_id,activation_id,attempt_id,workstation_id,device_id,command_kind,operation_name,command_state,idempotency_class,command_payload,payload_hash)
   VALUES(t,gen_random_uuid(),r,act_neg,att_neg,'ws-contract-test','dev','neg',bad_ws,'CREATED','IDEMPOTENT','{}',decode(repeat('a4',32),'hex'));
   RAISE EXCEPTION 'expected non-whitespace check violation on operation_name';
  EXCEPTION WHEN check_violation THEN NULL; END;
 END LOOP;
 BEGIN
  INSERT INTO command_ledger(tenant_id,command_id,run_id,activation_id,attempt_id,workstation_id,device_id,command_kind,operation_name,command_state,idempotency_class,command_payload,payload_hash)
  VALUES(t,gen_random_uuid(),r,act_neg,att_neg,'ws-unregistered','dev','neg','test_op','CREATED','IDEMPOTENT','{}',decode(repeat('a5',32),'hex'));
  RAISE EXCEPTION 'expected FK violation on unregistered workstation_id';
 EXCEPTION WHEN foreign_key_violation THEN NULL; END;
 BEGIN
  INSERT INTO quarantine_record(tenant_id,resource_id,run_id,quarantine_state,quarantine_origin,command_id,reason_code,evidence)
  VALUES(t,'device:neg',r,'ACTIVE','COMMAND_UNKNOWN',NULL,'TEST','{}');
  RAISE EXCEPTION 'expected CHECK violation when COMMAND_UNKNOWN quarantine lacks command_id';
 EXCEPTION WHEN check_violation THEN NULL; END;
 INSERT INTO inbox_receipt(tenant_id,inbox_id,source,source_message_id,payload_hash,message_type,run_id)
 VALUES(t,inbox_neg,'DEVICE_SPONTANEOUS_EARLY','early-neg-1',decode(repeat('b1',32),'hex'),'DEVICE_SPONTANEOUS_EARLY',r);
 BEGIN
  INSERT INTO wait_early_signal_buffer(tenant_id,inbox_id,run_id,correlation_key,expires_at)
  VALUES(t,inbox_neg,r2,'corr-neg',clock_timestamp()+interval '1 hour');
  RAISE EXCEPTION 'expected composite FK violation on cross-run wait_early_signal_buffer';
 EXCEPTION WHEN foreign_key_violation THEN NULL; END;
 BEGIN
  INSERT INTO node_activation(tenant_id,run_id,token_id,activation_no,node_key,activation_state)
  VALUES(t,r,tok,2,'n1','READY');
  RAISE EXCEPTION 'expected one live activation per token';
 EXCEPTION WHEN unique_violation THEN NULL; END;
 BEGIN
  INSERT INTO node_attempt(tenant_id,run_id,activation_id,attempt_no,dispatch_id,attempt_state,side_effect_class)
  VALUES(t,r,act,2,gen_random_uuid(),'CREATED','PURE');
  RAISE EXCEPTION 'expected one live attempt per activation';
 EXCEPTION WHEN unique_violation THEN NULL; END;
 INSERT INTO resource_lease(tenant_id,lease_id,run_id,resource_id,lease_state,owner_kind,owner_id)
 VALUES(t,l1,r,'resource-1','QUARANTINED','RUN',r);
 BEGIN
  INSERT INTO resource_lease(tenant_id,run_id,resource_id,lease_state,owner_kind,owner_id)
  VALUES(t,r,'resource-1','ACTIVE','RUN',r);
  RAISE EXCEPTION 'expected quarantined lease to block reassignment';
 EXCEPTION WHEN unique_violation THEN NULL; END;
 INSERT INTO runtime_event(tenant_id,run_id,event_seq,event_type,aggregate_type,payload)
 VALUES(t,r,1,'TEST','RUN','{}');
 BEGIN
  INSERT INTO runtime_event(tenant_id,run_id,event_seq,event_type,aggregate_type,payload)
  VALUES(t,r,1,'DUP','RUN','{}');
  RAISE EXCEPTION 'expected unique event sequence';
 EXCEPTION WHEN unique_violation THEN NULL; END;
END $$;
ROLLBACK;
