import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert, Button, Card, Empty, Select, Space, Table, Tag, Typography, message,
} from 'antd';
import { apiFetch } from '../api/client';

interface Template { id: number; templateNo: string; name: string; status: string; }
interface Position { pointId: number | null; code: string; parentDeviceCode: string; }
interface Placement {
  id: number; roleCode: string; materialKind: string; materialRef?: number | null;
  quantity: number; required: boolean; status: string;
  positionId?: number | null; positionCode?: string | null; deviceCode?: string | null;
  lifecycle?: string | null;
}

const STATUS_TEXT: Record<string, { zh: string; color: string }> = {
  unassigned: { zh: '未分配', color: 'default' },
  assigned: { zh: '已分配', color: 'blue' },
  verified: { zh: '已核验', color: 'green' },
  loaded: { zh: '已装载', color: 'cyan' },
  consumed: { zh: '已消耗', color: 'purple' },
  unloaded: { zh: '已卸载', color: 'default' },
  absent: { zh: '缺失', color: 'red' },
};

export default function MaterialConfigPage() {
  const qc = useQueryClient();
  const [templateId, setTemplateId] = useState<number | undefined>();
  const [runId, setRunId] = useState<number | undefined>();
  const [islandId, setIslandId] = useState<number | undefined>();

  const templatesQ = useQuery({
    queryKey: ['experiment-templates'],
    queryFn: () => apiFetch('/api/experiment-templates').then((r) => r.json()) as Promise<{
      items: Template[];
    }>,
  });
  const templates = templatesQ.data?.items ?? [];

  const positionsQ = useQuery({
    queryKey: ['positions', islandId],
    queryFn: () => apiFetch(`/api/positions?islandId=${islandId}`).then((r) => r.json()) as Promise<{
      items: Position[];
    }>,
    enabled: islandId != null,
  });
  const positionOptions = (positionsQ.data?.items ?? [])
    .filter((p) => p.pointId != null)
    .map((p) => ({ value: p.pointId as number, label: `${p.parentDeviceCode} · ${p.code}` }));

  const manifestQ = useQuery({
    queryKey: ['manifest', runId],
    queryFn: () => apiFetch(`/api/experiment-runs/${runId}/manifest`).then((r) => r.json()) as Promise<{
      items: Placement[];
    }>,
    enabled: runId != null,
    refetchInterval: 5000,
  });
  const items = manifestQ.data?.items ?? [];

  const readinessQ = useQuery({
    queryKey: ['readiness', runId],
    queryFn: () => apiFetch(`/api/experiment-runs/${runId}/readiness`).then((r) => r.json()) as Promise<{
      ready: boolean; reasons: Array<{ code: string; message: string }>;
    }>,
    enabled: runId != null,
    refetchInterval: 5000,
  });

  const instantiate = async () => {
    if (templateId == null) { message.warning('先选模板'); return; }
    try {
      const r = await apiFetch(`/api/experiment-templates/${templateId}/instantiate`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({}),
      }).then((x) => x.json()) as { runId: number; experimentId: number; islandId?: number };
      setRunId(r.runId);
      message.success(`已实例化，run #${r.runId}`);
    } catch (e) { message.error(`实例化失败：${(e as Error).message}`); }
  };

  const place = async (pid: number, positionId: number) => {
    try {
      await apiFetch(`/api/experiment-runs/${runId}/placements/${pid}`, {
        method: 'PUT', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ positionId }),
      });
      qc.invalidateQueries({ queryKey: ['manifest'] });
      qc.invalidateQueries({ queryKey: ['readiness'] });
    } catch (e) { message.error(`配置失败：${(e as Error).message}`); }
  };

  const verify = async (pid: number) => {
    try {
      await apiFetch(`/api/experiment-runs/${runId}/placements/${pid}/verify`, { method: 'POST' });
      qc.invalidateQueries({ queryKey: ['manifest'] });
      qc.invalidateQueries({ queryKey: ['readiness'] });
    } catch (e) { message.error(`核验失败：${(e as Error).message}`); }
  };

  const start = async () => {
    try {
      await apiFetch(`/api/experiment-runs/${runId}/start`, { method: 'POST' });
      message.success('已启动');
    } catch (e) { message.error(`启动失败：${(e as Error).message}`); }
  };

  return (
    <div style={{ padding: 16 }}>
      <Card size="small" title="物料配置"
        extra={<Space>
          <Select size="small" allowClear placeholder="选岛" style={{ width: 150 }}
            value={islandId} onChange={setIslandId}
            options={[ { value: 1000005, label: '基线岛' } ]} />
          <Select size="small" placeholder="选实验模板" style={{ width: 220 }}
            value={templateId} onChange={setTemplateId}
            options={templates.map((t) => ({ value: t.id, label: `${t.name}（${t.templateNo}）` }))} />
          <Button size="small" type="primary" onClick={instantiate}>实例化</Button>
        </Space>}>
        {runId == null ? (
          <Empty description="先选模板并实例化" />
        ) : (
          <>
            <Typography.Text type="secondary">run #{runId} · {items.length} 个物料</Typography.Text>
            {readinessQ.data && (
              <Alert style={{ margin: '8px 0' }}
                type={readinessQ.data.ready ? 'success' : 'warning'} showIcon
                message={readinessQ.data.ready ? '齐套，可以启动' : '未齐套'}
                description={readinessQ.data.reasons.map((r) => r.message).join('；')} />
            )}
            <Table<Placement> size="small" rowKey="id" dataSource={items} pagination={false}
              columns={[
                { title: '物料角色', dataIndex: 'roleCode', width: 120,
                  render: (v: string) => <Typography.Text code>{v}</Typography.Text> },
                { title: '类型', dataIndex: 'materialKind', width: 90 },
                { title: '必填', dataIndex: 'required', width: 60,
                  render: (v: boolean) => v ? <Tag color="red">必填</Tag> : '—' },
                { title: '目标点位', key: 'pos', width: 240,
                  render: (_, r) => (
                    <Select size="small" allowClear showSearch optionFilterProp="label"
                      style={{ width: '100%' }} placeholder="选点位"
                      value={r.positionId ?? undefined}
                      options={positionOptions}
                      onChange={(v) => place(r.id, v)} />
                  ) },
                { title: '核验', key: 'verify', width: 90,
                  render: (_, r) => (
                    <Button size="small" disabled={r.positionId == null || r.status === 'verified'}
                      onClick={() => verify(r.id)}>核验</Button>
                  ) },
                { title: '状态', dataIndex: 'status', width: 90,
                  render: (v: string) => {
                    const s = STATUS_TEXT[v] ?? { zh: v, color: 'default' };
                    return <Tag color={s.color}>{s.zh}</Tag>;
                  } },
                { title: '生命周期', dataIndex: 'lifecycle', width: 110,
                  render: (v: string | null | undefined) => {
                    if (v === 'process') return <Tag color="orange">本次专属</Tag>;
                    if (v === 'inventory') return <Tag color="green">永久库存</Tag>;
                    return '—';
                  } },
              ]} />
            <Space style={{ marginTop: 12 }}>
              <Button type="primary" disabled={!readinessQ.data?.ready} onClick={start}>
                启动实验
              </Button>
            </Space>
          </>
        )}
      </Card>
    </div>
  );
}
