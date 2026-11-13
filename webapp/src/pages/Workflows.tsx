import { useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert, Button, Card, Checkbox, Descriptions, Drawer, Empty, Input, Modal, Select, Space,
  Table, Tag, Tooltip, Typography, message,
} from 'antd';
import dayjs from 'dayjs';
import { apiFetch } from '../api/client';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { api, type WorkflowSummary } from '../api/client';
import { useReadOnly } from '../auth';
import { useT } from '../i18n';
import type { MessageKey, MessageVars } from '../i18n/core';
import { localizedNeutralLabel } from '../bpmn/nodeRoleLabel';
import { statusColor, statusLabel, useStatusVocabulary } from '../utils/status';

/** 每页条数：服务端分页，不再"拉 300 条前端切片"。 */
const PAGE_SIZE = 50;

/**
 * 流程状态码 → 文案/配色。
 *
 * <p>⚠ `workflow.status` 目前没有任何写入方去改它（新建固定 1），所以库里
 * 存量理论上全是「草稿」。这里照实显示，不把 1 偷偷翻译成"已发布" ——
 * 一条流程到底发布没发布，是要靠这张表告诉人的，不能让界面替它撒谎。
 * 语义取值需在 P1 的台账里盘点确认后固化。
 */
const STATUS_META: Record<number, { key: MessageKey; color: string }> = {
  1: { key: 'workflows.status.draft', color: 'default' },
  2: { key: 'workflows.status.published', color: 'green' },
  3: { key: 'workflows.status.disabled', color: 'red' },
};

/**
 * 「能力来源」的内核词 → 现场话。
 *
 * <p>explicit/protocol/heuristic 是 `CapabilitySource.source` 的取值，
 * 工艺工程师不关心"这个能力是怎么判定出来的"，界面只说人话，
 * 原始 code 留在数据里（接口仍返回原值，追溯不受影响）。
 */
const SOURCE_LABEL: Record<string, MessageKey> = {
  explicit: 'workflows.source.explicit',
  protocol: 'workflows.source.protocol',
  'protocol-short': 'workflows.source.protocolShort',
  heuristic: 'workflows.source.heuristic',
  logic: 'workflows.source.logic',
  unknown: 'workflows.source.unknown',
};

/** 分类列：已知内部枚举翻成现场话，未知值原样显示（不猜）。 */
const CATEGORY_LABEL: Record<string, MessageKey> = {
  choreography: 'workflows.category.choreography',
  verify: 'workflows.category.verify',
};

