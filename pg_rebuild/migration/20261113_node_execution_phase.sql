-- 节点执行阶段：把“等待资源”与“命令派发”从 running 中拆开。
-- 可重复执行；只增加可观测性字段，不改变既有 node_status 和命令账本语义。

ALTER TABLE run_node ADD COLUMN IF NOT EXISTS execution_phase varchar(32);
ALTER TABLE run_node ADD COLUMN IF NOT EXISTS phase_since timestamptz;
ALTER TABLE run_node ADD COLUMN IF NOT EXISTS blocked_by_run_id bigint;
ALTER TABLE run_node ADD COLUMN IF NOT EXISTS blocked_reason text;
ALTER TABLE run_node ADD COLUMN IF NOT EXISTS wait_started_at timestamptz;
ALTER TABLE run_node ADD COLUMN IF NOT EXISTS lock_acquired_at timestamptz;
ALTER TABLE run_node ADD COLUMN IF NOT EXISTS dispatch_started_at timestamptz;
ALTER TABLE run_node ADD COLUMN IF NOT EXISTS response_at timestamptz;
ALTER TABLE run_node ADD COLUMN IF NOT EXISTS wait_resource_ms bigint NOT NULL DEFAULT 0;
ALTER TABLE run_node ADD COLUMN IF NOT EXISTS device_execution_ms bigint NOT NULL DEFAULT 0;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint
                 WHERE conrelid = 'run_node'::regclass
                   AND conname = 'ck_run_node_execution_phase') THEN
    ALTER TABLE run_node ADD CONSTRAINT ck_run_node_execution_phase
      CHECK (execution_phase IS NULL OR execution_phase IN
             ('WAITING_RESOURCE', 'DISPATCHING', 'FINALIZING'));
  END IF;
END $$;

COMMENT ON COLUMN run_node.execution_phase IS
  '运行中节点的执行阶段：WAITING_RESOURCE=等待资源且未下发；DISPATCHING=进入派发且等待最终回包；FINALIZING=已收最终回包正在收尾。NULL=未进入、已结束或旧数据。';
COMMENT ON COLUMN run_node.dispatch_started_at IS
  '中控取得设备锁后进入派发的开始时间，不是设备接收确认时间。';
COMMENT ON COLUMN run_node.wait_resource_ms IS
  '各步骤等待设备锁的累计毫秒数，不包含设备执行时间。';
COMMENT ON COLUMN run_node.device_execution_ms IS
  '各步骤从进入派发到收到最终回包的累计毫秒数，不包含等待设备锁时间。';

CREATE INDEX IF NOT EXISTS ix_run_node_execution_phase
  ON run_node (tenant_id, execution_phase)
  WHERE execution_phase IS NOT NULL;
