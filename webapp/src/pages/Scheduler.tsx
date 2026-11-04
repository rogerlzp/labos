import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Alert, Card, Col, Empty, Row, Space, Statistic, Table, Tag, Tooltip, Typography,
} from 'antd';
import { useNavigate } from 'react-router-dom';
import {
  api,
  type ExperimentEtaItem,
  type SchedulerIslandLock,
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
function IslandCell({ code, id }: { code: string | null; id: number | null }) {
  const t = useT();
  if (id == null) return <Typography.Text type="secondary">—</Typography.Text>;
  return (
    <Space size={4}>
      {code
        ? <Tag color="blue">{code}</Tag>
        : <Typography.Text type="secondary">{t('scheduler.unknownIsland')}</Typography.Text>}
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>#{id}</Typography.Text>
    </Space>
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

  if (status.error) {
    return (
      <Alert
        type="error" showIcon message={t('scheduler.error.connect')}
        description={String((status.error as Error).message)}
      />
    );
  }

  const st = status.data;
  const locks = st?.islandLocks ?? [];
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
        <Table<SchedulerIslandLock>
          rowKey="islandId"
          size="small"
          pagination={false}
          loading={status.isLoading}
          dataSource={locks}
          locale={{
            emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('scheduler.locks.empty')} />,
          }}
          columns={[
            {
              title: t('scheduler.col.island'), width: 220,
              render: (_, r) => <IslandCell code={r.islandCode} id={r.islandId} />,
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
              render: (_, r) => <IslandCell code={r.islandCode} id={r.islandId} />,
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

      <Card size="small" title={t('scheduler.card.note')}>
        <Typography.Paragraph type="secondary" style={{ marginBottom: 0, fontSize: 12 }}>
          {t('scheduler.note.prefix')}<strong>{t('scheduler.note.bold')}</strong>
          {t('scheduler.note.suffix')}
        </Typography.Paragraph>
      </Card>
    </Space>
  );
}
