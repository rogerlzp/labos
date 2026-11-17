import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Alert, Card, Descriptions, Empty, Select, Space, Table, Tag, Typography,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { apiFetch } from '../api/client';
import { useT, type MessageKey } from '../i18n';

/**
 * 差分校验报告查看器：直接打开 pylabrobot-sim `--compare-twin` 生成的 JSON。
 *
 * <p><b>为什么是文件入口而不是后端接口</b>：报告现在是仿真器本机产物，
 * LabOS 内核没有对应端点；而运行态 `plate_well` 尚无写入方（设计稿 G2；设计态另存 `plate_well_design`），
 * 把报告硬塞进 run_artifact 只会造一条假 run。所以这一版只做纯前端只读渲染，
 * 不写库、不假装联机。等 M1 接通孔级血缘后，把 loadFile 换成 API 取数即可，
 * 页面其余部分不用动。
 */
interface DiffRow {
  plate: string;
  well: string;
  sim_ul: number | null;
  twin_ul: number | null;
  delta_ul: number | null;
  result: string;
}
interface Comparison {
  status: string;
  summary: Record<string, number>;
  rows: DiffRow[];
  note?: string;
  tolerance_ul?: number;
}
interface PhysFinding { code: string; message: string; step: number }
interface Preflight {
  status: string;
  allow: boolean;
  blocking: PhysFinding[];
  warnings: PhysFinding[];
  projected_wells: Record<string, number>;
  semantics?: string;
}
interface DiffReport {
  mode: string;
  run_id: string;
  job_id?: string;
  device_id?: string;
  status: string;
  job_status: string;
  twin_source?: { kind?: string; path?: string; sql?: string; barcodes?: string[] };
  simulator?: { pylabrobot?: string };
  ledger_applied_commands?: number;
  preflight?: Preflight;
  comparisons: { ledger: Comparison; twin: Comparison };
}
interface ReportSummary {
  id: number;
  reportUid: string;
  jobId?: string;
  deviceId?: string;
  sourceKind: string;
  status: string;
  jobStatus: string;
  createdAt: string;
}
interface ReportList { total: number; items: ReportSummary[] }
interface ReportDetail {
  id: number;
  reportUid: string;
  jobId?: string;
  deviceId?: string;
  sourceKind: string;
  sourceDetail?: string;
  status: string;
  jobStatus: string;
  createdBy?: string;
  createdAt: string;
  report: DiffReport;
}

const VERDICT_KEY: Record<string, MessageKey> = {
  PASS: 'twinDiff.verdict.PASS',
  FAIL: 'twinDiff.verdict.FAIL',
  NO_DATA: 'twinDiff.verdict.NO_DATA',
  SKIP: 'twinDiff.verdict.SKIP',
  BLOCK: 'twinDiff.verdict.BLOCK',
  WARN: 'twinDiff.verdict.WARN',
};
const RESULT_KEY: Record<string, MessageKey> = {
  match: 'twinDiff.result.match',
  mismatch: 'twinDiff.result.mismatch',
  missing_in_sim: 'twinDiff.result.missingInSim',
  missing_in_twin: 'twinDiff.result.missingInTwin',
};
const SOURCE_KEY: Record<string, MessageKey> = {
  ledger: 'twinDiff.source.ledger',
  db: 'twinDiff.source.db',
};
const RESULT_COLOR: Record<string, string> = {
  match: 'green', mismatch: 'red', missing_in_sim: 'orange', missing_in_twin: 'orange',
};
const VERDICT_COLOR: Record<string, string> = {
  PASS: 'green', FAIL: 'red', NO_DATA: 'orange', SKIP: 'default',
  BLOCK: 'red', WARN: 'orange',
};
const SUMMARY_KEYS: Array<[string, MessageKey]> = [
  ['match', 'twinDiff.summary.match'],
  ['mismatch', 'twinDiff.summary.mismatch'],
  ['missing_in_sim', 'twinDiff.summary.missingInSim'],
  ['missing_in_twin', 'twinDiff.summary.missingInTwin'],
];

function fmtVolume(value: number | null | undefined): string {
  return value == null ? '—' : String(value);
}

