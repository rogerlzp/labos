import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Card, Empty, Input, InputNumber, Modal, Space, Table, Tag, Typography, message } from 'antd';
import { apiFetch } from '../api/client';
import { api, type AlertItem } from '../api/client';
import { useReadOnly } from '../auth';
import { useT } from '../i18n';
import type { MessageKey } from '../i18n/core';

const SEVERITY_COLOR: Record<string, string> = { error: 'red', warn: 'orange', info: 'default' };
// 文案走文案包（key），颜色是固定映射 —— 状态码 → 文案 → 颜色三件事分开
const STATE_META: Record<string, { color: string; labelKey: MessageKey }> = {
  open: { color: 'red', labelKey: 'alerts.state.open' },
  acked: { color: 'blue', labelKey: 'alerts.state.acked' },
  closed: { color: 'green', labelKey: 'alerts.state.closed' },
  silenced: { color: 'default', labelKey: 'alerts.state.silenced' },
};

/** 告警：按「错误码|设备」聚合，可认领/关闭。 */

/**
 * 心跳参数：间隔 + 离线阈值。
 *
 * <p>两个值原来只能改环境变量再重启 API —— 现场调"多久没响应算掉线"是日常动作，
 * 重启会打断正在跑的实验。现在改完**下一个探测周期就生效**，并落到 sys_setting。
 *
 * <p>离线阈值必须 ≥ 2 个探测周期，否则每次探测前都会先被判一次离线
 * （后端也会夹紧，这里只做提示）。
 */
function HeartbeatSettings() {
  const qc = useQueryClient();
  // 心跳参数是全局配置（写 sys_setting）：viewer 只能看
  const ro = useReadOnly();
  const t = useT();
  const [saving, setSaving] = useState(false);
  const [iv, setIv] = useState<number | null>(null);
  const [off, setOff] = useState<number | null>(null);

  const q = useQuery({
    queryKey: ['hb-settings'],
    queryFn: () => apiFetch('/api/settings/heartbeat').then((r) => r.json()) as Promise<{
      intervalMs: number; offlineMs: number;
    }>,
  });

  // 首次拿到数据时填进输入框（之后用户改的不覆盖）
  const data = q.data;
  if (data && iv == null && off == null) {
    setIv(data.intervalMs);
    setOff(data.offlineMs);
  }

  const save = async () => {
    setSaving(true);
    try {
      const r = await apiFetch('/api/settings/heartbeat', {
        method: 'PUT',
        body: JSON.stringify({ intervalMs: iv, offlineMs: off }),
      });
      const j = await r.json();
      if (!r.ok) {
        message.error(j.error ?? t('alerts.error.saveFailed'));
        return;
      }
      setIv(j.intervalMs);
      setOff(j.offlineMs);
      message.success(t('alerts.toast.saved', {
        interval: j.intervalMs / 1000, offline: j.offlineMs / 1000,
      }));
      qc.invalidateQueries({ queryKey: ['hb-settings'] });
    } finally {
      setSaving(false);
    }
  };

  return (
    <Card size="small" title={t('alerts.heartbeat.title')}
      extra={<Button size="small" type="primary" loading={saving} {...ro} onClick={save}>
        {t('alerts.heartbeat.save')}
      </Button>}>
      <Space size={24} wrap>
        <Space size={6}>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('alerts.heartbeat.interval')}</Typography.Text>
          <InputNumber size="small" min={1} max={600} value={iv == null ? null : iv / 1000}
            addonAfter={t('alerts.heartbeat.seconds')} style={{ width: 130 }}
            onChange={(v) => setIv(v == null ? null : Number(v) * 1000)} />
        </Space>
        <Space size={6}>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('alerts.heartbeat.offline')}</Typography.Text>
          <InputNumber size="small" min={2} max={3600} value={off == null ? null : off / 1000}
            addonAfter={t('alerts.heartbeat.seconds')} style={{ width: 130 }}
            onChange={(v) => setOff(v == null ? null : Number(v) * 1000)} />
        </Space>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {t('alerts.heartbeat.hint')}
        </Typography.Text>
      </Space>
    </Card>
  );
}

