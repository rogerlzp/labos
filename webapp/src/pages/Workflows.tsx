import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert, Button, Card, Descriptions, Input, Modal, Select, Space, Table, Tag,
  Typography, message,
} from 'antd';
import { apiFetch } from '../api/client';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { api, type WorkflowSummary } from '../api/client';
import { useReadOnly } from '../auth';
import { useT } from '../i18n';
import type { MessageKey, MessageVars } from '../i18n/core';
import { localizedNeutralLabel } from '../bpmn/nodeRoleLabel';

/** 流程库：选一个 → 看预检 + 节点 → 执行。 */
export default function WorkflowsPage() {
  const t = useT();
  const nav = useNavigate();
  // 新建/执行/影子检查都是写操作（影子检查也会落 run 记录）：viewer 只读
  const ro = useReadOnly();
  /** 新建流程：原来界面里没有任何创建入口，流程库只能看 */
  const [creating, setCreating] = useState(false);
  const [newName, setNewName] = useState('');
  // 新建时选岛：设计器据此只显示「这个岛」的设备；留空 = 全部岛（兼容旧流程）
  const [newIslandId, setNewIslandId] = useState<number | null>(null);
  const [busyCreate, setBusyCreate] = useState(false);
  const qc = useQueryClient();
  const islandsQ = useQuery({
    queryKey: ['islands-for-create'],
    queryFn: () => apiFetch('/api/topology').then((r) => r.json()) as Promise<{
      islands?: Array<{ id: number; code: string; name?: string }>;
    }>,
  });

  const createWorkflow = async () => {
    if (!newName.trim()) {
      message.warning(t('workflows.warn.nameRequired'));
      return;
    }
    setBusyCreate(true);
    try {
      const r = await apiFetch('/api/workflows', {
        method: 'POST',
        body: JSON.stringify({ name: newName.trim(), islandId: newIslandId }),
      });
      const j = await r.json();
      if (!r.ok) {
        message.error(j.error ?? t('workflows.toast.createFailed'));
        return;
      }
      message.success(t('workflows.toast.created'));
      setCreating(false);
      setNewName('');
      qc.invalidateQueries({ queryKey: ['workflows'] });
      // 把岛带到设计器，否则刷新后又要重选；不传时设计器回落「全部岛」
      const q = newIslandId ? `?islandId=${newIslandId}` : '';
      nav(`/designer/${j.id}${q}`);
      setNewIslandId(null);
    } finally {
      setBusyCreate(false);
    }
  };
  const [sp] = useSearchParams();
  const [kw, setKw] = useState('');
  // 支持深链：/workflows?wf=239 直接选中某条（列表只取前 300 条，深链是必要的）
  const [selected, setSelected] = useState<number | null>(() => {
    const v = Number(sp.get('wf'));
    return Number.isFinite(v) && v > 0 ? v : null;
  });
  const [running, setRunning] = useState(false);
  const [shadow, setShadow] = useState(false);
  const [shadowBusy, setShadowBusy] = useState(false);
  /** 预检结果：资源不够时不直接跑，先问用户 */
  const [gate, setGate] = useState<Awaited<ReturnType<typeof api.canStart>> | null>(null);
  const [gateOpen, setGateOpen] = useState(false);
  const [shadowReport, setShadowReport] = useState<{
    workflowName: string; total: number; ok: number; failed: number; contractGaps: number;
    nodes: Array<{ nodeKey: string; name: string; capability: string; method: string;
      deviceCode: string | null; ok: boolean; errorCode: string | null;
      elapsedMs: number; contractMissing: number }>;
  } | null>(null);

  const list = useQuery({ queryKey: ['workflows'], queryFn: () => api.workflows(300) });
  const detail = useQuery({
    queryKey: ['workflow', selected],
    queryFn: () => api.workflow(selected!),
    enabled: selected != null,
  });
  const pre = useQuery({
    queryKey: ['preflight', selected],
    queryFn: () => api.preflight(selected!),
    enabled: selected != null,
  });

  const rows = (list.data?.items ?? []).filter(
    (w) => !kw || w.name.includes(kw) || w.code.includes(kw),
  );

  /**
   * 点执行：先做**启动前粗筛**，资源不够时不闷头跑。
   *
   * <p>为什么要有这一步：后端 M3 已经能算"够不够、被谁占、等多久"，
   * 但用户看不到，点了执行还是直接跑，跑到一半卡在抢设备上——
   * 感受就是"点了没反应"。
   */
  async function startFlow(id: number | null) {
    if (id == null) {
      message.warning(t('workflows.warn.selectFirst'));
      return;
    }
    setRunning(true);
    try {
      const g = await api.canStart(id);
      if (g.canStart) {
        await runNow(id);
      } else {
        setGate(g);
        setGateOpen(true);
      }
    } catch (e) {
      // **预检失败不自动执行** —— 预检失败说明"资源状态未知"，
      // 闷头跑可能撞车。让用户自己决定。
      message.error(t('workflows.error.preflight', { msg: String((e as Error).message) }));
    } finally {
      setRunning(false);
    }
  }

  /** 异步启动并跳到执行监控页，让用户能实时看进度、随时暂停。 */
  async function runNow(id: number) {
    const r = await api.execute(id, { mode: 'attended', async: true });
    if (r.runId) {
      message.success(t('workflows.toast.started'));
      nav(`/runs/${r.runId}`);
    } else {
      message.success(t('workflows.toast.completed', { done: r.completed, total: r.total }));
    }
  }

  return (
    <>
      {/* 资源不足弹窗：说清"被谁占、等多久"，让用户自己决定 */}
      <Modal
        open={creating}
        title={t('workflows.modal.createTitle')}
        okText={t('workflows.modal.createOk')}
        confirmLoading={busyCreate}
        onOk={createWorkflow}
        onCancel={() => setCreating(false)}
      >
        <Alert
          type="info" showIcon style={{ marginBottom: 12 }}
          message={t('workflows.modal.emptyGraphTitle')}
          description={t('workflows.modal.emptyGraphDesc')}
        />
        <Input
          placeholder={t('workflows.placeholder.name')}
          value={newName}
          onChange={(e) => setNewName(e.target.value)}
          onPressEnter={createWorkflow}
        />
        <Select
          allowClear
          style={{ width: '100%', marginTop: 10 }}
          placeholder={t('workflows.placeholder.island')}
          value={newIslandId ?? undefined}
          onChange={(v: number | undefined) => setNewIslandId(v ?? null)}
          options={(islandsQ.data?.islands ?? []).map((i) => ({
            value: i.id,
            label: `${i.code}${i.name ? ' · ' + i.name : ''}`,
          }))}
        />
      </Modal>

      <Modal
        title={t('workflows.modal.queueTitle')}
        open={gateOpen}
        onCancel={() => setGateOpen(false)}
        okText={t('workflows.modal.queueOk')}
        cancelText={t('workflows.modal.queueCancel')}
        okButtonProps={{ danger: true }}
        onOk={async () => {
          setGateOpen(false);
          if (selected != null) {
            setRunning(true);
            try {
              await runNow(selected);
            } catch (e) {
              message.error(String((e as Error).message));
            } finally {
              setRunning(false);
            }
          }
        }}
      >
        {gate && (
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            <Alert
              type="warning"
              showIcon
              message={gate.reason ?? t('workflows.modal.queueReasonFallback')}
              description={
                gate.estimatedWaitSeconds != null
                  ? t('workflows.modal.queueWait', { wait: formatWait(gate.estimatedWaitSeconds, t) })
                  : undefined
              }
            />
            {gate.blocking && gate.blocking.length > 0 && (
              <div>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {t('workflows.modal.queueHolding')}
                </Typography.Text>
                <Table
                  size="small"
                  pagination={false}
                  rowKey={(r) => `${r.runId}-${r.deviceInstanceId}`}
                  dataSource={gate.blocking}
                  columns={[
                    { title: t('workflows.col.run'), dataIndex: 'runNo', width: 200 },
                    { title: t('workflows.col.device'), dataIndex: 'deviceCode', width: 120 },
                    {
                      title: t('workflows.col.needed'),
                      width: 100,
                      render: (_, r) => formatWait(r.etaSeconds, t),
                    },
                  ]}
                />
              </div>
            )}
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {t('workflows.modal.queueHint')}
            </Typography.Text>
          </Space>
        )}
      </Modal>

    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card
        size="small"
        title={t('workflows.title', { n: rows.length })}
        extra={
          <Space>
            <Input.Search
              placeholder={t('workflows.placeholder.search')} allowClear style={{ width: 240 }}
              onChange={(e) => setKw(e.target.value)}
            />
            <Button type="primary" {...ro} onClick={() => setCreating(true)}>{t('workflows.action.create')}</Button>
          </Space>
        }
      >
        <Table<WorkflowSummary>
          rowKey="id" size="small" loading={list.isLoading}
          dataSource={rows}
          pagination={{ pageSize: 12, size: 'small' }}
          // 点流程 = 进画布看它长什么样（原来只"选中"，右侧卡片在下方便看不见）
          onRow={(r) => ({
            onClick: () => nav(`/designer/${r.id}`),
            style: { cursor: 'pointer' },
          })}
          columns={[
            { title: t('workflows.col.name'), dataIndex: 'name' },
            { title: t('workflows.col.code'), dataIndex: 'code', width: 130 },
            { title: t('workflows.col.nodes'), dataIndex: 'nodeCount', width: 70 },
            { title: t('workflows.col.category'), dataIndex: 'category', width: 110, render: (v) => <Tag>{v}</Tag> },
            {
              // 行点击已经进画布了；这里留给"预检 / 影子检查 / 执行"
              title: t('workflows.col.action'), width: 90,
              render: (_, r) => (
                <Button size="small" type="link"
                  onClick={(e) => { e.stopPropagation(); setSelected(r.id); }}>
                  {t('workflows.action.detail')}
                </Button>
              ),
            },
          ]}
        />
      </Card>

      {selected != null && (
        <Card
          size="small"
          title={detail.data?.workflow.name ?? t('workflows.detail.loading')}
          extra={
            <Space>
              <Button onClick={() => nav(`/designer/${selected}`)}>{t('workflows.action.openDesigner')}</Button>
              <Button loading={shadowBusy} {...ro} onClick={async () => {
                if (!selected) return;
                setShadowBusy(true);
                setShadowReport(null);
                setShadow(true);
                try {
                  const r = await apiFetch(`/api/workflows/${selected}/shadow-run`, {
                    method: 'POST',
                  });
                  const j = await r.json();
                  if (!r.ok) throw new Error(j.error);
                  setShadowReport(j);
                } catch (e) {
                  message.error(String((e as Error).message));
                  setShadow(false);
                } finally {
                  setShadowBusy(false);
                }
              }}>{t('workflows.action.shadow')}</Button>
              <Button type="primary" loading={running} disabled={ro.disabled || selected == null}
                title={ro.title} onClick={() => startFlow(selected)}>
                {t('workflows.action.execute')}
              </Button>
            </Space>
          }
        >
          {pre.data && (
            <Typography.Paragraph style={{ fontSize: 12 }}>
              {t('workflows.preflight.label')}
              {pre.data.blocking
                ? <Tag color="red">{t('workflows.preflight.blocking', { n: pre.data.errors })}</Tag>
                : pre.data.warnings > 0
                  ? <Tag color="orange">{t('workflows.preflight.warnings', { n: pre.data.warnings })}</Tag>
                  : <Tag color="green">{t('workflows.preflight.pass')}</Tag>}
              <Typography.Text type="secondary">
                {' '}{t('workflows.preflight.tasks', { count: pre.data.taskCount, reachable: pre.data.reachableCount })}
              </Typography.Text>
            </Typography.Paragraph>
          )}

          <Descriptions size="small" column={4} style={{ marginBottom: 12 }}>
            <Descriptions.Item label={t('workflows.field.code')}>{detail.data?.workflow.code}</Descriptions.Item>
            <Descriptions.Item label={t('workflows.field.nodes')}>{detail.data?.tasks.length}</Descriptions.Item>
            <Descriptions.Item label={t('workflows.field.edges')}>{detail.data?.edgeCount}</Descriptions.Item>
          </Descriptions>

          <Table
            size="small" pagination={false}
            dataSource={detail.data?.tasks ?? []}
            rowKey={(r, i) => `${r.name}-${i}`}
            columns={[
              { title: t('workflows.col.nodes'), dataIndex: 'name',
                render: (v: string) => localizedNeutralLabel(v) ?? v },
              { title: t('workflows.col.capability'), dataIndex: 'capability', width: 130 },
              {
                title: t('workflows.col.source'), dataIndex: 'source', width: 110,
                render: (v) => <Tag color={v === 'explicit' ? 'green' : 'default'}>{v}</Tag>,
              },
              { title: t('workflows.col.driverMethod'), dataIndex: 'method', width: 220 },
            ]}
          />
        </Card>
      )}
      <Modal
        open={shadow}
        width={900}
        title={t('workflows.shadow.title', { name: shadowReport?.workflowName ?? t('workflows.shadow.running') })}
        onCancel={() => setShadow(false)}
        footer={<Button onClick={() => setShadow(false)}>{t('workflows.action.close')}</Button>}
      >
        {shadowBusy && (
          <Alert type="info" showIcon message={t('workflows.shadow.progress')}
            description={t('workflows.shadow.desc')} />
        )}
        {shadowReport && (
          <>
            <Space size={24} style={{ marginBottom: 12 }}>
              <Typography.Text>{t('workflows.shadow.stat.nodes')} <b>{shadowReport.total}</b></Typography.Text>
              <Typography.Text type="success">{t('workflows.shadow.stat.ok')} <b>{shadowReport.ok}</b></Typography.Text>
              <Typography.Text type={shadowReport.failed ? 'danger' : 'secondary'}>
                {t('workflows.shadow.stat.failed')} <b>{shadowReport.failed}</b>
              </Typography.Text>
              <Typography.Text type={shadowReport.contractGaps ? 'warning' : 'secondary'}>
                {t('workflows.shadow.stat.contractGaps')} <b>{shadowReport.contractGaps}</b>
              </Typography.Text>
            </Space>
            <Table
              size="small" pagination={false}
              rowKey={(r, i) => `${r.nodeKey}-${i}`}
              dataSource={shadowReport.nodes}
              columns={[
                { title: t('workflows.col.nodes'), dataIndex: 'name', ellipsis: true },
                { title: t('workflows.col.capability'), dataIndex: 'capability', width: 110,
                  render: (v: string) => <Tag color="blue">{v}</Tag> },
                { title: t('workflows.col.method'), dataIndex: 'method', width: 150 },
                { title: t('workflows.col.device'), dataIndex: 'deviceCode', width: 100,
                  render: (v: string | null) => v ?? '—' },
                { title: t('workflows.col.result'), width: 100,
                  render: (_, r) => (r.ok
                    ? <Tag color="green">OK {r.elapsedMs}ms</Tag>
                    : <Tag color="red">{r.errorCode ?? t('workflows.col.resultFailed')}</Tag>) },
                { title: t('workflows.col.contract'), width: 90,
                  render: (_, r) => (r.contractMissing > 0
                    ? <Tag color="orange">{t('workflows.col.contractMissing', { n: r.contractMissing })}</Tag>
                    : <Tag color="green">{t('workflows.col.contractOk')}</Tag>) },
              ]}
            />
            <Typography.Paragraph type="secondary"
              style={{ fontSize: 11, marginTop: 10, marginBottom: 0 }}>
              {t('workflows.shadow.note')}
              {t('workflows.shadow.note2')}
            </Typography.Paragraph>
          </>
        )}
      </Modal>
    </Space>
    </>
  );
}

/** 秒数转成人话：90 → "1 分 30 秒"（多语种：t 由调用方传入，模块级函数拿不到 Context） */
function formatWait(
  sec: number | null | undefined, t: (k: MessageKey, vars?: MessageVars) => string,
): string {
  if (sec == null || sec < 0) return t('workflows.duration.unknown');
  // 0 秒 = 已超过预估时间但还没结束，说"0 秒"会误导成"马上就好"
  if (sec === 0) return t('workflows.duration.underMinute');
  if (sec < 60) return t('workflows.duration.seconds', { n: sec });
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return s > 0
    ? t('workflows.duration.minutesSeconds', { m, s })
    : t('workflows.duration.minutes', { m });
}
