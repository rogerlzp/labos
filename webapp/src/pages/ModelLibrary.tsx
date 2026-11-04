import { useQuery } from '@tanstack/react-query';
import {
  Alert, Button, Card, Col, Empty, Row, Space, Statistic, Table, Tag, Tooltip, Typography,
} from 'antd';
import { api, type DeviceModelManualItem, type DeviceModelPlannedItem } from '../api/client';
import { useT } from '../i18n';
import type { MessageKey } from '../i18n/core';

/**
 * 设备类型码 → 中文。
 *
 * <p>为什么本地有一份：状态词表（/api/meta/status-vocabulary）只覆盖状态，不覆盖设备类型；
 * 型号库只有离心机/封膜机两类，页面上给人看要中文。后端加新类型时未知码原样显示，不白屏。
 */
const TYPE_LABEL_KEY: Record<string, MessageKey> = {
  centrifuge: 'modelLibrary.type.centrifuge',
  sealer: 'modelLibrary.type.sealer',
};

/** 类型码 → 文案（未知码原样返回，不白屏）。 */
function typeLabel(type: string, t: (k: MessageKey) => string): string {
  const key = TYPE_LABEL_KEY[type];
  return key ? t(key) : type;
}

/** 错误码块：后端按 int 输出（如 0x40=64），格式化成现场认得的十六进制。 */
function fmtErrorBlock(v: number | string): string {
  if (typeof v === 'string') return v;
  return `0x${v.toString(16).toUpperCase().padStart(2, '0')}`;
}

/**
 * 型号库：回答"哪些型号能接真机、哪些只是占位、产线有几台在跑却缺手册"。
 *
 * <p>为什么需要一个页面：M10 卡在 360R / PlateLoc 的手册上，但"缺哪几本、缺了影响几台设备"
 * 原来只散在代码注释和文档里，评审时没人能一眼说清。这里把 DeviceModels 的适配证据
 * （MANUAL 明细 + PLANNED 占位 + 产线在跑台数）摊开，作为接真机前的对账界面。
 *
 * <p>数据来自后端 <code>GET /api/meta/device-models</code>（只读元数据）。
 * 接口还在并行开发时页面显示"不可用 + 重试"，不白屏、也不编数据。
 */
