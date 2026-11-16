import { useEffect, useMemo, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert, Badge, Button, Card, Descriptions, Empty, Input, Space, Table, Tag,
  Timeline, Typography, message,
} from 'antd';
import { apiFetch } from '../api/client';
import { useReadOnly } from '../auth';
import { useT } from '../i18n';

/**
 * 板特写屏（演示用）：**跟着**一块板，看它现在在哪、什么状态、谁在管它。
 *
 * <p>为什么单独做一屏而不是塞进运行看板：
 * 演示的主角是**一块板**（客户文档 §1.2 的"分段自驱"就发生在一块板身上）。
 * 看板是"系统视角"，这屏是"**它**的视角"—— 板在哪、状态是什么、刚刚发生了什么。
 *
 * <p>数据源（都是**已有**端点，不新增后端）：
 * <ul>
 *   <li>板列表 {@code GET /api/plates?limit=} —— 按最近活动排序；</li>
 *   <li>板详情 {@code GET /api/plates/{barcode}}；</li>
 *   <li>状态历史 {@code GET /api/plates/{barcode}/state-history}；</li>
 *   <li>定位 {@code POST /api/plates/{barcode}/locate}（板在不在某个工位）。</li>
 * </ul>
 *
 * <p>⚠ **状态历史要流程节点声明了物料效果才有**（仿真里常是空的）——
 * 那时这屏退化成"板的位置 + 状态 + 最近更新"，不假装有历史。
 */

interface PlateRow {
  barcode: string;
  plateType?: string | null;
  positionStatus?: string | null;
  status?: string | null;
  processState?: string | null;
  sealState?: string | null;
  stateVersion?: number | null;
  rows?: number | null;
  cols?: number | null;
  createdAt?: string | null;
  updatedAt?: string | null;
}

interface PlateHistoryRow {
  id?: number;
  fromState?: string | null;
  toState?: string | null;
  at?: string | null;
  source?: string | null;
  sourceRunId?: number | null;
  sourceNodeKey?: string | null;
}

interface LocateResult {
  ok?: boolean;
  barcode?: string;
  actualLocation?: string | null;
}

const POSITION_COLOR: Record<string, string> = {
  KNOWN: 'green',
  IN_TRANSIT: 'blue',
  LOST: 'red',
  UNKNOWN: 'default',
};

