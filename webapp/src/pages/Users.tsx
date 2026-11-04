import { apiFetch } from '../api/client';
import { roleColor, roleLabel } from '../auth';
import { useI18n, useT } from '../i18n';
import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert, Button, Card, Form, Input, Modal, Select, Space, Switch, Table, Tag, Typography, message,
} from 'antd';

interface User {
  id: number;
  username: string;
  displayName: string;
  role: string;
  enabled: boolean;
  lastLoginAt: string | null;
  createdAt: string;
}

/** 用户管理：仅 admin 可见可改。 */
export default function UsersPage() {
  const qc = useQueryClient();
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<User | null>(null);
  const [form] = Form.useForm();
  const [editForm] = Form.useForm();
  const t = useT();
  const { locale } = useI18n();

  const { data, isLoading, error } = useQuery({
    queryKey: ['users'],
    queryFn: () => apiFetch('/api/users', {
      headers: { Authorization: `Bearer ${localStorage.getItem('labos_token')}` },
    }).then((r) => r.json()) as Promise<{ items: User[] }>,
  });

  if (error) {
    return <Alert type="error" showIcon message={t('users.error.load')}
      description={t('users.error.notAdmin')} />;
  }

  const create = async () => {
    const v = await form.validateFields();
    const r = await apiFetch('/api/users', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${localStorage.getItem('labos_token')}`,
      },
      body: JSON.stringify(v),
    });
    if (!r.ok) {
      message.error((await r.json()).error ?? t('users.toast.createFailed'));
      return;
    }
    message.success(t('users.toast.created'));
    setCreating(false);
    form.resetFields();
    await qc.invalidateQueries({ queryKey: ['users'] });
  };

  const update = async () => {
    if (!editing) return;
    const v = await editForm.validateFields();
    const r = await apiFetch(`/api/users/${editing.id}`, {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${localStorage.getItem('labos_token')}`,
      },
      body: JSON.stringify(v),
    });
    if (!r.ok) {
      message.error((await r.json()).error ?? t('users.toast.updateFailed'));
      return;
    }
    message.success(t('users.toast.saved'));
    setEditing(null);
    editForm.resetFields();
    await qc.invalidateQueries({ queryKey: ['users'] });
  };

  return (
    <>
      <Card
        size="small"
        title={t('users.title', { n: data?.items.length ?? 0 })}
        extra={
          <Space>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {t('users.roleHint')}
            </Typography.Text>
            <Button type="primary" onClick={() => setCreating(true)}>{t('users.action.create')}</Button>
          </Space>
        }
      >
        <Table<User>
          rowKey="id" size="small" loading={isLoading}
          dataSource={data?.items ?? []}
          pagination={false}
          columns={[
            { title: t('users.col.username'), dataIndex: 'username', width: 180 },
            { title: t('users.col.displayName'), dataIndex: 'displayName', width: 150 },
            {
              title: t('users.col.role'), dataIndex: 'role', width: 110,
              render: (v: string) => <Tag color={roleColor(v)}>{roleLabel(v, locale)}</Tag>,
            },
            {
              title: t('users.col.status'), dataIndex: 'enabled', width: 90,
              render: (v: boolean) => (v
                ? <Tag color="green">{t('users.state.enabled')}</Tag>
                : <Tag>{t('users.state.disabled')}</Tag>),
            },
            {
              title: t('users.col.lastLogin'), dataIndex: 'lastLoginAt', width: 180,
              render: (v: string | null) => (v ? v.slice(0, 19).replace('T', ' ') : '—'),
            },
            {
              title: t('users.col.action'), width: 90,
              render: (_, r) => (
                <Button size="small" type="link" onClick={() => {
                  setEditing(r);
                  editForm.setFieldsValue({ role: r.role, enabled: r.enabled });
                }}>{t('users.action.edit')}</Button>
              ),
            },
          ]}
        />
      </Card>

      <Modal open={creating} title={t('users.modal.createTitle')} onCancel={() => setCreating(false)}
        onOk={create} okText={t('users.modal.createOk')}>
        <Form form={form} layout="vertical" initialValues={{ role: 'operator' }}>
          <Form.Item name="username" label={t('users.field.username')} rules={[{ required: true }]}>
            <Input placeholder={t('users.placeholder.username')} />
          </Form.Item>
          <Form.Item name="displayName" label={t('users.field.displayName')}>
            <Input placeholder={t('users.placeholder.displayName')} />
          </Form.Item>
          <Form.Item name="role" label={t('users.field.role')} rules={[{ required: true }]}>
            <Select options={[
              { value: 'admin', label: t('users.roleOption.adminFull') },
              { value: 'operator', label: t('users.roleOption.operatorFull') },
              { value: 'viewer', label: t('users.roleOption.viewer') },
            ]} />
          </Form.Item>
          <Form.Item name="password" label={t('users.field.password')}
            rules={[{ required: true, min: 6, message: t('users.rule.minLength') }]}>
            <Input.Password placeholder={t('users.rule.minLength')} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal open={editing != null} title={t('users.modal.editTitle', { username: editing?.username ?? '' })}
        onCancel={() => setEditing(null)} onOk={update} okText={t('users.modal.saveOk')}>
        <Form form={editForm} layout="vertical">
          <Form.Item name="role" label={t('users.field.role')}>
            <Select options={[
              { value: 'admin', label: t('users.roleOption.admin') },
              { value: 'operator', label: t('users.roleOption.operator') },
              { value: 'viewer', label: t('users.roleOption.viewer') },
            ]} />
          </Form.Item>
          <Form.Item name="enabled" label={t('users.field.enabled')} valuePropName="checked">
            <Switch />
          </Form.Item>
          <Form.Item name="password" label={t('users.field.resetPassword')}
            rules={[{ min: 6, message: t('users.rule.minLength') }]}>
            <Input.Password placeholder={t('users.placeholder.keepPassword')} />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
