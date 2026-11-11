/**
 * 设计器 palette 的三处调整：
 *
 * 1. **网关改成成对入口**（用户 2026-11-11 指出）：并行 / 异或 / 条件三种网关，
 *    每种都给「开始（分叉）」和「结束（汇合）」两个入口 —— 拖出来就是**带角色名**
 *    的节点，图上一眼能看出哪里是分、哪里是合。
 *    bpmn-js 原生 palette 只给一个「异或网关」，并行要手写 XML，
 *    条件（包容）网关<b>连入口都没有</b>。
 * 2. **拿掉解析器不认的入口**：中间/边界事件、数据对象、数据存储、泳道、分组。
 *    这类节点拖到链路上会被 `GraphRunner` 静默跳过（`node == null → continue`，
 *    且**不把后继入队**），后面整段不执行且不报错 —— 比缺功能更误导。
 * 3. **条件网关必须真能跑**：拖出来的 `bpmn:InclusiveGateway` 在执行侧
 *    2026-11-11 才补上识别（此前只认不执行 ⇒ 静默断流）。
 *
 * 做法：注册一个 priority=900 的 provider（默认 provider 是 1000，事件总线里
 * **高优先级先执行**，所以 900 在默认之后 → 拿到已合并的 entries 再增删），
 * 只动这三处，其余入口保持原生行为。
 */

import { GATEWAY_TYPES } from './GatewayVisuals';

interface PaletteLike {
  /** diagram-js：registerProvider(priority, provider)；priority 省略时为默认 1000。 */
  registerProvider: (priority: number, provider: unknown) => void;
}
interface CreateLike {
  start: (event: Event, elements: unknown) => void;
}
interface ElementFactoryLike {
  createShape: (attrs: Record<string, unknown>) => unknown;
}
interface PaletteEntry {
  group?: string;
  className?: string;
  title?: string;
  separator?: boolean;
  action?: Record<string, (event: Event) => void>;
}
type PaletteEntries = Record<string, PaletteEntry>;

/** 解析器不收、放上去会被静默跳过的入口，一律从 palette 移除。 */
const UNSUPPORTED = [
  'create.intermediate-event',
  'create.data-object',
  'create.data-store',
  'create.participant-expanded',
  'create.group',
];

/**
 * 网关的成对入口。
 *
 * <p>每一项 = 类型 + 方向 + **预置的中性 code 名字**。
 *
 * <p><b>为什么要预置名字</b>：拖出来的网关如果没名字，画布上就只是一个菱形，
 * 分叉还是汇合全靠数连线 —— 缩小后根本数不清。预置中性 code 后，
 * 画布上直接显示「并行分叉」「并行汇合」（本地化文案，见 nodeRoleLabel.ts），
 * 一眼就懂。
 *
 * <p><b>为什么要写 gatewayDirection</b>：这是 BPMN 2.0 的标准属性
 * （Diverging / Converging），写进去一是语义正确（别的工具能读懂），
 * 二是留个"意图"标记 —— 真实角色仍按连线数由引擎判定，两者不一致时以引擎为准。
 *
 * <p><b>名字存中性 code 而不是中文</b>：name 是持久化数据，
 * 同一张图不该因创建时的界面语种不同而存下不同的名字（见 nodeRoleLabel.ts 的说明）。
 */
interface GatewaySpec {
  /** palette 里的条目 id。 */
  id: string;
  /** bpmn 元素类型。 */
  type: (typeof GATEWAY_TYPES)[number];
  /** 分叉=Diverging、汇合=Converging。 */
  direction: 'Diverging' | 'Converging';
  /** 预置的中性 code 名字（库里存这个，显示时再本地化）。 */
  code: string;
  /** bpmn-font 的图标类。 */
  icon: string;
  /** palette 提示文案（跟 bpmn-js 自己的 palette 一样用英文）。 */
  title: string;
}

