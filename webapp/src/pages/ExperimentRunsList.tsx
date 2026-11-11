import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import {
  Button, Card, Empty, Input, Select, Space, Table, Tag, Typography,
} from 'antd';
import { apiFetch } from '../api/client';
import { statusColor, statusLabel, useStatusVocabulary } from '../utils/status';
import { useT } from '../i18n';

interface RunRow {
  id: number;
  experimentId: number;
  experimentName?: string | null;
  experimentNo?: string | null;
  status: string;
  controlState?: string | null;
  expandedSteps?: number;
  currentSeq?: number;
  startedAt?: string | null;
  finishedAt?: string | null;
  durationSec?: number | null;
  error?: string | null;
  pauseKind?: string | null;
  pauseReason?: string | null;
  stopRequested?: boolean;
}

function fmtTime(s?: string | null): string {
  if (!s) return '—';
  const naive = /^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}/.test(s)
    && !/(?:Z|[+-]\d{2}:?\d{2})$/.test(s);
  if (naive) return s.slice(0, 19).replace('T', ' ');
  const d = new Date(s);
  if (Number.isNaN(d.getTime())) return s.slice(0, 19).replace('T', ' ');
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} `
    + `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

/**
 * **实验执行记录**总表（全局）。
 *
 * <p>为什么要有这一页：值班员问「现在有哪些实验在跑 / 卡着 / 刚失败」，
 * 要的是**一张总表**，而不是逐个实验点进去翻（实验几十个，点不过来）。
 *
 * <p>菜单里的「实验执行记录」入口 ⇒ 这一页；
 * 单个实验的执行记录在 `/experiments/:id/runs`（上一轮拆出来的）。
 */
export default function ExperimentRunsListPage() {
  const t = useT();
  const nav = useNavigate();
  const vocab = useStatusVocabulary();
  const [kw, setKw] = useState('');
  const [status, setStatus] = useState<string | undefined>(undefined);

  const q = useQuery({
    queryKey: ['all-exp-runs'],
    queryFn: () => apiFetch('/api/experiment-runs').then((r) => r.json()) as Promise<{
      total: number; items: RunRow[];
    }>,
    refetchInterval: 5000,
  });

  const all = q.data?.items ?? [];
  const rows = all.filter((r) => {
    if (status && r.status !== status) return false;
    if (!kw.trim()) return true;
    const k = kw.trim().toLowerCase();
    return [String(r.id), r.experimentName, r.experimentNo]
      .some((x) => (x ?? '').toLowerCase().includes(k));
  });

  const counts = (s: string) => all.filter((r) => r.status === s).length;

  return (
    <div style={{ padding: 16 }}>
      <Card size="small"
        title={<Space><span>{t('expRunsList.title')}</span>
          <Tag>{rows.length}</Tag></Space>}
        extra={<Space>
          <Input size="small" allowClear placeholder={t('expRunsList.search')}
            value={kw} onChange={(e) => setKw(e.target.value)}
            style={{ width: 200 }} />
          <Select size="small" allowClear placeholder={t('expRunsList.filterStatus')}
            value={status} onChange={setStatus} style={{ width: 150 }}
            options={[
              { value: 'running', label: `${t('expRunsList.st.running')} (${counts('running')})` },
              { value: 'paused', label: `${t('expRunsList.st.paused')} (${counts('paused')})` },
              { value: 'queued', label: `${t('expRunsList.st.queued')} (${counts('queued')})` },
              { value: 'failed', label: `${t('expRunsList.st.failed')} (${counts('failed')})` },
              { value: 'completed', label: `${t('expRunsList.st.completed')} (${counts('completed')})` },
            ]} />
          <Button size="small" onClick={() => q.refetch()}>{t('expRunsList.refresh')}</Button>
        </Space>}>
        <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
          {t('expRunsList.hint')}
        </Typography.Paragraph>
        {rows.length === 0 ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('expRunsList.empty')} />
        ) : (
          <Table<RunRow> size="small" pagination={{ pageSize: 20, showSizeChanger: false }}
            rowKey="id" dataSource={rows}
            onRow={(r) => ({
              onClick: () => nav(`/experiments/${r.experimentId}/runs`),
              style: { cursor: 'pointer' },
            })}
            columns={[
              { title: 'run', dataIndex: 'id', width: 90,
                render: (v: number) => `#${v}` },
              {
                // 实验名 + 实验号：只看 run 编号不知道是哪个实验
                title: t('expRunsList.col.experiment'), dataIndex: 'experimentName', ellipsis: true,
                render: (v: string | null, r) => (
                  <Space size={4}>
                    <Typography.Text code>{r.experimentNo ?? ''}</Typography.Text>
                    <span>{v ?? `#${r.experimentId}`}</span>
                  </Space>
                ),
              },
              {
                title: t('expRunsList.col.status'), dataIndex: 'status', width: 130,
                render: (v: string, r) => (
                  <Space size={4} wrap>
                    <Tag color={statusColor('run', v, vocab)}>{statusLabel('run', v, vocab)}</Tag>
                    {r.controlState && r.controlState !== 'NONE' && (
                      <Tag color={statusColor('control', r.controlState, vocab)}>
                        {statusLabel('control', r.controlState, vocab)}
                      </Tag>
                    )}
                  </Space>
                ),
              },
              { title: t('expRunsList.col.progress'), width: 90,
                render: (_, r) => `${r.currentSeq ?? 0}/${r.expandedSteps ?? 0}` },
              { title: t('expRunsList.col.duration'), width: 90,
                render: (_, r) => (r.durationSec != null ? `${Math.round(r.durationSec)}s` : '—') },
              { title: t('expRunsList.col.start'), width: 160,
                render: (_, r) => (
                  <span style={{ fontFamily: 'monospace', fontSize: 11 }}>{fmtTime(r.startedAt)}</span>
                ) },
              { title: t('expRunsList.col.pauseReason'), width: 200, ellipsis: true,
                render: (_, r) => (r.pauseReason ?? '—') },
              { title: t('expRunsList.col.error'), ellipsis: true,
                render: (_, r) => r.error ?? '—' },
              {
                title: '', width: 110,
                render: (_, r) => (
                  <Button size="small" type="link"
                    onClick={(e) => { e.stopPropagation(); nav(`/experiments/${r.experimentId}/runs`); }}>
                    {t('expRunsList.action.detail')}
                  </Button>
                ),
              },
            ]} />
        )}
      </Card>
    </div>
  );
}
