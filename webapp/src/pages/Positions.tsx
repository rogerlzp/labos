import { useMemo, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert, Button, Card, Empty, Form, Input, InputNumber, Modal, Select, Space, Switch, Table, Tag, Typography, message,
} from 'antd';
import { apiFetch } from '../api/client';

interface Island { id: number; code: string; name: string; }
interface Device { resourceId: number; code: string; name: string; }
interface Position {
  resourceId: number; code: string; name: string; kind: string;
  plateId?: string | null; reserved: boolean;
  parentDeviceId: number; parentDeviceCode: string;
  pointRole?: string | null; robotReachable?: boolean | null;
  isEntry?: boolean | null; isExit?: boolean | null;
  contentKind?: string | null;
  contentCode?: string | null; contentName?: string | null;
  gridRow?: number | null; gridCol?: number | null;
  maxHeightMm?: number | null; plateTypes?: string | null;
  orderInDevice?: number | null;
  /** 现场贴的位置标识（空 = 回退显示系统编码 code）。 */
  positionLabel?: string | null;
  /** true = 网格之外的单独位置（培养箱/堆栈的进口/出口装载口）。 */
  standalone?: boolean | null;
}

const ROLE_TEXT: Record<string, { zh: string; color: string }> = {
  entry: { zh: '进口', color: 'blue' }, exit: { zh: '出口', color: 'purple' },
  both: { zh: '进出共用', color: 'green' }, internal: { zh: '内部位', color: 'default' },
};
const KIND_TEXT: Record<string, string> = {
  storage: '存储位', incubator: '孵育位', transfer_point: '中转位',
};

