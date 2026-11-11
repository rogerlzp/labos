import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import {
  Alert, Button, Card, Empty, Input, Space, Table, Tag, Tooltip, Typography,
} from 'antd';
import { apiFetch } from '../api/client';
import { useT } from '../i18n';

interface TraceRow {
  id: number;
  barcode: string;
  fromStatus?: string | null;
  toStatus?: string | null;
  actualLocation?: string | null;
  operator?: string | null;
  note?: string | null;
  runId?: number | null;
  createdAt?: string | null;
}

/**
 * 板子状态词表（与库里 `plate.position_status` 同一套）。
 *
 * <p>为什么页面上要翻成人话：`KNOWN` / `IN_TRANSFER` 是内部词，
 * 现场看到要知道的是"板子现在在哪、是不是在机械臂上"。
 */
const STATUS_TEXT: Record<string, { zh: string; en: string; color: string }> = {
  KNOWN: { zh: '在位', en: 'in place', color: 'green' },
  IN_TRANSFER: { zh: '在途', en: 'in transfer', color: 'blue' },
  UNKNOWN: { zh: '位置不明', en: 'unknown', color: 'orange' },
  LOST: { zh: '已丢失', en: 'lost', color: 'red' },
};

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
 * **板子流转**：板子编号在设备之间是怎么流转的。
 *
 * <p><b>为什么要有这一页</b>：以前 {@code plate_location_audit} 这张审计表
 * **只在人工盘点时写** ⇒ 执行过程里板子被放进哪台设备、又被搬到哪儿去，
 * **一条痕迹都没有**。现场问「PL-001 现在在哪、怎么过来的」，答不上来。
 *
 * <p>现在放板/取板都会留一行（{@code operator='AUTO'}），人工盘点留的行
 * {@code operator} 是人名 ⇒ 一张表里两种来源都能分清。
 *
 * <p>按板号筛选 = 看**单个板**的完整轨迹；不筛 = 最近的流转流水。
 */
export default function PlateTracesPage() {
  const t = useT();
  const nav = useNavigate();
  const [barcode, setBarcode] = useState('');

  const q = useQuery({
    queryKey: ['plate-traces', barcode],
    queryFn: () => apiFetch(
      `/api/plates/traces${barcode.trim() ? `?barcode=${encodeURIComponent(barcode.trim())}` : ''}`,
    ).then((r) => r.json()) as Promise<{ total: number; items: TraceRow[] }>,
    refetchInterval: 5000,
  });

  const rows = q.data?.items ?? [];

  return (
    <div style={{ padding: 16 }}>
      <Card size="small"
        title={<Space><span>{t('plateTraces.title')}</span>
          <Tag>{rows.length}</Tag></Space>}
        extra={<Space>
          <Input size="small" allowClear placeholder={t('plateTraces.search')}
            value={barcode} onChange={(e) => setBarcode(e.target.value)}
            style={{ width: 200 }} />
          <Button size="small" onClick={() => q.refetch()}>{t('plateTraces.refresh')}</Button>
        </Space>}>
        <Alert type="info" showIcon style={{ marginBottom: 12 }}
          message={t('plateTraces.hint')} />
        {rows.length === 0 ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('plateTraces.empty')} />
        ) : (
          <Table<TraceRow> size="small" pagination={{ pageSize: 20, showSizeChanger: false }}
            rowKey="id" dataSource={rows}
            columns={[
              { title: t('plateTraces.col.time'), dataIndex: 'createdAt', width: 170,
                render: (v: string | null) => (
                  <span style={{ fontFamily: 'monospace', fontSize: 11 }}>{fmtTime(v)}</span>
                ) },
              { title: t('plateTraces.col.barcode'), dataIndex: 'barcode', width: 150,
                render: (v: string) => <Typography.Text code>{v}</Typography.Text> },
              {
                title: t('plateTraces.col.change'), width: 230,
                render: (_, r) => {
                  const from = STATUS_TEXT[r.fromStatus ?? ''];
                  const to = STATUS_TEXT[r.toStatus ?? ''];
                  return (
                    <Space size={4}>
                      {from ? <Tag>{from.zh}</Tag> : <Tag>{r.fromStatus ?? '—'}</Tag>}
                      <span>→</span>
                      {to ? <Tag color={to.color}>{to.zh}</Tag> : <Tag>{r.toStatus ?? '—'}</Tag>}
                    </Space>
                  );
                },
              },
              {
                title: t('plateTraces.col.location'), dataIndex: 'actualLocation', width: 160,
                ellipsis: true,
                render: (v: string | null, r) => (
                  v ? <span style={{ fontFamily: 'monospace', fontSize: 11 }}>{v}</span>
                    : <Typography.Text type="secondary">—</Typography.Text>
                ),
              },
              {
                title: t('plateTraces.col.note'), dataIndex: 'note', ellipsis: true,
                render: (v: string | null) => v ?? '—',
              },
              {
                title: t('plateTraces.col.by'), dataIndex: 'operator', width: 110,
                render: (v: string | null, r) => (
                  v === 'AUTO'
                    ? <Tooltip title={t('plateTraces.autoHint')}>
                        <Tag color="blue">{t('plateTraces.auto')}</Tag>
                      </Tooltip>
                    : <Tag>{v ?? '—'}</Tag>
                ),
              },
              {
                title: t('plateTraces.col.run'), dataIndex: 'runId', width: 100,
                render: (v: number | null) => (v
                  ? <Button size="small" type="link" onClick={() => nav(`/runs/${v}`)}>#{v}</Button>
                  : '—'),
              },
            ]} />
        )}
      </Card>
    </div>
  );
}
