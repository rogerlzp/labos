import { useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Alert, Button, Card, Empty, Input, InputNumber, Modal, Select, Space, Table, Tag, Typography, message,
} from 'antd';
import { apiFetch, api } from '../api/client';
import type {
  ExperimentBatchAttempt, ExperimentBatchItem, HoldScope,
} from '../api/client';
import {
  holdKindMeta, holdScopeLabel, pauseKindMeta, statusColor, statusLabel, useStatusVocabulary,
} from '../utils/status';
import { useReadOnly } from '../auth';
import { useT } from '../i18n';

interface Exp {
  id: number;
  experimentNo: string;
  name: string;
  status: string;
  islandCode?: string | null;
}

interface ExpVar {
  id?: number;
  name: string;
  type: string;
  defaultValue: string;
  description?: string;
}

interface Proc {
  id: number;
  workflowId: number;
  workflowName: string;
  workflowCode: string;
  seq: number;
  nodeCount: number;
  loopCount?: string | null;
}

/**
 * 实验编排：把 已有流程按顺序拼成一个实验。
 *
 * 1.0 只做顺序（存量流程 99.7% 是顺序执行）。
 * 用列表 + 上下移而不是画布：顺序场景下，列表比画布更快、更不容易连错。
 * 等有条件分支/并行时再上画布（React Flow）。
 */
/** 一次实验执行（主表一行）。 */
interface ExpRun {
  id: number; status: string; expandedSteps: number; currentSeq: number;
  startedAt?: string | null; finishedAt?: string | null;
  durationSec?: number | null; error?: string | null;
  /** 暂停类型/原因：后端 experimentRunsJson 已带出，界面要显示"为什么停" */
  pauseKind?: string | null; pauseReason?: string | null;
  /** S-04-B：暂停控制过程（PAUSE_REQUESTED/PAUSING/...），与生命周期 status 正交 */
  controlState?: string | null;
  /** 人工停止请求已落库，执行器还没走到动作边界 */
  stopRequested?: boolean;
}

/** 一次实验执行的逐步明细：跑了哪些、哪些没跑。 */
interface ExpStep {
  seq: number; loopIndex: number;
  workflowId: number; workflowName?: string | null;
  status: string; runId?: number | null; error?: string | null;
}

// 状态 / 扣留的文案与颜色统一从 utils/status.ts 来（后端词表为权威，本页不再维护映射）。

/**
 * 时间显示（本地时区）。
 *
 * <p>后端目前有两套时间串：多数接口给 JDBC Timestamp 的**本地**时间
 * （`2026-11-03 18:50:40.0`），扣留接口给的是带时区的 ISO（`…T10:50:40Z`）。
 * 一律 `slice(0,19)` 会把 UTC 当本地显示 —— 扣留时间差 8 小时，
 * 值班员会误判"已经停了多久"。这里两种都能扛，且不做无谓换算。
 */
function fmtTime(s?: string | null): string {
  if (!s) return '—';
  const naive = /^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}/.test(s) && !/(?:Z|[+-]\d{2}:?\d{2})$/.test(s);
  if (naive) return s.slice(0, 19).replace('T', ' ');
  const d = new Date(s);
  if (Number.isNaN(d.getTime())) return s.slice(0, 19).replace('T', ' ');
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} `
    + `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

/**
 * 展开一行「执行历史」看到的步骤表。
 *
 * <p>为什么要单独查：`experiment_run_step` 只在**步骤开始时**插行，
 * 前面一步失败后后面的步骤根本没有行 —— 只看主表会以为"实验就 1 步"。
 * 后端按 experiment_process × loop_count 展开出计划步骤，没执行过的标 `pending`。
 */
function RunSteps({ runId, nav }: { runId: number; nav: (p: string) => void }) {
  const t = useT();
  // step scope：展开后的步骤状态（和 run 状态不是一回事，skipped 在本 scope 是橙色）
  const vocab = useStatusVocabulary();
  const q = useQuery({
    queryKey: ['exp-run-steps', runId],
    queryFn: () => apiFetch(`/api/experiments/runs/${runId}/steps`).then((r) => r.json()) as Promise<{
      status: string; expandedSteps: number;
      completed: number; failed: number; pending: number;
      items: ExpStep[];
    }>,
  });
  if (q.isLoading) return <Typography.Text type="secondary">{t('experiments.loading')}</Typography.Text>;
  const d = q.data;
  if (!d) return <Typography.Text type="secondary">{t('experiments.empty.noSteps')}</Typography.Text>;
  return (
    <Space direction="vertical" size={6} style={{ width: '100%' }}>
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        {t('experiments.steps.summary', { n: d.expandedSteps, done: d.completed, failed: d.failed, pending: d.pending })}
      </Typography.Text>
      <Table<ExpStep>
        size="small" pagination={false} rowKey={(r) => `${r.seq}-${r.loopIndex}`}
        dataSource={d.items}
        columns={[
          { title: t('experiments.col.step'), width: 70, render: (_, r) => `${r.seq}` },
          { title: t('experiments.col.loop'), width: 70, render: (_, r) => `#${r.loopIndex + 1}` },
          { title: t('experiments.col.workflow'), ellipsis: true, render: (_, r) => r.workflowName ?? `#${r.workflowId}` },
          {
            title: t('experiments.col.status'), width: 100,
            render: (_, r) => (
              <Tag color={statusColor('step', r.status, vocab)}>
                {statusLabel('step', r.status, vocab)}
              </Tag>
            ),
          },
          {
            title: t('experiments.col.run'), width: 110,
            render: (_, r) => (r.runId
              ? <Button size="small" type="link"
                  onClick={() => nav(`/runs/${r.runId}`)}>#{r.runId}</Button>
              : '—'),
          },
          {
            // 打开**只读的运行态流程页**（不是设计器！）：
            // 有这一步的 run ⇒ /runs/{runId}/flow（图按这次执行的状态着色）；
            // 没有 run（失败在建执行单之前）⇒ /workflows/{wfId}/flow（只看流程定义）。
            // ⚠ 页面与流程设计器**刻意分开**：设计器是编辑页（可改图），运行态是只读页。
            //
            // ⚠ **不要求 runId**：这一步失败在"建流程执行单之前"时（设备抢不到锁、
            // 展开失败等）**没有 runId** ⇒ 以前这一格是「—」⇒ 实验里点不进去、
            // 看不到这个流程长什么样。⇒ 只要有流程就给出入口：没有 run 就只看图。
            title: t('experiments.col.workflowGraph'), width: 110,
            render: (_, r) => (r.workflowId
              ? <Button size="small" type="link"
                  onClick={() => nav(r.runId
                    ? `/runs/${r.runId}/flow`
                    : `/workflows/${r.workflowId}/flow`)}>
                  {t('experiments.action.openWorkflowGraph')}
                </Button>
              : '—'),
          },
          { title: t('experiments.col.error'), ellipsis: true, render: (_, r) => r.error ?? '—' },
        ]}
      />
    </Space>
  );
}

