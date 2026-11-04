import { apiFetch } from '../api/client';
import { useReadOnly } from '../auth';
import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Button, Card, Empty, Form, Input, Modal, Select, Space, Table, Tag, Typography, message,
} from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useT } from '../i18n';

interface Dev {
  resourceId: number;
  code: string;
  name: string;
  model?: string;
  vendor?: string;
  capability?: string;
  instanceId?: number;
}

interface Island {
  id: number;
  code: string;
  name: string;
  deviceCount: number;
  devices: Dev[];
}

interface Driver {
  id: number;
  code: string;
  endpoint: string;
}

interface Cap {
  code: string;
  name: string;
  devices: number;
}

const TOKEN = () => localStorage.getItem('labos_token');

async function post<T>(path: string, body: unknown): Promise<T> {
  const r = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${TOKEN()}` },
    body: JSON.stringify(body),
  });
  const j = await r.json();
  if (!r.ok) throw new Error(j.error ?? `HTTP ${r.status}`);
  return j as T;
}

/**
 * 设备与岛屿：资源树 + 新增。
 *
 * 一个设备要建三样东西：资源树节点（在哪）、型号（是什么）、实例（连哪）。
 * 这里一次提交，后端事务里一起建。
 */
export default function TopologyPage() {
  const t = useT();
  const qc = useQueryClient();
  // 设备/岛屿增删改都是写操作：viewer 只读浏览拓扑
  const ro = useReadOnly();
  const [picked, setPicked] = useState<Dev | null>(null);
  const [addIsland, setAddIsland] = useState(false);
  const [addDevice, setAddDevice] = useState<Island | null>(null);
  const [editing, setEditing] = useState<Dev | null>(null);
  const [editForm] = Form.useForm();
  const [islandForm] = Form.useForm();
  const [deviceForm] = Form.useForm();

  const topo = useQuery({
    queryKey: ['topology'],
    queryFn: () => apiFetch('/api/topology', {
      headers: { Authorization: `Bearer ${TOKEN()}` },
    }).then((r) => r.json()) as Promise<{ islands: Island[] }>,
  });

  const drivers = useQuery({
    queryKey: ['drivers'],
    queryFn: () => apiFetch('/api/drivers', {
      headers: { Authorization: `Bearer ${TOKEN()}` },
    }).then((r) => r.json()) as Promise<{ items: Driver[] }>,
    enabled: addDevice != null || editing != null,
  });

  const caps = useQuery({
    queryKey: ['capabilities'],
    queryFn: () => apiFetch('/api/capabilities', {
      headers: { Authorization: `Bearer ${TOKEN()}` },
    }).then((r) => r.json()) as Promise<{ items: Cap[] }>,
    enabled: addDevice != null || editing != null,
  });

  const methods = useQuery({
    queryKey: ['device-methods', picked?.resourceId],
    queryFn: () => apiFetch(`/api/devices/${picked!.resourceId}/methods`, {
      headers: { Authorization: `Bearer ${TOKEN()}` },
    }).then((r) => r.json()),
    enabled: picked != null,
  });

  const islands = topo.data?.islands ?? [];

  const submitIsland = async () => {
    const v = await islandForm.validateFields();
    try {
      await post('/api/islands', v);
      message.success(t('topology.toast.islandCreated'));
      setAddIsland(false);
      islandForm.resetFields();
      await qc.invalidateQueries({ queryKey: ['topology'] });
    } catch (e) {
      message.error(String((e as Error).message));
    }
  };

  const submitDevice = async () => {
    if (!addDevice) return;
    const v = await deviceForm.validateFields();
    try {
      await post('/api/devices', { ...v, islandId: addDevice.id });
      message.success(t('topology.toast.deviceCreated'));
      setAddDevice(null);
      deviceForm.resetFields();
      await qc.invalidateQueries({ queryKey: ['topology'] });
    } catch (e) {
      message.error(String((e as Error).message));
    }
  };

  return (
    <>
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <Card
          size="small"
          title={t('topology.title.islands', { n: islands.length })}
          extra={
            <Space>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {t('topology.hint.methods')}
              </Typography.Text>
              <Button type="primary" icon={<PlusOutlined />} {...ro} onClick={() => setAddIsland(true)}>
                {t('topology.action.addIsland')}
              </Button>
            </Space>
          }
        >
          {islands.length === 0 ? <Empty description={t('topology.empty')} /> : (
            <Table<Island>
              rowKey="id" size="small" loading={topo.isLoading}
              dataSource={islands}
              pagination={false}
              expandable={{
                expandedRowRender: (row) => (
                  <Space direction="vertical" size={8} style={{ width: '100%' }}>
                    <Button size="small" icon={<PlusOutlined />} {...ro}
                      onClick={() => { setAddDevice(row); deviceForm.resetFields(); }}>
                      {t('topology.hint.addDevice')}
                    </Button>
                    <Table<Dev>
                      rowKey="resourceId" size="small" pagination={false}
                      dataSource={row.devices}
                      onRow={(d) => ({ onClick: () => setPicked(d), style: { cursor: 'pointer' } })}
                      rowClassName={(d) => (d.resourceId === picked?.resourceId ? 'ant-table-row-selected' : '')}
                      columns={[
                        { title: t('topology.col.device'), dataIndex: 'code', width: 120 },
                        { title: t('topology.col.name'), dataIndex: 'name' },
                        { title: t('topology.col.model'), dataIndex: 'model', width: 150 },
                        {
                          title: t('topology.col.capability'), width: 140,
                          render: (_, d) => (d.capability ? <Tag color="blue">{d.capability}</Tag> : '—'),
                        },
                        {
                          title: t('topology.col.action'), width: 120,
                          render: (_, d) => (
                            <Space size={2}>
                              <Button size="small" type="link" {...ro} onClick={(e) => {
                                e.stopPropagation();
                                setEditing(d);
                                editForm.setFieldsValue({ name: d.name });
                              }}>{t('topology.action.edit')}</Button>
                              <Button size="small" type="link" danger {...ro} onClick={async (e) => {
                                e.stopPropagation();
                                if (!confirm(t('topology.confirm.deleteDevice', { code: d.code }))) return;
                                try {
                                  const r = await apiFetch(`/api/devices/${d.resourceId}`,
                                    { method: 'DELETE', headers: { Authorization: `Bearer ${TOKEN()}` } });
                                  if (!r.ok) throw new Error((await r.json()).error);
                                  message.success(t('topology.toast.deleted'));
                                  await qc.invalidateQueries({ queryKey: ['topology'] });
                                } catch (err) {
                                  message.error(String((err as Error).message));
                                }
                              }}>{t('topology.action.delete')}</Button>
                            </Space>
                          ),
                        },
                      ]}
                    />
                  </Space>
                ),
              }}
              columns={[
                { title: t('topology.col.island'), dataIndex: 'code', width: 140 },
                { title: t('topology.col.name'), dataIndex: 'name' },
                {
                  title: t('topology.col.deviceCount'), dataIndex: 'deviceCount', width: 100,
                  render: (v: number) => <Tag>{v}</Tag>,
                },
              ]}
            />
          )}
        </Card>

        {picked && (
          <Card size="small" title={t('topology.card.methods', { code: picked.code })}>
            <Table size="small" loading={methods.isLoading}
              dataSource={methods.data?.items ?? []}
              rowKey={(r: { method: string }) => r.method}
              pagination={{ pageSize: 10, size: 'small' }}
              columns={[
                { title: t('topology.col.method'), dataIndex: 'method', width: 240 },
                { title: t('topology.col.name'), dataIndex: 'name' },
                {
                  title: t('topology.col.capability'), dataIndex: 'capability', width: 140,
                  render: (v: string) => (v ? <Tag color="blue">{v}</Tag> : '—'),
                },
                { title: t('topology.col.estimateSec'), dataIndex: 'estimateSec', width: 100 },
              ]}
            />
          </Card>
        )}
      </Space>

      <Modal open={addIsland} title={t('topology.modal.addIslandTitle')} onCancel={() => setAddIsland(false)}
        onOk={submitIsland} okText={t('topology.modal.create')}>
        <Form form={islandForm} layout="vertical">
          <Form.Item name="code" label={t('topology.field.islandCode')} rules={[{ required: true }]}
            extra={t('topology.field.islandCodeExtra')}>
            <Input placeholder="LAB-A" />
          </Form.Item>
          <Form.Item name="name" label={t('topology.field.islandName')} rules={[{ required: true }]}>
            <Input placeholder={t('topology.placeholder.islandName')} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal open={addDevice != null} width={560}
        title={t('topology.modal.addDeviceTitle', { name: addDevice?.name ?? '' })}
        onCancel={() => setAddDevice(null)} onOk={submitDevice} okText={t('topology.modal.create')}>
        <Form form={deviceForm} layout="vertical">
          <Space size={12} style={{ width: '100%' }}>
            <Form.Item name="code" label={t('topology.field.deviceCode')} rules={[{ required: true }]} style={{ width: 220 }}>
              <Input placeholder="X173" />
            </Form.Item>
            <Form.Item name="name" label={t('topology.field.deviceName')} rules={[{ required: true }]} style={{ width: 260 }}>
              <Input placeholder="Incubator_2C-1" />
            </Form.Item>
          </Space>

          <Space size={12} style={{ width: '100%' }}>
            <Form.Item name="capability" label={t('topology.field.capability')} style={{ width: 220 }}
              extra={t('topology.field.capabilityExtra')}>
              <Select showSearch allowClear placeholder={t('topology.placeholder.pickCapability')}
                optionFilterProp="label"
                options={(caps.data?.items ?? []).map((c) => ({
                  value: c.code, label: `${c.code}（${c.name}）`,
                }))} />
            </Form.Item>
            <Form.Item name="deviceType" label={t('topology.field.deviceType')} style={{ width: 260 }}>
              <Input placeholder="Incubators" />
            </Form.Item>
          </Space>

          <Form.Item name="ip" label={t('topology.field.ip')}
            extra={t('topology.field.ipExtra')}>
            <Input placeholder="192.0.2.10" />
          </Form.Item>

          <Form.Item name="driverId" label={t('topology.field.driverId')}
            extra={t('topology.field.driverIdExtra')}>
            <Select allowClear placeholder={t('topology.placeholder.defaultDriver')}
              options={(drivers.data?.items ?? []).map((d) => ({
                value: d.id, label: `${d.code}  ${d.endpoint}`,
              }))} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal open={editing != null} title={t('topology.modal.editDeviceTitle', { code: editing?.code ?? '' })}
        onCancel={() => setEditing(null)}
        onOk={async () => {
          if (!editing) return;
          const v = await editForm.validateFields();
          try {
            const r = await apiFetch(`/api/devices/${editing.resourceId}`, {
              method: 'PUT',
              headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${TOKEN()}` },
              body: JSON.stringify(v),
            });
            if (!r.ok) throw new Error((await r.json()).error);
            message.success(t('topology.toast.saved'));
            setEditing(null);
            await qc.invalidateQueries({ queryKey: ['topology'] });
          } catch (e) {
            message.error(String((e as Error).message));
          }
        }} okText={t('topology.modal.save')}>
        <Form form={editForm} layout="vertical">
          <Form.Item name="name" label={t('topology.field.deviceName')}>
            <Input />
          </Form.Item>
          <Form.Item name="ip" label={t('topology.field.ip')}
            extra={t('topology.field.ipExtraShort')}>
            <Input placeholder="192.0.2.10" />
          </Form.Item>
          <Form.Item name="driverId" label={t('topology.field.driverId')}
            extra={t('topology.field.driverIdExtraShort')}>
            <Select allowClear placeholder={t('topology.placeholder.unchanged')}
              options={(drivers.data?.items ?? []).map((d) => ({
                value: d.id, label: `${d.code}  ${d.endpoint}`,
              }))} />
          </Form.Item>
            <Form.Item name="capability" label={t('topology.field.capabilityEdit')}>
            <Select showSearch allowClear placeholder={t('topology.placeholder.unchanged')} optionFilterProp="label"
              options={(caps.data?.items ?? []).map((c) => ({
                value: c.code, label: `${c.code}（${c.name}）`,
              }))} />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
