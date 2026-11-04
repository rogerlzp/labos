import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import ReactFlow, {
  Background, Controls, Handle, MiniMap, Position, addEdge,
  useEdgesState, useNodesState,
  type Connection, type Edge, type Node, type NodeProps,
} from 'reactflow';
import 'reactflow/dist/style.css';
import {
  Alert, Button, Card, Col, Empty, Row, Select, Space, Tag, Typography, message,
} from 'antd';
import { apiFetch } from '../api/client';
import { useReadOnly } from '../auth';
import { useT } from '../i18n';

interface Island { id: number; code: string; name: string; deviceCount: number; devices?: Dev[] }
interface Dev {
  resourceId: number; code: string; name: string; capability?: string;
  ip?: string | null; x?: number | null; y?: number | null;
}
interface DevData {
  resourceId: number; code: string; name: string;
  capability?: string; ip?: string | null;
}

const CAP_COLOR: Record<string, string> = {
  TRANSFER: '#3b82f6', INCUBATE: '#f59e0b', ALIQUOT: '#a855f7',
  ANALYZE: '#06b6d4', CENTRIFUGE: '#ec4899', STORE: '#8b93a7',
  READ_PLATE: '#22c55e', PCR: '#ef4444',
};

/** 设备节点：展示照片色条 + 编号 + 能力 + IP。 */
function DeviceNode({ data, selected }: NodeProps<DevData>) {
  const t = useT();
  const color = CAP_COLOR[data.capability ?? ''] ?? '#8b93a7';
  return (
    <div style={{
      width: 150, padding: '6px 8px', borderRadius: 8, background: '#12161d',
      border: `2px solid ${selected ? color : '#262c38'}`,
      boxShadow: selected ? `0 0 0 3px ${color}33` : 'none',
    }}>
      <Handle type="target" position={Position.Top} style={{ background: color }} />
      <div style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
        <span style={{ width: 7, height: 7, borderRadius: 2, background: color }} />
        <span style={{ fontSize: 10, color, fontWeight: 700 }}>
          {data.capability ?? '—'}
        </span>
      </div>
      <div style={{ fontSize: 12, fontWeight: 600, marginTop: 2 }}>{data.code}</div>
      <div style={{ fontSize: 10, color: '#8b93a7', overflow: 'hidden',
        textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{data.name}</div>
      <div style={{ fontSize: 9, color: '#5b6376', fontFamily: 'monospace' }}>
        {data.ip ?? t('islandFlow.ipNotConfigured')}
      </div>
      <Handle type="source" position={Position.Bottom} style={{ background: color }} />
    </div>
  );
}

const NODE_TYPES = { deviceNode: DeviceNode };

export default function IslandFlowPage() {
  const t = useT();
  const nav = useNavigate();
  // 动线画布会自动保存（600ms debounce）与执行：viewer 只读
  const ro = useReadOnly();
  const canWrite = !ro.disabled;
  const [islands, setIslands] = useState<Island[]>([]);
  const [islandId, setIslandId] = useState<number | null>(null);
  const [nodes, setNodes, onNodesChange] = useNodesState<DevData>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState([]);
  const [running, setRunning] = useState(false);
  const [lastWf, setLastWf] = useState<number | null>(null);
  const [savedAt, setSavedAt] = useState<Date | null>(null);

  // 拉岛屿
  useEffect(() => {
    apiFetch('/api/topology')
      .then((r) => r.json())
      .then((d: { islands?: Island[] }) => {
        const list = d.islands ?? [];
        setIslands(list);
        // 默认选"已布局设备最多"的岛
        const laidOut = (i: Island) =>
          (i.devices ?? []).filter((x) => x.x != null).length;
        const best = [...list].sort((a, b) => laidOut(b) - laidOut(a))[0];
        if (best) setIslandId(best.id);
      })
      .catch(() => { /* 拉不到就空着 */ });
  }, []);

  const island = useMemo(
    () => islands.find((i) => i.id === islandId) ?? null,
    [islands, islandId],
  );

  /** 换岛：设备铺开 + 还原已存的动线（位置和箭头）。 */
  useEffect(() => {
    if (!island) { setNodes([]); setEdges([]); return; }
    let alive = true;
    const devs = island.devices ?? [];

    apiFetch(`/api/islands/${island.id}/flow`)
      .then((r) => r.json())
      .then((g: {
        nodes?: Array<{ id: string; position: { x: number; y: number } }>;
        edges?: Array<{ id: string; source: string; target: string }>;
      }) => {
        if (!alive) return;
        const savedPos = new Map((g.nodes ?? []).map((n) => [n.id, n.position]));

        const ns: Node<DevData>[] = devs.map((d, i) => {
          const id = `dev-${d.resourceId}`;
          const keep = savedPos.get(id);
          return {
            id,
            type: 'deviceNode',
            position: keep ?? {
              x: d.x != null ? Number(d.x) : 40 + (i % 4) * 200,
              y: d.y != null ? Number(d.y) : 40 + Math.floor(i / 4) * 120,
            },
            data: {
              resourceId: d.resourceId, code: d.code, name: d.name,
              capability: d.capability, ip: d.ip,
            },
          };
        });
        setNodes(ns);

        // 只保留两端设备都还在的箭头（设备可能已经被移出岛）
        const aliveIds = new Set(ns.map((n) => n.id));
        const es: Edge[] = (g.edges ?? [])
          .filter((e) => aliveIds.has(e.source) && aliveIds.has(e.target))
          .map((e) => ({
            ...e, animated: true, type: 'smoothstep',
            style: { stroke: '#f59e0b', strokeWidth: 2 },
            label: t('islandFlow.edge.label'),
            labelStyle: { fill: '#f59e0b', fontSize: 10 },
            labelBgStyle: { fill: '#12161d' },
          }));
        setEdges(es);
      })
      .catch(() => {
        // 拉不到就按拓扑坐标铺开
        if (!alive) return;
        setNodes(devs.map((d, i) => ({
          id: `dev-${d.resourceId}`,
          type: 'deviceNode',
          position: {
            x: d.x != null ? Number(d.x) : 40 + (i % 4) * 200,
            y: d.y != null ? Number(d.y) : 40 + Math.floor(i / 4) * 120,
          },
          data: {
            resourceId: d.resourceId, code: d.code, name: d.name,
            capability: d.capability, ip: d.ip,
          },
        })));
        setEdges([]);
      });
    return () => { alive = false; };
  }, [island, setNodes, setEdges]);

  /**
   * 自动串成一条链：按画布上的 x（再 y）排序，依次连起来。
   *
   * <p>手工拖箭头在演示时容易连错，先用这个把"一条动线"建出来，
   * 再让人手工微调。
   */
  const autoChain = () => {
    if (nodes.length < 2) { message.warning(t('islandFlow.warn.tooFewDevices')); return; }
    const sorted = [...nodes].sort((a, b) =>
      a.position.x - b.position.x || a.position.y - b.position.y);
    const es: Edge[] = [];
    for (let i = 0; i < sorted.length - 1; i++) {
      es.push({
        id: `auto-${i}`,
        source: sorted[i].id,
        target: sorted[i + 1].id,
        animated: true,
        type: 'smoothstep',
        style: { stroke: '#f59e0b', strokeWidth: 2 },
        label: t('islandFlow.edge.label'),
        labelStyle: { fill: '#f59e0b', fontSize: 10 },
        labelBgStyle: { fill: '#12161d' },
      });
    }
    setEdges(es);
    message.success(t('islandFlow.toast.chained', { n: sorted.length }));
  };

  /** 存动线：设备和箭头的当前状态。改动后 600ms 自动存。 */
  const saveTimer = useRef<number | null>(null);
  const flushSave = useCallback(() => {
    if (!island || !canWrite) return;   // 只读账号的拖动不产生 PUT（连自动保存也不能写）
    if (saveTimer.current) window.clearTimeout(saveTimer.current);
    saveTimer.current = window.setTimeout(() => {
      apiFetch(`/api/islands/${island.id}/flow`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          nodes: nodes.map((n) => ({ id: n.id, position: n.position })),
          edges: edges.map((e) => ({ id: e.id, source: e.source, target: e.target })),
        }),
      }).then(() => setSavedAt(new Date()))
        .catch(() => { /* 存失败不打断操作，下次改动会再试 */ });
    }, 600);
  }, [island, nodes, edges, canWrite]);

  useEffect(() => { flushSave(); }, [flushSave]);

  const onConnect = useCallback((c: Connection) => {
    setEdges((es) => addEdge({
      ...c, animated: true, type: 'smoothstep',
      style: { stroke: '#f59e0b', strokeWidth: 2 },
      label: t('islandFlow.edge.label'),
      labelStyle: { fill: '#f59e0b', fontSize: 10 },
      labelBgStyle: { fill: '#12161d' },
    }, es));
  }, [setEdges]);

  /** 按箭头顺序得出路径：从没有入边的节点出发，顺着 source→target 走。 */
  const path = useMemo(() => {
    if (nodes.length === 0 || edges.length === 0) return [] as number[];
    const incoming = new Set(edges.map((e) => e.target));
    const start = nodes.find((n) => !incoming.has(n.id));
    if (!start) return [];
    const seq: number[] = [];
    let cur: Node<DevData> | undefined = start;
    const seen = new Set<string>();
    while (cur && !seen.has(cur.id)) {
      seen.add(cur.id);
      seq.push(cur.data.resourceId);
      const next: Edge | undefined = edges.find((e) => e.source === cur!.id);
      cur = next ? nodes.find((n) => n.id === next!.target) : undefined;
    }
    return seq;
  }, [nodes, edges]);

  /** 执行：按路径生成一条搬运流程并跑，然后回放板子。 */
  const run = async () => {
    if (path.length < 2) {
      message.warning(t('islandFlow.warn.needTwoDevices'));
      return;
    }
    setRunning(true);
    try {
      // 每台设备取一个"放板/搬板"方法：有 Deposit 用 Deposit，否则用第一个方法
      const steps: Array<{ resourceId: number; method: string }> = [];
      for (const rid of path) {
        const ms = await apiFetch(`/api/devices/${rid}/methods`)
          .then((x) => x.json()) as { items?: Array<{ method: string }> };
        const names = (ms.items ?? []).map((m) => m.method);
        const pick = names.find((n) => /^(Deposit|Input|Put|Load)/.test(n))
          ?? names[0];
        if (pick) steps.push({ resourceId: rid, method: pick });
      }
      if (steps.length < 2) {
        message.warning(t('islandFlow.warn.noMethod'));
        return;
      }

      const ch = await apiFetch('/api/choreography', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: t('islandFlow.flowName', { name: island?.name ?? t('island.title.island'), time: new Date().toLocaleTimeString() }),
          steps,
        }),
      }).then((x) => x.json());

      if (!ch.workflowId) {
        message.error(ch.error ?? t('islandFlow.error.createFailed'));
        return;
      }
      setLastWf(ch.workflowId);

      const r = await apiFetch(`/api/workflows/${ch.workflowId}/run`, { method: 'POST' })
        .then((x) => x.json());
      if (r.error) {
        message.error(r.error);
        return;
      }
      message.success(t('islandFlow.toast.execDone', { done: r.completed, total: r.total, id: ch.workflowId, n: steps.length }));
    } catch (e) {
      message.error(String((e as Error).message));
    } finally {
      setRunning(false);
    }
  };

  return (
    <Row gutter={12} style={{ height: 'calc(100vh - 120px)' }}>
      <Col flex="auto">
        <Card size="small" style={{ height: '100%' }}
          styles={{ body: { height: 'calc(100% - 42px)', padding: 0 } }}
          title={<Space>
            <span>{t('islandFlow.title')}</span>
            <Select
              size="small" showSearch style={{ width: 280 }}
              placeholder={t('islandFlow.placeholder.pickIsland')}
              value={islandId ?? undefined}
              onChange={setIslandId}
              optionFilterProp="label"
              options={islands.map((i) => ({
                value: i.id, label: t('islandFlow.islandOption', { code: i.code, name: i.name, n: i.deviceCount }),
              }))}
            />
            {path.length > 0 && (
              <Tag color="orange">{t('islandFlow.tag.path', { n: path.length })}</Tag>
            )}
            {lastWf && <Tag color="blue">#{lastWf}</Tag>}
            {savedAt && (
              <Typography.Text type="secondary" style={{ fontSize: 10 }}>
                {t('islandFlow.savedAt', { time: savedAt.toLocaleTimeString() })}
              </Typography.Text>
            )}
          </Space>}
          extra={<Space>
            <Button size="small" onClick={() => nav('/experiments')}>{t('islandFlow.action.back')}</Button>
            <Button size="small" {...ro} onClick={autoChain}>{t('islandFlow.action.autoChain')}</Button>
            <Button size="small" type="primary" loading={running} {...ro} onClick={run}>
              {t('islandFlow.action.runByArrows')}
            </Button>
          </Space>}>
          <div style={{ width: '100%', height: '100%' }}>
            {nodes.length === 0 ? (
              <Empty description={t('islandFlow.empty.pickIsland')} style={{ paddingTop: 80 }} />
            ) : (
              <ReactFlow
                nodes={nodes} edges={edges}
                onNodesChange={onNodesChange}
                onEdgesChange={onEdgesChange}
                onConnect={onConnect}
                nodesDraggable={canWrite}
                nodesConnectable={canWrite}
                nodeTypes={NODE_TYPES}
                fitView
                style={{ background: '#0d1014' }}
              >
                <Background color="#262c38" gap={16} />
                <Controls />
                <MiniMap style={{ background: '#12161d' }} />
              </ReactFlow>
            )}
          </div>
        </Card>
      </Col>

      <Col flex="280px">
        <Card size="small" title={t('islandFlow.card.howTo')} style={{ height: '100%' }}>
          <Space direction="vertical" size={10} style={{ width: '100%' }}>
            <Alert type="info" showIcon
              message={t('islandFlow.step1.title')}
              description={t('islandFlow.step1.desc')} />
            <Alert type="warning" showIcon
              message={t('islandFlow.step2.title')}
              description={t('islandFlow.step2.desc')} />
            <Alert type="success" showIcon
              message={t('islandFlow.step3.title')}
              description={t('islandFlow.step3.desc')} />

            {path.length > 0 && (
              <div>
                <Typography.Text style={{ fontSize: 11 }}>{t('islandFlow.currentPath')}</Typography.Text>
                <div style={{ fontSize: 11, fontFamily: 'monospace', color: '#f59e0b' }}>
                  {path.map((rid) => {
                    const n = nodes.find((x) => x.data.resourceId === rid);
                    return n?.data.code ?? rid;
                  }).join(' → ')}
                </div>
              </div>
            )}
          </Space>
        </Card>
      </Col>
    </Row>
  );
}