export default function ExperimentsPage() {
  const t = useT();
  const nav = useNavigate();
  // 本页用 run / step / batch 三个 scope 的状态 + holdKind / holdScope：统一从词表取
  const vocab = useStatusVocabulary();
  // 执行/批次重跑/暂停恢复/变量保存都是写操作：viewer 只读
  const ro = useReadOnly();
  // 详情页由路由带 id：/experiments/:id
  const { id: routeId } = useParams();
  const [expId, setExpId] = useState<number | null>(routeId ? Number(routeId) : null);
  /** 实验变量（本地编辑，整体保存） */
  const [vars, setVars] = useState<ExpVar[]>([]);
  const [varsDirty, setVarsDirty] = useState(false);
  const [running, setRunning] = useState(false);
  /** 异步执行（S3.2）：分批实验只建下一批 run 入队，页面不阻塞 */
  const [asyncRunning, setAsyncRunning] = useState(false);
  const [runResult, setRunResult] = useState<string | null>(null);
  /** 新建流程弹窗：只建流程，不再直接改实验编排。 */
  const [createWfOpen, setCreateWfOpen] = useState(false);
  const [newWfName, setNewWfName] = useState('');
  const [busyWf, setBusyWf] = useState(false);

  const createWorkflow = async () => {
    if (!newWfName.trim()) {
      message.warning(t('experiments.warn.workflowName'));
      return;
    }
    setBusyWf(true);
    try {
      const r = await apiFetch('/api/workflows', {
        method: 'POST',
        body: JSON.stringify({ name: newWfName.trim() }),
      });
      const j = await r.json();
      if (!r.ok) {
        message.error(j.error ?? t('experiments.error.createFailed'));
        return;
      }
      setCreateWfOpen(false);
      setNewWfName('');
      await wfs.refetch();
      message.success(t('experiments.toast.workflowCreated'));
      // 带上实验上下文：设计器顶部显示"属于哪个实验/岛屿"，可一键返回
      const exp = exps.data?.items.find((e) => e.id === expId);
      nav(`/designer/${j.id}?expId=${expId}`
        + `&expName=${encodeURIComponent(exp?.name ?? '')}`
        + `&island=${encodeURIComponent(exp?.islandCode ?? '')}`);
    } finally {
      setBusyWf(false);
    }
  };

  /** 新建实验弹窗 */
  const [creating, setCreating] = useState(false);
  const [newName, setNewName] = useState('');
  const [newCode, setNewCode] = useState('');
  const [newIsland, setNewIsland] = useState<number | undefined>(undefined);
  const [newWfs, setNewWfs] = useState<number[]>([]);
  const [busyCreate, setBusyCreate] = useState(false);

  const exps = useQuery({
    queryKey: ['experiments'],
    queryFn: () => apiFetch('/api/experiments').then((r) => r.json()) as Promise<{ items: Exp[] }>,
  });
  const wfs = useQuery({ queryKey: ['workflows'], queryFn: () => api.workflows(300) });

  const procs = useQuery({
    queryKey: ['exp-processes', expId],
    queryFn: () => apiFetch(`/api/experiments/${expId}/processes`).then((r) => r.json()) as Promise<{ items: Proc[] }>,
    enabled: expId != null,
  });

  /**
   * 生效中的**现场扣留**（runtime_hold）。
   *
   * <p>为什么要在这里显示：调度器按 ADR-FAULT-SCOPE-001「现场扣留优先于派发」——
   * 扣留覆盖到本实验（本岛或本实验）时，**新执行会一直留在队列里不派发**
   * （调度器状态显示 `stalled`）。而扣留通常是「上一次执行转人工」自动抬的
   * ⇒ 现场只看得到"一直排队"，看不出原因（2026-11-11 用户现场卡在这里）。
   * ⇒ 这里直接把"为什么排队"摆在实验页上。
   */
  const holdsQ = useQuery({
    queryKey: ['runtime-holds'],
    queryFn: () => apiFetch('/api/runtime/holds').then((r) => r.json()) as Promise<{
      anyActive?: boolean; unknown?: boolean; unknownReason?: string | null;
      holds?: Array<{ scope: string; scopeId: number; kind: string; reason?: string }>;
    }>,
  });

  /**
   * 编排编辑：把「**已经建好的流程**」按顺序加进这个实验。
   *
   * <p>为什么原来界面上没有这个入口：编排表被设计成「画布编排」的**编译产物**
   * （写入口唯一 = 画布），于是**实验建好之后没有任何地方能把已有流程加进来** ——
   * 只有「新建实验」弹窗能带流程（2026-11-11 用户现场：新建了实验，却找不到地方
   * 放已经建好的流程）。
   *
   * <p>现在后端放开 `PUT /api/experiments/{id}/processes`（**已有画布编排的实验仍拒绝**
   * —— 画布是权威，两边都写会分裂成「画布一套、顺序一套」）。
   * 没有画布的实验：这里保存编排后，画布页会从编排表自动派生出一致的线性画布。
   */
  const [procEditOpen, setProcEditOpen] = useState(false);
  /** 当前编排顺序（workflowId 数组，顺序 = 执行顺序） */
  const [procOrder, setProcOrder] = useState<number[]>([]);
  /** 待加入的流程（多选） */
  const [procPick, setProcPick] = useState<number[]>([]);
  const [procSaving, setProcSaving] = useState(false);

  const openProcEdit = () => {
    setProcOrder([...(procs.data?.items ?? [])]
      .sort((a, b) => a.seq - b.seq).map((x) => x.workflowId));
    setProcPick([]);
    setProcEditOpen(true);
  };

  const addPicked = () => {
    setProcOrder((old) => {
      const seen = new Set(old);
      return [...old, ...procPick.filter((id) => !seen.has(id))];
    });
    setProcPick([]);
  };

  const moveProc = (i: number, delta: number) => {
    setProcOrder((old) => {
      const j = i + delta;
      if (j < 0 || j >= old.length) return old;
      const next = old.slice();
      const tmp = next[i]; next[i] = next[j]; next[j] = tmp;
      return next;
    });
  };

  const removeProc = (i: number) => {
    setProcOrder((old) => old.filter((_, k) => k !== i));
  };

  const saveProcs = async () => {
    if (expId == null) return;
    setProcSaving(true);
    try {
      const r = await apiFetch(`/api/experiments/${expId}/processes`, {
        method: 'PUT',
        body: JSON.stringify({ workflowIds: procOrder }),
      });
      const j = await r.json().catch(() => ({}));
      if (!r.ok) {
        message.error(j.error ?? t('experiments.error.saveProcsFailed'));
        return;
      }
      message.success(t('experiments.toast.procsSaved'));
      setProcEditOpen(false);
      await procs.refetch();
    } finally {
      setProcSaving(false);
    }
  };

  // 默认选中第一个实验：只在"没有路由 id"时做，否则会把 URL 指定的实验覆盖掉
  useEffect(() => {
    if (!routeId && expId == null && exps.data?.items?.length) {
      setExpId(exps.data.items[0].id);
    }
  }, [exps.data, expId, routeId]);

  // 路由 id 变化（从列表点进另一个实验）时同步
  useEffect(() => {
    if (routeId && Number(routeId) !== expId) {
      setExpId(Number(routeId));
    }
  }, [routeId, expId]);

  // 新建实验要选岛（决定去哪台设备上跑）
  const islands = useQuery({
    queryKey: ['islands-for-exp'],
    queryFn: () => apiFetch('/api/topology').then((r) => r.json()) as Promise<{
      islands: Array<{ code: string; id: number; name: string; devices?: unknown[] }>;
    }>,
  });

  // 当前实验的岛 id（列表接口只给 islandCode，用 topology 映射成 id）
  const expRow = exps.data?.items?.find((e) => e.id === expId);
  const expIslandId = islands.data?.islands?.find((i) => i.code === expRow?.islandCode)?.id;
  /** 本实验当前是否被现场扣留挡住（不派发） */
  const holdBlock = useMemo(() => {
    const h = holdsQ.data;
    if (!h) return null;
    if (h.unknown) {
      return { kind: 'unknown', reason: h.unknownReason ?? '' };
    }
    const list = h.holds ?? [];
    const hit = list.find((x) =>
      (x.scope === 'experiment' && x.scopeId === expId)
      || (x.scope === 'island' && expIslandId != null && x.scopeId === expIslandId));
    return hit ? { kind: hit.scope, reason: hit.reason ?? '' } : null;
  }, [holdsQ.data, expId, expIslandId]);

  /**
   * 新建实验。
   *
   * <p>以前界面上没有这个入口，只能手写 SQL 往实验 + 实验流程两张表里插 ——
   * 少插后者就会得到"实验建好了但点执行什么也不跑"。这里一次把两张表都写了。
   */
  const createExp = async () => {
    if (!newName.trim()) {
      message.warning(t('experiments.warn.experimentName'));
      return;
    }
    setBusyCreate(true);
    try {
      const r = await apiFetch('/api/experiments', {
        method: 'POST',
        body: JSON.stringify({
          name: newName.trim(),
          code: newCode.trim() || undefined,
          islandId: newIsland,
          workflowIds: newWfs,
        }),
      });
      const j = await r.json();
      if (!r.ok || !j.id) {
        throw new Error(j.error ?? `HTTP ${r.status}`);
      }
      message.success(t('experiments.toast.experimentCreated', { id: j.id, no: j.experimentNo }));
      setCreating(false);
      setNewName('');
      setNewCode('');
      setNewWfs([]);
      await exps.refetch();
      setExpId(j.id);          // 建完直接选中，不用再去列表里找
    } catch (e) {
      message.error(t('experiments.error.createFailedDetail', { msg: (e as Error).message }));
    } finally {
      setBusyCreate(false);
    }
  };

  const runsQuery = useQuery({
    queryKey: ['exp-runs', expId],
    queryFn: async () => {
      const r = await apiFetch(`/api/experiments/${expId}/runs`);
      return r.json() as Promise<{ items: ExpRun[] }>;
    },
    enabled: expId != null,
  });

  /**
   * 分批实验的批次视图（S3）。
   *
   * <p>单独请求、不塞进实验详情：batch_split 是后加的列，非分批实验没有批次；
   * 接口在 batchSplit 为 null 时返回空 items，卡片据此整块隐藏 —— 非分批实验零变化。
   * 批次状态由执行器推进，刷新方式与「现场扣留/执行历史」保持一致（手动刷新）。
   */
  const batchesQuery = useQuery({
    queryKey: ['exp-batches', expId],
    queryFn: () => api.experimentBatches(expId!),
    enabled: expId != null,
  });
  /** null = 不分批（非分批实验）：整个批次卡片不渲染 */
  const batchSplit = batchesQuery.data?.batchSplit ?? null;

  /** 分批设置弹窗（S3.1）：入口必须在页面上，否则 batch_split 只能改库（功能不可演示） */
  const [batchCfgOpen, setBatchCfgOpen] = useState(false);
  const [batchCfgValue, setBatchCfgValue] = useState<number | null>(null);
  const [batchSaving, setBatchSaving] = useState(false);

  const openBatchCfg = () => {
    setBatchCfgValue(batchSplit);
    setBatchCfgOpen(true);
  };

  const saveBatchCfg = async () => {
    if (expId == null) return;
    setBatchSaving(true);
    try {
      const r = await api.setExperimentBatchSplit(expId, batchCfgValue);
      message.success(r.batchSplit == null
        ? t('experiments.batch.closed')
        : t('experiments.batch.set', { n: r.batchSplit }));
      setBatchCfgOpen(false);
      // batchSplit 变了 -> 批次卡片的显示/隐藏与执行提示都要跟着刷
      await batchesQuery.refetch();
    } catch (e) {
      message.error(t('experiments.error.batchFailed', { msg: (e as Error).message }));
    } finally {
      setBatchSaving(false);
    }
  };

  /**
   * 生效的现场扣留。
   *
   * <p>实验级暂停是按"扣留闸门"落库的，run 的 paused 只是给界面的即时反馈。
   * 5 秒刷新：值班员不用手动点，也能看到别人抬的扣留（同岛/全场）。
   */
  const holdsQuery = useQuery({
    queryKey: ['runtime-holds'],
    queryFn: () => api.runtimeHolds(),
    refetchInterval: 5000,
  });

  /** 暂停弹窗：作用域 + 原因先问清楚，再落扣留 */
  const [pauseFor, setPauseFor] = useState<{
    runId: number; scope: HoldScope; reason: string;
  } | null>(null);
  const [acting, setActing] = useState(false);

  /** 动作之后把三处视图一起刷：执行历史、扣留、实验状态 */
  const refreshRunViews = async () => {
    await Promise.all([runsQuery.refetch(), holdsQuery.refetch(), exps.refetch()]);
  };

  const doPause = async () => {
    if (!pauseFor) return;
    setActing(true);
    try {
      const r = await api.pauseExperimentRun(pauseFor.runId, {
        scope: pauseFor.scope,
        reason: pauseFor.reason.trim() || undefined,
      });
      message.success(t('experiments.toast.paused', { id: r.runId, scope: holdScopeLabel(r.scope, vocab) }));
      setPauseFor(null);
      await refreshRunViews();
    } catch (e) {
      message.error(t('experiments.error.pauseFailed', { msg: (e as Error).message }));
    } finally {
      setActing(false);
    }
  };

  const doStop = (runId: number) => {
    Modal.confirm({
      title: t('experiments.confirm.stopTitle', { id: runId }),
      content: t('experiments.confirm.stopContent'),
      okText: t('experiments.confirm.stopOk'),
      okButtonProps: { danger: true },
      cancelText: t('experiments.confirm.cancel'),
      onOk: async () => {
        try {
          const r = await api.stopExperimentRun(runId);
          message.warning(t('experiments.toast.stopRequested', { id: r.runId, status: r.status }));
          await refreshRunViews();
        } catch (e) {
          message.error(t('experiments.error.stopFailed', { msg: (e as Error).message }));
        }
      },
    });
  };

  const doResume = async (runId: number) => {
    setActing(true);
    try {
      const r = await api.resumeExperimentRun(runId);
      if (r.stillHeldReason) {
        // site 级扣留不会被单实验恢复顺手解除 —— 如实说，别假装已恢复
        message.warning(t('experiments.warn.stillHeld', { reason: r.stillHeldReason }));
      } else {
        message.success(r.requeued ? t('experiments.toast.resumedRequeued') : t('experiments.toast.resumed'));
      }
      await refreshRunViews();
    } catch (e) {
      message.error(t('experiments.error.resumeFailed', { msg: (e as Error).message }));
    } finally {
      setActing(false);
    }
  };

  const varQuery = useQuery({
    queryKey: ['exp-vars', expId],
    queryFn: async () => {
      const r = await apiFetch(`/api/experiments/${expId}/variables`);
      return r.json() as Promise<{ items: ExpVar[] }>;
    },
    enabled: expId != null,
  });

  useEffect(() => {
    if (!varsDirty && varQuery.data?.items) {
      setVars(varQuery.data.items);
    }
  }, [varQuery.data, varsDirty]);

  const saveVars = async () => {
    if (expId == null) return;
    const r = await apiFetch(`/api/experiments/${expId}/variables`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ items: vars }),
    });
    const j = await r.json();
    if (!r.ok) { message.error(j.error ?? t('experiments.error.saveFailed')); return; }
    message.success(t('experiments.toast.varsSaved', { n: j.saved }));
    setVarsDirty(false);
    await varQuery.refetch();
  };

  /**
   * 当前变量值 → 执行时注入的覆盖表。
   *
   * <p>整实验执行和"重跑单批"必须用同一份：否则重跑批会拿到与首次执行不同的变量，
   * 结果对不上还查不出原因（D5：批变量由后端追加，不覆盖这里的显式值）。
   */
  const buildVarsOverride = () => {
    const override: Record<string, string> = {};
    for (const v of vars) override[v.name] = v.defaultValue;
    return override;
  };

  const runExp = async () => {
    if (expId == null) return;
    setRunning(true);
    setRunResult(null);
    try {
      const r = await apiFetch(`/api/experiments/${expId}/run`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ vars: buildVarsOverride() }),
      });
      const j = await r.json();
      if (!r.ok) throw new Error(j.error ?? t('experiments.error.execFailed'));
      // 分批实验的顶层 status 是「聚合后的实验状态」：全部成功为 finished（实验表词表），
      // 单批 run 才是 completed。只认 completed 会把"全部成功"弹成 warning。
      const okStatus = j.status === 'completed' || j.status === 'finished';
      const batchNote = j.batchSplit != null ? t('experiments.batch.note', { n: j.batches?.length ?? 0 }) : '';
      setRunResult(t('experiments.toast.runResult', {
        status: j.status, done: j.doneSteps, total: j.totalSteps, note: batchNote,
      })
        + (j.error ? ` · ${j.error}` : ''));
      message[okStatus ? 'success' : 'warning'](
        + t('experiments.toast.expResult', {
          status: j.status, done: j.doneSteps, total: j.totalSteps, note: batchNote,
        }));
      // 分批执行会按批串行推进：不刷批次列表和执行历史，用户点完执行看到的还是变更前的状态
      await Promise.all([batchesQuery.refetch(), runsQuery.refetch()]);
    } catch (e) {
      message.error(String((e as Error).message));
    } finally {
      setRunning(false);
    }
  };

  /**
   * 异步执行（S3.2）：分批实验"建下一批 run 入队、页面不阻塞"，调度器按批自动续跑。
   *
   * <p>三种响应必须分开提示，不能一律当成功：
   * <ul>
   *   <li>已入队：告诉用户第几批、run#，以及调度器会自动续后面的批；</li>
   *   <li>现场扣留（held）：**没有建 attempt**，不能提示"已排队"，否则用户以为系统会自己跑；</li>
   *   <li>没有待执行批次：如实说"没有待执行批次"，不要当失败；</li>
   * </ul>
   * 点击后兜底刷新批次/执行历史，让排队状态尽快可见（调度器 2s 一轮派发）。
   */
  const runExpAsync = async () => {
    if (expId == null) return;
    setAsyncRunning(true);
    try {
      const r = await api.runExperimentAsync(expId, buildVarsOverride());
      if (r.experimentRunId != null) {
        message.success(t('experiments.toast.queued', { batch: r.batchNo, run: r.experimentRunId }));
      } else if ('held' in r) {
        message.warning(t('experiments.warn.heldNotStarted', {
          batch: r.batchNo, reason: r.holdReason ?? t('experiments.warn.heldDefault'),
        }));
      } else {
        message.info(t('experiments.info.noPendingBatch', { status: r.status }));
      }
      await Promise.all([batchesQuery.refetch(), runsQuery.refetch()]);
    } catch (e) {
      message.error(t('experiments.error.asyncFailed', { msg: (e as Error).message }));
    } finally {
      setAsyncRunning(false);
    }
  };

  /**
   * 重跑单批（D4）。
   *
   * <p>确认框必须把"只跑这一批、历史尝试保留"说清楚：用户最怕的是点一下
   * 把整个实验连同已成功批次重跑一遍，或把失败历史覆盖掉。
   * pending 批不开放重跑 —— 它还没跑过，应该用「执行实验」按批串行跑。
   */
  const doRerunBatch = (batchNo: number) => {
    if (expId == null) return;
    Modal.confirm({
      title: t('experiments.confirm.rerunTitle', { batch: batchNo }),
      content: t('experiments.confirm.rerunContent'),
      okText: t('experiments.confirm.rerunOk'),
      cancelText: t('experiments.confirm.cancel'),
      onOk: async () => {
        try {
          const r = await api.runExperimentBatch(expId, batchNo, buildVarsOverride());
          message[r.status === 'completed' ? 'success' : 'warning'](
            t('experiments.toast.rerunDone', {
            batch: batchNo, status: r.status, done: r.doneSteps,
            total: r.totalSteps, attempt: r.attemptNo,
          }));
          // 重跑会新增 run + batch_run：批次列表和执行历史都会变，两张一起刷
          await Promise.all([batchesQuery.refetch(), runsQuery.refetch()]);
        } catch (e) {
          message.error(t('experiments.error.rerunFailed', { batch: batchNo, msg: (e as Error).message }));
        }
      },
    });
  };

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card
        size="small"
        title={t('experiments.title')}
        extra={
          <Space>
            <Button size="small" onClick={() => nav('/experiments')}>{t('experiments.action.backToList')}</Button>
            <Select
              showSearch
              style={{ width: 320 }}
              placeholder={t('experiments.placeholder.pickExperiment')}
              value={expId ?? undefined}
              onChange={(v) => { nav(`/experiments/${v}`); }}
              optionFilterProp="label"
              options={(exps.data?.items ?? []).map((e) => ({
                value: e.id,
                label: `${e.experimentNo} ${e.name}`,
              }))}
            />
            <Button type="primary" ghost {...ro} onClick={() => setCreating(true)}>{t('experiments.action.createExperiment')}</Button>
          </Space>
        }
      >
        {expId == null ? (
          <Empty description={t('experiments.empty.pickExperiment')} />
        ) : (
          <>
            <Space style={{ marginBottom: 12 }} wrap>
              <Button {...ro} onClick={() => setCreateWfOpen(true)}>{t('experiments.action.createWorkflow')}</Button>
              {/* 没有编排任何流程时直接禁用：点了也只会回"实验没有编排任何流程" */}
              <Button
                loading={running} onClick={runExp}
                disabled={ro.disabled || (procs.data?.items?.length ?? 0) === 0}
                title={ro.disabled ? ro.title : ((procs.data?.items?.length ?? 0) === 0
                  ? t('experiments.hint.needCanvasFirst') : undefined)}
              >
                {t('experiments.action.execute')}
              </Button>
              {batchSplit != null && (
                <Button loading={asyncRunning} onClick={runExpAsync}
                  disabled={ro.disabled || (procs.data?.items?.length ?? 0) === 0}
                  title={ro.title ?? t('experiments.title.async')}>
                  {t('experiments.action.async')}
                </Button>
              )}
              <Button {...ro} onClick={openBatchCfg} title={ro.title ?? t('experiments.title.batchCfg')}>
                {batchSplit != null ? t('experiments.batch.label', { n: batchSplit }) : t('experiments.action.batchCfg')}
              </Button>
              {batchSplit != null && (
                // 分批实验的"执行实验"不是一次跑完：按批串行、失败继续后面批。
                // 不说明的话，用户看到执行历史里多出几条 run 会以为系统重复执行了。
                <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                  {t('experiments.hint.batchMode', { n: batchSplit })}
                </Typography.Text>
              )}
              <Button type="primary" ghost onClick={() => nav(`/experiments/${expId}/canvas`)}>
                {t('experiments.action.canvas')}
              </Button>
              {runResult && (
                <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                  {runResult}
                </Typography.Text>
              )}
            </Space>

            {holdBlock && (
              <Alert
                type="warning" showIcon style={{ marginBottom: 8 }}
                message={holdBlock.kind === 'unknown'
                  ? t('experiments.holdBlock.unknownTitle')
                  : t('experiments.holdBlock.title')}
                description={holdBlock.kind === 'unknown'
                  ? (holdBlock.reason || t('experiments.holdBlock.unknownDesc'))
                  : t('experiments.holdBlock.desc', { reason: holdBlock.reason })}
              />
            )}
            <Alert
              type="info" showIcon style={{ marginBottom: 8 }}
              message={t('experiments.alert.mirrorTitle')}
              description={t('experiments.alert.mirrorDesc')}
            />

            <Space style={{ width: '100%', justifyContent: 'space-between', marginBottom: 8 }}>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {t('experiments.hint.procsList')}
              </Typography.Text>
              <Button size="small" type="primary" ghost {...ro} title={ro.title}
                onClick={openProcEdit}>
                {t('experiments.action.editProcs')}
              </Button>
            </Space>

            {(procs.data?.items?.length ?? 0) === 0 && (
              <Alert
                type="warning" showIcon style={{ marginBottom: 8 }}
                message={t('experiments.alert.noFlowTitle')}
                description={t('experiments.alert.noFlowDesc')}
              />
            )}
            <Table
              rowKey="id"
              size="small" pagination={false}
              dataSource={procs.data?.items ?? []}
              columns={[
                { title: t('experiments.col.seq'), dataIndex: 'seq', width: 50 },
                { title: t('experiments.col.workflowName'), dataIndex: 'workflowName' },
                { title: t('experiments.col.workflowCode'), dataIndex: 'workflowCode', width: 120 },
                { title: t('experiments.col.nodeCount'), dataIndex: 'nodeCount', width: 70 },
                {
                  title: t('experiments.col.loopCount'), width: 130,
                  render: (_, r: Proc) => r.loopCount?.trim() ? r.loopCount : '1',
                },
                {
                  title: t('experiments.col.action'), width: 120,
                  render: (_, r: Proc) => (
                    <Space size={2}>
                      <Button size="small" type="link"
                        onClick={() => nav(`/designer/${r.workflowId}`)}>
                        {t('experiments.action.editWorkflow')}
                      </Button>
                    </Space>
                  ),
                },
              ]}
            />

            {/* 实验变量属于**这个**实验：选了实验才显示。
                原来挂在页面顶部，看着像"所有实验共用的全局变量"，误导。 */}
          {/* 实验变量：流程里用 ${name} 引用 */}
          <Card size="small"
            title={<Space><span>{t('experiments.vars.title')}</span><Tag>{vars.length}</Tag></Space>}
            extra={<Space>
              <Button size="small" {...ro} onClick={() => {
                setVars([...vars, { name: '', type: 'number', defaultValue: '' }]);
                setVarsDirty(true);
              }}>{t('experiments.action.addVar')}</Button>
              <Button size="small" type="primary" disabled={ro.disabled || !varsDirty}
                title={ro.title} onClick={saveVars}>{t('experiments.action.saveVars')}</Button>
            </Space>}>
            {vars.length === 0 ? (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {t('experiments.hint.noVars')}
              </Typography.Text>
            ) : (
              <Table size="small" pagination={false}
                rowKey={(_, i) => String(i)} dataSource={vars}
                columns={[
                  { title: t('experiments.col.varName'), width: 180,
                    render: (_, r, i) => (
                      <Input size="small" value={r.name} placeholder="temp"
                        onChange={(e) => {
                          const n = [...vars]; n[i] = { ...r, name: e.target.value };
                          setVars(n); setVarsDirty(true);
                        }} />
                    ) },
                  { title: t('experiments.col.varDefault'), width: 140,
                    render: (_, r, i) => (
                      <Input size="small" value={r.defaultValue} placeholder="37"
                        onChange={(e) => {
                          const n = [...vars]; n[i] = { ...r, defaultValue: e.target.value };
                          setVars(n); setVarsDirty(true);
                        }} />
                    ) },
                  { title: t('experiments.col.varDesc'),
                    render: (_, r, i) => (
                      <Input size="small" value={r.description ?? ''} placeholder={t('experiments.placeholder.varDesc')}
                        onChange={(e) => {
                          const n = [...vars]; n[i] = { ...r, description: e.target.value };
                          setVars(n); setVarsDirty(true);
                        }} />
                    ) },
                  { title: '', width: 60,
                    render: (_, __, i) => (
                      <Button size="small" type="link" danger {...ro}
                        onClick={() => { setVars(vars.filter((_, k) => k !== i)); setVarsDirty(true); }}>
                        {t('experiments.action.deleteVar')}
                      </Button>
                    ) },
                ]} />
            )}
          </Card>

            {/* 现场扣留：实验级暂停的"真身"。
                只显示 run 的 paused 状态会让人以为"某个单子停了"，
                实际是整片现场被闸住 —— 必须让值班员看见闸门本身。 */}
            <Card size="small" style={{ marginTop: 12 }}
              title={<Space><span>{t('experiments.holds.title')}</span>
                {holdsQuery.data?.unknown ? (
                  <Tag color="orange">{t('experiments.holds.unknownTag')}</Tag>
                ) : (
                  <Tag color={(holdsQuery.data?.holds.length ?? 0) > 0 ? 'red' : 'default'}>
                    {holdsQuery.data?.holds.length ?? 0}
                  </Tag>
                )}</Space>}
              extra={<Button size="small" onClick={() => holdsQuery.refetch()}>{t('experiments.action.refresh')}</Button>}>
              {holdsQuery.data?.unknown ? (
                /* 读扣留失败(=UNKNOWN)：调度器 fail-closed 已阻断新派发，
                   若这里显示"没有生效的扣留"就是误导 —— 必须显式提示（ADR-PAUSE-010）。 */
                <Alert type="warning" showIcon
                  message={t('experiments.holds.unknownTitle')}
                  description={holdsQuery.data?.unknownReason ?? t('experiments.holds.unknownDesc')} />
              ) : (holdsQuery.data?.holds.length ?? 0) === 0 ? (
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {t('experiments.holds.none')}
                </Typography.Text>
              ) : (
                <Space direction="vertical" size={8} style={{ width: '100%' }}>
                  <Alert type="error" showIcon
                    message={t('experiments.alert.holdTitle')}
                    description={t('experiments.alert.holdDesc')} />
                  <Table size="small" pagination={false}
                    rowKey="id" dataSource={holdsQuery.data?.holds ?? []}
                    columns={[
                      { title: t('experiments.col.scope'), width: 190,
                        render: (_, h) => (
                          <Space size={4} wrap>
                            <Tag color="red">{holdScopeLabel(h.scope, vocab)}</Tag>
                            {h.scopeId != null && <span>#{h.scopeId}</span>}
                            {h.scope === 'experiment' && h.scopeId === expId && (
                              <Tag color="processing">{t('experiments.hold.currentExp')}</Tag>
                            )}
                          </Space>
                        ) },
                      { title: t('experiments.col.kind'), width: 120,
                        render: (_, h) => holdKindMeta(h.kind, vocab).label },
                      { title: t('experiments.col.reason'), ellipsis: true, render: (_, h) => h.reason ?? '—' },
                      { title: t('experiments.col.since'), width: 170,
                        render: (_, h) => (
                          <span style={{ fontFamily: 'monospace', fontSize: 11 }}>
                            {fmtTime(h.heldAt)}
                          </span>
                        ) },
                    ]} />
                </Space>
              )}
            </Card>

            {/* 分批执行（S3）：只有 batch_split 非空才有这一卡，非分批实验整块不渲染。
                批次以"逻辑批"为主语，runs 是这批的每次尝试 —— 只看 run 列表
                分不清"哪批、第几次、成没成"；重跑按钮就落在对应批这一行。 */}
            {batchSplit != null && (
              <Card size="small" style={{ marginTop: 12 }}
                title={<Space wrap>
                  <span>{t('experiments.batches.title')}</span>
                  <Tag>{batchesQuery.data?.summary.total ?? 0}</Tag>
                  <Tag color="green">{t('experiments.batches.completed', { n: batchesQuery.data?.summary.completed ?? 0 })}</Tag>
                  <Tag color="red">{t('experiments.batches.failed', { n: batchesQuery.data?.summary.failed ?? 0 })}</Tag>
                  <Tag color="blue">{t('experiments.batches.label', { n: batchSplit })}</Tag>
                </Space>}
                extra={<Button size="small" onClick={() => batchesQuery.refetch()}>{t('experiments.action.refresh')}</Button>}>
                {(batchesQuery.data?.items ?? []).length === 0 ? (
                  <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}
                    description={t('experiments.batches.empty')} />
                ) : (
                  <Table<ExperimentBatchItem> size="small" pagination={false}
                    rowKey="id"
                    dataSource={batchesQuery.data?.items ?? []}
                    expandable={{
                      // 尝试历史已随批次列表返回，展开不再发请求：重跑追踪要一眼看完
                      // runs 缺失时按空处理（联调期后端字段可能还没带全），不让整页白屏
                      rowExpandable: (r) => (r.runs ?? []).length > 0,
                      expandedRowRender: (r) => (
                        <Table<ExperimentBatchAttempt> size="small" pagination={false}
                          rowKey={(a) => a.batchRunId}
                          dataSource={r.runs ?? []}
                          columns={[
                            { title: t('experiments.col.attempt'), width: 90,
                              render: (_, a) => t('experiments.attempt.text', { n: a.attemptNo }) },
                            { title: 'run', width: 90,
                              render: (_, a) => (a.runId
                                ? <Button size="small" type="link"
                                    onClick={() => nav(`/runs/${a.runId}`)}>#{a.runId}</Button>
                                : '—') },
                            { title: t('experiments.batch.col.status'), width: 110,
                              render: (_, a) => (
                                <Tag color={statusColor('run', a.status, vocab)}>{statusLabel('run', a.status, vocab)}</Tag>
                              ) },
                            { title: t('experiments.col.start'), width: 170,
                              render: (_, a) => (
                                <span style={{ fontFamily: 'monospace', fontSize: 11 }}>
                                  {fmtTime(a.startedAt)}
                                </span>
                              ) },
                            { title: t('experiments.col.end'), width: 170,
                              render: (_, a) => (
                                <span style={{ fontFamily: 'monospace', fontSize: 11 }}>
                                  {fmtTime(a.finishedAt)}
                                </span>
                              ) },
                            { title: t('experiments.col.error'), ellipsis: true, render: (_, a) => a.error ?? '—' },
                          ]} />
                      ),
                    }}
                    columns={[
                      { title: t('experiments.col.batchNo'), width: 90, render: (_, r) => `#${r.batchNo}` },
                      { title: t('experiments.col.sampleCount'), width: 90, render: (_, r) => r.sampleCount },
                      { title: t('experiments.batch.col.status'), width: 120,
                        render: (_, r) => (
                          <Tag color={statusColor('batch', r.status, vocab)}>{statusLabel('batch', r.status, vocab)}</Tag>
                        ) },
                      { title: t('experiments.col.attemptCount'), width: 100,
                        render: (_, r) => (r.attemptCount > 0 ? t('experiments.attemptCount.text', { n: r.attemptCount }) : '—') },
                      { title: t('experiments.col.currentRun'), width: 110,
                        render: (_, r) => (r.currentRunId
                          ? <Button size="small" type="link"
                              onClick={() => nav(`/runs/${r.currentRunId}`)}>#{r.currentRunId}</Button>
                          : '—') },
                      { title: t('experiments.col.action'), width: 180,
                        render: (_, r) => {
                          // 只对"跑过但没成"的批开放重跑：pending 还没跑过（用执行实验），
                          // completed 不该再跑；这与后端 D4"只允许非 completed 批"同一口径。
                          const rerunnable = r.status === 'failed'
                            || r.status === 'aborted' || r.status === 'paused';
                          if (rerunnable) {
                            return (
                              <Button size="small" type="link" {...ro}
                                onClick={() => doRerunBatch(r.batchNo)}>
                                {t('experiments.action.rerunBatch')}
                              </Button>
                            );
                          }
                          return (
                            <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                              {r.status === 'pending' ? t('experiments.batch.pendingHint') : '—'}
                            </Typography.Text>
                          );
                        } },
                    ]} />
                )}
              </Card>
            )}

            <Card size="small" style={{ marginTop: 12 }}
              title={<Space><span>{t('experiments.runs.title')}</span><Tag>{runsQuery.data?.items.length ?? 0}</Tag></Space>}
              extra={<Button size="small" onClick={() => runsQuery.refetch()}>{t('experiments.action.refresh')}</Button>}>
              {(runsQuery.data?.items ?? []).length === 0 ? (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('experiments.empty.noRuns')} />
              ) : (
                <Table<ExpRun> size="small" pagination={false}
                  rowKey="id"
                  dataSource={runsQuery.data?.items ?? []}
                  expandable={{
                    // 点开就能看到"哪几步跑了、哪几步没跑"——这是用户最想知道的
                    expandedRowRender: (r) => <RunSteps runId={r.id} nav={nav} />,
                    // **默认展开**：进实验页就直接看到每个步骤与它的「打开流程图」入口。
                    // 以前要点行左侧的「+」才展开 ⇒ 入口藏了两层 ⇒ 现场反馈
                    // "在实验里点不进去、看不到流程状态"（2026-11-11）。
                    // 实验一般只有 1~3 条执行记录 ⇒ 展开成本可接受。
                    defaultExpandedRowKeys: (runsQuery.data?.items ?? []).map((r) => r.id),
                  }}
                  columns={[
                    { title: 'run', dataIndex: 'id', width: 80,
                      render: (v: number) => `#${v}` },
                    { title: t('experiments.col.status'), dataIndex: 'status', width: 140,
                      render: (v: string, r) => (
                        <Space size={4} wrap>
                          <Tag color={statusColor('run', v, vocab)}>{statusLabel('run', v, vocab)}</Tag>
                          {r.controlState && r.controlState !== 'NONE' && (
                            <Tag color={statusColor('control', r.controlState, vocab)}>
                              {statusLabel('control', r.controlState, vocab)}
                            </Tag>
                          )}
                          {r.stopRequested && v !== 'aborted' && (
                            <Tag color="volcano">{t('experiments.tag.stopping')}</Tag>
                          )}
                        </Space>
                      ) },
                    { title: t('experiments.col.progress'), width: 100,
                      render: (_, r) => `${r.currentSeq}/${r.expandedSteps}` },
                    { title: t('experiments.col.duration'), width: 90,
                      render: (_, r) => (r.durationSec != null ? `${r.durationSec}s` : '—') },
                    { title: t('experiments.col.start'), width: 170,
                      render: (_, r) => (
                        <span style={{ fontFamily: 'monospace', fontSize: 11 }}>
                          {fmtTime(r.startedAt)}
                        </span>
                      ) },
                    { title: t('experiments.col.pauseReason'), width: 200, ellipsis: true,
                      render: (_, r) => (r.pauseKind ? (
                        <Space size={4}>
                          <Tag color={pauseKindMeta(r.pauseKind, vocab).color}>
                            {pauseKindMeta(r.pauseKind, vocab).label}
                          </Tag>
                          <span>{r.pauseReason ?? ''}</span>
                        </Space>
                      ) : '—') },
                    { title: t('experiments.runs.col.error'), ellipsis: true,
                      render: (_, r) => r.error ?? '—' },
                    {
                      title: t('experiments.col.action'), width: 170,
                      render: (_, r) => {
                        // 暂停：只有还在活动（排队/运行）的才有意义；
                        // 恢复：paused 是暂停中、failed 是处置完要继续跑；
                        // 停止：除终态外都能停（运行中走"停止请求"，其余直接终止）。
                        const live = r.status === 'running' || r.status === 'queued';
                        // S-04-B：暂停请求已受理、还没收敛时不再重复给「暂停」入口
                        const pendingPause = r.controlState === 'PAUSE_REQUESTED'
                          || r.controlState === 'PAUSING';
                        const resumable = r.status === 'paused' || r.status === 'failed';
                        const stoppable = !['completed', 'aborted', 'cancelled']
                          .includes(r.status);
                        if (!live && !resumable && !stoppable) {
                          return <Typography.Text type="secondary">—</Typography.Text>;
                        }
                        return (
                          <Space size={2} wrap>
                            {live && !pendingPause && (
                              <Button size="small" type="link" {...ro}
                                onClick={() => setPauseFor({
                                  runId: r.id, scope: 'island', reason: '',
                                })}>
                                {t('experiments.action.pause')}
                              </Button>
                            )}
                            {resumable && (
                              <Button size="small" type="link" loading={acting} {...ro}
                                onClick={() => doResume(r.id)}>
                                {t('experiments.action.resume')}
                              </Button>
                            )}
                            {stoppable && (
                              <Button size="small" type="link" danger {...ro}
                                onClick={() => doStop(r.id)}>
                                {t('experiments.action.stop')}
                              </Button>
                            )}
                          </Space>
                        );
                      },
                    },
                  ]} />
              )}
            </Card>

            <Typography.Paragraph type="secondary" style={{ fontSize: 11, marginTop: 10, marginBottom: 0 }}>
              {t('experiments.note.sequential')}
            </Typography.Paragraph>
          </>
        )}
      </Card>

      <Modal
        open={creating}
        title={t('experiments.modal.createExpTitle')}
        okText={t('experiments.modal.createOk')}
        confirmLoading={busyCreate}
        onOk={createExp}
        onCancel={() => setCreating(false)}
        width={560}
      >
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <div>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('experiments.field.expName')}</Typography.Text>
            <Input value={newName} placeholder={t('experiments.placeholder.expName')}
              onChange={(e) => setNewName(e.target.value)} />
          </div>
          <div>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {t('experiments.field.expCode')}
            </Typography.Text>
            <Input value={newCode} placeholder={t('experiments.placeholder.expCode')}
              onChange={(e) => setNewCode(e.target.value)} />
          </div>
          <div>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('experiments.field.island')}</Typography.Text>
            <Select
              allowClear style={{ width: '100%' }} placeholder={t('experiments.placeholder.island')}
              value={newIsland} onChange={setNewIsland}
              showSearch optionFilterProp="label"
              options={(islands.data?.islands ?? []).map((i) => ({
                value: i.id, label: t('experiments.islandOption', { code: i.code, name: i.name, n: i.devices?.length ?? 0 }),
              }))}
            />
          </div>
          <div>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {t('experiments.hint.attachFlows')}
            </Typography.Text>
            <Select
              mode="multiple" allowClear style={{ width: '100%' }}
              placeholder={t('experiments.placeholder.searchFlow')}
              value={newWfs} onChange={setNewWfs}
              optionFilterProp="label"
              options={(wfs.data?.items ?? []).map((w) => ({
                value: w.id, label: `${w.name} (${w.code})`,
              }))}
            />
            <div style={{ fontSize: 11, color: '#8b93a7', marginTop: 4 }}>
              {t('experiments.hint.linearChain')}
            </div>
          </div>
        </Space>
      </Modal>
      <Modal
        open={createWfOpen}
        title={t('experiments.modal.createWfTitle')}
        okText={t('experiments.modal.createWfOk')}
        confirmLoading={busyWf}
        onOk={createWorkflow}
        onCancel={() => setCreateWfOpen(false)}
      >
        <Alert
          type="info" showIcon style={{ marginBottom: 12 }}
          message={t('experiments.alert.createWfTitle')}
          description={t('experiments.alert.createWfDesc')}
        />
        <Input
          placeholder={t('experiments.placeholder.workflowName')}
          value={newWfName}
          onChange={(e) => setNewWfName(e.target.value)}
          onPressEnter={createWorkflow}
        />
      </Modal>

      {/* 暂停必须先问作用域：默认整座岛（与接口默认一致），单实验是更轻的选择 */}
      <Modal
        open={pauseFor != null}
        title={t('experiments.modal.pauseTitle', { id: pauseFor?.runId ?? '' })}
        okText={t('experiments.modal.pauseOk')}
        confirmLoading={acting}
        onOk={doPause}
        onCancel={() => setPauseFor(null)}
        width={520}
      >
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Alert type="warning" showIcon
            message={t('experiments.alert.pauseTitle')}
            description={t('experiments.alert.pauseDesc')} />
          <div>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('experiments.field.scope')}</Typography.Text>
            <Select style={{ width: '100%' }} value={pauseFor?.scope ?? 'island'}
              onChange={(v: HoldScope) => setPauseFor((p) => (p ? { ...p, scope: v } : p))}
              options={[
                { value: 'island', label: t('experiments.scope.island') },
                { value: 'experiment', label: t('experiments.scope.experiment') },
                { value: 'site', label: t('experiments.scope.site') },
              ]} />
            <div style={{ fontSize: 11, color: '#8b93a7', marginTop: 4 }}>
              {t('experiments.hint.scopeDefault')}
              {t('experiments.hint.scopeFallback')}
            </div>
          </div>
          <div>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {t('experiments.field.reason')}
            </Typography.Text>
            <Input.TextArea rows={2} value={pauseFor?.reason ?? ''}
              placeholder={t('experiments.placeholder.reason')}
              onChange={(e) => setPauseFor((p) => (p ? { ...p, reason: e.target.value } : p))} />
          </div>
        </Space>
      </Modal>

      {/* 分批设置（S3.1）：以前 batch_split 只能改库，页面上配不了 */}
      <Modal
        open={batchCfgOpen}
        title={t('experiments.modal.batchTitle')}
        okText={t('experiments.modal.batchOk')}
        confirmLoading={batchSaving}
        onOk={saveBatchCfg}
        onCancel={() => setBatchCfgOpen(false)}
        width={520}
      >
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Alert type="info" showIcon message={t('experiments.alert.batchTitle')}
            description={t('experiments.alert.batchDesc')} />
          <div>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {t('experiments.field.batchSize')}
            </Typography.Text>
            <InputNumber min={1} style={{ width: '100%' }} value={batchCfgValue}
              placeholder={t('experiments.placeholder.batchSize')}
              onChange={(v) => setBatchCfgValue(typeof v === 'number' ? v : null)} />
            <div style={{ fontSize: 11, color: '#8b93a7', marginTop: 4 }}>
              {t('experiments.batch.current', { text: batchSplit != null ? t('experiments.batch.currentSet', { n: batchSplit }) : t('experiments.batch.currentOff') })}
            </div>
          </div>
        </Space>
      </Modal>

      {/* 编排编辑：把已建好的流程按顺序加进这个实验（2026-11-11 新增） */}
      <Modal
        title={t('experiments.modal.procsTitle')}
        open={procEditOpen}
        onOk={saveProcs}
        confirmLoading={procSaving}
        onCancel={() => setProcEditOpen(false)}
        okText={t('experiments.modal.procsOk')}
        width={640}
      >
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Alert type="info" showIcon message={t('experiments.alert.procsTitle')}
            description={t('experiments.alert.procsDesc')} />
          <Space style={{ width: '100%' }}>
            <Select mode="multiple" style={{ minWidth: 420 }} value={procPick}
              placeholder={t('experiments.placeholder.pickWorkflows')}
              showSearch optionFilterProp="label" onChange={setProcPick}
              options={(wfs.data?.items ?? []).map((w) => ({
                value: w.id, label: `${w.name}（${w.code}）`,
              }))} />
            <Button onClick={addPicked} disabled={procPick.length === 0}>
              {t('experiments.action.addPicked')}
            </Button>
          </Space>
          <Table size="small" pagination={false} rowKey={(_, i) => String(i)}
            dataSource={procOrder}
            locale={{ emptyText: t('experiments.procs.empty') }}
            columns={[
              { title: t('experiments.col.seq'), width: 50, render: (_: unknown, __: unknown, i: number) => i + 1 },
              {
                title: t('experiments.col.workflowName'),
                render: (wid: number) => {
                  const w = (wfs.data?.items ?? []).find((x) => x.id === wid);
                  return w ? `${w.name}（${w.code}）` : `#${wid}`;
                },
              },
              {
                title: t('experiments.col.action'), width: 190,
                render: (_: unknown, __: unknown, i: number) => (
                  <Space size={2}>
                    <Button size="small" type="link" disabled={i === 0}
                      onClick={() => moveProc(i, -1)}>{t('experiments.action.moveUp')}</Button>
                    <Button size="small" type="link" disabled={i === procOrder.length - 1}
                      onClick={() => moveProc(i, 1)}>{t('experiments.action.moveDown')}</Button>
                    <Button size="small" type="link" danger
                      onClick={() => removeProc(i)}>{t('experiments.action.removeFlow')}</Button>
                  </Space>
                ),
              },
            ]} />
        </Space>
      </Modal>
    </Space>
  );
}
