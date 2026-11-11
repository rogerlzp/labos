import { useMemo } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Card, Space, Tag, Typography } from 'antd';
import { api, apiFetch } from '../api/client';
import RunGraph from '../components/RunGraph';
import RunCommands from '../components/RunCommands';
import { statusColor, statusLabel, useStatusVocabulary } from '../utils/status';
import { useT } from '../i18n';

/**
 * **只读**的运行态流程页（与流程设计器完全是两回事）。
 *
 * <p>为什么单独开一页：设计器是**编辑**页面（Modeler + palette + 保存 + 历史恢复），
 * 而运行态要的是**看一眼这个流程这次跑成什么样** —— 只读、只着色、不给任何改图的入口。
 * 两者混在一页会出两个问题：① 现场以为能改（其实改了正在跑的流程是危险的）；
 * ② 编辑控件会盖住图、抢视线。
 *
 * <p>两条路由：
 * <ul>
 *   <li>{@code /runs/:runId/flow} —— 有这次执行 ⇒ 图按执行状态着色，每 2 秒刷新；</li>
 *   <li>{@code /workflows/:workflowId/flow} —— 没有指定某次执行 ⇒ **自动取最近一次执行**
 *       （{@code GET /api/workflows/{id}/runs?limit=1}）来着色并继续跟踪；
 *       这条流程**一次都没跑过**时才只画流程定义，并说明"没有执行记录"。</li>
 * </ul>
 */
export default function RunFlowViewPage() {
  const t = useT();
  const vocab = useStatusVocabulary();
  const nav = useNavigate();
  const params = useParams();
  const runId = params.runId ? Number(params.runId) : undefined;
  const workflowId = params.workflowId ? Number(params.workflowId) : undefined;

  const runQ = useQuery({
    queryKey: ['run-flow', runId],
    queryFn: () => apiFetch(`/api/runs/${runId}`).then((r) => r.json()) as Promise<{
      run?: { id?: number; status?: string; workflowId?: number; runNo?: string };
    }>,
    enabled: runId != null,
  });

  /**
   * 只有 workflowId、没 runId 时 ⇒ **自动取最近一次执行**。
   *
   * <p>为什么必须取：这条路由是从「流程库」点进来的，现场打开就是为了看
   * 「这条流程现在跑到哪了」。不取的话页面只有一张静态流程图 ——
   * 节点全白、没有状态、也没有往下走的迹象，看起来像"根本没执行"。
   * （2026-11-11 用户现场：流程正在跑，页面却"看不到状态、没有往下走"。）</p>
   */
  const latestQ = useQuery({
    queryKey: ['wf-latest-run', workflowId],
    queryFn: () => apiFetch(`/api/workflows/${workflowId}/runs?limit=1`).then((r) => r.json()) as Promise<{
      items?: Array<{ id: number; runNo?: string; status?: string; startedAt?: string }>;
    }>,
    enabled: runId == null && workflowId != null,
    // 还在跑就继续找最新一次；已终态就不必再问了
    refetchInterval: (q) => {
      const s = (q.state.data as { items?: Array<{ status?: string }> } | undefined)
        ?.items?.[0]?.status;
      return s && ['completed', 'failed', 'aborted', 'cancelled'].includes(s) ? false : 4000;
    },
  });
  const latest = latestQ.data?.items?.[0];
  /** 真正要展示的那次执行：指定了就用指定的，没指定就用最近一次。 */
  const shownRunId = runId ?? latest?.id;

  // 只有 workflowId、没 runId 时，去流程库把流程的元信息取回来（名字/编号）
  const wfQ = useQuery({
    queryKey: ['wf-flow-meta', workflowId],
    queryFn: () => api.workflow(workflowId as number).then(
      (d) => d as unknown as { workflow?: { name?: string }; name?: string }),
    enabled: runId == null && workflowId != null,
  });

  const run = runQ.data?.run;
  const shownStatus = run?.status ?? latest?.status;
  const title = useMemo(() => {
    if (run?.runNo) return `#${run.id ?? runId} · ${run.runNo}`;
    if (latest?.runNo) {
      return `${wfQ.data?.workflow?.name ?? wfQ.data?.name ?? `#${workflowId}`}`
        + ` · 最近一次 #${latest.id}`;
    }
    if (wfQ.data) return `${wfQ.data.workflow?.name ?? wfQ.data.name ?? `#${workflowId}`}`;
    return runId ? `#${runId}` : `#${workflowId}`;
  }, [run, latest, wfQ.data, runId, workflowId]);

  return (
    <div>
      <Card size="small" style={{ marginBottom: 12 }}
        title={<Space size={8}>
          <span>{t('runFlowView.title')}</span>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>{title}</Typography.Text>
          {shownStatus && (
            <Tag color={statusColor('run', shownStatus, vocab)}>
              {statusLabel('run', shownStatus, vocab)}
            </Tag>
          )}
        </Space>}
        extra={<Space>
          {shownRunId != null && (
            <Button size="small" onClick={() => nav(`/runs/${shownRunId}`)}>
              {t('runFlowView.openRunDetail')}
            </Button>
          )}
          <Button size="small" onClick={() => nav('/runs')}>{t('runFlowView.backToRuns')}</Button>
        </Space>}>
        <Space direction="vertical" size={8} style={{ width: '100%' }}>
          <Alert type="info" showIcon
            message={t('runFlowView.readonly')}
            description={shownRunId == null
              ? t('runFlowView.noRunDesc')
              : t('runFlowView.desc')} />
          <RunGraph runId={shownRunId} workflowId={workflowId} />
          {shownRunId != null && (
            <Card size="small" title={t('runCommands.title')}>
              <RunCommands runId={shownRunId} />
            </Card>
          )}
        </Space>
      </Card>
    </div>
  );
}