export default function AlertsPage() {
  const qc = useQueryClient();
  // 认领/关闭告警与心跳参数保存都是写操作：viewer 只读
  const ro = useReadOnly();
  const { data, isLoading } = useQuery({ queryKey: ['alerts'], queryFn: () => api.alerts(100) });
  const [acting, setActing] = useState<{ fp: string; action: 'ack' | 'close' } | null>(null);
  const [who, setWho] = useState('');
  const [note, setNote] = useState('');
  const t = useT();

  const items = data?.items ?? [];
  const pending = items.filter((a) => a.state === 'open').length;

  const submit = async () => {
    if (!acting) return;
    try {
      await api.alertAct(acting.fp, acting.action, {
        assignee: acting.action === 'ack' ? who : undefined,
        note: note || undefined,
      });
      message.success(acting.action === 'ack' ? t('alerts.toast.acked') : t('alerts.toast.closed'));
      setActing(null);
      setNote('');
      await qc.invalidateQueries({ queryKey: ['alerts'] });
    } catch (e) {
      message.error(String((e as Error).message));
    }
  };

  return (
    <>
      <div style={{ marginBottom: 12 }}>
        <HeartbeatSettings />
      </div>
      <Card
        size="small"
        title={t('alerts.title', { pending, total: items.length })}
        extra={<Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {t('alerts.hint')}
        </Typography.Text>}
      >
        {items.length === 0 ? <Empty description={t('alerts.empty')} /> : (
          <Table<AlertItem>
            rowKey="fingerprint" size="small" loading={isLoading}
            dataSource={items}
            pagination={{ pageSize: 15, size: 'small' }}
            columns={[
              { title: t('alerts.col.fingerprint'), dataIndex: 'fingerprint', width: 220 },
              { title: t('alerts.col.hits'), dataIndex: 'count', width: 70 },
              {
                title: t('alerts.col.severity'), dataIndex: 'severity', width: 80,
                render: (v) => <Tag color={SEVERITY_COLOR[v] ?? 'default'}>{v}</Tag>,
              },
              {
                title: t('alerts.col.lastMessage'), dataIndex: 'lastMessage', ellipsis: true,
                render: (v) => <Typography.Text style={{ fontSize: 12 }}>{v}</Typography.Text>,
              },
              {
                title: t('alerts.col.disposition'), width: 110,
                render: (_, r) => {
                  const s = STATE_META[r.state] ?? STATE_META.open;
                  return (
                    <Tag color={s.color}>
                      {t(s.labelKey)}{r.assignee ? ` · ${r.assignee}` : ''}
                    </Tag>
                  );
                },
              },
              {
                title: t('alerts.col.action'), width: 150,
                render: (_, r) => (
                  <Space size={4}>
                    {r.state !== 'closed' && (
                      <>
                        <Button size="small" type="link" {...ro}
                          onClick={() => { setActing({ fp: r.fingerprint, action: 'ack' }); }}>
                          {t('alerts.action.ack')}
                        </Button>
                        <Button size="small" type="link" danger {...ro}
                          onClick={() => { setActing({ fp: r.fingerprint, action: 'close' }); }}>
                          {t('alerts.action.close')}
                        </Button>
                      </>
                    )}
                  </Space>
                ),
              },
            ]}
          />
        )}
      </Card>

      <Modal
        open={acting != null}
        title={acting?.action === 'ack' ? t('alerts.modal.ackTitle') : t('alerts.modal.closeTitle')}
        onCancel={() => setActing(null)}
        onOk={submit}
        okText={t('alerts.modal.ok')}
      >
        <Space direction="vertical" style={{ width: '100%' }}>
          <Typography.Text code>{acting?.fp}</Typography.Text>
          {acting?.action === 'ack' && (
            <Input addonBefore={t('alerts.modal.assignee')} value={who}
            onChange={(e) => setWho(e.target.value)} />
          )}
          <Input.TextArea rows={3} placeholder={t('alerts.modal.note')}
            value={note} onChange={(e) => setNote(e.target.value)} />
        </Space>
      </Modal>
    </>
  );
}
