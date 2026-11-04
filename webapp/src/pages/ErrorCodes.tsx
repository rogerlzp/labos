import { useMemo, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert, Button, Card, Input, InputNumber, Modal, Popconfirm, Select, Space,
  Table, Tag, Tooltip, Typography, message,
} from 'antd';
import { apiFetch } from '../api/client';
import { useReadOnly } from '../auth';
import { useT } from '../i18n';
import type { MessageKey } from '../i18n/core';

/**
 * 错误码字典：**出错之后怎么处理，这里说了算**。
 *
 * 作用域（scope）、策略（strategy）、最大次数都落在库里，运行时直接读；
 * 以前改一个码要写三条 SQL（error_code / recovery_policy / 种子文件），
 * 现场没人敢动，等于"配了等于没配"。
 */

/** 恢复作用域：同一错误码在不同层级下的处理方式完全不同。 */
// 文案走文案包（key）：作用域/策略的英文措辞比中文长，必须能整句替换
const SCOPES: Array<{ value: string; labelKey: MessageKey; hintKey: MessageKey }> = [
  { value: 'command', labelKey: 'errorCodes.scope.command.label', hintKey: 'errorCodes.scope.command.hint' },
  { value: 'node', labelKey: 'errorCodes.scope.node.label', hintKey: 'errorCodes.scope.node.hint' },
  { value: 'run', labelKey: 'errorCodes.scope.run.label', hintKey: 'errorCodes.scope.run.hint' },
  { value: 'device', labelKey: 'errorCodes.scope.device.label', hintKey: 'errorCodes.scope.device.hint' },
];

const STRATEGIES: Array<{ value: string; labelKey: MessageKey }> = [
  { value: 'retry', labelKey: 'errorCodes.strategy.retry.label' },
  { value: 'reassign', labelKey: 'errorCodes.strategy.reassign.label' },
  // skip 的运行时语义不是"忽略继续"：SKIPPED 意味着"该做没做、板位不可信"，
  // GraphRunner 会**停下转人工**。文案必须写清楚，否则运营会以为配了就跳过（评审 20261103-01 提问）。
  { value: 'skip', labelKey: 'errorCodes.strategy.skip.label' },
  { value: 'abort', labelKey: 'errorCodes.strategy.abort.label' },
  { value: 'manual', labelKey: 'errorCodes.strategy.manual.label' },
];

const STRATEGY_COLOR: Record<string, string> = {
  retry: 'green', reassign: 'geekblue', skip: 'default',
  abort: 'red', manual: 'orange', compensate: 'purple',
};

const SEVERITY_COLOR: Record<string, string> = {
  info: 'default', warn: 'orange', error: 'red', fatal: 'magenta',
};

interface Policy {
  id: number;
  scope: string;
  strategy: string;
  maxAttempts: number;
  params?: string | null;
  priority: number;
  enabled: boolean;
}

interface ErrorCode {
  id: number;
  code: string;
  scope?: string | null;
  severity: string;
  source: string;
  title?: string | null;
  detail?: string | null;
  occurrences: number;
  policies?: Policy[] | null;
  /** 这个码在**它自己的作用域**上有没有启用中的策略（false = 会落到通配，配置静默失效） */
  scopeCovered?: boolean;
}