export default function PlateFocusPage() {
  const t = useT();
  const qc = useQueryClient();
  const ro = useReadOnly();

  const [picked, setPicked] = useState<string | null>(null);
  const [filter, setFilter] = useState('');
  const [locate, setLocate] = useState<LocateResult | null>(null);

  // 板列表：每 3 秒刷新 ⇒ "自动跟着走"
  const listQ = useQuery({
    queryKey: ['plate-focus-list'],
    queryFn: () => apiFetch('/api/plates?limit=50').then((r) => r.json()) as Promise<PlateRow[]>,
    refetchInterval: 3000,
  });
  const plates = useMemo(() => listQ.data ?? [], [listQ.data]);

  // 没选就自动跟"最近活动的第一块板"⇒ 演示时不用点也能动
  const current = picked ?? plates[0]?.barcode ?? null;

  const detailQ = useQuery({
    queryKey: ['plate-focus-detail', current],
    queryFn: () => apiFetch(`/api/plates/${encodeURIComponent(current as string)}`)
      .then((r) => r.json()) as Promise<PlateRow>,
    enabled: !!current,
    refetchInterval: 3000,
  });
  const historyQ = useQuery({
    queryKey: ['plate-focus-history', current],
    queryFn: () => apiFetch(`/api/plates/${encodeURIComponent(current as string)}/state-history`)
      .then((r) => r.json()) as Promise<PlateHistoryRow[]>,
    enabled: !!current,
    refetchInterval: 3000,
  });

  // 换板 ⇒ 旧的定位结论作废（别把上一块板的结论挂在新板上）
  useEffect(() => { setLocate(null); }, [current]);

  const doLocate = async () => {
    if (!current) return;
    try {
      const r = await apiFetch(`/api/plates/${encodeURIComponent(current)}/locate`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: '{}',
      }).then((x) => x.json()) as LocateResult;
      setLocate(r);
    } catch (e) {
      message.error(t('plateFocus.action.locateFailed', { msg: (e as Error).message }));
    }
  };

  const shown = plates.filter((p) =>
    !filter || p.barcode.toLowerCase().includes(filter.toLowerCase()));

  const detail = detailQ.data;

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Alert
        type="info" showIcon
        message={t('plateFocus.alert.title')}
        description={t('plateFocus.alert.desc')}
      />

      <Card size="small" title={t('plateFocus.list.title', { n: plates.length })}>
        {listQ.error ? (
          <Alert type="error" showIcon message={t('plateFocus.error.load')}
            description={String((listQ.error as Error).message)} />
        ) : plates.length === 0 ? (
          <Empty description={t('plateFocus.list.empty')} />
        ) : (
          <Space direction="vertical" size={8} style={{ width: '100%' }}>
            <Input
              placeholder={t('plateFocus.list.filter')}
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
              style={{ maxWidth: 320 }}
            />
            <Table<PlateRow>
              rowKey="barcode" size="small" loading={listQ.isLoading}
              dataSource={shown} pagination={{ pageSize: 8, size: 'small' }}
              onRow={(p) => ({
                onClick: () => setPicked(p.barcode),
                style: {
                  cursor: 'pointer',
                  background: p.barcode === current ? 'rgba(22,119,255,0.10)' : undefined,
                },
              })}
              columns={[
                {
                  title: t('plateFocus.col.barcode'), dataIndex: 'barcode', width: 180,
                  render: (v: string) => <Typography.Text code>{v}</Typography.Text>,
                },
                {
                  title: t('plateFocus.col.plateType'), dataIndex: 'plateType', width: 120,
                  render: (v?: string | null) => v || '—',
                },
                {
                  title: t('plateFocus.col.positionStatus'), dataIndex: 'positionStatus', width: 130,
                  render: (v?: string | null) => (
                    <Tag color={POSITION_COLOR[v ?? ''] ?? 'default'}>{v || '—'}</Tag>
                  ),
                },
                {
                  title: t('plateFocus.col.processState'), dataIndex: 'processState', width: 130,
                  render: (v?: string | null) => v || '—',
                },
                {
                  title: t('plateFocus.col.updatedAt'), dataIndex: 'updatedAt', width: 170,
                  render: (v?: string | null) => (v ? new Date(v).toLocaleTimeString() : '—'),
                },
              ]}
            />
          </Space>
        )}
      </Card>

      <Card
        size="small"
        title={t('plateFocus.card.title', { barcode: current ?? '—' })}
        extra={
          <Space size={4}>
            <Button size="small" onClick={() => qc.invalidateQueries({ queryKey: ['plate-focus-list'] })}>
              {t('plateFocus.action.refresh')}
            </Button>
            <Button size="small" type="primary" {...ro} disabled={!current} onClick={doLocate}>
              {t('plateFocus.action.locate')}
            </Button>
          </Space>
        }
      >
        {!current ? (
          <Empty description={t('plateFocus.card.empty')} />
        ) : (
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            <Descriptions size="small" column={3} bordered>
              <Descriptions.Item label={t('plateFocus.card.barcode')}>
                <Typography.Text code>{current}</Typography.Text>
              </Descriptions.Item>
              <Descriptions.Item label={t('plateFocus.card.plateType')}>
                {detail?.plateType || '—'}
              </Descriptions.Item>
              <Descriptions.Item label={t('plateFocus.card.format')}>
                {detail?.rows ?? '—'} × {detail?.cols ?? '—'}
              </Descriptions.Item>
              <Descriptions.Item label={t('plateFocus.card.positionStatus')}>
                <Tag color={POSITION_COLOR[detail?.positionStatus ?? ''] ?? 'default'}>
                  {detail?.positionStatus || '—'}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t('plateFocus.card.status')}>
                <Badge status={detail?.status === 'active' ? 'processing' : 'default'}
                  text={detail?.status || '—'} />
              </Descriptions.Item>
              <Descriptions.Item label={t('plateFocus.card.sealState')}>
                {detail?.sealState || '—'}
              </Descriptions.Item>
              <Descriptions.Item label={t('plateFocus.card.stateVersion')}>
                {detail?.stateVersion ?? '—'}
              </Descriptions.Item>
              <Descriptions.Item label={t('plateFocus.card.updatedAt')}>
                {detail?.updatedAt ? new Date(detail.updatedAt).toLocaleString() : '—'}
              </Descriptions.Item>
              <Descriptions.Item label={t('plateFocus.card.createdAt')}>
                {detail?.createdAt ? new Date(detail.createdAt).toLocaleString() : '—'}
              </Descriptions.Item>
            </Descriptions>

            {locate && (
              <Alert
                type={locate.actualLocation === 'LOST' ? 'warning' : 'success'}
                showIcon
                message={t('plateFocus.card.locateResult', {
                  location: locate.actualLocation || 'UNKNOWN',
                })}
              />
            )}

            <div>
              <Typography.Text strong>{t('plateFocus.card.history')}</Typography.Text>
              {(historyQ.data ?? []).length === 0 ? (
                <div style={{ fontSize: 12, color: '#8b93a7', marginTop: 6 }}>
                  {t('plateFocus.card.historyEmpty')}
                </div>
              ) : (
                <Timeline
                  style={{ marginTop: 12 }}
                  items={(historyQ.data ?? []).map((h) => ({
                    children: (
                      <Space direction="vertical" size={0}>
                        <Typography.Text style={{ fontSize: 12 }}>
                          {h.fromState || '—'} → <b>{h.toState || '—'}</b>
                        </Typography.Text>
                        <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                          {h.at ? new Date(h.at).toLocaleString() : ''}
                          {h.sourceNodeKey ? ` · ${h.sourceNodeKey}` : ''}
                        </Typography.Text>
                      </Space>
                    ),
                  }))}
                />
              )}
            </div>
          </Space>
        )}
      </Card>
    </Space>
  );
}
