/**
 * 自动生成的名字：**库里存中性 code，界面渲染时再本地化**。
 *
 * <p><b>问题</b>：设计器给自动生成的节点起名时，如果把界面语种的文案
 * 直接写进 BPMN 的 {@code name} 属性，那么**同一张图会因创建时的界面语种不同
 * 而存下不同的名字** —— 中文界面建出来的存「异或汇合」，英文界面建出来的存
 * "XOR merge"。而 {@code name} 是持久化数据，不该跟着界面语种变。
 *
 * <p><b>做法</b>：存中性 code（与语种无关），界面各处渲染时再把 code
 * 映射成本地化文案。代码里只有映射表，画布、节点设置面板、甘特图共用。
 *
 * <p><b>覆盖两类自动名</b>（同一个机制，两个来源）：
 * <ol>
 *   <li><b>网关角色名</b>（P-13）：分叉/汇合。code 形如 {@code XOR_MERGE}。</li>
 *   <li><b>内置逻辑节点名</b>（P-14）：变量/表达式/延时/等待，以及设计器
 *       自动插入的「机械臂搬运」节点。code 形如 {@code __expr} /
 *       {@code ARM_TRANSFER}。</li>
 * </ol>
 *
 * <p><b>为什么网关角色适合这么干</b>：后端 {@code WorkflowGraphParser} 是
 * **按拓扑**判定分叉/汇合的（入边 > 1 汇合、出边 > 1 分叉、都多按汇合），
 * <b>从不读网关的 name</b> ⇒ 这个名字纯属展示，改成中性 code 对执行零影响。
 * 内置节点同理：后端按 {@code protocolName}（{@code __expr}）识别它，
 * 核实过全仓库**没有任何按节点 name 匹配的逻辑** ⇒ name 纯属展示。
 *
 * <p><b>向后兼容（老数据中文名）</b>：2026-11-10 之前自动起名写死的是**中文**
 * （「异或汇合」「表达式节点」等），这些老名字**不在上面的 code 表里**。
 * 所以额外接两张**反向映射表** {@link LEGACY_CN_ROLE_NAME_TO_CODE} /
 * {@link LEGACY_CN_BUILTIN_NAME_TO_CODE}：命中老中文名 → 先反查成 code →
 * 再走同一套本地化。<b>库里的数据一个字都不动</b>（不跑迁移，避免把用户
 * 恰好写的同名自定义名改坏），只在显示层做一次反查。详见那两张表文件头的「口径」。
 *
 * <p><b>已知局限</b>：反查只按**名字精确匹配**，没有节点类型守门
 * （前端 RunNode / 甘特图节点里没有 nodeType 字段，后端 DTO 也没暴露）。
 * 所以用户若把普通任务**恰好命名为**「异或汇合」，在英文界面下会被显示成
 * 角色文案。宁可漏翻、不误判，这条只在名字恰好撞上时才发生。
 */
import { translate, type MessageKey } from '../i18n/core';
import { LEGACY_CN_ROLE_NAME_TO_CODE } from '../i18n/legacyRoleNames';
import { LEGACY_CN_BUILTIN_NAME_TO_CODE } from '../i18n/legacyBuiltinNames';

/**
 * 网关角色 code → i18n 键。
 *
 * <p>code 用大写下划线：一眼能看出它是机器标识而不是人写的名字，
 * 也不会和用户自己起的名字撞上。
 */
const CODE_TO_KEY: Record<string, MessageKey> = {
  XOR_SPLIT: 'designer.gateway.roleXorSplit',
  XOR_MERGE: 'designer.gateway.roleXorMerge',
  PARALLEL_SPLIT: 'designer.gateway.roleParallelSplit',
  PARALLEL_MERGE: 'designer.gateway.roleParallelMerge',
};

/**
 * 内置逻辑节点 code → i18n 键。
 *
 * <p>内置节点的中性 code **直接用它的 method**（{@code __setVar} /
 * {@code __expr} / {@code __sleep} / {@code __wait}）：method 本身就是
 * 与语种无关的标识（后端按它识别内置节点），再另起一套 code 只会多一份
 * 需要维护的对照。{@code ARM_TRANSFER} 是设计器自动插入的「机械臂搬运」
 * 节点的 code —— 它的 method 是 {@code Transfer}，而 {@code Transfer} 在库里
 * 有 2000 多行（普通搬运任务），不能拿来当显示标识。
 */
const BUILTIN_CODE_TO_KEY: Record<string, MessageKey> = {
  __setVar: 'designer.node.setVar',
  __expr: 'designer.node.expr',
  __sleep: 'designer.node.sleep',
  __wait: 'designer.node.wait',
  ARM_TRANSFER: 'designer.node.armTransfer',
};

/** 本表收录的全部网关角色中性 code。 */
export const NEUTRAL_CODES = Object.keys(CODE_TO_KEY);

/** 本表收录的全部内置节点中性 code。 */
export const BUILTIN_CODES = Object.keys(BUILTIN_CODE_TO_KEY);

