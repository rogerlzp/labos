/**
 * 流程规格（Flow Spec）—— **AI / 脚本建流程的中间表示**。
 *
 * <p><b>为什么要有这一层</b>：流程一多，手工拖节点就不可行了。但直接让 AI 生成
 * BPMN XML 并不好做 —— XML 里塞满了与业务无关的样板（DI 坐标、flow id、
 * incoming/outgoing 引用），AI 生成它极易漏引用、坐标乱、连线断。
 * <ul>
 *   <li><b>Flow Spec 是"业务视角"的图</b>：只有节点（设备方法 / 逻辑节点 / 网关 /
 *       起止）和边，坐标可省（省了就自动布局）；</li>
 *   <li>页面把 Spec <b>翻译</b>成 bpmn-js 的 modeling 调用 —— 与用户手工拖拽
 *       走同一条通道，所以落库带 DI、画布能渲染、能撤销；</li>
 *   <li>同一个 Spec 也能被脚本直接消费（见 {@link buildFlowSpec}），于是
 *       「自然语言 → AI → Spec → 自动建图 → 保存 → 校验」整条链路闭环。</li>
 * </ul>
 *
 * <p><b>Schema 与 {@code scripts/s2-page-flows.json} 兼容</b>（那份清单就是
 * Flow Spec 的一种），但这里额外支持：{@code logic} 逻辑节点、{@code condition}
 * 连线条件、{@code default} 默认边、以及省略坐标自动布局。
 *
 * <p><b>坐标约定</b>：与 bpmn-js 一致 —— {@code x/y} 是**形状中心**，不是左上角。
 *
 * <p><b>命名约定</b>：自动生成的名字一律写**中性 code**（{@code XOR_SPLIT} /
 * {@code __expr}），界面渲染时再本地化 —— 见 {@code nodeRoleLabel.ts}。
 * 绝不能把界面文案（如「异或分叉」）写进 name，那是持久化数据，不该跟着语种变。
 */

import { gatewayRoleCode } from './nodeRoleLabel';
import { iconFor } from './DeviceImageRenderer';
import { translate } from '../i18n/core';

/** 节点类型。 */
export type FlowSpecNodeType =
  | 'start'          // 开始事件
  | 'end'            // 结束事件
  | 'method'         // 设备方法任务（要绑设备）
  | 'logic'          // 内置逻辑任务（__setVar / __expr / __sleep / __wait）
  | 'exclusive'      // 异或网关
  | 'parallel'       // 并行网关
  | 'inclusive';     // 条件（包容）网关

/** Flow Spec 里的一个节点。 */
export interface FlowSpecNode {
  id: string;
  type: FlowSpecNodeType;
  /** 显示名。省略时自动生成（设备任务=方法名，逻辑节点=method，网关=角色 code）。 */
  name?: string;
  /** 设备任务：设备资源 id（与 palette 的 resourceId 同源）。 */
  resourceId?: number | string;
  /** 设备任务：设备 code（如 ID-X231）。用于按 code 反查 resourceId。 */
  code?: string;
  /** 设备任务：方法名（protocolName）。 */
  method?: string;
  /** 逻辑节点：方法名，必须是 __setVar / __expr / __sleep / __wait / __waitAny 之一。 */
  params?: Record<string, unknown>;
  /** 形状中心坐标；省略则自动布局。 */
  x?: number;
  y?: number;
}

/** Flow Spec 里的一条边（SequenceFlow）。 */
export interface FlowSpecEdge {
  from: string;
  to: string;
  /** 是否为源网关的**默认边**（无条件兜底）。 */
  default?: boolean;
  /** 连线条件表达式（写进 bpmn:conditionExpression）。 */
  condition?: string;
}

/** 一张完整流程图。 */
export interface FlowSpec {
  /** 流程名（仅作提示；页面不据此改名）。 */
  name?: string;
  /** 所属岛 code（仅作提示；页面不据此切岛）。 */
  island?: string;
  nodes: FlowSpecNode[];
  edges: FlowSpecEdge[];
}

