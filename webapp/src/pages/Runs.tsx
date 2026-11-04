import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Card, Empty, Progress, Space, Table, Tag, Typography, message } from 'antd';
import { useNavigate } from 'react-router-dom';
import { api, apiFetch, type ProcessStatusItem } from '../api/client';
import { statusColor, statusLabel, useStatusVocabulary } from '../utils/status';
import { useReadOnly } from '../auth';
import { useT } from '../i18n';

/** 崩溃中断、可续跑的单子（/api/runs/recoverable）。 */
interface RecoverableRun {
  runId: number; runNo: string;
  totalNodes: number; completedNodes: number; failedNodes: number;
}

/** 实时看板：回答"现在有没有在跑、卡在哪"。数据来自内存态（2 秒轮询）。 */
export default function RunsPage() {
  const nav = useNavigate();
  const qc = useQueryClient();
  const t = useT();
  // current.status 是**节点**状态（不是 run 状态），走 node scope
  const vocab = useStatusVocabulary();
  // 从断点继续 / 忽略 / 切换值守都会写库或改执行策略：viewer 只读
  const ro = useReadOnly();
  const { data, error } = useQuery({
    queryKey: ['process-status'],
    queryFn: api.processStatus,
    refetchInterval: 2000,
  });

  // 上次进程崩溃时被中断、且能接着跑的单子。
  // 后端早就有 /api/runs/recoverable 和 /api/runs/{id}/dismiss，
  // 但界面一直没接 —— 崩溃恢复等于只能看日志。
  const { data: recov } = useQuery({
    queryKey: ['recoverable'],
    queryFn: () => apiFetch('/api/runs/recoverable').then((r) => r.json()),
    refetchInterval: 15000,
  });
  const orphans = recov?.items ?? [];

  const resumeOrphan = async (runId: number) => {
    const r = await apiFetch(`/api/runs/${runId}/resume?action=continue`, { method: 'POST' });
    const j = await r.json();
    if (j.ok) {
      message.success(t('runs.toast.resumed', { id: runId }));
      qc.invalidateQueries({ queryKey: ['recoverable'] });
      qc.invalidateQueries({ queryKey: ['process-status'] });
      nav(`/runs/${runId}`);
    } else {
      message.error(j.error ?? t('runs.toast.resumeFailed'));
    }
  };

  const dismissOrphan = async (runId: number) => {
    await apiFetch(`/api/runs/${runId}/dismiss`, { method: 'POST' });
    message.success(t('runs.toast.dismissed', { id: runId }));
    qc.invalidateQueries({ queryKey: ['recoverable'] });
  };

  if (error) {
    return <Alert type="error" showIcon message={t('runs.error.connect')}
      description={String((error as Error).message)} />;
  }

  const items = data?.items ?? [];

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      {orphans.length > 0 && (
        <Card size="small" title={t('runs.orphan.title', { count: orphans.length })}
          extra={<Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {t('runs.orphan.desc')}
          </Typography.Text>}>
          <Space direction="vertical" size={8} style={{ width: '100%' }}>
            {orphans.map((o: RecoverableRun) => (
              <Space key={o.runId} size={12} wrap>
                <Button size="small" type="link" onClick={() => nav(`/runs/${o.runId}`)}>
                  #{o.runId}
                </Button>
                <Typography.Text code style={{ fontSize: 12 }}>{o.runNo}</Typography.Text>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {t('runs.orphan.progress', {
                    done: o.completedNodes, total: o.totalNodes,
                  })}
                  {o.failedNodes > 0 && t('runs.orphan.failed', { count: o.failedNodes })}
                </Typography.Text>
                <Button size="small" type="primary" ghost {...ro} onClick={() => resumeOrphan(o.runId)}>
                  {t('runs.action.resume')}
                </Button>
                <Button size="small" {...ro} onClick={() => dismissOrphan(o.runId)}>
                  {t('runs.action.dismiss')}
                </Button>
              </Space>
            ))}
          </Space>
        </Card>
      )}
      <Card size="small" title={t('runs.running.title', { count: items.length })}
        extra={<Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {t('runs.source', {
            source: data?.source === 'memory' ? t('runs.source.memory') : t('runs.source.db'),
          })}
        </Typography.Text>}>
        {items.length === 0 ? (
          <Empty description={t('runs.empty')} />
        ) : (
          <Table<ProcessStatusItem>
            rowKey="runId" size="small" pagination={false}
            dataSource={items}
            columns={[
              { title: t('runs.col.run'), dataIndex: 'runId', width: 90,
                render: (v: number) => (
                  <a onClick={() => nav(`/runs/${v}`)}>{v}</a>
                ) },
              {
                title: t('runs.col.progress'), width: 170,
                render: (_, r) => (
                  <Progress size="small"
                    percent={r.total ? Math.round((r.done * 100) / r.total) : 0}
                    format={() => `${r.done}/${r.total}`} />
                ),
              },
              {
                title: t('runs.col.node'),
                render: (_, r) => r.current?.name
                  || <Typography.Text type="secondary">{t('runs.node.waiting')}</Typography.Text>,
              },
              { title: t('runs.col.device'), width: 80, render: (_, r) => r.current?.resourceId ?? '—' },
              {
                title: t('runs.col.status'), width: 100,
                render: (_, r) => {
                  const code = r.current?.status;
                  // 有节点状态就显示词表文案；没有当前节点时保持「—」，不要显示空 Tag
                  return (
                    <Tag color={statusColor('node', code ?? '', vocab)}>
                      {code ? statusLabel('node', code, vocab) : '—'}
                    </Tag>
                  );
                },
              },
              {
                title: t('runs.col.attendance'), width: 80,
                render: (_, r) => (r.attendance === 'unattended'
                  ? <Tag color="purple">{t('runs.attendance.unattended')}</Tag>
                  : <Tag>{t('runs.attendance.attended')}</Tag>),
              },
              {
                title: t('runs.col.action'), width: 110,
                render: (_, r) => (
                  <Button size="small" {...ro} onClick={async () => {
                    const next = r.attendance === 'unattended' ? 'attended' : 'unattended';
                    const res = await api.setAttendance(r.runId, next);
                    message.success(res.updated
                      ? t('runs.toast.attendanceSwitched', {
                        state: next === 'unattended'
                          ? t('runs.attendance.unattended')
                          : t('runs.attendance.attended'),
                      })
                      : t('runs.toast.runEnded'));
                  }}>{t('runs.action.switchAttendance')}</Button>
                ),
              },
            ]}
          />
        )}
      </Card>

      <Card size="small" title={t('runs.note.title')}>
        <Typography.Paragraph type="secondary" style={{ marginBottom: 0, fontSize: 12 }}>
          {t('runs.note.body')}
        </Typography.Paragraph>
      </Card>
    </Space>
  );
}
