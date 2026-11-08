import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Card, Empty, Form, Input, Modal, Space, Table, Tag, Typography, message } from 'antd';
import { apiFetch } from '../api/client';
import { useT } from '../i18n';
import { useReadOnly } from '../auth';

interface QuarantinedRun {
  runId: number;
  runNo: string;
  errorCode?: string | null;
  reason?: string | null;
  createdAt?: string | null;
  experimentId?: number | null;
}

/**
 * P-08 隔离处置页：物理结果未知的 run 集中在这里，人工确认后才能解除。
 */
export default function QuarantinePage() {
  const t = useT();
  const nav = useNavigate();
  const qc = useQueryClient();
  const ro = useReadOnly();
  const [current, setCurrent] = useState<QuarantinedRun | null>(null);
  const [note, setNote] = useState('');
  const [plate, setPlate] = useState('');

  const runs = useQuery({
    queryKey: ['quarantined-runs'],
    queryFn: () => apiFetch('/api/runs/quarantined')
      .then((r) => r.json() as Promise<{ items: QuarantinedRun[] }>),
    refetchInterval: 20000,
  });

  const release = async () => {
    if (!current) return;
    await apiFetch('/api/runs/' + current.runId + '/quarantine/release', {
      method: 'POST',
      body: JSON.stringify({ note, plateBarcode: plate }),
    });
    message.success(t('quarantine.toast.ok'));
    setCurrent(null);
    setNote('');
    setPlate('');
    await qc.invalidateQueries({ queryKey: ['quarantined-runs'] });
  };

  return (
    <Card
      title={t('quarantine.title')}
      extra={(
        <Space>
          <Typography.Text type="secondary">{t('quarantine.hint')}</Typography.Text>
          <Button onClick={() => runs.refetch()}>{t('quarantine.refresh')}</Button>
        </Space>
      )}
    >
      <Table<QuarantinedRun>
        rowKey="runId"
        size="small"
        loading={runs.isLoading}
        dataSource={runs.data?.items ?? []}
        locale={{ emptyText: <Empty description={t('quarantine.empty')} /> }}
        columns={[
          {
            title: t('quarantine.col.run'),
            render: (_, r) => (
              <Space size={6}>
                <a onClick={() => nav('/runs/' + r.runId)}>{r.runNo}</a>
                <Typography.Text type="secondary">#{r.runId}</Typography.Text>
              </Space>
            ),
          },
          { title: t('quarantine.col.error'), dataIndex: 'errorCode', width: 160 },
          { title: t('quarantine.col.reason'), dataIndex: 'reason', ellipsis: true },
          {
            title: t('quarantine.col.experiment'),
            dataIndex: 'experimentId',
            width: 110,
            render: (v: number | null) => (v ? <a onClick={() => nav('/experiments/' + v)}>#{v}</a> : '—'),
          },
          { title: t('quarantine.col.created'), dataIndex: 'createdAt', width: 180 },
          {
            title: t('quarantine.col.operate'),
            width: 140,
            render: (_, r) => (
              <Button size="small" danger disabled={ro.disabled} title={ro.title}
                onClick={() => setCurrent(r)}>
                {t('quarantine.release')}
              </Button>
            ),
          },
        ]}
      />

      <Modal
        open={current != null}
        title={t('quarantine.modal.title')}
        onCancel={() => setCurrent(null)}
        onOk={release}
        okText={t('quarantine.release')}
      >
        <Form layout="vertical">
          <Form.Item label={t('quarantine.field.plate')}>
            <Input value={plate} onChange={(e) => setPlate(e.target.value)} />
          </Form.Item>
          <Form.Item label={t('quarantine.field.note')}>
            <Input.TextArea rows={3} value={note} onChange={(e) => setNote(e.target.value)} />
          </Form.Item>
          <Tag color="red">{t('quarantine.tip')}</Tag>
        </Form>
      </Modal>
    </Card>
  );
}
