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
 * <p><b>向后兼容</b>：只有命中本表 code 的名字才会被替换显示；库里已有的
 * 中文名（如「异或汇合」）不在表里，按原样显示，不需要迁移。
 */
import { translate, type MessageKey } from '../i18n/core';

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
 * 中性 code → 本地化文案。
 *
 * @param name 持久化下来的名字
 * @returns 本地化文案；不是中性 code（用户自起的名字、库里旧的中文名）时返回 null，
 *          调用方按原样显示
 */
export function localizedNeutralLabel(name?: string | null): string | null {
  if (!name) return null;
  const key = CODE_TO_KEY[name];
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
