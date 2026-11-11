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
 *   <li>{@code /workflows/:workflowId/flow} —— 这一步**失败在建执行单之前**、没有 run
 *       ⇒ 只画流程定义，并说明"这一步没有执行记录"。</li>
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

  // 只有 workflowId、没 runId 时，去流程库把流程的元信息取回来（名字/编号）
  const wfQ = useQuery({
    queryKey: ['wf-flow-meta', workflowId],
    queryFn: () => api.workflow(workflowId as number).then(
      (d) => d as unknown as { workflow?: { name?: string }; name?: string }),
    enabled: runId == null && workflowId != null,
  });

  const run = runQ.data?.run;
  const title = useMemo(() => {
    if (run?.runNo) return `#${run.id ?? runId} · ${run.runNo}`;
    if (wfQ.data) return `${wfQ.data.workflow?.name ?? wfQ.data.name ?? `#${workflowId}`}`;
    return runId ? `#${runId}` : `#${workflowId}`;
  }, [run, wfQ.data, runId, workflowId]);

  return (
    <div>
      <Card size="small" style={{ marginBottom: 12 }}
        title={<Space size={8}>
          <span>{t('runFlowView.title')}</span>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>{title}</Typography.Text>
          {run?.status && (
            <Tag color={statusColor('run', run.status, vocab)}>
              {statusLabel('run', run.status, vocab)}
            </Tag>
          )}
        </Space>}
        extra={<Space>
          {runId != null && (
            <Button size="small" onClick={() => nav(`/runs/${runId}`)}>
              {t('runFlowView.openRunDetail')}
            </Button>
          )}
          <Button size="small" onClick={() => nav('/runs')}>{t('runFlowView.backToRuns')}</Button>
        </Space>}>
        <Space direction="vertical" size={8} style={{ width: '100%' }}>
          <Alert type="info" showIcon
            message={t('runFlowView.readonly')}
            description={runId == null
              ? t('runFlowView.noRunDesc')
              : t('runFlowView.desc')} />
          <RunGraph runId={runId} workflowId={workflowId} />
          <Card size="small" title={t('runCommands.title')}>
            <RunCommands runId={runId as number} />
          </Card>
        </Space>
      </Card>
    </div>
  );
}
