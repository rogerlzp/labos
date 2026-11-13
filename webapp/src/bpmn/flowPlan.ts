/**
 * **顺序图（Flow Plan）→ 修改操作（ops）** 的编译器。
 *
 * <p><b>为什么要有这一层</b>（2026-11-13 用户实测踩出来的坑）：
 * 让模型直接吐 {@code connect/disconnect} 来改结构，它**能连线，但保证不了图还连着** ——
 * 实测把主链拆成两截、留下一堆走不到的孤儿节点（PF-03 报 8 处）。
 * ⇒ 结构性改动一律走这条路：
 * <ol>
 *   <li>模型只负责**想清楚改完之后这张图应该长什么样**（顺序图）；</li>
 *   <li>由本编译器**确定性地**把它翻译成操作：新增/删除节点、重连、
 *       插并行网关（开始 + 结束）。</li>
 * </ol>
 * 连线由代码做 ⇒ 不会出现"连着连着图断了"。
 *
 * <p><b>顺序图的语义</b>：
 * <ul>
 *   <li>{@code steps} 是改完之后**从上到下**的顺序；</li>
 *   <li>带 {@code ref} 的步骤 = **复用**当前图里的那个节点（保留它的身份与参数）；
 *       不带 ref 的 = 新节点；</li>
 *   <li>顺序图里**没出现**的已有节点 ⇒ 会被删掉（所以要列全改完之后的全部步骤）；</li>
 *   <li>{@code parallel} 步骤 = 一组并行分支；<b>两个并行网关由本编译器自动生成</b>
 *       （模型不需要、也不该自己写它们）。</li>
 * </ul>
 */

import { translate } from '../i18n/core';
import type { FlowOp } from './flowOps';
import type { FlowSpec, FlowSpecNode } from './flowSpec';

/** 顺序图里的一个步骤。 */
export interface PlanStep {
  type: 'start' | 'end' | 'method' | 'logic' | 'parallel';
  /** 复用当前图里的哪个节点（id）。 */
  ref?: string;
  /** 显示名。 */
  label?: string;
  /** 设备编码（method 步骤）。 */
  device?: string;
  /** 方法名（method / logic 步骤）。 */
  method?: string;
  /** 参数。 */
  params?: Record<string, unknown>;
  /** parallel 步骤：若干条分支，每条是一个步骤数组（分支内部串行）。 */
  branches?: PlanStep[][];
}

/** 一张顺序图。 */
export interface FlowPlan {
  steps: PlanStep[];
}

/** 编译结果。 */
export interface PlanCompileResult {
  /** 展开出来的操作（顺序即应用顺序）。 */
  ops: FlowOp[];
  /** 编不出来的地方（人话）。 */
  errors: string[];
  /** 新建出来的节点 id（预览用）。 */
  added: string[];
  /** 被删掉的节点 id（预览用）。 */
  removed: string[];
}

/** 编译上下文。 */
export interface CompilePlanOptions {
  /** 设备 code → resourceId（把顺序图里的 device 变成能落库的 resource）。 */
  deviceByCode: Map<string, number>;
  /** 当前图里节点 id → 节点（用来比较"要不要改属性"）。 */
  currentById?: Map<string, FlowSpecNode>;
}

const isMethodStep = (s: PlanStep) => s.type === 'method' || s.type === 'logic';

/**
 * 把顺序图编译成操作列表。
 *
 * @param plan 顺序图
 * @param current 当前流程规格（用来算"删哪些、断哪些线"）
 * @param opts 上下文
 */
