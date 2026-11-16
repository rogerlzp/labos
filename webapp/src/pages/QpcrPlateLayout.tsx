import { useMemo, useState } from 'react';
import {
  Alert, Badge, Button, Card, Col, Divider, Empty, Input, InputNumber,
  Row, Select, Space, Table, Tag, Tooltip, Typography, message,
} from 'antd';
import {
  CheckCircleOutlined, CloudUploadOutlined, DatabaseOutlined, DeleteOutlined,
  DownloadOutlined, ExperimentOutlined, PlusOutlined, ReloadOutlined,
  WarningOutlined, CloseCircleOutlined,
} from '@ant-design/icons';
import { api } from '../api/client';
import {
  DEFAULT_PARAMS, DEFAULT_TARGETS, DYE_COLORS, dyeColor,
  downloadText, generateLayout, rowLabel, toTraceCsv, toWorklist,
  type QpcrLayoutParams, type QpcrTarget, type TransferRow, type WellCell,
} from '../lib/qpcrLayout';
import { useT } from '../i18n';
import type { MessageKey } from '../i18n/core';

const DYE_OPTIONS = Object.keys(DYE_COLORS).map((d) => ({ label: d, value: d }))
  .concat([{ label: 'CUSTOM', value: 'CUSTOM' }]);

/** 单块孔的 tooltip：来源孔 + 靶点 + 体积 */
function wellTip(cell: WellCell, t: (k: MessageKey, vars?: Record<string, string | number>) => string): string {
  if (!cell.targetName) return `${cell.well} · ${t('qpcr.well.empty')}`;
  return [
    `${cell.well}  (${t('qpcr.well.zone')}${cell.zone + 1})`,
    `${t('qpcr.col.target')}: ${cell.targetName}`,
    `${t('qpcr.col.dye')}: ${cell.dye}`,
    `${t('qpcr.col.sourceWell')}: ${cell.sourceWell}`,
    `${t('qpcr.col.replicate')}: ${cell.replicate}`,
    `MasterMix ${cell.masterMixVol} µL + ${t('qpcr.col.sampleVol')} ${cell.sampleVol} µL`,
  ].join('\n');
}

function Plate96({ params }: { params: QpcrLayoutParams }) {
  const t = useT();
  const taken = new Set(params.targets.length && params.sampleCount > 0
    ? Array.from({ length: Math.min(params.sampleCount, 96) }, (_, i) => i)
    : []);
  return (
    <div style={{ display: 'inline-block', padding: 8, background: '#161b22', borderRadius: 6 }}>
      <div style={{ display: 'grid', gridTemplateColumns: `20px repeat(12, 26px)`, gap: 2 }}>
        <div />
        {Array.from({ length: 12 }, (_, c) => (
          <div key={`h${c}`} style={{ fontSize: 10, color: '#8b949e', textAlign: 'center' }}>{c + 1}</div>
        ))}
        {Array.from({ length: 8 }, (_, r) => (
          <div key={`r${r}`} style={{ display: 'contents' }}>
            <div style={{ fontSize: 10, color: '#8b949e', lineHeight: '22px' }}>{rowLabel(r)}</div>
            {Array.from({ length: 12 }, (_, c) => {
              const idx = r * 12 + c;
              return (
                <Tooltip key={c} title={taken.has(idx) ? `${t('qpcr.col.sampleIdx')} ${idx + 1}` : t('qpcr.well.empty')}>
                  <div style={{
                    width: 26, height: 22, borderRadius: 3, fontSize: 9,
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                    background: taken.has(idx) ? '#1971c2' : '#22272e',
                    color: taken.has(idx) ? '#fff' : '#484f58',
                  }}>
                    {taken.has(idx) ? idx + 1 : '·'}
                  </div>
                </Tooltip>
              );
            })}
          </div>
        ))}
      </div>
    </div>
  );
}

