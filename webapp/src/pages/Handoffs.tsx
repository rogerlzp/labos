import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Card, Empty, Input, Modal, Space, Table, Tag, Typography, message } from 'antd';
import { apiFetch } from '../api/client';
import { useT } from '../i18n';
import { useReadOnly } from '../auth';

interface Handoff {
  id: number;
  experimentId: number;
  experimentNo?: string | null;
  fromWorkcellId: number;
  fromName?: string | null;
  toWorkcellId: number;
  toName?: string | null;
  status: string;
  carrier?: string | null;
  remark?: string | null;
}

/** N-10 跨 Workcell Handoff 处置页：配置 → 人工确认后才允许执行。 */
export default function HandoffsPage() {
  const t = useT();
  const nav = useNavigate();
  const qc = useQueryClient();
  const ro = useReadOnly();
  const [current, setCurrent] = useState<Handoff | null>(null);
  const [note, setNote] = useState('');

  const handoffs = useQuery({
    queryKey: ['handoffs'],
    queryFn: () => apiFetch('/api/handoffs')
      .then((r) => r.json() as Promise<{ items: Handoff[] }>),
    refetchInterval: 20000,
  });

  const confirm = async () => {
    if (!current) return;
    await apiFetch('/api/experiments/' + current.experimentId + '/handoffs/confirm', {
      method: 'POST',
      body: JSON.stringify({
        fromWorkcellId: current.fromWorkcellId,
        toWorkcellId: current.toWorkcellId,
        note,
      }),
    });
    message.success(t('handoffs.toast.ok'));
    setCurrent(null);
    setNote('');
    await qc.invalidateQueries({ queryKey: ['handoffs'] });
  };

  return (
    <Card
      title={t('handoffs.title')}
      extra={(
        <Space>
          <Typography.Text type="secondary">{t('handoffs.hint')}</Typography.Text>
          <Button onClick={() => handoffs.refetch()}>{t('handoffs.refresh')}</Button>
        </Space>
      )}
    >
      <Table<Handoff>
        rowKey="id"
        size="small"
        loading={handoffs.isLoading}
        dataSource={handoffs.data?.items ?? []}
        locale={{ emptyText: <Empty description={t('handoffs.empty')} /> }}
        columns={[
          {
            title: t('handoffs.col.experiment'),
            render: (_, h) => (
              <a onClick={() => nav('/experiments/' + h.experimentId)}>
                {h.experimentNo ?? ('#' + h.experimentId)}
              </a>
            ),
          },
          {
            title: t('handoffs.col.route'),
            render: (_, h) => (
              <Space size={4}>
                <Tag>{h.fromName ?? h.fromWorkcellId}</Tag>
                <span>→</span>
                <Tag>{h.toName ?? h.toWorkcellId}</Tag>
              </Space>
            ),
          },
          { title: t('handoffs.col.carrier'), dataIndex: 'carrier', width: 110 },
          {
            title: t('handoffs.col.status'),
            width: 120,
            render: (_, h) => (
              <Tag color={h.status === 'confirmed' ? 'green' : 'orange'}>{h.status}</Tag>
            ),
          },
          { title: t('handoffs.col.remark'), dataIndex: 'remark', ellipsis: true },
          {
            title: t('handoffs.col.operate'),
            width: 140,
            render: (_, h) => (
              <Button size="small" disabled={ro.disabled || h.status === 'confirmed'}
                title={ro.title} onClick={() => setCurrent(h)}>
                {h.status === 'confirmed' ? t('handoffs.confirmed') : t('handoffs.confirm')}
              </Button>
            ),
          },
        ]}
      />

      <Modal
        open={current != null}
        title={t('handoffs.modal.title')}
        onCancel={() => setCurrent(null)}
        onOk={confirm}
        okText={t('handoffs.confirm')}
      >
        <Typography.Paragraph type="secondary">{t('handoffs.tip')}</Typography.Paragraph>
        <Input.TextArea rows={3} value={note} onChange={(e) => setNote(e.target.value)}
          placeholder={t('handoffs.field.note')} />
      </Modal>
    </Card>
  );
}