/** 设备信息：建图时用来解析图标与能力（AI 生成的 Spec 里不写图标）。 */
export interface FlowSpecDevice {
  resourceId: number;
  code: string;
  capability?: string;
  icon?: string | null;
  islandId?: number;
}

/** 内置逻辑节点允许的 method。 */
export const LOGIC_METHODS = ['__setVar', '__expr', '__sleep', '__wait', '__waitAny'] as const;

/** 解析结果：要么拿到合法 spec，要么拿到一组**人话**错误。 */
export interface ParseResult {
  spec: FlowSpec | null;
  errors: string[];
}

const NODE_TYPES: readonly FlowSpecNodeType[] = [
  'start', 'end', 'method', 'logic', 'exclusive', 'parallel', 'inclusive',
];

/**
 * 解析并校验一份 Flow Spec（JSON 文本）。
 *
 * <p><b>为什么校验做在这里</b>：AI 生成的 Spec 一定有出错的时候（引用了不存在的
 * 节点、设备 id 写成字符串、"method" 拼成 "methods"）。错误必须在**落图之前**
 * 报出来 —— 否则会建出一张断线的图，用户还得回头手工收拾，比报错糟糕得多。
 *
 * @param raw JSON 文本
 * @returns 校验通过则 spec 非空、errors 为空；否则 spec 为 null、errors 非空
 */
export function parseFlowSpec(raw: string): ParseResult {
  const errors: string[] = [];
  let obj: unknown;
  try {
    obj = JSON.parse(raw);
  } catch (e) {
    return { spec: null, errors: [translate('designer.spec.err.json',
      { msg: (e as Error).message })] };
  }
  if (!obj || typeof obj !== 'object' || Array.isArray(obj)) {
    return { spec: null, errors: [translate('designer.spec.err.notObject')] };
  }
  const o = obj as Record<string, unknown>;
  const rawNodes = o.nodes;
  const rawEdges = o.edges;
  if (!Array.isArray(rawNodes) || rawNodes.length === 0) {
    errors.push(translate('designer.spec.err.noNodes'));
  }
  if (!Array.isArray(rawEdges)) {
    errors.push(translate('designer.spec.err.noEdges'));
  }
  if (errors.length > 0) return { spec: null, errors };

  const nodes: FlowSpecNode[] = [];
  const seen = new Set<string>();
  for (const [i, n] of (rawNodes as unknown[]).entries()) {
    if (!n || typeof n !== 'object') {
      errors.push(translate('designer.spec.err.nodeNotObject', { i }));
      continue;
    }
    const nn = n as Record<string, unknown>;
    const id = String(nn.id ?? '').trim();
    if (!id) {
      errors.push(translate('designer.spec.err.nodeNoId', { i }));
      continue;
    }
    if (seen.has(id)) {
      errors.push(translate('designer.spec.err.dupId', { id }));
      continue;
    }
    seen.add(id);
    const type = String(nn.type ?? '') as FlowSpecNodeType;
    if (!NODE_TYPES.includes(type)) {
      errors.push(translate('designer.spec.err.badType',
        { id, type: String(nn.type), types: NODE_TYPES.join(' / ') }));
      continue;
    }
    const node: FlowSpecNode = { id, type };
    if (nn.name != null) node.name = String(nn.name);
    if (nn.resourceId != null) node.resourceId = nn.resourceId as number | string;
    if (nn.code != null) node.code = String(nn.code);
    if (nn.method != null) node.method = String(nn.method);
    if (nn.params && typeof nn.params === 'object' && !Array.isArray(nn.params)) {
      node.params = nn.params as Record<string, unknown>;
    }
    if (typeof nn.x === 'number') node.x = nn.x;
    if (typeof nn.y === 'number') node.y = nn.y;

    // 按类型做必填校验：报"缺什么"而不是"格式不对"
    if (type === 'method') {
      if (!node.method) errors.push(translate('designer.spec.err.noMethod', { id }));
      if (node.resourceId == null && !node.code) {
        errors.push(translate('designer.spec.err.noDevice', { id }));
      }
    }
    if (type === 'logic') {
      if (!node.method) {
        errors.push(translate('designer.spec.err.noLogicMethod',
          { id, methods: LOGIC_METHODS.join(' / ') }));
      } else if (!(LOGIC_METHODS as readonly string[]).includes(node.method)) {
        errors.push(translate('designer.spec.err.badLogicMethod',
          { id, method: node.method, methods: LOGIC_METHODS.join(' / ') }));
      }
    }
    nodes.push(node);
  }

  const edges: FlowSpecEdge[] = [];
  for (const [i, e] of (rawEdges as unknown[]).entries()) {
    if (!e || typeof e !== 'object') {
      errors.push(translate('designer.spec.err.edgeNotObject', { i }));
      continue;
    }
    const ee = e as Record<string, unknown>;
    const from = String(ee.from ?? '').trim();
    const to = String(ee.to ?? '').trim();
    if (!from || !to) {
      errors.push(translate('designer.spec.err.edgeNoEnds', { i }));
      continue;
    }
    if (!seen.has(from)) errors.push(translate('designer.spec.err.edgeFromUnknown',
      { i, ref: from }));
    if (!seen.has(to)) errors.push(translate('designer.spec.err.edgeToUnknown',
      { i, ref: to }));
    const edge: FlowSpecEdge = { from, to };
    if (ee.default === true) edge.default = true;
    if (ee.condition != null) edge.condition = String(ee.condition);
    edges.push(edge);
  }

  if (errors.length > 0) return { spec: null, errors };
  const spec: FlowSpec = { nodes, edges };
  if (o.name != null) spec.name = String(o.name);
  if (o.island != null) spec.island = String(o.island);
  return { spec, errors: [] };
}

