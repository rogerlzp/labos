import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert, Button, Card, Empty, Input, Modal, Segmented, Select, Space, Statistic,
  Table, Tag, Tooltip, Typography, message,
} from 'antd';
import {
  CheckCircleOutlined, PlayCircleOutlined, ReloadOutlined,
  StopOutlined, WarningOutlined,
} from '@ant-design/icons';
import { api, apiFetch } from '../api/client';
import { pauseKindMeta, useStatusVocabulary } from '../utils/status';
import { useReadOnly } from '../auth';
import { useT } from '../i18n';

/**
 * 暂停队列：**所有停下来的执行集中在一页**。
 *
 * <p>为什么需要这一页：执行散在几百条记录里，早上来上班没法"一眼看到
 * 昨晚有几个卡住了"。这里按暂停类型分组，直接给处置入口。
 *
 * <p>三种暂停态的处置方式不同：
 * <ul>
 *   <li>{@code paused_fault} —— 设备恢复心跳会自己继续，人不用管</li>
 *   <li>{@code waiting_human} —— 必须人确认后点继续</li>
 *   <li>{@code paused_unknown} —— 必须填板位校正才能继续</li>
 * </ul>
 */
interface PausedRun {
  runId: number;
  runNo: string;
  pauseKind: string;
  pauseReason?: string;
  pauseNodeKey?: string;
  /** 卡住的业务节点（后端按"第一个未完成节点"补的，比 pauseNodeKey 可靠） */
  nodeName?: string;
  nodeStatus?: string;
  /** 卡住的原因，里面带错误码，如「策略[EC-08-400C@NODE] 指定直接转人工」 */
  nodeReason?: string;
  pausedAt?: string;
}

