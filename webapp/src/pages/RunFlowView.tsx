import { useMemo } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Card, Space, Table, Tag, Tooltip, Typography } from 'antd';
import { api, apiFetch } from '../api/client';
import RunGraph from '../components/RunGraph';
import RunCommands from '../components/RunCommands';
import RunVariablesCard from '../components/RunVariablesCard';
import { localizedNeutralLabel } from '../bpmn/nodeRoleLabel';
import { methodLabel } from '../bpmn/builtinLabels';
import { statusColor, statusLabel, useStatusVocabulary } from '../utils/status';
import { useT } from '../i18n';

/** 节点清单的一行（只读流程页用）。 */
interface FlowNode {
  nodeKey: string;
  name: string;
  status: string;
  /** 内置逻辑节点的一句人话（"等待 gate = open（超时 120 秒）"） */
  note?: string | null;
  method?: string | null;
  resourceCode?: string | null;
  level?: number;
}

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

  /**
   * 这条执行的状态 + **节点清单**（含每个节点"在做什么"的人话说明）。
   *
   * <p>为什么这个查询要自己轮询：页面上的图（{@code RunGraph}）是**另一个组件**、
   * 它自己每 2 秒刷一次；这里的 run 状态与节点清单如果不跟着刷，
   * 就会出现「图在变、说明不变」的割裂感（2026-11-11 现场：图上节点在跑，
   * 下面却看不到任何说明）。
   */
  const runQ = useQuery({
    queryKey: ['run-flow', runId],
    queryFn: () => apiFetch(`/api/runs/${runId}`).then((r) => r.json()) as Promise<{
      run?: { id?: number; status?: string; workflowId?: number; runNo?: string };
      nodes?: FlowNode[];
    }>,
    enabled: runId != null,
    refetchInterval: (q) => {
      const s = q.state.data?.run?.status;
      return s === 'running' || s === 'paused' ? 2000 : false;
    },
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
  // 顶层节点（level=0）：逻辑节点与设备节点都在内
  const flowNodes = (runQ.data?.nodes ?? []).filter((n) => (n.level ?? 0) === 0);
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

          {/* 节点清单：图上只看得见形状和颜色，看不见「这个节点在做什么」。
              逻辑节点（延时/等待/赋值/表达式）尤其如此 —— 节点名是 __wait 这种内部名，
              不写出来就完全不知道它在等什么。 */}
          {shownRunId != null && flowNodes.length > 0 && (
            <Card size="small" title={t('runFlowView.nodesTitle')}>
              <Table<FlowNode>
                size="small" pagination={false} rowKey="nodeKey"
                dataSource={flowNodes}
                columns={[
                  {
                    title: t('runFlowView.col.node'), dataIndex: 'name', width: 200,
                    render: (v: string) => (
                      <strong>{localizedNeutralLabel(v) ?? v}</strong>
                    ),
                  },
                  {
                    title: t('runFlowView.col.status'), dataIndex: 'status', width: 110,
                    render: (v: string) => (
                      <Tag color={statusColor('node', v, vocab)}>
                        {statusLabel('node', v, vocab)}
                      </Tag>
                    ),
                  },
                  {
                    // 这一列就是「这个节点在做什么」：逻辑节点给人话，
                    // 设备节点给方法 + 设备编号。
                    title: t('runFlowView.col.what'), dataIndex: 'note', ellipsis: true,
                    render: (v: string | null | undefined, r) => {
                      const note = v ?? '';
                      const what = note || r.method || '—';
                      return (
                        <Tooltip title={what}>
                          <Typography.Text style={{ fontSize: 12 }}>{what}</Typography.Text>
                        </Tooltip>
                      );
                    },
                  },
                  {
                    // 逻辑节点没有设备 ⇒ 显示它的中文标签（"等待"），
                    // 不是内部名 "__wait"（现场读不出来）。
                    title: t('runFlowView.col.device'), dataIndex: 'resourceCode', width: 130,
                    render: (v: string | null, r) => v ?? methodLabel(r.method),
                  },
                ]}
              />
            </Card>
          )}

          {/* 运行变量：这一页是现场盯着的那一页 ⇒ 变量也要在这儿看得见、改得动。
              改的是**这条执行**的变量表（不是实验级变量）⇒ 改完 __wait 下一轮读到就放行。 */}
          {shownRunId != null && (
            <Card size="small" title={t('runVars.title')} style={{ marginBottom: 12 }}>
              <RunVariablesCard runId={shownRunId} />
            </Card>
          )}

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
