/**
 * 流程修改操作（Flow Ops）—— **AI"改流程 / 改参数"的落点**。
 *
 * <p><b>为什么是 ops 而不是"新的整张图"</b>：
 * <ul>
 *   <li>用户手工调过的东西（名字、参数、坐标）不能被整图重画冲掉 ——
 *       ops 只描述"要改什么"，没提到的节点一律不动；</li>
 *   <li>改动要能**先预览再应用**（人能看懂 AI 要干什么）；</li>
 *   <li>落到画布时走 bpmn-js 的 modeling API ⇒ 能撤销、坐标自动排、改完直接落库。</li>
 * </ul>
 *
 * <p><b>谁产出 ops</b>：后端 {@code POST /api/workflows/{id}/ai/plan} ——
 * 它把「当前流程图 + 设备目录 + 需求」交给 LLM，拿回 ops。
 * <b>谁应用 ops</b>：本文件（前端）—— 因为只有前端知道画布上下文（节点 id、图标、岛）。
 *
 * <p><b>形状校验</b>（ops 结构对不对）在后端；<b>业务校验</b>（节点存不存在、
 * 设备在不在池里、方法存不存在）在前端 {@link applyOps}。
 */

import { translate } from '../i18n/core';
import { iconFor } from './DeviceImageRenderer';
import { gatewayRoleCode } from './nodeRoleLabel';
import { syncBusinessObjectId } from './flowSpec';
import type { FlowSpec, FlowSpecEdge, FlowSpecNode, FlowSpecNodeType } from './flowSpec';

/** 一条修改操作。 */
export type FlowOp =
  | { op: 'update_params'; id: string; params: Record<string, unknown> }
  | { op: 'set_device'; id: string; code: string }
  | { op: 'set_method'; id: string; method: string }
  | { op: 'rename'; id: string; name: string }
  | { op: 'add'; after?: string; before?: string; node: FlowSpecNode }
  | { op: 'remove'; id: string }
  | { op: 'connect'; from: string; to: string }
  | { op: 'disconnect'; from: string; to: string };

/** ops 应用结果。 */
export interface ApplyResult {
  /** 真正生效的操作条数。 */
  applied: number;
  /** 没生效的原因（逐条，人能看懂）。 */
  errors: string[];
}

// ---------------------------------------------------------------- 画布读取

interface ReadableElement {
  id: string;
  type: string;
  businessObject?: {
    name?: string;
    $attrs?: Record<string, string>;
    incoming?: Array<{ id: string }>;
    outgoing?: Array<{ id: string }>;
    /** sequenceFlow 自己的 businessObject 上是 sourceRef / targetRef。 */
    sourceRef?: { id: string };
    targetRef?: { id: string };
  };
}

/** resourceId → 设备编码（把 canvas 上的 resource 还原成"人能认的 code"）。 */
export type DeviceResolver = (resourceId: string) => string | undefined;

/**
 * 从当前画布**反读**出流程规格。
 *
 * <p>这是"在页面上提需求"能成立的关键：**发给 LLM 的必须是这张图此刻的样子**
 * （含真实的节点 id），AI 才能说"把第 3 步的参数改成 300"而不是"建一张新图"。
 *
 * @param modeler bpmn-js 实例
 * @param resolveCode resourceId → 设备 code（可选；没有就只留 resourceId）
 */
export function specFromCanvas(
  modeler: { get: (name: string) => unknown },
  resolveCode?: DeviceResolver,
): FlowSpec {
  const registry = modeler.get('elementRegistry') as {
    getAll: () => ReadableElement[];
  };
  const nodes: FlowSpecNode[] = [];
  const edges: FlowSpecEdge[] = [];

  // ---- 边：sequenceFlow 自己的 businessObject 上是 sourceRef / targetRef ----
  // ⚠ 节点的 businessObject.incoming/outgoing 里装的是**连线的 id**，
  //   连线的 businessObject 上才是 sourceRef/targetRef —— 用错地方会读出**空边表**，
  //   于是"顺序图 → diff"全错：旧边断不掉、新边又连一遍（2026-11-13 实测）。
  registry.getAll().forEach((el) => {
    if (el.type !== 'bpmn:SequenceFlow') return;
    const src = el.businessObject?.sourceRef?.id;
    const tgt = el.businessObject?.targetRef?.id;
    if (src && tgt) edges.push({ from: src, to: tgt });
  });

  for (const el of registry.getAll()) {
    const attrs = el.businessObject?.$attrs ?? {};
    const protocol = attrs.protocolName;
    switch (el.type) {
      case 'bpmn:StartEvent':
        nodes.push({ id: el.id, type: 'start', name: el.businessObject?.name });
        break;
      case 'bpmn:EndEvent':
        nodes.push({ id: el.id, type: 'end', name: el.businessObject?.name });
        break;
      case 'bpmn:ExclusiveGateway':
      case 'bpmn:ParallelGateway':
      case 'bpmn:InclusiveGateway':
        nodes.push({
          id: el.id,
          type: el.type === 'bpmn:ExclusiveGateway' ? 'exclusive'
            : el.type === 'bpmn:ParallelGateway' ? 'parallel' : 'inclusive',
          name: el.businessObject?.name,
        });
        break;
      case 'bpmn:Task': {
        const params = parseParams(attrs.methodParams);
        if (protocol && protocol.startsWith('__')) {
          nodes.push({ id: el.id, type: 'logic', method: protocol, name: el.businessObject?.name, params });
        } else {
          const resourceId = attrs.resource;
          nodes.push({
            id: el.id,
            type: 'method',
            method: protocol,
            resourceId,
            code: resourceId ? resolveCode?.(resourceId) : undefined,
            name: el.businessObject?.name,
            params,
          });
        }
        break;
      }
      default:
        break;
    }
  }
  return { nodes, edges };
}

