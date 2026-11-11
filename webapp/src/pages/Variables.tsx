import { useState } from 'react';
import {
  Alert, Button, Card, Input, Modal, Popconfirm, Select, Space, Table, Tag,
  Typography, message,
} from 'antd';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
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

  /**
   * 当前对象（这条流程 / 这个实验）。
   *
   * <p>⚠ **不能默认取列表第一条**：变量是挂在某个对象名下的，
   * 默认取第一条 ⇒ 用户以为自己建的是「刚才在设计器里看的那条流程」的变量，
   * 实际建到了另一条流程头上 ⇒ 回到设计器一看"我的变量呢"就没有了。
   * （2026-11-11 现场就是这个坑：变量建到了另一条测试流程上。）
   *
   * <p>所以：**必须显式选**。从设计器点「变量管理」进来时带上
   * {@code ?workflowId=} / {@code ?experimentId=} ⇒ 直接落到那条流程上。
   */
  const [sp] = useSearchParams();
  const presetId = (() => {
    const raw = scope === 'workflow'
      ? sp.get('workflowId')
      : sp.get('experimentId');
    const n = raw == null ? NaN : Number(raw);
    return Number.isFinite(n) && n > 0 ? n : null;
  })();
  const ownerList = owners.data?.items ?? [];
  const effectiveOwner = ownerId ?? presetId;

  // 预设的对象如果不在列表里（比如换了范围），别硬撑着显示一个空表。
  const ownerKnown = effectiveOwner == null
    || ownerList.length === 0
    || ownerList.some((o) => o.id === effectiveOwner);
  const activeOwner = ownerKnown ? effectiveOwner : null;

  const vars = useQuery({
    queryKey: ['vars', scope, activeOwner],
    enabled: activeOwner != null,
    queryFn: () => apiFetch(
      scope === 'workflow'
        ? `/api/workflows/${activeOwner}/variables`
        : `/api/experiments/${activeOwner}/variables`,
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
    if (activeOwner == null) return;
    const url = scope === 'workflow'
      ? `/api/workflows/${activeOwner}/variables`
      : `/api/experiments/${activeOwner}/variables`;
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
    if (activeOwner == null) return;
    const url = scope === 'workflow'
      ? `/api/workflows/${activeOwner}/variables/${encodeURIComponent(name)}`
      : `/api/experiments/${activeOwner}/variables`;
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
              value={activeOwner ?? undefined}
              placeholder={t('variables.pickOwner')}
              onChange={(v: number) => setOwnerId(v)}
              options={ownerList.map((o) => ({
                value: o.id,
                label: o.name || o.code || `#${o.id}`,
              }))}
            />
            <Button size="small" type="primary" disabled={ro.disabled || activeOwner == null}
              onClick={() => setOpen(true)}>
              {t('variables.new')}
            </Button>
          </Space>
        }
      >
        <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
          {t('variables.scopeHint')}
        </Typography.Paragraph>
        {activeOwner == null && (
          <Alert type="info" showIcon style={{ marginBottom: 12 }}
            message={t('variables.pickFirst')}
            description={t('variables.pickFirstDesc')} />
        )}
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
