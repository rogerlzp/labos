import {
  api, apiFetch, type DevicePool, type DevicePoolMember,
} from '../api/client';
import { useReadOnly } from '../auth';
import { useMemo, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert, Button, Card, Checkbox, Descriptions, Empty, Form, Input, InputNumber, Modal, Select, Space,
  Table, Tag, Typography, message,
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
  /** 设备示意图（/devices/xxx.svg）；空 = 前端退回能力色块。 */
  icon?: string;
  /**
   * 型号协议属性（后端 `device.attr::text` 原样透出）。
   *
   * <p>内含 {@code device_model} / {@code protocol}（帧格式、波特率、校验算法）/
   * {@code manual}（手册路径）/ {@code evidence}（MANUAL|SPEC|PLANNED）/
   * {@code gaps}（手册没写、必须现场确认的点）。**权威值在数据库**，
   * 这里只读展示 —— 写入走 `PUT /api/devices/{id}/attr`（白名单 6 键）。
   */
  deviceAttr?: string;
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

/**
 * 池类型：operation = 操作池（同型号，运行时挑一台做）/ storage = 存储池。
 *
 * <p>后端是白名单校验（POOL_KINDS），写错会直接 400 ⇒ 只能从下拉里选。
 */
const POOL_KINDS: { value: DevicePool['poolKind']; labelKey: MessageKey }[] = [
  { value: 'operation', labelKey: 'topology.pool.kind.operation' },
  { value: 'storage', labelKey: 'topology.pool.kind.storage' },
];

/** 选位策略：后端枚举，值必须原样传（写错 400）。 */
const POOL_STRATEGIES: { value: DevicePool['strategy']; labelKey: MessageKey }[] = [
  { value: 'first_available', labelKey: 'topology.pool.strategy.first_available' },
  { value: 'least_recently_used', labelKey: 'topology.pool.strategy.least_recently_used' },
  { value: 'round_robin', labelKey: 'topology.pool.strategy.round_robin' },
  { value: 'priority_commit', labelKey: 'topology.pool.strategy.priority_commit' },
];

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

  // ---- 设备池 ----
  const [poolIsland, setPoolIsland] = useState<number | undefined>(undefined);
  const [poolKindFilter, setPoolKindFilter] = useState<DevicePool['poolKind'] | undefined>(undefined);
  const [addPoolOpen, setAddPoolOpen] = useState(false);
  const [editPoolRow, setEditPoolRow] = useState<DevicePool | null>(null);
  const [detailPoolId, setDetailPoolId] = useState<number | null>(null);
  const [addMemberOpen, setAddMemberOpen] = useState(false);
  const [poolForm] = Form.useForm();
  const [poolEditForm] = Form.useForm();
  const [memberForm] = Form.useForm();

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

  const pools = useQuery({
    queryKey: ['pools', poolIsland ?? 'all', poolKindFilter ?? 'all'],
    queryFn: () => api.pools({ islandId: poolIsland, kind: poolKindFilter }),
  });

  const poolDetail = useQuery({
    queryKey: ['pool', detailPoolId],
    queryFn: () => api.pool(detailPoolId!),
    enabled: detailPoolId != null,
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

  // ---------------------------------------------------------------- 设备池

  /**
   * 可入池的设备实例。
   *
   * <p>池成员引用的是 **device_instance.id**，不是 resource_node.id ——
   * 拓扑里 devices[].instanceId 才是它；没有实例的设备（软删/未建实例）不能入池。
   */
  const instances = useMemo(() => {
    const out: { instanceId: number; code: string; name: string; model?: string }[] = [];
    for (const isl of topo.data?.islands ?? []) {
      for (const d of isl.devices) {
        if (d.instanceId == null) continue;
        out.push({ instanceId: d.instanceId, code: d.code, name: d.name, model: d.model });
      }
    }
    return out;
  }, [topo.data?.islands]);

  /** 型号：拓扑的 devices[].model（型号字符串）；查不到就没有，不瞎猜。 */
  const modelOf = (instanceId: number): string | undefined =>
    instances.find((x) => x.instanceId === instanceId)?.model;

  /**
   * operation 池的成员必须同型号 —— 后端会回 409，这里提前拦。
   *
   * <p>依据：拓扑 devices[].model。型号为空/查不到的**不参与判断**（不做推断）。
   * 返回不一致的型号列表（去重）；一致或无从判断时返回 null。
   */
  const modelMismatch = (instanceIds: number[]): string[] | null => {
    const models = Array.from(new Set(
      instanceIds
        .map((id) => modelOf(id))
        .filter((m): m is string => !!m && m.trim() !== ''),
    ));
    return models.length > 1 ? models : null;
  };

  /** 建池表单里已选的成员（用于同型号预校验）。 */
  const draftMembers = Form.useWatch('members', poolForm) as
    { deviceInstanceId?: number }[] | undefined;
  const draftInstanceIds = (draftMembers ?? [])
    .map((m) => m?.deviceInstanceId)
    .filter((x): x is number => typeof x === 'number');
  const draftKind = Form.useWatch('poolKind', poolForm);
  const draftMismatch = draftKind === 'operation' ? modelMismatch(draftInstanceIds) : null;

  /** 加成员表单里选中的实例（用于同型号预校验）。 */
  const pickedInstanceId = Form.useWatch('deviceInstanceId', memberForm) as number | undefined;
  const memberMismatch = (() => {
    const pool = poolDetail.data;
    if (!pool || pool.poolKind !== 'operation' || pickedInstanceId == null) return null;
    const existing = (pool.members ?? []).map((m) => m.deviceInstanceId);
    return modelMismatch([...existing, pickedInstanceId]);
  })();

  /** 建池：后端 400（编号重复 / 缺字段）会把原因放在 error 字段里，直接透给用户。 */
  const submitPool = async () => {
    let v: {
      code?: string; name?: string; islandId?: number;
      poolKind?: DevicePool['poolKind']; strategy?: DevicePool['strategy'];
      skipOnError?: boolean; skipOnOffline?: boolean; allowIterationOnError?: boolean;
      minNestCount?: number;
      members?: { deviceInstanceId?: number; subGroup?: string; priority?: number }[];
    };
    try {
      v = await poolForm.validateFields();
    } catch {
      // 字段没过校验：antd 已在字段下方标红，这里不重复弹窗
      return;
    }
    const rows = (v.members ?? []).filter((m) => typeof m?.deviceInstanceId === 'number');
    if (rows.length !== (v.members ?? []).length) {
      message.error(t('topology.pool.warn.memberMissing'));
      return;
    }
    const memberIds = rows.map((m) => m.deviceInstanceId as number);
    if (new Set(memberIds).size !== memberIds.length) {
      message.error(t('topology.pool.warn.duplicateMember'));
      return;
    }
    if (v.poolKind === 'operation') {
      const bad = modelMismatch(memberIds);
      if (bad) {
        message.error(t('topology.pool.warn.modelMismatch', { models: bad.join(' / ') }));
        return;
      }
    }
    try {
      await api.createPool({
        code: v.code ?? '',
        name: v.name ?? '',
        islandId: v.islandId ?? 0,
        poolKind: v.poolKind ?? 'operation',
        strategy: v.strategy ?? 'first_available',
        skipOnError: !!v.skipOnError,
        skipOnOffline: !!v.skipOnOffline,
        allowIterationOnError: !!v.allowIterationOnError,
        minNestCount: v.minNestCount ?? 0,
        members: rows.map((m) => ({
          deviceInstanceId: m.deviceInstanceId as number,
          subGroup: m.subGroup?.trim() ? m.subGroup.trim() : null,
          priority: typeof m.priority === 'number' ? m.priority : 100,
        })),
      });
      message.success(t('topology.pool.toast.created'));
      setAddPoolOpen(false);
      poolForm.resetFields();
      await qc.invalidateQueries({ queryKey: ['pools'] });
    } catch (e) {
      message.error(String((e as Error).message));
    }
  };

  /** 改池：编号/类型不可改（后端 PUT 不收）；容错开关必须全传（后端缺省=true）。 */
  const submitPoolEdit = async () => {
    if (!editPoolRow) return;
    let v: {
      name?: string; strategy?: DevicePool['strategy'];
      skipOnError?: boolean; skipOnOffline?: boolean; allowIterationOnError?: boolean; enabled?: boolean;
    };
    try {
      v = await poolEditForm.validateFields();
    } catch {
      return;
    }
    try {
      await api.updatePool(editPoolRow.id, {
        name: v.name ?? '',
        strategy: v.strategy ?? 'first_available',
        skipOnError: !!v.skipOnError,
        skipOnOffline: !!v.skipOnOffline,
        allowIterationOnError: !!v.allowIterationOnError,
        enabled: !!v.enabled,
      });
      message.success(t('topology.pool.toast.saved'));
      setEditPoolRow(null);
      await qc.invalidateQueries({ queryKey: ['pools'] });
    } catch (e) {
      message.error(String((e as Error).message));
    }
  };

  /** 删池：被 node_binding 引用时后端回 409，原文透给用户。 */
  const deletePool = async (p: DevicePool) => {
    if (!confirm(t('topology.pool.confirm.delete', { code: p.code }))) return;
    try {
      await api.deletePool(p.id);
      message.success(t('topology.pool.toast.deleted'));
      await qc.invalidateQueries({ queryKey: ['pools'] });
    } catch (e) {
      message.error(String((e as Error).message));
    }
  };

  /** 加成员：operation 池先在前端校验同型号（后端还会再校验一次）。 */
  const submitAddMember = async () => {
    const pool = poolDetail.data;
    if (!pool) return;
    let v: { deviceInstanceId?: number; subGroup?: string; priority?: number };
    try {
      v = await memberForm.validateFields();
    } catch {
      return;
    }
    const di = v.deviceInstanceId;
    if (typeof di !== 'number') return;
    if (pool.poolKind === 'operation') {
      const existing = (pool.members ?? []).map((m) => m.deviceInstanceId);
      const bad = modelMismatch([...existing, di]);
      if (bad) {
        message.error(t('topology.pool.warn.modelMismatch', { models: bad.join(' / ') }));
        return;
      }
    }
    try {
      await api.addPoolMember(pool.id, {
        deviceInstanceId: di,
        subGroup: v.subGroup?.trim() ? v.subGroup.trim() : null,
        priority: typeof v.priority === 'number' ? v.priority : 100,
      });
      message.success(t('topology.pool.toast.memberAdded'));
      setAddMemberOpen(false);
      memberForm.resetFields();
      await qc.invalidateQueries({ queryKey: ['pool', pool.id] });
      await qc.invalidateQueries({ queryKey: ['pools'] });
    } catch (e) {
      message.error(String((e as Error).message));
    }
  };

  /** 移成员。 */
  const removeMember = async (m: DevicePoolMember) => {
    const pool = poolDetail.data;
    if (!pool) return;
    const code = m.deviceResourceCode ?? m.deviceCode ?? String(m.deviceInstanceId);
    if (!confirm(t('topology.pool.confirm.removeMember', { code, pool: pool.code }))) return;
    try {
      await api.removePoolMember(pool.id, m.deviceInstanceId);
      message.success(t('topology.pool.toast.memberRemoved'));
      await qc.invalidateQueries({ queryKey: ['pool', pool.id] });
      await qc.invalidateQueries({ queryKey: ['pools'] });
    } catch (e) {
      message.error(String((e as Error).message));
    }
  };

  /** 池类型/策略的下拉文案：后端枚举不认识的值原样显示，不吞掉。 */
  const poolKindLabel = (k?: string) => {
    const hit = POOL_KINDS.find((x) => x.value === k);
    return hit ? t(hit.labelKey) : (k ?? '—');
  };
  const poolStrategyLabel = (s?: string) => {
    const hit = POOL_STRATEGIES.find((x) => x.value === s);
    return hit ? t(hit.labelKey) : (s ?? '—');
  };

  const poolList = pools.data?.items ?? [];

  const islandFileRef = useRef<HTMLInputElement>(null);

  /** 整岛导出：岛 + 设备 + 点位 + 点位方法 + 示教（全部业务码，可跨环境）。 */
  const exportIsland = async (row: Island) => {
    try {
      const r = await apiFetch(`/api/islands/${row.id}/export`);
      if (!r.ok) {
        message.error(t('topology.io.error.export'));
        return;
      }
      const j = await r.json();
      const blob = new Blob([JSON.stringify(j, null, 2)], { type: 'application/json' });
      const a = document.createElement('a');
      a.href = URL.createObjectURL(blob);
      a.download = `island-${row.code}.json`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(a.href);
    } catch (e) {
      message.error(String((e as Error).message));
    }
  };

  /**
   * 整岛导入：目标环境缺的东西会新建，已存在的按 code 跳过（幂等）。
   *
   * <p>返回里 problems 非空 = 有对象没建成（整单 409），这时**不要**当成功提示。
   */
  const importIsland = async (file: File) => {
    let payload: unknown;
    try {
      payload = JSON.parse(await file.text());
    } catch {
      message.error(t('topology.io.error.json'));
      return;
    }
    try {
      const r = await apiFetch('/api/islands/import', {
        method: 'POST',
        body: JSON.stringify(payload),
      });
      const j = await r.json();
      if (!r.ok || (j.problems && j.problems.length)) {
        message.error((j.problems && j.problems[0]) || j.error || t('topology.io.error.import'), 8);
        return;
      }
      message.success(t('topology.io.toast.imported', {
        island: j.islandCode,
        dev: j.devicesCreated,
        points: j.pointsUpserted,
        methods: j.pointMethodsCreated,
        robot: j.robotPointsUpserted,
      }), 6);
      if (j.warnings && j.warnings.length) {
        message.warning(j.warnings.slice(0, 3).join('；'), 8);
      }
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
              <input
                ref={islandFileRef}
                type="file"
                accept=".json,application/json"
                data-testid="island-import-input"
                style={{ display: 'none' }}
                onChange={(e) => {
                  const f = e.target.files?.[0];
                  if (f) void importIsland(f);
                  e.target.value = '';
                }}
              />
              <Button size="small" {...ro} onClick={() => islandFileRef.current?.click()}>
                {t('topology.io.action.import')}
              </Button>
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
                {
                  title: '', key: 'io', width: 110, align: 'right' as const,
                  render: (_: unknown, row: Island) => (
                    <Button size="small" type="link" disabled={ro.disabled}
                      title={ro.title} onClick={() => exportIsland(row)}>
                      {t('topology.io.action.export')}
                    </Button>
                  ),
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

        <Card
          size="small"
          title={t('topology.pool.title', { n: poolList.length })}
          extra={
            <Space>
              <Select
                allowClear size="small" style={{ width: 170 }}
                placeholder={t('topology.pool.filter.island')}
                value={poolIsland}
                onChange={(v) => setPoolIsland(v)}
                options={islands.map((i) => ({ value: i.id, label: `${i.code}  ${i.name}` }))}
              />
              <Select
                allowClear size="small" style={{ width: 150 }}
                placeholder={t('topology.pool.filter.kind')}
                value={poolKindFilter}
                onChange={(v) => setPoolKindFilter(v)}
                options={POOL_KINDS.map((k) => ({ value: k.value, label: t(k.labelKey) }))}
              />
              <Button type="primary" icon={<PlusOutlined />} {...ro}
                onClick={() => { poolForm.resetFields(); setAddPoolOpen(true); }}>
                {t('topology.pool.action.add')}
              </Button>
            </Space>
          }
        >
          <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 8 }}>
            {t('topology.pool.hint')}
          </Typography.Text>
          {poolList.length === 0 ? <Empty description={t('topology.pool.empty')} /> : (
            <Table<DevicePool>
              rowKey="id" size="small" loading={pools.isLoading}
              dataSource={poolList}
              pagination={false}
              columns={[
                {
                  title: t('topology.pool.col.code'), dataIndex: 'code', width: 180,
                  render: (v: string) => <Typography.Text code>{v}</Typography.Text>,
                },
                { title: t('topology.pool.col.name'), dataIndex: 'name' },
                {
                  title: t('topology.pool.col.kind'), dataIndex: 'poolKind', width: 120,
                  render: (v: string) => (
                    <Tag color={v === 'operation' ? 'blue' : 'green'}>{poolKindLabel(v)}</Tag>
                  ),
                },
                {
                  title: t('topology.pool.col.strategy'), dataIndex: 'strategy', width: 140,
                  render: (v: string) => poolStrategyLabel(v),
                },
                {
                  title: t('topology.pool.col.memberCount'), dataIndex: 'memberCount', width: 90,
                  render: (v?: number) => <Tag>{v ?? 0}</Tag>,
                },
                {
                  title: t('topology.pool.col.enabled'), dataIndex: 'enabled', width: 90,
                  render: (v: boolean) => (v
                    ? <Tag color="green">{t('topology.pool.enabled.on')}</Tag>
                    : <Tag>{t('topology.pool.enabled.off')}</Tag>),
                },
                {
                  title: t('topology.pool.col.action'), width: 180,
                  render: (_, p) => (
                    <Space size={2}>
                      <Button size="small" type="link" {...ro}
                        onClick={() => setDetailPoolId(p.id)}>{t('topology.pool.action.members')}</Button>
                      <Button size="small" type="link" {...ro} onClick={() => {
                        setEditPoolRow(p);
                        poolEditForm.setFieldsValue({
                          name: p.name ?? '',
                          strategy: p.strategy ?? 'first_available',
                          skipOnError: !!p.skipOnError,
                          skipOnOffline: !!p.skipOnOffline,
                          allowIterationOnError: !!p.allowIterationOnError,
                          enabled: p.enabled !== false,
                        });
                      }}>{t('topology.pool.action.edit')}</Button>
                      <Button size="small" type="link" danger {...ro}
                        onClick={() => deletePool(p)}>{t('topology.pool.action.delete')}</Button>
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
        {/* 协议卡只读：帧格式/串口/证据等级/待确认清单都来自 device.attr（库里的配置） */}
        <ProtocolPanel attr={editing?.deviceAttr} />
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

      {/* ---- 设备池 ---- */}

      <Modal open={addPoolOpen} width={760} forceRender
        title={t('topology.pool.modal.createTitle')}
        onCancel={() => setAddPoolOpen(false)}
        onOk={submitPool} okText={t('topology.pool.modal.create')}>
        <Form form={poolForm} layout="vertical"
          initialValues={{
            poolKind: 'operation', strategy: 'first_available',
            skipOnError: true, skipOnOffline: true, allowIterationOnError: true,
            minNestCount: 0, members: [],
          }}>
          <Space size={12} style={{ width: '100%' }} align="start">
            <Form.Item name="code" label={t('topology.pool.field.code')} rules={[{ required: true }]}
              extra={t('topology.pool.field.codeExtra')} style={{ width: 280 }}>
              <Input placeholder="POOL-EXTRACT-1" />
            </Form.Item>
            <Form.Item name="name" label={t('topology.pool.field.name')} rules={[{ required: true }]}
              style={{ width: 280 }}>
              <Input placeholder={t('topology.pool.placeholder.poolName')} />
            </Form.Item>
          </Space>

          <Space size={12} style={{ width: '100%' }} align="start">
            <Form.Item name="islandId" label={t('topology.pool.field.island')} rules={[{ required: true }]}
              style={{ width: 280 }}>
              <Select placeholder={t('topology.pool.filter.island')}
                options={islands.map((i) => ({ value: i.id, label: `${i.code}  ${i.name}` }))} />
            </Form.Item>
            <Form.Item name="poolKind" label={t('topology.pool.field.kind')} rules={[{ required: true }]}
              extra={t('topology.pool.field.kindExtra')} style={{ width: 220 }}>
              <Select options={POOL_KINDS.map((k) => ({ value: k.value, label: t(k.labelKey) }))} />
            </Form.Item>
            <Form.Item name="strategy" label={t('topology.pool.field.strategy')} rules={[{ required: true }]}
              style={{ width: 220 }}>
              <Select options={POOL_STRATEGIES.map((s) => ({ value: s.value, label: t(s.labelKey) }))} />
            </Form.Item>
          </Space>

          <Space size={12} style={{ width: '100%' }} align="start" wrap>
            <Form.Item name="skipOnError" label={t('topology.pool.field.skipOnError')}
              valuePropName="checked" extra={t('topology.pool.field.skipOnErrorExtra')}>
              <Checkbox />
            </Form.Item>
            <Form.Item name="skipOnOffline" label={t('topology.pool.field.skipOnOffline')}
              valuePropName="checked" extra={t('topology.pool.field.skipOnOfflineExtra')}>
              <Checkbox />
            </Form.Item>
            <Form.Item name="allowIterationOnError" label={t('topology.pool.field.allowIterationOnError')}
              valuePropName="checked" extra={t('topology.pool.field.allowIterationOnErrorExtra')}>
              <Checkbox />
            </Form.Item>
            <Form.Item name="minNestCount" label={t('topology.pool.field.minNestCount')}
              extra={t('topology.pool.field.minNestCountExtra')}>
              <InputNumber min={0} style={{ width: 140 }} />
            </Form.Item>
          </Space>

          <Form.Item label={t('topology.pool.field.members')} extra={t('topology.pool.field.membersExtra')}>
            <Form.List name="members">
              {(fields, { add, remove }) => (
                <Space direction="vertical" size={8} style={{ width: '100%' }}>
                  {fields.map((f) => (
                    <Space key={f.key} size={8} align="start">
                      <Form.Item {...f} name={[f.name, 'deviceInstanceId']}
                        rules={[{ required: true }]}>
                        <Select showSearch allowClear style={{ width: 320 }}
                          placeholder={t('topology.pool.placeholder.device')}
                          optionFilterProp="label"
                          options={instances.map((x) => ({
                            value: x.instanceId,
                            label: `${x.code}  ${x.name}${x.model ? ` · ${x.model}` : ''}`,
                          }))} />
                      </Form.Item>
                      <Form.Item {...f} name={[f.name, 'subGroup']} noStyle>
                        <Input style={{ width: 130 }} placeholder={t('topology.pool.placeholder.subGroup')} />
                      </Form.Item>
                      <Form.Item {...f} name={[f.name, 'priority']} noStyle>
                        <InputNumber min={0} style={{ width: 110 }}
                          placeholder={t('topology.pool.placeholder.priority')} />
                      </Form.Item>
                      <Button size="small" type="link" danger {...ro}
                        onClick={() => remove(f.name)}>{t('topology.pool.action.removeMemberRow')}</Button>
                    </Space>
                  ))}
                  <Button size="small" icon={<PlusOutlined />} {...ro}
                    onClick={() => add({ priority: 100 })}>
                    {t('topology.pool.action.addMemberRow')}
                  </Button>
                </Space>
              )}
            </Form.List>
          </Form.Item>

          {draftMismatch && (
            <Alert type="error" showIcon
              message={t('topology.pool.warn.modelMismatch', { models: draftMismatch.join(' / ') })} />
          )}
        </Form>
      </Modal>

      <Modal open={editPoolRow != null} width={560}
        title={t('topology.pool.modal.editTitle', { code: editPoolRow?.code ?? '' })}
        onCancel={() => setEditPoolRow(null)}
        onOk={submitPoolEdit} okText={t('topology.pool.modal.save')}>
        <Form form={poolEditForm} layout="vertical">
          <Form.Item name="name" label={t('topology.pool.field.name')} rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="strategy" label={t('topology.pool.field.strategy')} rules={[{ required: true }]}>
            <Select options={POOL_STRATEGIES.map((s) => ({ value: s.value, label: t(s.labelKey) }))} />
          </Form.Item>
          <Space size={12} align="start" wrap>
            <Form.Item name="skipOnError" label={t('topology.pool.field.skipOnError')} valuePropName="checked">
              <Checkbox />
            </Form.Item>
            <Form.Item name="skipOnOffline" label={t('topology.pool.field.skipOnOffline')} valuePropName="checked">
              <Checkbox />
            </Form.Item>
            <Form.Item name="allowIterationOnError" label={t('topology.pool.field.allowIterationOnError')}
              valuePropName="checked">
              <Checkbox />
            </Form.Item>
            <Form.Item name="enabled" label={t('topology.pool.field.enabled')} valuePropName="checked">
              <Checkbox />
            </Form.Item>
          </Space>
        </Form>
      </Modal>

      <Modal open={detailPoolId != null} width={760}
        title={t('topology.pool.modal.membersTitle', { code: poolDetail.data?.code ?? '' })}
        onCancel={() => setDetailPoolId(null)}
        footer={
          <Space>
            <Button onClick={() => setDetailPoolId(null)}>{t('topology.pool.modal.close')}</Button>
            <Button type="primary" {...ro} disabled={ro.disabled || !poolDetail.data}
              onClick={() => { memberForm.resetFields(); setAddMemberOpen(true); }}>
              {t('topology.pool.action.addMember')}
            </Button>
          </Space>
        }>
        <Table<DevicePoolMember> rowKey="deviceInstanceId" size="small" loading={poolDetail.isLoading}
          dataSource={poolDetail.data?.members ?? []}
          pagination={false}
          locale={{ emptyText: <Empty description={t('topology.pool.emptyMembers')} /> }}
          columns={[
            {
              title: t('topology.pool.col.memberDevice'), dataIndex: 'deviceResourceCode', width: 180,
              render: (v: string | null | undefined, m) => v || m.deviceCode || String(m.deviceInstanceId),
            },
            { title: t('topology.pool.col.instanceId'), dataIndex: 'deviceInstanceId', width: 100 },
            {
              title: t('topology.pool.col.subGroup'), dataIndex: 'subGroup', width: 120,
              render: (v: string | null | undefined) => (v ? <Tag>{v}</Tag> : '—'),
            },
            { title: t('topology.pool.col.priority'), dataIndex: 'priority', width: 90 },
            {
              title: t('topology.pool.col.useCount'), dataIndex: 'useCount', width: 100,
              render: (v?: number | null) => v ?? '—',
            },
            {
              title: t('topology.pool.col.action'), width: 120,
              render: (_, m) => (
                <Button size="small" type="link" danger {...ro}
                  onClick={() => removeMember(m)}>{t('topology.pool.action.removeMember')}</Button>
              ),
            },
          ]} />
      </Modal>

      <Modal open={addMemberOpen} width={560} forceRender
        title={t('topology.pool.modal.addMemberTitle', { code: poolDetail.data?.code ?? '' })}
        onCancel={() => setAddMemberOpen(false)}
        onOk={submitAddMember} okText={t('topology.pool.modal.add')}>
        <Form form={memberForm} layout="vertical" initialValues={{ priority: 100 }}>
          <Form.Item name="deviceInstanceId" label={t('topology.pool.field.device')} rules={[{ required: true }]}>
            <Select showSearch allowClear placeholder={t('topology.pool.placeholder.device')}
              optionFilterProp="label"
              options={instances.map((x) => ({
                value: x.instanceId,
                label: `${x.code}  ${x.name}${x.model ? ` · ${x.model}` : ''}`,
              }))} />
          </Form.Item>
          {memberMismatch && (
            <Alert type="error" showIcon
              message={t('topology.pool.warn.modelMismatch', { models: memberMismatch.join(' / ') })} />
          )}
          <Form.Item name="subGroup" label={t('topology.pool.field.subGroup')}
            extra={t('topology.pool.field.subGroupExtra')}>
            <Input placeholder={t('topology.pool.placeholder.subGroup')} />
          </Form.Item>
          <Form.Item name="priority" label={t('topology.pool.field.priority')}
            extra={t('topology.pool.field.priorityExtra')}>
            <InputNumber min={0} style={{ width: 160 }} />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}

/** 协议属性的形状（device.attr 的松散结构，字段都可能缺）。 */
interface ProtoAttr {
  device_model?: string;
  manual?: string;
  evidence?: string;
  gaps?: string[];
  protocol?: {
    kind?: string;
    baud?: number;
    dataBits?: number;
    stopBits?: number;
    parity?: string;
    bits?: string;
    frame?: Record<string, unknown>;
  };
}

function asText(v: unknown): string {
  return Array.isArray(v) ? v.map((x) => String(x)).join(' ') : String(v);
}

/**
 * 型号协议卡（**只读**）。
 *
 * <p>为什么放在设备编辑弹窗里：现场调设备时第一个要回答的就是"这台的帧怎么发、
 * 波特率多少、校验怎么算"，这些以前只在手册 PDF 里，现在随型号存在 `device.attr`。
 *
 * <p>为什么不在这里做编辑：写接口是 `PUT /api/devices/{id}/attr`（白名单 6 键），
 * 归型号库维护；拓扑页只负责让人**看得到**。`gaps` 是手册里没写、必须现场确认的点，
 * 用黄色告警显式列出 —— 这类"以为文档写了"的假设最容易在接真机时踩坑。
 */
function ProtocolPanel({ attr }: { attr?: string }) {
  const t = useT();
  let parsed: ProtoAttr | null = null;
  if (attr) {
    try {
      parsed = JSON.parse(attr) as ProtoAttr;
    } catch {
      // 脏数据不炸页面：退回"未登记"
      parsed = null;
    }
  }
  const p = parsed?.protocol;
  if (!p) {
    return (
      <Alert style={{ marginTop: 12 }} type="info" showIcon
        message={t('topology.device.protocol.empty')} />
    );
  }
  const f = p.frame ?? {};
  const frameText = [
    f.sof ? `SOF ${asText(f.sof)}` : null,
    f.bytes ? `${asText(f.bytes)} 字节` : null,
    f.cmdBytes ? `CMD ${asText(f.cmdBytes)}B` : null,
    f.lenBytes ? `LEN ${asText(f.lenBytes)}B` : null,
    f.dataBytes ? `DATA ${asText(f.dataBytes)}B` : null,
    f.eof ? `EOF ${asText(f.eof)}` : null,
    f.checksum ? `CHK ${asText(f.checksum)}` : null,
  ].filter(Boolean).join(' · ');
  const serial = p.kind === 'binary-serial'
    ? `${p.baud ?? '?'} bps · ${p.dataBits ?? 8}${p.parity === 'none' ? 'N' : (p.parity ?? 'N')}${p.stopBits ?? 1}`
    : (p.kind ?? '—');

  return (
    <Card size="small" style={{ marginTop: 12 }} title={t('topology.device.protocol.title')}>
      <Descriptions size="small" column={2} bordered>
        <Descriptions.Item label={t('topology.device.protocol.serial')}>
          <Typography.Text code>{serial}</Typography.Text>
        </Descriptions.Item>
        <Descriptions.Item label={t('topology.device.protocol.evidence')}>
          {parsed?.evidence ?? '—'}
          {parsed?.device_model ? ` · ${parsed.device_model}` : ''}
        </Descriptions.Item>
        <Descriptions.Item label={t('topology.device.protocol.frame')} span={2}>
          <Typography.Text code>{frameText || '—'}</Typography.Text>
        </Descriptions.Item>
        <Descriptions.Item label={t('topology.device.protocol.manual')} span={2}>
          {parsed?.manual ? <Typography.Text code>{parsed.manual}</Typography.Text> : '—'}
        </Descriptions.Item>
      </Descriptions>
      {Array.isArray(parsed?.gaps) && parsed.gaps.length > 0 && (
        <Alert style={{ marginTop: 8 }} type="warning" showIcon
          message={t('topology.device.protocol.gaps')}
          description={
            <ul style={{ margin: 0, paddingLeft: 18 }}>
              {parsed.gaps.map((g) => <li key={g}>{g}</li>)}
            </ul>
          } />
      )}
    </Card>
  );
}