function parseParams(raw?: string): Record<string, unknown> {
  if (!raw) return {};
  try {
    const v = JSON.parse(raw);
    return v && typeof v === 'object' && !Array.isArray(v)
      ? v as Record<string, unknown> : {};
  } catch {
    return {};
  }
}

// ---------------------------------------------------------------- ops 应用

interface OpsModeler {
  get: (name: string) => unknown;
}
interface OpsElementFactory {
  createShape: (attrs: { type: string; id?: string }) => {
    id: string;
    businessObject: { name?: string; $attrs?: Record<string, string> };
  };
}
interface OpsModeling {
  createShape: (shape: unknown, position: { x: number; y: number }, parent: unknown) => unknown;
  connect: (source: unknown, target: unknown) => unknown;
  removeElements: (elements: unknown[]) => void;
  updateProperties: (el: unknown, props: Record<string, unknown>) => void;
}
interface OpsCanvas {
  getRootElement: () => unknown;
  getRootElements?: () => unknown[];
}
interface OpsRegistry {
  get: (id: string) => ReadableElement | undefined;
  getAll: () => ReadableElement[];
}

/** 应用 ops 时的上下文。 */
export interface ApplyOpsOptions {
  /** resourceId → 设备（用于换设备时写 resource + icon）。 */
  devices?: Map<number, { resourceId: number; code: string; capability?: string; icon?: string | null }>;
  /** code → resourceId（spec 里只给了 code 时用）。 */
  deviceByCode?: Map<string, number>;
  /** 把节点排到哪（新插入节点的落点）。省略则按当前图自动找空位。 */
  layout?: (nodeId: string) => { x: number; y: number };
}

