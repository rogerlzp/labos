import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert, Button, Card, Descriptions, Input, Select, Space, Table, Tag, Tooltip, Typography, message,
} from 'antd';
import { apiFetch } from '../api/client';
import { useT } from '../i18n';

interface Island { id: number; code: string; name: string; devices: Device[]; }
interface Device { resourceId: number; code: string; name: string; deviceType?: string; }
interface RobotPoint {
  id: number; code: string; name?: string; x?: number | null; y?: number | null; z?: number | null;
  deviceResourceId?: number; fillStatus?: string; action?: string;
}
/** 设备方法清单里的一项（`GET /api/devices/{id}/methods`）。 */
interface DeviceMethod {
  /** device_method.id（2026-11-15 起接口才带这个字段） */
  methodId?: number;
  method: string;
  name?: string;
  capability?: string;
}

/**
 * 机械臂调试台（admin 专属）。
 *
 * <p><b>2026-11-15 修的真 bug</b>：「示教读取」原来发的是
 * `POST /api/islands/{id}/robot-points/{code}/capture` **不带任何 query 参数**，
 * 而后端 {@code DeviceController.captureRobotPoint} 强制要
 * {@code robotResourceId}（机械臂的 resource_node.id）+ {@code method}
 * （读取坐标用的方法名）—— 缺任一个都直接回 400「需要 robotResourceId 和 method」。
 * ⇒ **这个按钮以前必然失败**，而且失败原因指向的参数在界面上根本没地方填。
 *
 * <p>⇒ 现在：先选机械臂，再选该机械臂的**某个方法**（从
 * {@code GET /api/devices/{resourceId}/methods} 拿，方法名各厂商不同：
 * {@code Home} / {@code GetLoadPosition} / {@code DepositToAbsolutePosition} …），
 * 两个参数都带上去。
 */
