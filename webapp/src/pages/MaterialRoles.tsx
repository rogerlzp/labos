import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Button, Card, Empty, Form, Input, InputNumber, Modal, Popconfirm, Select,
  Space, Table, Tag, Typography, message,
} from 'antd';
import { apiFetch } from '../api/client';
import { useDesignOnly } from '../auth';
import { useT } from '../i18n';

/**
 * 物料角色字典（P2-8）。
 *
 * <p>为什么需要一个页面：`node_binding.material_role`、模板的 `manifest_items.role_code`、
 * 以及点位视角 palette 的「物料角色」下拉，全都读这张表。它原先只有
 * `GET/POST/PUT/DELETE /api/material-roles` 四个接口，**没有任何界面入口** ⇒
 * 新环境里它是空的 ⇒ 角色下拉没有选项 ⇒ 绑定存成 `role=null` ⇒ 预检 PF-23
 * 一直提示、跨点位搬运不知道"搬哪块板"。（此前只能靠 scripts/seed-material-roles.py 兜。）
 *
 * <p>`physicalType` 是**物理形态**（板/盖/架/槽/吸头盒），不是业务名；
 * 业务叫什么由 `roleCode + displayName` 表达。
 */

interface MaterialRole {
  id: number;
  roleCode: string;
  displayName: string;
  physicalType: string;
  plateType?: string | null;
  barcodeRegex?: string | null;
  maxHeightMm?: number | null;
  autoBarcodePrefix?: string | null;
}

/** 与后端 MATERIAL_PHYSICAL 保持一致（写死在这里会被后端 400 兜住，不会脏写）。 */
const PHYSICAL_TYPES = ['plate', 'lid', 'rack', 'reservoir', 'tip_box'];

