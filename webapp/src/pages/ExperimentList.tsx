import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Card, Input, Modal, Select, Space, Table, Tag, Tooltip, Typography, message } from 'antd';
import { api, apiFetch, type ExperimentEtaItem } from '../api/client';
import { formatWait } from '../utils/format';
import { statusMeta, useStatusVocabulary } from '../utils/status';
import { useReadOnly } from '../auth';
import { useT } from '../i18n';

interface ExpRow {
  id: number;
  experimentNo: string;
  name: string;
  status: string;
  version?: number;
  remark?: string | null;
  islandCode?: string | null;
  islandName?: string | null;
  /** N-10 显式绑定 / 跨 Workcell 的岛名列表（顿号分隔） */
  workcellNames?: string | null;
  flowCount?: number;
  plannedAt?: string | null;
  startedAt?: string | null;
  finishedAt?: string | null;
  workflowName?: string | null;
}

/**
 * 实验列表：**先看到有哪些实验，再进某个实验的编排页**。
 *
 * <p>为什么要有这一页：原来 `/experiments` 直接是编排页，靠一个下拉选实验 ——
 * 那个下拉分不清"岛 / 内容 / 版本"，也没法一眼看全。列表页把这三样摊开，
 * 点行进详情（`/experiments/:id`），详情页仍是原来的编排界面。
 */
