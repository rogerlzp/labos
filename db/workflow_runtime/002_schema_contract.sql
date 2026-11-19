DO $$
DECLARE
  expected text[] := ARRAY['tenant','definition_snapshot','workflow_run','run_request_dedup','execution_token','node_activation','node_attempt','scheduler_capacity','dispatch_reservation','split_activation','branch_activation','join_activation','loop_context','wait_registration','retry_registration','flow_variable','branch_output','runtime_event','inbox_receipt','inbox_protocol_violation','runtime_outbox','command_ledger','command_dispatch_attempt','resource_fence_counter','resource_lease','drain_record','drain_item','runtime_checkpoint','manual_intervention','quarantine_record','runtime_audit'];
  missing text[];
BEGIN
  SELECT array_agg(x.table_name) INTO missing FROM unnest(expected) AS x(table_name)
  WHERE NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
    WHERE n.nspname='workflow_runtime' AND c.relname=x.table_name AND c.relkind='r');
  IF missing IS NOT NULL THEN RAISE EXCEPTION 'Missing tables: %',missing; END IF;
  IF (SELECT count(*) FROM pg_indexes WHERE schemaname='workflow_runtime') < 19 THEN
    RAISE EXCEPTION 'Insufficient indexes';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname='workflow_runtime' AND indexname='activation_one_live_per_token_uq') THEN
    RAISE EXCEPTION 'Missing live activation partial unique index';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname='workflow_runtime' AND indexname='attempt_one_live_per_activation_uq') THEN
    RAISE EXCEPTION 'Missing live attempt partial unique index';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname='workflow_runtime' AND indexname='resource_one_live_lease_uq') THEN
    RAISE EXCEPTION 'Missing live lease partial unique index';
  END IF;
END $$;
BEGIN;
SET LOCAL search_path=workflow_runtime,public;
DO $$
DECLARE
 t uuid:=gen_random_uuid(); d uuid:=gen_random_uuid(); r uuid:=gen_random_uuid();
 tok uuid:=gen_random_uuid(); act uuid:=gen_random_uuid(); att uuid:=gen_random_uuid();
 cmd uuid:=gen_random_uuid(); l1 uuid:=gen_random_uuid();
BEGIN
 INSERT INTO tenant(tenant_id,tenant_key,display_name) VALUES(t,'contract-'||t::text,'contract');
 INSERT INTO definition_snapshot(tenant_id,definition_id,definition_key,definition_version,definition_hash,compiler_version,graph_plan)
 VALUES(t,d,'contract','1',decode(repeat('ab',32),'hex'),'test','{}');
 INSERT INTO workflow_run(tenant_id,run_id,definition_id,status) VALUES(t,r,d,'CREATED');
 INSERT INTO execution_token(tenant_id,token_id,run_id,token_state,node_key) VALUES(t,tok,r,'READY','n1');
 INSERT INTO node_activation(tenant_id,activation_id,run_id,token_id,activation_no,node_key,activation_state)
 VALUES(t,act,r,tok,1,'n1','RUNNING');
 INSERT INTO node_attempt(tenant_id,attempt_id,run_id,activation_id,attempt_no,dispatch_id,dispatch_epoch,attempt_state,side_effect_class)
 VALUES(t,att,r,act,1,gen_random_uuid(),1,'STARTED','PURE');
 INSERT INTO command_ledger(tenant_id,command_id,run_id,activation_id,attempt_id,device_id,command_kind,command_state,idempotency_class,command_payload,payload_hash)
 VALUES(t,cmd,r,act,att,'dev','test','CREATED','IDEMPOTENT','{}',decode(repeat('cd',32),'hex'));
 BEGIN
  INSERT INTO command_ledger(tenant_id,command_id,run_id,activation_id,attempt_id,device_id,command_kind,command_state,idempotency_class,command_payload,payload_hash)
  VALUES(t,gen_random_uuid(),r,act,att,'dev','duplicate','CREATED','IDEMPOTENT','{}',decode(repeat('ef',32),'hex'));
  RAISE EXCEPTION 'expected unique command per attempt';
 EXCEPTION WHEN unique_violation THEN NULL; END;
 BEGIN
  INSERT INTO node_activation(tenant_id,run_id,token_id,activation_no,node_key,activation_state)
  VALUES(t,r,tok,2,'n1','READY');
  RAISE EXCEPTION 'expected one live activation per token';
 EXCEPTION WHEN unique_violation THEN NULL; END;
 BEGIN
  INSERT INTO node_attempt(tenant_id,run_id,activation_id,attempt_no,dispatch_id,dispatch_epoch,attempt_state,side_effect_class)
  VALUES(t,r,act,2,gen_random_uuid(),1,'CREATED','PURE');
  RAISE EXCEPTION 'expected one live attempt per activation';
 EXCEPTION WHEN unique_violation THEN NULL; END;
 INSERT INTO resource_lease(tenant_id,lease_id,run_id,resource_id,lease_state,owner_kind,owner_id,fencing_token)
 VALUES(t,l1,r,'resource-1','QUARANTINED','RUN',r,1);
 BEGIN
  INSERT INTO resource_lease(tenant_id,run_id,resource_id,lease_state,owner_kind,owner_id,fencing_token)
  VALUES(t,r,'resource-1','ACTIVE','RUN',r,2);
  RAISE EXCEPTION 'expected quarantined lease to block reassignment';
 EXCEPTION WHEN unique_violation THEN NULL; END;
 INSERT INTO runtime_event(tenant_id,run_id,event_seq,event_type,aggregate_type,coordinator_epoch,payload)
 VALUES(t,r,1,'TEST','RUN',1,'{}');
 BEGIN
  INSERT INTO runtime_event(tenant_id,run_id,event_seq,event_type,aggregate_type,coordinator_epoch,payload)
  VALUES(t,r,1,'DUP','RUN',1,'{}');
  RAISE EXCEPTION 'expected unique event sequence';
 EXCEPTION WHEN unique_violation THEN NULL; END;
END $$;
ROLLBACK;
