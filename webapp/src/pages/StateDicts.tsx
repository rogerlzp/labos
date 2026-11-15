import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert, Button, Card, Input, InputNumber, Modal, Popconfirm, Select, Space, Switch,
  Table, Tabs, Tag, Typography, message,
} from 'antd';
import { api } from '../api/client';
import type { DeviceBusinessState, PlateProcessState } from '../api/client';
import { useReadOnly } from '../auth';
import { useT } from '../i18n';
import type { MessageKey } from '../i18n/core';

/**
 * 状态字典（板状态 + 设备业务状态）。
 *
 * <p>**为什么这两张表要放一起**：它们是同一类东西的两面 ——
 * 「板在工艺里处于什么阶段」与「设备在业务上处于什么状态」，
 * 都来自客户工艺文档的枚举，都不该写死在代码里。
 *
 * <p>⚠ **板状态与设备业务状态是两套不同的东西，别混**：
 * <ul>
 *   <li>板状态（{@code plate_process_state}）= 板在**物料工艺**里的阶段
 *       （未处理 / 已就绪 / 处理中 / 待交互 / 已完成 / 已废弃）。</li>
 *   <li>设备业务状态（{@code device_business_state}）= 设备**在干什么**
 *       （提取仪：初始化中 / 等待交互 / 转盘运行中 / 操作位执行工序 …）。</li>
 *   <li>而 {@code device_state.status}（idle/running/error）是**运行时健康与占用标记**，
 *       第三回事，别拿来当业务状态用。</li>
 * </ul>
 *
 * <p>「客户文档叫法」（docLabel）是客户工艺文档里的措辞（如 §1.4 的「空板」）
 * 与平台通用措辞（raw）的对照 —— 做成配置列，客户看得见，也不绑死任何一边。
 */

/** 平台自己的 device_type 口径（与字典里的 device_kind 一一对应）。 */
const DEVICE_KINDS: Array<{ value: string; labelKey: MessageKey }> = [
  { value: 'extractor', labelKey: 'stateDicts.kind.extractor' },
  { value: 'dispenser', labelKey: 'stateDicts.kind.dispenser' },
  { value: 'stirrer', labelKey: 'stateDicts.kind.stirrer' },
  { value: 'robot', labelKey: 'stateDicts.kind.robot' },
  { value: 'plate_hotel', labelKey: 'stateDicts.kind.plateHotel' },
];

interface PlateForm {
  code: string;
  displayName: string;
  docLabel: string;
  sortOrder: number;
  enabled: boolean;
}

interface DeviceForm {
  deviceKind: string;
  stateCode: string;
  stateName: string;
  sortOrder: number;
  enabled: boolean;
}

const EMPTY_PLATE: PlateForm = {
  code: '', displayName: '', docLabel: '', sortOrder: 100, enabled: true,
};
const EMPTY_DEVICE: DeviceForm = {
  deviceKind: 'extractor', stateCode: '', stateName: '', sortOrder: 100, enabled: true,
};

