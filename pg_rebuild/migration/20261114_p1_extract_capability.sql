-- P1 · 核酸提取工作站资源模型（第一刀：能力字典）
-- EXTRACT 能力：提取仪内部工艺（初始化/转盘/孵育/磁吸/洗涤/洗脱），不再借用 MIX/ANALYZE。
-- 幂等：ON CONFLICT DO NOTHING。

INSERT INTO capability (tenant_id, code, name, category, description)
VALUES (1, 'EXTRACT', '核酸提取', 'extraction',
        '提取仪内部工艺：初始化、转盘、孵育、磁吸、洗涤、洗脱；等待交互由 __awaitEvent 承接')
ON CONFLICT (tenant_id, code) DO NOTHING;
