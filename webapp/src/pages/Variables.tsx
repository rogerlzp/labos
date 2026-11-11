import { useState } from 'react';
import {
  Button, Card, Input, Modal, Popconfirm, Select, Space, Table, Tag,
  Typography, message,
} from 'antd';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from '../api/client';
import { useReadOnly } from '../auth';
import { useT } from '../i18n';

type VarItem = {
  name: string;
  type?: string;
  defaultValue?: string | null;
  description?: string | null;
};

type Owner = { id: number; name?: string; code?: string };

/**
 * 变量管理台。
 *
 * <p><b>变量的范围分两级</b>：
 * <ul>
 *   <li><b>流程级</b> —— 属于一条流程。运行期由 {@code __setVar} / {@code __expr} 改写；
 *       这条流程的所有实验共享它。</li>
 *   <li><b>实验级</b> —— 属于一个实验。实验启动时把变量带进运行期，
 *       <b>同名覆盖</b>流程级的初始值 ⇒ 同一个流程在不同实验里可以跑出不同结果。</li>
 * </ul>
 *
 * <p>每个变量有**名称 / 类型 / 初始值 / 说明**。初始值很重要：
 * 变量没被任何节点赋值就用它（初值是"没等到之前先用这个"）。
 */
export default function VariablesPage() {
  const t = useT();
  const qc = useQueryClient();
  const ro = useReadOnly();
  const [scope, setScope] = useState<'workflow' | 'experiment'>('workflow');
  const [ownerId, setOwnerId] = useState<number | null>(null);
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState({
    name: '', type: 'number', defaultValue: '', description: '',
  });

  const owners = useQuery({
    queryKey: ['var-owners', scope],
    queryFn: () => apiFetch(
      scope === 'workflow' ? '/api/workflows?limit=300' : '/api/experiments?limit=300',
    ).then((r) => r.json()) as Promise<{ items?: Owner[] }>,
  });

  // 选了范围就自动选第一个对象（现场最常见的是"就改这一条流程的变量"）
  const ownerList = owners.data?.items ?? [];
  const effectiveOwner = ownerId ?? ownerList[0]?.id ?? null;

  const vars = useQuery({
    queryKey: ['vars', scope, effectiveOwner],
    enabled: effectiveOwner != null,
    queryFn: () => apiFetch(
      scope === 'workflow'
        ? `/api/workflows/${effectiveOwner}/variables`
        : `/api/experiments/${effectiveOwner}/variables`,
    ).then((r) => r.json()) as Promise<{ items?: VarItem[] }>,
  });

  const items = vars.data?.items ?? [];

  const reload = () => {
    qc.invalidateQueries({ queryKey: ['vars'] });
    qc.invalidateQueries({ queryKey: ['workflow-vars'] });
  };

  const save = async () => {
    if (!form.name.trim()) {
      message.error(t('variables.nameRequired'));
      return;
    }
    if (effectiveOwner == null) return;
    const url = scope === 'workflow'
      ? `/api/workflows/${effectiveOwner}/variables`
      : `/api/experiments/${effectiveOwner}/variables`;
    const body = scope === 'workflow'
      ? { name: form.name.trim(), type: form.type,
          defaultValue: form.defaultValue, description: form.description }
      : [{ name: form.name.trim(), type: form.type,
          defaultValue: form.defaultValue, description: form.description }];
    try {
      const r = await apiFetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      if (!r.ok) {
        const j = await r.json().catch(() => ({}));
        message.error(j.error ?? t('variables.saveFailed'));
        return;
      }
      message.success(t('variables.saved'));
      setOpen(false);
      setForm({ name: '', type: 'number', defaultValue: '', description: '' });
      reload();
    } catch (e) {
      message.error(String(e));
    }
  };

  const remove = async (name: string) => {
    if (effectiveOwner == null) return;
    const url = scope === 'workflow'
      ? `/api/workflows/${effectiveOwner}/variables/${encodeURIComponent(name)}`
      : `/api/experiments/${effectiveOwner}/variables`;
    try {
      const r = await apiFetch(url, scope === 'workflow'
        ? { method: 'DELETE' }
        : {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(items.filter((v) => v.name !== name)),
        });
      if (!r.ok) {
        message.error(t('variables.deleteFailed'));
        return;
      }
      message.success(t('variables.deleted'));
      reload();
    } catch (e) {
      message.error(String(e));
    }
  };

  return (
    <div style={{ padding: 16 }}>
      <Card
        size="small"
        title={t('variables.title')}
        extra={
          <Space>
            <Select
              size="small" style={{ width: 160 }}
              value={scope}
              onChange={(v: 'workflow' | 'experiment') => { setScope(v); setOwnerId(null); }}
              options={[
                { value: 'workflow', label: t('variables.scope.workflow') },
                { value: 'experiment', label: t('variables.scope.experiment') },
              ]}
            />
            <Select
              size="small" style={{ width: 260 }}
              showSearch optionFilterProp="label"
              value={effectiveOwner ?? undefined}
              placeholder={t('variables.pickOwner')}
              onChange={(v: number) => setOwnerId(v)}
              options={ownerList.map((o) => ({
                value: o.id,
                label: o.name || o.code || `#${o.id}`,
              }))}
            />
            <Button size="small" type="primary" disabled={ro.disabled}
              onClick={() => setOpen(true)}>
              {t('variables.new')}
            </Button>
          </Space>
        }
      >
        <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
          {t('variables.scopeHint')}
        </Typography.Paragraph>
        <Table<VarItem>
          rowKey="name" size="small" pagination={false}
          dataSource={items}
          columns={[
            { title: t('variables.col.name'), dataIndex: 'name', width: 200 },
            {
              title: t('variables.col.type'), dataIndex: 'type', width: 110,
              render: (v?: string) => <Tag>{v ?? 'number'}</Tag>,
            },
            {
              title: t('variables.col.default'), dataIndex: 'defaultValue', width: 140,
              render: (v?: string | null) => (v == null || v === '' ? '—' : v),
            },
            { title: t('variables.col.desc'), dataIndex: 'description' },
            {
              title: t('variables.col.action'), width: 90,
              render: (_, v) => (
                <Popconfirm title={t('variables.deleteConfirm')} onConfirm={() => remove(v.name)}
                  disabled={ro.disabled}>
                  <Button size="small" type="link" danger disabled={ro.disabled}>
                    {t('variables.delete')}
                  </Button>
                </Popconfirm>
              ),
            },
          ]}
        />
      </Card>

      <Modal
        open={open} title={t('variables.new')}
        okText={t('variables.save')} cancelText={t('variables.cancel')}
        onOk={save} onCancel={() => setOpen(false)}
      >
        <Space direction="vertical" size={10} style={{ width: '100%' }}>
          <Input
            placeholder={t('variables.ph.name')} value={form.name}
            onChange={(e) => setForm({ ...form, name: e.target.value })}
          />
          <Select
            style={{ width: '100%' }} value={form.type}
            onChange={(v: string) => setForm({ ...form, type: v })}
            options={[
              { value: 'number', label: 'number' },
              { value: 'string', label: 'string' },
              { value: 'boolean', label: 'boolean' },
            ]}
          />
          <Input
            placeholder={t('variables.ph.default')} value={form.defaultValue}
            onChange={(e) => setForm({ ...form, defaultValue: e.target.value })}
          />
          <Input
            placeholder={t('variables.ph.desc')} value={form.description}
            onChange={(e) => setForm({ ...form, description: e.target.value })}
          />
        </Space>
      </Modal>
    </div>
  );
}
