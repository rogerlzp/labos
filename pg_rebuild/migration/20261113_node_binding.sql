-- P0-3 · 流程节点真绑定（node_binding）+ workflow_node.binding_mode
-- node_binding 是流程节点的真源（点位/设备/池 + 方法 + 物料角色 + Loading Pattern），
-- 与 workflow 同生命周期、不塞 BPMN XML（bpmn-js 会重写丢属性）。
BEGIN;

CREATE TABLE IF NOT EXISTS node_binding (
  id            bigserial PRIMARY KEY,
  tenant_id     bigint NOT NULL,
  node_id       bigint NOT NULL REFERENCES workflow_node(id) ON DELETE CASCADE,
  seq           integer NOT NULL DEFAULT 1,
  position_id   bigint REFERENCES device_point(id),
  device_instance_id bigint REFERENCES device_instance(id),
  pool_id       bigint REFERENCES device_pool(id),
  method_id     bigint REFERENCES device_method(id),
  material_role text,
  loading_pattern text NOT NULL DEFAULT 'load_single',
  leave_at_station boolean NOT NULL DEFAULT false,
  barcode_policy text NOT NULL DEFAULT 'none',
  params        jsonb,
  enabled       boolean NOT NULL DEFAULT true,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_node_binding_loading
    CHECK (loading_pattern IN ('do_not_load','load_single','load_selected','load_all_remaining')),
  CONSTRAINT ck_node_binding_barcode
    CHECK (barcode_policy IN ('none','read','update','validate_in','validate_out','validate_both')),
  CONSTRAINT ck_node_binding_target
    CHECK (num_nonnulls(position_id, device_instance_id, pool_id) >= 1)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_node_binding_seq ON node_binding (node_id, seq);
CREATE INDEX IF NOT EXISTS idx_node_binding_node ON node_binding (node_id);
CREATE INDEX IF NOT EXISTS idx_node_binding_position ON node_binding (position_id);

-- workflow_node 绑定模式：legacy（只 XML 设备绑定）/ point（有 node_binding）/ mixed
ALTER TABLE workflow_node ADD COLUMN IF NOT EXISTS binding_mode text NOT NULL DEFAULT 'legacy';
DO $$ BEGIN
  ALTER TABLE workflow_node ADD CONSTRAINT ck_workflow_node_binding_mode
    CHECK (binding_mode IN ('legacy','point','mixed'));
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

COMMENT ON TABLE node_binding IS '流程节点真绑定：点位+方法+物料角色，与 workflow 同生命周期，不塞 BPMN XML';
COMMENT ON COLUMN workflow_node.binding_mode IS 'legacy=只 XML 设备绑定；point=有 node_binding；mixed=混合';

COMMIT;