/**
 * 按网关类型与入/出边数推角色 code。
 *
 * <p>判定口径**必须和后端一致**（{@code WorkflowGraphParser}）：
 * 入边多 = 汇合，出边多 = 分叉，都多时按汇合处理（先等再分），
 * 单进单出等价于直通（不产生角色）。
 *
 * @param type bpmn 元素类型，如 {@code bpmn:ExclusiveGateway}
 * @param ins 入边数
 * @param outs 出边数
 * @returns 中性 code；直通或非网关返回空串
 */
export function gatewayRoleCode(type: string, ins: number, outs: number): string {
  if (type === 'bpmn:ExclusiveGateway') {
    if (ins > 1) return 'XOR_MERGE';
    if (outs > 1) return 'XOR_SPLIT';
    return '';
  }
  if (type === 'bpmn:ParallelGateway') {
    if (ins > 1) return 'PARALLEL_MERGE';
    if (outs > 1) return 'PARALLEL_SPLIT';
    return '';
  }
  return '';
}

/** 这个名字是不是本表收录的中性 code。 */
export function isNeutralCode(name?: string | null): boolean {
  return !!name && Object.prototype.hasOwnProperty.call(CODE_TO_KEY, name);
}

/**
 * 名字 → 网关角色中性 code。
 *
 * <p>两条来源：
 * <ol>
 *   <li>**新数据**：库里存的就是中性 code（{@code XOR_MERGE}），原样返回；</li>
 *   <li>**老数据**：2026-11-10 之前写死的中文名（「异或汇合」），
 *       经反向映射表换成对应 code。</li>
 * </ol>
 *
 * <p>其它名字（用户自起的名字、老库里那些「分支」「B还要再来吗」等）
 * 一律返回 null —— **不做 trim / 大小写 / 全半角归一**，宁可漏翻不误判。
 *
 * @param name 持久化下来的名字
 * @returns 中性 code；不是网关角色名时返回 null
 */
export function roleCodeOf(name?: string | null): string | null {
  if (!name) return null;
  if (isNeutralCode(name)) return name;
  return LEGACY_CN_ROLE_NAME_TO_CODE[name] ?? null;
}

/**
 * 名字 → 内置节点中性 code。
 *
 * <p>两条来源：
 * <ol>
 *   <li>**新数据**：库里存的就是 code（{@code __expr} / {@code ARM_TRANSFER}）；</li>
 *   <li>**老数据**：2026-11-10 之前写死的中文名（「表达式节点」等），
 *       经反向映射表换成对应 code。</li>
 * </ol>
 *
 * <p>⚠ 只反查**中文**字面量：内置节点的旧英文名（"Delay" 之类）与**真实
 * 设备方法名**撞名 —— 实测库里 {@code Delay} 有 480 行、{@code protocol_name}
 * 就是 {@code Delay}（真设备方法），数据上无法区分 ⇒ 反查必然误判。
 * 详见 {@code i18n/legacyBuiltinNames.ts} 的说明。
 *
 * @param name 持久化下来的名字
 * @returns 中性 code；不是内置节点名时返回 null
 */
export function builtinCodeOf(name?: string | null): string | null {
  if (!name) return null;
  if (Object.prototype.hasOwnProperty.call(BUILTIN_CODE_TO_KEY, name)) return name;
  return LEGACY_CN_BUILTIN_NAME_TO_CODE[name] ?? null;
}

/**
 * 名字 → 自动生成名的中性 code（网关角色 ∪ 内置节点）。
 *
 * <p>这是**显示层**的总入口：拿到的名字若是自动生成的，就换成 code；
 * 否则返回 null，调用方按原样显示。
 */
export function autoNameCode(name?: string | null): string | null {
  return roleCodeOf(name) ?? builtinCodeOf(name);
}

/**
 * 这个名字**能不能**被本地化成自动名文案。
 *
 * <p>覆盖：网关角色 code、内置节点 code、以及两者各自的老数据中文名。
 * 画布切语种重画时用它判断"要不要重画这个标签"。
 */
export function isLocalizableAutoName(name?: string | null): boolean {
  return autoNameCode(name) !== null;
}

/**
 * 自动名（中性 code / 老数据中文名）→ 本地化文案。
 *
 * <p>新数据直接查 code 表；老数据中文名先反查成 code 再查表，
 * 两条路径最终都落到同一套 i18n 文案上。
 *
 * @param name 持久化下来的名字
 * @returns 本地化文案；不是自动名（用户自起的名字）时返回 null，
 *          调用方按原样显示
 */
export function localizedNeutralLabel(name?: string | null): string | null {
  const code = autoNameCode(name);
  if (!code) return null;
  const key = CODE_TO_KEY[code] ?? BUILTIN_CODE_TO_KEY[code];
  return key ? translate(key) : null;
}

/**
 * 汇合角色判定（用于配色：分叉=绿、汇合=橙）。
 *
 * <p>旧实现是在中文串里 {@code includes('汇合')} —— 换语种就失效，
 * 改成按中性 code 判，与语种无关。
 */
export function isMergeRole(code: string): boolean {
  return code.endsWith('MERGE');
}