function Plate384({ cells, onPick, picked }: {
  cells: WellCell[];
  onPick: (well: string | null) => void;
  picked: string | null;
}) {
  const t = useT();
  const byRow = useMemo(() => {
    const m = new Map<number, WellCell[]>();
    cells.forEach((c) => {
      const arr = m.get(c.row) ?? [];
      arr[c.col] = c;
      m.set(c.row, arr);
    });
    return m;
  }, [cells]);
  return (
    <div style={{ overflowX: 'auto', padding: 8, background: '#161b22', borderRadius: 6 }}>
      <div style={{ display: 'grid', gridTemplateColumns: `22px repeat(24, 27px)`, gap: 2, minWidth: 690 }}>
        <div />
        {Array.from({ length: 24 }, (_, c) => (
          <div key={`h${c}`} style={{ fontSize: 9, color: c < 12 ? '#e8590c' : '#1971c2', textAlign: 'center' }}>
            {c + 1}
          </div>
        ))}
        {Array.from({ length: 16 }, (_, r) => (
          <div key={`r${r}`} style={{ display: 'contents' }}>
            <div style={{ fontSize: 9, color: r < 8 ? '#e8590c' : '#1971c2', lineHeight: '22px' }}>{rowLabel(r)}</div>
            {(byRow.get(r) ?? []).map((cell, c) => {
              const isPicked = picked === cell.well;
              return (
                <Tooltip key={c} title={<pre style={{ margin: 0, fontSize: 11 }}>{wellTip(cell, t)}</pre>}>
                  <div
                    onClick={() => onPick(isPicked ? null : cell.well)}
                    style={{
                      width: 27, height: 22, borderRadius: 3, cursor: 'pointer',
                      display: 'flex', alignItems: 'center', justifyContent: 'center',
                      fontSize: 8, fontWeight: cell.targetName ? 600 : 400,
                      border: isPicked ? '2px solid #fff' : '1px solid transparent',
                      background: cell.targetName ? dyeColor(cell.dye) : (cell.zone === 0 ? '#22272e' : '#1c2128'),
                      color: cell.targetName ? '#fff' : '#484f58',
                      opacity: cell.targetName ? 1 : 0.7,
                    }}
                  >
                    {cell.targetName ? (cell.sampleIdx ?? '') : '·'}
                  </div>
                </Tooltip>
              );
            })}
          </div>
        ))}
      </div>
    </div>
  );
}

