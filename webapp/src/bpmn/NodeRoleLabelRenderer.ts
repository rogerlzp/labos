import BaseRenderer from 'diagram-js/lib/draw/BaseRenderer';
import { localizedNeutralLabel } from './nodeRoleLabel';

/**
 * 把画布上的**中性 code** 显示成本地化文案。
 *
 * <p><b>只接管标签（type='label'）</b>，网关形状仍由 bpmn-js 原生渲染 ——
 * bpmn-js 的 renderer 是按元素类型分发的，{@code 'label'} 是**独立入口**
 * （见 bpmn-js {@code BpmnRenderer} 的 {@code 'label': function(...)}），
 * 所以只改标签不影响菱形、✕/＋ 标记、拖拽与选中行为。
 *
 * <p><b>为什么不能直接改 businessObject.name</b>：bpmn-js 的标签元素与图形
 * <b>共享同一个 businessObject</b>（{@code BpmnImporter} 里
 * {@code elementData(semantic, di, ...)} 把 {@code businessObject: semantic}），
 * 改它就等于改模型，保存时会被 {@code saveXML} 写进 BPMN ⇒ 持久化的就不再是
 * 中性 code 了。
 *
 * <p><b>做法</b>：渲染前把目标名**临时**换成本地化文案，交给 bpmn-js 默认
 * 渲染器画（盒、fitBox 自适应、颜色全由它保证），画完**立刻还原**。
 * 于是：SVG 里是本地化文案，模型里仍是中性 code ⇒ 画布随语种变，落库不随语种变。
 *
 * <p>取不到中性 code 的标签（用户自起的名字、库里旧的中文名、连线标签）
 * 一律 {@code canRender=false}，交回默认渲染器，行为与改动前完全一致。
 */
/* eslint-disable @typescript-eslint/no-explicit-any */
class NodeRoleLabelRenderer extends BaseRenderer {
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
    const code = bo?.name;
    const localized = localizedNeutralLabel(code);
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
      bo.name = code;
    }
  }
}

(NodeRoleLabelRenderer as any).$inject = ['eventBus', 'bpmnRenderer'];

export default NodeRoleLabelRenderer;