export default function PausedRunsPage() {
  const nav = useNavigate();
  const qc = useQueryClient();
  // 暂停类型的文案/颜色/hint/auto 全走词表（暂停队列、执行详情、调度器页同一套）
  const vocab = useStatusVocabulary();
  const t = useT();
  // 处置（继续/忽略/盘点）都是写操作：viewer 只能看队列，不能处置
  const ro = useReadOnly();
  const [filter, setFilter] = useState<string>('all');
  const [busy, setBusy] = useState<number | null>(null);
  const [selectedRows, setSelectedRows] = useState<number[]>([]);
  /**
   * 「忽略筛选内」的时间范围（小时）。0 = 不限时间。
   *
   * <p>为什么加这个：队列里常有几十条历史联调残留，运维想"只清 24 小时前的"，
   * 原来的「全部忽略」要么清光（可能带走真在等人的单）要么一条条点。
   */
  const [beforeHours, setBeforeHours] = useState<number>(0);

  // 板位校正弹窗
  const [locateOpen, setLocateOpen] = useState(false);
  const [locateRun, setLocateRun] = useState<number | null>(null);
  const [barcode, setBarcode] = useState('');
  const [location, setLocation] = useState('');

  const { data, error, refetch, isFetching } = useQuery({
    queryKey: ['paused-runs'],
    queryFn: () => api.pausedRuns(),
    // 20 秒刷新：暂停状态可能被自动恢复（设备故障）或别人处理掉
    refetchInterval: 20000,
  });

  const list: PausedRun[] = data ?? [];

  const counts = useMemo(() => {
    const c: Record<string, number> = { all: list.length };
    for (const r of list) {
      c[r.pauseKind] = (c[r.pauseKind] ?? 0) + 1;
    }
    return c;
  }, [list]);

  const rows = useMemo(
    () => (filter === 'all' ? list : list.filter((r) => r.pauseKind === filter)),
    [list, filter],
  );

  /** 当前筛选的暂停类型元信息（tab / 表格 / 底部提示共用一个来源） */
  const filterMeta = filter === 'all' ? null : pauseKindMeta(filter, vocab);

  async function act(runId: number, fn: () => Promise<unknown>, okMsg: string) {
    setBusy(runId);
    try {
      await fn();
      message.success(okMsg);
      await refetch();
      qc.invalidateQueries({ queryKey: ['runs'] });
    } catch (e) {
      message.error(String((e as Error).message ?? e));
    } finally {
      setBusy(null);
    }
  }

  function resumeOne(r: PausedRun) {
    if (r.pauseKind === 'paused_unknown') {
      setLocateRun(r.runId);
      setBarcode('');
      setLocation('');
      setLocateOpen(true);
      return;
    }
    act(r.runId, () => api.resumeRun(r.runId, 'continue'),
      t('pausedRuns.toast.continued', { id: r.runId }));
  }

  async function resumeBatch() {
    const targets = rows.filter((r) => selectedRows.includes(r.runId));
    const normal = targets.filter((r) => r.pauseKind !== 'paused_unknown');
    if (normal.length === 0) {
      message.warning(t('pausedRuns.warn.allUnknown'));
      return;
    }
    let ok = 0;
    for (const r of normal) {
      try {
        // eslint-disable-next-line no-await-in-loop
        await api.resumeRun(r.runId, 'continue');
        ok += 1;
      } catch {
        // 单个失败不阻塞其余的
      }
    }
    message.success(t('pausedRuns.toast.resumed', { ok, total: normal.length }));
    setSelectedRows([]);
    await refetch();
  }

  /**
   * 忽略：不再等人处理，标记 aborted（与"终止"落地状态一样，
   * 但错误码写 EC-HUMAN-DISMISS，报表上能区分"人不理它"和"人主动终止"）。
   *
   * 为什么需要：队列里堆了 89 条（历史遗留 + 联调残留），
   * 没人会一条条点终止，页面永远脏着，真出事的单子反而看不见。
   */
  async function dismissBatch(all: boolean) {
    const ids = all ? [] : selectedRows;
    if (!all && ids.length === 0) return;
    // all=true 时按当前筛选收敛：tab 上的暂停类型 + 时间范围
    const body = JSON.stringify(ids.length
      ? { runIds: ids }
      : {
        kind: filter === 'all' ? undefined : filter,
        beforeHours: beforeHours > 0 ? beforeHours : undefined,
      });
    const r = await apiFetch('/api/runs/dismiss', { method: 'POST', body });
    const j = await r.json().catch(() => ({}));
    message.success(t('pausedRuns.toast.dismissedCount', { n: j.dismissed ?? 0 }));
    setSelectedRows([]);
    await refetch();
  }

  async function dismissOne(runId: number) {
    await apiFetch('/api/runs/dismiss', {
      method: 'POST', body: JSON.stringify({ runIds: [runId] }),
    });
    message.success(t('pausedRuns.toast.dismissed', { id: runId }));
    await refetch();
  }

  if (error) {
    return <Alert type="error" showIcon message={t('pausedRuns.error.load')}
      description={String((error as Error).message)} />;
  }

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      {/* 统计 */}
      <Card size="small">
        <Space size={48}>
          <Statistic title={t('pausedRuns.stat.paused')} value={counts.all ?? 0}
            valueStyle={{ color: counts.all ? '#faad14' : '#52c41a' }} />
          <Statistic title={t('pausedRuns.stat.fault')} value={counts.paused_fault ?? 0}
            valueStyle={{ color: counts.paused_fault ? '#ff4d4f' : undefined }} />
          <Statistic title={t('pausedRuns.stat.waitingHuman')} value={counts.waiting_human ?? 0}
            valueStyle={{ color: counts.waiting_human ? '#fa8c16' : undefined }} />
          <Statistic title={t('pausedRuns.stat.plateUnknown')} value={counts.paused_unknown ?? 0}
            valueStyle={{ color: counts.paused_unknown ? '#fa541c' : undefined }} />
          {isFetching && <ReloadOutlined spin style={{ color: '#999' }} />}
        </Space>
      </Card>

      {list.length === 0 ? (
        <Card>
          <Empty
            image={<CheckCircleOutlined style={{ fontSize: 64, color: '#52c41a' }} />}
            description={
              <Space direction="vertical" size={4}>
                <Typography.Text strong>{t('pausedRuns.empty.title')}</Typography.Text>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {t('pausedRuns.empty.desc')}
                </Typography.Text>
              </Space>
            }
          />
        </Card>
      ) : (
        <Card
          size="small"
          title={
            <Space>
              <span>{t('pausedRuns.queue.title', { n: rows.length })}</span>
              <Segmented
                size="small"
                value={filter}
                onChange={(v) => { setFilter(String(v)); setSelectedRows([]); }}
                options={[
                  { label: t('pausedRuns.filter.all', { n: counts.all ?? 0 }), value: 'all' },
                  // 词表数组顺序即展示序（后端：paused_fault → waiting_human → paused_unknown）
                  ...vocab.pauseKinds.map((k) => ({
                    label: `${k.label} ${counts[k.code] ?? 0}`,
                    value: k.code,
                  })),
                ]}
              />
            </Space>
          }
          extra={
            <Space>
              <Button size="small" icon={<ReloadOutlined />} onClick={() => refetch()}>
                {t('pausedRuns.action.refresh')}
              </Button>
              <Button
                size="small" type="primary" icon={<PlayCircleOutlined />}
                disabled={ro.disabled || selectedRows.length === 0}
                title={ro.title}
                onClick={resumeBatch}
              >
                {t('pausedRuns.action.batchContinue', { n: selectedRows.length })}
              </Button>
              <Button
                size="small" danger
                disabled={ro.disabled || selectedRows.length === 0}
                title={ro.title}
                onClick={() => Modal.confirm({
                  title: t('pausedRuns.confirm.dismissTitle', { n: selectedRows.length }),
                  content: t('pausedRuns.confirm.dismissContent'),
                  okText: t('pausedRuns.confirm.dismissOk'), okButtonProps: { danger: true },
                  onOk: () => dismissBatch(false),
                })}
              >
                {t('pausedRuns.action.batchDismiss', { n: selectedRows.length })}
              </Button>
              <Select
                size="small" style={{ width: 130 }} value={beforeHours}
                onChange={(v) => setBeforeHours(v)}
                options={[
                  { value: 0, label: t('pausedRuns.filter.anyTime') },
                  { value: 24, label: t('pausedRuns.filter.dayAgo') },
                  { value: 168, label: t('pausedRuns.filter.weekAgo') },
                ]}
              />
              <Button
                size="small" type="dashed" danger {...ro}
                onClick={() => Modal.confirm({
                  title: t('pausedRuns.confirm.dismissFilteredTitle', { n: rows.length }),
                  content: t('pausedRuns.confirm.dismissFilteredContent', {
                    scope: filter === 'all' ? t('pausedRuns.confirm.scope.allKinds') : filter,
                    time: beforeHours > 0
                      ? t('pausedRuns.confirm.time.olderThan', { n: beforeHours })
                      : t('pausedRuns.confirm.time.anyTime'),
                  }),
                  okText: t('pausedRuns.confirm.dismissOk'), okButtonProps: { danger: true },
                  onOk: () => dismissBatch(true),
                })}
              >
                {t('pausedRuns.action.dismissFiltered', { n: rows.length })}
              </Button>
            </Space>
          }
        >
          <Table<PausedRun>
            rowKey="runId"
            size="small"
            dataSource={rows}
            pagination={rows.length > 20 ? { pageSize: 20, size: 'small' } : false}
            rowSelection={{
              selectedRowKeys: selectedRows,
              onChange: (keys) => setSelectedRows(keys as number[]),
            }}
            columns={[
              {
                title: t('pausedRuns.col.run'), dataIndex: 'runNo', width: 220,
                render: (v: string, r) => (
                  <Space>
                    <Button type="link" size="small" style={{ padding: 0 }}
                      onClick={() => nav(`/runs/${r.runId}`)}>
                      #{r.runId}
                    </Button>
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      {v}
                    </Typography.Text>
                  </Space>
                ),
              },
              {
                title: t('pausedRuns.col.kind'), dataIndex: 'pauseKind', width: 120,
                render: (v: string) => {
                  const k = pauseKindMeta(v, vocab);
                  return (
                    <Tooltip title={k.hint || undefined}>
                      <Tag color={k.color} style={{ cursor: 'help' }}>
                        {k.auto && <ReloadOutlined style={{ marginRight: 4 }} />}
                        {k.label}
                      </Tag>
                    </Tooltip>
                  );
                },
              },
              {
                  // 优先显示节点级原因（含错误码 EC-xx），没有才退回 run 级说明。
                  // 现场最想知道的就是"为什么停"——原来只显示"检查设备"这种泛泛的话。
                  title: t('pausedRuns.col.reason'), ellipsis: true,
                  render: (_, r) => {
                    const reason = r.nodeReason || r.pauseReason;
                    if (!reason) return '—';
                    const code = reason.match(/EC-\d{2}-[0-9A-Z]{4,}/)?.[0];
                    return (
                      <Space size={4}>
                        {code && <Tag color="red">{code}</Tag>}
                        <Typography.Text ellipsis style={{ maxWidth: 380 }} title={reason}>
                          {reason}
                        </Typography.Text>
                      </Space>
                    );
                  },
                },
              {
                  title: t('pausedRuns.col.atNode'), width: 200, ellipsis: true,
                  render: (_, r) => {
                    const n = r.nodeName || r.pauseNodeKey;
                    return n
                      ? <Space size={4}>
                          <Typography.Text code>{n}</Typography.Text>
                          {r.nodeStatus && <Tag>{r.nodeStatus}</Tag>}
                        </Space>
                      : '—';
                  },
                },
              {
                title: t('pausedRuns.col.duration'), width: 110,
                render: (_, r) => {
                  if (!r.pausedAt) return '—';
                  const sec = Math.max(0,
                    Math.floor((Date.now() - new Date(r.pausedAt).getTime()) / 1000));
                  const m = Math.floor(sec / 60);
                  if (m < 1) return t('pausedRuns.duration.underMinute');
                  if (m < 60) return t('pausedRuns.duration.minutes', { m });
                  return t('pausedRuns.duration.hours', { h: Math.floor(m / 60), m: m % 60 });
                },
              },
              {
                title: t('pausedRuns.col.action'), width: 200,
                render: (_, r) => (
                  <Space size={4}>
                    <Button
                      size="small" type="primary" ghost {...ro}
                      icon={<PlayCircleOutlined />}
                      loading={busy === r.runId}
                      onClick={() => resumeOne(r)}
                    >
                      {r.pauseKind === 'paused_unknown'
                        ? t('pausedRuns.action.countAndContinue')
                        : t('pausedRuns.action.continue')}
                    </Button>
                    <Tooltip title={t('pausedRuns.tooltip.dismiss')}>
                      <Button
                        size="small" danger icon={<StopOutlined />} {...ro}
                        loading={busy === r.runId}
                        onClick={() => Modal.confirm({
                          title: t('pausedRuns.confirm.dismissOneTitle', { id: r.runId }),
                          content: t('pausedRuns.confirm.dismissOneContent'),
                          okText: t('pausedRuns.confirm.dismissOk'),
                          okButtonProps: { danger: true },
                          onOk: () => dismissOne(r.runId),
                        })}
                      >
                        {t('pausedRuns.action.dismiss')}
                      </Button>
                    </Tooltip>
                  </Space>
                ),
              },
            ]}
          />

          {filterMeta && (
            <Alert
              style={{ marginTop: 12 }}
              type={filterMeta.auto ? 'info' : 'warning'}
              showIcon
              icon={filterMeta.auto ? <ReloadOutlined /> : <WarningOutlined />}
              message={filterMeta.hint}
            />
          )}
        </Card>
      )}

      {/* 板位校正 */}
      <Modal
        title={t('pausedRuns.plate.title', { id: locateRun ?? '' })}
        open={locateOpen}
        onCancel={() => setLocateOpen(false)}
        okText={t('pausedRuns.plate.ok')}
        okButtonProps={{ disabled: ro.disabled, title: ro.title }}
        confirmLoading={busy != null}
        onOk={async () => {
          if (!barcode.trim() || !location.trim()) {
            message.warning(t('pausedRuns.plate.warn'));
            return;
          }
          if (locateRun == null) return;
          await act(
            locateRun,
            () => api.resumeRun(locateRun, 'continue', { [barcode.trim()]: location.trim() }),
            t('pausedRuns.plate.toast'),
          );
          setLocateOpen(false);
        }}
      >
        <Alert
          type="warning" showIcon style={{ marginBottom: 12 }}
          message={t('pausedRuns.plate.alertTitle')}
          description={t('pausedRuns.plate.alertDesc')}
        />
        <Space direction="vertical" style={{ width: '100%' }}>
          <Input
            placeholder={t('pausedRuns.plate.placeholderId')}
            value={barcode}
            onChange={(e) => setBarcode(e.target.value)}
          />
          <Input
            placeholder={t('pausedRuns.plate.placeholderPos')}
            value={location}
            onChange={(e) => setLocation(e.target.value)}
          />
        </Space>
      </Modal>
    </Space>
  );
}
