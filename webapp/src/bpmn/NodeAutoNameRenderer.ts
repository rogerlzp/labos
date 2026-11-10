import BaseRenderer from 'diagram-js/lib/draw/BaseRenderer';
import { localizedNeutralLabel } from './nodeRoleLabel';

/**
 * 自动生成的名字 → 画布上显示本地化文案（网关角色 + 内置逻辑节点）。
 *
 * <p><b>为什么需要两个渲染器</b>：bpmn-js 把名字画在**两个完全不同的地方**——
 * <ol>
 *   <li><b>外置标签</b>（`label` 元素）：网关、事件、连线等的名字画在图形**外面**；</li>
 *   <li><b>图形内部</b>（embedded label）：**`bpmn:Task` 的名字画在方框里面**。</li>
 * </ol>
 * 两条路径的渲染入口不同（前者是 `type='label'` 的元素，后者是形状本身），
 * 所以要**各接管一次**。
 * 只接管 label 是不够的：内置逻辑节点就是普通 task（没有设备图标），
 * 它的名字画在方框内部 ⇒ 只接管 label 的话，中性 code（`__expr`）
 * 会直接露在画布上（2026-11-10 实测踩到：P-14 的第一版只接了 label，
 * 渲染级验证里 `__expr` 原样出现在方框里）。
 *
 * <p><b>做法（两个渲染器同一套）</b>：渲染前把 {@code businessObject.name}
 * **临时**换成本地化文案，交给 bpmn-js 默认渲染器画，画完**立刻还原**。
 * bpmn-js 的标签元素与图形**共享同一个 businessObject**
 * （bpmn-moddle 的关联属性），改它就等于改模型 ⇒ 必须还原，
 * 否则保存时会把本地化文案写进 BPMN，持久化的就不再是中性 code 了。
 *
 * <p><b>优先级</b>：外置标签 2000（高于 bpmn-js 默认的 1000）；
 * 图形 1900 —— **故意低于设备卡片渲染器（2000）**：带设备图标的 task
 * 由设备卡片渲染器画（白底卡片 + 设备图 + 名字），它自己画的那行字
 * 已经走本地化（见 DeviceImageRenderer.ts），不需要形状渲染器插手。
 *
 * <p>取不到自动名的标签/图形（用户自起的名字、真实设备方法名如 `Delay`、
 * 连线标签）一律 {@code canRender=false}，交回默认渲染器，行为与改动前完全一致。
 */
/* eslint-disable @typescript-eslint/no-explicit-any */

/** 外置标签：网关/事件/连线等画在图形外面的名字。 */
class NodeAutoNameLabelRenderer extends BaseRenderer {
  private _bpmnRenderer: any;

  constructor(eventBus: any, bpmnRenderer: any) {
    super(eventBus, 2000);
    this._bpmnRenderer = bpmnRenderer;
  }

  canRender(element: any): boolean {
    if (!element || element.type !== 'label' || !element.labelTarget) {
      return false;
    }
    return localizedNeutralLabel(element.labelTarget.businessObject?.name) !== null;
  }

  drawShape(parent: SVGElement, element: any): SVGElement {
    const target = element.labelTarget;
    const bo = target?.businessObject;
    const raw = bo?.name;
    const localized = localizedNeutralLabel(raw);
    if (!bo || !localized) {
      return this._bpmnRenderer.drawShape(parent, element);
    }

    // 临时换名 → 交给 bpmn-js 默认渲染器 → 立刻还原。
    // 渲染是同步的（SVG 直接建节点），所以还原后画布上已是本地化文案，
    // 而模型里仍是中性 code。
    bo.name = localized;
    try {
      return this._bpmnRenderer.drawShape(parent, element);
    } finally {
      bo.name = raw;
    }
  }
}

(NodeAutoNameLabelRenderer as any).$inject = ['eventBus', 'bpmnRenderer'];

/**
 * 图形内部的名字：`bpmn:Task` 的名字画在方框里（内置逻辑节点走这条）。
 *
 * <p>只接管**名字是自动名**的 task（内置节点的中性 code）——
 * 设备任务的 name 是方法名（`Delay`/`Transfer` 之类），**不是**自动名，
 * 一律交回默认渲染器（其中带图标的那些由设备卡片渲染器接管）。
 */
class NodeAutoNameShapeRenderer extends BaseRenderer {
  private _bpmnRenderer: any;

  constructor(eventBus: any, bpmnRenderer: any) {
    // 1900：低于设备卡片渲染器（2000），高于 bpmn-js 默认（1000）
    super(eventBus, 1900);
    this._bpmnRenderer = bpmnRenderer;
  }

  canRender(element: any): boolean {
    if (!element || element.waypoints || element.labelTarget) {
      return false;
    }
    if (element.type !== 'bpmn:Task') {
      return false;
    }
    return localizedNeutralLabel(element.businessObject?.name) !== null;
  }

  drawShape(parent: SVGElement, element: any): SVGElement {
    const bo = element.businessObject;
    const raw = bo?.name;
    const localized = localizedNeutralLabel(raw);
    if (!bo || !localized) {
      return this._bpmnRenderer.drawShape(parent, element);
    }

    bo.name = localized;
    try {
      return this._bpmnRenderer.drawShape(parent, element);
    } finally {
      bo.name = raw;
    }
  }
}

(NodeAutoNameShapeRenderer as any).$inject = ['eventBus', 'bpmnRenderer'];

export default NodeAutoNameLabelRenderer;
export { NodeAutoNameLabelRenderer, NodeAutoNameShapeRenderer };
