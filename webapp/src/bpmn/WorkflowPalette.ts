/**
 * 设计器 palette 的两处调整（批 2）：
 *
 * 1. **补并行网关入口**：后端 `WorkflowGraphParser` 完整支持 `bpmn:ParallelGateway`
 *    （`SPLIT_PARALLEL` 全出边 + `JOIN_PARALLEL` 等全部入边），但 bpmn-js 原生 palette
 *    只给了一个「异或网关」，并行结构此前只能手写 XML —— 「能跑但画不出来」。
 * 2. **拿掉解析器不认的入口**：中间/边界事件、数据对象、数据存储、泳道、分组。
 *    这类节点拖到链路上会被 `GraphRunner` 静默跳过（`node == null → continue`，
 *    且**不把后继入队**），后面整段不执行且不报错 —— 比缺功能更误导。
 *
 * 做法：注册一个 priority=900 的 provider（默认 provider 是 1000，事件总线里
 * **高优先级先执行**，所以 900 在默认之后 → 拿到已合并的 entries 再增删），
 * 只动这两处，其余入口保持原生行为。
 */

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
    const addParallel = (event: Event) => {
      create.start(event, elementFactory.createShape({ type: 'bpmn:ParallelGateway' }));
    };
    return (entries: PaletteEntries) => {
      for (const id of UNSUPPORTED) {
        delete entries[id];
      }
      entries['create.parallel-gateway'] = {
        group: 'gateway',
        className: 'bpmn-icon-gateway-parallel',
        title: 'Create parallel gateway',
        action: { dragstart: addParallel, click: addParallel },
      };
      return entries;
    };
  }
}

/** DI 模块：注册进 BpmnModeler 的 additionalModules。 */
export const workflowPaletteModule = {
  __init__: ['workflowPalette'],
  workflowPalette: ['type', WorkflowPalette] as const,
};
