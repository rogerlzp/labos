import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert, Badge, Button, Card, Empty, Input, Modal, Progress, Space, Statistic, Table, Tag,
  Tooltip, Typography, message,
} from 'antd';
import {
  api, apiFetch, type AlertItem, type DeviceBusinessStateNow, type ProcessStatusItem,
  type RuntimeHoldItem,
} from '../api/client';
import { businessStateColor, businessStateLabel, statusColor, statusLabel, useStatusVocabulary } from '../utils/status';
import { useReadOnly } from '../auth';
import { useI18n, useT, type MessageKey } from '../i18n';

/**
 * 单页运行看板（2026-11-15）。
 *
 * <p><b>为什么要有这一页</b>：现场要回答「现在跑得怎么样、卡在哪、谁在等我」，
 * 以前得跨 5 个页面：{@code /runs}（在跑什么）→ {@code /runs/:id}（这次跑到哪）
 * → {@code /manual-windows}（等我决策）→ {@code /alerts}（有没有炸）
 * → {@code /island}（设备状态）。每换一个页签就丢一次上下文 ——
 * 值班员最常做的事是「一边看进度一边处理告警」，跨页等于来回切。
 *
 * <p><b>这一页不重复造轮子</b>：数据全部来自已有的接口
 * （{@code /api/process-status}、{@code /api/topology}、{@code /api/manual-windows}、
 * {@code /api/alerts}、{@code /api/runtime/holds}），不新增任何后端端点。
 * 深度视图仍然在原页面，看板只提供**跳转入口**。
 *
 * <p><b>刷新节奏</b>：进程状态 2 秒（内存态，跟调度器同频）、设备状态 3 秒、
 * 人工窗口与告警 10 秒（人工介入是低频事件，刷太快只是徒增请求）。
 */

/** 拓扑里的设备（只取看板要用的字段）。 */
interface BoardDevice {
  resourceId: number;
  code: string;
  name: string;
  model?: string | null;
  capability?: string | null;
  instanceId?: number | null;
  online: boolean;
  status: string;
  currentMethod?: string | null;
  hasPlate: boolean;
  /** 交板就绪（设备现在能不能把板交出去） */
  readyGive: boolean;
  /** 接板就绪（设备现在能不能接板） */
  readyTake: boolean;
  /** 最近一次错误 */
  lastError?: string | null;
  /** 当前占用它的 run */
  currentRunId?: number | null;
}

interface BoardIsland {
  id: number;
  code: string;
  name: string;
  devices: BoardDevice[];
}

/** 人工窗口（与 ManualWindows.tsx 同一份数据）。 */
interface BoardWindow {
  windowId: number;
  runId: number;
  windowKey: string;
  errorCode?: string | null;
  nodeKey?: string | null;
  strategy?: string | null;
  defaultAction: string;
  allowedActions: string[];
  decisionReason: string;
  status: string;
  openedAt?: string | null;
  timeoutAt?: string | null;
}

/** 现场扣留（运行时闸门）：比告警更"硬"，会直接阻断派发。 */
type BoardHold = RuntimeHoldItem;

/** 告警严重度 → 颜色。 */
const SEVERITY_COLOR: Record<string, string> = { error: 'red', warn: 'orange', info: 'default' };

/**
 * 人工窗口决策要落的**证据字段**（后端 `RunService.decide` → L-01 人工介入记录）。
 *
 * <p>这些不是"备注"，是**追溯链**：这块板是谁的、从哪来、到哪去、谁处理的。
 * 少了它们，事后复盘只能看到"某人在某时刻点了继续"，看不到"哪块板"。
 */
interface DecisionEvidence {
  plateBarcode: string;
  sampleId: string;
  fromLocation: string;
  toLocation: string;
  note: string;
}

