import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert, Button, Card, Descriptions, Empty, Input, Modal, Progress, Space, Statistic,
  Table, Tag, Tooltip, Typography, message,
} from 'antd';
import {
  ArrowLeftOutlined, PauseCircleOutlined, PlayCircleOutlined,
  ReloadOutlined, StopOutlined, SyncOutlined,
} from '@ant-design/icons';
import { api, type RunNode } from '../api/client';
import RunGraph from '../components/RunGraph';
import {
  pauseKindMeta, statusColor, statusLabel, useStatusVocabulary,
} from '../utils/status';
import { useReadOnly } from '../auth';
import { useT } from '../i18n';
import { localizedNeutralLabel } from '../bpmn/nodeRoleLabel';

/**
 * 执行详情：**实时看跑到哪一步**，并能暂停/继续/停止。
 *
 * 关键设计：
 * - 运行中 2 秒自动刷新（比后端 3 秒快一档，避免"点了按钮要等很久才变"）
 * - 当前执行的节点高亮 —— 一眼看出卡在哪
 * - 暂停按类型显示不同颜色和提示 —— 三种暂停态恢复条件不同
 */
export default function RunDetailPage() {
  const { id } = useParams();
  const nav = useNavigate();
  const qc = useQueryClient();
  const runId = Number(id);
  const t = useT();

  // 状态文案/颜色统一从后端词表来（接口没上线时用内置兜底，不闪英文 code）
  const vocab = useStatusVocabulary();
  // 暂停/继续/终止都直接驱动设备：viewer 只能看执行详情
  const ro = useReadOnly();

  const [busy, setBusy] = useState(false);
  const [locateOpen, setLocateOpen] = useState(false);
  const [locateBarcode, setLocateBarcode] = useState('');
  const [locateValue, setLocateValue] = useState('');

  const { data, error, refetch, isFetching } = useQuery({
    queryKey: ['run', runId],
    queryFn: () => api.run(runId),
    enabled: Number.isFinite(runId),
    // 运行中或暂停中都要刷新（暂停时可能被别人恢复）
    refetchInterval: (q) => {
      const st = q.state.data?.run.status;
      return st === 'running' || st === 'paused' ? 2000 : false;
    },
  });

  const run = data?.run;
  const nodes = data?.nodes ?? [];
  const events = data?.events ?? [];

  // 循环的"第 N 圈"行（loopIndex>0）是额外补的，和汇总行同 node_key。
  // 把它们挂到汇总行下面当子行 —— 这样「节点（1）」表示图上有 1 个节点、
  // 展开能看到「第 1/2/3 圈」，而不是让圈次行冒充顶层节点把计数撑大。
  const aggIdByKey = useMemo(() => {
    const m = new Map<string, number>();
    nodes
      .filter((n) => n.level === 0 && (n.loopIndex ?? 0) === 0)
      .forEach((n) => m.set(n.nodeKey, n.id));
    return m;
  }, [nodes]);

  const viewNodes = useMemo(() => nodes.map((n) => {
    if (n.level === 0 && (n.loopIndex ?? 0) > 0) {
      const aggId = aggIdByKey.get(n.nodeKey);
      return aggId ? { ...n, parentNodeId: aggId } : n;
    }
    return n;
  }), [nodes, aggIdByKey]);

  const roots = useMemo(() => viewNodes.filter((n) => !n.parentNodeId), [viewNodes]);
  const kidsOf = (nid: number) => viewNodes.filter((n) => n.parentNodeId === nid);

  /** 当前正在执行的节点（level=0 的业务节点）。 */
  const current = useMemo(
    () => roots.find((r) => r.status === 'running'),
    [roots],
  );
  /** 当前正在执行的子步骤（level=1）。 */
  const currentStep = useMemo(() => {
    if (!current) return undefined;
    return kidsOf(current.id).find((k) => k.status === 'running');
  }, [current, nodes]);

  const total = roots.length;
  const done = roots.filter((r) => r.status === 'completed').length;
  const failed = roots.filter((r) => r.status === 'failed' || r.status === 'aborted').length;
  // manual = 卡在"要人处理"上。既不算完成也不算失败，原来两个数字都体现不出来，
  // 页面显示成「节点 0/1、失败 0」，读起来像"压根没跑"。
  const manual = roots.filter((r) => r.status === 'manual').length;
  const percent = total === 0 ? 0 : Math.round((done / total) * 100);

  // 暂停类型也走词表：三种暂停态的恢复条件不同，文案/颜色必须和暂停队列页一致
  const pauseInfo = run?.status === 'paused' && run.pauseKind
    ? pauseKindMeta(run.pauseKind, vocab)
    : undefined;

  const pausingKind = run?.pauseKind;

  // 已用时
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    if (run?.status !== 'running') return;
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, [run?.status]);

  const elapsed = useMemo(() => {
    const start = run?.actualStart;
    if (!start) return null;
    const sec = Math.max(0, Math.floor((now - new Date(start).getTime()) / 1000));
    const m = Math.floor(sec / 60);
    const s = sec % 60;
    return m > 0
      ? t('runDetail.duration.minutes', { m, s })
      : t('runDetail.duration.seconds', { s });
  }, [run, now]);

  async function act(fn: () => Promise<unknown>, okMsg: string) {
    setBusy(true);
    try {
      await fn();
      message.success(okMsg);
      await refetch();
      qc.invalidateQueries({ queryKey: ['runs'] });
    } catch (e) {
      message.error(String((e as Error).message ?? e));
    } finally {
      setBusy(false);
    }
  }

  if (error) {
    return <Alert type="error" showIcon message={t('runDetail.error.load')}
      description={String((error as Error).message)} />;
  }

  type Row = RunNode & { children?: Row[] };
  const tree: Row[] = roots.map((r) => ({
    ...r,
    children: kidsOf(r.id).map((c) => ({ ...c })),
  }));

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      {/* ---------------- 顶部：状态 + 操作 ---------------- */}
      <Card
        size="small"
        title={
          <Space>
            <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => nav('/runs')} />
            <span>{t('runDetail.title', { runId })}</span>
            {run && <Tag color={statusColor('run', run.status, vocab)}>{run.status}</Tag>}
            {isFetching && <SyncOutlined spin style={{ color: '#999' }} />}
          </Space>
        }
        extra={
          <Space>
            <Button size="small" icon={<ReloadOutlined />} onClick={() => refetch()}>
              {t('runDetail.action.refresh')}
            </Button>
            {run?.status === 'running' && (
              <>
                <Button
                  size="small" danger icon={<PauseCircleOutlined />} loading={busy} {...ro}
                  onClick={() => act(
                    () => api.pauseRun(runId, 'waiting_human', t('runDetail.pause.reason.operator')),
                    t('runDetail.toast.paused'),
                  )}
                >
                  {t('runDetail.action.pause')}
                </Button>
                <Button
                  size="small" danger icon={<StopOutlined />} loading={busy} {...ro}
                  onClick={() => Modal.confirm({
                    title: t('runDetail.confirm.abortTitle'),
                    content: t('runDetail.confirm.abortContent'),
                    okText: t('runDetail.confirm.abortOk'),
                    okButtonProps: { danger: true },
                    onOk: () => act(
                      () => api.resumeRun(runId, 'abort'),
                      t('runDetail.toast.aborted'),
                    ),
                  })}
                >
                  {t('runDetail.action.stop')}
                </Button>
              </>
            )}
            {run?.status === 'paused' && (
              <>
                <Button
                  size="small" type="primary" icon={<PlayCircleOutlined />} loading={busy} {...ro}
                  onClick={() => {
                    if (pausingKind === 'paused_unknown') {
                      setLocateOpen(true);
                    } else {
                      act(() => api.resumeRun(runId, 'continue'), t('runDetail.toast.continued'));
                    }
                  }}
                >
                  {t('runDetail.action.continue')}
                </Button>
                <Button
                  size="small" danger icon={<StopOutlined />} loading={busy} {...ro}
                  onClick={() => Modal.confirm({
                    title: t('runDetail.confirm.abortTitleShort'),
                    okButtonProps: { danger: true },
                    onOk: () => act(() => api.resumeRun(runId, 'abort'), t('runDetail.toast.aborted')),
                  })}
                >
                  {t('runDetail.confirm.abortOk')}
                </Button>
              </>
            )}
          </Space>
        }
      >
        <Descriptions size="small" column={5} style={{ marginBottom: 12 }}>
          <Descriptions.Item label={t('runDetail.field.runNo')}>{run?.runNo ?? '—'}</Descriptions.Item>
          <Descriptions.Item label={t('runDetail.field.type')}>{run?.runType ?? '—'}</Descriptions.Item>
          <Descriptions.Item label={t('runDetail.field.elapsed')}>{elapsed ?? '—'}</Descriptions.Item>
          <Descriptions.Item label={t('runDetail.field.nodes')}>{done}/{total}</Descriptions.Item>
          {manual > 0 && (
            <Descriptions.Item label={t('runDetail.field.manual')}>
              <Typography.Text type="warning">{manual}</Typography.Text>
            </Descriptions.Item>
          )}
          <Descriptions.Item label={t('runDetail.field.failed')}>{failed}</Descriptions.Item>
        </Descriptions>

        <Progress
          percent={percent}
          status={failed > 0 ? 'exception' : run?.status === 'completed' ? 'success' : 'active'}
          strokeColor={failed > 0 ? undefined : { from: '#108ee9', to: '#87d068' }}
        />

        {/* 当前执行到哪一步 —— 只在真正 running 时显示"正在执行"；
            暂停时节点还挂着 running 状态，说"正在执行"是错的 */}
        {current && run?.status === 'running' && (
          <Alert
            style={{ marginTop: 12 }}
            type="info"
            showIcon
            icon={<SyncOutlined spin />}
            message={
              <Space>
                <b>{t('runDetail.current.running', { name: localizedNeutralLabel(current.name) ?? current.name })}</b>
                {currentStep && (
                  <Tag color="blue">
                    {t('runDetail.current.step', { seq: currentStep.stepSeq ?? '?' })}
                  </Tag>
                )}
                {(current.resourceCode || current.resourceId) && (
                  <Tag>
                    {t('runDetail.current.device', {
                      code: current.resourceCode ?? current.resourceId ?? '',
                    })}
                  </Tag>
                )}
                {current.attempt > 0 && (
                  <Tag color="orange">
                    {t('runDetail.current.attempt', { n: current.attempt + 1 })}
                  </Tag>
                )}
              </Space>
            }
            description={
              current.estimateSec
                ? t('runDetail.current.estimate', { n: current.estimateSec })
                : undefined
            }
          />
        )}

        {/* 暂停原因 + 恢复条件 */}
        {pauseInfo && (
          <Alert
            style={{ marginTop: 12 }}
            type={pauseInfo.color === 'red' ? 'error' : 'warning'}
            showIcon
            message={
              <Space>
                <b>{t('runDetail.paused.label', { label: pauseInfo.label })}</b>
                {run?.pauseReason && (
                  <span style={{ fontWeight: 400 }}>— {run.pauseReason}</span>
                )}
              </Space>
            }
            description={
              <Space direction="vertical" size={2}>
                <span>{pauseInfo.hint}</span>
                {run?.pauseNodeKey && (
                  <span style={{ fontSize: 12, color: '#888' }}>
                    {t('runDetail.paused.atNode', { key: run.pauseNodeKey })}
                  </span>
                )}
              </Space>
            }
          />
        )}
      </Card>

      {/* ---------------- 当前步骤大卡片（醒目展示） ---------------- */}
      {currentStep && (
        <Card size="small" style={{ borderColor: '#1677ff', borderWidth: 2 }}>
          <Space size={24}>
            <Statistic title={t('runDetail.stat.currentStep')} value={currentStep.stepSeq ?? '—'} />
            <Statistic title={t('runDetail.stat.stepName')} value={localizedNeutralLabel(currentStep.name) ?? currentStep.name ?? '—'} />
            {currentStep.attempt > 0 && (
              <Statistic title={t('runDetail.stat.retries')} value={currentStep.attempt} />
            )}
          </Space>
        </Card>
      )}

      {/* ---------------- 节点树 ---------------- */}
      {/* 流程图：一眼看出图上有几步、卡在哪、后面哪几步没跑。
          表格看细节，图看全局，两个都留着。 */}
      <Card size="small" title={t('runDetail.card.graph')} style={{ marginBottom: 12 }}>
        <RunGraph runId={runId} />
      </Card>

      <Card size="small" title={t('runDetail.card.nodes', { n: total })}>
        {tree.length === 0 ? <Empty description={t('runDetail.empty.nodes')} /> : (
          <Table<Row>
            rowKey="id" size="small" pagination={false}
            dataSource={tree}
            rowClassName={(r) => (r.status === 'running' ? 'row-running' : '')}
            expandable={{
              rowExpandable: (r) => (r.children?.length ?? 0) > 0,
              defaultExpandAllRows: nodes.length <= 30,
            }}
            columns={[
              {
                title: t('runDetail.col.node'), dataIndex: 'name', width: 260,
                render: (v: string, r: RunNode) => (
                  <Space>
                    {r.status === 'running' && <SyncOutlined spin style={{ color: '#1677ff' }} />}
                    <span style={{ fontWeight: r.status === 'running' ? 600 : 400 }}>
                      {localizedNeutralLabel(v) ?? v}
                    </span>
                  </Space>
                ),
              },
              {
                title: t('runDetail.col.status'), dataIndex: 'status', width: 110,
                render: (v: string) => (
                  <Tag color={statusColor('node', v, vocab)}>{statusLabel('node', v, vocab)}</Tag>
                ),
              },
              {
                // 循环才有值：0 是汇总行（最终状态），1..N 是第几圈。
                // 只有真出现循环时才显示这列，否则普通单子多一列"—"纯噪音。
                title: t('runDetail.col.loop'), width: 90,
                render: (_, r) => (r.loopIndex && r.loopIndex > 0
                  ? <Tag color="geekblue">{t('runDetail.col.loopTag', { n: r.loopIndex })}</Tag>
                  : '—'),
              },
              {
                title: t('runDetail.col.step'), width: 80,
                render: (_, r) => (r.level === 1 ? r.stepSeq ?? '—' : '—'),
              },
              {
                title: t('runDetail.col.attempt'), width: 80,
                render: (_, r) => (r.attempt
                  ? t('runDetail.col.attemptText', { n: r.attempt + 1 })
                  : '—'),
              },
              {
                title: t('runDetail.col.device'), width: 120,
                render: (_, r) => (r.resourceCode ?? r.resourceId ?? '—'),
              },
              {
                // 错误码单独一列（后端已落到 run_node.error_code）。
                // 原来只能从"失败原因"那段中文里用眼睛找，统计也没法做。
                title: t('runDetail.col.errorCode'), width: 130,
                render: (_, r) => (r.errorCode
                  ? <Tooltip title={r.errorCode}><Tag color="red">{r.errorCode}</Tag></Tooltip>
                  : '—'),
              },
              {
                title: t('runDetail.col.failureReason'), ellipsis: true,
                render: (_, r) => (r.reason ? (
                  <Tooltip title={r.reason}>
                    <Typography.Text type="danger" style={{ fontSize: 12 }}>
                      {r.reason.slice(0, 60)}
                    </Typography.Text>
                  </Tooltip>
                ) : '—'),
              },
            ]}
          />
        )}
      </Card>

      {/* ---------------- 事件流 ---------------- */}
      <Card size="small" title={t('runDetail.card.events', { n: events.length })}>
        {events.length === 0 ? <Empty description={t('runDetail.empty.events')} /> : (
          <Table
            rowKey={(r, i) => `${i}-${String((r as { type: string }).type)}`}
            size="small" pagination={{ pageSize: 12, size: 'small' }}
            dataSource={events}
            columns={[
              {
                title: '#', width: 60,
                render: (_, r: { payload?: { seq?: number } }) => r.payload?.seq ?? '—',
              },
              {
                title: t('runDetail.col.eventType'), width: 190,
                render: (_, r: { type: string }) => {
                  const t = r.type ?? '';
                  const color = t.includes('FAILED') || t.includes('MANUAL') || t.includes('ABORT')
                    ? 'red' : t.includes('COMPLETED') ? 'green'
                      : t.includes('PAUSED') ? 'gold'
                        : t.includes('DECIDED') || t.includes('SWITCHED') ? 'orange' : 'default';
                  return <Tag color={color}>{t}</Tag>;
                },
              },
              {
                title: t('runDetail.col.eventDetail'), ellipsis: true,
                render: (_, r: { payload?: Record<string, unknown> }) =>
                  String((r.payload?.detail ?? '') as string),
              },
            ]}
          />
        )}
      </Card>

      {/* ---------------- 板位校正弹窗（paused_unknown 必须） ---------------- */}
      <Modal
        title={t('runDetail.plate.unknownTitle')}
        open={locateOpen}
        onCancel={() => setLocateOpen(false)}
        okText={t('runDetail.plate.unknownOk')}
        confirmLoading={busy}
        onOk={() => {
          if (!locateBarcode.trim() || !locateValue.trim()) {
            message.warning(t('runDetail.plate.unknownWarn'));
            return;
          }
          const corrections = { [locateBarcode.trim()]: locateValue.trim() };
          act(
            () => api.resumeRun(runId, 'continue', corrections),
            t('runDetail.plate.unknownToast'),
          ).then(() => {
            setLocateOpen(false);
            setLocateBarcode('');
            setLocateValue('');
          });
        }}
      >
        <Alert
          type="warning" showIcon style={{ marginBottom: 12 }}
          message={t('runDetail.plate.unknownAlertTitle')}
          description={t('runDetail.plate.unknownAlertDesc')}
        />
        <Space direction="vertical" style={{ width: '100%' }}>
          <Input
            placeholder={t('runDetail.plate.placeholderId')}
            value={locateBarcode}
            onChange={(e) => setLocateBarcode(e.target.value)}
          />
          <Input
            placeholder={t('runDetail.plate.placeholderPos')}
            value={locateValue}
            onChange={(e) => setLocateValue(e.target.value)}
          />
        </Space>
      </Modal>
    </Space>
  );
}
