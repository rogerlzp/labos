import { useMemo, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert, Button, Card, Empty, Input, Modal, Popconfirm, Space, Statistic,
  Table, Tag, Typography, message,
} from 'antd';
import {
  CheckCircleOutlined, ExclamationCircleOutlined, ReloadOutlined,
  SearchOutlined, WarningOutlined,
} from '@ant-design/icons';
import { apiFetch } from '../api/client';
import { useReadOnly } from '../auth';
import { useT } from '../i18n';
import type { MessageKey } from '../i18n/core';

/**
 * 板位盘点。
 *
 * <p>为什么需要这一页：M4 的板位三态（KNOWN / UNKNOWN / LOST）在系统里
 * 只是一个字段，**现场的人得知道自己该去确认哪块板**。
 * 没有这一页之前，操作员只能对着日志和 curl 猜。
 *
 * <p>两条硬规则（设计文档 D13「不猜」）：
 * <ul>
 *   <li>{@code UNKNOWN} —— 板可能在这几个位置之一，必须**到现场看**后如实填写</li>
 *   <li>{@code LOST} —— 已确认丢失，不能再当"可能在某处"继续排流程</li>
 * </ul>
 * 系统绝不自动"猜"一个位置——猜错就是机械臂撞上去。
 */
interface UnknownPlate {
  barcode: string;
  positionStatus: 'UNKNOWN' | 'LOST';
  possibleLocations: string[];
}

// 文案走文案包（key），颜色固定 —— 状态码 → 文案 → 颜色三件事分开
const STATUS: Record<string, { color: string; labelKey: MessageKey; hintKey: MessageKey }> = {
  UNKNOWN: {
    color: 'orange',
    labelKey: 'plates.state.unknown.label',
    hintKey: 'plates.state.unknown.hint',
  },
  LOST: {
    color: 'red',
    labelKey: 'plates.state.lost.label',
    hintKey: 'plates.state.lost.hint',
  },
};