function setAttrs(
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
 * 把 ops 应用到画布。
 *
 * <p><b>逐条应用、逐条报错</b>：一条 op 不该拖垮整批 —— 报错信息要能直接告诉
 * 用户"哪个节点没找到""设备不在池子里"。
 *
 * @param modeler bpmn-js 实例
 * @param ops 操作列表
 * @param opts 上下文（设备目录、落点）
 */
export function applyOps(
  modeler: OpsModeler,
  ops: FlowOp[],
  opts: ApplyOpsOptions = {},
): ApplyResult {
  const errors: string[] = [];
  let applied = 0;

  const factory = modeler.get('elementFactory') as OpsElementFactory;
  const modeling = modeler.get('modeling') as OpsModeling;
  const canvas = modeler.get('canvas') as OpsCanvas;
  const registry = modeler.get('elementRegistry') as OpsRegistry;
  const roots = canvas.getRootElements?.() ?? [];
  const root = roots.find((el) => (el as { type?: string })?.type === 'bpmn:Process')
    ?? canvas.getRootElement();

  const deviceByCode = opts.deviceByCode ?? new Map<string, number>();
  const devices = opts.devices ?? new Map();

  /** 找节点；找不到就记一条错误。 */
  const need = (id: string | undefined, what: string): ReadableElement | null => {
    if (!id) {
      errors.push(translate('designer.ai.opErr.noId', { what }));
      return null;
    }
    const el = registry.get(id);
    if (!el) {
      errors.push(translate('designer.ai.opErr.nodeNotFound', { id }));
      return null;
    }
    return el;
  };

  for (const op of ops ?? []) {
    try {
      switch (op.op) {
        // ---- 改参数：合并进 methodParams ----
        case 'update_params': {
          const el = need(op.id, translate('designer.ai.what.params'));
          if (!el) break;
          const attrs = el.businessObject?.$attrs;
          if (!attrs) {
            errors.push(translate('designer.ai.opErr.notDeviceNode', { id: op.id }));
            break;
          }
          const cur = parseParams(attrs.methodParams);
          const next = { ...cur, ...(op.params ?? {}) };
          attrs.methodParams = JSON.stringify(next);
          // 触发一次重绘 + 进 commandStack（能撤销）
          modeling.updateProperties(el, {});
          applied += 1;
          break;
        }

        // ---- 换设备：resource + icon ----
        case 'set_device': {
          const el = need(op.id, translate('designer.ai.what.device'));
          if (!el) break;
          const rid = deviceByCode.get(op.code);
          if (rid == null) {
            errors.push(translate('designer.ai.opErr.deviceUnknown', { code: op.code }));
            break;
          }
          const attrs = el.businessObject?.$attrs;
          if (!attrs) {
            errors.push(translate('designer.ai.opErr.notDeviceNode', { id: op.id }));
            break;
          }
          attrs.resource = String(rid);
          const dev = devices.get(rid);
          if (dev) attrs.icon = iconFor(dev.icon, dev.capability);
          modeling.updateProperties(el, {});
          applied += 1;
          break;
        }

        // ---- 换方法：protocolName + 显示名跟着走 ----
        case 'set_method': {
          const el = need(op.id, translate('designer.ai.what.method'));
          if (!el) break;
          const attrs = el.businessObject?.$attrs;
          if (!attrs) {
            errors.push(translate('designer.ai.opErr.notDeviceNode', { id: op.id }));
            break;
          }
          attrs.protocolName = op.method;
          // 显示名跟着方法走（与手工拖入的约定一致：设备任务 name = method）
          el.businessObject!.name = op.method;
          modeling.updateProperties(el, { name: op.method });
          applied += 1;
          break;
        }

        // ---- 改名字 ----
        case 'rename': {
          const el = need(op.id, translate('designer.ai.what.name'));
          if (!el) break;
          el.businessObject!.name = op.name;
          modeling.updateProperties(el, { name: op.name });
          applied += 1;
          break;
        }

        // ---- 新增节点 ----
        case 'add': {
          const node = op.node;
          if (!node?.id) {
            errors.push(translate('designer.ai.opErr.addNoId'));
            break;
          }
          if (registry.get(node.id)) {
            errors.push(translate('designer.ai.opErr.dupId', { id: node.id }));
            break;
          }
          const type = bpmnTypeOf(node.type);
          const shape = factory.createShape({ type, id: node.id });
          if (node.type === 'method' || node.type === 'logic') {
            const rid = node.resourceId != null
              ? Number(node.resourceId)
              : (node.code ? deviceByCode.get(node.code) : undefined);
            if (node.type === 'method') {
              if (rid == null) {
                errors.push(translate('designer.ai.opErr.deviceUnknown', {
                  code: node.code ?? String(node.resourceId ?? ''),
                }));
                break;
              }
              const dev = devices.get(rid);
              setAttrs(shape, {
                protocolName: node.method ?? '',
                resource: String(rid),
                methodParams: JSON.stringify(node.params ?? {}),
                icon: iconFor(dev?.icon, dev?.capability),
              });
              shape.businessObject.name = node.name ?? node.method ?? '';
            } else {
              setAttrs(shape, {
                protocolName: node.method ?? '',
                methodParams: JSON.stringify(node.params ?? {}),
              });
              shape.businessObject.name = node.name ?? node.method ?? '';
            }
          } else if (node.type === 'start' || node.type === 'end') {
            shape.businessObject.name = node.name ?? (node.type === 'start' ? 'START' : 'END');
          } else {
            shape.businessObject.name = node.name ?? '';
          }
          const pos = opts.layout?.(node.id) ?? { x: 200, y: 200 };
          modeling.createShape(shape, pos, root);
          // 元素 id 与 businessObject id 必须一致（bpmn-js 会给后者另生成一个）
          syncBusinessObjectId(shape);
          applied += 1;
          // 插到某个节点后面 / 前面：把原来的边断开再重连
          if (op.after) {
            const prev = registry.get(op.after);
            if (prev) {
              const flows = (prev.businessObject?.outgoing ?? [])
                .map((f) => registry.getAll().find((x) => x.id === f.id))
                .filter(Boolean);
              flows.forEach((f) => modeling.removeElements([f!]));
            }
            const a = registry.get(op.after);
            const b = registry.get(node.id);
            if (a && b) modeling.connect(a, b);
          }
          break;
        }

        // ---- 删除节点（连带它的连线） ----
        case 'remove': {
          const el = need(op.id, translate('designer.ai.what.remove'));
          if (!el) break;
          const flows = [
            ...(el.businessObject?.incoming ?? []).map((f) => registry.get(f.id)),
            ...(el.businessObject?.outgoing ?? []).map((f) => registry.get(f.id)),
          ].filter(Boolean);
          modeling.removeElements([el, ...flows]);
          applied += 1;
          break;
        }

        // ---- 连线 ----
        case 'connect': {
          const a = need(op.from, translate('designer.ai.what.connect'));
          const b = need(op.to, translate('designer.ai.what.connect'));
          if (!a || !b) break;
          // 已经有的边不要再连一遍（否则图上会出现两条一样的线）
          const dup = registry.getAll().find((el) =>
            el.type === 'bpmn:SequenceFlow'
            && el.businessObject?.sourceRef?.id === op.from
            && el.businessObject?.targetRef?.id === op.to);
          if (dup) break;
          modeling.connect(a, b);
          applied += 1;
          break;
        }

        // ---- 断线 ----
        case 'disconnect': {
          // ⚠ sequenceFlow 的 businessObject 上是 **sourceRef / targetRef**，
          // 没有 incoming/outgoing（那俩挂在节点上）—— 用错字段会一条都找不到，
          // 于是旧边留着、新边又加了一遍，图就"既断又连"成一团（2026-11-13 实测）。
          const flow = registry.getAll().find((el) =>
            el.type === 'bpmn:SequenceFlow'
            && el.businessObject?.sourceRef?.id === op.from
            && el.businessObject?.targetRef?.id === op.to);
          if (!flow) {
            errors.push(translate('designer.ai.opErr.noSuchEdge', {
              from: op.from, to: op.to,
            }));
            break;
          }
          modeling.removeElements([flow]);
          applied += 1;
          break;
        }

        default:
          errors.push(translate('designer.ai.opErr.unknownOp', {
            op: String((op as { op?: string }).op ?? ''),
          }));
      }
    } catch (e) {
      errors.push(translate('designer.ai.opErr.applyFailed', {
        op: String((op as { op?: string }).op ?? ''),
        msg: String((e as Error).message),
      }));
    }
  }
  return { applied, errors };
}

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

// ---------------------------------------------------------------- 人类可读描述

/**
 * 把一条 op 描述成人话（预览列表用）。
 *
 * <p>为什么要专门写一遍：AI 返回的是 id 和 code，人看不懂
 * "update_params Activity_09euxn9" 到底改了什么。
 */
export function describeOp(op: FlowOp, label?: (id: string) => string): string {
  const name = (id?: string) => (id && label ? label(id) : id ?? '?');
  switch (op.op) {
    case 'update_params':
      return translate('designer.ai.op.updateParams', {
        id: name(op.id),
        params: Object.entries(op.params ?? {})
          .map(([k, v]) => `${k}=${String(v)}`).join(', '),
      });
    case 'set_device':
      return translate('designer.ai.op.setDevice', { id: name(op.id), code: op.code });
    case 'set_method':
      return translate('designer.ai.op.setMethod', { id: name(op.id), method: op.method });
    case 'rename':
      return translate('designer.ai.op.rename', { id: name(op.id), name: op.name });
    case 'add': {
      const what = op.node?.name ?? op.node?.method ?? op.node?.type ?? '?';
      const at = op.after ?? op.before;
      return at
        ? translate('designer.ai.op.add', { at: name(at), what })
        : translate('designer.ai.op.addNew', { what });
    }
    case 'remove':
      return translate('designer.ai.op.remove', { id: name(op.id) });
    case 'connect':
      return translate('designer.ai.op.connect', { from: name(op.from), to: name(op.to) });
    case 'disconnect':
      return translate('designer.ai.op.disconnect', { from: name(op.from), to: name(op.to) });
    default:
      return translate('designer.ai.op.unknown');
  }
}

/** 解析 LLM 返回的 ops（后端已保证是数组，这里再兜一层）。 */
export function parseOps(raw: unknown): { ops: FlowOp[]; errors: string[]; notes: string } {
  const errors: string[] = [];
  const obj = raw as { ops?: unknown; notes?: unknown };
  const list = Array.isArray(obj?.ops) ? obj.ops : Array.isArray(raw) ? raw : null;
  if (!list) {
    return { ops: [], errors: [translate('designer.ai.opErr.noOps')], notes: '' };
  }
  const ops: FlowOp[] = [];
  list.forEach((item, i) => {
    const o = item as Record<string, unknown>;
    if (!o || typeof o !== 'object' || typeof o.op !== 'string') {
      errors.push(translate('designer.ai.opErr.badOp', { i }));
      return;
    }
    ops.push(o as unknown as FlowOp);
  });
  // notes：AI 用来说明"哪部分做不到"（静默丢弃用户要求是最糟的）
  const notes = typeof obj?.notes === 'string' ? obj.notes : '';
  return { ops, errors, notes };
}

export { gatewayRoleCode };