const GATEWAY_SPECS: GatewaySpec[] = [
  {
    id: 'create.parallel-split',
    type: 'bpmn:ParallelGateway',
    direction: 'Diverging',
    code: 'PARALLEL_SPLIT',
    icon: 'bpmn-icon-gateway-parallel',
    title: 'Create parallel split gateway (all branches run)',
  },
  {
    id: 'create.parallel-merge',
    type: 'bpmn:ParallelGateway',
    direction: 'Converging',
    code: 'PARALLEL_MERGE',
    icon: 'bpmn-icon-gateway-parallel',
    title: 'Create parallel merge gateway (wait for all branches)',
  },
  {
    id: 'create.exclusive-split',
    type: 'bpmn:ExclusiveGateway',
    direction: 'Diverging',
    code: 'XOR_SPLIT',
    icon: 'bpmn-icon-gateway-xor',
    title: 'Create exclusive split gateway (one branch runs)',
  },
  {
    id: 'create.exclusive-merge',
    type: 'bpmn:ExclusiveGateway',
    direction: 'Converging',
    code: 'XOR_MERGE',
    icon: 'bpmn-icon-gateway-xor',
    title: 'Create exclusive merge gateway (any branch releases)',
  },
  {
    id: 'create.conditional-split',
    type: 'bpmn:InclusiveGateway',
    direction: 'Diverging',
    code: 'COND_SPLIT',
    icon: 'bpmn-icon-gateway-or',
    title: 'Create conditional split gateway (every matching branch runs)',
  },
  {
    id: 'create.conditional-merge',
    type: 'bpmn:InclusiveGateway',
    direction: 'Converging',
    code: 'COND_MERGE',
    icon: 'bpmn-icon-gateway-or',
    title: 'Create conditional merge gateway (waits for the branches that run)',
  },
];

export default class WorkflowPalette {
  static $inject = ['palette', 'create', 'elementFactory'];

  private readonly _create: CreateLike;
  private readonly _elementFactory: ElementFactoryLike;

  constructor(palette: PaletteLike, create: CreateLike, elementFactory: ElementFactoryLike) {
    this._create = create;
    this._elementFactory = elementFactory;
    palette.registerProvider(900, this);
  }

  /** 返回 updater（而非 entries）：在默认 provider 的结果上增删。 */
  getPaletteEntries(): (entries: PaletteEntries) => PaletteEntries {
    const create = this._create;
    const elementFactory = this._elementFactory;

    const addGateway = (spec: GatewaySpec) => (event: Event) => {
      const shape = elementFactory.createShape({ type: spec.type }) as {
        businessObject?: Record<string, unknown>;
      };
      // 预置角色名 + BPMN 标准方向属性。
      // 直接写 businessObject（而不是 modeling.updateProperties）：此时形状
      // 还没落到画布上，没有 commandStack 可进 —— bpmn-js 的 create 工具
      // 会把它一起放进创建命令里，落库时一并持久化。
      if (shape?.businessObject) {
        shape.businessObject.name = spec.code;
        shape.businessObject.gatewayDirection = spec.direction;
      }
      create.start(event, shape);
    };

    return (entries: PaletteEntries) => {
      for (const id of UNSUPPORTED) {
        delete entries[id];
      }
      // 原生的「异或网关」入口被下面成对的一组取代 ——
      // 留着一个不分叉汇合的通用入口，正是"拖出来只有一个、看不清结构"的根源。
      delete entries['create.exclusive-gateway'];
      GATEWAY_SPECS.forEach((spec) => {
        entries[spec.id] = {
          group: 'gateway',
          className: spec.icon,
          title: spec.title,
          action: { dragstart: addGateway(spec), click: addGateway(spec) },
        };
      });
      return entries;
    };
  }
}

/** DI 模块：注册进 BpmnModeler 的 additionalModules。 */
export const workflowPaletteModule = {
  __init__: ['workflowPalette'],
  workflowPalette: ['type', WorkflowPalette] as const,
};