export default function ArmConsolePage() {
  const t = useT();
  const qc = useQueryClient();
  const [islandId, setIslandId] = useState<number | undefined>();
  /** 选中的机械臂（resource_node.id） */
  const [robotId, setRobotId] = useState<number | undefined>();
  /** 选中的读取方法（方法名，不是 id —— 后端要的是方法名） */
  const [readMethod, setReadMethod] = useState<string | undefined>();
  /** 手工填坐标：真机读不到时，现场按图纸填 */
  const [manual, setManual] = useState<{ x?: number; y?: number; z?: number }>({});

  const topoQ = useQuery({
    queryKey: ['topology'],
    queryFn: () => apiFetch('/api/topology').then((r) => r.json()) as Promise<{ islands: Island[] }>,
  });
  const islands = topoQ.data?.islands ?? [];
  const arms = (islands.find((i) => i.id === islandId)?.devices ?? [])
    .filter((d) => d.deviceType === 'Robots');

  const pointsQ = useQuery({
    queryKey: ['robot-points', islandId],
    queryFn: () => apiFetch(`/api/islands/${islandId}/robot-points`).then((r) => r.json()) as Promise<{
      items?: RobotPoint[]; points?: RobotPoint[];
    }>,
    enabled: islandId != null,
  });
  const points = pointsQ.data?.items ?? pointsQ.data?.points ?? [];

  /**
   * 该机械臂支持的方法（用来选「读坐标用哪个方法」）。
   *
   * <p>方法名**各厂商不同**，没有统一叫法 ⇒ 只能让用户从实际方法里选，
   * 不能写死一个（写死就等于猜）。
   */
  const methodsQ = useQuery({
    queryKey: ['device-methods', robotId],
    queryFn: () => apiFetch(`/api/devices/${robotId}/methods`).then((r) => r.json()) as Promise<{
      items?: DeviceMethod[];
    }>,
    enabled: robotId != null,
  });
  const methods = methodsQ.data?.items ?? [];

  /**
   * 示教读取：把两个必需参数都带上。
   *
   * <p>⚠ robotResourceId 传的是 **resource_node.id**（机械臂设备节点），
   * 后端自己会把它转成 device_instance.id（`DeviceService.instanceIdOf`）——
   * 直接传实例 id 会让后端查不到。
   */
  const capture = async (code: string) => {
    if (robotId == null || !readMethod) {
      message.error(t('armConsole.error.pickRobotAndMethod'));
      return;
    }
    try {
      const q = new URLSearchParams({
        robotResourceId: String(robotId),
        method: readMethod,
      });
      const r = await apiFetch(
        `/api/islands/${islandId}/robot-points/${encodeURIComponent(code)}/capture?${q.toString()}`,
        { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({}) },
      );
      const j = await r.json() as { captured?: number; error?: string; raw?: string };
      if (!r.ok) throw new Error(j.error ?? `HTTP ${r.status}`);
      if (j.captured === 0) {
        // 驱动没返回坐标：把原文给现场看（他要判断该配什么字段）
        message.warning(j.raw ?? t('armConsole.toast.noCoords'));
      } else {
        message.success(t('armConsole.toast.captured', { code }));
      }
      qc.invalidateQueries({ queryKey: ['robot-points'] });
    } catch (e) {
      message.error(t('armConsole.error.capture', { error: (e as Error).message }));
    }
  };

  /** 手工填坐标（真机读不到时的兜底 —— 现场按图纸/示教器读数填）。 */
  const saveManual = async (code: string) => {
    if (manual.x == null || manual.y == null || manual.z == null) {
      message.error(t('armConsole.error.needXYZ'));
      return;
    }
    try {
      const r = await apiFetch(
        `/api/islands/${islandId}/robot-points/${encodeURIComponent(code)}`,
        {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ x: manual.x, y: manual.y, z: manual.z }),
        },
      );
      if (!r.ok) throw new Error((await r.json() as { error?: string }).error ?? `HTTP ${r.status}`);
      message.success(t('armConsole.toast.saved'));
      setManual({});
      qc.invalidateQueries({ queryKey: ['robot-points'] });
    } catch (e) {
      message.error(t('armConsole.error.save', { error: (e as Error).message }));
    }
  };

  return (
    <div style={{ padding: 16 }}>
      <Card size="small" title={t('armConsole.title')}
        extra={
          <Space>
            <Select size="small" placeholder={t('armConsole.pickIsland')} style={{ width: 160 }}
              value={islandId}
              onChange={(v) => { setIslandId(v); setRobotId(undefined); setReadMethod(undefined); }}
              options={islands.map((i) => ({ value: i.id, label: `${i.name}（${i.code}）` }))} />
            <Select size="small" placeholder={t('armConsole.pickRobot')} style={{ width: 200 }}
              value={robotId}
              onChange={(v) => { setRobotId(v); setReadMethod(undefined); }}
              options={arms.map((a) => ({ value: a.resourceId, label: `${a.code}  ${a.name}` }))} />
            <Select size="small" placeholder={t('armConsole.pickMethod')} style={{ width: 220 }}
              value={readMethod}
              onChange={setReadMethod}
              loading={methodsQ.isLoading}
              options={methods.map((m) => ({
                value: m.method,
                label: m.name ? `${m.method}（${m.name}）` : m.method,
              }))} />
          </Space>
        }>
        <Alert type="warning" showIcon style={{ marginBottom: 12 }}
          message={t('armConsole.warn')} />

        <Typography.Text strong>
          {t('armConsole.arms', { n: arms.length })}
        </Typography.Text>
        {arms.map((a) => (
          <Descriptions key={a.resourceId} size="small" bordered column={3}
            style={{ margin: '8px 0' }}>
            <Descriptions.Item label={t('armConsole.col.code')}>{a.code}</Descriptions.Item>
            <Descriptions.Item label={t('armConsole.col.name')}>{a.name}</Descriptions.Item>
            <Descriptions.Item label={t('armConsole.col.resourceId')}>{a.resourceId}</Descriptions.Item>
          </Descriptions>
        ))}

        <Typography.Text strong style={{ display: 'block', marginTop: 12 }}>
          {t('armConsole.points', { n: points.length })}
        </Typography.Text>
        <Table<RobotPoint> size="small" rowKey="id" dataSource={points} pagination={{ pageSize: 15 }}
          style={{ marginTop: 8 }}
          columns={[
            { title: t('armConsole.col.point'), dataIndex: 'code', width: 180,
              render: (v: string) => <Typography.Text code>{v}</Typography.Text> },
            { title: 'x / y / z', key: 'xyz', width: 220,
              render: (_, r) => (r.x != null && r.y != null && r.z != null)
                ? `${r.x}, ${r.y}, ${r.z}` : <Tag color="orange">{t('armConsole.pending')}</Tag> },
            { title: t('armConsole.col.status'), dataIndex: 'fillStatus', width: 100,
              render: (v: string) => (v === 'pending'
                ? <Tag color="orange">{v}</Tag> : <Tag color="green">{v ?? 'ok'}</Tag>) },
            {
              title: '', key: 'op', width: 260,
              render: (_, r) => (
                <Space size={4}>
                  <Tooltip title={
                    robotId == null || !readMethod
                      ? t('armConsole.hint.pickFirst')
                      : t('armConsole.hint.capture', { robot: robotId, method: readMethod })
                  }>
                    <Button size="small" onClick={() => { void capture(r.code); }}>
                      {t('armConsole.action.capture')}
                    </Button>
                  </Tooltip>
                  {/* 手工填坐标：真机读不到时的兜底 */}
                  <Input size="small" style={{ width: 62 }} placeholder="x"
                    value={manual.x}
                    onChange={(e) => setManual({ ...manual, x: e.target.value === '' ? undefined : Number(e.target.value) })} />
                  <Input size="small" style={{ width: 62 }} placeholder="y"
                    value={manual.y}
                    onChange={(e) => setManual({ ...manual, y: e.target.value === '' ? undefined : Number(e.target.value) })} />
                  <Input size="small" style={{ width: 62 }} placeholder="z"
                    value={manual.z}
                    onChange={(e) => setManual({ ...manual, z: e.target.value === '' ? undefined : Number(e.target.value) })} />
                  <Button size="small" onClick={() => { void saveManual(r.code); }}>
                    {t('armConsole.action.saveManual')}
                  </Button>
                </Space>
              ),
            },
          ]} />
      </Card>
    </div>
  );
}
