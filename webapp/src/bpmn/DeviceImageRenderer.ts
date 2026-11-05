import BaseRenderer from 'diagram-js/lib/draw/BaseRenderer';

/**
 * 让「拖方法生成的设备节点」显示该设备的图片，而不是 bpmn-js 的统一矩形。
 *
 * 做法：注册一个比 bpmnRenderer 优先级更高的 renderer，只对带 `$attrs.icon`
 * 的 bpmn:Task 接管绘制，画「白底圆角卡片 + 设备图 + 方法名」。图片走 Vite
 * 静态资源 `/devices/<CAP>.svg`，由 iconFor() 把库里失效的 /image/device/...
 * 路径按能力映射过来；不用 base64（XML 属性与 device.icon varchar(255) 都不合适）。
 *
 * 边界：展开视图会把多步 task 变成 bpmn:SubProcess，这里不接管，回落原生渲染，
 * 避免盖住内部子步骤；导出/打印时图片随 SVG 一起走（这是选 renderer 而非
 * HTML overlay 的原因）。
 */

const SVG_NS = 'http://www.w3.org/2000/svg';

/** 与 scripts/gen-device-icons.py 的 CAPS 保持一致。 */
const KNOWN_CAPS = new Set([
  'TRANSFER', 'INCUBATE', 'ALIQUOT', 'CENTRIFUGE', 'SEAL', 'STORE', 'ANALYZE',
  'WASH', 'SENSE', 'DECAP', 'SCAN', 'PEEL', 'READ_PLATE', 'PICK', 'DEVICE',
]);

/** 把库里的图片路径规范化成本地一定存在的示意图路径。 */
export function iconFor(icon?: string | null, capability?: string | null): string {
  if (icon && icon.startsWith('/devices/')) {
    return icon;
  }
  const cap = (capability ?? '').toUpperCase().replace(/[^A-Z0-9_]/g, '_');
  return KNOWN_CAPS.has(cap) ? `/devices/${cap}.svg` : '/devices/DEVICE.svg';
}

/** 机械臂：板子换设备时必须由它搬运（详见 docs/实验编排-功能设计.md §14）。 */
export function isArm(capability?: string | null): boolean {
  return (capability ?? '').toUpperCase() === 'TRANSFER';
}

function svgEl(tag: string, attrs: Record<string, string | number>): SVGElement {
  const node = document.createElementNS(SVG_NS, tag);
  for (const [k, v] of Object.entries(attrs)) {
    node.setAttribute(k, String(v));
  }
  return node;
}

/* eslint-disable @typescript-eslint/no-explicit-any */
class DeviceImageRenderer extends BaseRenderer {
  constructor(eventBus: any) {
    super(eventBus, 2000);
  }

  canRender(element: any): boolean {
    if (!element || element.waypoints || element.labelTarget) {
      return false;
    }
    if (element.type !== 'bpmn:Task') {
      return false;
    }
    const attrs = element.businessObject?.$attrs;
    return !!(attrs && attrs.icon);
  }

  drawShape(parent: SVGElement, element: any): SVGElement {
    const w: number = element.width || 100;
    const h: number = element.height || 80;
    const attrs = element.businessObject.$attrs as Record<string, string>;

    const rect = svgEl('rect', {
      x: 0, y: 0, width: w, height: h, rx: 8, ry: 8,
      fill: '#ffffff', stroke: '#0ea5e9', 'stroke-width': 2,
    });
    parent.appendChild(rect);

    const img = svgEl('image', {
      href: attrs.icon,
      x: 6, y: 6,
      width: Math.max(1, w - 12), height: Math.max(1, h - 28),
      preserveAspectRatio: 'xMidYMid meet',
    });
    parent.appendChild(img);

    const label = svgEl('text', {
      x: w / 2, y: h - 8, 'text-anchor': 'middle',
      'font-family': 'sans-serif', 'font-size': 11, fill: '#334155',
    });
    label.textContent = element.businessObject?.name || attrs.protocolName || '';
    parent.appendChild(label);

    return rect;
  }
}

(DeviceImageRenderer as any).$inject = ['eventBus'];

export default DeviceImageRenderer;
