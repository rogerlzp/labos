import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Button, Card, Empty, Form, Input, Modal, Space, Table, Tag, Typography, message,
} from 'antd';
import { apiFetch } from '../api/client';
import { useT } from '../i18n';
import { useReadOnly } from '../auth';

interface ManualWindow {
  windowId: number;
  runId: number;
  windowKey: string;
  errorCode?: string | null;
  nodeKey?: string | null;
  strategy?: string | null;
  defaultAction: string;
  allowedActions: string[];
  decisionReason: string;
  status: string;
  openedAt?: string | null;
  timeoutAt?: string | null;
}

interface InterventionRecord {
  id: number;
  manualWindowId?: number | null;
  runId?: number | null;
  operator: string;
  action?: string | null;
  plateBarcode?: string | null;
  sampleId?: string | null;
  fromLocation?: string | null;
  toLocation?: string | null;
  result: string;
  note?: string | null;
  createdAt?: string | null;
}

/**
 * 人工值守窗口（N-08 / L-04）：把「等人工确认」的窗口集中到一页，
 * 决策时同时落 L-01 人工介入记录（人/时间/板样本/交接点/结果）。
 */
export default function ManualWindowsPage() {
  const t = useT();
  const nav = useNavigate();
  const qc = useQueryClient();
  const ro = useReadOnly();
  const [current, setCurrent] = useState<ManualWindow | null>(null);
  const [form, setForm] = useState({
    plateBarcode: '', sampleId: '', fromLocation: '', toLocation: '', note: '',
  });

  const windows = useQuery({
    queryKey: ['manual-windows'],
    queryFn: () => apiFetch('/api/manual-windows')
      .then((r) => r.json() as Promise<{ items: ManualWindow[] }>),
    refetchInterval: 20000,
  });
  const records = useQuery({
    queryKey: ['intervention-records'],
    queryFn: () => apiFetch('/api/intervention-records?limit=50')
      .then((r) => r.json() as Promise<{ items: InterventionRecord[] }>),
    refetchInterval: 20000,
  });

  const decide = async (action: string) => {
    if (!current) return;
    await apiFetch('/api/manual-windows/' + current.windowId + '/decide', {
      method: 'POST',
      body: JSON.stringify({ action, ...form }),
    });
    message.success(t('manualWindows.toast.ok'));
    setCurrent(null);
    setForm({ plateBarcode: '', sampleId: '', fromLocation: '', toLocation: '', note: '' });
    await qc.invalidateQueries({ queryKey: ['manual-windows'] });
    await qc.invalidateQueries({ queryKey: ['intervention-records'] });
  };

  const actions = current
    ? (current.allowedActions && current.allowedActions.length > 0
        ? current.allowedActions : ['CONTINUE', 'RETRY', 'SKIP', 'ABORT'])
    : [];

  const actionLabel = (a: string): string => {
    switch (a) {
      case 'RETRY': return t('manualWindows.action.RETRY');
      case 'SKIP': return t('manualWindows.action.SKIP');
      case 'ABORT': return t('manualWindows.action.ABORT');
      case 'MANUAL': return t('manualWindows.action.MANUAL');
      default: return t('manualWindows.action.CONTINUE');
    }
  };

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card
        title={t('manualWindows.title')}
        extra={(
          <Space>
            <Typography.Text type="secondary">{t('manualWindows.hint')}</Typography.Text>
            <Button onClick={() => windows.refetch()}>{t('manualWindows.refresh')}</Button>
          </Space>
        )}
      >
        <Table<ManualWindow>
          rowKey="windowId"
          size="small"
          loading={windows.isLoading}
          dataSource={windows.data?.items ?? []}
          locale={{ emptyText: <Empty description={t('manualWindows.empty')} /> }}
          columns={[
            {
              title: t('manualWindows.col.run'),
              render: (_, w) => (
                <a onClick={() => nav('/runs/' + w.runId)}>run #{w.runId}</a>
              ),
            },
            {
              title: t('manualWindows.col.error'),
              render: (_, w) => (
                <Space direction="vertical" size={0}>
                  <Typography.Text code>{w.errorCode ?? '—'}</Typography.Text>
                  <Typography.Text type="secondary">{w.nodeKey ?? '—'}</Typography.Text>
                </Space>
              ),
            },
            {
              title: t('manualWindows.col.actions'),
              render: (_, w) => (
                <Space size={4} wrap>
                  {(w.allowedActions ?? []).map((a) => <Tag key={a}>{a}</Tag>)}
                </Space>
              ),
            },
            { title: t('manualWindows.col.reason'), dataIndex: 'decisionReason', ellipsis: true },
            {
              title: t('manualWindows.col.timeout'),
              dataIndex: 'timeoutAt',
              width: 170,
              render: (v: string | null) => v ?? '—',
            },
            {
              title: t('manualWindows.col.operate'),
              width: 120,
              render: (_, w) => (
                <Button
                  size="small"
                  disabled={ro.disabled}
                  title={ro.title}
                  onClick={() => { setCurrent(w); }}
                >
                  {t('manualWindows.decide')}
                </Button>
              ),
            },
          ]}
        />
      </Card>

      <Card title={t('manualWindows.records.title')}>
        <Table<InterventionRecord>
          rowKey="id"
          size="small"
          loading={records.isLoading}
          dataSource={records.data?.items ?? []}
          locale={{ emptyText: <Empty description={t('manualWindows.records.empty')} /> }}
          columns={[
            { title: t('manualWindows.records.col.time'), dataIndex: 'createdAt', width: 180 },
            { title: t('manualWindows.records.col.operator'), dataIndex: 'operator', width: 120 },
            { title: t('manualWindows.records.col.action'), dataIndex: 'action', width: 110 },
            { title: t('manualWindows.records.col.run'), dataIndex: 'runId', width: 100 },
            { title: t('manualWindows.records.col.plate'), dataIndex: 'plateBarcode', width: 150 },
            { title: t('manualWindows.records.col.sample'), dataIndex: 'sampleId', width: 130 },
            {
              title: t('manualWindows.records.col.location'),
              render: (_, r) => (r.fromLocation || r.toLocation
                ? (r.fromLocation ?? '—') + ' → ' + (r.toLocation ?? '—')
                : '—'),
            },
            { title: t('manualWindows.records.col.result'), dataIndex: 'result', width: 110 },
          ]}
        />
      </Card>

      <Modal
        open={current != null}
        title={t('manualWindows.modal.title')}
        onCancel={() => setCurrent(null)}
        footer={null}
      >
        <Form layout="vertical">
          <Form.Item label={t('manualWindows.field.plate')}>
            <Input value={form.plateBarcode}
              onChange={(e) => setForm({ ...form, plateBarcode: e.target.value })} />
          </Form.Item>
          <Form.Item label={t('manualWindows.field.sample')}>
            <Input value={form.sampleId}
              onChange={(e) => setForm({ ...form, sampleId: e.target.value })} />
          </Form.Item>
          <Form.Item label={t('manualWindows.field.from')}>
            <Input value={form.fromLocation}
              onChange={(e) => setForm({ ...form, fromLocation: e.target.value })} />
          </Form.Item>
          <Form.Item label={t('manualWindows.field.to')}>
            <Input value={form.toLocation}
              onChange={(e) => setForm({ ...form, toLocation: e.target.value })} />
          </Form.Item>
          <Form.Item label={t('manualWindows.field.note')}>
            <Input.TextArea rows={2} value={form.note}
              onChange={(e) => setForm({ ...form, note: e.target.value })} />
          </Form.Item>
          <Space wrap>
            {actions.map((a) => (
              <Button key={a} type={a === 'CONTINUE' ? 'primary' : 'default'}
                onClick={() => decide(a)}>
                {actionLabel(a)}
              </Button>
            ))}
          </Space>
        </Form>
      </Modal>
    </Space>
  );
}
