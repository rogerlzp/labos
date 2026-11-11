import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Alert, Button, Card, Empty, Input, Modal, Select, Space, Table, Tag,
  Typography, message,
} from 'antd';
import { apiFetch, api, type HoldScope } from '../api/client';
import RunVariablesCard from '../components/RunVariablesCard';
import {
  holdScopeLabel, pauseKindMeta, statusColor, statusLabel, useStatusVocabulary,
} from '../utils/status';
import { useReadOnly } from '../auth';
import { useT } from '../i18n';

/** 一次实验执行的逐步明细：跑了哪些、哪些没跑。 */
export interface ExpStep {
  seq: number; loopIndex: number;
  workflowId: number; workflowName?: string | null;
  status: string; runId?: number | null; error?: string | null;
}

/** 一条实验执行记录（experiment_run）。 */
export interface ExpRun {
  id: number;
  status: string;
  currentSeq?: number;
  expandedSteps?: number;
  durationSec?: number | null;
  startedAt?: string | null;
  error?: string | null;
  pauseKind?: string | null;
  pauseReason?: string | null;
  controlState?: string | null;
  stopRequested?: boolean;
}

/**
 * 实验**用到的**变量（编排流程声明的流程级变量 + 实验级覆盖，同名合并）。
 *
 * <p>为什么要这个视图：以前实验页只显示"实验自己写的那批"变量，
 * 于是流程里声明了变量、流程里也真的用 {@code ${name}} 引用它，实验页上却一条都看不到 ——
 * 现场看着流程引用 {@code ${VAR001}}，在实验里找不到它、更没法改它的值。
 *
 * <p><b>改值的语义</b>：改的是**这个实验的取值**（实验级覆盖流程级），
 * 不动流程本身 —— 别的实验用同一条流程时不受影响。
 * 下一次启动实验时生效（正在跑的那一次不受影响）。
 */
export interface UsedVar {
  name: string;
  type?: string | null;
  /** 流程里声明的初值（改它要去设计器） */
  defaultValue?: string | null;
  description?: string | null;
  /** 这个实验实际会用的值 */
  value?: string | null;
  /** 来源：workflow = 流程声明；experiment = 实验覆盖 */
  scope: 'workflow' | 'experiment';
  overridden: boolean;
  workflowId?: number | null;
  workflowName?: string | null;
}

/**
 * 时间显示（本地时区）。
 *
 * <p>后端目前有两套时间串：多数接口给 JDBC Timestamp 的**本地**时间
 * （`2026-11-03 18:50:40.0`），扣留接口给的是带时区的 ISO（`…T10:50:40Z`）。
 * 一律 `slice(0,19)` 会把 UTC 当本地显示 —— 扣留时间差 8 小时，
 * 值班员会误判"已经停了多久"。这里两种都能扛，且不做无谓换算。
 */
export function fmtTime(s?: string | null): string {
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
 * 展开一行「执行记录」看到的步骤表。
 *
 * <p>为什么要单独查：`experiment_run_step` 只在**步骤开始时**插行，
 * 前面一步失败后后面的步骤根本没有行 —— 只看主表会以为"实验就 1 步"。
 * 后端按 experiment_process × loop_count 展开出计划步骤，没执行过的标 `pending`。
 */
export function RunSteps({ runId, nav }: { runId: number; nav: (p: string) => void }) {
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
  // 这些步骤各自对应哪条流程级 run（变量表挂在 run 上，不是挂在实验上）
  const runIdsOfSteps = Array.from(new Set(
    d.items.map((r) => r.runId).filter((x): x is number => typeof x === 'number' && x > 0),
  ));
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
      {/* 运行变量：这一步真正在用的变量**当前值**（并能就地改）。
          为什么放在这里而不是只放运行详情页：现场盯着的是"这个实验"，
          变量值要能就地看就地改，不用先点进某个 run 再跳回来。 */}
      {runIdsOfSteps.length > 0 && (
        <Space direction="vertical" size={8} style={{ width: '100%', marginTop: 8 }}>
          {runIdsOfSteps.map((rid) => (
            <RunVariablesCard
              key={rid} runId={rid}
              title={t('experiments.vars.runVarsTitle', { id: rid })}
              style={{ marginBottom: 0 }}
            />
          ))}
        </Space>
      )}
    </Space>
  );
}

/**
 * 实验**用到的**变量：看得见、改得动。
 *
 * <p><b>为什么要有这张卡</b>：流程里声明了变量（而且流程里真的用 {@code ${name}}
 * 引用它），但以前实验页只显示"实验自己写的那批" ⇒ 现场看着流程引用
 * {@code ${VAR001}}，在实验里找不到它、更没法改它的值。
 */