export default function TwinDiffPage() {
  const t = useT();
  const [selectedId, setSelectedId] = useState<number | null>(null);

  const listQuery = useQuery({
    queryKey: ['twin-diff', 'list'],
    queryFn: () => apiFetch('/api/twin/diffs?limit=50').then((r) => r.json()) as Promise<ReportList>,
    refetchInterval: 10000,
  });
  const items = listQuery.data?.items ?? [];
  const detailQuery = useQuery({
    queryKey: ['twin-diff', 'detail', selectedId],
    queryFn: () => apiFetch(`/api/twin/diffs/${selectedId}`)
      .then((r) => r.json()) as Promise<ReportDetail>,
    enabled: selectedId != null,
  });
  const detail = detailQuery.data;

  const columns: ColumnsType<DiffRow> = [
    { title: t('twinDiff.col.plate'), dataIndex: 'plate', width: 150 },
    { title: t('twinDiff.col.well'), dataIndex: 'well', width: 90 },
    { title: t('twinDiff.col.sim'), dataIndex: 'sim_ul', width: 110, render: fmtVolume },
    { title: t('twinDiff.col.twin'), dataIndex: 'twin_ul', width: 110, render: fmtVolume },
    { title: t('twinDiff.col.delta'), dataIndex: 'delta_ul', width: 110, render: fmtVolume },
    {
      title: t('twinDiff.col.result'), dataIndex: 'result', width: 130,
      render: (value: string) => (
        <Tag color={RESULT_COLOR[value] ?? 'default'}>
          {t(RESULT_KEY[value] ?? 'twinDiff.result.unknown')}
        </Tag>
      ),
    },
  ];

  const renderComparison = (name: 'ledger' | 'twin', cmp: Comparison) => {
    const summary = SUMMARY_KEYS
      .map(([key, keyOf]) => `${t(keyOf)} ${cmp.summary?.[key] ?? 0}`)
      .join(' · ');
    return (
      <Card
        key={name}
        size="small"
        style={{ marginTop: 12 }}
        title={t(name === 'ledger' ? 'twinDiff.ledger.title' : 'twinDiff.twin.title')}
        extra={<Tag color={VERDICT_COLOR[cmp.status] ?? 'default'}>
          {t(VERDICT_KEY[cmp.status] ?? 'twinDiff.verdict.unknown')}
        </Tag>}
      >
        <Typography.Text type="secondary">{summary}</Typography.Text>
        {cmp.note && (
          <Alert type={cmp.status === 'NO_DATA' ? 'warning' : 'info'} showIcon
                 message={cmp.note} style={{ marginTop: 8 }} />
        )}
        {cmp.rows?.length ? (
          <Table<DiffRow> rowKey={(row) => `${row.plate}/${row.well}`}
                          columns={columns} dataSource={cmp.rows}
                          pagination={false} size="small" style={{ marginTop: 8 }} />
        ) : (
          <Empty description={t('twinDiff.empty.rows')} image={Empty.PRESENTED_IMAGE_SIMPLE} />
        )}
      </Card>
    );
  };

  const report = detail?.report;
  const sourceKind = detail?.sourceKind ?? '';
  const sourceLabel = sourceKind === 'ledger' || sourceKind === 'db'
    ? t(SOURCE_KEY[sourceKind])
    : (detail?.sourceDetail ?? '—');

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <Alert type="info" showIcon
             message={t('twinDiff.alert.title')}
             description={t('twinDiff.alert.desc')} />

      <Card size="small" title={t('twinDiff.upload.title')}
            extra={<Typography.Text type="secondary">
              {t('twinDiff.list.count', { n: items.length })}
            </Typography.Text>}>
        {listQuery.isError ? (
          <Alert type="error" showIcon message={t('twinDiff.list.error')} />
        ) : items.length === 0 ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('twinDiff.empty.report')} />
        ) : (
          <Select
            style={{ width: 420 }}
            value={selectedId}
            placeholder={t('twinDiff.list.placeholder')}
            onChange={setSelectedId}
            options={items.map((item) => ({
              value: item.id,
              label: `${item.reportUid} · ${item.jobId ?? '—'} · ${item.createdAt}`,
            }))}
          />
        )}
      </Card>

      {detailQuery.isError && (
        <Alert type="error" showIcon message={t('twinDiff.list.error')} />
      )}
      {!report ? (
        <Card size="small"><Empty description={t('twinDiff.empty.detail')} /></Card>
      ) : (
        <>
          <Card size="small" title={t('twinDiff.meta.title')}
                extra={<Tag color={VERDICT_COLOR[detail.status] ?? 'default'}>
                  {t(VERDICT_KEY[detail.status] ?? 'twinDiff.verdict.unknown')}
                </Tag>}>
            <Descriptions bordered size="small" column={2}>
              <Descriptions.Item label={t('twinDiff.meta.runId')}>{report.run_id}</Descriptions.Item>
              <Descriptions.Item label={t('twinDiff.meta.jobId')}>{report.job_id ?? '—'}</Descriptions.Item>
              <Descriptions.Item label={t('twinDiff.meta.device')}>{report.device_id ?? '—'}</Descriptions.Item>
              <Descriptions.Item label={t('twinDiff.meta.jobStatus')}>
                {t(report.job_status === 'completed'
                  ? 'twinDiff.job.completed' : 'twinDiff.job.failed')}
              </Descriptions.Item>
              <Descriptions.Item label={t('twinDiff.meta.source')}>{sourceLabel}</Descriptions.Item>
              <Descriptions.Item label={t('twinDiff.meta.applied')}>
                {report.ledger_applied_commands ?? 0}
              </Descriptions.Item>
              <Descriptions.Item label={t('twinDiff.meta.pylabrobot')}>
                {report.simulator?.pylabrobot ?? '—'}
              </Descriptions.Item>
              <Descriptions.Item label={t('twinDiff.meta.createdBy')}>
                {detail.createdBy ?? '—'}
              </Descriptions.Item>
            </Descriptions>
          </Card>
          {report.preflight && (
            <Card size="small" title={t('twinDiff.preflight.title')}
                  extra={<Tag color={VERDICT_COLOR[report.preflight.status] ?? 'default'}>
                    {t(VERDICT_KEY[report.preflight.status] ?? 'twinDiff.verdict.unknown')}
                  </Tag>}>
              <Typography.Text type="secondary">{t('twinDiff.preflight.hint')}</Typography.Text>
              {report.preflight.blocking.map((f, i) => (
                <Alert key={'b' + i} type="error" showIcon style={{ marginTop: 8 }}
                       message={`${f.code} · ${t('twinDiff.preflight.step', { n: f.step })}`}
                       description={f.message} />
              ))}
              {report.preflight.warnings.map((f, i) => (
                <Alert key={'w' + i} type="warning" showIcon style={{ marginTop: 8 }}
                       message={`${f.code} · ${t('twinDiff.preflight.step', { n: f.step })}`}
                       description={f.message} />
              ))}
              {report.preflight.blocking.length === 0 && report.preflight.warnings.length === 0 && (
                <Alert type="success" showIcon style={{ marginTop: 8 }}
                       message={t('twinDiff.preflight.clean')} />
              )}
            </Card>
          )}
          {renderComparison('ledger', report.comparisons.ledger)}
          {renderComparison('twin', report.comparisons.twin)}
        </>
      )}
    </Space>
  );
}
