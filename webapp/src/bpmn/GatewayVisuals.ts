import BaseRenderer from 'diagram-js/lib/draw/BaseRenderer';
import { gatewayRoleCode, gatewayRolePrefix, isNeutralCode } from './nodeRoleLabel';

/**
 * 网关在画布上的**可视区分**与**角色同步**。
 *
 * <p><b>为什么需要这一套</b>：用户 2026-11-11 指出「并行网关、异或网关、条件网关
 * 应该都有开始和结束网关成对出现，现在拖出来只有一个，看不清结构」。
 * 拆开是两件事：
 * <ol>
 *   <li><b>拖不出来成对的</b> ⇒ palette 补 6 个入口（见 {@link ../bpmn/WorkflowPalette}）；</li>
 *   <li><b>画出来也分不清分叉/汇合</b> ⇒ 本文件负责：
 *       <ul>
 *         <li><b>颜色</b>：分叉=绿、汇合=橙，与右栏「在本图中的作用」标签同一套色；</li>
 *         <li><b>名字</b>：网关名跟着**实际拓扑**走（连了几条线就是分叉还是汇合），
 *             别让名字骗人。</li>
 *       </ul>
 * </li>
 * </ol>
 *
 * <p><b>为什么颜色要自己画</b>：bpmn-js 的 {@code renderGateway} 完全不看
 * {@code gatewayDirection} —— 并行分叉与并行汇合画出来是<b>一模一样</b>的菱形。
 * moddle 明明支持 {@code gatewayDirection}（Diverging/Converging），
 * 但官方渲染器不用它 ⇒ 分叉/汇合只能靠名字和颜色区分。
 *
 * <p><b>角色口径必须和后端一致</b>：{@link gatewayRoleCode} 与
 * {@code WorkflowGraphParser} 同一套规则（入边多=汇合、出边多=分叉、都多按汇合、
 * 单进单出=直通）⇒ 画布上显示的角色就是引擎实际会怎么跑。
 */

/** 三种网关的 bpmn 元素类型。 */
export const GATEWAY_TYPES = [
  'bpmn:ExclusiveGateway',
  'bpmn:ParallelGateway',
  'bpmn:InclusiveGateway',
] as const;

/** 是不是网关（bpmn 的三种网关；事件网关/复杂网关本项目不认）。 */
export function isGatewayType(type?: string): boolean {
  return !!type && (GATEWAY_TYPES as readonly string[]).includes(type);
}

/** 分叉=绿、汇合=橙 —— 与右栏 antd Tag 的配色一致。 */
export const ROLE_COLOR = {
  split: '#52c415',
  merge: '#fa8c16',
} as const;

/** 中性 code → 颜色。 */
export function roleColor(code: string): string | null {
  if (!code) return null;
  if (code.endsWith('MERGE')) return ROLE_COLOR.merge;
  if (code.endsWith('SPLIT')) return ROLE_COLOR.split;
  return null;
}

/** 元素的入/出边数（bpmn-js 把连线挂在 businessObject 的 incoming/outgoing 上）。 */
export function edgeCounts(el: any): { ins: number; outs: number } {
  const bo = el?.businessObject;
  return {
    ins: bo?.incoming?.length ?? 0,
    outs: bo?.outgoing?.length ?? 0,
  };
}

/**
 * 元素的网关角色中性 code（与后端同一口径）。
 *
 * <p>直通（单进单出）返回空串 —— 那就没有角色色，也不该有角色名。
 *
 * <p><b>一个例外：还没连线的网关</b>。拓扑推不出角色（0 进 0 出），
 * 但 palette 拖出来的网关**已经带着意图**（BPMN 标准的 {@code gatewayDirection}
 * 属性：Diverging / Converging）⇒ 这时按意图上色。
 * 不这样的话，用户拖出来的第一个瞬间看到的仍是**白菱形** ——
 * 而「拖出来看不出是分叉还是汇合」正是要解决的问题本身。
 */
export function roleOf(el: any): string {
  if (!isGatewayType(el?.type)) return '';
  const { ins, outs } = edgeCounts(el);
  if (ins + outs === 0) {
    const prefix = gatewayRolePrefix(el.type);
    if (!prefix) return '';
    const dir = el?.businessObject?.gatewayDirection;
    if (dir === 'Diverging') return prefix + '_SPLIT';
    if (dir === 'Converging') return prefix + '_MERGE';
    return '';
  }
  return gatewayRoleCode(el.type, ins, outs);
}

/**
 * 网关形状渲染器：**按角色给菱形描边**。
 *
 * <p>做法：调 bpmn-js 默认渲染器画完（拿到菱形那个 `<polygon>`），
 * 再把它的 {@code stroke} 改成角色色。标记符号（✕ / ＋ / ○）保持默认黑色 ——
 * 它表示的是<b>网关类型</b>，描边色表示的是<b>分叉还是汇合</b>，两件事分开。
 *
 * <p>优先级 1850：高于 bpmn-js 默认（1000）；与设备卡片（2000，只管 task）
 * 和自动名形状渲染器（1900，也只管 task）不冲突 —— 它们只接 {@code bpmn:Task}。
 */
export class GatewayShapeRenderer extends BaseRenderer {
  private _bpmnRenderer: any;

  constructor(eventBus: any, bpmnRenderer: any) {
    super(eventBus, 1850);
    this._bpmnRenderer = bpmnRenderer;
  }

