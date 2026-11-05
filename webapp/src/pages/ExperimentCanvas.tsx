import { useCallback, useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import ReactFlow, {
  Background, Controls, Handle, MarkerType, MiniMap, Position, addEdge,
  useEdgesState, useNodesState,
  type Connection, type Edge, type Node, type NodeProps,
} from 'reactflow';
import 'reactflow/dist/style.css';
import {
  Alert, Button, Card, Col, Empty, Input, Row, Select, Space,
  Tag, Typography, message,
} from 'antd';
import { apiFetch } from '../api/client';
import { useReadOnly } from '../auth';
import { useT } from '../i18n';
import type { MessageKey } from '../i18n/core';

/**
 * 节点类型：实验画布只做「跨流程编排」——开始 / 结束 / 流程。
 *
 * <p>流程内的条件、并行、循环、变量与设备方法一律归流程设计器
 * （`/designer/:id`，bpmn-js）；画布不再提供「存不下/跑不了」的节点。
 * 分层口径与实施路线见 `docs/设计-逻辑节点分层与编辑器分工.md`。
 */
type NodeKind = 'start' | 'end' | 'workflow';

interface NodeData {
  kind: NodeKind;
  label: string;
  workflowId?: number;
}

// 文案走文案包（key）：颜色是固定映射，标题/说明随语种变
const KIND_META: Record<NodeKind, {
  titleKey: MessageKey; color: string; hintKey: MessageKey;
}> = {
  workflow: { titleKey: 'experimentCanvas.kind.workflow.title', color: '#3b82f6', hintKey: 'experimentCanvas.kind.workflow.hint' },
  start: { titleKey: 'experimentCanvas.kind.start.title', color: '#22c55e', hintKey: 'experimentCanvas.kind.start.hint' },
  end: { titleKey: 'experimentCanvas.kind.end.title', color: '#64748b', hintKey: 'experimentCanvas.kind.end.hint' },
};

/** 通用节点外观：一个色条 + 标题 + 说明 + 上下连接点。 */
function KindNode({ data, selected }: NodeProps<NodeData>) {
  const t = useT();
  const meta = KIND_META[data.kind];
  return (
    <div style={{
      minWidth: 170, padding: '8px 10px', borderRadius: 8,
      background: '#12161d',
      border: `2px solid ${selected ? meta.color : '#262c38'}`,
      boxShadow: selected ? `0 0 0 3px ${meta.color}33` : 'none',
      fontFamily: 'system-ui, sans-serif',
    }}>
      {data.kind !== 'start' && (
        <Handle type="target" position={Position.Top} style={{ background: meta.color }} />
      )}
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <span style={{
          width: 8, height: 8, borderRadius: 2, background: meta.color,
        }} />
        <span style={{ fontSize: 10, color: meta.color, fontWeight: 700 }}>
          {t(meta.titleKey)}
        </span>
      </div>
      <div style={{ fontSize: 12, marginTop: 2 }}>{data.label || t(meta.hintKey)}</div>
      {data.kind !== 'end' && (
        <Handle type="source" position={Position.Bottom}
          style={{ background: meta.color }} />
      )}
    </div>
  );
}

/** 边的统一样式：带箭头（表示执行方向）+ 可点击删除。 */
const EDGE_STYLE = {
  animated: true,
  style: { stroke: '#3b82f6', strokeWidth: 2 },
  markerEnd: { type: MarkerType.ArrowClosed, color: '#3b82f6', width: 18, height: 18 },
};

/** 把任意边补成带箭头的样子。 */
const withArrow = (e: Edge, t: (k: MessageKey) => string): Edge => ({
  ...e,
  ...EDGE_STYLE,
  // 边加一个可点的删除按钮（React Flow 支持 edge label 里的交互）
  label: t('experimentCanvas.edge.deleteHint'),
  labelShowBg: true,
  labelBgStyle: { fill: '#1a1f27', fillOpacity: 0.9 },
  labelStyle: { fill: '#8b93a7', fontSize: 9, cursor: 'pointer' },
  labelBgPadding: [4, 2] as [number, number],
  labelBgBorderRadius: 4,
  data: { ...(e.data ?? {}), deletable: true },
});

const NODE_TYPES = { kindNode: KindNode };

/** 左侧面板的拖拽来源：实验画布只提供「流程」一种节点。 */
const PALETTE: Array<{ kind: NodeKind; labelKey: MessageKey }> = [
  { kind: 'workflow', labelKey: 'experimentCanvas.palette.workflow' },
];

export default function ExperimentCanvasPage() {
  const t = useT();
  const { id } = useParams();
  const nav = useNavigate();
  // 画布可以只读浏览（拖节点只改本地 state），但"保存"会落库，viewer 禁用
  const ro = useReadOnly();
  // 没带 id 时用上次打开的（或第一个实验）——让"实验画布"能作为独立入口
  const [expId, setExpId] = useState<number>(() => {
    const fromUrl = Number(id);
    if (Number.isFinite(fromUrl) && fromUrl > 0) return fromUrl;
    const saved = Number(localStorage.getItem('labos_canvas_exp'));
    return Number.isFinite(saved) && saved > 0 ? saved : 0;
  });
  const [expList, setExpList] = useState<Array<{ id: number; experimentNo: string; name: string }>>([]);

  useEffect(() => {
    if (expId > 0) localStorage.setItem('labos_canvas_exp', String(expId));
  }, [expId]);

  // 拉实验列表（顶部可切换）
  useEffect(() => {
    apiFetch('/api/experiments?limit=100')
      .then((r) => r.json())
      .then((d: { items?: Array<{ id: number; experimentNo: string; name: string }> }) => {
        const list = d.items ?? [];
        setExpList(list);
        setExpId((prev) => (prev > 0 ? prev : (list[0]?.id ?? 0)));
      })
      .catch(() => { /* 拉不到就不显示下拉 */ });
  }, []);

  const [nodes, setNodes, onNodesChange] = useNodesState<NodeData>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState([]);
  const [selected, setSelected] = useState<Node<NodeData> | null>(null);
  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const [loaded, setLoaded] = useState(false);

  const [wfList, setWfList] = useState<Array<{ id: number; name: string; code: string }>>([]);

  // 载入流程图
  useEffect(() => {
    if (!expId) return;
    let alive = true;
    Promise.all([
      apiFetch(`/api/experiments/${expId}/graph`)
        .then((r) => r.json()) as Promise<{ nodes?: Node<NodeData>[]; edges?: Edge[] }>,
      apiFetch(`/api/experiments/${expId}/processes`)
        .then((r) => r.json()) as Promise<{
          items?: Array<{ seq: number; workflowId: number; workflowName: string; workflowCode: string }>;
        }>,
    ])
      .then(([g, p]) => {
        if (!alive) return;
        const loadedNodes = (g.nodes ?? []).map((n) => ({ ...n, type: 'kindNode' }));
        if (loadedNodes.length === 0) {
          const procItems = [...(p.items ?? [])].sort((a, b) => a.seq - b.seq);
          if (procItems.length > 0) {
            const seedNodes: Node<NodeData>[] = [
              {
                id: 'start-1',
                type: 'kindNode',
                position: { x: 240, y: -60 },
                data: { kind: 'start', label: t('experimentCanvas.node.start') },
              },
              ...procItems.map((one, i) => ({
                id: `wf-${one.seq}-${one.workflowId}`,
                type: 'kindNode' as const,
                position: { x: 240, y: 80 + i * 140 },
                data: {
                  kind: 'workflow' as const,
                  workflowId: one.workflowId,
                  label: one.workflowName || one.workflowCode || `#${one.workflowId}`,
                },
              })),
              {
                id: 'end-1',
                type: 'kindNode',
                position: { x: 240, y: 80 + procItems.length * 140 },
                data: { kind: 'end', label: t('experimentCanvas.node.end') },
              },
            ];
            const seedEdges: Edge[] = [];
            for (let i = 0; i < seedNodes.length - 1; i += 1) {
              seedEdges.push(withArrow({
                id: `e-${seedNodes[i].id}-${seedNodes[i + 1].id}`,
                source: seedNodes[i].id,
                target: seedNodes[i + 1].id,
              } as Edge, t));
            }
            setNodes(seedNodes);
            setEdges(seedEdges);
          } else {
            // 新实验：自动放"开始"和"结束"，并连起来
            setNodes([
              { id: 'start-1', type: 'kindNode',
                position: { x: 240, y: -60 },
                data: { kind: 'start', label: t('experimentCanvas.node.start') } },
              { id: 'end-1', type: 'kindNode',
                position: { x: 240, y: 420 },
                data: { kind: 'end', label: t('experimentCanvas.node.end') } },
            ] as Node<NodeData>[]);
            setEdges([withArrow({
              id: 'e-start-end', source: 'start-1', target: 'end-1',
            } as Edge, t)]);
          }
        } else {
          // 现有图可能没有开始/结束，补上——不然用户找不到编排的起止
          const hasStart = loadedNodes.some((n) => n.data.kind === 'start');
          const hasEnd = loadedNodes.some((n) => n.data.kind === 'end');
          const extra: Node<NodeData>[] = [];
          if (!hasStart) {
            extra.push({
              id: 'start-1', type: 'kindNode',
              position: { x: 240, y: -200 },
              data: { kind: 'start', label: t('experimentCanvas.node.start') },
            });
          }
          if (!hasEnd) {
            const bottom = loadedNodes.reduce((m, n) => Math.max(m, n.position.y), 0);
            extra.push({
              id: 'end-1', type: 'kindNode',
              position: { x: 240, y: bottom + 140 },
              data: { kind: 'end', label: t('experimentCanvas.node.end') },
            });
          }
          setNodes([...extra, ...loadedNodes]);
          setEdges((g.edges ?? []).map((e) => withArrow(e, t)));
        }
        setLoaded(true);
      })
      .catch(() => setLoaded(true));

    // 顺便拉流程库（节点选流程用）
    apiFetch('/api/workflows?limit=200')
      .then((r) => r.json())
      .then((d: { items?: Array<{ id: number; name: string; code: string }> }) => {
        if (alive) setWfList(d.items ?? []);
      })
      .catch(() => { /* 流程列表拉不到不影响画布 */ });
    return () => { alive = false; };
  }, [expId, setNodes, setEdges]);

  const onConnect = useCallback((c: Connection) => {
    setEdges((eds) => addEdge(withArrow({ ...c, id: `e-${Date.now()}` } as Edge, t), eds));
    setDirty(true);
  }, [setEdges, t]);

  /** 从左侧面板拖入。 */
  const onDrop = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    const bounds = (e.currentTarget as HTMLElement).getBoundingClientRect();
    const px = e.clientX - bounds.left - 90;
    const py = e.clientY - bounds.top - 30;

    const kind = e.dataTransfer.getData('application/x-node-kind') as NodeKind;
    if (!kind) return;
    setNodes((ns) => [...ns, {
      id: `${kind}-${Date.now()}`,
      type: 'kindNode',
      position: { x: px, y: py },
      data: { kind, label: t(KIND_META[kind].hintKey) },
    }]);
    setDirty(true);
  }, [setNodes, t]);

  const patchSelected = (patch: Partial<NodeData>) => {
    if (!selected) return;
    setNodes((ns) => ns.map((n) => (n.id === selected.id
      ? { ...n, data: { ...n.data, ...patch } }
      : n)));
    setSelected((s) => (s ? { ...s, data: { ...s.data, ...patch } } : s));
    setDirty(true);
  };

  const save = async () => {
    if (!expId) { message.warning(t('experimentCanvas.warn.pickExperiment')); return; }
    setSaving(true);
    try {
      const r = await apiFetch(`/api/experiments/${expId}/graph`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ nodes, edges }),
      });
      const j = await r.json();
      if (!r.ok) throw new Error(j.error ?? t('experimentCanvas.error.saveFailed'));
      message.success(t('experimentCanvas.toast.savedAndCompiled', {
        n: j.compiledProcesses ?? 0, nodes: nodes.length, edges: edges.length,
      }));
      setDirty(false);
    } catch (e) {
      message.error(String((e as Error).message));
    } finally {
      setSaving(false);
    }
  };

  const deleteSelected = () => {
    if (!selected) return;
    setNodes((ns) => ns.filter((n) => n.id !== selected.id));
    setEdges((es) => es.filter((e) => e.source !== selected.id && e.target !== selected.id));
    setSelected(null);
    setDirty(true);
  };

  return (
    <>
      {/* 统一入口已落地：画布保存时同时更新 graph + process。 */}
      <Alert
        type="info" showIcon style={{ marginBottom: 8 }}
        message={t('experimentCanvas.alert.saveTitle')}
        description={t('experimentCanvas.alert.saveDesc')}
      />
    <Row gutter={12} style={{ height: 'calc(100vh - 160px)' }}>
      {/* 左：节点面板 */}
      <Col flex="170px">
        <Card size="small" title={t('experimentCanvas.card.nodes')} style={{ height: '100%', overflow: 'auto' }}>
          <Typography.Paragraph type="secondary" style={{ fontSize: 10,
            marginBottom: 4 }}>
            {t('experimentCanvas.hint.palette')}
          </Typography.Paragraph>
          <Space direction="vertical" size={6} style={{ width: '100%' }}>
            {PALETTE.map((p) => {
              const meta = KIND_META[p.kind];
              return (
                <div key={p.kind} draggable
                  onDragStart={(e) => {
                    e.dataTransfer.setData('application/x-node-kind', p.kind);
                    e.dataTransfer.effectAllowed = 'copy';
                  }}
                  style={{
                    padding: '6px 8px', borderRadius: 6, cursor: 'grab',
                    border: `1px solid ${meta.color}55`, background: `${meta.color}14`,
                    fontSize: 12,
                  }}>
                  <span style={{ color: meta.color, fontWeight: 700 }}>{t(meta.titleKey)}</span>
                  <div style={{ fontSize: 10, color: '#8b93a7' }}>{t(meta.hintKey)}</div>
                </div>
              );
            })}
          </Space>
          <Typography.Paragraph type="secondary" style={{ fontSize: 10, marginTop: 12 }}>
            {t('experimentCanvas.hint.logicElsewhere')}
          </Typography.Paragraph>
        </Card>
      </Col>

      {/* 中：画布 */}
      <Col flex="auto">
        <Card size="small" style={{ height: '100%' }}
          styles={{ body: { height: 'calc(100% - 42px)', padding: 0 } }}
          title={<Space>
            <span>{t('experimentCanvas.title')}</span>
            <Select
              size="small" showSearch style={{ width: 260 }}
              placeholder={t('experimentCanvas.placeholder.pickExperiment')}
              value={expId || undefined}
              onChange={(v) => { setExpId(v); setDirty(false); }}
              optionFilterProp="label"
              options={expList.map((e) => ({
                value: e.id, label: `${e.experimentNo} ${e.name}`,
              }))}
            />
            {dirty && <Tag color="orange">{t('experimentCanvas.tag.dirty')}</Tag>}
          </Space>}
          extra={<Space>
            <Button size="small" onClick={() => nav('/experiments')}>{t('experimentCanvas.action.back')}</Button>
            <Button size="small" type="primary" loading={saving} {...ro} onClick={save}>
              {t('experimentCanvas.action.save')}
            </Button>
          </Space>}>
          <div style={{ width: '100%', height: '100%' }}
            onDrop={onDrop}
            onDragOver={(e) => { e.preventDefault(); e.dataTransfer.dropEffect = 'copy'; }}>
            {!loaded ? <Empty description={t('experimentCanvas.loadingShort')} /> : (
              <ReactFlow
                nodes={nodes} edges={edges}
                onNodesChange={(c) => { onNodesChange(c); setDirty(true); }}
                onEdgesChange={(c) => { onEdgesChange(c); setDirty(true); }}
                onConnect={onConnect}
                onEdgesDelete={() => setDirty(true)}
                deleteKeyCode={['Backspace', 'Delete']}
                onEdgeClick={(_, e) => {
                  // 单击选中（方便按 Delete），双击直接删
                  setEdges((es) => es.map((x) => ({ ...x, selected: x.id === e.id })));
                }}
                onEdgeDoubleClick={(_, e) => {
                  setEdges((es) => es.filter((x) => x.id !== e.id));
                  setDirty(true);
                  message.success(t('experimentCanvas.toast.edgeDeleted'));
                }}
                onNodeClick={(_, n) => setSelected(n)}
                onPaneClick={() => setSelected(null)}
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

      {/* 右：属性 */}
      <Col flex="260px">
        <Card size="small" title={t('experimentCanvas.card.properties')} style={{ height: '100%' }}>
          {!selected ? (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {t('experimentCanvas.hint.properties')}
            </Typography.Text>
          ) : (
            <Space direction="vertical" size={10} style={{ width: '100%' }}>
              <div>
                <Typography.Text style={{ fontSize: 11 }}>{t('experimentCanvas.field.type')}</Typography.Text>
                <div>
                  <Tag color={KIND_META[selected.data.kind].color}>
                    {t(KIND_META[selected.data.kind].titleKey)}
                  </Tag>
                </div>
              </div>

              <div>
                <Typography.Text style={{ fontSize: 11 }}>{t('experimentCanvas.field.name')}</Typography.Text>
                <Input size="small" value={selected.data.label}
                  onChange={(e) => patchSelected({ label: e.target.value })} />
              </div>

              {selected.data.kind === 'workflow' && (
                <div>
                  <Typography.Text style={{ fontSize: 11 }}>{t('experimentCanvas.field.pickWorkflow')}</Typography.Text>
                  <Select
                    size="small" showSearch style={{ width: '100%' }}
                    placeholder={t('experimentCanvas.placeholder.searchWorkflow')}
                    optionFilterProp="label"
                    value={selected.data.workflowId}
                    onChange={(v) => {
                      const wf = wfList.find((w) => w.id === v);
                      patchSelected({ workflowId: v, label: wf?.name ?? String(v) });
                    }}
                    options={wfList.map((w) => ({
                      value: w.id, label: `${w.name} (${w.code})`,
                    }))}
                  />
                </div>
              )}

              <Button size="small" danger {...ro} onClick={deleteSelected}>{t('experimentCanvas.action.deleteNode')}</Button>

              <Alert type="info" showIcon style={{ fontSize: 10 }}
                message={t('experimentCanvas.hint.edge')} />
            </Space>
          )}
        </Card>
      </Col>
    </Row>
    </>
  );
}
