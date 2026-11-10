/**
 * 老数据里**自动生成的起止节点标签** → 中性 code 的对照表（P-15）。
 *
 * <p><b>为什么需要这张表</b>：实验编排画布的节点标签是**持久化数据**
 * （整个画布 JSON 落 `experiment_graph.graph`）。2026-11-10 之前，
 * 自动生成的起止节点标签写的是**界面语种的文案**（`t('experimentCanvas.node.start')`
 * 之类）—— 中文界面建出来的存「开始」，英文界面建出来的存 "Start"，
 * 换语种打开对方建的实验，标签就是另一种语言。
 *
 * <p><b>为什么这里能按类型守门</b>（BPMN 那边做不到）：画布节点**带 `kind`
 * 字段**（start / end / workflow）⇒ 能确定"这个标签就是自动生成的那个"，
 * 不必像 BPMN 那样只能按名字猜（那边前端接口没有 nodeType、拿不到节点类型）。
 * 用户把**流程**节点起名叫「开始」不会被误改。
 *
 * <p><b>这张表的中文为什么放在这里</b>：它不是"界面文案"，而是**历史数据的
 * 键值对照**（读老数据时用）。放在 {@code webapp/src/i18n/} 下是为了
 * 与语言包同域；扫描器 {@code scripts/scan-i18n.py} 对本目录整体豁免
 * （理由见该脚本的 EXCLUDE 表），因为这里的字面量是**数据键**而不是待翻译文案。
 */

/** 自动生成的起止节点标签的**中性 code**（落库用，与语种无关）。 */
export const AUTO_LABEL_CODE: Record<'start' | 'end', string> = {
  start: 'START',
  end: 'END',
};

/**
 * 老数据里自动生成过的起止标签（冻结字面量，两种语种各一份）。
 *
 * <p>只收**起止节点**的（显示层按 kind 守门后再查这张表）。
 */
export const LEGACY_AUTO_LABELS: Record<'start' | 'end', string[]> = {
  start: ['开始', 'Start'],
  end: ['结束', 'End'],
};