/** 流程库：选一条 → 看预检/节点 → 执行；行点击选中开抽屉，改图走「编辑」。 */
export default function WorkflowsPage() {
  const t = useT();
  const nav = useNavigate();
  // 运行状态文案/颜色：走全站统一词表（不在这儿另起一套颜色）
  const vocab = useStatusVocabulary();
  // 新建/执行/影子检查都是写操作（影子检查也会落 run 记录）：viewer 只读
  const ro = useReadOnly();
  /** 新建流程：原来界面里没有任何创建入口，流程库只能看 */
  const [creating, setCreating] = useState(false);
  const [newName, setNewName] = useState('');
  // 新建时选岛：设计器据此只显示「这个岛」的设备；留空 = 全部岛（兼容旧流程）
  const [newIslandId, setNewIslandId] = useState<number | null>(null);
  const [busyCreate, setBusyCreate] = useState(false);
  const qc = useQueryClient();
  const islandsQ = useQuery({
    queryKey: ['islands-for-create'],
    queryFn: () => apiFetch('/api/topology').then((r) => r.json()) as Promise<{
      islands?: Array<{ id: number; code: string; name?: string }>;
    }>,
  });

  const createWorkflow = async () => {
    if (!newName.trim()) {
      message.warning(t('workflows.warn.nameRequired'));
      return;
    }
    setBusyCreate(true);
    try {
      const r = await apiFetch('/api/workflows', {
        method: 'POST',
        body: JSON.stringify({ name: newName.trim(), islandId: newIslandId }),
      });
      const j = await r.json();
      if (!r.ok) {
        message.error(j.error ?? t('workflows.toast.createFailed'));
        return;
      }
      message.success(t('workflows.toast.created'));
      setCreating(false);
      setNewName('');
      qc.invalidateQueries({ queryKey: ['workflows'] });
      // 把岛带到设计器，否则刷新后又要重选；不传时设计器回落「全部岛」
      const q = newIslandId ? `?islandId=${newIslandId}` : '';
      nav(`/designer/${j.id}${q}`);
      setNewIslandId(null);
    } finally {
      setBusyCreate(false);
    }
  };
  const [sp] = useSearchParams();
  // 搜索框的即时输入 vs 已提交查询：中间隔 300ms，避免每敲一个字打一次接口
  const [kwInput, setKwInput] = useState('');
  const [kw, setKw] = useState('');
  const [page, setPage] = useState(1);
  /** 岛筛选：'' = 全部岛；'none' = 未指定岛（P-01：存量不推断，这档长期非空）。 */
  const [islandFilter, setIslandFilter] = useState('');
  /** 只看空流程（一个 task 节点都没有的壳）。 */
  const [onlyEmpty, setOnlyEmpty] = useState(false);
  // 支持深链：/workflows?wf=239 直接选中某条并展开抽屉
  const [selected, setSelected] = useState<number | null>(() => {
    const v = Number(sp.get('wf'));
    return Number.isFinite(v) && v > 0 ? v : null;
  });
  const [running, setRunning] = useState(false);
  const [shadow, setShadow] = useState(false);
  const [shadowBusy, setShadowBusy] = useState(false);
  /** 预检结果：资源不够时不直接跑，先问用户 */
  const [gate, setGate] = useState<Awaited<ReturnType<typeof api.canStart>> | null>(null);
  const [gateOpen, setGateOpen] = useState(false);
  const [shadowReport, setShadowReport] = useState<{
    workflowName: string; total: number; ok: number; failed: number; contractGaps: number;
    nodes: Array<{ nodeKey: string; name: string; capability: string; method: string;
      deviceCode: string | null; ok: boolean; errorCode: string | null;
      elapsedMs: number; contractGaps: number }>;
  } | null>(null);

  useEffect(() => {
    const id = window.setTimeout(() => {
      setKw(kwInput.trim());
      setPage(1);
    }, 300);
    return () => window.clearTimeout(id);
  }, [kwInput]);

  /**
   * queryKey 用 `['workflows','page',…]`，**不能只用 `['workflows']`** ——
   * 实验页的「选流程」下拉用 `['workflows']` 取 300 条全量，共用同一个 key
   * 会互相覆盖缓存，那边会静默只剩当前一页的流程。
   */
  const list = useQuery({
    queryKey: ['workflows', 'page', page, kw, islandFilter, onlyEmpty],
    queryFn: () => api.workflows({
      limit: PAGE_SIZE,
      offset: (page - 1) * PAGE_SIZE,
      q: kw || undefined,
      islandId: islandFilter || undefined,
      empty: onlyEmpty || undefined,
    }),
  });
  /**
   * 台账统计：统计条用。
   *
   * <p>独立一条接口（`/api/workflows/stats`），不数当前页 —— 统计条回答的是
   * "这个台账整体什么状况"（库里 159 条里有 8 条空流程、128 条没定岛），
   * 按当前页数算会把这个最该看见的事实藏起来。
   */
  const statsQ = useQuery({ queryKey: ['workflow-stats'], queryFn: () => api.workflowStats() });
  const detail = useQuery({
    queryKey: ['workflow', selected],
    queryFn: () => api.workflow(selected!),
    enabled: selected != null,
  });
  const pre = useQuery({
    queryKey: ['preflight', selected],
    queryFn: () => api.preflight(selected!),
    enabled: selected != null,
  });
  /** 最近运行：抽屉里的"这条流程最近跑得怎么样"。 */
  const runsQ = useQuery({
    queryKey: ['workflow-runs', selected],
    queryFn: () => api.workflowRuns(selected!),
    enabled: selected != null,
  });
  /** 图版本历史：每次保存一条，可回滚（回滚端点已接 workflow.restore 审计）。 */
  const historyQ = useQuery({
    queryKey: ['workflow-history', selected],
    queryFn: () => api.workflowHistory(selected!),
    enabled: selected != null,
  });
  // S0-safety：调试通路开关状态（关闭时隐藏 shadow-run 调试入口）
  const switches = useQuery({ queryKey: ['debug-switches'], queryFn: () => api.debugSwitches() });

  /**
   * 回滚到某个历史版本。
   *
   * <p>后端 `POST /workflows/{id}/history/{hid}/restore` 会写 `workflow.restore`
   * 审计 —— 回滚是治理动作，不是"撤销保存"，所以必须留痕。
   */
  const restoreVersion = async (hid: number) => {
    if (selected == null) return;
    try {
      await api.restoreWorkflowHistory(selected, hid);
      message.success(t('workflows.toast.restored'));
      qc.invalidateQueries({ queryKey: ['workflow-history', selected] });
      qc.invalidateQueries({ queryKey: ['workflow', selected] });
      qc.invalidateQueries({ queryKey: ['workflows'] });
    } catch (e) {
      message.error(String((e as Error).message));
    }
  };

  /**
   * 点执行：先做**启动前粗筛**，资源不够时不闷头跑。
   *
   * <p>为什么要有这一步：后端 M3 已经能算"够不够、被谁占、等多久"，
   * 但用户看不到，点了执行还是直接跑，跑到一半卡在抢设备上——
   * 感受就是"点了没反应"。
   */
  async function startFlow(id: number | null) {
    if (id == null) {
      message.warning(t('workflows.warn.selectFirst'));
      return;
    }
    setRunning(true);
    try {
      const g = await api.canStart(id);
      if (g.canStart) {
        await runNow(id);
      } else {
        setGate(g);
        setGateOpen(true);
      }
    } catch (e) {
      // **预检失败不自动执行** —— 预检失败说明"资源状态未知"，
      // 闷头跑可能撞车。让用户自己决定。
      message.error(t('workflows.error.preflight', { msg: String((e as Error).message) }));
    } finally {
      setRunning(false);
    }
  }

  /** 异步启动并跳到执行监控页，让用户能实时看进度、随时暂停。 */
  async function runNow(id: number) {
    const r = await api.execute(id, { mode: 'attended', async: true });
    if (r.runId) {
      message.success(t('workflows.toast.started'));
      nav(`/runs/${r.runId}`);
    } else {
      message.success(t('workflows.toast.completed', { done: r.completed, total: r.total }));
    }
  }

  const total = list.data?.total ?? 0;
  const rows = list.data?.items ?? [];
  /** 台账统计（覆盖全库，不受当前页与搜索词影响）。 */
  const stats = {
    total: statsQ.data?.total ?? 0,
    published: statsQ.data?.published ?? 0,
    draft: statsQ.data?.draft ?? 0,
    noIsland: statsQ.data?.noIsland ?? 0,
    emptyGraph: statsQ.data?.emptyGraph ?? 0,
  };
  /** 当前是否处于筛选态：决定"重置"按钮与页头的筛选标记。 */
  const filtering = Boolean(kw) || Boolean(islandFilter) || onlyEmpty;
  const resetFilters = () => {
    setKwInput(''); setKw(''); setIslandFilter(''); setOnlyEmpty(false); setPage(1);
  };
  const preflightBlocking = pre.data?.blocking === true;

  /** 状态徽章：颜色与文字同时出现（不靠颜色单独承载语义）。 */
  const statusTag = (v: number | undefined) => {
    if (v == null) return <Typography.Text type="secondary">{t('workflows.status.unknown')}</Typography.Text>;
    const m = STATUS_META[v];
    if (!m) return <Tag>{v}</Tag>;
    return <Tag color={m.color}>{t(m.key)}</Tag>;
  };

  const categoryTag = (v: string | null | undefined) => {
    if (!v) return <Typography.Text type="secondary">—</Typography.Text>;
    const k = CATEGORY_LABEL[v];
    return <Tag>{k ? t(k) : v}</Tag>;
  };

  const islandTag = (code: string | null | undefined, name: string | null | undefined) => {
    if (!code) {
      return <Typography.Text type="secondary">{t('workflows.island.unassigned')}</Typography.Text>;
    }
    return (
      <Tooltip title={name ?? code}>
        <Tag color="blue">{code}</Tag>
      </Tooltip>
    );
  };

  const fmtTime = (v: string | null | undefined) => {
    if (!v) return '—';
    const d = dayjs(v);
    return d.isValid() ? d.format('MM-DD HH:mm') : '—';
  };

  const selectedRow = rows.find((r) => r.id === selected) ?? null;
  /**
   * 抽屉里展示的治理信息：优先用详情接口返回的 workflow（深链 `?wf=xxx`
   * 打开时它不在当前页的行里，取不到 selectedRow），行数据只作兜底。
   */
  const wfMeta = detail.data?.workflow ?? selectedRow;

  return (
    <>
      {/* 资源不足弹窗：说清"被谁占、等多久"，让用户自己决定 */}
      <Modal
        open={creating}
        title={t('workflows.modal.createTitle')}
        okText={t('workflows.modal.createOk')}
        confirmLoading={busyCreate}
        onOk={createWorkflow}
        onCancel={() => setCreating(false)}
      >
        <Alert
          type="info" showIcon style={{ marginBottom: 12 }}
          message={t('workflows.modal.emptyGraphTitle')}
          description={t('workflows.modal.emptyGraphDesc')}
        />
        <Input
          placeholder={t('workflows.placeholder.name')}
          value={newName}
          onChange={(e) => setNewName(e.target.value)}
          onPressEnter={createWorkflow}
        />
        <Select
          allowClear
          style={{ width: '100%', marginTop: 10 }}
          placeholder={t('workflows.placeholder.island')}
          value={newIslandId ?? undefined}
          onChange={(v: number | undefined) => setNewIslandId(v ?? null)}
          options={(islandsQ.data?.islands ?? []).map((i) => ({
            value: i.id,
            label: `${i.code}${i.name ? ' · ' + i.name : ''}`,
          }))}
        />
      </Modal>

      <Modal
        title={t('workflows.modal.queueTitle')}
        open={gateOpen}
        onCancel={() => setGateOpen(false)}
        okText={t('workflows.modal.queueOk')}
        cancelText={t('workflows.modal.queueCancel')}
        okButtonProps={{ danger: true }}
        onOk={async () => {
          setGateOpen(false);
          if (selected != null) {
            setRunning(true);
            try {
              await runNow(selected);
            } catch (e) {
              message.error(String((e as Error).message));
            } finally {
              setRunning(false);
            }
          }
        }}
      >
        {gate && (
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            <Alert
              type="warning"
              showIcon
              message={gate.reason ?? t('workflows.modal.queueReasonFallback')}
              description={
                gate.estimatedWaitSeconds != null
                  ? t('workflows.modal.queueWait', { wait: formatWait(gate.estimatedWaitSeconds, t) })
                  : undefined
              }
            />
            {gate.blocking && gate.blocking.length > 0 && (
              <div>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {t('workflows.modal.queueHolding')}
                </Typography.Text>
                <Table
                  size="small"
                  pagination={false}
                  rowKey={(r) => `${r.runId}-${r.deviceInstanceId}`}
                  dataSource={gate.blocking}
                  columns={[
                    { title: t('workflows.col.run'), dataIndex: 'runNo', width: 200 },
                    { title: t('workflows.col.device'), dataIndex: 'deviceCode', width: 120 },
                    {
                      title: t('workflows.col.needed'),
                      width: 100,
                      render: (_, r) => formatWait(r.etaSeconds, t),
                    },
                  ]}
                />
              </div>
            )}
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {t('workflows.modal.queueHint')}
            </Typography.Text>
          </Space>
        )}
      </Modal>

      {/*
        版面三段式：页头 / 统计条 / 工具条 + 表格。
        为什么不再"大卡片套表格"：工业级数据页的密度来自**用列承载信息**，
        每块套一层 Card 只会把 12 行挤成 8 行，还多出两圈边框。
      */}
      <Space direction="vertical" size={10} style={{ width: '100%' }}>
        {/* ① 页头：标题 + 计数 + 主操作 */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <Typography.Title level={4} style={{ margin: 0 }}>
            {t('workflows.title', { n: total })}
          </Typography.Title>
          {filtering && (
            <Tag color="blue">{t('workflows.filter.active', { n: list.data?.total ?? 0 })}</Tag>
          )}
          <div style={{ flex: 1 }} />
          <Button type="primary" {...ro} onClick={() => setCreating(true)}>
            {t('workflows.action.create')}
          </Button>
        </div>

        {/* ② 统计条：台账健康度。带下箭头的格子可点（对应真实筛选），其余是只读信息 */}
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          <Kpi label={t('workflows.kpi.total')} value={stats.total} />
          <Kpi label={t('workflows.kpi.published')} value={stats.published} tone="good" />
          <Kpi label={t('workflows.kpi.draft')} value={stats.draft} tone="warn" />
          <Kpi
            label={t('workflows.kpi.noIsland')} value={stats.noIsland} tone="warn"
            active={islandFilter === 'none'}
            onClick={() => {
              setIslandFilter(islandFilter === 'none' ? '' : 'none'); setPage(1);
            }}
          />
          <Kpi
            label={t('workflows.kpi.emptyGraph')} value={stats.emptyGraph} tone="muted"
            active={onlyEmpty}
            onClick={() => { setOnlyEmpty(!onlyEmpty); setPage(1); }}
          />
        </div>

        {/* ③ 工具条：搜索 + 筛选 + 只读提示 */}
        <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
          <Input.Search
            placeholder={t('workflows.placeholder.search')} allowClear style={{ width: 260 }}
            value={kwInput}
            onChange={(e) => setKwInput(e.target.value)}
          />
          <Select
            allowClear
            style={{ width: 200 }}
            placeholder={t('workflows.filter.allIslands')}
            value={islandFilter || undefined}
            onChange={(v: string | undefined) => { setIslandFilter(v ?? ''); setPage(1); }}
            options={[
              { value: 'none', label: t('workflows.kpi.noIsland') },
              ...(islandsQ.data?.islands ?? []).map((i) => ({
                value: String(i.id),
                label: `${i.code}${i.name ? ' · ' + i.name : ''}`,
              })),
            ]}
          />
          <Checkbox
            checked={onlyEmpty}
            onChange={(e) => { setOnlyEmpty(e.target.checked); setPage(1); }}
          >
            {t('workflows.filter.onlyEmpty')}
          </Checkbox>
          {filtering && (
            <Button size="small" onClick={resetFilters}>{t('workflows.action.resetFilter')}</Button>
          )}
          <div style={{ flex: 1 }} />
          {ro.disabled && <Tag>{t('workflows.readonly.hint')}</Tag>}
        </div>

        <Table<WorkflowSummary>
          rowKey="id" size="small" loading={list.isLoading}
          dataSource={rows}
          // 行点击 = 选中并在右侧抽屉看详情；进设计器走行内「编辑」，
          // 让"看"和"改"两种意图分开（原来点行直接跳走，想看细节得再去点详情）
          onRow={(r) => ({
            onClick: () => setSelected(r.id),
            style: { cursor: 'pointer' },
          })}
          locale={{
            emptyText: (
              <Empty
                description={kw ? t('workflows.empty.noMatch') : t('workflows.empty.title')}
              >
                {!kw && (
                  <Button type="primary" {...ro} onClick={() => setCreating(true)}>
                    {t('workflows.action.create')}
                  </Button>
                )}
              </Empty>
            ),
          }}
          // 服务端分页：total 来自 COUNT 查询，翻到第 N 页也能取到真实数据
          pagination={{
            current: page,
            pageSize: PAGE_SIZE,
            total,
            size: 'small',
            showSizeChanger: false,
            onChange: setPage,
            showTotal: (n) => t('workflows.total', { n }),
          }}
          columns={[
            {
              // 行首信号灯：只用「节点数为 0」这个廉价且确定的事实。
              // 列表级批量预检需要接口支持一次性算 N 条图，本轮不做 ——
              // 单条预检在抽屉里跑，避免列表一开就串行打 50 个请求。
              title: '', width: 4,
              render: (_, r) => {
                const empty = r.nodeCount === 0;
                return (
                  <div style={{
                    width: 4, height: 20, borderRadius: 2,
                    background: empty ? '#8c8c8c' : 'transparent',
                  }} />
                );
              },
            },
            {
              title: t('workflows.col.name'), dataIndex: 'name', ellipsis: true,
              render: (v: string, r) => (
                <Space size={4}>
                  <Typography.Text strong={r.id === selected}>{v}</Typography.Text>
                  {r.nodeCount === 0 && <Tag>{t('workflows.status.emptyGraph')}</Tag>}
                </Space>
              ),
            },
            {
              title: t('workflows.col.code'), dataIndex: 'code', width: 130, ellipsis: true,
              render: (v: string) => (
                <Typography.Text style={{ fontFamily: 'ui-monospace, monospace', fontSize: 12 }}>
                  {v}
                </Typography.Text>
              ),
            },
            {
              title: t('workflows.col.island'), width: 100,
              render: (_, r) => islandTag(r.islandCode, r.islandName),
            },
            {
              title: t('workflows.col.version'), width: 64, align: 'right',
              render: (_, r) => (
                <Tooltip title={t('workflows.version.hint')}>
                  <span style={{ fontFamily: 'ui-monospace, monospace' }}>v{r.version ?? 1}</span>
                </Tooltip>
              ),
            },
            {
              title: t('workflows.col.status'), width: 88,
              render: (_, r) => statusTag(r.status),
            },
            {
              title: t('workflows.col.category'), dataIndex: 'category', width: 100,
              render: (v: string | null) => categoryTag(v),
            },
            {
              title: t('workflows.col.nodes'), dataIndex: 'nodeCount', width: 64, align: 'right',
              render: (v: number) => (
                <span style={{ fontFamily: 'ui-monospace, monospace' }}>{v}</span>
              ),
            },
            {
              title: t('workflows.col.updated'), width: 110,
              render: (_, r) => (
                <Tooltip title={r.updatedAt ?? ''}>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {fmtTime(r.updatedAt)}
                  </Typography.Text>
                </Tooltip>
              ),
            },
            {
              title: t('workflows.col.action'), width: 90,
              render: (_, r) => (
                <Space size={0}>
                  <Button size="small" type="link"
                    onClick={(e) => { e.stopPropagation(); nav(`/designer/${r.id}`); }}>
                    {t('workflows.action.edit')}
                  </Button>
                </Space>
              ),
            },
          ]}
        />
      </Space>

      {/*
        详情改右侧抽屉：原来详情卡追加在列表下方，点一次"详情"要滚一屏，
        而"看"和"改"两个入口语义还打架。抽屉让主从同屏，关掉即回列表。
      */}
      <Drawer
        open={selected != null}
        onClose={() => setSelected(null)}
        width={560}
        destroyOnClose
        title={
          detail.data?.workflow.name
          ?? (detail.isError
            ? t('workflows.detail.loadFailed')
            : t('workflows.detail.loading'))
        }
        extra={(
          <Space>
            <Button onClick={() => selected != null && nav(`/designer/${selected}`)}>
              {t('workflows.action.openDesigner')}
            </Button>
            {switches.data?.shadowRunEnabled && (
              <Button loading={shadowBusy} {...ro} onClick={async () => {
                if (!selected) return;
                setShadowBusy(true);
                setShadowReport(null);
                setShadow(true);
                try {
                  const r = await apiFetch(`/api/workflows/${selected}/shadow-run`, {
                    method: 'POST',
                  });
                  const j = await r.json();
                  if (!r.ok) throw new Error(j.error);
                  setShadowReport(j);
                } catch (e) {
                  message.error(String((e as Error).message));
                  setShadow(false);
                } finally {
                  setShadowBusy(false);
                }
              }}>{t('workflows.action.shadow')}</Button>
            )}
            {/*
              阻塞时不让人点了才发现：按钮直接禁用并说明原因。
              antd 的 disabled Button 不弹 tooltip，所以套一层 span 承载 title。
            */}
            <Tooltip title={
              ro.disabled ? ro.title
                : preflightBlocking ? t('workflows.preflight.blockedHint')
                  : undefined
            }>
              <span>
                <Button
                  type="primary" loading={running}
                  disabled={ro.disabled || preflightBlocking || selected == null}
                  onClick={() => startFlow(selected)}
                >
                  {t('workflows.action.execute')}
                </Button>
              </span>
            </Tooltip>
          </Space>
        )}
      >
        {/*
          预检：从前只有一个「阻塞 1」的红标签，用户没有任何途径知道
          阻塞在哪、为什么。后端 /preflight 一直是带 issues[] 的
          （level/code/nodeKey/message），缺的只是把它渲染出来。
        */}
        <Typography.Paragraph style={{ fontSize: 12 }}>
          {t('workflows.preflight.label')}
          {pre.data
            ? (pre.data.blocking
              ? <Tag color="red">{t('workflows.preflight.blocking', { n: pre.data.errors })}</Tag>
              : pre.data.warnings > 0
                ? <Tag color="orange">{t('workflows.preflight.warnings', { n: pre.data.warnings })}</Tag>
                : <Tag color="green">{t('workflows.preflight.pass')}</Tag>)
            : <Tag>—</Tag>}
          {pre.data && (
            <Typography.Text type="secondary">
              {' '}{t('workflows.preflight.tasks', {
                count: pre.data.taskCount, reachable: pre.data.reachableCount,
              })}
            </Typography.Text>
          )}
        </Typography.Paragraph>

        {pre.data && pre.data.issues.length > 0 && (
          <Card
            size="small" style={{ marginBottom: 12 }}
            title={t('workflows.preflight.issueTitle', { n: pre.data.issues.length })}
            styles={{ body: { padding: 0 } }}
          >
            <Table
              size="small" pagination={false}
              rowKey={(r, i) => `${r.code}-${r.nodeKey ?? ''}-${i}`}
              dataSource={pre.data.issues}
              columns={[
                {
                  title: t('workflows.col.level'), dataIndex: 'level', width: 76,
                  render: (v: string) => (
                    <Tag color={v === 'ERROR' ? 'red' : v === 'WARN' ? 'orange' : 'default'}>{v}</Tag>
                  ),
                },
                {
                  title: t('workflows.col.node'), dataIndex: 'nodeKey', width: 110, ellipsis: true,
                  render: (v: string | null) => v ?? '—',
                },
                { title: t('workflows.col.reason'), dataIndex: 'message' },
              ]}
            />
          </Card>
        )}

        <Descriptions size="small" column={2} style={{ marginBottom: 12 }}>
          <Descriptions.Item label={t('workflows.field.code')}>
            {wfMeta?.code}
          </Descriptions.Item>
          <Descriptions.Item label={t('workflows.field.island')}>
            {islandTag(wfMeta?.islandCode, wfMeta?.islandName)}
          </Descriptions.Item>
          <Descriptions.Item label={t('workflows.field.version')}>
            v{wfMeta?.version ?? 1}
          </Descriptions.Item>
          <Descriptions.Item label={t('workflows.field.status')}>
            {statusTag(wfMeta?.status)}
          </Descriptions.Item>
          <Descriptions.Item label={t('workflows.field.nodes')}>
            {detail.data?.tasks.length ?? wfMeta?.nodeCount}
          </Descriptions.Item>
          <Descriptions.Item label={t('workflows.field.edges')}>
            {detail.data?.edgeCount}
          </Descriptions.Item>
          <Descriptions.Item label={t('workflows.field.updatedAt')} span={2}>
            {wfMeta?.updatedAt
              ? dayjs(wfMeta.updatedAt).format('YYYY-MM-DD HH:mm:ss')
              : '—'}
          </Descriptions.Item>
        </Descriptions>

        {/* 节点构成：能力显示中文业务名（capability.name），code 收进技术细节 */}
        <Table
          size="small" pagination={false}
          dataSource={detail.data?.tasks ?? []}
          // 不用 index 做 key（antd 5 已废弃）：用节点自身的 id，稳定且不重复
          rowKey={(r) => String(r.id ?? r.name)}
          columns={[
            { title: t('workflows.col.step'), dataIndex: 'name', width: 96,
              ellipsis: true,
              render: (v: string) => localizedNeutralLabel(v) ?? v },
            {
              title: t('workflows.col.capability'), dataIndex: 'capability', width: 128,
              render: (v: string, r) => (
                <Space size={4}>
                  <span>{r.capabilityName ?? v}</span>
                  {r.capabilityName && r.capabilityName !== v && (
                    <Typography.Text type="secondary" style={{ fontSize: 11 }}>{v}</Typography.Text>
                  )}
                </Space>
              ),
            },
            {
              // 「来源」是内核词（explicit/protocol/…），界面上说人话
              title: t('workflows.col.source'), dataIndex: 'source', width: 96,
              render: (v: string) => (
                <Tag color={v === 'explicit' ? 'green' : 'default'}>
                  {t(SOURCE_LABEL[v] ?? 'workflows.source.unknown')}
                </Tag>
              ),
            },
            { title: t('workflows.col.driverMethod'), dataIndex: 'method', width: 150, ellipsis: true },
          ]}
        />

        {/*
          最近运行：抽屉里回答"这条流程最近跑得怎么样" ——
          列表上不给（要 N 次请求），选中一条再查就有意义了。
        */}
        <Card
          size="small"
          style={{ marginTop: 12 }}
          title={t('workflows.runs.title')}
          styles={{ body: { padding: 0 } }}
        >
          {runsQ.data && runsQ.data.items.length > 0 ? (
            <Table
              size="small" pagination={false} rowKey="id"
              dataSource={runsQ.data.items}
              columns={[
                {
                  title: t('workflows.runs.runNo'), dataIndex: 'runNo', width: 190, ellipsis: true,
                  render: (v: string) => (
                    <Typography.Text style={{ fontFamily: 'ui-monospace, monospace', fontSize: 12 }}>
                      {v}
                    </Typography.Text>
                  ),
                },
                {
                  title: t('workflows.runs.status'), dataIndex: 'status', width: 84,
                  render: (v: string) => (
                    <Tag color={statusColor('run', v, vocab)}>{statusLabel('run', v, vocab)}</Tag>
                  ),
                },
                {
                  title: t('workflows.runs.progress'), width: 96, align: 'right',
                  render: (_, r: { done: number; total: number }) => (
                    <span style={{ fontFamily: 'ui-monospace, monospace' }}>{r.done}/{r.total}</span>
                  ),
                },
                {
                  title: t('workflows.runs.duration'), dataIndex: 'durationSec', width: 80,
                  align: 'right',
                  render: (v: number | null) => (
                    <span style={{ fontFamily: 'ui-monospace, monospace' }}>
                      {v == null ? '—' : `${v}s`}
                    </span>
                  ),
                },
                {
                  title: t('workflows.runs.startedAt'), dataIndex: 'startedAt', width: 104,
                  render: (v: string | null) => fmtTime(v),
                },
              ]}
            />
          ) : (
            <Typography.Paragraph
              type="secondary" style={{ fontSize: 12, padding: 12, margin: 0 }}
            >
              {t('workflows.runs.empty')}
            </Typography.Paragraph>
          )}
        </Card>

        {/*
          版本历史：每次保存留一条图快照，可回滚。
          `workflow_graph_history` 表 + `/history` + `/restore`（已接审计）早就有了，
          缺的是把它端到端呈现在页面上 —— 流程是资产，资产要能回退。
        */}
        <Card
          size="small"
          style={{ marginTop: 12 }}
          title={t('workflows.history.title')}
          styles={{ body: { padding: 0 } }}
        >
          {historyQ.data && historyQ.data.items.length > 0 ? (
            <Table
              size="small" pagination={false} rowKey="id"
              dataSource={historyQ.data.items}
              columns={[
                {
                  title: t('workflows.history.savedAt'), dataIndex: 'savedAt', width: 140,
                  render: (v: string) => fmtTime(v),
                },
                {
                  title: t('workflows.history.nodes'), dataIndex: 'nodeCount', width: 64,
                  align: 'right',
                  render: (v: number) => (
                    <span style={{ fontFamily: 'ui-monospace, monospace' }}>{v}</span>
                  ),
                },
                {
                  title: t('workflows.history.edges'), dataIndex: 'edgeCount', width: 64,
                  align: 'right',
                  render: (v: number) => (
                    <span style={{ fontFamily: 'ui-monospace, monospace' }}>{v}</span>
                  ),
                },
                {
                  title: t('workflows.col.action'), width: 90,
                  render: (_, r: { id: number }) => (
                    <Button size="small" type="link" {...ro} onClick={() => restoreVersion(r.id)}>
                      {t('workflows.action.restore')}
                    </Button>
                  ),
                },
              ]}
            />
          ) : (
            <Typography.Paragraph
              type="secondary" style={{ fontSize: 12, padding: 12, margin: 0 }}
            >
              {t('workflows.history.empty')}
            </Typography.Paragraph>
          )}
        </Card>
      </Drawer>

      <Modal
        open={shadow}
        width={900}
        title={t('workflows.shadow.title', { name: shadowReport?.workflowName ?? t('workflows.shadow.running') })}
        onCancel={() => setShadow(false)}
        footer={<Button onClick={() => setShadow(false)}>{t('workflows.action.close')}</Button>}
      >
        {shadowBusy && (
          <Alert type="info" showIcon message={t('workflows.shadow.progress')}
            description={t('workflows.shadow.desc')} />
        )}
        {shadowReport && (
          <>
            <Space size={24} style={{ marginBottom: 12 }}>
              <Typography.Text>{t('workflows.shadow.stat.nodes')} <b>{shadowReport.total}</b></Typography.Text>
              <Typography.Text type="success">{t('workflows.shadow.stat.ok')} <b>{shadowReport.ok}</b></Typography.Text>
              <Typography.Text type={shadowReport.failed ? 'danger' : 'secondary'}>
                {t('workflows.shadow.stat.failed')} <b>{shadowReport.failed}</b>
              </Typography.Text>
              <Typography.Text type={shadowReport.contractGaps ? 'warning' : 'secondary'}>
                {t('workflows.shadow.stat.contractGaps')} <b>{shadowReport.contractGaps}</b>
              </Typography.Text>
            </Space>
            <Table
              size="small" pagination={false}
              rowKey={(r, i) => `${r.nodeKey}-${i}`}
              dataSource={shadowReport.nodes}
              columns={[
                { title: t('workflows.col.nodes'), dataIndex: 'name', ellipsis: true },
                { title: t('workflows.col.capability'), dataIndex: 'capability', width: 110,
                  render: (v: string) => <Tag color="blue">{v}</Tag> },
                { title: t('workflows.col.method'), dataIndex: 'method', width: 150 },
                { title: t('workflows.col.device'), dataIndex: 'deviceCode', width: 100,
                  render: (v: string | null) => v ?? '—' },
                { title: t('workflows.col.result'), width: 100,
                  render: (_, r) => (r.ok
                    ? <Tag color="green">OK {r.elapsedMs}ms</Tag>
                    : <Tag color="red">{r.errorCode ?? t('workflows.col.resultFailed')}</Tag>) },
                { title: t('workflows.col.contract'), width: 90,
                  // 后端影子跑的字段名是 contractGaps（contractMissing 从来不存在，
                  // 从前这里恒走 else 分支，缺字段的节点也会显示"完整"）
                  render: (_, r) => (r.contractGaps > 0
                    ? <Tag color="orange">{t('workflows.col.contractMissing', { n: r.contractGaps })}</Tag>
                    : <Tag color="green">{t('workflows.col.contractOk')}</Tag>) },
              ]}
            />
            <Typography.Paragraph type="secondary"
              style={{ fontSize: 11, marginTop: 10, marginBottom: 0 }}>
              {t('workflows.shadow.note')}
              {t('workflows.shadow.note2')}
            </Typography.Paragraph>
          </>
        )}
      </Modal>
    </>
  );
}

/**
 * 统计条上的一格：数值 + 标签 + 语义色。
 *
 * <p>颜色**不单独承载语义**：数值与文字同时在场，去掉颜色仍能读懂
 * （绿=已发布、黄=待办/待补录、灰=空壳）。带 onClick 的是筛选入口，
 * 加一个下箭头提示"可点"，避免"点了没反应"。
 */
function Kpi(props: {
  label: string;
  value: number | undefined;
  tone?: 'normal' | 'good' | 'warn' | 'muted';
  active?: boolean;
  onClick?: () => void;
}) {
  const { label, value, tone = 'normal', active, onClick } = props;
  const toneColor = tone === 'good' ? '#52c41a'
    : tone === 'warn' ? '#faad14'
      : tone === 'muted' ? '#8c8c8c' : '#d7dbe2';
  return (
    <div
      role={onClick ? 'button' : undefined}
      onClick={onClick}
      style={{
        display: 'flex', alignItems: 'baseline', gap: 8,
        padding: '6px 12px', borderRadius: 6,
        background: '#151920',
        border: `1px solid ${active ? '#2f6fd0' : '#262c38'}`,
        cursor: onClick ? 'pointer' : 'default',
      }}
    >
      <span style={{
        fontFamily: 'ui-monospace, monospace', fontSize: 18, fontWeight: 600,
        color: toneColor,
      }}>
        {value ?? '—'}
      </span>
      <span style={{ fontSize: 12, color: '#a6adb8' }}>{label}</span>
      {onClick && <span style={{ fontSize: 10, color: '#5a6270' }}>▾</span>}
    </div>
  );
}

/** 秒数转成人话：90 → "1 分 30 秒"（多语种：t 由调用方传入，模块级函数拿不到 Context） */
function formatWait(
  sec: number | null | undefined, t: (k: MessageKey, vars?: MessageVars) => string,
): string {
  if (sec == null || sec < 0) return t('workflows.duration.unknown');
  // 0 秒 = 已超过预估时间但还没结束，说"0 秒"会误导成"马上就好"
  if (sec === 0) return t('workflows.duration.underMinute');
  if (sec < 60) return t('workflows.duration.seconds', { n: sec });
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return s > 0
    ? t('workflows.duration.minutesSeconds', { m, s })
    : t('workflows.duration.minutes', { m });
}
