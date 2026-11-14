import { useMemo } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  Alert, Button, Card, Col, Empty, Form, Input, InputNumber, Row, Space, Statistic,
  Table, Tag, Tooltip, Typography, message,
} from 'antd';
import { useNavigate } from 'react-router-dom';
import {
  api,
  type ExperimentEtaItem,
  type SchedulerResourceLock,
  type SchedulerQueueItem,
} from '../api/client';
import { formatTime, formatWait } from '../utils/format';
import { useT } from '../i18n';

/**
 * 岛：编码 + #id。
 *
 * <p>为什么两个都显示：编码是值班员认得的（Pre-NGS），id 是日志/工单里引用的；
 * 联查不到编码时也不能让这一格空着——#id 至少能拿去查库。
 */
function IslandCell({ code, name }: { code: string | null; name?: string | null }) {
  const t = useT();
  // N-09 / ADR-ISLAND-005：界面只显示岛名，不显示内部 island_id。
  if (!code && !name) return <Typography.Text type="secondary">{t('scheduler.unknownIsland')}</Typography.Text>;
  return (
    <Tooltip title={code ? t('island.codeTooltip', { code }) : ''}>
      <Tag color="blue">{name || code}</Tag>
    </Tooltip>
  );
}

/**
 * 资源占用单元格：显示设备名，编码放 tooltip。
 *
 * <p>2026-11-10 起调度占用的是**设备**（不是整个岛）—— 同岛不同设备可以并行，
 * 值班员要看到的是「哪台设备被谁占着」。
 */
function ResourceCell({ code, name }: { code: string | null; name?: string | null }) {
  const t = useT();
  if (!code && !name) {
    return <Typography.Text type="secondary">{t('scheduler.unknownResource')}</Typography.Text>;
  }
  return (
    <Tooltip title={code ? t('island.codeTooltip', { code }) : ''}>
      <Tag color="blue">{name || code}</Tag>
    </Tooltip>
  );
}

/**
 * 调度器（值班看调度）。
 *
 * <p>回答三个问题：现在派了几个、谁占着岛、我在队里第几。这些信息日志里都有，
 * 但值班员不能一边 tail 日志一边判断"要不要人工插队"。
 *
 * <p>数据来自内核内存态（`GET /api/scheduler/status`）。3 秒轮询：
 * 调度循环本身 2 秒一轮，比它快没有更细的变化粒度，比它慢会漏掉
 * "派发了又立刻失败"这种一轮内的翻转。
 */
