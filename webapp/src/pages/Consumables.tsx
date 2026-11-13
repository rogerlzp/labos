import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Button, Card, Empty, Form, Input, InputNumber, Modal, Popconfirm, Select, Space,
  Table, Tabs, Tag, Typography, message,
} from 'antd';
import { apiFetch } from '../api/client';

type Kind = 'consumable' | 'reagent';

interface Catalog {
  id: number; code: string; name: string; category?: string | null;
  specification?: string | null; unit: string; maxStack?: number | null;
  lotCount?: number; totalRemaining?: number;
}
interface Lot {
  id: number; consumableId?: number; reagentId?: number;
  lotNo: string; barcode?: string | null; quantity?: number; volume?: number; remaining: number;
  locationId?: number | null; deviceId?: number | null;
  deviceCode?: string | null; positionCode?: string | null;
  expireAt?: string | null; status: string;
  consumableCode?: string; consumableName?: string; reagentCode?: string; reagentName?: string;
}
interface Position { resourceId: number; code: string; parentDeviceCode: string; }
interface Island { id: number; code: string; name: string; }

const LOT_STATUS: Record<string, { zh: string; color: string }> = {
  available: { zh: '可用', color: 'green' },
  reserved: { zh: '预留', color: 'blue' },
  used: { zh: '已用', color: 'default' },
  expired: { zh: '过期', color: 'red' },
  discarded: { zh: '废弃', color: 'default' },
};

