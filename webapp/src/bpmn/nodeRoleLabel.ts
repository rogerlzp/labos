/**
 * 自动生成的名字：**库里存中性 code，界面渲染时再本地化**。
 *
 * <p><b>问题</b>：设计器给网关自动起名（分叉/汇合）时，如果把界面语种的文案
 * 直接写进 BPMN 的 {@code name} 属性，那么**同一张图会因创建时的界面语种不同
 * 而存下不同的名字** —— 中文界面建出来的存「异或汇合」，英文界面建出来的存
 * "XOR merge"。而 {@code name} 是持久化数据，不该跟着界面语种变。
 *
 * <p><b>做法</b>：存中性 code（{@code XOR_MERGE} 这类，与语种无关），
 * 界面各处渲染时再把 code 映射成本地化文案。代码里只有一个映射表，
 * 画布、节点设置面板、甘特图共用。
 *
 * <p><b>为什么网关角色适合这么干</b>：后端 {@code WorkflowGraphParser} 是
 * **按拓扑**判定分叉/汇合的（入边 > 1 汇合、出边 > 1 分叉、都多按汇合），
 * <b>从不读网关的 name</b> ⇒ 这个名字纯属展示，改成中性 code 对执行零影响。
 *
 * <p><b>向后兼容（老数据中文名）</b>：2026-11-10 之前设计器给网关自动起名时
 * 写死的是**中文**（「异或汇合」等），这些老名字**不在上面这张 code 表里**。
 * 所以额外接一张**反向映射表** {@link LEGACY_CN_ROLE_NAME_TO_CODE}：
 * 命中老中文名 → 先反查成 code → 再走同一套本地化。
 * <b>库里的数据一个字都不动</b>（不跑迁移，避免把用户恰好写的同名自定义名
 * 改坏），只在显示层做一次反查。详见该表文件头的「口径」。
 *
 * <p><b>已知局限</b>：反查只按**名字精确匹配**，没有节点类型守门
 * （前端 RunNode / 甘特图节点里没有 nodeType 字段，后端 DTO 也没暴露）。
 * 所以用户若把普通任务**恰好命名为**「异或分叉」，在英文界面下会被显示成
 * 角色文案。宁可漏翻、不误判，这条只在名字恰好撞上时才发生。
 */
import { translate, type MessageKey } from '../i18n/core';
import { LEGACY_CN_ROLE_NAME_TO_CODE } from '../i18n/legacyRoleNames';

/**
 * 中性 code → i18n 键。
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

/** 本表收录的全部中性 code。 */
export const NEUTRAL_CODES = Object.keys(CODE_TO_KEY);

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
 * 名字 → 中性 code。
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
 * @returns 中性 code；不是角色名时返回 null
 */
export function roleCodeOf(name?: string | null): string | null {
  if (!name) return null;
  if (isNeutralCode(name)) return name;
  return LEGACY_CN_ROLE_NAME_TO_CODE[name] ?? null;
}

/**
 * 这个名字**能不能**被本地化成角色文案。
 *
 * <p>覆盖两种：中性 code（新数据）与老数据中文名（P-13 反向映射）。
 * 画布切语种重画时用它判断"要不要重画这个标签"。
 */
export function isLocalizableRoleName(name?: string | null): boolean {
  return roleCodeOf(name) !== null;
}

/**
 * 中性 code → 本地化文案。
 *
 * <p>新数据直接查 code 表；老数据中文名先反查成 code 再查表，
 * 两条路径最终都落到同一套 i18n 文案上。
 *
 * @param name 持久化下来的名字
 * @returns 本地化文案；不是角色名（用户自起的名字）时返回 null，
 *          调用方按原样显示
 */
export function localizedNeutralLabel(name?: string | null): string | null {
  const code = roleCodeOf(name);
  if (!code) return null;
  const key = CODE_TO_KEY[code];
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