export default function SchedulerPage() {
  const t = useT();
  const nav = useNavigate();

  const status = useQuery({
    queryKey: ['scheduler-status'],
    queryFn: api.schedulerStatus,
    refetchInterval: 3000,
  });

  /**
   * 排队表的「预计等待」取自 §7.2 的 ETA 接口，而不是 §7.1 的 queue 字段。
   *
   * <p>为什么两处都拉：status 的 queue 只回答"谁在第几位"，估算（岛持有者剩余 +
   * 同岛前面实验耗时）不在它的契约里。按 experimentId 合并两个接口，而不是前端
   * 自己估一个数——编出来的等待时间会直接误导值班判断。
   *
   * <p>5 秒刷新：估算变化比队列慢，没必要跟着 3 秒刷。
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

  // P2 完善：当前排程参数 + 陈旧暂停 + 死锁风险（低频，60s 刷一次）
  const config = useQuery({
    queryKey: ['scheduler-config'],
    queryFn: api.schedulerConfig,
    refetchInterval: 60000,
  });
  const stale = useQuery({
    queryKey: ['scheduler-stale'],
    queryFn: () => api.schedulerStalePauses(7),
    refetchInterval: 60000,
  });
  const deadlock = useQuery({
    queryKey: ['scheduler-deadlock'],
    queryFn: () => api.schedulerDeadlockCheck(30, 30),
    refetchInterval: 60000,
  });
  const qc = useQueryClient();
  const [form] = Form.useForm();
  const plans = useQuery({
    queryKey: ['schedule-plans'],
    queryFn: api.schedulePlans,
    refetchInterval: 60000,
  });
  const archivePlan = useMutation({
    mutationFn: (id: number) => api.updateSchedulePlan(id, { status: 'archived' }),
    onSuccess: () => {
      message.success(t('scheduler.config.archived'));
      qc.invalidateQueries({ queryKey: ['schedule-plans'] });
      qc.invalidateQueries({ queryKey: ['scheduler-config'] });
    },
    onError: (e) => message.error(String((e as Error).message)),
  });
  const createPlan = useMutation({
    mutationFn: api.createSchedulePlan,
    onSuccess: (r) => {
      message.success(t('scheduler.config.submitted', { planNo: r.planNo }));
      form.resetFields();
      qc.invalidateQueries({ queryKey: ['scheduler-config'] });
      qc.invalidateQueries({ queryKey: ['schedule-plans'] });
    },
    onError: (e) => message.error(String((e as Error).message)),
  });

  if (status.error) {
    return (
      <Alert
        type="error" showIcon message={t('scheduler.error.connect')}
        description={String((status.error as Error).message)}
      />
    );
  }

  const st = status.data;
  const locks = st?.resourceLocks ?? [];
  const queue = st?.queue ?? [];
  const workers = st?.workers;
  const stalled = !!st?.stalled;

  // §7.1：started = 累计派发次数（number），"调度循环是否已启动"是 loopStarted（boolean 语义）。
  // 内核拿不到数字就显示「—」，不把 true / NaN 显示出来误导值班。
  const dispatchCount = typeof st?.started === 'number' ? st.started : '—';
  const loopStarted = st?.loopStarted;

  const metrics: Array<{ title: string; value: string | number; hint?: string }> = [
    { title: t('scheduler.stat.running'), value: st?.running ?? 0 },
    { title: t('scheduler.stat.queued'), value: st?.queued ?? 0 },
    { title: t('scheduler.stat.maxConcurrent'), value: st?.maxConcurrent ?? '—' },
    {
      title: t('scheduler.stat.dispatchCount'), value: dispatchCount,
      // 顺带把"调度循环到底起没起"亮出来：循环没起时次数=0，看着像"没活干"，
      // 实际是"没人派活"，两者的处置完全不同。
      hint: loopStarted == null ? undefined
        : (loopStarted ? t('scheduler.hint.loopStarted') : t('scheduler.hint.loopNotStarted')),
    },
    { title: t('scheduler.stat.lastDispatch'), value: st?.lastDispatchAt ? formatTime(st.lastDispatchAt) : '—' },
    {
      title: t('scheduler.stat.workerPool'),
      value: workers ? `${workers.pool} / ${workers.active} / ${workers.queue}` : '—',
      hint: t('scheduler.hint.workerPool'),
    },
  ];

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      {/* 顶部告警是这一页存在的理由：stalled 意味着"队里有人，但没人被派出"，
          光看计数看不出异常，必须把判断结论直接摆出来。 */}
      {st && (stalled ? (
        <Alert
          type="error" showIcon
          message={t('scheduler.alert.blockedTitle')}
          description={t('scheduler.alert.blockedDesc', {
            running: st.running, queued: st.queued, time: formatTime(st.lastDispatchAt),
          })}
        />
      ) : (
        <Alert
          type="info" showIcon
          message={t('scheduler.alert.okTitle')}
          description={t('scheduler.alert.okDesc', {
            running: st.running, max: st.maxConcurrent, queued: st.queued,
            time: formatTime(st.lastDispatchAt),
          })}
        />
      ))}

      <Row gutter={[12, 12]}>
        {metrics.map((m) => (
          <Col key={m.title} xs={12} sm={8} lg={4}>
            <Card size="small" loading={status.isLoading}>
              <Statistic title={m.title} value={m.value} valueStyle={{ fontSize: 15 }} />
              {m.hint && (
                <Typography.Text type="secondary" style={{ fontSize: 11 }}>{m.hint}</Typography.Text>
              )}
            </Card>
          </Col>
        ))}
      </Row>

      <Card
        size="small"
        title={t('scheduler.locks.title', { n: locks.length })}
        extra={
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {t('scheduler.locks.hint')}
          </Typography.Text>
        }
      >
        <Table<SchedulerResourceLock>
          rowKey="resourceId"
          size="small"
          pagination={false}
          loading={status.isLoading}
          dataSource={locks}
          locale={{
            emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('scheduler.locks.empty')} />,
          }}
          columns={[
            {
              title: t('scheduler.col.resource'), width: 220,
              render: (_, r) => <ResourceCell code={r.resourceCode} name={r.resourceName} />,
            },
            {
              title: t('scheduler.col.holder'),
              render: (_, r) => (
                <Space size={6}>
                  <a onClick={() => nav(`/experiments/${r.experimentId}`)}>{r.experimentNo}</a>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    #{r.experimentId} · run {r.experimentRunId}
                  </Typography.Text>
                </Space>
              ),
            },
            {
              title: t('scheduler.col.since'), dataIndex: 'since', width: 170,
              render: (v: string | null) => formatTime(v),
            },
            {
              title: t('scheduler.col.expires'), dataIndex: 'expiresAt', width: 170,
              render: (v: string | null) => formatTime(v),
            },
          ]}
        />
      </Card>

      <Card
        size="small"
        title={t('scheduler.queue.title', { n: queue.length })}
        extra={
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {t('scheduler.queue.hint')}
          </Typography.Text>
        }
      >
        <Table<SchedulerQueueItem>
          rowKey="experimentRunId"
          size="small"
          pagination={false}
          loading={status.isLoading}
          dataSource={queue}
          locale={{
            emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('scheduler.queue.empty')} />,
          }}
          columns={[
            {
              title: t('scheduler.col.position'), dataIndex: 'position', width: 70,
              render: (v: number) => <Tag>{v}</Tag>,
            },
            {
              title: t('scheduler.col.experiment'),
              render: (_, r) => (
                <Space size={6}>
                  <a onClick={() => nav(`/experiments/${r.experimentId}`)}>{r.experimentNo}</a>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    #{r.experimentId}
                  </Typography.Text>
                </Space>
              ),
            },
            {
              title: t('scheduler.col.island'), width: 220,
              render: (_, r) => <IslandCell code={r.islandCode} name={r.islandName} />,
            },
            { title: t('scheduler.col.priority'), dataIndex: 'priority', width: 90 },
            {
              title: t('scheduler.col.deadline'), dataIndex: 'deadline', width: 170,
              render: (v: string | null) => (v
                ? formatTime(v)
                : <Typography.Text type="secondary">—</Typography.Text>),
            },
            {
              title: t('scheduler.col.submittedAt'), dataIndex: 'submittedAt', width: 170,
              render: (v: string | null) => formatTime(v),
            },
            {
              title: t('scheduler.col.eta'), width: 150,
              render: (_, r) => {
                const e = etaById.get(r.experimentId);
                // ETA 接口没覆盖到（比如超过 limit）就显示「—」，不用队列位次反推一个假数字
                if (!e || e.estimatedWaitSec == null) {
                  return <Typography.Text type="secondary">—</Typography.Text>;
                }
                const tip = [
                  e.reason,
                  e.estimateSec != null
                    ? t('scheduler.estimate.hint', { wait: formatWait(e.estimateSec) })
                    : null,
                ].filter(Boolean).join('\n');
                const node = (
                  <Typography.Text style={{ cursor: 'help' }}>
                    {formatWait(e.estimatedWaitSec)}
                  </Typography.Text>
                );
                return tip
                  ? <Tooltip title={<span style={{ whiteSpace: 'pre-line' }}>{tip}</span>}>{node}</Tooltip>
                  : node;
              },
            },
          ]}
        />
      </Card>

      {/* P2 完善：排程参数配置 + 陈旧暂停 + 死锁风险 */}
      <Row gutter={[12, 12]}>
        <Col xs={24} lg={12}>
          <Card size="small" title={t('scheduler.config.title')}
                extra={<Typography.Text type="secondary" style={{ fontSize: 11 }}>
                  {t('scheduler.config.reloadHint')}</Typography.Text>}>
            <Space direction="vertical" size={8} style={{ width: '100%' }}>
              <Space size={16} wrap>
                <Statistic title={t('scheduler.config.optimizationFactor')}
                  value={config.data?.optimizationFactor ?? '—'} valueStyle={{ fontSize: 14 }} />
                <Statistic title={t('scheduler.config.maxIterationsRunning')}
                  value={config.data?.maxConcurrent ?? '—'} valueStyle={{ fontSize: 14 }} />
                <Statistic title={t('scheduler.config.iterationDelaySec')}
                  value={config.data ? Math.round((config.data.tickMs - 2000) / 1000) : '—'}
                  valueStyle={{ fontSize: 14 }} />
              </Space>
              <Form form={form} layout="inline"
                onFinish={(v) => createPlan.mutate({
                  planNo: v.planNo || undefined,
                  optimizationFactor: v.optimizationFactor,
                  maxIterationsRunning: v.maxIterationsRunning,
                  iterationDelaySec: v.iterationDelaySec,
                })}>
                <Form.Item name="optimizationFactor" initialValue={50}
                  tooltip={t('scheduler.config.factorHint')}>
                  <InputNumber min={0} max={100} placeholder="50" />
                </Form.Item>
                <Form.Item name="maxIterationsRunning" initialValue={4}
                  tooltip={t('scheduler.config.maxHint')}>
                  <InputNumber min={1} max={4} placeholder="4" />
                </Form.Item>
                <Form.Item name="iterationDelaySec" initialValue={0}>
                  <InputNumber min={0} placeholder="0" />
                </Form.Item>
                <Form.Item name="planNo">
                  <Input placeholder={t('scheduler.config.planPlaceholder')}
                    style={{ width: 180 }} allowClear />
                </Form.Item>
                <Button type="primary" htmlType="submit" loading={createPlan.isPending}>
                  {t('scheduler.config.submit')}
                </Button>
              </Form>
              {plans.data && plans.data.total > 0 && (
                <Space direction="vertical" size={4} style={{ width: '100%' }}>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {t('scheduler.config.planList')}
                  </Typography.Text>
                  {plans.data.items.slice(0, 8).map((p) => (
                    <Space key={p.id} size={8} wrap>
                      <Typography.Text code style={{ fontSize: 12 }}>{p.planNo}</Typography.Text>
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        factor={p.optimizationFactor} · max={p.maxIterationsRunning} · delay={p.iterationDelaySec}s
                      </Typography.Text>
                      <Tag color={p.status === 'archived' ? 'default' : 'blue'}>{p.status}</Tag>
                      {p.status !== 'archived' && (
                        <Button size="small" type="link" danger
                          loading={archivePlan.isPending}
                          onClick={() => archivePlan.mutate(p.id)}>
                          {t('scheduler.config.archive')}
                        </Button>
                      )}
                    </Space>
                  ))}
                </Space>
              )}
            </Space>
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Space direction="vertical" size={8} style={{ width: '100%' }}>
            <Card size="small" title={t('scheduler.stale.title', { n: stale.data?.total ?? 0 })}>
              {stale.data && stale.data.total > 0 ? (
                <Space direction="vertical" size={4} style={{ width: '100%' }}>
                  {stale.data.items.slice(0, 5).map((it) => (
                    <Space key={it.runId} size={8}>
                      <a onClick={() => nav(`/runs/${it.runId}`)}>{it.runNo || `#${it.runId}`}</a>
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        {it.pauseKind} · {formatTime(it.pausedAt)}
                      </Typography.Text>
                    </Space>
                  ))}
                  {stale.data.total > 5 && (
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      …+{stale.data.total - 5}
                    </Typography.Text>
                  )}
                </Space>
              ) : (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description={t('scheduler.stale.empty')} />
              )}
              <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                {t('scheduler.stale.hint', { days: stale.data?.days ?? 7 })}
              </Typography.Text>
            </Card>
            <Card size="small" title={t('scheduler.deadlock.title', { n: deadlock.data?.total ?? 0 })}>
              {deadlock.data && deadlock.data.total > 0 ? (
                <Space direction="vertical" size={4} style={{ width: '100%' }}>
                  {deadlock.data.risks.slice(0, 5).map((r, i) => (
                    <Typography.Text key={i} type="warning" style={{ fontSize: 12 }}>
                      {r.message}
                    </Typography.Text>
                  ))}
                </Space>
              ) : (
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {t('scheduler.deadlock.empty')}
                </Typography.Text>
              )}
            </Card>
          </Space>
        </Col>
      </Row>

      <Card size="small" title={t('scheduler.card.note')}>
        <Typography.Paragraph type="secondary" style={{ marginBottom: 0, fontSize: 12 }}>
          {t('scheduler.note.prefix')}<strong>{t('scheduler.note.bold')}</strong>
          {t('scheduler.note.suffix')}
        </Typography.Paragraph>
      </Card>
    </Space>
  );
}
