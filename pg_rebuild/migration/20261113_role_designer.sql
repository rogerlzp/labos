-- P0-6 · 角色重划：新增 designer（实验编排人员）
-- 三角色：operator(操作员) / designer(编排员) / admin(管理员)；viewer 保留为可选只读。
-- 幂等：ON CONFLICT DO NOTHING。
BEGIN;

INSERT INTO app_role (tenant_id, code, name, description, is_builtin)
VALUES (1, 'designer', '实验编排人员', '建/改流程、绑点位与方法、维护点位用途、管设备池与物料角色', true)
ON CONFLICT (tenant_id, code) DO NOTHING;

COMMIT;