export default function ConsumablesPage() {
  const qc = useQueryClient();
  const [kind, setKind] = useState<Kind>('consumable');
  const [islandId, setIslandId] = useState<number | undefined>();
  const [catalogForm] = Form.useForm();
  const [lotForm] = Form.useForm();
  const [editingCatalog, setEditingCatalog] = useState<Catalog | null>(null);
  const [editingLot, setEditingLot] = useState<Lot | null>(null);

  const isConsumable = kind === 'consumable';

  const topologyQ = useQuery({
    queryKey: ['topology'],
    queryFn: () => apiFetch('/api/topology').then((r) => r.json()) as Promise<{ islands: Island[] }>,
  });
  const islands = topologyQ.data?.islands ?? [];

  const positionsQ = useQuery({
    queryKey: ['positions-for-lot', islandId],
    queryFn: () => apiFetch(`/api/positions?islandId=${islandId}`).then((r) => r.json()) as Promise<{ items: Position[] }>,
    enabled: islandId != null,
  });
  const positionOptions = (positionsQ.data?.items ?? []).map((p) => ({
    value: p.resourceId, label: `${p.parentDeviceCode} · ${p.code}`,
  }));

  const catalogQ = useQuery({
    queryKey: [kind, islandId],
    queryFn: () => apiFetch(`/api/${kind}s${islandId ? `?islandId=${islandId}` : ''}`)
      .then((r) => r.json()) as Promise<{ total: number; items: Catalog[] }>,
    refetchInterval: 8000,
  });
  const catalog = catalogQ.data?.items ?? [];

  const lotsQ = useQuery({
    queryKey: [`${kind}-lots`, islandId],
    queryFn: () => apiFetch(`/api/${kind}-lots${islandId ? `?islandId=${islandId}` : ''}`)
      .then((r) => r.json()) as Promise<{ total: number; items: Lot[] }>,
    refetchInterval: 8000,
  });
  const lots = lotsQ.data?.items ?? [];

  const invalidate = () => {
    qc.invalidateQueries({ queryKey: [kind] });
    qc.invalidateQueries({ queryKey: [`${kind}-lots`] });
    qc.invalidateQueries({ queryKey: ['positions'] });
  };

  const openNewCatalog = () => {
    setEditingCatalog(null);
    catalogForm.resetFields();
    catalogForm.setFieldsValue({ unit: isConsumable ? 'pcs' : 'uL' });
  };
  const openEditCatalog = (c: Catalog) => {
    setEditingCatalog(c);
    catalogForm.setFieldsValue(c);
  };
  const saveCatalog = async () => {
    const v = await catalogForm.validateFields();
    const url = editingCatalog ? `/api/${kind}s/${editingCatalog.id}` : `/api/${kind}s`;
    const method = editingCatalog ? 'PUT' : 'POST';
    try {
      await apiFetch(url, { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(v) });
      message.success('已保存');
      setEditingCatalog(null);
      invalidate();
    } catch (e) { message.error(`保存失败：${(e as Error).message}`); }
  };
  const removeCatalog = async (id: number) => {
    try {
      await apiFetch(`/api/${kind}s/${id}`, { method: 'DELETE' });
      message.success('已删除');
      invalidate();
    } catch (e) { message.error(`删除失败：${(e as Error).message}`); }
  };

  const openNewLot = () => {
    setEditingLot(null);
    lotForm.resetFields();
    lotForm.setFieldsValue({ status: 'available' });
  };
  const openEditLot = (l: Lot) => {
    setEditingLot(l);
    lotForm.setFieldsValue({
      consumableId: l.consumableId,
      reagentId: l.reagentId,
      lotNo: l.lotNo, barcode: l.barcode,
      quantity: l.quantity, volume: l.volume, remaining: l.remaining,
      locationId: l.locationId ?? undefined,
      expireAt: l.expireAt?.slice(0, 10), status: l.status,
    });
  };
  const saveLot = async () => {
    const v = await lotForm.validateFields();
    const url = editingLot ? `/api/${kind}-lots/${editingLot.id}` : `/api/${kind}-lots`;
    const method = editingLot ? 'PUT' : 'POST';
    try {
      await apiFetch(url, { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(v) });
      message.success('已保存');
      setEditingLot(null);
      invalidate();
    } catch (e) { message.error(`保存失败：${(e as Error).message}`); }
  };
  const removeLot = async (id: number) => {
    try {
      await apiFetch(`/api/${kind}-lots/${id}`, { method: 'DELETE' });
      message.success('已删除');
      invalidate();
    } catch (e) { message.error(`删除失败：${(e as Error).message}`); }
  };

  const catalogName = (l: Lot) => (isConsumable ? l.consumableName : l.reagentName)
    ?? (isConsumable ? l.consumableCode : l.reagentCode) ?? '';

  const catalogColumns = [
    { title: '编号', dataIndex: 'code', width: 100, render: (v: string) => <Typography.Text code>{v}</Typography.Text> },
    { title: '名称', dataIndex: 'name', ellipsis: true },
    { title: '分类', dataIndex: 'category', width: 70 },
    { title: '单位', dataIndex: 'unit', width: 60 },
    { title: '批次', dataIndex: 'lotCount', width: 60, align: 'right' as const },
    { title: '剩余', dataIndex: 'totalRemaining', width: 80, align: 'right' as const,
      render: (v: number) => <span style={{ fontFamily: 'monospace' }}>{v ?? 0}</span> },
    { title: '', key: 'op', width: 100,
      render: (_: unknown, c: Catalog) => <Space size={0}>
        <Button size="small" type="link" onClick={() => openEditCatalog(c)}>编辑</Button>
        <Popconfirm title="删除？" onConfirm={() => removeCatalog(c.id)}>
          <Button size="small" type="link" danger>删除</Button>
        </Popconfirm>
      </Space> },
  ];

  const lotColumns = [
    { title: isConsumable ? '耗材' : '试剂', key: 'name', width: 150, ellipsis: true,
      render: (_: unknown, l: Lot) => catalogName(l) },
    { title: '批次', dataIndex: 'lotNo', width: 110, render: (v: string) => <Typography.Text code>{v}</Typography.Text> },
    { title: isConsumable ? '数量' : '体积', key: 'qty', width: 70, align: 'right' as const,
      render: (_: unknown, l: Lot) => (isConsumable ? l.quantity : l.volume) ?? '—' },
    { title: '剩余', dataIndex: 'remaining', width: 70, align: 'right' as const,
      render: (v: number) => <span style={{ fontFamily: 'monospace' }}>{v}</span> },
    { title: '所在设备', dataIndex: 'deviceCode', width: 120,
      render: (v: string | null) => v ? <Tag color="blue">{v}</Tag> : <Tag>未挂</Tag> },
    { title: '所在点位', dataIndex: 'positionCode', width: 130, render: (v: string | null) => v ?? '—' },
    { title: '效期', dataIndex: 'expireAt', width: 100,
      render: (v: string | null) => v ? v.slice(0, 10) : '—' },
    { title: '状态', dataIndex: 'status', width: 70,
      render: (v: string) => {
        const s = LOT_STATUS[v] ?? { zh: v, color: 'default' };
        return <Tag color={s.color}>{s.zh}</Tag>;
      } },
    { title: '', key: 'op', width: 100,
      render: (_: unknown, l: Lot) => <Space size={0}>
        <Button size="small" type="link" onClick={() => openEditLot(l)}>编辑</Button>
        <Popconfirm title="删除？" onConfirm={() => removeLot(l.id)}>
          <Button size="small" type="link" danger>删除</Button>
        </Popconfirm>
      </Space> },
  ];

  const catalogOptions = catalog.map((c) => ({ value: c.id, label: `${c.code} · ${c.name}` }));

  return (
    <div style={{ padding: 16 }}>
      <Card size="small" title="物料管理"
        extra={<Space>
          <Select size="small" allowClear placeholder="按岛筛（可选）" style={{ width: 200 }}
            value={islandId} onChange={setIslandId}
            options={islands.map((i) => ({ value: i.id, label: `${i.name}（${i.code}）` }))} />
          <Button size="small" onClick={invalidate}>刷新</Button>
        </Space>}>
        <Tabs activeKey={kind} onChange={(k) => setKind(k as Kind)}
          items={[
            { key: 'consumable', label: '耗材' },
            { key: 'reagent', label: '试剂' },
          ]} />
        <Table<Catalog> size="small" rowKey="id" dataSource={catalog}
          style={{ marginTop: 8 }} pagination={{ pageSize: 8, showSizeChanger: false }}
          title={() => <Space><Typography.Text strong>{isConsumable ? '耗材目录' : '试剂目录'}</Typography.Text>
            <Button size="small" type="primary" onClick={openNewCatalog}>
              {isConsumable ? '新增耗材' : '新增试剂'}</Button></Space>}
          columns={catalogColumns} />
        <Table<Lot> size="small" rowKey="id" dataSource={lots}
          style={{ marginTop: 16 }} pagination={{ pageSize: 8, showSizeChanger: false }}
          title={() => <Space><Typography.Text strong>库存批次</Typography.Text>
            <Button size="small" type="primary" onClick={openNewLot}>新增批次</Button></Space>}
          columns={lotColumns} />
        {catalog.length === 0 && lots.length === 0 && <Empty description="暂无数据" />}
      </Card>

      <Modal title={editingCatalog ? (isConsumable ? '编辑耗材' : '编辑试剂') : (isConsumable ? '新增耗材' : '新增试剂')}
        open={editingCatalog !== null} onCancel={() => setEditingCatalog(null)}
        onOk={saveCatalog} okText="保存" cancelText="取消">
        <Form form={catalogForm} layout="vertical">
          <Form.Item name="code" label="编号" rules={[{ required: true }]}>
            <Input disabled={editingCatalog != null} />
          </Form.Item>
          <Form.Item name="name" label="名称" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="category" label="分类"><Input /></Form.Item>
          <Form.Item name="specification" label="规格"><Input /></Form.Item>
          <Form.Item name="unit" label="单位"><Input /></Form.Item>
          {isConsumable && <Form.Item name="maxStack" label="最大堆叠"><InputNumber min={0} /></Form.Item>}
        </Form>
      </Modal>

      <Modal title={editingLot ? '编辑批次' : '新增批次'} open={editingLot !== null}
        onCancel={() => setEditingLot(null)} onOk={saveLot} okText="保存" cancelText="取消">
        <Form form={lotForm} layout="vertical">
          <Form.Item name={isConsumable ? 'consumableId' : 'reagentId'} label={isConsumable ? '耗材' : '试剂'}
            rules={[{ required: true }]}>
            <Select showSearch optionFilterProp="label" options={catalogOptions} />
          </Form.Item>
          <Form.Item name="lotNo" label="批次号" rules={[{ required: true }]}><Input /></Form.Item>
          {isConsumable && <Form.Item name="barcode" label="条码"><Input /></Form.Item>}
          <Form.Item name={isConsumable ? 'quantity' : 'volume'} label={isConsumable ? '数量' : '体积(µL)'}
            rules={[{ required: true }]}>
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="remaining" label="剩余" rules={[{ required: true }]}>
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="locationId" label="所在点位（挂到设备点位）">
            <Select allowClear showSearch optionFilterProp="label"
              placeholder="选一个点位（设备自动派生）" options={positionOptions} />
          </Form.Item>
          <Form.Item name="expireAt" label="效期"><Input placeholder="2027-01-01" /></Form.Item>
          <Form.Item name="status" label="状态">
            <Select options={Object.entries(LOT_STATUS).map(([k, v]) => ({ value: k, label: v.zh }))} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