export default function MaterialRolesPage() {
  const t = useT();
  const ro = useDesignOnly();
  const qc = useQueryClient();
  const [editing, setEditing] = useState<MaterialRole | null>(null);
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm();

  const q = useQuery({
    queryKey: ['material-roles'],
    queryFn: () => apiFetch('/api/material-roles')
      .then((r) => r.json()) as Promise<{ items: MaterialRole[] }>,
  });
  const items = q.data?.items ?? [];
  const refresh = () => {
    void qc.invalidateQueries({ queryKey: ['material-roles'] }).catch(() => undefined);
  };

  const startCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ physicalType: 'plate' });
    setOpen(true);
  };

  const startEdit = (row: MaterialRole) => {
    setEditing(row);
    form.setFieldsValue({
      roleCode: row.roleCode,
      displayName: row.displayName,
      physicalType: row.physicalType,
      plateType: row.plateType ?? undefined,
      barcodeRegex: row.barcodeRegex ?? undefined,
      maxHeightMm: row.maxHeightMm ?? undefined,
    });
    setOpen(true);
  };

  const save = async () => {
    const v = await form.validateFields();
    const body = {
      roleCode: v.roleCode?.trim(),
      displayName: v.displayName?.trim(),
      physicalType: v.physicalType,
      plateType: v.plateType?.trim() || null,
      barcodeRegex: v.barcodeRegex?.trim() || null,
      maxHeightMm: v.maxHeightMm ?? null,
    };
    const r = editing
      ? await apiFetch(`/api/material-roles/${editing.id}`, {
        method: 'PUT', body: JSON.stringify(body),
      })
      : await apiFetch('/api/material-roles', {
        method: 'POST', body: JSON.stringify(body),
      });
    const j = await r.json().catch(() => ({}));
    if (!r.ok) {
      message.error(j.error ?? t('materialRoles.error.save'));
      return;
    }
    message.success(t('materialRoles.toast.saved'));
    setOpen(false);
    refresh();
  };

  const remove = async (row: MaterialRole) => {
    const r = await apiFetch(`/api/material-roles/${row.id}`, { method: 'DELETE' });
    const j = await r.json().catch(() => ({}));
    if (!r.ok) {
      message.error(j.error ?? t('materialRoles.error.delete'));
      return;
    }
    message.success(t('materialRoles.toast.deleted'));
    refresh();
  };

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card
        size="small"
        title={t('materialRoles.title')}
        extra={(
          <Button type="primary" size="small" disabled={ro.disabled}
            title={ro.title} onClick={startCreate}>
            {t('materialRoles.action.create')}
          </Button>
        )}
      >
        <Typography.Paragraph type="secondary" style={{ fontSize: 11 }}>
          {t('materialRoles.hint')}
        </Typography.Paragraph>
        {items.length === 0 ? <Empty description={t('materialRoles.empty')} /> : (
          <Table<MaterialRole>
            size="small" rowKey="id" pagination={false} dataSource={items}
            columns={[
              {
                title: t('materialRoles.col.code'), dataIndex: 'roleCode', width: 160,
                render: (v: string) => <Typography.Text code>{v}</Typography.Text>,
              },
              { title: t('materialRoles.col.name'), dataIndex: 'displayName', width: 180 },
              {
                title: t('materialRoles.col.physical'), dataIndex: 'physicalType', width: 120,
                render: (v: string) => <Tag>{v}</Tag>,
              },
              {
                title: t('materialRoles.col.plateType'), dataIndex: 'plateType', width: 100,
                render: (v?: string | null) => v || '—',
              },
              {
                title: t('materialRoles.col.barcode'), dataIndex: 'barcodeRegex',
                render: (v?: string | null) => (v
                  ? <Typography.Text code style={{ fontSize: 11 }}>{v}</Typography.Text>
                  : '—'),
              },
              {
                title: '', key: 'op', width: 150, align: 'right' as const,
                render: (_: unknown, row: MaterialRole) => (
                  <Space size={4}>
                    <Button size="small" disabled={ro.disabled} title={ro.title}
                      onClick={() => startEdit(row)}>
                      {t('materialRoles.action.edit')}
                    </Button>
                    <Popconfirm
                      title={t('materialRoles.confirm.delete')}
                      onConfirm={() => remove(row)}
                      disabled={ro.disabled}
                    >
                      <Button size="small" danger type="link" disabled={ro.disabled}>
                        {t('materialRoles.action.delete')}
                      </Button>
                    </Popconfirm>
                  </Space>
                ),
              },
            ]}
          />
        )}
      </Card>

      <Modal
        open={open}
        title={editing ? t('materialRoles.action.edit') : t('materialRoles.action.create')}
        onCancel={() => setOpen(false)}
        onOk={save}
        okText={t('materialRoles.action.save')}
        destroyOnClose
      >
        <Form form={form} layout="vertical" size="small">
          <Form.Item name="roleCode" label={t('materialRoles.col.code')}
            rules={[{ required: true }]}
            extra={editing ? t('materialRoles.hint.codeImmutable') : undefined}>
            <Input disabled={!!editing} placeholder="mainPlate" />
          </Form.Item>
          <Form.Item name="displayName" label={t('materialRoles.col.name')}
            rules={[{ required: true }]}>
            <Input placeholder={t('materialRoles.placeholder.name')} />
          </Form.Item>
          <Form.Item name="physicalType" label={t('materialRoles.col.physical')}
            rules={[{ required: true }]}>
            <Select options={PHYSICAL_TYPES.map((v) => ({ value: v, label: v }))} />
          </Form.Item>
          <Space size={12} style={{ width: '100%' }}>
            <Form.Item name="plateType" label={t('materialRoles.col.plateType')}>
              <Input placeholder="96 / 384" style={{ width: 140 }} />
            </Form.Item>
            <Form.Item name="maxHeightMm" label={t('materialRoles.col.height')}>
              <InputNumber min={0} step={0.5} style={{ width: 140 }} />
            </Form.Item>
          </Space>
          <Form.Item name="barcodeRegex" label={t('materialRoles.col.barcode')}
            extra={t('materialRoles.hint.barcode')}>
            <Input placeholder="^[A-Z0-9_-]{4,64}$" />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  );
}
