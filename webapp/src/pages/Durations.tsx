import { apiFetch } from '../api/client';
import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Card, Input, Progress, Space, Table, Tag, Typography } from 'antd';
import { useT } from '../i18n';

interface Duration {
  method: string;
  p50Ms: number;
  p90Ms: number;
  minMs: number;
  maxMs: number;
  sampleCount: number;
}

const sec = (ms: number) => `${(ms / 1000).toFixed(ms < 1000 ? 2 : 1)}s`;

/**
 * 时长分布：同一个方法在不同设备上的耗时差异，是排程和预测的依据。
 *
 * 「波动比」= p90 / p50：> 3 的方法说明耗时极不稳定，
 * 用它做预测会偏乐观，需要按 p90 排期。
 */
export default function DurationsPage() {
  const [kw, setKw] = useState('');
  const t = useT();
  const { data, isLoading } = useQuery({
    queryKey: ['durations'],
    queryFn: () => apiFetch('/api/durations').then((r) => r.json()) as Promise<{ items: Duration[] }>,
  });

  const items = useMemo(() => {
    const all = (data?.items ?? []).filter((d) => !kw || d.method.toLowerCase().includes(kw.toLowerCase()));
    return all.map((d) => ({ ...d, ratio: d.p50Ms > 0 ? d.p90Ms / d.p50Ms : 0 }));
  }, [data, kw]);

  const maxP90 = Math.max(1, ...items.map((d) => d.p90Ms));

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card
        size="small"
        title={t('durations.title', { n: items.length })}
        extra={
          <Input.Search
            placeholder={t('durations.placeholder')} allowClear style={{ width: 220 }}
            onChange={(e) => setKw(e.target.value)}
          />
        }
      >
        <Table
          rowKey="method" size="small" loading={isLoading}
          dataSource={items}
          pagination={{ pageSize: 20, size: 'small' }}
          columns={[
            { title: t('durations.col.method'), dataIndex: 'method', width: 240 },
            {
              title: 'p50', dataIndex: 'p50Ms', width: 90,
              render: (v: number) => <span style={{ fontFamily: 'monospace' }}>{sec(v)}</span>,
            },
            {
              title: 'p90', dataIndex: 'p90Ms', width: 90,
              render: (v: number) => <span style={{ fontFamily: 'monospace' }}>{sec(v)}</span>,
            },
            {
              // 用进度条横向对比 p90 的相对大小，一眼看出谁最慢
              title: t('durations.col.p90'), width: 220,
              render: (_, r) => (
                <Progress
                  percent={Math.round((r.p90Ms / maxP90) * 100)}
                  size="small" showInfo={false}
                  strokeColor={r.ratio > 3 ? '#f59e0b' : '#3b82f6'}
                />
              ),
            },
            {
              title: t('durations.col.ratio'), dataIndex: 'ratio', width: 100,
              render: (v: number) => (
                <Tag color={v > 3 ? 'orange' : 'default'}>{v.toFixed(1)}×</Tag>
              ),
            },
            { title: t('durations.col.samples'), dataIndex: 'sampleCount', width: 90 },
          ]}
        />
        <Typography.Paragraph type="secondary" style={{ fontSize: 11, marginTop: 10, marginBottom: 0 }}>
          {t('durations.note.ratio')}
        </Typography.Paragraph>
      </Card>
    </Space>
  );
}