export function ExperimentUsedVars({ expId }: { expId: number }) {
  const t = useT();
  const qc = useQueryClient();
  const ro = useReadOnly();
  const [editing, setEditing] = useState<string | null>(null);
  const [draft, setDraft] = useState('');

  const q = useQuery({
    queryKey: ['exp-used-vars', expId],
    queryFn: () => apiFetch(`/api/experiments/${expId}/used-variables`)
      .then((r) => r.json()) as Promise<{ total: number; items: UsedVar[] }>,
    enabled: Number.isFinite(expId),
  });
  const items = q.data?.items ?? [];

  const save = async (v: UsedVar) => {
    try {
      const r = await apiFetch(`/api/experiments/${expId}/variables/${encodeURIComponent(v.name)}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: v.name, value: draft, type: v.type ?? 'number' }),
      });
      const j = await r.json().catch(() => ({}));
      if (!r.ok || j.ok === false) {
        message.error(j.error ?? t('experiments.error.saveFailed'));
        return;
      }
      message.success(t('experiments.toast.varSet', { name: v.name, value: draft }));
      setEditing(null);
      qc.invalidateQueries({ queryKey: ['exp-used-vars', expId] });
      qc.invalidateQueries({ queryKey: ['exp-vars', expId] });
    } catch (e) {
      message.error(String(e));
    }
  };

  return (
    <Card size="small" style={{ marginTop: 12 }}
      title={<Space>
        <span>{t('experiments.usedVars.title')}</span>
        <Tag>{items.length}</Tag>
      </Space>}>
      <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
        {t('experiments.usedVars.hint')}
      </Typography.Paragraph>
      {items.length === 0 ? (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {t('experiments.usedVars.empty')}
        </Typography.Text>
      ) : (
        <Table<UsedVar>
          size="small" pagination={false} rowKey="name"
          dataSource={items}
          columns={[
            {
              title: t('experiments.col.varName'), dataIndex: 'name', width: 180,
              render: (v: string) => <strong>{v}</strong>,
            },
            {
              // 这个实验实际会用的值（实验级覆盖优先，否则用流程初值）
              title: t('experiments.usedVars.col.value'), dataIndex: 'value', width: 180,
              render: (v: string | null, r) => (
                editing === r.name ? (
                  <Input size="small" autoFocus value={draft}
                    onChange={(e) => setDraft(e.target.value)}
                    onPressEnter={() => save(r)}
                    onBlur={() => setEditing(null)}
                    style={{ width: '100%' }} />
                ) : (
                  <Space size={4}>
                    <span style={{ fontFamily: 'monospace', color: v ? '#1677ff' : '#94a3b8' }}>
                      {v || t('experiments.usedVars.noValue')}
                    </span>
                    {!ro.disabled && (
                      <Button size="small" type="link" style={{ padding: '0 4px' }}
                        onClick={() => { setEditing(r.name); setDraft(v ?? ''); }}>
                        {t('runVars.edit')}
                      </Button>
                    )}
                  </Space>
                )
              ),
            },
            {
              title: t('experiments.usedVars.col.initial'), dataIndex: 'defaultValue', width: 140,
              render: (v: string | null) => (
                v == null || v === ''
                  ? <Typography.Text type="secondary">{t('runVars.noDefault')}</Typography.Text>
                  : <span style={{ fontFamily: 'monospace' }}>{v}</span>
              ),
            },
            {
              // 来源：流程声明的还是实验覆盖的
              title: t('experiments.usedVars.col.source'), width: 150,
              render: (_, r) => (
                r.scope === 'experiment'
                  ? <Tag color="blue">{t('experiments.usedVars.src.experiment')}</Tag>
                  : <Tag>{t('experiments.usedVars.src.workflow')}</Tag>
              ),
            },
          ]}
        />
      )}
    </Card>
  );
}

/**
 * **实验执行记录**面板：这个实验跑过哪几次、每次跑到哪、能不能干预。
 *
 * <p><b>为什么独立成页</b>：实验的**配置**（编排、变量、批次）与它的**执行记录**
 * 是两回事 —— 混在一个页面里，配置被历史记录挤到下面去，现场看着很乱
 * （2026-11-11 用户现场："在一个实验里面，不需要把执行记录也放到下面"）。
 * ⇒ 这一块搬到独立路由 {@code /experiments/:id/runs}，实验页只留一个入口。
 */
export function ExperimentRunsPanel({ expId }: { expId: number }) {
  const t = useT();
  const nav = useNavigate();
  const vocab = useStatusVocabulary();
  const ro = useReadOnly();
  const [acting, setActing] = useState(false);
  const [pauseFor, setPauseFor] = useState<{
    runId: number; scope: HoldScope; reason: string;
  } | null>(null);

  const runsQuery = useQuery({
    queryKey: ['exp-runs', expId],
    queryFn: async () => {
      const r = await apiFetch(`/api/experiments/${expId}/runs`);
      return r.json() as Promise<{ items: ExpRun[] }>;
    },
    enabled: Number.isFinite(expId),
    // **列表要自己会刷新**：执行刚起来时这一页还没有那条执行单，
    // 不轮询的话现场只能手动按「刷新」才看得到自己刚点的实验 ——
    // 同一个毛病（"每次要刷新页面才看得到"）在执行记录页上又犯了一次。
    //
    // ⚠ **固定轮询，不能"有在跑的才轮"**：那是个鸡生蛋问题 ——
    //   刚点执行时列表里还没有"在跑"的行 ⇒ 判定为"不用轮" ⇒
    //   于是新执行单永远不会出现。实测踩过。
    refetchInterval: 5000,
  });

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
      await runsQuery.refetch();
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
      onOk: async () => {
        setActing(true);
        try {
          const r = await api.stopExperimentRun(runId);
          message.warning(t('experiments.toast.stopRequested', { id: r.runId, status: r.status }));
          await runsQuery.refetch();
        } catch (e) {
          message.error(t('experiments.error.stopFailed', { msg: (e as Error).message }));
        } finally {
          setActing(false);
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
      await runsQuery.refetch();
    } catch (e) {
      message.error(t('experiments.error.resumeFailed', { msg: (e as Error).message }));
    } finally {
      setActing(false);
    }
  };

  return (
    <>
      <Card size="small"
        title={<Space><span>{t('experiments.runs.title')}</span>
          <Tag>{runsQuery.data?.items.length ?? 0}</Tag></Space>}
        extra={<Button size="small" onClick={() => runsQuery.refetch()}>
          {t('experiments.action.refresh')}
        </Button>}>
        {(runsQuery.data?.items ?? []).length === 0 ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('experiments.empty.noRuns')} />
        ) : (
          <Table<ExpRun> size="small" pagination={false}
            rowKey="id"
            dataSource={runsQuery.data?.items ?? []}
            expandable={{
              // 点开就能看到"哪几步跑了、哪几步没跑"——这是用户最想知道的
              expandedRowRender: (r) => <RunSteps runId={r.id} nav={nav} />,
              // **默认展开**：进这一页就直接看到每个步骤与它的「打开流程图」入口。
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

      {/* 暂停弹窗：选扣留范围（岛级 / 实验级）+ 原因 */}
      <Modal
        open={pauseFor != null}
        title={t('experiments.modal.pauseTitle', { id: pauseFor?.runId ?? '' })}
        okText={t('experiments.modal.pauseOk')}
        confirmLoading={acting}
        onOk={doPause}
        onCancel={() => setPauseFor(null)}
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
    </>
  );
}

/**
 * **实验执行记录**页（独立路由 `/experiments/:id/runs`）。
 *
 * <p>为什么单独一页：实验的**配置**与它的**执行记录**是两回事。
 * 混在一个页面里，配置被历史记录挤到下面，现场看着很乱
 * （2026-11-11 用户现场："实验列表，和实验执行列表，需要分开来"）。
 */
export default function ExperimentRunsPage() {
  const t = useT();
  const nav = useNavigate();
  const params = useParams();
  const expId = Number(params.id);

  const exps = useQuery({
    queryKey: ['experiments'],
    queryFn: () => apiFetch('/api/experiments').then((r) => r.json()) as Promise<{
      items: Array<{ id: number; name: string; experimentNo?: string }>;
    }>,
  });
  const exp = exps.data?.items?.find((e) => e.id === expId);

  return (
    <div style={{ padding: 16 }}>
      <Card size="small" style={{ marginBottom: 12 }}
        title={<Space>
          <span>{t('experiments.runs.pageTitle')}</span>
          {exp && (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {exp.name}
            </Typography.Text>
          )}
        </Space>}
        extra={<Space>
          <Button size="small" onClick={() => nav(`/experiments/${expId}`)}>
            {t('experiments.runs.backToExperiment')}
          </Button>
          <Button size="small" onClick={() => nav('/experiments')}>
            {t('experiments.runs.backToList')}
          </Button>
        </Space>}>
        <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
          {t('experiments.runs.pageHint')}
        </Typography.Paragraph>
      </Card>

      {Number.isFinite(expId) && <ExperimentRunsPanel expId={expId} />}
    </div>
  );
}