export default function PositionsPage() {
  const qc = useQueryClient();
  const [islandId, setIslandId] = useState<number | undefined>();
  const [deviceId, setDeviceId] = useState<number | undefined>();
  const [editing, setEditing] = useState<Position | null>(null);
  const [form] = Form.useForm();
  // 单独位置不属于网格，勾上后禁用排/列输入（后端也会把两列清成 NULL）
  const isStandalone = Form.useWatch('standalone', form);
  const [createOpen, setCreateOpen] = useState(false);
  const [createForm] = Form.useForm();

  const islandsQ = useQuery({
    queryKey: ['topology'],
    queryFn: () => apiFetch('/api/topology').then((r) => r.json()) as Promise<{
      islands: (Island & { devices: Device[] })[];
    }>,
  });
  const islands = islandsQ.data?.islands ?? [];
  const devices = islands.find((i) => i.id === islandId)?.devices ?? [];

  const positionsQ = useQuery({
    queryKey: ['positions', islandId],
    queryFn: () => apiFetch(`/api/positions?islandId=${islandId}`).then((r) => r.json()) as Promise<{
      total: number; items: Position[];
    }>,
    enabled: islandId != null,
    refetchInterval: 8000,
  });
  const positions = positionsQ.data?.items ?? [];

  // 每台设备的点位数（合并 topology 设备 + positions）
  const devWithCount = useMemo(() => devices.map((d) => ({
    ...d,
    count: positions.filter((p) => p.parentDeviceId === d.resourceId).length,
  })), [devices, positions]);

  const selectedPositions = useMemo(() => {
    if (deviceId == null) return [];
    const pts = positions.filter((p) => p.parentDeviceId === deviceId);
    // 单独位置不占网格，排在最后单独展示
    pts.sort((a, b) => Number(!!a.standalone) - Number(!!b.standalone)
      || (a.gridRow ?? 0) - (b.gridRow ?? 0) || (a.gridCol ?? 0) - (b.gridCol ?? 0));
    return pts;
  }, [positions, deviceId]);

  /** 网格之外的单独位置：培养箱/堆栈的进口/出口装载口。 */
  const standalonePositions = useMemo(
    () => selectedPositions.filter((p) => p.standalone), [selectedPositions]);
  /** 参与「几行几列」的点位（排除单独位置，避免它撑大网格维度）。 */
  const gridPositions = useMemo(
    () => selectedPositions.filter((p) => !p.standalone), [selectedPositions]);

  // 网格维度
  const grid = useMemo(() => {
    const maxRow = Math.max(1, ...gridPositions.map((p) => p.gridRow ?? 1));
    const maxCol = Math.max(1, ...gridPositions.map((p) => p.gridCol ?? 1));
    const hasGrid = gridPositions.some((p) => p.gridRow != null || p.gridCol != null);
    return { maxRow, maxCol, hasGrid };
  }, [gridPositions]);

  const openEdit = (p: Position) => {
    setEditing(p);
    form.setFieldsValue({
      pointRole: p.pointRole ?? 'both',
      robotReachable: p.robotReachable ?? true,
      isEntry: p.isEntry ?? true, isExit: p.isExit ?? true,
      orderInDevice: p.orderInDevice ?? 0,
      gridRow: p.gridRow ?? undefined, gridCol: p.gridCol ?? undefined,
      maxHeightMm: p.maxHeightMm ?? undefined, plateTypes: p.plateTypes ?? '',
      standalone: p.standalone ?? false,
      positionLabel: p.positionLabel ?? '',
      note: '',
    });
  };

  const save = async () => {
    const v = await form.validateFields();
    if (!editing) return;
    // 备注没填就别提交：后端对未提交的备注保持原值（否则每次编辑都会冲掉装载口备注）
    if (!v.note) delete v.note;
    try {
      await apiFetch(`/api/device-points/${editing.resourceId}`, {
        method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(v),
      });
      message.success('点位配置已保存');
      setEditing(null);
      qc.invalidateQueries({ queryKey: ['positions'] });
    } catch (e) { message.error(`保存失败：${(e as Error).message}`); }
  };

  /** 新建单独位置：编码 = 设备编码-后缀（默认 IN），同编码已存在时复用。 */
  const createStandalone = async () => {
    if (deviceId == null) return;
    const v = await createForm.validateFields();
    const mode = v.mode ?? 'both';
    try {
      const r = await apiFetch('/api/device-points/standalone', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          deviceResourceId: deviceId,
          positionLabel: v.positionLabel ?? '',
          codeSuffix: (v.codeSuffix || 'IN').trim(),
          isEntry: mode !== 'exit',
          isExit: mode !== 'entry',
        }),
      });
      const j = await r.json();
      if (!r.ok) throw new Error(j.error ?? '创建失败');
      message.success(`已创建单独位置 ${j.pointCode}`);
      setCreateOpen(false);
      qc.invalidateQueries({ queryKey: ['positions'] });
    } catch (e) { message.error(`创建失败：${(e as Error).message}`); }
  };

  const cellText = (p?: Position) => {
    if (!p) return '';
    if (p.plateId) return `板 ${p.plateId}`;
    if (p.contentKind === 'consumable') return `耗材 ${p.contentCode ?? ''}`;
    if (p.contentKind === 'reagent') return `试剂 ${p.contentCode ?? ''}`;
    return '';
  };

  const occupiedColor = (p?: Position) => (p && (p.plateId || p.contentKind)) ? '#fff7e6' : undefined;

  /**
   * 网格之外的单独位置：培养箱/耗材堆栈的进口 + 出口。
   * 它不占 grid_row/grid_col，所以不能塞进上面那张网格图里 —— 单独一块展示，
   * 并显式打出「进口 / 出口」标记和位置标识（现场贴的标签，可改）。
   */
  const standaloneBlock = (
    <div style={{ marginTop: 16, paddingTop: 12, borderTop: '1px dashed #d9d9d9' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <Space size={6}>
          <Typography.Text strong>单独位置 · 进口 / 出口</Typography.Text>
          <Tag color="green">{standalonePositions.length}</Tag>
        </Space>
        <Button size="small" type="primary" ghost
          onClick={() => {
            createForm.setFieldsValue({ mode: 'both', positionLabel: '', codeSuffix: 'IN' });
            setCreateOpen(true);
          }}>新建单独位置</Button>
      </div>
      <div style={{ fontSize: 12, color: '#888', margin: '4px 0 8px' }}>
        不属于「几行几列」的网格：机械臂只在这里放板 / 取板。位置标识可改成现场贴的标签，系统编码不变。
      </div>
      {standalonePositions.length === 0 ? (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          该设备还没有单独位置：点右上角「新建单独位置」补一个（也可以把已有装载口在编辑弹窗里勾选为「单独位置」）。
        </Typography.Text>
      ) : (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
          {standalonePositions.map((p) => {
            const label = p.positionLabel || p.code;
            return (
              <div key={p.resourceId} onClick={() => openEdit(p)}
                style={{
                  minWidth: 190, padding: 10, borderRadius: 8, cursor: 'pointer',
                  border: '1px solid #91caff', background: occupiedColor(p) ?? '#f0f8ff',
                }}>
                <div style={{ fontWeight: 600 }}>{label}</div>
                {label !== p.code && (
                  <div style={{ fontFamily: 'monospace', fontSize: 11, color: '#888' }}>{p.code}</div>
                )}
                <Space size={4} wrap style={{ marginTop: 8 }}>
                  {p.isEntry && <Tag color="blue">进口</Tag>}
                  {p.isExit && <Tag color="purple">出口</Tag>}
                  {!p.isEntry && !p.isExit && <Tag>未标记进出口</Tag>}
                  {cellText(p) ? <Tag color="orange">{cellText(p)}</Tag> : <Tag>空</Tag>}
                </Space>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );

  return (
    <div style={{ padding: 16 }}>
      <Card size="small" title="位置管理"
        extra={<Space>
          <Select size="small" allowClear placeholder="选择岛" style={{ width: 200 }}
            value={islandId} onChange={(v) => { setIslandId(v); setDeviceId(undefined); }}
            options={islands.map((i) => ({ value: i.id, label: `${i.name}（${i.code}）` }))} />
          <Button size="small" onClick={() => qc.invalidateQueries({ queryKey: ['positions'] })}>刷新</Button>
        </Space>}>
        {islandId == null ? <Empty description="先选择一个岛" /> : (
          <div style={{ display: 'flex', gap: 16 }}>
            {/* 左：设备列表（全部 13 台） */}
            <div style={{ width: 240, flexShrink: 0 }}>
              <Typography.Text strong>设备（{devWithCount.length} 台）</Typography.Text>
              <div style={{ marginTop: 8, maxHeight: 600, overflow: 'auto' }}>
                {devWithCount.map((d) => (
                  <div key={d.resourceId}
                    onClick={() => setDeviceId(d.resourceId)}
                    style={{
                      padding: '6px 10px', cursor: 'pointer', borderRadius: 6, marginBottom: 4,
                      background: deviceId === d.resourceId ? '#e6f4ff' : '#fafafa',
                      border: deviceId === d.resourceId ? '1px solid #91caff' : '1px solid #eee',
                    }}>
                    <div><Typography.Text code>{d.code}</Typography.Text></div>
                    <div style={{ fontSize: 12, color: '#888' }}>{d.name}</div>
                    <Tag color={d.count > 0 ? 'green' : 'default'}>{d.count} 个点位</Tag>
                  </div>
                ))}
              </div>
            </div>

            {/* 右：选中设备的点位（网格或表格） */}
            <div style={{ flex: 1 }}>
              {deviceId == null ? <Empty description="点左侧选择一台设备" /> : (
                <>
                  {selectedPositions.length === 0 ? (
                    <Empty description="该设备尚未配置点位（可用下方「新建单独位置」补进出口，或用 gen-grid-points.py 生成网格）" />
                  ) : grid.hasGrid ? (
                      <div>
                        <Typography.Text strong>
                          {selectedPositions[0].parentDeviceCode} · {grid.maxCol} 列 × {grid.maxRow} 层
                        </Typography.Text>
                        <div style={{
                          display: 'grid', gridTemplateColumns: `repeat(${grid.maxCol}, 1fr)`,
                          gap: 6, marginTop: 8,
                        }}>
                          {Array.from({ length: grid.maxRow }).flatMap((_, r) =>
                            Array.from({ length: grid.maxCol }).map((_, c) => {
                              const p = gridPositions.find((x) => x.gridRow === r + 1 && x.gridCol === c + 1);
                              const label = p ? (p.positionLabel || p.code) : '—';
                              return (
                                <div key={`${r}-${c}`} onClick={() => p && openEdit(p)}
                                  title={p ? `${p.code}${p.positionLabel ? ' · ' + p.positionLabel : ''}${cellText(p) ? ' · ' + cellText(p) : ''}` : '空槽'}
                                  style={{
                                    minHeight: 52, padding: 6, borderRadius: 6, cursor: p ? 'pointer' : 'default',
                                    background: occupiedColor(p) ?? '#f5f5f5',
                                    border: '1px solid #e0e0e0', fontSize: 11,
                                  }}>
                                  <div style={{ fontWeight: 600 }}>{label}</div>
                                  {p?.positionLabel && p.positionLabel !== p.code && (
                                    <div style={{ fontFamily: 'monospace', fontSize: 10, color: '#888' }}>{p.code}</div>
                                  )}
                                  <div style={{ color: '#888' }}>{cellText(p)}</div>
                                </div>
                              );
                            }))}
                        </div>
                      </div>
                    ) : gridPositions.length > 0 ? (
                      <Table<Position> size="small" rowKey="resourceId" dataSource={gridPositions}
                        pagination={false} columns={[
                          { title: '位置标识', key: 'label', width: 150, render: (_, p) => <Typography.Text code>{p.positionLabel || p.code}</Typography.Text> },
                          { title: '点位编码', dataIndex: 'code', width: 150, render: (v: string) => <Typography.Text code>{v}</Typography.Text> },
                          { title: '类型', dataIndex: 'kind', width: 90, render: (v: string) => KIND_TEXT[v] ?? v },
                          { title: '角色', dataIndex: 'pointRole', width: 90, render: (v: string) => { const r = ROLE_TEXT[v] ?? { zh: v ?? '—', color: 'default' }; return <Tag color={r.color}>{r.zh}</Tag>; } },
                          { title: '占用', key: 'occ', render: (_, p) => cellText(p) ? <Tag color="orange">{cellText(p)}</Tag> : <Tag>空</Tag> },
                          { title: '', key: 'op', width: 60, render: (_, p) => <Button size="small" type="link" onClick={() => openEdit(p)}>编辑</Button> },
                        ]} />
                    ) : (
                      <Empty description="该设备只有单独位置（见下方）" />
                    )}
                  {standaloneBlock}
                </>
              )}
            </div>
          </div>
        )}
      </Card>

      <Modal title={`编辑点位 · ${editing?.code ?? ''}`} open={editing != null}
        onCancel={() => setEditing(null)} onOk={save} okText="保存" cancelText="取消">
        <Form form={form} layout="vertical">
          <Form.Item name="standalone" label="单独位置（网格之外的进口 / 出口）"
            tooltip="培养箱、耗材堆栈除几行几列的存储格外，还有一个机械臂放板/取板的位置。勾上后不参与网格排布。"
            valuePropName="checked"><Switch /></Form.Item>
          <Form.Item name="positionLabel" label="位置标识"
            tooltip="现场贴的标签/编号；留空则页面显示系统编码。改它不会改点位编码。"
            extra={editing ? `系统编码：${editing.code}` : undefined}>
            <Input placeholder="如：X229 进出口 / IN-1" />
          </Form.Item>
          <Form.Item name="pointRole" label="角色" rules={[{ required: true }]}>
            <Select options={[
              { value: 'entry', label: '进口' }, { value: 'exit', label: '出口' },
              { value: 'both', label: '进出共用' },
              ...(isStandalone ? [] : [{ value: 'internal', label: '内部位（机械臂不可达）' }]),
            ]} />
          </Form.Item>
          <Form.Item name="robotReachable" label="机械臂可达" valuePropName="checked"><Switch /></Form.Item>
          <Form.Item name="isEntry" label="是进口" valuePropName="checked"><Switch /></Form.Item>
          <Form.Item name="isExit" label="是出口" valuePropName="checked"><Switch /></Form.Item>
          <Form.Item name="orderInDevice" label="设备内顺序"><Input type="number" /></Form.Item>
          <Form.Item name="gridRow" label="排（gridRow）"
            extra={isStandalone ? '单独位置不参与网格' : undefined}>
            <InputNumber min={0} disabled={!!isStandalone} />
          </Form.Item>
          <Form.Item name="gridCol" label="列（gridCol）"
            extra={isStandalone ? '单独位置不参与网格' : undefined}>
            <InputNumber min={0} disabled={!!isStandalone} />
          </Form.Item>
          <Form.Item name="maxHeightMm" label="最高板高（mm）"><InputNumber min={0} /></Form.Item>
          <Form.Item name="plateTypes" label="可放板型（逗号分隔）"><Input placeholder="pcr96,deep96" /></Form.Item>
          <Form.Item name="note" label="备注"><Input.TextArea rows={2} /></Form.Item>
        </Form>
      </Modal>

      <Modal title="新建单独位置（进口 / 出口）" open={createOpen}
        onCancel={() => setCreateOpen(false)} onOk={createStandalone}
        okText="创建" cancelText="取消">
        <Alert type="info" showIcon style={{ marginBottom: 12 }}
          message="网格之外的独立点位：机械臂只在这里放板 / 取板，不占「几行几列」。"
          description="编码 = 设备编码-后缀（默认 IN）；同编码已存在时复用并更新配置，重复创建不会造出第二个装载口。" />
        <Form form={createForm} layout="vertical">
          <Form.Item name="mode" label="进出口" rules={[{ required: true }]}>
            <Select options={[
              { value: 'both', label: '进口 + 出口（进出共用）' },
              { value: 'entry', label: '只进口' },
              { value: 'exit', label: '只出口' },
            ]} />
          </Form.Item>
          <Form.Item name="positionLabel" label="位置标识"
            tooltip="现场贴的标签/编号；留空则用系统编码。之后可在编辑弹窗里随时改。">
            <Input placeholder="如：X229 进出口" />
          </Form.Item>
          <Form.Item name="codeSuffix" label="编码后缀" extra="默认 IN；最终编码 = 设备编码-后缀">
            <Input placeholder="IN" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
