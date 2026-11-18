import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert, Button, Card, Checkbox, Input, InputNumber, Modal, Popconfirm, Select,
  Space, Switch, Table, Tag, Tooltip, Typography, message,
} from 'antd';
import { api } from '../api/client';
import type { DeviceEventType } from '../api/client';
import { useAdminOnly } from '../auth';
import { useT } from '../i18n';
import type { MessageKey } from '../i18n/core';

/**
 * 设备事件类型字典（事件码字典）。
 *
 * <p>事件码（waiting_interaction / process_done / …）是「侧车产出 / 内核匹配 /
 * 流程节点引用」三方对齐的**唯一键**。以前它只活在代码里 ⇒ 任何一边改了字串，
 * 另一边**静默失配** ⇒ 上位机永远等不到那个事件，现场只看到"卡住不动"，
 * 没有任何报错指向真凶。
 *
 * <p>⇒ 字串存库，设计器从这张表下拉选；真机手册到手后在这里改字串 +
 * 改侧车 translator 即可，**不用改内核**（内核只按字串匹配，不认具体字面量）。
 */

/**
 * 语义分类：只是**界面分组**，不是行为开关。
 *
 * <p>真要「上位机收到后要不要停下来等人」⇒ 看 needsAction，不看 category。
 */
const CATEGORIES: Array<{
  value: DeviceEventType['category'];
  labelKey: MessageKey;
  hintKey: MessageKey;
}> = [
  {
    value: 'interaction',
    labelKey: 'eventTypes.category.interaction',
    hintKey: 'eventTypes.hint.interaction',
  },
  {
    value: 'progress',
    labelKey: 'eventTypes.category.progress',
    hintKey: 'eventTypes.hint.progress',
  },
  {
    value: 'terminal',
    labelKey: 'eventTypes.category.terminal',
    hintKey: 'eventTypes.hint.terminal',
  },
  {
    value: 'fault',
    labelKey: 'eventTypes.category.fault',
    hintKey: 'eventTypes.hint.fault',
  },
];

const CATEGORY_COLOR: Record<string, string> = {
  interaction: 'blue',
  progress: 'default',
  terminal: 'green',
  fault: 'red',
};

/** 弹窗里的可编辑副本。 */
interface FormState {
  eventCode: string;
  displayName: string;
  category: DeviceEventType['category'];
  needsAction: boolean;
  sortOrder: number;
  enabled: boolean;
}

const EMPTY_FORM: FormState = {
  eventCode: '',
  displayName: '',
  category: 'progress',
  needsAction: false,
  sortOrder: 100,
  enabled: true,
};

