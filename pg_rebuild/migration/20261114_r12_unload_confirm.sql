-- R12 · 卸载确认链：manifest_placement.status 加 'unloading'
-- 语义：实验结束 → 请求卸载（loaded→unloading，位置仍占用）；确认卸载（unloading→unloaded，清位置）。
-- 幂等：DROP + ADD 包裹在 DO $$（先判是否存在再改）。

DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_constraint
             WHERE conrelid = 'manifest_placement'::regclass
               AND conname = 'ck_manifest_placement_status') THEN
    ALTER TABLE manifest_placement DROP CONSTRAINT ck_manifest_placement_status;
  END IF;
END $$;

ALTER TABLE manifest_placement ADD CONSTRAINT ck_manifest_placement_status
  CHECK (status IN ('unassigned','assigned','verified','loaded','unloading',
                    'consumed','unloaded','absent'));

COMMENT ON COLUMN manifest_placement.status IS
  'unassigned/assigned/verified/loaded/unloading(请求卸载中)/consumed/unloaded(已确认卸载)/absent';
