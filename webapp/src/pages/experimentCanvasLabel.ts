import type { MessageKey } from '../i18n/core';
import { AUTO_LABEL_CODE, LEGACY_AUTO_LABELS } from '../i18n/legacyCanvasLabels';

/**
 * 实验编排画布的节点标签：**落库用中性 code，显示时本地化**（P-15）。
 *
 * <p><b>为什么</b>：画布 JSON 是**持久化数据**（落 `experiment_graph.graph`）。
 * 标签若存界面文案，中文界面建出来的实验存「开始」、英文界面建出来的存
 * "Start" —— 换语种打开对方建的实验，标签就是另一种语言。
 *
 * <p><b>后端不读 label</b>：`compileExperimentGraph` 只取 `kind` 与
 * `workflowId`，label 纯属展示 ⇒ 改它不影响编译与执行。
 *
 * <p>对照表（中性 code 与老数据文案）见 {@code i18n/legacyCanvasLabels.ts}。
 */

/** 画布节点类型。 */
export type CanvasNodeKind = 'start' | 'end' | 'workflow';

/**
 * 节点标签的**显示文案**。
 *
 * <p>三种情况：
 * <ol>
 *   <li>没标签（用户没起名）→ 显示该类型的本地化说明；</li>
 *   <li>是自动生成的起止标签（中性 code 或老数据文案）→ 显示本地化标题；</li>
 *   <li>其它 → 用户自己起的名字，原样显示。</li>
 * </ol>
 *
 * @param kind 节点类型（守门用：只有起止节点才会命中自动标签）
 * @param label 持久化下来的标签
 * @param t 取文案的函数（当前语种）
 * @param hintKey 没起名时显示的说明文案键
 * @param titleKey 自动生成的起止节点显示的标题文案键
 */
export function canvasNodeLabel(
  kind: CanvasNodeKind,
  label: string,
  t: (k: MessageKey) => string,
  hintKey: MessageKey,
  titleKey: MessageKey,
): string {
  if (!label) return t(hintKey);
  if (kind === 'start' || kind === 'end') {
    if (label === AUTO_LABEL_CODE[kind] || LEGACY_AUTO_LABELS[kind].includes(label)) {
      return t(titleKey);
    }
  }
  return label;
}