const EMPTY_EVIDENCE: DecisionEvidence = {
  plateBarcode: '', sampleId: '', fromLocation: '', toLocation: '', note: '',
};
/** 扣留作用域 → 文案 key。 */
const HOLD_SCOPE_KEYS: Record<string, MessageKey> = {
  site: 'board.hold.scope.site',
  island: 'board.hold.scope.island',
  experiment: 'board.hold.scope.experiment',
};
/** 扣留类型 → 文案 key。 */
const HOLD_KIND_KEYS: Record<string, MessageKey> = {
  fault: 'board.hold.kind.fault',
  plate_unknown: 'board.hold.kind.plate_unknown',
  human: 'board.hold.kind.human',
  manual: 'board.hold.kind.manual',
};

/**
 * 设备状态 → 颜色。
 *
 * <p>判定顺序有意为之：**先看离线、再看错误、最后看状态**。
 * 离线是最要紧的（设备根本不在），错误次之（设备在但坏了），
 * 状态词表排最后（正常运行时各设备状态不同，不该盖住前两个信号）。
 */
function deviceStatusColor(d: BoardDevice): string {
  if (!d.online) return 'default';
  if (d.lastError) return 'red';
  const s = d.status;
  if (s === 'busy' || s === 'running') return 'blue';
  if (s === 'error' || s === 'fault') return 'red';
  if (s === 'offline') return 'default';
  return 'green';
}

/**
 * 设备状态标签。
 *
 * <p>只显示**人话状态**，不显示裸 code —— 现场看的是「空闲/忙碌/离线」。
 * 状态词表里没有的 code 回落到原样（不编文案）。
 */
function deviceStatusLabel(d: BoardDevice, vocab: ReturnType<typeof useStatusVocabulary>): string {
  if (!d.online) return '—';
  return statusLabel('node', d.status, vocab) || d.status;
}

/** 看板顶部四个计数（各回答一个问题）。 */
interface BoardStats {
  running: number;
  waitingHuman: number;
  openAlerts: number;
  offlineDevices: number;
}