export function compilePlan(
  plan: FlowPlan,
  current: FlowSpec,
  opts: CompilePlanOptions,
): PlanCompileResult {
  const ops: FlowOp[] = [];
  const errors: string[] = [];
  const added: string[] = [];
  const removed: string[] = [];

  const steps = plan?.steps ?? [];
  if (!steps.length) {
    return { ops, errors: [translate('designer.plan.err.empty')], added, removed };
  }
  // 顺序图的结构校验：必须有 start、必须有 end、start 在最前
  const starts = steps.filter((s) => s.type === 'start').length;
  const ends = steps.filter((s) => s.type === 'end').length;
  if (starts !== 1) {
    errors.push(translate('designer.plan.err.startCount', { n: starts }));
  }
  if (ends < 1) {
    errors.push(translate('designer.plan.err.noEnd'));
  }
  if (steps[0]?.type !== 'start') {
    errors.push(translate('designer.plan.err.firstNotStart'));
  }
  if (errors.length) return { ops, errors, added, removed };

  const currentById = opts.currentById ?? new Map(current.nodes.map((n) => [n.id, n]));
  const referenced = new Set<string>();
  const newEdges = new Set<string>();
  // 当前图里**已经有的边** —— 编译器要做的是 diff：
  // 已经有的边不要重复连（会出现两条一样的线），
  // 新图里没有的旧边要断掉（不然旧链和新链并存，图就乱了）。
  const existingEdges = new Set(current.edges.map((e) => `${e.from}->${e.to}`));
  let seq = 0;

  const nextId = (prefix: string) => `plan_${prefix}${++seq}`;

  /** 放一个普通步骤（start/end/method/logic），返回它在图里的节点 id。 */
  const placeStep = (s: PlanStep): string | null => {
    if (s.type === 'parallel') return null;   // 由 placeParallel 处理
    const ref = s.ref && currentById.has(s.ref) ? s.ref : undefined;
    if (ref) referenced.add(ref);
    if (ref) {
      // 复用已有节点：属性变了才发对应操作（没变就别动）
      const cur = currentById.get(ref)!;
      if (isMethodStep(s)) {
        if (s.device && cur.code && s.device !== cur.code) {
          ops.push({ op: 'set_device', id: ref, code: s.device });
        }
        if (s.method && cur.method && s.method !== cur.method) {
          ops.push({ op: 'set_method', id: ref, method: s.method });
        }
        if (s.params && Object.keys(s.params).length) {
          ops.push({ op: 'update_params', id: ref, params: s.params });
        }
      }
      if (s.label && s.label !== cur.name) {
        ops.push({ op: 'rename', id: ref, name: s.label });
      }
      return ref;
    }
    // 新节点
    const id = nextId(s.type === 'start' ? 'S' : s.type === 'end' ? 'E' : 'N');
    const node: FlowSpecNode = { id, type: s.type };
    if (s.label) node.name = s.label;
    if (s.type === 'method') {
      node.code = s.device;
      node.method = s.method;
      node.params = s.params ?? {};
    } else if (s.type === 'logic') {
      node.method = s.method;
      node.params = s.params ?? {};
    }
    ops.push({ op: 'add', node });
    added.push(id);
    return id;
  };

  /** 放一组并行分支：自动生成"并行网关开始/结束"并把分支接好。 */
  const placeParallel = (s: PlanStep, fromId: string | null): string | null => {
    const branches = s.branches ?? [];
    if (!branches.length) {
      errors.push(translate('designer.plan.err.noBranch'));
      return null;
    }
    const splitId = nextId('G');
    const joinId = nextId('G');
    // 名字用**中性 code**（显示层再本地化）：与手工拖出来的网关一致，
    // 也让 GatewayRoleSync 能按实际拓扑把它校正成 PARALLEL_SPLIT/PARALLEL_MERGE。
    ops.push({
      op: 'add',
      node: { id: splitId, type: 'parallel', name: 'PARALLEL_SPLIT' },
    });
    added.push(splitId);
    ops.push({
      op: 'add',
      node: { id: joinId, type: 'parallel', name: 'PARALLEL_MERGE' },
    });
    added.push(joinId);
    if (fromId) {
      newEdges.add(`${fromId}->${splitId}`);
      if (!existingEdges.has(`${fromId}->${splitId}`)) {
        ops.push({ op: 'connect', from: fromId, to: splitId });
      }
    }
    branches.forEach((branch) => {
      // 分支内部是串行的：从分叉出来，一路走到汇合
      let tail: string | null = splitId;
      branch.forEach((bs) => {
        const id = placeStep(bs);
        if (!id) return;
        if (tail) {
          newEdges.add(`${tail}->${id}`);
          if (!existingEdges.has(`${tail}->${id}`)) {
            ops.push({ op: 'connect', from: tail, to: id });
          }
        }
        tail = id;
      });
      if (tail) {
        newEdges.add(`${tail}->${joinId}`);
        if (!existingEdges.has(`${tail}->${joinId}`)) {
          ops.push({ op: 'connect', from: tail, to: joinId });
        }
      }
    });
    return joinId;
  };

  /** 走一串步骤，返回最后一个节点的 id。 */
  const walk = (list: PlanStep[], fromId: string | null): string | null => {
    let tail = fromId;
    list.forEach((s) => {
      if (s.type === 'parallel') {
        const join = placeParallel(s, tail);
        if (join) {
          if (tail) newEdges.add(`${tail}->${join}`);
          tail = join;
        }
        return;
      }
      const id = placeStep(s);
      if (!id) return;
      if (tail) {
        newEdges.add(`${tail}->${id}`);
        if (!existingEdges.has(`${tail}->${id}`)) {
          ops.push({ op: 'connect', from: tail, to: id });
        }
      }
      tail = id;
    });
    return tail;
  };

  walk(steps, null);

  // ---- 顺序图里没出现的已有节点 ⇒ 删掉 ----
  current.nodes.forEach((n) => {
    if (!referenced.has(n.id)) {
      ops.push({ op: 'remove', id: n.id });
      removed.push(n.id);
    }
  });

  // ---- 旧的边里不再需要的 ⇒ 断掉 ----
  current.edges.forEach((e) => {
    const key = `${e.from}->${e.to}`;
    if (!newEdges.has(key)) {
      ops.push({ op: 'disconnect', from: e.from, to: e.to });
    }
  });

  // ---- 连通性自检：新图必须从 start 能走到每个节点、每个节点能走到 end ----
  const succ = new Map<string, string[]>();
  newEdges.forEach((k) => {
    const [f, t] = k.split('->');
    if (!succ.has(f)) succ.set(f, []);
    succ.get(f)!.push(t);
  });
  const startId = steps.find((s) => s.type === 'start');
  const startNodeId = startId?.ref && currentById.has(startId.ref)
    ? startId.ref
    : added.find((id) => id.startsWith('plan_S')) ?? '';
  const stack = startNodeId ? [startNodeId] : [];
  const seen = new Set<string>();
  while (stack.length) {
    const cur = stack.pop()!;
    if (seen.has(cur)) continue;
    seen.add(cur);
    (succ.get(cur) ?? []).forEach((n) => stack.push(n));
  }
  const allIds = [
    ...current.nodes.map((n) => n.id),
    ...added,
  ];
  const orphan = allIds.filter((id) => !seen.has(id)
    && !removed.includes(id));
  if (orphan.length) {
    errors.push(translate('designer.plan.err.orphan', {
      n: orphan.length,
      ids: orphan.slice(0, 5).join('、'),
    }));
  }

  return { ops, errors, added, removed };
}

/** 解析 LLM 返回的顺序图（兜一层形状校验）。 */
export function parsePlan(raw: unknown): { plan: FlowPlan | null; errors: string[] } {
  const errors: string[] = [];
  const obj = raw as { plan?: unknown; steps?: unknown };
  const p = (obj?.plan ?? obj) as { steps?: unknown } | null;
  if (!p || !Array.isArray(p.steps)) {
    return { plan: null, errors: [translate('designer.plan.err.noSteps')] };
  }
  return { plan: { steps: p.steps as PlanStep[] }, errors };
}