export default function EventTypesPage() {
  const t = useT();
  const qc = useQueryClient();
  // 事件码字典是配置类写操作：viewer 只读
  // 资产类写（建岛/建设备/改驱动/建池/改系统字典）＝仅管理员
  // 与后端 ApiAuthInterceptor.isAdminOnlyAssetWrite 对齐（2026-11-18）
  const ao = useAdminOnly();

  const [open, setOpen] = useState(false);
  /** 非 null = 编辑模式；null = 新建模式（新建时才允许填事件码）。 */
  const [editingId, setEditingId] = useState<number | null>(null);
  const [form, setForm] = useState<FormState>(EMPTY_FORM);
  const [busy, setBusy] = useState(false);

  const query = useQuery({
    queryKey: ['device-event-types'],
    queryFn: api.deviceEventTypes,
  });
  const items = query.data?.items ?? [];
  const isEdit = editingId != null;

  const refresh = () => qc.invalidateQueries({ queryKey: ['device-event-types'] });

  const categoryHint = (v: string) =>
    t(CATEGORIES.find((c) => c.value === v)?.hintKey ?? 'eventTypes.hint.progress');

  const openCreate = () => {
    setEditingId(null);
    setForm(EMPTY_FORM);
    setOpen(true);
  };

  const openEdit = (row: DeviceEventType) => {
    setEditingId(row.id);
    setForm({
      eventCode: row.eventCode,
      displayName: row.displayName,
      category: row.category,
      needsAction: row.needsAction,
      sortOrder: row.sortOrder,
      enabled: row.enabled,
    });
    setOpen(true);
  };

  /** 新建：事件码可填。 */
  const create = async () => {
    const code = form.eventCode.trim();
    const name = form.displayName.trim();
    if (!code) {
      message.warning(t('eventTypes.warn.codeEmpty'));
      return;
    }
    if (!name) {
      message.warning(t('eventTypes.warn.displayNameEmpty'));
      return;
    }
    setBusy(true);
    try {
      await api.createDeviceEventType({
        eventCode: code,
        displayName: name,
        category: form.category,
        needsAction: form.needsAction,
        sortOrder: form.sortOrder,
      });
      message.success(t('eventTypes.toast.created', { code }));
      setOpen(false);
      setForm(EMPTY_FORM);
      refresh();
    } catch (e) {
      // 后端 400/409 的原文直接透给现场（"eventCode 可能重复"这类）
      message.error(t('eventTypes.toast.createFailed', { msg: (e as Error).message }));
    } finally {
      setBusy(false);
    }
  };

  /** 编辑：**事件码不可改**（那是三方对齐的主键），其余字段整份提交。 */
  const save = async () => {
    if (editingId == null) return;
    const name = form.displayName.trim();
    if (!name) {
      message.warning(t('eventTypes.warn.displayNameEmpty'));
      return;
    }
    setBusy(true);
    try {
      await api.updateDeviceEventType(editingId, {
        displayName: name,
        category: form.category,
        needsAction: form.needsAction,
        sortOrder: form.sortOrder,
        enabled: form.enabled,
      });
      message.success(t('eventTypes.toast.saved', { code: form.eventCode }));
      setOpen(false);
      refresh();
    } catch (e) {
      message.error(t('eventTypes.toast.saveFailed', { msg: (e as Error).message }));
    } finally {
      setBusy(false);
    }
  };

  const remove = async (row: DeviceEventType) => {
    try {
      await api.deleteDeviceEventType(row.id);
      message.success(t('eventTypes.toast.deleted', { code: row.eventCode }));
      refresh();
    } catch (e) {
      message.error(t('eventTypes.toast.deleteFailed', { msg: (e as Error).message }));
    }
  };

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Alert
        type="info" showIcon
        message={t('eventTypes.alert.title')}
        description={t('eventTypes.alert.desc')}
      />

      <Card
        size="small"
        title={t('eventTypes.title', { n: items.length })}
        extra={
          <Button type="primary" {...ao} onClick={openCreate}>
            {t('eventTypes.action.create')}
          </Button>
        }
      >
        {query.error ? (
          <Alert type="error" showIcon message={t('eventTypes.error.load')}
            description={String((query.error as Error).message)} />
        ) : (
          <Table<DeviceEventType>
            rowKey="id" size="small" loading={query.isLoading}
            dataSource={items} pagination={{ pageSize: 20, showSizeChanger: false }}
            columns={[
              {
                // 事件码是三方对齐的主键 ⇒ 等宽字体，方便和侧车/流程节点里的字串逐字比对
                title: t('eventTypes.col.eventCode'), dataIndex: 'eventCode', width: 220,
                render: (v: string) => <Typography.Text code>{v}</Typography.Text>,
              },
              {
                title: t('eventTypes.col.displayName'), dataIndex: 'displayName', ellipsis: true,
                render: (v: string) => v || '—',
              },
              {
                title: t('eventTypes.col.category'), dataIndex: 'category', width: 130,
                render: (v: string) => (
                  <Tooltip title={categoryHint(v)}>
                    <Tag color={CATEGORY_COLOR[v] ?? 'default'}>
                      {t(CATEGORIES.find((c) => c.value === v)?.labelKey ?? 'eventTypes.category.progress')}
                    </Tag>
                  </Tooltip>
                ),
              },
              {
                title: t('eventTypes.col.needsAction'), dataIndex: 'needsAction', width: 110,
                render: (v: boolean) => (
                  <Tag color={v ? 'orange' : 'default'}>
                    {v ? t('eventTypes.yes') : t('eventTypes.no')}
                  </Tag>
                ),
              },
              {
                title: t('eventTypes.col.sortOrder'), dataIndex: 'sortOrder', width: 80,
              },
              {
                title: t('eventTypes.col.enabled'), dataIndex: 'enabled', width: 100,
                render: (v: boolean) => (
                  <Tag color={v ? 'green' : 'default'}>
                    {v ? t('eventTypes.enabled.on') : t('eventTypes.enabled.off')}
                  </Tag>
                ),
              },
              {
                title: t('eventTypes.col.action'), width: 140,
                render: (_, r) => (
                  <Space size={0}>
                    <Button size="small" type="link" {...ao} onClick={() => openEdit(r)}>
                      {t('eventTypes.action.edit')}
                    </Button>
                    <Popconfirm
                      title={t('eventTypes.confirm.deleteTitle', { code: r.eventCode })}
                      description={t('eventTypes.confirm.deleteDesc')}
                      okText={t('eventTypes.confirm.deleteOk')} okButtonProps={{ danger: true }}
                      onConfirm={() => remove(r)}
                    >
                      <Button size="small" type="link" danger {...ao}>
                        {t('eventTypes.action.delete')}
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
        title={isEdit
          ? t('eventTypes.modal.editTitle', { code: form.eventCode })
          : t('eventTypes.modal.createTitle')}
        onCancel={() => setOpen(false)}
        footer={[
          <Button key="close" onClick={() => setOpen(false)}>{t('eventTypes.action.close')}</Button>,
          <Button key="save" type="primary" loading={busy} {...ao}
            onClick={isEdit ? save : create}>
            {t('eventTypes.action.save')}
          </Button>,
        ]}
        width={720}
      >
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <div>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {t('eventTypes.field.eventCode')}
            </Typography.Text>
            <Input
              style={{ width: 320 }}
              value={form.eventCode}
              readOnly={isEdit}
              placeholder={isEdit ? undefined : t('eventTypes.placeholder.eventCode')}
              onChange={(e) => setForm({ ...form, eventCode: e.target.value })}
            />
            <div style={{ fontSize: 11, color: '#8b93a7' }}>
              {isEdit
                ? t('eventTypes.hint.eventCodeImmutable')
                : t('eventTypes.hint.eventCodeCreate')}
            </div>
          </div>

          <div>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {t('eventTypes.field.displayName')}
            </Typography.Text>
            <Input
              style={{ width: 320 }}
              value={form.displayName}
              placeholder={t('eventTypes.placeholder.displayName')}
              onChange={(e) => setForm({ ...form, displayName: e.target.value })}
            />
          </div>

          <div>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {t('eventTypes.field.category')}
            </Typography.Text>
            <Select
              style={{ width: 240 }}
              value={form.category}
              options={CATEGORIES.map((c) => ({ value: c.value, label: t(c.labelKey) }))}
              onChange={(v) => setForm({ ...form, category: v })}
            />
            <div style={{ fontSize: 11, color: '#8b93a7' }}>{categoryHint(form.category)}</div>
          </div>

          <div>
            <Checkbox
              checked={form.needsAction}
              onChange={(e) => setForm({ ...form, needsAction: e.target.checked })}
            >
              {t('eventTypes.field.needsAction')}
            </Checkbox>
            <div style={{ fontSize: 11, color: '#8b93a7' }}>{t('eventTypes.hint.needsAction')}</div>
          </div>

          <div>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {t('eventTypes.field.sortOrder')}
            </Typography.Text>
            <InputNumber
              style={{ width: 160 }}
              min={0} max={9999}
              value={form.sortOrder}
              onChange={(v) => setForm({ ...form, sortOrder: Number(v ?? 100) })}
            />
            <div style={{ fontSize: 11, color: '#8b93a7' }}>{t('eventTypes.hint.sortOrder')}</div>
          </div>

          <div>
            <Switch
              checked={form.enabled}
              disabled={!isEdit}
              onChange={(v) => setForm({ ...form, enabled: v })}
            />
            <Typography.Text style={{ marginLeft: 8, fontSize: 12 }}>
              {t('eventTypes.field.enabled')}
            </Typography.Text>
            <div style={{ fontSize: 11, color: '#8b93a7' }}>{t('eventTypes.hint.enabled')}</div>
          </div>
        </Space>
      </Modal>
    </Space>
  );
}