export default function PlatesPage() {
  const qc = useQueryClient();
  // 报位置/登记丢失都是写操作：viewer 只能看板位盘点结果
  const ro = useReadOnly();
  const t = useT();
  const [target, setTarget] = useState<UnknownPlate | null>(null);
  const [locate, setLocate] = useState('');
  const [busy, setBusy] = useState(false);

  const { data, isLoading, refetch, isFetching } = useQuery({
    queryKey: ['plates-unknown'],
    queryFn: () => apiFetch('/api/plates/unknown').then((r) => r.json() as Promise<UnknownPlate[]>),
    refetchInterval: 20_000,
  });

  const rows = data ?? [];
  const stat = useMemo(() => ({
    unknown: rows.filter((r) => r.positionStatus === 'UNKNOWN').length,
    lost: rows.filter((r) => r.positionStatus === 'LOST').length,
  }), [rows]);

  const submit = async (barcode: string, actualLocation: string) => {
    setBusy(true);
    try {
      const r = await apiFetch(`/api/plates/${encodeURIComponent(barcode)}/locate`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ actualLocation }),
      });
      const j = await r.json() as { ok?: boolean; error?: string };
      if (j.error) {
        message.error(j.error);
        return;
      }
      message.success(actualLocation === 'LOST'
        ? t('plates.toast.lost', { barcode })
        : t('plates.toast.located', { barcode, location: actualLocation }));
      setTarget(null);
      setLocate('');
      await qc.invalidateQueries({ queryKey: ['plates-unknown'] });
    } catch (e) {
      message.error(t('plates.error.inventory', { msg: String((e as Error).message) }));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div style={{ padding: 16 }}>
      <Typography.Title level={4} style={{ marginTop: 0 }}>
        {t('plates.title')}
        <Typography.Text type="secondary" style={{ marginLeft: 12, fontSize: 13, fontWeight: 400 }}>
          {t('plates.hint')}
        </Typography.Text>
      </Typography.Title>

      <Space size={16} style={{ marginBottom: 16 }}>
        <Card size="small" style={{ minWidth: 150 }}>
          <Statistic
            title={t('plates.filter.unknown')}
            value={stat.unknown}
            valueStyle={{ color: stat.unknown > 0 ? '#fa8c16' : undefined }}
            prefix={<WarningOutlined />}
          />
        </Card>
        <Card size="small" style={{ minWidth: 150 }}>
          <Statistic
            title={t('plates.filter.lost')}
            value={stat.lost}
            valueStyle={{ color: stat.lost > 0 ? '#cf1322' : undefined }}
            prefix={<ExclamationCircleOutlined />}
          />
        </Card>
        <Button icon={<ReloadOutlined />} onClick={() => refetch()} loading={isFetching}>
          {t('plates.action.refresh')}
        </Button>
      </Space>

      {rows.length === 0 && !isLoading ? (
        <Card>
          <Empty
            image={<CheckCircleOutlined style={{ fontSize: 56, color: '#52c41a' }} />}
            description={
              <span>
                {t('plates.empty.title')}
                <Typography.Text type="secondary" style={{ display: 'block', marginTop: 4 }}>
                  {t('plates.empty.desc')}
                </Typography.Text>
              </span>
            }
          />
        </Card>
      ) : (
        <Table<UnknownPlate>
          rowKey="barcode"
          loading={isLoading}
          dataSource={rows}
          pagination={false}
          size="middle"
          columns={[
            {
              title: t('plates.col.barcode'),
              dataIndex: 'barcode',
              width: 180,
              render: (v: string) => <Typography.Text strong copyable>{v}</Typography.Text>,
            },
            {
              title: t('plates.col.status'),
              dataIndex: 'positionStatus',
              width: 130,
              render: (v: string) => (
                <Tag color={STATUS[v]?.color}>{t(STATUS[v]?.labelKey ?? 'plates.state.unknown.label')}</Tag>
              ),
            },
            {
              title: t('plates.col.candidates'),
              dataIndex: 'possibleLocations',
              render: (locs: string[], row) => (
                locs.length === 0
                  ? <Typography.Text type="secondary">{t('plates.empty.candidates')}</Typography.Text>
                  : (
                    <Space size={[4, 4]} wrap>
                      {locs.map((l) => (
                        <Tag
                          key={l}
                          style={{ cursor: 'pointer' }}
                          onClick={() => {
                            setTarget(row);
                            setLocate(l);
                          }}
                        >
                          {l}
                        </Tag>
                      ))}
                    </Space>
                  )
              ),
            },
            {
              title: t('plates.col.action'),
              width: 190,
              render: (_, row) => (
                <Space>
                  <Button
                    size="small"
                    type="primary"
                    icon={<SearchOutlined />}
                    {...ro}
                    onClick={() => { setTarget(row); setLocate(''); }}
                  >
                    {t('plates.action.report')}
                  </Button>
                  <Popconfirm
                    title={t('plates.confirm.lostTitle', { barcode: row.barcode })}
                    description={t('plates.confirm.lostDesc')}
                    okText={t('plates.confirm.lostOk')}
                    okButtonProps={{ danger: true }}
                    onConfirm={() => submit(row.barcode, 'LOST')}
                  >
                    <Button size="small" danger {...ro}>{t('plates.action.lost')}</Button>
                  </Popconfirm>
                </Space>
              ),
            },
          ]}
        />
      )}

      <Modal
        open={!!target}
        title={target ? t('plates.modal.title', { barcode: target.barcode }) : ''}
        okText={t('plates.modal.ok')}
        cancelText={t('plates.modal.cancel')}
        confirmLoading={busy}
        okButtonProps={{ disabled: ro.disabled || !locate.trim(), title: ro.title }}
        onCancel={() => { setTarget(null); setLocate(''); }}
        onOk={() => submit(target!.barcode, locate.trim())}
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message={t('plates.modal.warnTitle')}
          description={
            target
              ? t(STATUS[target.positionStatus]?.hintKey ?? 'plates.modal.hint')
              : ''
          }
        />
        <Typography.Text type="secondary">{t('plates.modal.field.location')}</Typography.Text>
        <Input
          value={locate}
          onChange={(e) => setLocate(e.target.value)}
          placeholder={t('plates.modal.placeholder.location')}
          style={{ marginTop: 6 }}
          onPressEnter={() => locate.trim() && submit(target!.barcode, locate.trim())}
        />
        {target && target.possibleLocations.length > 0 && (
          <div style={{ marginTop: 10 }}>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {t('plates.modal.candidates')}
            </Typography.Text>
            <div style={{ marginTop: 4 }}>
              <Space size={[4, 4]} wrap>
                {target.possibleLocations.map((l) => (
                  <Tag key={l} style={{ cursor: 'pointer' }} onClick={() => setLocate(l)}>
                    {l}
                  </Tag>
                ))}
              </Space>
            </div>
          </div>
        )}
      </Modal>
    </div>
  );
}
