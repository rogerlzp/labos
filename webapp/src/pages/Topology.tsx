import { apiFetch } from '../api/client';
import { useReadOnly } from '../auth';
import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Button, Card, Checkbox, Empty, Form, Input, Modal, Select, Space, Table, Tag, Typography, message,
} from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useT, type MessageKey } from '../i18n';

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
  name?: string;
  protocol?: string;
  endpoint: string;
  status?: number;
}

interface Cap {
  code: string;
  name: string;
  devices: number;
}

/**
 * 驱动协议：内核枚举 driver_protocol（grpc/http/tcp/sila2/modbus/opcua/netdll/mock）。
 *
 * <p>后端是 CAST(... AS driver_protocol)，写错会直接报错 ⇒ 这里只能从下拉里选。
 */
const DRIVER_PROTOCOLS: { value: string; labelKey: MessageKey }[] = [
  { value: 'grpc', labelKey: 'topology.driver.protocol.grpc' },
  { value: 'http', labelKey: 'topology.driver.protocol.http' },
  { value: 'tcp', labelKey: 'topology.driver.protocol.tcp' },
  { value: 'sila2', labelKey: 'topology.driver.protocol.sila2' },
  { value: 'modbus', labelKey: 'topology.driver.protocol.modbus' },
  { value: 'opcua', labelKey: 'topology.driver.protocol.opcua' },
  { value: 'netdll', labelKey: 'topology.driver.protocol.netdll' },
  { value: 'mock', labelKey: 'topology.driver.protocol.mock' },
];

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
  const [addDriver, setAddDriver] = useState(false);
  const [editDriver, setEditDriver] = useState<Driver | null>(null);
  const [editForm] = Form.useForm();
  const [islandForm] = Form.useForm();
  const [deviceForm] = Form.useForm();
  const [driverForm] = Form.useForm();
  const [driverEditForm] = Form.useForm();

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

  const driverList = drivers.data?.items ?? [];

  /** 协议下拉/表格共用同一份枚举文案。 */
  const protocolLabel = (p?: string) => {
    const hit = DRIVER_PROTOCOLS.find((x) => x.value === p);
    return hit ? t(hit.labelKey) : (p ?? '—');
  };

  /** 登记驱动：后端 400（编号重复 / 缺字段）会把原因放在 error 字段里，直接透给用户。 */
  const submitDriver = async () => {
    const v = await driverForm.validateFields();
    try {
      await apiFetch('/api/drivers', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${TOKEN()}` },
        body: JSON.stringify({ ...v, resident: !!v.resident }),
      });
      message.success(t('topology.driver.toast.created'));
      setAddDriver(false);
      driverForm.resetFields();
      await qc.invalidateQueries({ queryKey: ['drivers'] });
    } catch (e) {
      message.error(String((e as Error).message));
    }
  };

  /** 改驱动：编号不可改（后端 PUT 不收 code）；空字段 = 不修改。 */
  const submitDriverEdit = async () => {
    if (!editDriver) return;
    const v = await driverEditForm.validateFields();
    try {
      await apiFetch(`/api/drivers/${editDriver.id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${TOKEN()}` },
        body: JSON.stringify({ ...v, resident: !!v.resident }),
      });
      message.success(t('topology.driver.toast.saved'));
      setEditDriver(null);
      await qc.invalidateQueries({ queryKey: ['drivers'] });
    } catch (e) {
      message.error(String((e as Error).message));
    }
  };

  /** 删驱动：还被设备实例引用时后端回 400 并说明占用台数，透给用户。 */
  const deleteDriver = async (d: Driver) => {
    if (!confirm(t('topology.driver.confirm.delete', { code: d.code }))) return;
    try {
      await apiFetch(`/api/drivers/${d.id}`, {
        method: 'DELETE',
        headers: { Authorization: `Bearer ${TOKEN()}` },
      });
      message.success(t('topology.driver.toast.deleted'));
      await qc.invalidateQueries({ queryKey: ['drivers'] });
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

        <Card
          size="small"
          title={t('topology.driver.title', { n: driverList.length })}
          extra={
            <Space>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {t('topology.driver.hint')}
              </Typography.Text>
              <Button type="primary" icon={<PlusOutlined />} {...ro}
                onClick={() => { setAddDriver(true); driverForm.resetFields(); }}>
                {t('topology.driver.action.add')}
              </Button>
            </Space>
          }
        >
          {driverList.length === 0 ? <Empty description={t('topology.driver.empty')} /> : (
            <Table<Driver>
              rowKey="id" size="small" loading={drivers.isLoading}
              dataSource={driverList}
              pagination={false}
              columns={[
                {
                  title: t('topology.driver.col.code'), dataIndex: 'code', width: 200,
                  render: (v: string) => <Typography.Text code>{v}</Typography.Text>,
                },
                { title: t('topology.driver.col.name'), dataIndex: 'name' },
                {
                  title: t('topology.driver.col.protocol'), dataIndex: 'protocol', width: 220,
                  render: (v: string) => (v ? <Tag color="blue">{protocolLabel(v)}</Tag> : '—'),
                },
                {
                  title: t('topology.driver.col.endpoint'), dataIndex: 'endpoint',
                  render: (v: string) => <Typography.Text code>{v}</Typography.Text>,
                },
                {
                  title: t('topology.driver.col.action'), width: 120,
                  render: (_, d) => (
                    <Space size={2}>
                      <Button size="small" type="link" {...ro} onClick={() => {
                        setEditDriver(d);
                        driverEditForm.setFieldsValue({
                          name: d.name ?? '',
                          protocol: d.protocol ?? 'tcp',
                          endpoint: d.endpoint ?? '',
                          // 列表接口不返回厂商/常驻 ⇒ 留空 = 不修改
                          vendor: '',
                          resident: false,
                        });
                      }}>{t('topology.action.edit')}</Button>
                      <Button size="small" type="link" danger {...ro}
                        onClick={() => deleteDriver(d)}>{t('topology.action.delete')}</Button>
                    </Space>
                  ),
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

      <Modal open={addDriver} width={560} title={t('topology.driver.modal.addTitle')}
        onCancel={() => setAddDriver(false)} onOk={submitDriver} okText={t('topology.modal.create')}>
        <Form form={driverForm} layout="vertical">
          <Form.Item name="code" label={t('topology.driver.field.code')} rules={[{ required: true }]}
            extra={t('topology.driver.field.codeExtra')}>
            <Input placeholder="DRV-SIDECAR-EXT1" />
          </Form.Item>
          <Form.Item name="name" label={t('topology.driver.field.name')} rules={[{ required: true }]}>
            <Input placeholder={t('topology.driver.placeholder.name')} />
          </Form.Item>
          <Form.Item name="protocol" label={t('topology.driver.field.protocol')} rules={[{ required: true }]}
            extra={t('topology.driver.field.protocolExtra')}>
            <Select placeholder={t('topology.driver.placeholder.pickProtocol')}
              options={DRIVER_PROTOCOLS.map((p) => ({ value: p.value, label: t(p.labelKey) }))} />
          </Form.Item>
          <Form.Item name="endpoint" label={t('topology.driver.field.endpoint')} rules={[{ required: true }]}
            extra={t('topology.driver.field.endpointExtra')}>
            <Input placeholder="tcp://192.168.1.10:8501" />
          </Form.Item>
          <Form.Item name="vendor" label={t('topology.driver.field.vendor')}>
            <Input placeholder={t('topology.driver.placeholder.vendor')} />
          </Form.Item>
          <Form.Item name="resident" label={t('topology.driver.field.resident')}
            valuePropName="checked" extra={t('topology.driver.field.residentExtra')}>
            <Checkbox />
          </Form.Item>
        </Form>
      </Modal>

      <Modal open={editDriver != null} width={560}
        title={t('topology.driver.modal.editTitle', { code: editDriver?.code ?? '' })}
        onCancel={() => setEditDriver(null)} onOk={submitDriverEdit} okText={t('topology.modal.save')}>
        <Form form={driverEditForm} layout="vertical">
          <Form.Item name="name" label={t('topology.driver.field.name')} rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="protocol" label={t('topology.driver.field.protocol')} rules={[{ required: true }]}
            extra={t('topology.driver.field.protocolExtra')}>
            <Select options={DRIVER_PROTOCOLS.map((p) => ({ value: p.value, label: t(p.labelKey) }))} />
          </Form.Item>
          <Form.Item name="endpoint" label={t('topology.driver.field.endpoint')} rules={[{ required: true }]}
            extra={t('topology.driver.field.endpointExtra')}>
            <Input placeholder="tcp://192.168.1.10:8501" />
          </Form.Item>
          <Form.Item name="vendor" label={t('topology.driver.field.vendor')}
            extra={t('topology.driver.field.vendorEditExtra')}>
            <Input placeholder={t('topology.driver.placeholder.vendor')} />
          </Form.Item>
          <Form.Item name="resident" label={t('topology.driver.field.resident')}
            valuePropName="checked" extra={t('topology.driver.field.residentEditExtra')}>
            <Checkbox />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