export default function QpcrPlateLayoutPage() {
  const t = useT();
  const [params, setParams] = useState<QpcrLayoutParams>({ ...DEFAULT_PARAMS, targets: DEFAULT_TARGETS.map((x) => ({ ...x })) });
  const [picked, setPicked] = useState<string | null>(null);
  const [busy, setBusy] = useState<'save' | 'load' | 'sim' | 'diff' | null>(null);
  const [saveInfo, setSaveInfo] = useState<string | null>(null);
  const [loadInfo, setLoadInfo] = useState<string | null>(null);
  const [simInfo, setSimInfo] = useState<string | null>(null);
  const [diffInfo, setDiffInfo] = useState<string | null>(null);
  const result = useMemo(() => generateLayout(params), [params]);
  const rows = useMemo(() => picked
    ? result.transfers.filter((r) => r.targetWell === picked || r.sourceWell === picked)
    : result.transfers, [result, picked]);

  const patch = (p: Partial<QpcrLayoutParams>) => setParams((prev) => ({ ...prev, ...p }));
  const patchTarget = (key: string, p: Partial<QpcrTarget>) =>
    setParams((prev) => ({ ...prev, targets: prev.targets.map((x) => (x.key === key ? { ...x, ...p } : x)) }));
  const addTarget = () => setParams((prev) => ({
    ...prev,
    targets: [...prev.targets, { key: `t${Date.now()}`, name: `Target${prev.targets.length + 1}`, dye: 'FAM' }],
  }));
  const removeTarget = (key: string) =>
    setParams((prev) => ({ ...prev, targets: prev.targets.filter((x) => x.key !== key) }));

  const doExport = (kind: 'worklist' | 'trace') => {
    if (!result.ok) return;
    const stamp = new Date().toISOString().slice(0, 10).replace(/-/g, '');
    if (kind === 'worklist') {
      downloadText(`labos_qpcr_worklist_${stamp}.gwl`, toWorklist(result));
    } else {
      downloadText(`labos_qpcr_trace_${stamp}.csv`, toTraceCsv(result), 'text/csv;charset=utf-8');
    }
  };

  const buildSaveBody = () => ({
    plateBarcode: params.targetPlateId,
    sourceBarcode: params.sourcePlateId,
    sampleCount: params.sampleCount,
    replicates: params.replicates,
    masterMixVol: params.masterMixVol,
    sampleVol: params.sampleVol,
    targets: params.targets.map((x) => ({ name: x.name, dye: x.dye })),
    wells: result.transfers.map((r) => {
      const col = parseInt(r.targetWell.replace(/[A-Z]/g, ''), 10) - 1;
      const row = r.targetWell.charCodeAt(0) - 65;
      return {
        wellRow: row, wellCol: col, wellLabel: r.targetWell,
        // 口径：plate_well.volume = 本段 worklist 真正转移的样品体积（5 µL），
        // MasterMix(15 µL) 是预分装、不在这段吸分里，单独存 plate.attr.masterMixVol。
        // 这样 i8 仿真报告、.gwl、plate_well 三者体积口径一致，TwinDiff 才对得上。
        volume: r.sampleVol,
        sampleIdx: r.sampleIdx, target: r.targetName, dye: r.dye, replicate: r.replicate,
      };
    }),
  });

  const doSave = async () => {
    if (!result.ok) return;
    setBusy('save');
    try {
      const r = await api.saveQpcrLayout(buildSaveBody());
      setSaveInfo(t('qpcr.db.saved', { n: r.saved, barcode: r.barcode }));
      message.success(t('qpcr.db.saved', { n: r.saved, barcode: r.barcode }));
    } catch (e) {
      message.error(String((e as Error).message));
    } finally { setBusy(null); }
  };

  const doLoad = async () => {
    setBusy('load');
    try {
      const r = await api.loadQpcrLayout(params.targetPlateId);
      const saved = r.wells.length;
      // 用 wellLabel 集合对比当前布局的 targetWell 集合
      const cur = new Set(result.transfers.map((x) => x.targetWell));
      const back = new Set(r.wells.map((x) => x.wellLabel));
      const same = cur.size === back.size && [...cur].every((w) => back.has(w));
      setLoadInfo(t('qpcr.db.loaded', {
        n: saved,
        result: same ? t('qpcr.db.match') : t('qpcr.db.mismatch'),
      }));
      message.info(t('qpcr.db.loaded', {
        n: saved, result: same ? t('qpcr.db.match') : t('qpcr.db.mismatch'),
      }));
    } catch (e) {
      message.error(String((e as Error).message));
    } finally { setBusy(null); }
  };

  const doSimulate = async () => {
    if (!result.ok) return;
    setBusy('sim');
    try {
      const r = await api.simulateQpcr({
        sourceBarcode: params.sourcePlateId,
        targetBarcode: params.targetPlateId,
        sampleCount: params.sampleCount,
        masterMixVol: params.masterMixVol,
        sampleVol: params.sampleVol,
        transfers: result.transfers.map((x) => ({
          sampleIdx: x.sampleIdx, sourceWell: x.sourceWell, targetWell: x.targetWell,
          targetName: x.targetName, dye: x.dye, replicate: x.replicate,
        })),
      });
      if (r.status === 'PASS') {
        const m = t('qpcr.db.simPass', { n: r.summary.match, total: r.summary.total, b: r.batches });
        setSimInfo(m); message.success(m);
      } else {
        const m = r.error ?? t('qpcr.db.simFail', { n: r.summary?.mismatch ?? 0, total: r.summary?.total ?? 0 });
        setSimInfo(m); message.error(m);
      }
    } catch (e) {
      message.error(String((e as Error).message));
    } finally { setBusy(null); }
  };

  const doDiff = async () => {
    if (!result.ok) return;
    setBusy('diff');
    try {
      const r = await api.simulateQpcrCompareDb({
        sourceBarcode: params.sourcePlateId,
        targetBarcode: params.targetPlateId,
        sampleCount: params.sampleCount,
        masterMixVol: params.masterMixVol,
        sampleVol: params.sampleVol,
        transfers: result.transfers.map((x) => ({
          sampleIdx: x.sampleIdx, sourceWell: x.sourceWell, targetWell: x.targetWell,
          targetName: x.targetName, dye: x.dye, replicate: x.replicate,
        })),
      });
      const sum = r.report?.comparisons?.twin?.summary ?? {};
      const m = t('qpcr.db.diffDone', {
        r: r.report?.status ?? 'FAIL',
        n: Object.values(sum).reduce((a, b) => a + b, 0),
        id: r.twinDiffPush?.id ?? '?',
      });
      setDiffInfo(m);
      if (r.report?.status === 'PASS') message.success(m); else message.warning(m);
    } catch (e) {
      message.error(String((e as Error).message));
    } finally { setBusy(null); }
  };

  const levelIcon = (level: string) => level === 'error'
    ? <CloseCircleOutlined color="#cf1322" />
    : level === 'warn' ? <WarningOutlined /> : <CheckCircleOutlined />;

  return (
    <div style={{ padding: 16 }}>
      <Typography.Title level={4} style={{ marginTop: 0 }}>
        {t('qpcr.title')}
        <Typography.Text type="secondary" style={{ marginLeft: 12, fontSize: 13, fontWeight: 400 }}>
          {t('qpcr.hint')}
        </Typography.Text>
      </Typography.Title>

      <Row gutter={16}>
        <Col xs={24} lg={9}>
          <Card size="small" title={t('qpcr.param.title')} style={{ marginBottom: 16 }}>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
              <div>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('qpcr.param.samples')}</Typography.Text>
                <InputNumber min={1} max={96} value={params.sampleCount}
                  onChange={(v) => patch({ sampleCount: v ?? 1 })} style={{ width: '100%', marginTop: 4 }} />
              </div>
              <div>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('qpcr.param.replicates')}</Typography.Text>
                <Select value={params.replicates} onChange={(v) => patch({ replicates: v })} style={{ width: '100%', marginTop: 4 }}
                  options={[{ label: t('qpcr.rep.1'), value: 1 }, { label: t('qpcr.rep.2'), value: 2 }]} />
              </div>
              <div>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('qpcr.param.mastermix')} (µL)</Typography.Text>
                <InputNumber min={1} max={50} value={params.masterMixVol}
                  onChange={(v) => patch({ masterMixVol: v ?? 15 })} style={{ width: '100%', marginTop: 4 }} />
              </div>
              <div>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('qpcr.param.sampleVol')} (µL)</Typography.Text>
                <InputNumber min={1} max={50} value={params.sampleVol}
                  onChange={(v) => patch({ sampleVol: v ?? 5 })} style={{ width: '100%', marginTop: 4 }} />
              </div>
              <div>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('qpcr.param.passiveRef')}</Typography.Text>
                <Input value={params.passiveRef} onChange={(e) => patch({ passiveRef: e.target.value })} style={{ marginTop: 4 }} />
              </div>
              <div>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('qpcr.param.plates')}</Typography.Text>
                <Input value={params.targetPlateId} onChange={(e) => patch({ targetPlateId: e.target.value })} style={{ marginTop: 4 }} />
              </div>
            </div>

            <Divider orientation="left" plain style={{ margin: '14px 0 8px' }}>{t('qpcr.target.title')}</Divider>
            {params.targets.map((tg) => (
              <Space key={tg.key} style={{ display: 'flex', marginBottom: 6 }} align="center">
                <Tag color={dyeColor(tg.dye)} style={{ margin: 0, width: 8, height: 22, padding: 0 }} />
                <Input size="small" value={tg.name} onChange={(e) => patchTarget(tg.key, { name: e.target.value })} style={{ width: 120 }} />
                <Select size="small" value={tg.dye} onChange={(v) => patchTarget(tg.key, { dye: v })}
                  options={DYE_OPTIONS} style={{ width: 100 }} />
                <Button size="small" type="text" danger icon={<DeleteOutlined />}
                  onClick={() => removeTarget(tg.key)} disabled={params.targets.length <= 1} />
              </Space>
            ))}
            <Button size="small" type="dashed" icon={<PlusOutlined />} onClick={addTarget} block>
              {t('qpcr.target.add')}
            </Button>
          </Card>

          <Card size="small" title={t('qpcr.check.title')} style={{ marginBottom: 16 }}>
            <Space direction="vertical" style={{ width: '100%' }} size={6}>
              {result.checks.map((c) => (
                <Alert key={c.code} type={c.level === 'error' ? 'error' : c.level === 'warn' ? 'warning' : 'success'}
                  showIcon icon={levelIcon(c.level)} message={<span style={{ fontSize: 12 }}>{c.text}</span>} />
              ))}
            </Space>
          </Card>

          <Card size="small" title={t('qpcr.export.title')} style={{ marginBottom: 16 }}>
            <Space wrap>
              <Button type="primary" icon={<DownloadOutlined />} disabled={!result.ok}
                onClick={() => doExport('worklist')}>{t('qpcr.export.worklist')}</Button>
              <Button icon={<DownloadOutlined />} disabled={!result.ok}
                onClick={() => doExport('trace')}>{t('qpcr.export.trace')}</Button>
            </Space>
            <Typography.Paragraph type="secondary" style={{ fontSize: 11, margin: '10px 0 0' }}>
              {t('qpcr.export.hint')}
            </Typography.Paragraph>
          </Card>

          <Card size="small" title={t('qpcr.db.title')} extra={<DatabaseOutlined />}>
            <Space wrap>
              <Button icon={<CloudUploadOutlined />} disabled={!result.ok || busy !== null}
                loading={busy === 'save'} onClick={doSave}>{t('qpcr.db.save')}</Button>
              <Button icon={<ReloadOutlined />} disabled={busy !== null}
                loading={busy === 'load'} onClick={doLoad}>{t('qpcr.db.load')}</Button>
              <Button icon={<ExperimentOutlined />} disabled={!result.ok || busy !== null}
                loading={busy === 'sim'} onClick={doSimulate}>{t('qpcr.db.simulate')}</Button>
              <Button icon={<DatabaseOutlined />} disabled={!result.ok || busy !== null}
                loading={busy === 'diff'} onClick={doDiff}>{t('qpcr.db.diff')}</Button>
            </Space>
            {(saveInfo || loadInfo || simInfo || diffInfo) && (
              <div style={{ marginTop: 10 }}>
                {saveInfo && <Alert type="success" showIcon message={<span style={{ fontSize: 12 }}>{saveInfo}</span>} style={{ marginBottom: 6 }} />}
                {loadInfo && <Alert type="info" showIcon message={<span style={{ fontSize: 12 }}>{loadInfo}</span>} style={{ marginBottom: 6 }} />}
                {simInfo && <Alert type="success" showIcon message={<span style={{ fontSize: 12 }}>{simInfo}</span>} style={{ marginBottom: 6 }} />}
                {diffInfo && <Alert type="info" showIcon message={<span style={{ fontSize: 12 }}>{diffInfo}</span>} />}
              </div>
            )}
            <Typography.Paragraph type="secondary" style={{ fontSize: 11, margin: '10px 0 0' }}>
              {t('qpcr.db.hint')}
            </Typography.Paragraph>
          </Card>
        </Col>

        <Col xs={24} lg={15}>
          <Card size="small" title={t('qpcr.plate384.title')} extra={<Space size={12}>
            <Badge color="#e8590c" text={t('qpcr.zone.0')} />
            <Badge color="#1971c2" text={t('qpcr.zone.1')} />
          </Space>} style={{ marginBottom: 16 }}>
            <Plate384 cells={result.plate384} onPick={setPicked} picked={picked} />
            <div style={{ marginTop: 8, fontSize: 11, color: '#8b949e' }}>
              {t('qpcr.plate384.hint')}
            </div>
          </Card>

          <Card size="small" title={t('qpcr.plate96.title')} style={{ marginBottom: 16 }}>
            <Plate96 params={params} />
          </Card>

          <Card size="small" title={t('qpcr.transfer.title')}
            extra={<Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {picked ? `${t('qpcr.filter.by')} ${picked} · ${rows.length} ${t('qpcr.unit.rows')}`
                : `${rows.length} ${t('qpcr.unit.rows')}`}
              {picked && <Button type="link" size="small" onClick={() => setPicked(null)}>{t('qpcr.filter.clear')}</Button>}
            </Typography.Text>}>
            <Table<TransferRow>
              rowKey="seq"
              size="small"
              dataSource={rows}
              pagination={{ pageSize: 10, showSizeChanger: false }}
              scroll={{ x: 900 }}
              columns={[
                { title: '#', dataIndex: 'seq', width: 40 },
                { title: t('qpcr.col.sampleIdx'), dataIndex: 'sampleIdx', width: 60 },
                { title: t('qpcr.col.sourceWell'), dataIndex: 'sourceWell', width: 80,
                  render: (v: string, r: TransferRow) => <Tooltip title={r.sourcePlate}><Tag>{v}</Tag></Tooltip> },
                { title: t('qpcr.col.targetWell'), dataIndex: 'targetWell', width: 80,
                  render: (v: string) => <Tag color="blue">{v}</Tag> },
                { title: t('qpcr.col.target'), dataIndex: 'targetName', width: 90 },
                { title: t('qpcr.col.dye'), dataIndex: 'dye', width: 70,
                  render: (v: string) => <Tag color={dyeColor(v)}>{v}</Tag> },
                { title: t('qpcr.col.replicate'), dataIndex: 'replicate', width: 50 },
                { title: 'MasterMix', dataIndex: 'masterMixVol', width: 80, render: (v: number) => `${v} µL` },
                { title: t('qpcr.col.sampleVol'), dataIndex: 'sampleVol', width: 70, render: (v: number) => `${v} µL` },
                { title: t('qpcr.col.note'), dataIndex: 'note', ellipsis: true },
              ]}
              locale={{ emptyText: <Empty description={t('qpcr.transfer.empty')} /> }}
            />
          </Card>
        </Col>
      </Row>
    </div>
  );
}