export default function ExperimentListPage() {
  const t = useT();
  const nav = useNavigate();
  const qc = useQueryClient();
  // 实验状态的文案/颜色统一从后端词表来（接口没上线时用内置兜底）
  const vocab = useStatusVocabulary();
  // viewer 只能看列表，不能新建实验（后端会 403，这里提前禁用并说明）
  const ro = useReadOnly();
  const [kw, setKw] = useState('');
  const [creating, setCreating] = useState(false);
  const [newName, setNewName] = useState('');
  const [newIsland, setNewIsland] = useState<number>();
  const [busy, setBusy] = useState(false);

  const exps = useQuery({
    queryKey: ['experiments'],
    queryFn: () => apiFetch('/api/experiments?limit=300').then((r) => r.json()) as Promise<{
      items: ExpRow[];
    }>,
  });

  const islands = useQuery({
    queryKey: ['islands-for-exp'],
    queryFn: () => apiFetch('/api/topology').then((r) => r.json()) as Promise<{
      islands: Array<{ id: number; code: string; name: string }>;
    }>,
  });

  /**
   * 预计等待（设计 §7.2）。
   *
   * <p>为什么不并进实验列表接口：列表是库里的静态数据，估算是调度器内存态算出来的，
   * 两者生命周期不同；合成一个接口会让"实验列表打不开"被调度器拖慢。单独查、按
   * experimentId 合并即可。
   *
   * <p>5 秒刷新：排队位次是秒级变化的，但展示粒度是"分"，刷太勤只是徒增请求；
   * 太慢又会让值班盯着一个已经过期的"第 2 位"。
   */
  const eta = useQuery({
    queryKey: ['experiment-eta'],
    queryFn: () => api.experimentEta(300),
    refetchInterval: 5000,
  });
  const etaById = useMemo(() => {
    const m = new Map<number, ExperimentEtaItem>();
    for (const it of eta.data?.items ?? []) m.set(it.experimentId, it);
    return m;
  }, [eta.data]);

  const rows = useMemo(() => {
    const all = exps.data?.items ?? [];
    const q = kw.trim().toLowerCase();
    if (!q) return all;
    return all.filter((e) =>
      [e.experimentNo, e.name, e.islandCode, e.islandName, e.remark]
        .filter(Boolean).join(' ').toLowerCase().includes(q));
  }, [exps.data, kw]);

  const create = async () => {
    if (!newName.trim()) {
      message.warning(t('experimentList.warn.nameRequired'));
      return;
    }
    setBusy(true);
    try {
      const r = await apiFetch('/api/experiments', {
        method: 'POST',
        body: JSON.stringify({ name: newName.trim(), islandId: newIsland }),
      });
      const j = await r.json();
      if (!r.ok || !j.id) {
        message.error(j.error ?? t('experimentList.error.createFailed'));
        return;
      }
      message.success(t('experimentList.toast.created', { no: j.experimentNo ?? `#${j.id}` }));
      setCreating(false);
      setNewName('');
      qc.invalidateQueries({ queryKey: ['experiments'] });
      nav(`/experiments/${j.id}`);   // 建完直接进详情编排
    } finally {
      setBusy(false);
    }
  };

  if (exps.error) {
    return <Alert type="error" showIcon message={t('experimentList.error.load')}
      description={String((exps.error as Error).message)} />;
  }

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <Alert
        type="info" showIcon
        message={t('experimentList.alert.title')}
        description={t('experimentList.alert.desc')}
      />
      <Card
        size="small"
        title={t('experimentList.title', { n: rows.length })}
        extra={
          <Space>
            <Input.Search
              allowClear placeholder={t('experimentList.placeholder.search')} style={{ width: 260 }}
              onChange={(e) => setKw(e.target.value)}
            />
            <Button type="primary" {...ro} onClick={() => setCreating(true)}>{t('experimentList.action.create')}</Button>
          </Space>
        }
      >
        <Table<ExpRow>
          rowKey="id"
          size="small"
          loading={exps.isLoading}
          dataSource={rows}
          pagination={{ pageSize: 20, showSizeChanger: false }}
          onRow={(r) => ({
            onClick: () => nav(`/experiments/${r.id}`),
            style: { cursor: 'pointer' },
          })}
          columns={[
            {
              title: t('experimentList.col.no'), dataIndex: 'experimentNo', width: 200,
              render: (v: string, r) => (
                <Space size={4}>
                  <Typography.Text code>{v}</Typography.Text>
                  {(r.version ?? 1) > 1 && (
                    <Tooltip title={t('experimentList.tooltip.version')}>
                      <Tag color="geekblue">v{r.version}</Tag>
                    </Tooltip>
                  )}
                </Space>
              ),
            },
            { title: t('experimentList.col.name'), dataIndex: 'name', ellipsis: true },
            {
              title: t('experimentList.col.island'), dataIndex: 'islandName', width: 180,
              render: (_, r) => {
                // N-09 / ADR-ISLAND-005：只显示岛名，不显示 island_id；跨 Workcell 显示涉及岛名列表
                const label = r.workcellNames || r.islandName || r.islandCode;
                return label
                  ? <Tooltip title={r.islandCode ? `编码：${r.islandCode}` : ''}><Tag color="blue">{label}</Tag></Tooltip>
                  : <Typography.Text type="secondary">{t('experimentList.col.islandUnset')}</Typography.Text>;
              },
            },
            {
              title: t('experimentList.col.flowCount'), dataIndex: 'flowCount', width: 90,
              render: (v: number) => (v > 0
                ? <Tag>{t('experimentList.col.flowCountTag', { n: v })}</Tag>
                : <Typography.Text type="warning">0</Typography.Text>),
            },
            {
              title: t('experimentList.col.status'), dataIndex: 'status', width: 100,
              render: (v: string) => {
                const m = statusMeta('experiment', v, vocab);
                return <Tag color={m.color}>{m.label}</Tag>;
              },
            },
            {
              title: t('experimentList.col.eta'), width: 170,
              render: (_, r) => {
                const e = etaById.get(r.id);
                // 没有 ETA 数据（调度器接口未就绪 / 这个实验不参与调度）就显示「—」。
                // 不能编一个 0 或"— 分钟"：值班会拿它判断要不要人工插队。
                if (!e) return <Typography.Text type="secondary">—</Typography.Text>;
                // 正在跑的不需要等待；用 ETA 里的状态而不是行 status，
                // 因为行 status 来自库（可能还没回写），ETA 来自调度器实时判定。
                if (e.status === 'running') return <Tag color="default">{t('experimentList.tag.running')}</Tag>;
                if (!e.queued || e.estimatedWaitSec == null) {
                  return <Typography.Text type="secondary">—</Typography.Text>;
                }
                const pos = e.queuePosition != null ? t('experimentList.queuePosition', { n: e.queuePosition }) : '';
                const tip = [
                  e.reason,
                  e.estimateSec != null
                    ? t('experimentList.estimate.hint', { wait: formatWait(e.estimateSec) })
                    : null,
                ].filter(Boolean).join('\n');
                const text = `${formatWait(e.estimatedWaitSec)}${pos}`;
                const node = (
                  <Typography.Text style={{ cursor: 'help' }}>{text}</Typography.Text>
                );
                // tooltip 放 reason + 单次耗时：回答"为什么是这么久"，
                // 只看一个总数值班员没法判断该不该干预。
                return tip
                  ? <Tooltip title={<span style={{ whiteSpace: 'pre-line' }}>{tip}</span>}>{node}</Tooltip>
                  : node;
              },
            },
            {
              title: t('experimentList.col.plannedAt'), dataIndex: 'plannedAt', width: 170,
              render: (v: string | null) => (v ? v.replace('T', ' ').slice(0, 16) : '—'),
            },
            {
              title: t('experimentList.col.remark'), dataIndex: 'remark', ellipsis: true,
              render: (v: string | null) => v || '—',
            },
          ]}
        />
      </Card>

      <Modal
        open={creating}
        title={t('experimentList.modal.createTitle')}
        okText={t('experimentList.modal.createOk')}
        confirmLoading={busy}
        onOk={create}
        onCancel={() => setCreating(false)}
      >
        <Space direction="vertical" size={10} style={{ width: '100%' }}>
          <Alert
            type="info" showIcon
            message={t('experimentList.modal.flowOptionalTitle')}
            description={t('experimentList.modal.flowOptionalDesc')}
          />
          <Input
            placeholder={t('experimentList.placeholder.name')}
            value={newName}
            onChange={(e) => setNewName(e.target.value)}
            onPressEnter={create}
          />
          <Select
            style={{ width: '100%' }} allowClear placeholder={t('experimentList.placeholder.island')}
            value={newIsland}
            onChange={(v) => setNewIsland(v)}
            options={(islands.data?.islands ?? []).map((i) => ({
              value: i.id, label: `${i.code} · ${i.name}`,
            }))}
          />
        </Space>
      </Modal>
    </Space>
  );
}