  canRender(element: any): boolean {
    if (!element || element.waypoints || element.labelTarget) {
      return false;
    }
    if (!isGatewayType(element.type)) {
      return false;
    }
    // 直通（单进单出）没有角色 ⇒ 交回默认渲染器，保持原样
    return roleColor(roleOf(element)) !== null;
  }

  drawShape(parent: SVGElement, element: any): SVGElement {
    // 默认渲染器对网关返回的是**菱形那个 polygon**（标记符号是它的兄弟节点）
    const gfx = this._bpmnRenderer.drawShape(parent, element);
    const color = roleColor(roleOf(element));
    if (color && gfx && typeof gfx.setAttribute === 'function') {
      gfx.setAttribute('stroke', color);
      // ⚠ **必须同时改内联 style**：bpmn-js 的 `shapeStyle` 把颜色写成
      //   一整串 inline style（`style="...; stroke: rgb(34,36,42); ..."`），
      //   而 **CSS style 的优先级高于 SVG presentation attribute** ——
      //   只 setAttribute('stroke') 的话属性变了、画出来还是黑的
      //   （2026-11-11 实测踩到：DOM 里 stroke 属性是绿的，计算样式却是黑的）。
      if (gfx.style) {
        gfx.style.stroke = color;
      }
    }
    return gfx;
  }
}

(GatewayShapeRenderer as any).$inject = ['eventBus', 'bpmnRenderer'];

/**
 * 角色同步：**网关的名字跟着实际拓扑走**。
 *
 * <p>palette 拖出来的网关带一个预置名字（「并行分叉」这类），
 * 但那是**意图**；等用户连完线，它在图里实际是分叉还是汇合是**事实** ——
 * 引擎按连线数判（入边多=汇合、出边多=分叉）。
 * 事实和名字打架时，以事实为准，否则画布上会写着「并行分叉」而引擎在等齐。
 *
 * <p><b>只改自动生成的名字</b>：名字命中中性 code 才动，
 * 用户自己起的名字（哪怕就叫「并行分叉」）**绝不覆盖** ——
 * 那是用户的表达，不是系统的自动名。
 *
 * <p><b>没有任何连线时不动</b>：刚拖出来还没连线的网关，
 * 拓扑推不出角色（单进单出=直通），若照着改就把预置名清掉了 ⇒ 保留 palette 给的意图名。
 *
 * <p><b>为什么要延迟一拍</b>（setTimeout 0）：同步要在命令栈的
 * {@code postExecute} 之后做，而不是在命令执行过程中嵌套执行命令
 * —— 后者容易和 commandStack 的重入处理打架。
 */
export class GatewayRoleSync {
  private _elementRegistry: any;
  private _modeling: any;
  private _graphicsFactory: any;
  private _timer: any = null;
  private _running = false;

  constructor(eventBus: any, elementRegistry: any, modeling: any, graphicsFactory: any) {
    this._elementRegistry = elementRegistry;
    this._modeling = modeling;
    this._graphicsFactory = graphicsFactory;
    eventBus.on([
      'commandStack.connection.create.postExecute',
      'commandStack.connection.delete.postExecute',
      'commandStack.connection.reconnect.postExecute',
      'commandStack.shape.create.postExecute',
      'commandStack.shape.delete.postExecute',
    ], () => this.schedule());
  }

  /** 合并多次触发：一次事件循环里只同步一遍。 */
  private schedule() {
    if (this._timer) return;
    this._timer = setTimeout(() => {
      this._timer = null;
      this.sync();
    }, 0);
  }

  /** 立刻同步一次（保存前、导入后用）。 */
  sync() {
    if (this._running) return;
    this._running = true;
    try {
      const all = this._elementRegistry?.getAll?.() ?? [];
      all.forEach((el: any) => {
        if (!isGatewayType(el?.type)) return;
        const { ins, outs } = edgeCounts(el);
        const name = el.businessObject?.name ?? '';
        const role = gatewayRoleCode(el.type, ins, outs);

        // 还没连线 ⇒ 保留 palette 预置的意图名（拓扑推不出角色）
        if (ins + outs === 0) {
          this._repaint(el);
          return;
        }
        // 只改**自动名**（中性 code）；用户自起的名字绝不覆盖
        if (isNeutralCode(name)) {
          const next = role;
          // ⚠ **只在角色明确时写**，直通（单进单出）时**保留原名**。
          // 理由：清空是不可逆的 —— name 一旦空了，它就不再是「自动名」，
          // 之后角色再变明确时也补不回来（`isNeutralCode('')` 为 false），
          // 网关就永远没有名字了。而「实际是直通」这件事右栏会如实提示，
          // 名字保留上一次的角色反而更有信息量。
          if (next && name !== next) {
            this._modeling.updateProperties(el, { name: next });
          }
        }
        this._repaint(el);
      });
    } finally {
      this._running = false;
    }
  }

  /** 重画形状，让角色色跟着新的连线数走。 */
  private _repaint(el: any) {
    const gfx = this._elementRegistry?.getGraphics?.(el);
    if (gfx) {
      this._graphicsFactory.update('shape', el, gfx);
    }
  }
}

(GatewayRoleSync as any).$inject = [
  'eventBus',
  'elementRegistry',
  'modeling',
  'graphicsFactory',
];
