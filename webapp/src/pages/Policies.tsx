import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert, Button, Card, InputNumber, Modal, Select, Space, Switch,
  Table, Tag, Typography, message,
} from 'antd';
import { api, type Policy, type PolicyValidation } from '../api/client';
import { useReadOnly } from '../auth';
import { useT } from '../i18n';

const STRATEGIES = ['RETRY', 'REASSIGN', 'SKIP', 'ABORT', 'MANUAL'];

/** 策略：错误码 → 动作。可编辑，保存前跑一次生效校验。 */
export default function PoliciesPage() {
  const qc = useQueryClient();
  // 策略影响真实执行，属于配置写操作：viewer 只读
  const ro = useReadOnly();
  const { data, isLoading } = useQuery({ queryKey: ['policies'], queryFn: api.policies });

  const [editing, setEditing] = useState<Policy | null>(null);
  const [strategy, setStrategy] = useState('RETRY');
  const [attempts, setAttempts] = useState(3);
  const [prio, setPrio] = useState(50);
  const [enabled, setEnabled] = useState(true);
  const [check, setCheck] = useState<PolicyValidation | null>(null);
  const [busy, setBusy] = useState(false);

  const open = (p: Policy) => {
    setEditing(p);
    setStrategy((p.strategy ?? 'retry').toUpperCase());
    setAttempts(p.maxAttempts ?? 3);
    setPrio(p.priority ?? 50);
    setEnabled(p.enabled ?? true);
    setCheck(null);
  };

  const runValidate = async () => {
    if (!editing) return;
    setBusy(true);
    try {
      const r = await api.validatePolicy(editing.errorCode ?? '', prio);
      setCheck(r);
    } catch (e) {
      message.error(String((e as Error).message));
    } finally {
      setBusy(false);
    }
  };

  const save = async () => {
    if (!editing) return;
    setBusy(true);
    try {
      // ⚠ 发给后端必须是**小写**：PG 的 `recovery_strategy` 是枚举，取值只有
      // retry/skip/reassign/abort/manual/compensate（小写），而界面与 Java 的
      // 词表是大写（RETRY/SKIP/...）。直接发大写 ⇒ PG 报
      // `invalid input value for enum recovery_strategy: "RETRY"` ⇒ 保存必 500。
      // 库读出来也是小写（`JdbcRecoveryPolicyRepository` 读时 `.toUpperCase()`
      // 转成 Java 词表），所以"界面大写、落库小写"是这个字段的既有约定。
      await api.updatePolicy(editing.id!, {
        strategy: strategy.toLowerCase(), maxAttempts: attempts, priority: prio, enabled,
      });
      message.success(t('policies.toast.saved'));
      setEditing(null);
      await qc.invalidateQueries({ queryKey: ['policies'] });
    } catch (e) {
      message.error(String((e as Error).message));
    } finally {
      setBusy(false);
    }
  };

  const t = useT();
  const items = data?.items ?? [];

  return (
    <>
      <Card
        size="small"
        title={t('policies.title', { n: items.length })}
        extra={<Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {t('policies.hint')}
        </Typography.Text>}
      >
        <Table<Policy>
          rowKey={(r, i) => String(r.id ?? i)}
          size="small" loading={isLoading}
          dataSource={items}
          pagination={{ pageSize: 15, size: 'small' }}
          columns={[
            {
              title: t('policies.col.errorCode'), width: 200,
              render: (_, r) => (
                <Space size={4}>
                  <Typography.Text code>{r.errorCode ?? '*'}</Typography.Text>
                  {r.errorCode == null && <Tag>{t('policies.tag.wildcard')}</Tag>}
                </Space>
              ),
            },
            { title: t('policies.col.scope'), width: 90, render: (_, r) => r.scope ?? 'ANY' },
            {
              title: t('policies.col.action'), width: 110,
              render: (_, r) => <Tag color="blue">{(r.strategy ?? '').toUpperCase()}</Tag>,
            },
            { title: t('policies.col.maxAttempts'), dataIndex: 'maxAttempts', width: 70 },
            { title: t('policies.col.priority'), dataIndex: 'priority', width: 80 },
            {
              title: t('policies.col.enabled'), width: 80,
              render: (_, r) => (r.enabled
                ? <Tag color="green">{t('policies.yes')}</Tag>
                : <Tag>{t('policies.no')}</Tag>),
            },
            {
              title: t('policies.col.source'), width: 90,
              render: (_, r) => <Tag color={r.origin === 'manual' ? 'default' : 'purple'}>{r.origin}</Tag>,
            },
            {
              title: t('policies.col.operation'), width: 90,
              render: (_, r) => <Button size="small" type="link" {...ro} onClick={() => open(r)}>{t('policies.action.edit')}</Button>,
            },
          ]}
        />
      </Card>

      <Modal
        open={editing != null}
        title={t('policies.modal.title', { code: editing?.errorCode ?? t('policies.tag.wildcard') })}
        onCancel={() => setEditing(null)}
        onOk={save}
        okText={t('policies.modal.ok')}
        confirmLoading={busy}
        width={560}
      >
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Alert type="warning" showIcon
            message={t('policies.alert.title')}
            description={t('policies.alert.desc')} />

          <div>
            <Typography.Text>{t('policies.field.action')}</Typography.Text>
            <Select style={{ width: '100%' }} value={strategy}
              onChange={setStrategy}
              options={STRATEGIES.map((s) => ({ value: s, label: s }))} />
          </div>

          <Space size={12}>
            <div>
              <Typography.Text>{t('policies.field.maxAttempts')}</Typography.Text>
              <InputNumber min={0} max={20} value={attempts}
                onChange={(v) => setAttempts(v ?? 0)} style={{ width: 120, display: 'block' }} />
            </div>
            <div>
              <Typography.Text>{t('policies.field.priority')}</Typography.Text>
              <InputNumber min={0} max={99} value={prio}
                onChange={(v) => setPrio(v ?? 50)} style={{ width: 120, display: 'block' }} />
            </div>
            <div>
              <Typography.Text>{t('policies.field.enabled')}</Typography.Text>
              <div><Switch checked={enabled} onChange={setEnabled} /></div>
            </div>
          </Space>

          <Space>
            <Button onClick={runValidate} loading={busy}>{t('policies.action.validate')}</Button>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {t('policies.action.validateHint')}
            </Typography.Text>
          </Space>

          {check && (
            <Alert
              type={check.ok ? 'success' : 'warning'}
              showIcon
              message={check.ok ? t('policies.check.pass') : check.warning}
              description={
                <Typography.Text style={{ fontSize: 12 }}>
                  {t('policies.check.codeExists', { v: check.codeExists ? t('policies.yes') : t('policies.no') })} ｜
                  {t('policies.check.historyHits', { n: check.historyHits })} ｜
                  {t('policies.check.shadowed', { n: check.shadowedBy })}
                </Typography.Text>
              }
            />
          )}
        </Space>
      </Modal>
    </>
  );
}