/**
 * 分层自动布局：给没写坐标的节点算一个**看得懂**的位置。
 *
 * <p>口径：按拓扑深度分层（start 在最左，end 在最右），同层节点上下排开。
 * 分支自然就分成了上下两行 —— 不需要图算法去认"这是分叉"。
 *
 * <p>坐标是**形状中心**（与 bpmn-js 一致）。
 *
 * @param spec 流程规格
 * @returns 节点 id → 中心坐标
 */
export function layoutFlowSpec(spec: FlowSpec): Map<string, { x: number; y: number }> {
  const ids = spec.nodes.map((n) => n.id);
  const preds = new Map<string, string[]>();
  const succs = new Map<string, string[]>();
  ids.forEach((id) => { preds.set(id, []); succs.set(id, []); });
  spec.edges.forEach((e) => {
    if (!preds.has(e.from) || !succs.has(e.to)) return;
    succs.get(e.from)!.push(e.to);
    preds.get(e.to)!.push(e.from);
  });

  // 最长路径深度。用迭代松弛（Bellman-Ford 风格）避免递归栈爆，
  // 并用迭代上限兜住环（BPMN 允许回路，别死循环）。
  const depth = new Map<string, number>();
  ids.forEach((id) => depth.set(id, 0));
  const MAX_ROUNDS = ids.length + 2;
  for (let round = 0; round < MAX_ROUNDS; round += 1) {
    let changed = false;
    ids.forEach((id) => {
      const d = (preds.get(id) ?? []).reduce(
        (mx, p) => Math.max(mx, (depth.get(p) ?? 0) + 1), 0);
      if (d > (depth.get(id) ?? 0)) { depth.set(id, d); changed = true; }
    });
    if (!changed) break;
  }

  const layers = new Map<number, string[]>();
  ids.forEach((id) => {
    const d = depth.get(id) ?? 0;
    if (!layers.has(d)) layers.set(d, []);
    layers.get(d)!.push(id);
  });

  const COL = 230;   // 列间距
  const ROW = 140;   // 行间距
  const out = new Map<string, { x: number; y: number }>();
  for (const d of [...layers.keys()].sort((a, b) => a - b)) {
    const row = layers.get(d)!;
    row.forEach((id, i) => {
      out.set(id, {
        x: 120 + d * COL,
        y: 140 + i * ROW,
      });
    });
  }
  return out;
}