export default function StateDictsPage() {
  const t = useT();
  const qc = useQueryClient();
  // 字典是配置类写操作：viewer 只读
  const ro = useReadOnly();

  const [tab, setTab] = useState('plate');
  const [plateOpen, setPlateOpen] = useState(false);
  const [deviceOpen, setDeviceOpen] = useState(false);
  /** 非 null = 编辑模式；null = 新建模式。 */
  const [plateEditing, setPlateEditing] = useState<string | null>(null);
  const [deviceEditing, setDeviceEditing] = useState<number | null>(null);
  const [plateForm, setPlateForm] = useState<PlateForm>(EMPTY_PLATE);
  const [deviceForm, setDeviceForm] = useState<DeviceForm>(EMPTY_DEVICE);
  const [busy, setBusy] = useState(false);

  const plateQuery = useQuery({
    queryKey: ['plate-state-dicts'],
    queryFn: () => api.plateStateDicts(true),
  });
  const deviceQuery = useQuery({
    queryKey: ['device-business-states'],
    queryFn: () => api.deviceBusinessStates(),
  });

  const plateItems = plateQuery.data?.items ?? [];
  const deviceItems = deviceQuery.data?.items ?? [];

  const refresh = () => {
    qc.invalidateQueries({ queryKey: ['plate-state-dicts'] });
    qc.invalidateQueries({ queryKey: ['device-business-states'] });
  };

  // ---------------------------------------------------------------- 板状态

  const openPlateCreate = () => {
    setPlateEditing(null);
    setPlateForm(EMPTY_PLATE);
    setPlateOpen(true);
  };

  const openPlateEdit = (row: PlateProcessState) => {
    setPlateEditing(row.code);
    setPlateForm({
      code: row.code,
      displayName: row.displayName,
      docLabel: row.docLabel ?? '',
      sortOrder: row.sortOrder ?? 100,
      enabled: row.enabled ?? true,
    });
    setPlateOpen(true);
  };

  const savePlate = async () => {
    const code = plateForm.code.trim();
    const name = plateForm.displayName.trim();
    if (!code) {
      message.warning(t('stateDicts.warn.codeEmpty'));
      return;
    }
    if (!name) {
      message.warning(t('stateDicts.warn.displayNameEmpty'));
      return;
    }
    setBusy(true);
    try {
      if (plateEditing == null) {
        await api.createPlateState({
          code, displayName: name,
          sortOrder: plateForm.sortOrder,
          docLabel: plateForm.docLabel.trim() || undefined,
        });
        message.success(t('stateDicts.toast.created', { code }));
      } else {
        await api.updatePlateState(plateEditing, {
          displayName: name,
          sortOrder: plateForm.sortOrder,
          enabled: plateForm.enabled,
          docLabel: plateForm.docLabel.trim() || undefined,
        });
        message.success(t('stateDicts.toast.saved', { code }));
      }
      setPlateOpen(false);
      refresh();
    } catch (e) {
      message.error(t('stateDicts.toast.saveFailed', { msg: (e as Error).message }));
    } finally {
      setBusy(false);
    }
  };

  const removePlate = async (row: PlateProcessState) => {
    try {
      await api.deletePlateState(row.code);
      message.success(t('stateDicts.toast.deleted', { code: row.code }));
      refresh();
    } catch (e) {
      // 后端 409 的原文（"仍被引用，不能删"）直接透给现场
      message.error(t('stateDicts.toast.deleteFailed', { msg: (e as Error).message }));
    }
  };

  // ---------------------------------------------------------------- 设备业务状态

  const openDeviceCreate = () => {
    setDeviceEditing(null);
    setDeviceForm(EMPTY_DEVICE);
    setDeviceOpen(true);
  };

  const openDeviceEdit = (row: DeviceBusinessState) => {
    setDeviceEditing(row.id);
    setDeviceForm({
      deviceKind: row.deviceKind,
      stateCode: row.stateCode,
      stateName: row.stateName,
      sortOrder: row.sortOrder,
      enabled: row.enabled,
    });
    setDeviceOpen(true);
  };

  const saveDevice = async () => {
    const code = deviceForm.stateCode.trim();
    const name = deviceForm.stateName.trim();
    if (!code) {
      message.warning(t('stateDicts.warn.codeEmpty'));
      return;
    }
    if (!name) {
      message.warning(t('stateDicts.warn.displayNameEmpty'));
      return;
    }
    setBusy(true);
    try {
      if (deviceEditing == null) {
        await api.createDeviceBusinessState({
          deviceKind: deviceForm.deviceKind,
          stateCode: code,
          stateName: name,
          sortOrder: deviceForm.sortOrder,
        });
        message.success(t('stateDicts.toast.created', { code }));
      } else {
        await api.updateDeviceBusinessState(deviceEditing, {
          stateName: name,
          sortOrder: deviceForm.sortOrder,
          enabled: deviceForm.enabled,
        });
        message.success(t('stateDicts.toast.saved', { code }));
      }
      setDeviceOpen(false);
      refresh();
    } catch (e) {
      message.error(t('stateDicts.toast.saveFailed', { msg: (e as Error).message }));
    } finally {
      setBusy(false);
    }
  };

  const removeDevice = async (row: DeviceBusinessState) => {
    try {
      await api.deleteDeviceBusinessState(row.id);
      message.success(t('stateDicts.toast.deleted', { code: row.stateCode }));
      refresh();
    } catch (e) {
      message.error(t('stateDicts.toast.deleteFailed', { msg: (e as Error).message }));
    }
  };

  const kindLabel = (v: string) =>
    t(DEVICE_KINDS.find((k) => k.value === v)?.labelKey ?? 'stateDicts.kind.extractor');

  // ---------------------------------------------------------------- 渲染

  const plateTab = (
    <Card
      size="small"
      title={t('stateDicts.plate.title', { n: plateItems.length })}
      extra={
        <Button type="primary" {...ro} onClick={openPlateCreate}>
          {t('stateDicts.action.create')}
        </Button>
      }
    >
      {plateQuery.error ? (
        <Alert type="error" showIcon message={t('stateDicts.error.load')}
          description={String((plateQuery.error as Error).message)} />
      ) : (
        <Table<PlateProcessState>
          rowKey="code" size="small" loading={plateQuery.isLoading}
          dataSource={plateItems} pagination={{ pageSize: 20, showSizeChanger: false }}
          columns={[
            {
              // 状态码是字典的键 ⇒ 等宽字体，方便和流程声明里的字串逐字比对
              title: t('stateDicts.col.code'), dataIndex: 'code', width: 200,
              render: (v: string) => <Typography.Text code>{v}</Typography.Text>,
            },
            {
              title: t('stateDicts.col.displayName'), dataIndex: 'displayName', ellipsis: true,
              render: (v: string) => v || '—',
            },
            {
              title: t('stateDicts.col.docLabel'), dataIndex: 'docLabel', width: 200,
              render: (v?: string | null) => v || '—',
            },
            {
              title: t('stateDicts.col.sortOrder'), dataIndex: 'sortOrder', width: 80,
            },
            {
              title: t('stateDicts.col.enabled'), dataIndex: 'enabled', width: 100,
              render: (v?: boolean) => (
                <Tag color={v ? 'green' : 'default'}>
                  {v ? t('stateDicts.enabled.on') : t('stateDicts.enabled.off')}
                </Tag>
              ),
            },
            {
              title: t('stateDicts.col.action'), width: 140,
              render: (_, r) => (
                <Space size={0}>
                  <Button size="small" type="link" {...ro} onClick={() => openPlateEdit(r)}>
                    {t('stateDicts.action.edit')}
                  </Button>
                  <Popconfirm
                    title={t('stateDicts.confirm.deleteTitle', { code: r.code })}
                    description={t('stateDicts.confirm.deleteDesc')}
                    okText={t('stateDicts.confirm.deleteOk')} okButtonProps={{ danger: true }}
                    onConfirm={() => removePlate(r)}
                  >
                    <Button size="small" type="link" danger {...ro}>
                      {t('stateDicts.action.delete')}
                    </Button>
                  </Popconfirm>
                </Space>
              ),
            },
          ]}
        />
      )}
    </Card>
  );

  const deviceTab = (
    <Card
      size="small"
      title={t('stateDicts.device.title', { n: deviceItems.length })}
      extra={
        <Button type="primary" {...ro} onClick={openDeviceCreate}>
          {t('stateDicts.action.create')}
        </Button>
      }
    >
      {deviceQuery.error ? (
        <Alert type="error" showIcon message={t('stateDicts.error.load')}
          description={String((deviceQuery.error as Error).message)} />
      ) : (
        <Table<DeviceBusinessState>
          rowKey="id" size="small" loading={deviceQuery.isLoading}
          dataSource={deviceItems}
          pagination={{ pageSize: 30, showSizeChanger: false }}
          columns={[
            {
              title: t('stateDicts.col.deviceKind'), dataIndex: 'deviceKind', width: 170,
              render: (v: string) => <Tag color="blue">{kindLabel(v)}</Tag>,
            },
            {
              title: t('stateDicts.col.stateCode'), dataIndex: 'stateCode', width: 220,
              render: (v: string) => <Typography.Text code>{v}</Typography.Text>,
            },
            {
              title: t('stateDicts.col.stateName'), dataIndex: 'stateName', ellipsis: true,
              render: (v: string) => v || '—',
            },
            {
              title: t('stateDicts.col.sortOrder'), dataIndex: 'sortOrder', width: 80,
            },
            {
              title: t('stateDicts.col.enabled'), dataIndex: 'enabled', width: 100,
              render: (v: boolean) => (
                <Tag color={v ? 'green' : 'default'}>
                  {v ? t('stateDicts.enabled.on') : t('stateDicts.enabled.off')}
                </Tag>
              ),
            },
            {
              title: t('stateDicts.col.action'), width: 140,
              render: (_, r) => (
                <Space size={0}>
                  <Button size="small" type="link" {...ro} onClick={() => openDeviceEdit(r)}>
                    {t('stateDicts.action.edit')}
                  </Button>
                  <Popconfirm
                    title={t('stateDicts.confirm.deleteTitle', { code: r.stateCode })}
                    description={t('stateDicts.confirm.deleteDesc')}
                    okText={t('stateDicts.confirm.deleteOk')} okButtonProps={{ danger: true }}
                    onConfirm={() => removeDevice(r)}
                  >
                    <Button size="small" type="link" danger {...ro}>
                      {t('stateDicts.action.delete')}
                    </Button>
                  </Popconfirm>
                </Space>
              ),
            },
          ]}
        />
      )}
    </Card>
  );

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Alert
        type="info" showIcon
        message={t('stateDicts.alert.title')}
        description={t('stateDicts.alert.desc')}
      />

      <Tabs
        activeKey={tab}
        onChange={setTab}
        items={[
          { key: 'plate', label: t('stateDicts.tab.plate'), children: plateTab },
          { key: 'device', label: t('stateDicts.tab.device'), children: deviceTab },
        ]}
      />

      <Modal
        open={plateOpen}
        title={plateEditing != null
          ? t('stateDicts.modal.editTitle', { code: plateForm.code })
          : t('stateDicts.modal.createTitle')}
        onCancel={() => setPlateOpen(false)}
        footer={[
          <Button key="close" onClick={() => setPlateOpen(false)}>{t('stateDicts.action.close')}</Button>,
          <Button key="save" type="primary" loading={busy} {...ro} onClick={savePlate}>
            {t('stateDicts.action.save')}
          </Button>,
        ]}
        width={720}
      >
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <div>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {t('stateDicts.field.code')}
            </Typography.Text>
            <Input
              style={{ width: 320 }}
              value={plateForm.code}
              readOnly={plateEditing != null}
              placeholder={plateEditing != null ? undefined : t('stateDicts.placeholder.code')}
              onChange={(e) => setPlateForm({ ...plateForm, code: e.target.value })}
            />
            <div style={{ fontSize: 11, color: '#8b93a7' }}>
              {plateEditing != null
                ? t('stateDicts.hint.codeImmutable')
                : t('stateDicts.hint.codeCreate')}
            </div>
          </div>

          <div>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {t('stateDicts.field.displayName')}
            </Typography.Text>
            <Input
              style={{ width: 320 }}
              value={plateForm.displayName}
              placeholder={t('stateDicts.placeholder.displayName')}
              onChange={(e) => setPlateForm({ ...plateForm, displayName: e.target.value })}
            />
          </div>

          <div>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {t('stateDicts.field.docLabel')}
            </Typography.Text>
            <Input
              style={{ width: 320 }}
              value={plateForm.docLabel}
              placeholder={t('stateDicts.placeholder.docLabel')}
              onChange={(e) => setPlateForm({ ...plateForm, docLabel: e.target.value })}
            />
            <div style={{ fontSize: 11, color: '#8b93a7' }}>{t('stateDicts.hint.docLabel')}</div>
          </div>

          <div>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {t('stateDicts.field.sortOrder')}
            </Typography.Text>
            <InputNumber
              style={{ width: 160 }}
              min={0} max={9999}
              value={plateForm.sortOrder}
              onChange={(v) => setPlateForm({ ...plateForm, sortOrder: Number(v ?? 100) })}
            />
            <div style={{ fontSize: 11, color: '#8b93a7' }}>{t('stateDicts.hint.sortOrder')}</div>
          </div>

          <div>
            <Switch
              checked={plateForm.enabled}
              disabled={plateEditing == null}
              onChange={(v) => setPlateForm({ ...plateForm, enabled: v })}
            />
            <Typography.Text style={{ marginLeft: 8, fontSize: 12 }}>
              {t('stateDicts.field.enabled')}
            </Typography.Text>
            <div style={{ fontSize: 11, color: '#8b93a7' }}>{t('stateDicts.hint.enabled')}</div>
          </div>
        </Space>
      </Modal>

      <Modal
        open={deviceOpen}
        title={deviceEditing != null
          ? t('stateDicts.modal.editTitle', { code: deviceForm.stateCode })
          : t('stateDicts.modal.createTitle')}
        onCancel={() => setDeviceOpen(false)}
        footer={[
          <Button key="close" onClick={() => setDeviceOpen(false)}>{t('stateDicts.action.close')}</Button>,
          <Button key="save" type="primary" loading={busy} {...ro} onClick={saveDevice}>
            {t('stateDicts.action.save')}
          </Button>,
        ]}
        width={720}
      >
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <div>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {t('stateDicts.field.deviceKind')}
            </Typography.Text>
            <Select
              style={{ width: 240 }}
              value={deviceForm.deviceKind}
              disabled={deviceEditing != null}
              options={DEVICE_KINDS.map((k) => ({ value: k.value, label: t(k.labelKey) }))}
              onChange={(v) => setDeviceForm({ ...deviceForm, deviceKind: v })}
            />
            <div style={{ fontSize: 11, color: '#8b93a7' }}>{t('stateDicts.hint.deviceKind')}</div>
          </div>

          <div>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {t('stateDicts.field.stateCode')}
            </Typography.Text>
            <Input
              style={{ width: 320 }}
              value={deviceForm.stateCode}
              readOnly={deviceEditing != null}
              placeholder={deviceEditing != null ? undefined : t('stateDicts.placeholder.code')}
              onChange={(e) => setDeviceForm({ ...deviceForm, stateCode: e.target.value })}
            />
            <div style={{ fontSize: 11, color: '#8b93a7' }}>
              {deviceEditing != null
                ? t('stateDicts.hint.codeImmutable')
                : t('stateDicts.hint.codeCreate')}
            </div>
          </div>

          <div>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {t('stateDicts.field.stateName')}
            </Typography.Text>
            <Input
              style={{ width: 320 }}
              value={deviceForm.stateName}
              placeholder={t('stateDicts.placeholder.displayName')}
              onChange={(e) => setDeviceForm({ ...deviceForm, stateName: e.target.value })}
            />
          </div>

          <div>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {t('stateDicts.field.sortOrder')}
            </Typography.Text>
            <InputNumber
              style={{ width: 160 }}
              min={0} max={9999}
              value={deviceForm.sortOrder}
              onChange={(v) => setDeviceForm({ ...deviceForm, sortOrder: Number(v ?? 100) })}
            />
            <div style={{ fontSize: 11, color: '#8b93a7' }}>{t('stateDicts.hint.sortOrder')}</div>
          </div>

          <div>
            <Switch
              checked={deviceForm.enabled}
              disabled={deviceEditing == null}
              onChange={(v) => setDeviceForm({ ...deviceForm, enabled: v })}
            />
            <Typography.Text style={{ marginLeft: 8, fontSize: 12 }}>
              {t('stateDicts.field.enabled')}
            </Typography.Text>
            <div style={{ fontSize: 11, color: '#8b93a7' }}>{t('stateDicts.hint.enabled')}</div>
          </div>
        </Space>
      </Modal>
    </Space>
  );
}
