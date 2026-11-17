/**
 * 客户 §3 的 8 阶段（+ 两个辅助阶段）。
 *
 * <p>为什么把编码写死在这里：阶段是**客户文档的编号**（S1..S8），不是我们自创的
 * 分组名。设计器、甘特、看板三处必须用同一份顺序和颜色，否则同一张图在不同页面
 * 上"阶段顺序不一样"，评审时会被当成两套模型。
 *
 * <p>S0（启动校验）与 S9（连续循环/耗材预警）是客户 §2 / §3 阶段9 的内容，
 * 不在 §3 的 8 阶段里，但流程上确实存在 ⇒ 单独列出并标成"辅助"。
 */
export interface StageDef {
  code: string;
  /** 是否属于客户 §3 的 8 个正式阶段 */
  primary: boolean;
}

export const STAGES: StageDef[] = [
  { code: 'S1', primary: true },
  { code: 'S2', primary: true },
  { code: 'S3', primary: true },
  { code: 'S4', primary: true },
  { code: 'S5', primary: true },
  { code: 'S6', primary: true },
  { code: 'S7', primary: true },
  { code: 'S8', primary: true },
  { code: 'S0', primary: false },
  { code: 'S9', primary: false },
];

export const STAGE_CODES = STAGES.map((s) => s.code);

/** 阶段色带（甘特分组色 / 画布节点描边色）：同色系渐变，S1→S8 由冷到暖。 */
export const STAGE_COLOR: Record<string, string> = {
  S0: '#8b93a7',
  S1: '#3b82f6',
  S2: '#0ea5e9',
  S3: '#06b6d4',
  S4: '#14b8a6',
  S5: '#22c55e',
  S6: '#eab308',
  S7: '#f97316',
  S8: '#ef4444',
  S9: '#a855f7',
};

export function stageColor(code?: string | null): string {
  return (code && STAGE_COLOR[code]) || '#8b93a7';
}