/** bpmn-js 服务的最小接口（只取建图用得到的部分）。 */
interface SpecModelerLike {
  get: (name: string) => unknown;
}

interface SpecElementFactory {
  createShape: (attrs: { type: string; id?: string }) => {
    id: string;
    businessObject: { name?: string; $attrs?: Record<string, string> };
  };
}
interface SpecModeling {
  createShape: (shape: unknown, position: { x: number; y: number }, parent: unknown) => unknown;
  connect: (source: unknown, target: unknown) => unknown;
  removeElements: (elements: unknown[]) => void;
  updateProperties?: (el: unknown, props: Record<string, unknown>) => void;
}
interface SpecCanvas {
  getRootElement: () => unknown;
  /** 多个根元素时用（导入前 process 还没就绪的那一瞬，根是 implicit root）。 */
  getRootElements?: () => unknown[];
}
interface SpecRegistry {
  get: (id: string) => unknown;
  getAll: () => Array<{ id: string; type: string }>;
}

/** 建图选项。 */
export interface BuildOptions {
  /** 先清空画布上的旧图（默认 false = 追加）。 */
  clear?: boolean;
  /**
   * 设备目录：resourceId → 设备信息。用来解析图标与能力。
   * 传了就按目录校验设备是否存在；没传则只按 spec 里写的 resourceId 建节点。
   */
  devices?: Map<number, FlowSpecDevice>;
}

/** 建图结果。 */
export interface BuildResult {
  /** 成功建出的节点 id。 */
  created: string[];
  /** 没能建出的部分（设备不存在、method 缺失等）。 */
  errors: string[];
}

/** 规格节点类型 → bpmn 元素类型。 */
function bpmnTypeOf(type: FlowSpecNodeType): string {
  switch (type) {
    case 'start': return 'bpmn:StartEvent';
    case 'end': return 'bpmn:EndEvent';
    case 'exclusive': return 'bpmn:ExclusiveGateway';
    case 'parallel': return 'bpmn:ParallelGateway';
    case 'inclusive': return 'bpmn:InclusiveGateway';
    default: return 'bpmn:Task';
  }
}

/** 网关类型 → 分叉/汇合的中性 code 前缀。 */
function gatewayPrefixOf(type: FlowSpecNodeType): 'XOR' | 'PARALLEL' | 'COND' | null {
  if (type === 'exclusive') return 'XOR';
  if (type === 'parallel') return 'PARALLEL';
  if (type === 'inclusive') return 'COND';
  return null;
}

/** $attrs 在新建 shape 上是只读 getter，只能取回对象逐属性写（同 Designer.tsx）。 */
function setShapeAttrs(
  shape: { businessObject: { name?: string; $attrs?: Record<string, string> } },
  kv: Record<string, string>,
): void {
  const bo = shape.businessObject;
  let attrs = bo.$attrs as Record<string, string> | undefined;
  if (!attrs) {
    attrs = {};
    Object.defineProperty(bo, '$attrs', {
      value: attrs, writable: true, configurable: true, enumerable: false,
    });
  }
  Object.assign(attrs, kv);
}

/**
 * bpmn-js 的 `ElementFactory.createShape` 会**自己给 businessObject 生成新 id**
 * （源码：`businessObject = this._bpmnFactory.create(attrs.type)` —— **不传 attrs**），
 * 而**元素**的 id 用的是我们给的 ⇒ 两者不一致：
 * 画布注册表用 `T1`，XML 与连线却用 `Activity_xxx`。
 * ⇒ 后续「按 id 找节点 / 改连线 / 节点设置回写 XML」全对不上。
 * ⇒ 创建之后把 businessObject 的 id 统一成元素的 id。
 */
export function syncBusinessObjectId(shape: {
  id: string;
  businessObject: { id?: string; [k: string]: unknown };
}): void {
  if (shape.businessObject && shape.businessObject.id !== shape.id) {
    shape.businessObject.id = shape.id;
  }
}

