import { apiFetch } from '../api/client';
import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Card, Select, Space, Table, Tag, Typography } from 'antd';
import { useT } from '../i18n';

interface AuditRow {
  occurredAt: string;
  username: string;
  action: string;
  entityType: string | null;
  entityId: number | null;
  result: string;
  ip: string | null;
  after: Record<string, unknown> | null;
}

const ACTION_COLOR = (a: string) => {
  if (a.startsWith('auth.login')) return a.includes('fail') ? 'red' : 'default';
  if (a === 'run.start') return 'blue';
  if (a.startsWith('user.')) return 'purple';
  if (a.startsWith('policy.')) return 'orange';
  if (a.startsWith('alert.')) return 'cyan';
  return 'default';
};

/**
 * 审计日志：谁在什么时候对什么做了什么。
 *
 * 危险操作（执行工艺、改策略、处置告警、建改账号）全部留痕，
 * 登录失败也记——这是安全审计的基本要求。
 */
export default function AuditPage() {
  const [action, setAction] = useState<string>('all');

  const { data, isLoading } = useQuery({
    queryKey: ['audit', action],
    queryFn: () => apiFetch(
      `/api/audit${action === 'all' ? '' : `?action=${action}`}`,
      { headers: { Authorization: `Bearer ${localStorage.getItem('labos_token')}` } },
    ).then((r) => r.json()) as Promise<{ items: AuditRow[] }>,
  });

  const t = useT();
  const items = data?.items ?? [];

  return (
    <Card
      size="small"
      title={t('audit.title', { n: items.length })}
      extra={
        <Space>
          <Select
            style={{ width: 180 }}
            value={action}
            onChange={setAction}
            options={[
              { value: 'all', label: t('audit.filter.all') },
              { value: 'auth.login', label: t('audit.action.authLogin') },
              { value: 'run.start', label: t('audit.action.runStart') },
              { value: 'policy.update', label: t('audit.action.policyUpdate') },
              { value: 'alert.ack', label: t('audit.action.alertAck') },
              { value: 'alert.close', label: t('audit.action.alertClose') },
              { value: 'user.create', label: t('audit.action.userCreate') },
              { value: 'user.update', label: t('audit.action.userUpdate') },
              { value: 'schedulePlan.create', label: t('audit.action.schedulePlanCreate') },
              { value: 'schedulePlan.update', label: t('audit.action.schedulePlanUpdate') },
              { value: 'resourceBooking.create', label: t('audit.action.resourceBookingCreate') },
            ]}
          />
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {t('audit.hint')}
          </Typography.Text>
        </Space>
      }
    >
      <Table<AuditRow>
        rowKey={(r, i) => `${r.occurredAt}-${i}`}
        size="small" loading={isLoading}
        dataSource={items}
        pagination={{ pageSize: 20, size: 'small' }}
        columns={[
          {
            title: t('audit.col.time'), dataIndex: 'occurredAt', width: 180,
            render: (v: string) => (
              <span style={{ fontFamily: 'monospace', fontSize: 12 }}>
                {v.slice(0, 19).replace('T', ' ')}
              </span>
            ),
          },
          { title: t('audit.col.user'), dataIndex: 'username', width: 110 },
          {
            title: t('audit.col.action'), dataIndex: 'action', width: 150,
            render: (v: string) => <Tag color={ACTION_COLOR(v)}>{v}</Tag>,
          },
          {
            title: t('audit.col.target'), width: 180,
            render: (_, r) => (
              <Typography.Text style={{ fontSize: 12 }}>
                {r.entityType ?? '—'}{r.entityId ? ` #${r.entityId}` : ''}
              </Typography.Text>
            ),
          },
          {
            title: t('audit.col.result'), dataIndex: 'result', width: 80,
            render: (v: string) => (
              <Tag color={v === 'ok' ? 'green' : 'red'}>{v}</Tag>
            ),
          },
          {
            title: t('audit.col.detail'), ellipsis: true,
            render: (_, r) => (
              <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                {r.after ? JSON.stringify(r.after) : '—'}
              </Typography.Text>
            ),
          },
          { title: 'IP', dataIndex: 'ip', width: 130 },
        ]}
      />
    </Card>
  );
}