export default function LiveBoardPage() {
  const t = useT();
  const { locale } = useI18n();
  const nav = useNavigate();
  const qc = useQueryClient();
  const ro = useReadOnly();
  const vocab = useStatusVocabulary();
  /** 只看某个岛；null = 全部岛 */
  const [islandFilter, setIslandFilter] = useState<number | null>(null);
  /** 展开的 run id（看该 run 的节点进度） */
  const [expandedRun, setExpandedRun] = useState<number | null>(null);
  /** 正在决策的人工窗口 + 选中的动作（证据字段在弹窗里补） */
  const [decideTarget, setDecideTarget] = useState<{ w: BoardWindow; action: string } | null>(null);
  const [evidence, setEvidence] = useState<DecisionEvidence>(EMPTY_EVIDENCE);

  // ---------------------------------------------------------------- 数据源

  /** 在跑的流程（内存态，2 秒）。 */
  const processQ = useQuery({
    queryKey: ['process-status'],
    queryFn: api.processStatus,
    refetchInterval: 2000,
  });

  /** 设备实时状态（拓扑，3 秒）。 */
  // 设备**业务状态**（客户文档 §1.5）：提取仪在不在等人、分液器在不在分液…
  // ⚠ 与 device_state.status（运行时 idle/running/error）**不是一回事**。
  const bizQ = useQuery({
    queryKey: ['device-business-states'],
    queryFn: () => api.deviceBusinessStates(),
    refetchInterval: 3000,
  });
  const bizByDevice = useMemo(() => {
    const m = new Map<number, DeviceBusinessStateNow>();
    for (const it of bizQ.data?.items ?? []) m.set(it.deviceId, it);
    return m;
  }, [bizQ.data]);

  const topoQ = useQuery({
    queryKey: ['topology'],
    queryFn: () => apiFetch('/api/topology').then((r) => r.json()) as Promise<{
      islands: BoardIsland[];
    }>,
    refetchInterval: 3000,
  });

  /** 待处理人工窗口（10 秒）。 */
  const windowsQ = useQuery({
    queryKey: ['manual-windows'],
    queryFn: () => apiFetch('/api/manual-windows').then((r) => r.json()) as Promise<{
      items: BoardWindow[];
    }>,
    refetchInterval: 10000,
  });

  /** 告警（10 秒）。 */
  const alertsQ = useQuery({
    queryKey: ['alerts'],
    queryFn: () => api.alerts(50),
    refetchInterval: 10000,
  });

  /** 现场扣留（10 秒）：比告警更硬，会直接阻断派发。 */
  const holdsQ = useQuery({
    queryKey: ['runtime-holds'],
    queryFn: api.runtimeHolds,
    refetchInterval: 10000,
  });

  /** 展开的 run 的节点明细（按需拉，不轮询）。 */
  const runDetailQ = useQuery({
    queryKey: ['run-detail', expandedRun],
    queryFn: () => api.run(expandedRun!),
    enabled: expandedRun != null,
  });

  // ---------------------------------------------------------------- 派生

  const islands = topoQ.data?.islands ?? [];

  /** 按岛过滤后的设备。 */
  const devices = useMemo(() => {
    const all = islands.flatMap((i) => i.devices.map((d) => ({ ...d, islandId: i.id, islandName: i.name })));
    return islandFilter == null ? all : all.filter((d) => d.islandId === islandFilter);
  }, [islands, islandFilter]);

  const runs = processQ.data?.items ?? [];
  /** 待处理的人工窗口（只留 open 状态的）。 */
  const windows = (windowsQ.data?.items ?? []).filter((w) => w.status === 'open');
  const alerts = alertsQ.data?.items ?? [];
  /** 未关闭的告警。 */
  const openAlerts = alerts.filter((a) => a.state === 'open' || a.state === 'acked');
  const holds: BoardHold[] = holdsQ.data?.holds ?? [];

  const stats: BoardStats = {
    running: runs.length,
    waitingHuman: windows.length,
    openAlerts: openAlerts.filter((a) => a.state === 'open').length,
    offlineDevices: devices.filter((d) => !d.online).length,
  };

  /** 手动刷新：一次 invalidate 全部。 */
  const refreshAll = async () => {
    await Promise.all([
      qc.invalidateQueries({ queryKey: ['process-status'] }),
      qc.invalidateQueries({ queryKey: ['topology'] }),
      qc.invalidateQueries({ queryKey: ['manual-windows'] }),
      qc.invalidateQueries({ queryKey: ['alerts'] }),
      qc.invalidateQueries({ queryKey: ['runtime-holds'] }),
    ]);
  };

  /**
   * 处理人工窗口的决策（与 ManualWindows 页**同口径**）。
   *
   * <p>⚠ 2026-11-15 之前这里是 `body: JSON.stringify({ action })` —— 只发动作，
   * **把 5 个证据字段全丢了**（板号/样本号/原位置/目标位置/备注）。
   * 后端 `RunService.decide` 会拿这些字段落人工介入记录（L-01），
   * 所以看板上做的每一次决策，在台账里都是**一条空记录** —— 追溯断链。
   *
   * <p>⇒ 改成：点动作先开弹窗补证据字段，再提交（与 ManualWindows 页完全一致）。
   */
  const decideWindow = async (w: BoardWindow, action: string, evidence: DecisionEvidence) => {
    try {
      await apiFetch(`/api/manual-windows/${w.windowId}/decide`, {
        method: 'POST',
        body: JSON.stringify({ action, ...evidence }),
      });
      message.success(t('board.toast.decided', { action }));
      setDecideTarget(null);
      await refreshAll();
    } catch (e) {
      message.error(String((e as Error).message));
    }
  };

  /** 告警快速认领（不关掉，只认领，表示有人在处理）。 */
  const ackAlert = async (a: AlertItem) => {
    try {
      await api.alertAct(a.fingerprint, 'ack', {});
      message.success(t('board.toast.acked'));
      await qc.invalidateQueries({ queryKey: ['alerts'] });
    } catch (e) {
      message.error(String((e as Error).message));
    }
  };

  /** 展开的 run 的节点（只取业务层，level=0）。 */
  const runNodes = (runDetailQ.data?.nodes ?? []).filter((n) => n.level === 0);

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      {/* ---------------------------------------------------------- 顶部计数 */}
      <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap' }}>
        <Card size="small" style={{ flex: '1 1 180px' }}>
          <Statistic
            title={t('board.stat.running')}
            value={stats.running}
            valueStyle={{ color: stats.running > 0 ? '#1677ff' : undefined }}
          />
        </Card>
        <Card size="small" style={{ flex: '1 1 180px' }}>
          <Statistic
            title={t('board.stat.waitingHuman')}
            value={stats.waitingHuman}
            valueStyle={{ color: stats.waitingHuman > 0 ? '#d48806' : undefined }}
          />
        </Card>
        <Card size="small" style={{ flex: '1 1 180px' }}>
          <Statistic
            title={t('board.stat.openAlerts')}
            value={stats.openAlerts}
            valueStyle={{ color: stats.openAlerts > 0 ? '#cf1322' : undefined }}
          />
        </Card>
        <Card size="small" style={{ flex: '1 1 180px' }}>
          <Statistic
            title={t('board.stat.offlineDevices')}
            value={stats.offlineDevices}
            valueStyle={{ color: stats.offlineDevices > 0 ? '#cf1322' : undefined }}
          />
        </Card>
      </div>

      {/* 现场扣留：这是比告警更硬的闸门（直接阻断派发），必须置顶显示 */}
      {holds.length > 0 && (
        <Alert
          type="warning"
          showIcon
          message={t('board.hold.title', { n: holds.length })}
          description={
            <Space direction="vertical" size={4} style={{ width: '100%' }}>
              {holds.map((h) => (
                <Space key={h.id} size={6} wrap>
                  <Tag color="orange">
                    {t(HOLD_SCOPE_KEYS[h.scope] ?? 'board.hold.scope.site')}
                  </Tag>
                  <Tag>{t(HOLD_KIND_KEYS[h.kind] ?? 'board.hold.kind.fault')}</Tag>
                  <Typography.Text style={{ fontSize: 12 }}>
                    {h.reason ?? '—'}
                  </Typography.Text>
                  {h.heldAt && (
                    <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                      {h.heldAt}
                    </Typography.Text>
                  )}
                </Space>
              ))}
            </Space>
          }
        />
      )}

      {/* ---------------------------------------------------------- 流程进度 */}
      <Card
        size="small"
        title={t('board.runs.title', { n: runs.length })}
        extra={
          <Space>
            <IslandFilter island={islandFilter} islands={islands}
              onChange={(v) => setIslandFilter(v)} />
            <Button size="small" onClick={() => { void refreshAll(); }}>
              {t('board.action.refresh')}
            </Button>
            <Button size="small" type="link" onClick={() => nav('/runs')}>
              {t('board.action.fullRuns')}
            </Button>
          </Space>
        }
      >
        {runs.length === 0 ? (
          <Empty description={t('board.runs.empty')} />
        ) : (
          <Table<ProcessStatusItem>
            rowKey="runId"
            size="small"
            loading={processQ.isLoading}
            dataSource={runs}
            pagination={false}
            expandable={{
              expandedRowRender: (r) => (
                expandedRun === r.runId && runDetailQ.data ? (
                  <Table
                    rowKey={(n) => String(n.id)}
                    size="small"
                    pagination={false}
                    dataSource={runNodes}
                    columns={[
                      { title: t('board.runs.col.node'), dataIndex: 'name', width: 200 },
                      {
                        title: t('board.runs.col.status'), dataIndex: 'status', width: 110,
                        render: (v: string) => (
                          <Tag color={statusColor('node', v, vocab)}>
                            {statusLabel('node', v, vocab)}
                          </Tag>
                        ),
                      },
                      { title: t('board.runs.col.device'), dataIndex: 'resourceCode', width: 110,
                        render: (v: string | null) => v ?? '—' },
                      { title: t('board.runs.col.reason'), dataIndex: 'reason', ellipsis: true,
                        render: (v: string | null) => v ?? '—' },
                    ]}
                  />
                ) : (
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {t('board.runs.expanding')}
                  </Typography.Text>
                )
              ),
              // antd v5 的 onExpand 签名是 (expanded: boolean, record: T)，
              // 不是 v4 的 (expandedRows: Key[]) —— 用受控 expandedRowKeys 才准。
              expandedRowKeys: expandedRun != null ? [expandedRun] : [],
              onExpand: (expanded: boolean, record: ProcessStatusItem) => {
                setExpandedRun(expanded ? Number(record.runId) : null);
              },
            }}
            columns={[
              {
                title: t('board.runs.col.run'), dataIndex: 'runId', width: 90,
                render: (v: number) => <a onClick={() => nav(`/runs/${v}`)}>#{v}</a>,
              },
              {
                title: t('board.runs.col.progress'), width: 180,
                render: (_, r) => (
                  <Progress
                    size="small"
                    percent={r.total ? Math.round((r.done * 100) / r.total) : 0}
                    format={() => `${r.done}/${r.total}`}
                  />
                ),
              },
              {
                title: t('board.runs.col.current'), width: 220,
                render: (_, r) => r.current?.name
                  ?? <Typography.Text type="secondary">{t('board.runs.waiting')}</Typography.Text>,
              },
              {
                title: t('board.runs.col.status'), width: 110,
                render: (_, r) => {
                  const code = r.current?.status;
                  return (
                    <Tag color={statusColor('node', code ?? '', vocab)}>
                      {code ? statusLabel('node', code, vocab) : '—'}
                    </Tag>
                  );
                },
              },
              {
                title: t('board.runs.col.attendance'), width: 100,
                render: (_, r) => (r.attendance === 'unattended'
                  ? <Tag color="purple">{t('board.attendance.unattended')}</Tag>
                  : <Tag>{t('board.attendance.attended')}</Tag>),
              },
              {
                title: t('board.runs.col.action'), width: 110,
                render: (_, r) => (
                  <Button size="small" type="link" onClick={() => nav(`/runs/${r.runId}`)}>
                    {t('board.action.detail')}
                  </Button>
                ),
              },
            ]}
          />
        )}
      </Card>

      {/* ---------------------------------------------------------- 人工窗口 */}
      <Card
        size="small"
        title={t('board.windows.title', { n: windows.length })}
        extra={
          <Button size="small" type="link" onClick={() => nav('/manual-windows')}>
            {t('board.action.fullWindows')}
          </Button>
        }
      >
        {windows.length === 0 ? (
          <Empty description={t('board.windows.empty')} />
        ) : (
          <Table<BoardWindow>
            rowKey="windowId"
            size="small"
            loading={windowsQ.isLoading}
            dataSource={windows}
            pagination={false}
            columns={[
              {
                title: t('board.windows.col.run'), width: 90,
                render: (_, w) => <a onClick={() => nav(`/runs/${w.runId}`)}>#{w.runId}</a>,
              },
              {
                title: t('board.windows.col.error'), width: 200,
                render: (_, w) => (
                  <Space direction="vertical" size={0}>
                    <Typography.Text code>{w.errorCode ?? '—'}</Typography.Text>
                    <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                      {w.nodeKey ?? '—'}
                    </Typography.Text>
                  </Space>
                ),
              },
              {
                title: t('board.windows.col.reason'), dataIndex: 'decisionReason', ellipsis: true,
              },
              {
                title: t('board.windows.col.timeout'), dataIndex: 'timeoutAt', width: 170,
                render: (v: string | null) => v ?? '—',
              },
              {
                title: t('board.windows.col.action'), width: 220,
                render: (_, w) => {
                  const actions = (w.allowedActions && w.allowedActions.length > 0)
                    ? w.allowedActions
                    : ['CONTINUE', 'RETRY', 'SKIP', 'ABORT'];
                  return (
                    <Space size={4} wrap>
                      {actions.map((a) => (
                        <Button
                          key={a}
                          size="small"
                          type={a === 'CONTINUE' ? 'primary' : 'default'}
                          disabled={ro.disabled}
                          title={ro.title}
                          onClick={() => {
                            // 不直接提交：先开弹窗补证据字段（板号/样本/交接点），
                            // 否则人工介入台账里会是一条空记录。
                            setEvidence(EMPTY_EVIDENCE);
                            setDecideTarget({ w, action: a });
                          }}
                        >
                          {a}
                        </Button>
                      ))}
                    </Space>
                  );
                },
              },
            ]}
          />
        )}
      </Card>

      {/* ---------------------------------------------------------- 设备状态 */}
      <Card
        size="small"
        title={t('board.devices.title', { n: devices.length })}
        extra={
          <Space>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {t('board.devices.hint')}
            </Typography.Text>
            <Button size="small" type="link" onClick={() => nav('/island')}>
              {t('board.action.fullIsland')}
            </Button>
          </Space>
        }
      >
        {devices.length === 0 ? (
          <Empty description={t('board.devices.empty')} />
        ) : (
          <Table<BoardDevice & { islandId: number; islandName: string }>
            rowKey="resourceId"
            size="small"
            loading={topoQ.isLoading}
            dataSource={devices}
            pagination={{ pageSize: 12, size: 'small' }}
            columns={[
              { title: t('board.devices.col.code'), dataIndex: 'code', width: 110,
                render: (v: string) => <Typography.Text code>{v}</Typography.Text> },
              { title: t('board.devices.col.name'), dataIndex: 'name', width: 150 },
              { title: t('board.devices.col.island'), dataIndex: 'islandName', width: 110 },
              {
                title: t('board.devices.col.status'), width: 100,
                render: (_, d) => (
                  <Tag color={deviceStatusColor(d)}>{deviceStatusLabel(d, vocab)}</Tag>
                ),
              },
              {
                // **业务状态**（客户 §1.5）：这台设备"在干什么" ——
                // 与上面的运行时状态（idle/running/error）**不是一回事**。
                title: t('board.devices.col.businessState'), width: 150,
                render: (_, d) => {
                  const b = d.instanceId != null ? bizByDevice.get(d.instanceId) : undefined;
                  if (!b || !b.stateCode) {
                    return <Typography.Text type="secondary">—</Typography.Text>;
                  }
                  const label = businessStateLabel(b.stateCode, b.stateName, locale);
                  return (
                    <Tooltip
                      title={t('board.devices.businessStateTip', {
                        source: b.sourceDetail || b.source,
                      })}
                    >
                      <Tag color={businessStateColor(b.stateCode)}>{label}</Tag>
                    </Tooltip>
                  );
                },
              },
              {
                title: t('board.devices.col.plate'), width: 80,
                render: (_, d) => (d.hasPlate
                  ? <Badge status="processing" text={t('board.devices.hasPlate')} />
                  : <Typography.Text type="secondary">{t('board.devices.noPlate')}</Typography.Text>),
              },
              {
                title: t('board.devices.col.ready'), width: 120,
                render: (_, d) => (
                  <Space size={4}>
                    <Tag color={d.readyGive ? 'green' : 'default'}>
                      {t('board.devices.readyGive')}
                    </Tag>
                    <Tag color={d.readyTake ? 'green' : 'default'}>
                      {t('board.devices.readyTake')}
                    </Tag>
                  </Space>
                ),
              },
              {
                title: t('board.devices.col.method'), dataIndex: 'currentMethod', width: 150,
                render: (v: string | null) => v ?? '—',
              },
              {
                title: t('board.devices.col.error'), dataIndex: 'lastError', ellipsis: true,
                render: (v: string | null) => (v
                  ? <Typography.Text type="danger" style={{ fontSize: 12 }}>{v}</Typography.Text>
                  : '—'),
              },
            ]}
          />
        )}
      </Card>

      {/* ---------------------------------------------------------- 告警 */}
      <Card
        size="small"
        title={t('board.alerts.title', { n: openAlerts.length })}
        extra={
          <Button size="small" type="link" onClick={() => nav('/alerts')}>
            {t('board.action.fullAlerts')}
          </Button>
        }
      >
        {openAlerts.length === 0 ? (
          <Empty description={t('board.alerts.empty')} />
        ) : (
          <Table<AlertItem>
            rowKey="fingerprint"
            size="small"
            loading={alertsQ.isLoading}
            dataSource={openAlerts}
            pagination={{ pageSize: 8, size: 'small' }}
            columns={[
              { title: t('board.alerts.col.fingerprint'), dataIndex: 'fingerprint', width: 220 },
              { title: t('board.alerts.col.hits'), dataIndex: 'count', width: 70 },
              {
                title: t('board.alerts.col.severity'), dataIndex: 'severity', width: 90,
                render: (v: string) => <Tag color={SEVERITY_COLOR[v] ?? 'default'}>{v}</Tag>,
              },
              {
                title: t('board.alerts.col.message'), dataIndex: 'lastMessage', ellipsis: true,
                render: (v: string) => <Typography.Text style={{ fontSize: 12 }}>{v}</Typography.Text>,
              },
              {
                title: t('board.alerts.col.action'), width: 90,
                render: (_, a) => (a.state === 'open' ? (
                  <Button size="small" type="link" disabled={ro.disabled} title={ro.title}
                    onClick={() => { void ackAlert(a); }}>
                    {t('board.action.ack')}
                  </Button>
                ) : <Tag color="blue">{t('board.alerts.acked')}</Tag>),
              },
            ]}
          />
        )}
      </Card>

      {/* 人工窗口决策弹窗：补齐 5 个证据字段（与 ManualWindows 页同口径） */}
      <Modal
        open={decideTarget != null}
        title={t('board.decide.title', { action: decideTarget?.action ?? '' })}
        onCancel={() => setDecideTarget(null)}
        onOk={() => {
          if (decideTarget) {
            void decideWindow(decideTarget.w, decideTarget.action, evidence);
          }
        }}
        okText={t('board.decide.ok')}
        cancelText={t('board.decide.cancel')}
      >
        {decideTarget && (
          <Space direction="vertical" size={10} style={{ width: '100%' }}>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {t('board.decide.hint')}
            </Typography.Text>
            <Input
              placeholder={t('board.decide.field.plate')}
              value={evidence.plateBarcode}
              onChange={(e) => setEvidence({ ...evidence, plateBarcode: e.target.value })}
            />
            <Input
              placeholder={t('board.decide.field.sample')}
              value={evidence.sampleId}
              onChange={(e) => setEvidence({ ...evidence, sampleId: e.target.value })}
            />
            <Input
              placeholder={t('board.decide.field.from')}
              value={evidence.fromLocation}
              onChange={(e) => setEvidence({ ...evidence, fromLocation: e.target.value })}
            />
            <Input
              placeholder={t('board.decide.field.to')}
              value={evidence.toLocation}
              onChange={(e) => setEvidence({ ...evidence, toLocation: e.target.value })}
            />
            <Input.TextArea
              rows={2}
              placeholder={t('board.decide.field.note')}
              value={evidence.note}
              onChange={(e) => setEvidence({ ...evidence, note: e.target.value })}
            />
          </Space>
        )}
      </Modal>
    </Space>
  );
}

/**
 * 岛过滤下拉。
 *
 * <p>单独抽成小组件是因为它在「流程进度」卡的 header 里，
 * 而看板其它卡不按岛过滤 —— 设备卡已经按同一个 islandFilter 过滤了。
 */
function IslandFilter({ island, islands, onChange }: {
  island: number | null;
  islands: BoardIsland[];
  onChange: (v: number | null) => void;
}) {
  const t = useT();
  return (
    <select
      value={island == null ? '' : String(island)}
      onChange={(e) => onChange(e.target.value ? Number(e.target.value) : null)}
      style={{
        background: '#1f2430', color: '#e6e6e6', border: '1px solid #3a4152',
        borderRadius: 6, padding: '4px 8px', fontSize: 12,
      }}
    >
      <option value="">{t('board.filter.allIslands')}</option>
      {islands.map((i) => (
        <option key={i.id} value={i.id}>{i.name}（{i.code}）</option>
      ))}
    </select>
  );
}
