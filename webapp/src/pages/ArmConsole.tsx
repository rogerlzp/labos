import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert, Button, Card, Descriptions, Select, Table, Tag, Typography, message,
} from 'antd';
import { apiFetch } from '../api/client';

interface Island { id: number; code: string; name: string; devices: Device[]; }
interface Device { resourceId: number; code: string; name: string; deviceType?: string; }
interface RobotPoint {
  id: number; code: string; name?: string; x?: number | null; y?: number | null; z?: number | null;
  deviceResourceId?: number; fillStatus?: string; action?: string;
}

export default function ArmConsolePage() {
  const qc = useQueryClient();
  const [islandId, setIslandId] = useState<number | undefined>();

  const topoQ = useQuery({
    queryKey: ['topology'],
    queryFn: () => apiFetch('/api/topology').then((r) => r.json()) as Promise<{ islands: Island[] }>,
  });
  const islands = topoQ.data?.islands ?? [];
  const arms = (islands.find((i) => i.id === islandId)?.devices ?? [])
    .filter((d) => d.deviceType === 'Robots');

  const pointsQ = useQuery({
    queryKey: ['robot-points', islandId],
    queryFn: () => apiFetch(`/api/islands/${islandId}/robot-points`).then((r) => r.json()) as Promise<{
      items?: RobotPoint[]; points?: RobotPoint[];
    }>,
    enabled: islandId != null,
  });
  const points = pointsQ.data?.items ?? pointsQ.data?.points ?? [];

  const capture = async (code: string) => {
    try {
      await apiFetch(`/api/islands/${islandId}/robot-points/${encodeURIComponent(code)}/capture`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({}),
      });
      message.success(`已从机械臂读取 ${code} 坐标`);
      qc.invalidateQueries({ queryKey: ['robot-points'] });
    } catch (e) { message.error(`示教读取失败：${(e as Error).message}`); }
  };

  return (
    <div style={{ padding: 16 }}>
      <Card size="small" title="机械臂调试台"
        extra={<Select size="small" placeholder="选岛" style={{ width: 160 }}
          value={islandId} onChange={setIslandId}
          options={islands.map((i) => ({ value: i.id, label: `${i.name}（${i.code}）` }))} />}>
        <Alert type="warning" showIcon style={{ marginBottom: 12 }}
          message="仅管理员可用；单步动作/示教录入会写审计，机械臂被占用时拒绝执行" />

        <Typography.Text strong>机械臂（{arms.length} 台）</Typography.Text>
        {arms.map((a) => (
          <Descriptions key={a.resourceId} size="small" bordered column={3}
            style={{ margin: '8px 0' }}>
            <Descriptions.Item label="编号">{a.code}</Descriptions.Item>
            <Descriptions.Item label="名称">{a.name}</Descriptions.Item>
            <Descriptions.Item label="资源 id">{a.resourceId}</Descriptions.Item>
          </Descriptions>
        ))}

        <Typography.Text strong style={{ display: 'block', marginTop: 12 }}>
          点位示教（{points.length} 个，x/y/z 为空 = 待示教，不编造坐标）
        </Typography.Text>
        <Table<RobotPoint> size="small" rowKey="id" dataSource={points} pagination={{ pageSize: 15 }}
          style={{ marginTop: 8 }}
          columns={[
            { title: '点位', dataIndex: 'code', width: 180,
              render: (v: string) => <Typography.Text code>{v}</Typography.Text> },
            { title: 'x / y / z', key: 'xyz', width: 220,
              render: (_, r) => (r.x != null && r.y != null && r.z != null)
                ? `${r.x}, ${r.y}, ${r.z}` : <Tag color="orange">待示教</Tag> },
            { title: '状态', dataIndex: 'fillStatus', width: 100,
              render: (v: string) => (v === 'pending'
                ? <Tag color="orange">pending</Tag> : <Tag color="green">{v ?? 'ok'}</Tag>) },
            { title: '', key: 'op', width: 110,
              render: (_, r) => <Button size="small" onClick={() => capture(r.code)}>示教读取</Button> },
          ]} />
      </Card>
    </div>
  );
}