/**
 * 把 Flow Spec 画到 bpmn-js 画布上。
 *
 * <p><b>走的是和用户手工拖拽完全一样的通道</b>（{@code modeling.createShape} +
 * {@code modeling.connect}）⇒ DI 信息齐全、画布能渲染、能撤销、画完点「保存」
 * 就能落库。
 *
 * <p><b>自动命名</b>：
 * <ul>
 *   <li>设备任务 → name = method（与手工拖入一致）；</li>
 *   <li>逻辑节点 → name = method（__setVar 等，天然与语种无关）；</li>
 *   <li>网关 → 按**实际拓扑**（入边/出边数）算分叉还是汇合，写中性 code ——
 *       口径与后端 {@code WorkflowGraphParser} 一致（见 {@link gatewayRoleCode}）。</li>
 * </ul>
 *
 * @param modeler bpmn-js Modeler 实例
 * @param spec 流程规格
 * @param opts 选项（清空 / 设备目录）
 * @returns 建了哪些节点、哪里没建成功
 */
export function buildFlowSpec(
  modeler: SpecModelerLike,
  spec: FlowSpec,
  opts: BuildOptions = {},
): BuildResult {
  const errors: string[] = [];
  const created: string[] = [];

  const factory = modeler.get('elementFactory') as SpecElementFactory;
  const modeling = modeler.get('modeling') as SpecModeling;
  const canvas = modeler.get('canvas') as SpecCanvas;
  const registry = modeler.get('elementRegistry') as SpecRegistry;
  // 根元素要**认准 process**：图刚 import 完之前，canvas 的根还是
  // implicit root —— 往那儿建节点的话，连线会落到 implicit root 里，
  // bpmn-js 的 ordering provider 直接抛 "no parent for <Flow_...>"。
  // （脚本自动化可能在 importXML 完成前就调建图钩子，所以这里要自己兜住。）
  const roots = canvas.getRootElements?.() ?? [];
  const root = roots.find((el) => (el as { type?: string })?.type === 'bpmn:Process')
    ?? canvas.getRootElement();

  if (opts.clear) {
    const olds = registry.getAll().filter((el) => el.id !== (root as { id?: string })?.id
      && el.type !== 'label');
    if (olds.length) modeling.removeElements(olds);
  }

  // 设备目录：resourceId → 设备；同时建一张 code → resourceId 的反查表
  const byCode = new Map<string, number>();
  opts.devices?.forEach((d) => byCode.set(d.code, d.resourceId));

  /** 解析节点最终要绑的资源 id（spec 写 id 或 code 都能认）。 */
  const resolveResource = (node: FlowSpecNode): number | null => {
    if (node.resourceId != null && node.resourceId !== '') {
      const n = Number(node.resourceId);
      return Number.isFinite(n) ? n : null;
    }
    if (node.code) {
      const n = byCode.get(node.code);
      if (n != null) return n;
    }
    return null;
  };

  const positions = layoutFlowSpec(spec);

  // 先算每个节点的入/出边数 —— 网关角色要靠它（和后端同口径）
  const ins = new Map<string, number>();
  const outs = new Map<string, number>();
  spec.nodes.forEach((n) => { ins.set(n.id, 0); outs.set(n.id, 0); });
  spec.edges.forEach((e) => {
    outs.set(e.from, (outs.get(e.from) ?? 0) + 1);
    ins.set(e.to, (ins.get(e.to) ?? 0) + 1);
  });

  const made = new Map<string, unknown>();

  spec.nodes.forEach((node) => {
    const type = bpmnTypeOf(node.type);
    const shape = factory.createShape({ type });

    if (node.type === 'method' || node.type === 'logic') {
      const resId = node.type === 'method' ? resolveResource(node) : null;
      if (node.type === 'method' && resId == null) {
        errors.push(translate('designer.spec.err.deviceUnresolved', {
          id: node.id,
          resourceId: String(node.resourceId),
          code: String(node.code),
        }));
        return;
      }
      if (node.type === 'method' && opts.devices && !opts.devices.has(resId!)) {
        errors.push(translate('designer.spec.err.deviceNotInPool',
          { id: node.id, resourceId: resId ?? '' }));
        return;
      }
      const dev = node.type === 'method' && resId != null
        ? opts.devices?.get(resId) : undefined;

      if (node.type === 'logic') {
        setShapeAttrs(shape, {
          protocolName: node.method!,
          methodParams: JSON.stringify(node.params ?? {}),
        });
        shape.businessObject.name = node.name ?? node.method!;
      } else {
        setShapeAttrs(shape, {
          protocolName: node.method!,
          resource: String(resId),
          methodParams: JSON.stringify(node.params ?? {}),
          icon: iconFor(dev?.icon, dev?.capability),
        });
        // 设备任务的显示名 = 方法名（与手工拖入一致，与语种无关）
        shape.businessObject.name = node.name ?? node.method!;
      }
    } else if (node.type === 'start' || node.type === 'end') {
      // 起止事件的名字是**展示性**的，但也要与语种无关：存中性 code，
      // 界面渲染时由 nodeRoleLabel / legacyBuiltinNames 映射。
      shape.businessObject.name = node.name ?? (node.type === 'start' ? 'START' : 'END');
    } else {
      // 网关：按实际拓扑算角色；spec 显式给了名字就以 spec 为准
      const role = gatewayRoleCode(type, ins.get(node.id) ?? 0, outs.get(node.id) ?? 0);
      const prefix = gatewayPrefixOf(node.type);
      const fallback = prefix
        ? (role || (outs.get(node.id) ?? 0) > 1 ? `${prefix}_SPLIT` : `${prefix}_MERGE`)
        : node.type.toUpperCase();
      shape.businessObject.name = node.name ?? role ?? fallback;
      // BPMN 标准属性：语义正确 + 给别的工具留个"意图"标记
      (shape.businessObject as { gatewayDirection?: string }).gatewayDirection =
        (outs.get(node.id) ?? 0) > 1 ? 'Diverging'
          : (ins.get(node.id) ?? 0) > 1 ? 'Converging' : 'Unspecified';
    }

    const pos = (node.x != null && node.y != null)
      ? { x: node.x, y: node.y }
      : (positions.get(node.id) ?? { x: 120, y: 140 });
    modeling.createShape(shape, pos, root);
    syncBusinessObjectId(shape);
    made.set(node.id, shape);
    created.push(node.id);
  });

  // 连线
  spec.edges.forEach((e) => {
    const from = made.get(e.from);
    const to = made.get(e.to);
    if (!from || !to) return;   // 端点没建成，报错已经在上面记过了
    const flow = modeling.connect(from, to);
    if (e.condition && flow && modeling.updateProperties) {
      modeling.updateProperties(flow, {
        conditionExpression: { body: e.condition },
      });
    }
    if (e.default && from) {
      // 默认边挂在**源元素**的 default 属性上（后端按这个口径读）
      const srcEl = registry.get((from as { id: string }).id);
      if (srcEl && modeling.updateProperties) {
        modeling.updateProperties(srcEl, { default: (flow as { id?: string })?.id });
      }
    }
  });

  return { created, errors };
}

/** 示例 Spec：页面上给用户一个可直接改的模板。 */
export const FLOW_SPEC_EXAMPLE: FlowSpec = {
  name: 'example',
  nodes: [
    { id: 'S', type: 'start', x: 120, y: 200 },
    { id: 'READ', type: 'method', code: 'ID-1021', method: 'ReadBarcode', x: 350, y: 200 },
    { id: 'SPIN', type: 'method', code: 'ID-ASPIN_2K-1', method: 'RunProgram', x: 580, y: 200 },
    { id: 'E', type: 'end', x: 810, y: 200 },
  ],
  edges: [
    { from: 'S', to: 'READ' },
    { from: 'READ', to: 'SPIN' },
    { from: 'SPIN', to: 'E' },
  ],
};