export default function ModelLibraryPage() {
  const { data, error, isLoading, refetch } = useQuery({
    queryKey: ['meta-device-models'],
    queryFn: api.metaDeviceModels,
    // 接口并行开发中：失败一次就明确报错（不重试到页面一直转圈），恢复后点重试即可
    retry: 1,
  });

  const t = useT();
  const totals = data?.totals;
  const manuals = data?.manual ?? [];
  const planned = data?.planned ?? [];
  const deployed = totals?.plannedDeployed ?? 0;

  /** 页面顶部固定说明：MANUAL / PLANNED 的差别决定了能不能对客户承诺直连。 */
  const header = (
    <Alert
      type="info" showIcon
      message={t('modelLibrary.header.title')}
      description={
        <Space direction="vertical" size={2}>
          <span>
            <b>MANUAL</b>: {t('modelLibrary.header.manual')}
          </span>
          <span>
            <b>PLANNED</b>: {t('modelLibrary.header.plannedPrefix')}
            <b>{t('modelLibrary.header.plannedBold')}</b>
            {t('modelLibrary.header.plannedSuffix')}
          </span>
          <span>
            {t('modelLibrary.header.deployedPrefix')} <b>{t('modelLibrary.header.device1')}</b>{' '}
            {t('modelLibrary.header.and')}{' '}
            <b>{t('modelLibrary.header.device2')}</b> {t('modelLibrary.header.deployedRest')}
            <b>{t('modelLibrary.header.m10')}</b>{t('modelLibrary.header.checklist')}{' '}
            <Typography.Text code>docs/设备/M10-手册到型号库接入准备清单-v0.1.md</Typography.Text>
            {t('modelLibrary.header.checklistTail')}
          </span>
        </Space>
      }
    />
  );

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      {header}

      {error ? (
        <Alert
          type="error" showIcon
          message={t('modelLibrary.error.title')}
          description={t('modelLibrary.error.desc', {
            msg: String((error as Error).message),
          })}
          action={<Button size="small" onClick={() => refetch()}>{t('modelLibrary.action.retry')}</Button>}
        />
      ) : (
        <>
          {/* 三张统计卡：一眼看清"能接几个 / 还缺几个 / 产线在跑几台" */}
          <Row gutter={[12, 12]}>
            <Col xs={24} md={8}>
              <Card size="small" loading={isLoading}>
                <Statistic title={t('modelLibrary.stat.manual')} value={totals?.manual ?? 0}
                  suffix={t('modelLibrary.stat.countSuffix')} />
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {t('modelLibrary.stat.manualHint')}
                </Typography.Text>
              </Card>
            </Col>
            <Col xs={24} md={8}>
              <Card size="small" loading={isLoading}>
                <Statistic title={t('modelLibrary.stat.planned')} value={totals?.planned ?? 0}
                  suffix={t('modelLibrary.stat.countSuffix')} />
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {t('modelLibrary.stat.plannedHint')}
                </Typography.Text>
              </Card>
            </Col>
            <Col xs={24} md={8}>
              <Card size="small" loading={isLoading}>
                <Statistic
                  title={t('modelLibrary.stat.deployed')} value={deployed}
                  suffix={t('modelLibrary.stat.unitSuffix')}
                  valueStyle={deployed > 0 ? { color: '#ef4444' } : undefined}
                />
                <Typography.Text
                  type={deployed > 0 ? 'danger' : 'secondary'} style={{ fontSize: 12 }}
                >
                  {deployed > 0
                    ? t('modelLibrary.stat.deployedWarn')
                    : t('modelLibrary.stat.deployedOk')}
                </Typography.Text>
              </Card>
            </Col>
          </Row>

          {!isLoading && manuals.length === 0 && planned.length === 0 && (
            <Alert
              type="warning" showIcon message={t('modelLibrary.empty.title')}
              description={t('modelLibrary.empty.desc')}
            />
          )}

          {/* 表 A：有手册的型号 —— 对接证据的明细 */}
          <Card
            size="small"
            title={t('modelLibrary.table.manualTitle', { n: manuals.length })}
            extra={
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {t('modelLibrary.table.manualHint')}
              </Typography.Text>
            }
          >
            <Table<DeviceModelManualItem>
              rowKey="id"
              size="small"
              pagination={false}
              loading={isLoading}
              dataSource={manuals}
              locale={{
                emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('modelLibrary.empty.manual')} />,
              }}
              columns={[
                {
                  title: t('modelLibrary.col.modelId'), dataIndex: 'id', width: 165,
                  render: (v: string) => <Typography.Text code>{v}</Typography.Text>,
                },
                {
                  title: t('modelLibrary.col.vendorModel'), width: 190,
                  render: (_, r) => (
                    <Tooltip title={`${r.vendor} ${r.model}`}>
                      <Typography.Text ellipsis style={{ maxWidth: 175 }}>
                        {r.vendor} · {r.model}
                      </Typography.Text>
                    </Tooltip>
                  ),
                },
                {
                  title: t('modelLibrary.col.type'), dataIndex: 'type', width: 75,
                  render: (v: string) => typeLabel(v, t),
                },
                {
                  title: t('modelLibrary.col.protocol'), width: 115,
                  render: (_, r) => (
                    <Tooltip title={r.transport}>
                      <Space direction="vertical" size={0}>
                        <span>{r.protocol}</span>
                        <Typography.Text type="secondary" ellipsis style={{ fontSize: 11, maxWidth: 100 }}>
                          {r.transport}
                        </Typography.Text>
                      </Space>
                    </Tooltip>
                  ),
                },
                {
                  title: t('modelLibrary.col.manual'), width: 175,
                  render: (_, r) => (r.manual ? (
                    <Tooltip title={r.manual}>
                      <Typography.Text ellipsis style={{ maxWidth: 160 }}>{r.manual}</Typography.Text>
                    </Tooltip>
                  ) : <Typography.Text type="secondary">—</Typography.Text>),
                },
                {
                  title: t('modelLibrary.col.methods'), width: 145,
                  render: (_, r) => (
                    <Tooltip
                      title={t('modelLibrary.col.methodsTooltip', {
                        documented: r.documentedMethodCount,
                        unverified: r.unverifiedMethodCount,
                      })}
                    >
                      <span>
                        {r.methodCount}
                        {r.unverifiedMethodCount > 0 && (
                          <Typography.Text type="warning" style={{ fontSize: 12 }}>
                            {t('modelLibrary.col.methodsUnverified', { n: r.unverifiedMethodCount })}
                          </Typography.Text>
                        )}
                      </span>
                    </Tooltip>
                  ),
                },
                {
                  title: t('modelLibrary.col.errorCount'), width: 95,
                  render: (_, r) => (
                    <Space size={4}>
                      <span>{r.errorCount}</span>
                      {r.errorBlock != null && (
                        <Tooltip title={t('modelLibrary.col.errorBlockTooltip', { block: fmtErrorBlock(r.errorBlock) })}>
                          <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                            {t('modelLibrary.col.errorBlock', { block: fmtErrorBlock(r.errorBlock) })}
                          </Typography.Text>
                        </Tooltip>
                      )}
                    </Space>
                  ),
                },
                {
                  title: t('modelLibrary.col.actionTimeout'), width: 95,
                  render: (_, r) => {
                    if (r.actionTimeoutMs == null || r.actionTimeoutMs === 0) {
                      return (
                        <Tooltip title={t('modelLibrary.col.actionTimeoutTooltip')}>
                          <Typography.Text type="warning">{t('modelLibrary.col.actionTimeoutUnknown')}</Typography.Text>
                        </Tooltip>
                      );
                    }
                    return t('modelLibrary.col.actionTimeoutValue', { n: Math.round(r.actionTimeoutMs / 1000) });
                  },
                },
                {
                  title: t('modelLibrary.col.note'), ellipsis: true,
                  render: (_, r) => (r.note ? (
                    <Tooltip title={<span style={{ whiteSpace: 'pre-line' }}>{r.note}</span>}>
                      <Typography.Text ellipsis style={{ maxWidth: 420 }}>{r.note}</Typography.Text>
                    </Tooltip>
                  ) : <Typography.Text type="secondary">—</Typography.Text>),
                },
              ]}
            />
          </Card>

          {/* 表 B：只有型号没有手册 —— M10 要补的清单 */}
          <Card
            size="small"
            title={t('modelLibrary.table.plannedTitle', { n: planned.length })}
            extra={
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {t('modelLibrary.table.plannedHint')}
              </Typography.Text>
            }
          >
            <Table<DeviceModelPlannedItem>
              rowKey="id"
              size="small"
              pagination={false}
              loading={isLoading}
              dataSource={planned}
              locale={{
                emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('modelLibrary.empty.planned')} />,
              }}
              columns={[
                {
                  title: t('modelLibrary.col.modelId'), dataIndex: 'id', width: 240,
                  render: (v: string) => <Typography.Text code>{v}</Typography.Text>,
                },
                {
                  title: t('modelLibrary.col.type'), dataIndex: 'type', width: 90,
                  render: (v: string) => typeLabel(v, t),
                },
                {
                  title: t('modelLibrary.col.vendorModel'), width: 330,
                  render: (_, r) => (
                    <Tooltip title={r.vendorModel}>
                      <Typography.Text ellipsis style={{ maxWidth: 315 }}>{r.vendorModel}</Typography.Text>
                    </Tooltip>
                  ),
                },
                {
                  title: t('modelLibrary.col.dbAlias'), dataIndex: 'dbModelAlias', width: 140,
                  render: (v: string | null) => (v
                    ? <Typography.Text code>{v}</Typography.Text>
                    : <Typography.Text type="secondary">—</Typography.Text>),
                },
                {
                  title: t('modelLibrary.col.missing'), ellipsis: true,
                  render: (_, r) => (
                    <Tooltip title={r.missing}>
                      <Typography.Text ellipsis style={{ maxWidth: 420 }}>{r.missing}</Typography.Text>
                    </Tooltip>
                  ),
                },
                {
                  title: t('modelLibrary.col.deployedCount'), dataIndex: 'deployedCount', width: 120,
                  render: (v: number) => (v > 0
                    ? <Tag color="red">{t('modelLibrary.col.deployedCountTag', { n: v })}</Tag>
                    : <Typography.Text type="secondary">—</Typography.Text>),
                },
                {
                  title: t('modelLibrary.col.state'), width: 100,
                  render: () => <Tag color="orange">{t('modelLibrary.col.stateAwaiting')}</Tag>,
                },
              ]}
            />
          </Card>
        </>
      )}
    </Space>
  );
}
