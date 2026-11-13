import { useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useLocation, useNavigate } from 'react-router-dom';
import {
  Alert, Button, Card, Empty, Input, Select, Space, Table, Tag, Tooltip, Typography,
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
  /** 实验归属（审计表只有 run_id，由后端按 experiment_run_step / experiment.run_id 解析）。 */
  experimentId?: number | null;
  experimentNo?: string | null;
  experimentName?: string | null;
  createdAt?: string | null;
}

interface ScopeExperiment {
  id: number; experimentNo: string; name: string; traceCount: number;
}
interface ScopeRun { id: number; runNo?: string | null; traceCount: number; }
interface TraceData {
  total: number;
  items: TraceRow[];
  scopes?: { experiments: ScopeExperiment[]; runs: ScopeRun[] };
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
 * **一条痕迹都没有**。现在放板/取板都会留一行（{@code operator='AUTO'}）。
 *
 * <p><b>实验范围</b>：审计行只有 run_id，后端按
 * `experiment_run_step.run_id → experiment_run.experiment_id`（及 `experiment.run_id` 兜底）
 * 解析出实验。选定实验后，页面把该实验里每块板的**位置链**串出来，
 * 回答「这一个实验里板子是怎么走的」。解析不到实验的是独立 workflow 执行 / 人工盘点，
 * 只在「全部实验」范围出现，绝不按时间窗猜归属（并行实验会串台）。
 *
 * <p>从实验详情页 `/experiments/:id` 可带 `?experimentId=` 直接跳进来。
 */
export default function PlateTracesPage() {
  const t = useT();
  const nav = useNavigate();
  const location = useLocation();
  const [barcode, setBarcode] = useState('');
  const [experimentId, setExperimentId] = useState<number>(0); // 0 = 全部实验
  const [runId, setRunId] = useState<number>(0);               // 0 = 全部执行单

  // 支持从实验详情页带范围跳进来；URL 变化时同步筛选器。
  useEffect(() => {
    const v = Number(new URLSearchParams(location.search).get('experimentId'));
    if (v > 0) { setExperimentId(v); setRunId(0); }
  }, [location.search]);

  const q = useQuery({
    queryKey: ['plate-traces', barcode, experimentId, runId],
    queryFn: () => {
      const p = new URLSearchParams();
      if (barcode.trim()) p.set('barcode', barcode.trim());
      if (experimentId > 0) p.set('experimentId', String(experimentId));
      if (runId > 0) p.set('runId', String(runId));
      const qs = p.toString();
      return apiFetch(`/api/plates/traces${qs ? `?${qs}` : ''}`)
        .then((r) => r.json()) as Promise<TraceData>;
    },
    refetchInterval: 5000,
  });

  const rows = q.data?.items ?? [];
  const experiments = q.data?.scopes?.experiments ?? [];
  const runs = q.data?.scopes?.runs ?? [];
  const scopedExperiment = experiments.find((e) => e.id === experimentId);

  /** 按板号聚成一条位置链（时间正序，相邻重复位置合并）。 */
  const flows = useMemo(() => {
    const by = new Map<string, TraceRow[]>();
    rows.forEach((r) => {
      const list = by.get(r.barcode) ?? [];
      list.push(r);
      by.set(r.barcode, list);
    });
    return Array.from(by.entries()).map(([code, list]) => {
      const ordered = [...list].sort((a, b) =>
        (a.createdAt ?? '').localeCompare(b.createdAt ?? ''));
      const steps: string[] = [];
      ordered.forEach((r) => {
        const loc = r.actualLocation?.trim();
        if (loc && steps[steps.length - 1] !== loc) steps.push(loc);
      });
      return {
        barcode: code, steps, count: list.length,
        last: steps.length > 0 ? steps[steps.length - 1] : '',
        lastAt: ordered[ordered.length - 1]?.createdAt,
      };
    }).sort((a, b) => (b.lastAt ?? '').localeCompare(a.lastAt ?? ''));
  }, [rows]);

  return (
    <div style={{ padding: 16 }}>
      {/* 实验范围内，每块板怎么走 —— 比一行行流水更直观 */}
      {flows.length > 0 && (
        <Card size="small" style={{ marginBottom: 12 }}
          title={<Space><span>{t('plateTraces.flow.title')}</span><Tag>{flows.length}</Tag></Space>}
          extra={scopedExperiment
            ? <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {t('plateTraces.scope.current')}：{scopedExperiment.experimentNo} {scopedExperiment.name}
              </Typography.Text>
            : undefined}>
          <div style={{ maxHeight: 230, overflow: 'auto' }}>
            {flows.map((f) => (
              <div key={f.barcode}
                style={{ display: 'flex', alignItems: 'flex-start', gap: 10, padding: '6px 0',
                  borderBottom: '1px solid #f0f0f0', fontSize: 12 }}>
                <Typography.Text code style={{ flexShrink: 0 }}>{f.barcode}</Typography.Text>
                <div style={{ flex: 1, lineHeight: '22px' }}>
                  {f.steps.length === 0
                    ? <Typography.Text type="secondary">{t('plateTraces.flow.noLocation')}</Typography.Text>
                    : f.steps.map((s, i) => (
                        <span key={`${s}-${i}`}>
                          {i > 0 && <span style={{ color: '#aaa', margin: '0 4px' }}>→</span>}
                          <Tag color={i === f.steps.length - 1 ? 'blue' : undefined}
                            style={{ fontFamily: 'monospace', fontSize: 11 }}>{s}</Tag>
                        </span>
                      ))}
                </div>
                <Typography.Text type="secondary" style={{ flexShrink: 0, fontSize: 11 }}>
                  {f.count} {t('plateTraces.flow.stepsUnit')}
                  {f.last ? ` · ${t('plateTraces.flow.current')} ${f.last}` : ''}
                </Typography.Text>
              </div>
            ))}
          </div>
        </Card>
      )}

      <Card size="small"
        title={<Space><span>{t('plateTraces.title')}</span>
          <Tag>{rows.length}</Tag></Space>}
        extra={<Space wrap>
          <Select size="small" style={{ width: 240 }} value={experimentId}
            onChange={(v) => { setExperimentId(v); setRunId(0); }}
            options={[
              { value: 0, label: t('plateTraces.scope.allExperiments') },
              ...experiments.map((e) => ({
                value: e.id,
                label: `${e.experimentNo} ${e.name}（${e.traceCount}）`,
              })),
            ]} />
          <Select size="small" style={{ width: 190 }} value={runId}
            onChange={setRunId}
            options={[
              { value: 0, label: t('plateTraces.scope.allRuns') },
              ...runs.map((r) => ({
                value: r.id,
                label: `${r.runNo ?? `#${r.id}`}（${r.traceCount}）`,
              })),
            ]} />
          <Input size="small" allowClear placeholder={t('plateTraces.search')}
            value={barcode} onChange={(e) => setBarcode(e.target.value)}
            style={{ width: 170 }} />
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
                title: t('plateTraces.col.experiment'), width: 190, ellipsis: true,
                render: (_, r) => (r.experimentId
                  ? <Tooltip title={`${r.experimentNo ?? ''} ${r.experimentName ?? ''}`}>
                      <Button size="small" type="link"
                        onClick={() => nav(`/experiments/${r.experimentId}`)}>
                        {r.experimentNo ?? `#${r.experimentId}`}
                      </Button>
                    </Tooltip>
                  : <Tag>{t('plateTraces.independent')}</Tag>),
              },
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
                render: (v: string | null) => (
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
                render: (v: string | null) => (
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