export default function ErrorCodesPage() {
  const qc = useQueryClient();
  // 错误码/处置策略都是配置类写操作：viewer 只读
  const ro = useReadOnly();
  const t = useT();
  const [scope, setScope] = useState<string>();
  const [severity, setSeverity] = useState<string>();
  const [kw, setKw] = useState('');

  const [editing, setEditing] = useState<ErrorCode | null>(null);
  const [busy, setBusy] = useState(false);
  const [form, setForm] = useState<Partial<ErrorCode>>({});
  /** 弹窗里那份策略的可编辑副本 */
  const [pols, setPols] = useState<Policy[]>([]);
  const [newScope, setNewScope] = useState('node');
  const [newStrategy, setNewStrategy] = useState('manual');
  /** 新建错误码弹窗（原来只能改不能加，接新设备时卡脖子） */
  const [creating, setCreating] = useState(false);
  const [cf, setCf] = useState<{ code: string; scope: string; severity: string; title: string; detail: string }>({
    code: '', scope: 'node', severity: 'warn', title: '', detail: '',
  });

  const query = useMemo(() => {
    const p = new URLSearchParams();
    if (scope) p.set('scope', scope);
    if (severity) p.set('severity', severity);
    if (kw.trim()) p.set('q', kw.trim());
    return p.toString();
  }, [scope, severity, kw]);

  const { data, isLoading, error } = useQuery({
    queryKey: ['error-codes', query],
    queryFn: () => apiFetch(`/api/error-codes?${query}`).then((r) => r.json()),
  });
  const items: ErrorCode[] = data?.items ?? [];

  const refreshList = () => qc.invalidateQueries({ queryKey: ['error-codes'] });

  /** 删除（后端软删：列表不再显示，历史发生记录保留）。 */
  const removeCode = async (id: number) => {
    const r = await apiFetch(`/api/error-codes/${id}`, { method: 'DELETE' });
    if (r.ok) {
      message.success(t('errorCodes.toast.deleted'));
      refreshList();
    } else {
      message.error(t('errorCodes.toast.deleteFailed'));
    }
  };

  /** 新建错误码。策略要在建完之后单独加（一个码 × 一个作用域 = 一条）。 */
  const createCode = async () => {
    if (!cf.code.trim()) {
      message.warning(t('errorCodes.warn.codeEmpty'));
      return;
    }
    setBusy(true);
    try {
      const r = await apiFetch('/api/error-codes', {
        method: 'POST',
        body: JSON.stringify(cf),
      });
      const j = await r.json().catch(() => ({}));
      if (!r.ok) {
        message.error(j.error ?? `HTTP ${r.status}`);
        return;
      }
      message.success(t('errorCodes.toast.created', { code: cf.code }));
      setCreating(false);
      setCf({ code: '', scope: 'node', severity: 'warn', title: '', detail: '' });
      refreshList();
    } finally {
      setBusy(false);
    }
  };

  const openEdit = (row: ErrorCode) => {
    setEditing(row);
    setForm({
      scope: row.scope ?? 'node',
      severity: row.severity,
      title: row.title ?? '',
    });
    setPols((row.policies ?? []).map((p) => ({
      ...p,
      params: p.params == null ? '{}' : JSON.stringify(p.params),
    })));
    setNewScope('node');
    setNewStrategy('manual');
  };

  /** 保存码本身的属性（标题/严重度/作用域）。 */
  const saveCode = async () => {
    if (!editing) return;
    const body: Record<string, string> = {};
    if (form.scope !== (editing.scope ?? 'node')) body.scope = String(form.scope);
    if (form.severity !== editing.severity) body.severity = String(form.severity);
    if ((form.title ?? '') !== (editing.title ?? '')) body.title = String(form.title ?? '');
    if (Object.keys(body).length === 0) {
      message.info(t('errorCodes.toast.codeUnchanged'));
      return;
    }
    setBusy(true);
    try {
      const r = await apiFetch(`/api/error-codes/${editing.id}`, {
        method: 'PATCH', body: JSON.stringify(body),
      });
      if (!r.ok) throw new Error((await r.json().catch(() => ({}))).error ?? `HTTP ${r.status}`);
      message.success(t('errorCodes.toast.saved', {
          code: editing.code, fields: Object.keys(body).join(', '),
        }));
      refreshList();
      setEditing(null);
    } catch (e) {
      message.error(t('errorCodes.toast.saveFailed', { msg: (e as Error).message }));
    } finally {
      setBusy(false);
    }
  };

  const reloadPolicies = async () => {
    if (!editing) return;
    const r = await apiFetch(`/api/error-codes?q=${encodeURIComponent(editing.code)}`)
      .then((x) => x.json());
    const row = (r.items ?? []).find((x: ErrorCode) => x.id === editing.id);
    setPols((row?.policies ?? []).map((p: Policy) => ({
      ...p, params: p.params == null ? '{}' : JSON.stringify(p.params),
    })));
    refreshList();
  };

  const savePolicy = async (p: Policy) => {
    try {
      const r = await apiFetch(`/api/error-codes/${editing!.id}/policies/${p.id}`, {
        method: 'PATCH',
        body: JSON.stringify({
          strategy: p.strategy,
          maxAttempts: String(p.maxAttempts),
          params: p.params ?? '{}',
        }),
      });
      if (!r.ok) throw new Error((await r.json().catch(() => ({}))).error ?? `HTTP ${r.status}`);
      message.success(t('errorCodes.toast.policySaved', { scope: p.scope }));
      await reloadPolicies();
    } catch (e) {
      message.error(t('errorCodes.toast.saveFailed', { msg: (e as Error).message }));
    }
  };

  const removePolicy = async (p: Policy) => {
    const r = await apiFetch(`/api/error-codes/${editing!.id}/policies/${p.id}`,
      { method: 'DELETE' });
    if (r.ok) {
      message.success(t('errorCodes.toast.policyDeleted', { scope: p.scope }));
      await reloadPolicies();
    } else {
      message.error(t('errorCodes.toast.deleteFailed'));
    }
  };

  const addPolicy = async () => {
    if (pols.some((p) => p.scope === newScope)) {
      message.warning(t('errorCodes.warn.scopeExists', { scope: newScope }));
      return;
    }
    const r = await apiFetch(`/api/error-codes/${editing!.id}/policies`, {
      method: 'POST',
      body: JSON.stringify({ scope: newScope, strategy: newStrategy, maxAttempts: '1' }),
    });
    if (r.ok) {
      message.success(t('errorCodes.toast.policyAdded', { scope: newScope }));
      await reloadPolicies();
    } else {
      message.error(t('errorCodes.toast.addFailed'));
    }
  };

  const scopeHint = (v?: string | null) =>
    t(SCOPES.find((s) => s.value === v)?.hintKey ?? 'errorCodes.scope.unsetHint');

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Alert
        type="info" showIcon
        message={t('errorCodes.alert.title')}
        description={t('errorCodes.alert.desc')}
      />

      <Card
        size="small"
        title={t('errorCodes.title', { n: items.length })}
        extra={
          <Space wrap>
            <Input.Search
              allowClear placeholder={t('errorCodes.placeholder.search')} style={{ width: 220 }}
              onSearch={(v) => setKw(v)}
              onChange={(e) => { if (!e.target.value) setKw(''); }}
            />
            <Select allowClear placeholder={t('errorCodes.placeholder.scope')} style={{ width: 200 }}
              options={SCOPES.map((s) => ({ value: s.value, label: t(s.labelKey) }))}
              onChange={(v) => setScope(v)} />
            <Select allowClear placeholder={t('errorCodes.placeholder.severity')} style={{ width: 120 }}
              options={['info', 'warn', 'error', 'fatal'].map((v) => ({ value: v, label: v }))}
              onChange={(v) => setSeverity(v)} />
            <Button type="primary" {...ro} onClick={() => setCreating(true)}>{t('errorCodes.action.create')}</Button>
          </Space>
        }
      >
        {error ? (
          <Alert type="error" showIcon message={t('errorCodes.error.load')}
            description={String((error as Error).message)} />
        ) : (
          <Table<ErrorCode>
            rowKey="id" size="small" loading={isLoading}
            dataSource={items} pagination={{ pageSize: 20, showSizeChanger: false }}
            columns={[
              {
                title: t('errorCodes.col.code'), dataIndex: 'code', width: 150,
                render: (v: string, r) => (
                  <Space size={4}>
                    <Typography.Text code>{v}</Typography.Text>
                    {r.occurrences > 0 && (
                      <Tooltip title={t('errorCodes.col.occurrences', { n: r.occurrences })}>
                        <Tag>{r.occurrences}</Tag>
                      </Tooltip>
                    )}
                  </Space>
                ),
              },
              { title: t('errorCodes.col.title'), dataIndex: 'title', ellipsis: true, render: (v: string) => v ?? '—' },
              {
                title: t('errorCodes.col.severity'), dataIndex: 'severity', width: 90,
                render: (v: string) => <Tag color={SEVERITY_COLOR[v] ?? 'default'}>{v}</Tag>,
              },
              {
                title: t('errorCodes.col.scope'), dataIndex: 'scope', width: 110,
                render: (v: string) => (v
                  ? <Tooltip title={scopeHint(v)}><Tag color="blue">{v}</Tag></Tooltip>
                  : <Typography.Text type="secondary">{t('errorCodes.scope.unset')}</Typography.Text>),
              },
              {
                // 一个码可能有多条策略（按作用域分）；标出哪条是当前生效的
                title: t('errorCodes.col.policies'), dataIndex: 'policies',
                render: (_, r) => {
                  const list = r.policies ?? [];
                  // 有策略但都不在**这个码自己的作用域**上 → 运行时匹配不到，等于没配
                  const uncovered = r.scopeCovered === false;
                  if (list.length === 0) {
                    return (
                      <Space size={4}>
                        <Typography.Text type="secondary">{t('errorCodes.policy.none')}</Typography.Text>
                        {uncovered && (
                          <Tooltip title={t('errorCodes.policy.uncoveredTooltip')}>
                            <Tag color="red">{t('errorCodes.policy.uncovered')}</Tag>
                          </Tooltip>
                        )}
                      </Space>
                    );
                  }
                  return (
                    <Space size={4} wrap>
                      {list.map((p) => (
                        <Tooltip key={p.id}
                          title={`priority=${p.priority}${p.enabled ? '' : t('errorCodes.policy.disabled')}`}>
                          <Tag
                            color={STRATEGY_COLOR[p.strategy] ?? 'default'}
                            style={r.scope === p.scope
                              ? { border: '2px solid #1677ff' }   // 当前作用域 → 这条真正生效
                              : undefined}
                          >
                            {p.scope}·{p.strategy}×{p.maxAttempts}
                          </Tag>
                        </Tooltip>
                      ))}
                      {uncovered && (
                        <Tooltip title={t('errorCodes.policy.scopeMissingTooltip', { scope: r.scope ?? '' })}>
                          <Tag color="red">{t('errorCodes.policy.scopeMissing')}</Tag>
                        </Tooltip>
                      )}
                    </Space>
                  );
                },
              },
              {
                title: t('errorCodes.col.action'), width: 130,
                render: (_, r) => (
                  <Space size={0}>
                    <Button size="small" type="link" {...ro} onClick={() => openEdit(r)}>{t('errorCodes.action.edit')}</Button>
                    <Popconfirm
                      title={t('errorCodes.confirm.deleteTitle', { code: r.code })}
                      description={t('errorCodes.confirm.deleteDesc')}
                      okText={t('errorCodes.confirm.deleteOk')} okButtonProps={{ danger: true }}
                      onConfirm={() => removeCode(r.id)}
                    >
                      <Button size="small" type="link" danger {...ro}>{t('errorCodes.action.delete')}</Button>
                    </Popconfirm>
                  </Space>
                ),
              },
            ]}
          />
        )}
      </Card>

      <Modal
        open={editing != null}
        title={editing ? t('errorCodes.modal.editTitle', { code: editing.code }) : ''}
        onCancel={() => setEditing(null)}
        footer={[
          <Button key="close" onClick={() => setEditing(null)}>{t('errorCodes.action.close')}</Button>,
          <Button key="save" type="primary" loading={busy} {...ro} onClick={saveCode}>
            {t('errorCodes.action.saveCode')}
          </Button>,
        ]}
        width={760}
      >
        {editing && (
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            <Space size={12} wrap>
              <div>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('errorCodes.field.title')}</Typography.Text>
                <Input style={{ width: 300 }} value={form.title ?? ''}
                  onChange={(e) => setForm({ ...form, title: e.target.value })} />
              </div>
              <div>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('errorCodes.field.severity')}</Typography.Text>
                <Select style={{ width: 120 }} value={form.severity}
                  options={['info', 'warn', 'error', 'fatal'].map((v) => ({ value: v, label: v }))}
                  onChange={(v) => setForm({ ...form, severity: v })} />
              </div>
              <div>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('errorCodes.field.scope')}</Typography.Text>
                <Select style={{ width: 240 }} value={form.scope ?? 'node'}
                  options={SCOPES.map((s) => ({ value: s.value, label: t(s.labelKey) }))}
                  onChange={(v) => setForm({ ...form, scope: v })} />
                <div style={{ fontSize: 11, color: '#8b93a7' }}>{scopeHint(form.scope)}</div>
              </div>
            </Space>

            <Alert
              type="warning" showIcon style={{ fontSize: 12 }}
              message={t('errorCodes.alert.scopeTitle', { scope: form.scope ?? 'node' })}
              description={t('errorCodes.alert.scopeDesc')}
            />

            <Typography.Text strong style={{ fontSize: 13 }}>{t('errorCodes.section.policies')}</Typography.Text>
            <Table<Policy>
              rowKey="id" size="small" pagination={false} dataSource={pols}
              locale={{ emptyText: t('errorCodes.policies.empty') }}
              columns={[
                {
                  title: t('errorCodes.col.scope'), dataIndex: 'scope', width: 110,
                  render: (v: string) => <Tag color="blue">{v}</Tag>,
                },
                {
                  title: t('errorCodes.col.strategy'), dataIndex: 'strategy', width: 150,
                  render: (v: string, p) => (
                    <Select size="small" style={{ width: 130 }} value={v}
                      options={STRATEGIES.map((s) => ({ value: s.value, label: t(s.labelKey) }))}
                      onChange={(nv) => setPols(pols.map((x) => x.id === p.id
                        ? { ...x, strategy: nv } : x))} />
                  ),
                },
                {
                  title: t('errorCodes.col.attempts'), dataIndex: 'maxAttempts', width: 80,
                  render: (v: number, p) => (
                    <InputNumber size="small" min={0} max={99} value={v}
                      onChange={(nv) => setPols(pols.map((x) => x.id === p.id
                        ? { ...x, maxAttempts: Number(nv ?? 1) } : x))} />
                  ),
                },
                {
                  title: t('errorCodes.col.params'), dataIndex: 'params',
                  render: (v: string, p) => (
                    <Input size="small" value={v ?? '{}'}
                      onChange={(e) => setPols(pols.map((x) => x.id === p.id
                        ? { ...x, params: e.target.value } : x))} />
                  ),
                },
                {
                  title: t('errorCodes.col.action'), width: 130,
                  render: (_, p) => (
                    <Space size={4}>
                      <Button size="small" type="link" {...ro} onClick={() => savePolicy(p)}>{t('errorCodes.modal.saveOk')}</Button>
                      <Popconfirm title={t('errorCodes.confirm.deletePolicyTitle', { scope: p.scope })}
                        onConfirm={() => removePolicy(p)}>
                        <Button size="small" type="link" danger {...ro}>{t('errorCodes.action.delete')}</Button>
                      </Popconfirm>
                    </Space>
                  ),
                },
              ]}
            />

            <Space size={8} wrap>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('errorCodes.policy.addLabel')}</Typography.Text>
              <Select size="small" style={{ width: 200 }} value={newScope}
                options={SCOPES.map((s) => ({ value: s.value, label: t(s.labelKey) }))}
                onChange={setNewScope} />
              <Select size="small" style={{ width: 160 }} value={newStrategy}
                options={STRATEGIES.map((s) => ({ value: s.value, label: t(s.labelKey) }))}
                onChange={setNewStrategy} />
              <Button size="small" {...ro} onClick={addPolicy}>{t('errorCodes.action.add')}</Button>
            </Space>
          </Space>
        )}
      </Modal>

      <Modal
        open={creating}
        title={t('errorCodes.modal.createTitle')}
        okText={t('errorCodes.modal.createOk')}
        confirmLoading={busy}
        onOk={createCode}
        onCancel={() => setCreating(false)}
        width={520}
      >
        <Space direction="vertical" size={10} style={{ width: '100%' }}>
          <Alert
            type="info" showIcon
            message={t('errorCodes.alert.codeTitle')}
            description={t('errorCodes.alert.codeDesc')}
          />
          <div>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('errorCodes.field.code')}</Typography.Text>
            <Input placeholder="EC-09-1234" value={cf.code}
              onChange={(e) => setCf({ ...cf, code: e.target.value })} />
          </div>
          <div>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('errorCodes.field.scopeHint')}</Typography.Text>
            <Select style={{ width: '100%' }} value={cf.scope}
              options={SCOPES.map((x) => ({ value: x.value, label: t(x.labelKey) }))}
              onChange={(v) => setCf({ ...cf, scope: v })} />
          </div>
          <div>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('errorCodes.field.severity')}</Typography.Text>
            <Select style={{ width: '100%' }} value={cf.severity}
              options={['info', 'warn', 'error', 'fatal'].map((v) => ({ value: v, label: v }))}
              onChange={(v) => setCf({ ...cf, severity: v })} />
          </div>
          <div>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('errorCodes.field.title')}</Typography.Text>
            <Input placeholder={t('errorCodes.placeholder.title')} value={cf.title}
              onChange={(e) => setCf({ ...cf, title: e.target.value })} />
          </div>
          <div>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('errorCodes.field.description')}</Typography.Text>
            <Input.TextArea rows={3} value={cf.detail}
              onChange={(e) => setCf({ ...cf, detail: e.target.value })} />
          </div>
        </Space>
      </Modal>
    </Space>
  );
}
