import { useCallback, useEffect, useMemo, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import ReactFlow, {
  Background, Controls, Handle, MarkerType, MiniMap, Position, addEdge,
  useEdgesState, useNodesState,
  type Connection, type Edge, type Node, type NodeProps,
} from 'reactflow';
import 'reactflow/dist/style.css';
import {
  Alert, Button, Card, Col, Empty, Input, InputNumber, Row, Select, Space,
  Tag, Typography, message,
} from 'antd';
import { apiFetch } from '../api/client';
import { useReadOnly } from '../auth';
import { useT } from '../i18n';
import type { MessageKey } from '../i18n/core';

/** 节点类型：设备流程 / 变量 / 分叉 / 并行 / 循环。 */
type NodeKind = 'start' | 'end' | 'join' | 'workflow' | 'variable' | 'fork' | 'parallel' | 'loop' | 'device';

interface NodeData {
  kind: NodeKind;
  label: string;
  workflowId?: number;
  /** 变量名或循环次数表达式 */
  value?: string;
  /** 分叉条件（如 "${temp} > 37"） */
  condition?: string;
  /** 设备节点用：资源 id、能力、IP */
  resourceId?: number;
  capability?: string;
  /** 设备示意图；没有就退回"按能力名猜"的老路径 */
  icon?: string | null;
  ip?: string | null;
  /** 设备节点用：执行哪个方法（留空则自动选第一个 Deposit 类） */
  method?: string;
  /** 设备节点用：方法参数 {name: value}，值支持 ${var} */
  params?: Record<string, string>;
  /** 设备节点用：把返回值里的某个字段存到变量。
   *  格式 "plateId=plate"，表示取返回 JSON 的 plateId 存进 ${plate}。 */
  output?: string;
}

/** 有真实照片的能力（Wikimedia CC 授权，存在 public/devices/）。 */


// 文案走文案包（key）：颜色是固定映射，标题/说明随语种变
const KIND_META: Record<NodeKind, {
  titleKey: MessageKey; color: string; hintKey: MessageKey;
}> = {
  workflow: { titleKey: 'experimentCanvas.kind.workflow.title', color: '#3b82f6', hintKey: 'experimentCanvas.kind.workflow.hint' },
  variable: { titleKey: 'experimentCanvas.kind.variable.title', color: '#a855f7', hintKey: 'experimentCanvas.kind.variable.hint' },
  fork: { titleKey: 'experimentCanvas.kind.fork.title', color: '#f59e0b', hintKey: 'experimentCanvas.kind.fork.hint' },
  parallel: { titleKey: 'experimentCanvas.kind.parallel.title', color: '#10b981', hintKey: 'experimentCanvas.kind.parallel.hint' },
  loop: { titleKey: 'experimentCanvas.kind.loop.title', color: '#ec4899', hintKey: 'experimentCanvas.kind.loop.hint' },
  device: { titleKey: 'experimentCanvas.kind.device.title', color: '#0ea5e9', hintKey: 'experimentCanvas.kind.device.hint' },
  start: { titleKey: 'experimentCanvas.kind.start.title', color: '#22c55e', hintKey: 'experimentCanvas.kind.start.hint' },
  end: { titleKey: 'experimentCanvas.kind.end.title', color: '#64748b', hintKey: 'experimentCanvas.kind.end.hint' },
  join: { titleKey: 'experimentCanvas.kind.join.title', color: '#14b8a6', hintKey: 'experimentCanvas.kind.join.hint' },
};

/** 通用节点外观：一个色条 + 标题 + 说明 + 上下连接点。 */
function KindNode({ data, selected }: NodeProps<NodeData>) {
  const t = useT();
  const meta = KIND_META[data.kind];
  const multi = data.kind === 'fork' || data.kind === 'parallel';
  // 形状区分：分叉=菱形边（切角），并行=双线，循环=左侧竖条，其余圆角
  const shapeStyle: React.CSSProperties =
    data.kind === 'fork'
      ? { clipPath: 'polygon(12px 0, 100% 0, 100% calc(100% - 12px), calc(100% - 12px) 100%, 0 100%, 0 12px)' }
      : data.kind === 'join'
        ? { clipPath: 'polygon(0 12px, 12px 0, calc(100% - 12px) 0, 100% 12px, 100% 100%, 0 100%)' }
      : data.kind === 'parallel'
        ? { borderStyle: 'double', borderWidth: 4 }
        : data.kind === 'loop'
          ? { borderLeftWidth: 6 }
          : {};
  return (
    <div style={{
      minWidth: 170, padding: '8px 10px', borderRadius: 8,
      background: '#12161d',
      border: `2px solid ${selected ? meta.color : '#262c38'}`,
      boxShadow: selected ? `0 0 0 3px ${meta.color}33` : 'none',
      fontFamily: 'system-ui, sans-serif',
      ...shapeStyle,
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
      {data.kind === 'device' && (
        <div style={{ fontSize: 12, fontWeight: 600, marginTop: 2 }}>
          {data.label || t(meta.hintKey)}
        </div>
      )}
      {data.kind === 'device' && data.icon ? (
        <img
          // 用设备池登记的示意图。**没有就不画** ——
          // 原来按能力名猜 /devices/<CAP>.jpg，那些文件根本不存在，
          // 每个设备节点都白打一次 404，然后留一块空白。
          src={data.icon}
          alt={data.label}
          style={{
            width: '100%', height: 44, objectFit: 'cover',
            borderRadius: 4, marginTop: 4, display: 'block',
            border: `1px solid ${meta.color}44`,
          }}
          onError={(e) => { (e.currentTarget as HTMLImageElement).style.display = 'none'; }}
        />
      ) : null}
      {data.kind === 'device' && (
        <div style={{ fontSize: 9, color: '#5b6376', fontFamily: 'monospace', marginTop: 2 }}>
          {data.capability ?? '—'} · {data.ip ?? t('experimentCanvas.device.noIp')}
        </div>
      )}
      {data.kind !== 'device' && (
        <div style={{ fontSize: 12, marginTop: 2 }}>{data.label || t(meta.hintKey)}</div>
      )}
      {data.value && (
        <div style={{ fontSize: 10, color: '#8b93a7', marginTop: 2 }}>
          {data.value}
        </div>
      )}
      {data.condition && (
        <div style={{ fontSize: 10, color: '#f59e0b', marginTop: 2 }}>
          {data.condition}
        </div>
      )}
      {data.kind !== 'end' && (
        <Handle type="source" position={Position.Bottom}
          style={{ background: meta.color }} />
      )}
      {multi && (
        <>
          <Handle id="right" type="source" position={Position.Right}
            style={{ background: meta.color }} />
          <Handle id="left" type="target" position={Position.Left}
            style={{ background: meta.color }} />
        </>
      )}
      {data.kind === 'join' && (
        <>
          <Handle id="left" type="target" position={Position.Left}
            style={{ background: meta.color }} />
          <Handle id="right" type="target" position={Position.Right}
            style={{ background: meta.color }} />
        </>
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

/** 左侧面板的拖拽来源。 */
const PALETTE: Array<{ kind: NodeKind; labelKey: MessageKey }> = [
  { kind: 'workflow', labelKey: 'experimentCanvas.palette.workflow' },
  { kind: 'variable', labelKey: 'experimentCanvas.palette.variable' },
  { kind: 'fork', labelKey: 'experimentCanvas.palette.fork' },
  { kind: 'parallel', labelKey: 'experimentCanvas.palette.parallel' },
  { kind: 'loop', labelKey: 'experimentCanvas.palette.loop' },
  { kind: 'join', labelKey: 'experimentCanvas.palette.join' },
];

interface Island { id: number; code: string; name: string; deviceCount: number; devices?: DevRow[] }
interface DevRow {
  resourceId: number; code: string; name: string; capability?: string;
  icon?: string | null;
  ip?: string | null; x?: number | null; y?: number | null;
}

/**
 * 极简数据加载 hook：url 为 null 时不请求。
 *
 * <p>不用 react-query 是因为这里只需要"拿到一次"，切换方法时重新拉，
 * 引入额外依赖不划算。
 */
function useQueryLike<T>(url: string | null) {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(false);
  useEffect(() => {
    if (!url) { setData(null); return; }
    let alive = true;
    setLoading(true);
    apiFetch(url)
      .then((r) => r.json())
      .then((d: T) => { if (alive) setData(d); })
      .catch(() => { if (alive) setData(null); })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, [url]);
  return { data, loading };
}

/**
 * 极简条件求值：支持 `${var} > 37`、`${n} == 3`、`${ok} == true`。
 *
 * <p>不用 `eval`：画布内容来自用户输入，eval 等于把浏览器交给别人。
 * 只支持"左值 运算符 右值"这一种形式，够表达"温度够了没""板数齐了没"。
 */
function evalCondition(expr: string | undefined, vars: Record<string, string>): boolean {
  if (!expr || !expr.includes(' ')) return true;   // 没写条件就当通过
  const m = expr.match(/^\s*(.+?)\s*(>=|<=|==|!=|>|<)\s*(.+?)\s*$/);
  if (!m) return true;

  const num = (s2: string): number | null => {
    const resolved = s2.replace(/\$\{([A-Za-z_][A-Za-z0-9_]*)}/g, (_, k) => vars[k] ?? '');
    const n = Number(resolved);
    return Number.isNaN(n) ? null : n;
  };
  const a = num(m[1]);
  const b = num(m[3]);

  if (a != null && b != null) {
    switch (m[2]) {
      case '>': return a > b;
      case '<': return a < b;
      case '>=': return a >= b;
      case '<=': return a <= b;
      case '==': return a === b;
      case '!=': return a !== b;
      default: return true;
    }
  }
  // 非数字按字符串比（== / != 才有意义）
  const sa = m[1].replace(/\$\{([A-Za-z_][A-Za-z0-9_]*)}/g, (_, k) => vars[k] ?? '');
  const sb = m[3];
  if (m[2] === '==') return sa === sb;
  if (m[2] === '!=') return sa !== sb;
  return true;
}

export default function ExperimentCanvasPage() {
  const t = useT();
  const { id } = useParams();
  const nav = useNavigate();
  // 画布可以只读浏览（拖节点只改本地 state），但"执行/保存"会落库，viewer 禁用
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
  /** 示教输入：x/y/z */
  const [teach, setTeach] = useState<{ x?: string; y?: string; z?: string }>({});
  const [islands, setIslands] = useState<Island[]>([]);
  const [islandId, setIslandId] = useState<number | null>(null);

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
          // 老图可能没有开始/结束，补上——不然用户找不到执行入口
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
    // 拉岛屿（设备面板用）
    apiFetch('/api/topology')
      .then((r) => r.json())
      .then((d: { islands?: Island[] }) => {
        if (!alive) return;
        const list = d.islands ?? [];
        setIslands(list);
        const laidOut = (i: Island) =>
          (i.devices ?? []).filter((x) => x.x != null).length;
        const best = [...list].sort((a, b) => laidOut(b) - laidOut(a))[0];
        if (best) setIslandId((prev) => prev ?? best.id);
      })
      .catch(() => { /* 拉不到就没有设备面板 */ });

    // 顺便拉流程库（节点选流程用）
    apiFetch('/api/workflows?limit=200')
      .then((r) => r.json())
      .then((d: { items?: Array<{ id: number; name: string; code: string }> }) => {
        if (alive) setWfList(d.items ?? []);
      })
      .catch(() => { /* 流程列表拉不到不影响画布 */ });
    return () => { alive = false; };
  }, [expId, setNodes, setEdges]);

  // 选中设备节点且填了方法名 → 拉该方法的参数定义
  const methodParamDefs = useQueryLike(
    selected?.data.kind === 'device' && selected.data.method
      ? `/api/methods/${encodeURIComponent(selected.data.method)}/params`
      : null,
  );

  // 参数定义到了之后，用 default 回填空值（只填一次，不覆盖用户改过的）
  useEffect(() => {
    const defs = (methodParamDefs.data as {
      params?: Array<{ name: string; default?: string; control_type?: string }>;
    } | null)?.params ?? [];
    if (defs.length === 0 || !selected || selected.data.kind !== 'device') return;
    setNodes((ns) => ns.map((n) => {
      if (n.id !== selected.id) return n;
      const cur = n.data.params ?? {};
      let changed = false;
      const next = { ...cur };
      for (const d of defs) {
        const empty = next[d.name] === undefined || next[d.name] === '';
        const dv = d.default;
        // 只回填简单值，避免把 JSON 片段塞进输入框
        if (empty && dv && dv.length < 60 && !dv.includes('{')) {
          next[d.name] = dv;
          changed = true;
        }
      }
      return changed ? { ...n, data: { ...n.data, params: next } } : n;
    }));
  }, [methodParamDefs.data, selected, setNodes]);

  const islandDevices = useMemo<DevRow[]>(() => {
    const found = islands.find((i) => i.id === islandId);
    return (found?.devices ?? []) as DevRow[];
  }, [islands, islandId]);

  /** 把岛上设备加到画布（按岛屿坐标摆放，已有的不重复加）。 */
  const addIslandDevices = () => {
    const exist = new Set(nodes.map((n) => n.data.resourceId));
    // 已有节点就把设备整体往下放，避免和逻辑节点叠在一起看不清
    const bottom = nodes.reduce((m, n) => Math.max(m, n.position.y), 0);
    const offsetY = nodes.length === 0 ? 0 : bottom + 140;
    const add: Node<NodeData>[] = [];
    islandDevices.forEach((d: DevRow, i: number) => {
      if (exist.has(d.resourceId)) return;
      add.push({
        id: `dev-${d.resourceId}`,
        type: 'kindNode',
        position: {
          x: d.x != null ? Number(d.x) + 40 : 60 + (i % 4) * 200,
          y: (d.y != null ? Number(d.y) + 40 : 60 + Math.floor(i / 4) * 110) + offsetY,
        },
        data: {
          kind: 'device', label: d.code,
          resourceId: d.resourceId, capability: d.capability, ip: d.ip,
          icon: d.icon,
        },
      });
    });
    if (add.length === 0) { message.info(t('experimentCanvas.info.alreadyOnCanvas')); return; }
    setNodes((ns) => [...ns, ...add]);
    setDirty(true);
    message.success(t('experimentCanvas.toast.addedDevices', { n: add.length }));
  };

  /**
   * 把新节点插到"结束节点"前面，自动接上前后。
   *
   * <p>为什么要自动连：拖 5 台设备就要拉 6 次线，很容易漏一根，
   * 而漏一根的表现是"后面几步不执行"——看不出原因。
   *
   * <p>规则：找到指向 end 的那条边，改成指向新节点，再加一条 新节点→end。
   * 没有 end 时，直接连到"没有出边的节点"。
   */
  const autoInsert = (
    newNode: Node<NodeData>,
    curNodes: Node<NodeData>[],
    curEdges: Edge[],
  ): { nodes: Node<NodeData>[]; edges: Edge[] } => {
    const endNode = curNodes.find((n) => n.data.kind === 'end');
    if (endNode) {
      const intoEnd = curEdges.filter((e) => e.target === endNode.id);
      const rest = curEdges.filter((e) => e.target !== endNode.id);
      const newEdges: Edge[] = [
        ...rest,
        ...intoEnd.map((e) => ({ ...e, target: newNode.id })),
        {
          id: `e-${newNode.id}-end`,
          source: newNode.id,
          target: endNode.id,
          animated: true,
          style: { stroke: '#3b82f6' },
        },
      ];
      // 新节点放在 end 上方 130px，视觉上就是"插进去"
      const pos = { x: endNode.position.x, y: endNode.position.y - 130 };
      return {
        nodes: [
          ...curNodes.map((n) => (n.id === endNode.id
            ? { ...n, position: { x: n.position.x, y: n.position.y + 130 } }
            : n)),
          { ...newNode, position: pos },
        ],
        edges: newEdges,
      };
    }
    // 没有 end：接在"没有出边"的节点后面
    const sources = new Set(curEdges.map((e) => e.source));
    const tail = curNodes.find((n) => !sources.has(n.id));
    return {
      nodes: [...curNodes, newNode],
      edges: tail
        ? [...curEdges, {
            id: `e-${tail.id}-${newNode.id}`,
            source: tail.id, target: newNode.id,
            animated: true, style: { stroke: '#3b82f6' },
          }]
        : curEdges,
    };
  };

  /** 单台设备拖入：从左侧设备列表拖到画布。 */
  const onDropDevice = (rid: number, x: number, y: number) => {
    const d = islandDevices.find((x2: DevRow) => x2.resourceId === rid);
    if (!d) return;
    // 允许同一台设备出现多次：机械臂要反复搬板，一个流程里可能用 5 次
    const newNode: Node<NodeData> = {
      id: `dev-${rid}-${Date.now()}`,
      type: 'kindNode',
      position: { x, y },
      data: {
        kind: 'device', label: d.code,
        resourceId: rid, capability: d.capability, ip: d.ip,
      },
    };
    const merged = autoInsert(newNode, nodes, edges);
    setNodes(merged.nodes);
    setEdges(merged.edges);
    setDirty(true);
  };

  const onConnect = useCallback((c: Connection) => {
    setEdges((eds) => addEdge(withArrow({ ...c, id: `e-${Date.now()}` } as Edge, t), eds));
    setDirty(true);
  }, [setEdges]);

  /** 从左侧面板拖入。 */
  const onDrop = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    const bounds = (e.currentTarget as HTMLElement).getBoundingClientRect();
    const px = e.clientX - bounds.left - 90;
    const py = e.clientY - bounds.top - 30;

    // 拖的是设备
    const rid = e.dataTransfer.getData('application/x-device');
    if (rid) {
      onDropDevice(Number(rid), px, py);
      return;
    }

    // 拖的是逻辑节点
    const kind = e.dataTransfer.getData('application/x-node-kind') as NodeKind;
    if (!kind) return;
    setNodes((ns) => [...ns, {
      id: `${kind}-${Date.now()}`,
      type: 'kindNode',
      position: { x: px, y: py },
      data: { kind, label: t(KIND_META[kind].hintKey) },
    }]);
    setDirty(true);
  }, [setNodes, onDropDevice]);

  const patchSelected = (patch: Partial<NodeData>) => {
    if (!selected) return;
    setNodes((ns) => ns.map((n) => (n.id === selected.id
      ? { ...n, data: { ...n.data, ...patch } }
      : n)));
    setSelected((s) => (s ? { ...s, data: { ...s.data, ...patch } } : s));
    setDirty(true);
  };

  /**
   * 保存前校验必填参数。
   *
   * <p>执行前不校验"从没填过"的必填项（要逐台拉方法定义，太慢），
   * 放在保存时做：保存是低频操作，用户也在等，几百毫秒可接受。
   */
  const validateRequired = async (): Promise<string[]> => {
    const devs = nodes.filter((n) => n.data.kind === 'device' && n.data.method);
    const uniq = [...new Set(devs.map((n) => n.data.method!))];
    const defs = new Map<string, Array<{ name: string; required?: number }>>();
    await Promise.all(uniq.map(async (m) => {
      try {
        const r = await apiFetch(`/api/methods/${encodeURIComponent(m)}/params`)
          .then((x) => x.json()) as { params?: Array<{ name: string; required?: number }> };
        defs.set(m, r.params ?? []);
      } catch {
        defs.set(m, []);
      }
    }));

    const missing: string[] = [];
    for (const n of devs) {
      const list = defs.get(n.data.method!) ?? [];
      for (const d of list) {
        if (!d.required) continue;
        const v = n.data.params?.[d.name];
        if (v === undefined || v === null || v === '') {
          missing.push(`${n.data.label}.${d.name}`);
        }
      }
    }
    return missing;
  };

  const save = async () => {
    if (!expId) { message.warning(t('experimentCanvas.warn.pickExperiment')); return; }
    setSaving(true);
    try {
      const miss = await validateRequired();
      if (miss.length > 0) {
        message.warning(t('experimentCanvas.warn.missingParams', { names: miss.slice(0, 6).join(', ') })
          + (miss.length > 6 ? t('experimentCanvas.warn.missingParamsMore', { n: miss.length }) : ''));
        return;
      }
    } catch {
      // 校验本身失败不阻断保存（宁可存下来，也别让人白点）
    }
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

  /**
   * 按箭头顺序执行。
   *
   * <p>规则：
   * <ul>
   *   <li>变量节点：记下 {@code k=v}，后续可被引用（1.0 只做记录，不代入方法参数）</li>
   *   <li>设备节点：收集 (设备, 方法)，最后合成一条搬运流程执行</li>
   *   <li>循环/分叉/并行：1.0 先跳过（执行器还不支持）</li>
   * </ul>
   */
  const runFlow = async () => {
    if (!expId) { message.warning(t('experimentCanvas.warn.pickExperiment')); return; }
    // 拓扑：从没有入边的节点出发，顺着 source→target
    const incoming = new Set(edges.map((e) => e.target));
    let cur = nodes.find((n) => !incoming.has(n.id));
    const seen = new Set<string>();
    const steps: Array<{
      resourceId: number; method: string; output?: string;
      params?: Record<string, string>;
    }> = [];
    const vars: Record<string, string> = {};
    const skipped: string[] = [];
    const loops: string[] = [];
    const outputs: string[] = [];
    const transferNotes: string[] = [];
    let prevDevice: { code: string; capability?: string } | null = null;

    // 循环节点：记下"从哪开始重复、repeat 几次"，走到汇合点后回跳
    let loopFrom = -1;
    let loopLeft = 0;

    while (cur && !seen.has(cur.id)) {
      seen.add(cur.id);
      const d = cur.data;

      if (d.kind === 'device' && d.resourceId) {
        // 上一站不是机械臂、这一站也不是机械臂 → 中间补一次机械臂搬运
        const prevDev = prevDevice;
        const isArm = (cap?: string) => cap === 'TRANSFER';
        if (prevDev && !isArm(prevDev.capability) && !isArm(d.capability)) {
          const arm = islandDevices.find((x) => x.capability === 'TRANSFER');
          if (arm) {
            steps.push({
              resourceId: arm.resourceId,
              method: 'Transfer',
              params: { source: prevDev.code, target: d.label },
            });
            transferNotes.push(`${prevDev.code} → ${d.label}`);
          }
        }
        prevDevice = { code: d.label, capability: d.capability };
        steps.push({
          resourceId: d.resourceId,
          method: d.method ?? '',
          output: d.output,
          params: d.params,
        });
        if (d.output) outputs.push(`${d.label}: ${d.output}`);
      } else if (d.kind === 'variable' && d.value) {
        const i = d.value.indexOf('=');
        if (i > 0) {
          const k = d.value.slice(0, i).trim();
          const v0 = d.value.slice(i + 1).trim()
            .replace(/\$\{([A-Za-z_][A-Za-z0-9_]*)}/g, (_, kk) => vars[kk] ?? '');
          vars[k] = v0;
        }
      } else if (d.kind === 'loop') {
        // 循环次数：数字或 ${var}
        const raw = (d.value ?? '1')
          .replace(/\$\{([A-Za-z_][A-Za-z0-9_]*)}/g, (_, kk) => vars[kk] ?? '1');
        const n = Math.max(1, Math.min(20, Number(raw) || 1));
        loopFrom = steps.length;
        loopLeft = n;
        loops.push(t('experimentCanvas.info.loops', { n }));
      } else if (d.kind === 'fork') {
        const ok = evalCondition(d.condition, vars);
        // 走条件成立的那条：成立走主出口（sourceHandle 空），否则走 right
        const outs = edges.filter((e) => e.source === cur!.id);
        const pick = outs.find((e) => (e.sourceHandle === 'right') === !ok) ?? outs[0];
        cur = pick ? nodes.find((n) => n.id === pick.target) : undefined;
        if (!cur && loopLeft > 1 && loopFrom >= 0) { /* 没下一站，交给循环收尾 */ }
        continue;
      } else if (d.kind === 'parallel') {
        skipped.push(t(KIND_META[d.kind].titleKey));
      }
      // join 节点：只是视觉标记，执行时跳过（分支已经在上游选过了）

      const next = edges.find((e) => e.source === cur!.id);
      cur = next ? nodes.find((n) => n.id === next.target) : undefined;
    }

    // 循环展开：主链走完后，把循环节点之后的设备段再复制 (次数-1) 遍。
    // 放在主循环外面做，是因为回跳要改 cur，而 cur 一旦重复就会被 seen 挡住。
    if (loopFrom >= 0 && loopLeft > 1 && steps.length > loopFrom) {
      const segment = steps.slice(loopFrom);
      for (let r = 1; r < loopLeft; r++) {
        steps.push(...segment);
      }
    }

    if (steps.length === 0) {
      message.warning(t('experimentCanvas.warn.noDeviceNode'));
      return;
    }

    // 必填校验：缺参数的直接拦下，别等驱动报错才猜哪里漏了
    const missing: string[] = [];
    for (const n of nodes) {
      if (n.data.kind !== 'device' || !n.data.method) continue;
      const defs = (n.data.params ?? {});
      // 只校验"已知必填但没填"——图里没存必填信息，执行前按方法名再拉一次代价高，
      // 所以这里只校验用户显式留空的必填项（由属性面板标红提示）
      Object.entries(defs).forEach(([k, v]) => {
        if (v === '' && n.data.params?.[k] !== undefined) {
          missing.push(`${n.data.label}.${k}`);
        }
      });
    }
    if (missing.length > 0) {
      message.warning(t('experimentCanvas.warn.emptyParams', { names: missing.join(', ') }));
      return;
    }
    if (skipped.length) {
      message.info(t('experimentCanvas.info.skipped', { nodes: skipped.join('/') }));
    }
    if (loops.length) {
      message.info(t('experimentCanvas.info.loopsExpanded', { items: loops.join(', ') }));
    }
    if (transferNotes.length) {
      message.info(t('experimentCanvas.info.autoTransfer', { items: transferNotes.join(', ') }));
    }
    if (outputs.length) {
      message.info(t('experimentCanvas.info.outputVars', { items: outputs.join(', ') }));
    }

    setSaving(true);
    try {
      // 逐步执行交给后端：它能落 run/run_node/run_command，运维查得到。
      // 前端只负责把图算成有序步骤（含循环展开和分叉选路）。
      // 格式：id:method:output:参数（参数用 ; 分隔，值里支持 ${var}）
      const stepsExpr = steps.map((st) => {
        const m = st.method || '';
        const o = st.output || '';
        const ps = Object.entries(st.params ?? {})
          .map(([k, v]) => `${k}=${v}`).join(';');
        return `${st.resourceId}:${m}:${o}:${ps}`;
      }).join(',');

      const varsExpr = Object.entries(vars)
        .map(([k, v]) => `${k}=${v}`).join(',');

      const r = await apiFetch(
        `/api/experiments/flow-run?steps=${encodeURIComponent(stepsExpr)}`
        + `&vars=${encodeURIComponent(varsExpr)}`
        + `&name=${encodeURIComponent(t('experimentCanvas.flowName', { time: new Date().toLocaleTimeString() }))}`,
        { method: 'POST' },
      ).then((x) => x.json());

      if (r.error && !r.runId) { message.error(r.error); return; }

      message[r.status === 'completed' ? 'success' : 'warning'](
        t('experimentCanvas.toast.execDone', { done: r.completed, total: r.total, run: r.runId })
        + (r.error ? `：${r.error}` : '')
        + (r.vars && Object.keys(r.vars).length
          ? t('experimentCanvas.toast.execVars', { vars: JSON.stringify(r.vars) })
          : ''));

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
          <div style={{ marginBottom: 10 }}>
            <Typography.Text style={{ fontSize: 11 }}>{t('experimentCanvas.field.island')}</Typography.Text>
            <Select
              size="small" showSearch style={{ width: '100%' }}
              placeholder={t('experimentCanvas.placeholder.pickIsland')}
              value={islandId ?? undefined}
              onChange={setIslandId}
              optionFilterProp="label"
              options={islands.map((i) => ({
                value: i.id, label: `${i.code}（${i.deviceCount}）`,
              }))}
            />
            <Button size="small" block style={{ marginTop: 6 }}
              onClick={addIslandDevices} disabled={islandDevices.length === 0}>
              {t('experimentCanvas.action.addAllToCanvas', { n: islandDevices.length })}
            </Button>
          </div>

          <Typography.Paragraph type="secondary" style={{ fontSize: 10,
            marginBottom: 4 }}>
            {t('experimentCanvas.hint.islandDevices')}
          </Typography.Paragraph>
          <div style={{ maxHeight: 220, overflow: 'auto', marginBottom: 10 }}>
            {islandDevices.length === 0 ? (
              <Typography.Text type="secondary" style={{ fontSize: 10 }}>
                {t('experimentCanvas.empty.islandDevices')}
              </Typography.Text>
            ) : islandDevices.map((d: DevRow) => (
              <div key={d.resourceId} draggable
                onDragStart={(e) => {
                  e.dataTransfer.setData('application/x-device', String(d.resourceId));
                  e.dataTransfer.effectAllowed = 'copy';
                }}
                style={{
                  display: 'flex', alignItems: 'center', gap: 6,
                  padding: '4px 6px', marginBottom: 4, borderRadius: 4,
                  border: '1px solid #0ea5e955', background: '#0ea5e914',
                  fontSize: 11, cursor: 'grab',
                }}>
                {d.icon ? (
                  <img src={d.icon} alt={d.code}
                    style={{
                      width: 36, height: 28, objectFit: 'cover',
                      borderRadius: 3, flexShrink: 0,
                      border: '1px solid #0ea5e944',
                    }}
                    onError={(e) => {
                      (e.currentTarget as HTMLImageElement).style.display = 'none';
                    }} />
                ) : (
                  <div style={{
                    width: 36, height: 28, borderRadius: 3, flexShrink: 0,
                    background: '#0ea5e933', display: 'flex',
                    alignItems: 'center', justifyContent: 'center',
                    fontSize: 9, color: '#0ea5e9',
                  }}>{d.capability?.slice(0, 3) ?? '?'}</div>
                )}
                <div style={{ minWidth: 0 }}>
                  <div style={{ fontWeight: 600 }}>{d.code}</div>
                  <div style={{ fontSize: 9, color: '#8b93a7',
                    whiteSpace: 'nowrap', overflow: 'hidden',
                    textOverflow: 'ellipsis' }}>
                    {d.capability ?? '—'} · {d.ip ?? t('experimentCanvas.device.noIp')}
                  </div>
                </div>
              </div>
            ))}
          </div>

          <Typography.Paragraph type="secondary" style={{ fontSize: 10,
            marginBottom: 4 }}>
            {t('experimentCanvas.hint.logicNodes')}
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
            <Button size="small" loading={saving} {...ro} onClick={runFlow}>{t('experimentCanvas.action.runByArrows')}</Button>
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

              {selected.data.kind === 'device' && (
                <>
                  <div>
                    <Typography.Text style={{ fontSize: 11 }}>{t('experimentCanvas.field.codeCapability')}</Typography.Text>
                    <div style={{ fontSize: 11, fontFamily: 'monospace' }}>
                      {selected.data.label} · {selected.data.capability ?? '—'}
                    </div>
                  </div>
                  <div>
                    <Typography.Text style={{ fontSize: 11 }}>IP</Typography.Text>
                    <div style={{ fontSize: 11, fontFamily: 'monospace' }}>
                      {selected.data.ip ?? t('experimentCanvas.device.ipNotSet')}
                    </div>
                  </div>
                  <div>
                    <Typography.Text style={{ fontSize: 11 }}>{t('experimentCanvas.field.method')}</Typography.Text>
                    <Input size="small" placeholder="Deposit1"
                      value={selected.data.method}
                      onChange={(e) => patchSelected({ method: e.target.value })} />
                  </div>
                  {(() => {
                    const defs = (methodParamDefs.data as {
                      params?: Array<{ name: string; desc?: string;
                        control_type?: string; type?: string;
                        required?: number; default?: string;
                        unit?: string; range?: string }>;
                    } | null)?.params ?? [];
                    if (defs.length === 0) return null;
                    return (
                      <div>
                        <Typography.Text style={{ fontSize: 11 }}>
                          {t('experimentCanvas.methodParams', { n: defs.length })}
                        </Typography.Text>
                        {methodParamDefs.loading && (
                          <div style={{ fontSize: 9, color: '#8b93a7' }}>{t('experimentCanvas.loading')}</div>
                        )}
                        {defs.map((pp) => {
                          const val = selected.data.params?.[pp.name];
                          const setVal = (v: unknown) => patchSelected({
                            params: {
                              ...(selected.data.params ?? {}),
                              [pp.name]: v == null ? '' : String(v),
                            },
                          });
                          const missing = pp.required && (val === undefined || val === '');
                          return (
                            <div key={pp.name} style={{ marginTop: 4 }}>
                              <div style={{ fontSize: 10,
                                color: missing ? '#ef4444' : '#8b93a7' }}>
                                {pp.name}
                                {pp.required ? <span style={{ color: '#ef4444' }}> *</span> : null}
                                {pp.unit ? `（${pp.unit}）` : ''}
                                {missing ? t('experimentCanvas.param.missing') : ''}
                              </div>
                              {pp.control_type === 'number' ? (
                                <InputNumber
                                  size="small" style={{ width: '100%' }}
                                  placeholder={pp.default ?? ''}
                                  value={val === undefined || val === '' ? null : Number(val)}
                                  onChange={(v) => setVal(v)} />
                              ) : pp.control_type === 'select'
                                  && (pp.range ?? '').trim() !== '' ? (
                                <Select
                                  size="small" style={{ width: '100%' }} allowClear
                                  placeholder={pp.default ?? t('experimentCanvas.placeholder.select')}
                                  value={val || undefined}
                                  onChange={(v) => setVal(v)}
                                  options={(pp.range ?? '')
                                    .split(/[,，;；\n]/)
                                    .map((o) => o.trim())
                                    .filter(Boolean)
                                    .map((o) => ({ value: o, label: o }))} />
                              ) : (
                                <Input size="small"
                                  placeholder={pp.default ?? (pp.desc ?? '').slice(0, 20)}
                                  value={val ?? ''}
                                  onChange={(e) => setVal(e.target.value)} />
                              )}
                            </div>
                          );
                        })}
                        <Typography.Text type="secondary" style={{ fontSize: 9 }}>
                          {t('experimentCanvas.param.hint')}
                        </Typography.Text>
                      </div>
                    );
                  })()}

                  <div style={{ borderTop: '1px solid #262c38', paddingTop: 8 }}>
                    <Typography.Text style={{ fontSize: 11 }}>
                      {t('experimentCanvas.field.teachPoint')}
                    </Typography.Text>
                    <div style={{ fontSize: 9, color: '#8b93a7', marginBottom: 4 }}>
                      {t('experimentCanvas.hint.teachPoint')}
                    </div>
                    <Space size={4}>
                      {(['x', 'y', 'z'] as const).map((axis) => (
                        <Input size="small" key={axis} style={{ width: 68 }}
                          placeholder={axis.toUpperCase()}
                          value={teach[axis] ?? ''}
                          onChange={(e) => setTeach({ ...teach, [axis]: e.target.value })} />
                      ))}
                      <Button size="small" {...ro} onClick={async () => {
                        const code = selected.data.label;
                        const isl = islandId;
                        if (!isl || !code) { message.warning(t('experimentCanvas.warn.pickIsland')); return; }
                        try {
                          const q = `?action=PickPlace&deviceResourceId=${selected.data.resourceId}`;
                          const r = await apiFetch(
                            `/api/islands/${isl}/robot-points/${encodeURIComponent(code)}${q}`,
                            {
                              method: 'PUT',
                              headers: { 'Content-Type': 'application/json' },
                              body: JSON.stringify({ x: Number(teach.x) }),
                            });
                          const j = await r.json();
                          if (!r.ok) throw new Error(j.error ?? t('experimentCanvas.error.saveFailed'));
                          if (j.updated === 0) {
                            message.warning(t('experimentCanvas.warn.pointMissing'));
                          } else {
                            message.success(t('experimentCanvas.toast.pointUpdated', { code: code ?? '', x: teach.x ?? 0 }));
                          }
                        } catch (e) {
                          message.error(String((e as Error).message));
                        }
                      }}>{t('experimentCanvas.action.teachPoint')}</Button>
                      <Button size="small" {...ro} onClick={async () => {
                        const isl = islandId;
                        if (!isl) { message.warning(t('experimentCanvas.warn.pickIsland')); return; }
                        // 岛上第一台 TRANSFER 能力设备就是机械臂
                        const arm = islandDevices.find((d) => d.capability === 'TRANSFER');
                        if (!arm) { message.warning(t('experimentCanvas.warn.noArm')); return; }
                        try {
                          const r = await apiFetch(
                            `/api/islands/${isl}/robot-points/`
                            + `${encodeURIComponent(selected.data.label)}/capture`
                            + `?robotResourceId=${arm.resourceId}&method=Home`,
                            { method: 'POST' });
                          const j = await r.json();
                          if (j.captured > 0) {
                            setTeach({
                              x: String(j.x ?? ''), y: String(j.y ?? ''),
                              z: String(j.z ?? ''),
                            });
                            message.success(t('experimentCanvas.toast.readPos', { x: j.x, y: j.y, z: j.z }));
                          } else {
                            message.warning(j.reason ?? j.error ?? t('experimentCanvas.error.readFailed'));
                          }
                        } catch (e) {
                          message.error(String((e as Error).message));
                        }
                      }}>{t('experimentCanvas.action.readCoords')}</Button>
                    </Space>
                  </div>

                  <div>
                    <Typography.Text style={{ fontSize: 11 }}>{t('experimentCanvas.field.outputVar')}</Typography.Text>
                    <Input size="small" placeholder="plateId=plate"
                      value={selected.data.output}
                      onChange={(e) => patchSelected({ output: e.target.value })} />
                    <Typography.Text type="secondary" style={{ fontSize: 9 }}>
                      {t('experimentCanvas.hint.outputVar')}
                    </Typography.Text>
                  </div>
                </>
              )}

              {selected.data.kind === 'variable' && (
                <div>
                  <Typography.Text style={{ fontSize: 11 }}>{t('experimentCanvas.field.varAssign')}</Typography.Text>
                  <Input size="small" placeholder="temp=37"
                    value={selected.data.value}
                    onChange={(e) => patchSelected({ value: e.target.value })} />
                </div>
              )}

              {selected.data.kind === 'fork' && (
                <div>
                  <Typography.Text style={{ fontSize: 11 }}>{t('experimentCanvas.field.condition')}</Typography.Text>
                  <Input size="small" placeholder="${temp} > 37"
                    value={selected.data.condition}
                    onChange={(e) => patchSelected({ condition: e.target.value })} />
                </div>
              )}

              {selected.data.kind === 'loop' && (
                <div>
                  <Typography.Text style={{ fontSize: 11 }}>{t('experimentCanvas.field.repeatCount')}</Typography.Text>
                  <Input size="small" placeholder={t('experimentCanvas.placeholder.repeatCount')}
                    value={selected.data.value}
                    onChange={(e) => patchSelected({ value: e.target.value })} />
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
