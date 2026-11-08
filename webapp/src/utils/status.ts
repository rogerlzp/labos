/**
 * 状态词表：前端**唯一**的状态 → 文案 / 颜色来源。
 *
 * <p>为什么要有这一层：原来 8 个页面各写一份 STATUS_COLOR / STATUS_TEXT / KIND，
 * 同一个 failed 在一页红、一页灰，后端加一个状态要改 8 处、还总漏。现在
 * `GET /api/meta/status-vocabulary` 是**后端权威**，前端只做两件事：
 * <ul>
 *   <li>内置 {@link FALLBACK}：首屏 / 接口还没上线 / 拉取失败时照常渲染（不闪 code、不白屏）；</li>
 *   <li>接口通了就把后端的表合并进来（缺项仍用兜底），页面全部改读这里。</li>
 * </ul>
 *
 * <p>注意接口的 `scopes` / `pauseKinds` / `holdKinds` / `holdScopes` 是**数组**
 * （元素带 code，顺序即展示序），不是 code→对象的 map；这里统一按数组存储并 find。
 *
 * <p><b>多语种</b>：中文侧的 label 来自后端词表（DB 权威，后端加状态前端立刻可见）；
 * 英文侧按 code 查本地表（{@code i18n/codes.ts}），查不到显示 code 本身 ——
 * 英文界面里不出现中文。切换由 {@link localizeVocabulary} 完成，页面无需感知。
 */
import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import { useI18n } from '../i18n';
import { CODE_TEXT_EN } from '../i18n/codes';
import type { Locale } from '../i18n/core';

/**
 * 词表作用域。run=执行单；node=BPMN 节点；step=展开后的步骤；experiment=实验单；batch=批次；
 * control=暂停控制过程（`experiment_run.control_state`，与 experiment 生命周期正交，见 ADR-PAUSE-008）。
 */
export type StatusScope = 'run' | 'node' | 'step' | 'experiment' | 'batch' | 'control';

/** 一个状态项的原始定义（后端词表数组元素）。 */
export interface StatusScopeEntry {
  code: string;
  label: string;
  /** antd Tag 颜色 token：green/red/orange/gold/blue/default/processing/volcano */
  color: string;
  /** 终态：不会自己再变 */
  terminal?: boolean;
  /** 活动态：还在推进 / 等人处置 */
  active?: boolean;
}

export interface PauseKindEntry {
  code: string;
  label: string;
  color: string;
  /** 会自动恢复（设备故障）→ 暂停队列给 info 提示而不是 warning */
  auto: boolean;
  /** 处置提示，页面 tooltip / Alert 直接显示 */
  hint: string;
}

export interface HoldKindEntry { code: string; label: string; color: string }

export interface HoldScopeEntry { code: string; label: string }

/** `GET /api/meta/status-vocabulary` 的完整响应（数组形状，字段名与后端一致）。 */
export interface StatusVocabulary {
  version?: string;
  scopes: Record<StatusScope, StatusScopeEntry[]>;
  pauseKinds: PauseKindEntry[];
  holdKinds: HoldKindEntry[];
  holdScopes: HoldScopeEntry[];
  transitions: { runToExperiment: Record<string, string> };
}

/** 词表数组的遍历顺序（也是页面上的展示顺序）。 */
const STATUS_SCOPES: StatusScope[] = ['run', 'node', 'step', 'experiment', 'batch', 'control'];

/**
 * 内置兜底词表：与后端契约逐项一致。
 *
 * <p>为什么内置而不是只靠接口：实验列表、执行详情这些页面必须先能显示；
 * 接口 404 / 内核还没重启时不能白屏，也不该把英文 code 直接甩给值班员。
 */
export const FALLBACK_PAUSE_KINDS: PauseKindEntry[] = [
  {
    code: 'paused_fault', label: '设备故障', color: 'red', auto: true,
    hint: '设备心跳恢复后会自己继续，通常不用管；超过 10 分钟仍未恢复建议检查设备',
  },
  {
    code: 'waiting_human', label: '等待人工', color: 'orange', auto: false,
    hint: '重试耗尽或需要判断，确认现场后点继续',
  },
  {
    code: 'paused_unknown', label: '板位未知', color: 'volcano', auto: false,
    hint: '板在哪不确定，必须到现场盘点后填写实际位置',
  },
];

export const FALLBACK_HOLD_KINDS: HoldKindEntry[] = [
  { code: 'fault', label: '设备故障', color: 'red' },
  { code: 'plate_unknown', label: '板位未知', color: 'volcano' },
  { code: 'human', label: '等人工确认', color: 'orange' },
  { code: 'manual', label: '人工暂停', color: 'orange' },
];

export const FALLBACK_HOLD_SCOPES: HoldScopeEntry[] = [
  { code: 'site', label: '全场' },
  { code: 'island', label: '岛' },
  { code: 'experiment', label: '本实验' },
];

export const FALLBACK: StatusVocabulary = {
  version: 'fallback',
  scopes: {
    run: [
      { code: 'draft', label: '草稿', color: 'default' },
      { code: 'pending', label: '待执行', color: 'default' },
      { code: 'planned', label: '已排程', color: 'blue' },
      { code: 'queued', label: '排队中', color: 'default', active: true },
      { code: 'running', label: '执行中', color: 'blue', active: true },
      { code: 'paused', label: '已暂停', color: 'orange', active: true },
      { code: 'completed', label: '已完成', color: 'green', terminal: true },
      { code: 'failed', label: '失败', color: 'red', terminal: true },
      { code: 'aborted', label: '已终止', color: 'red', terminal: true },
      { code: 'cancelled', label: '已取消', color: 'default', terminal: true },
      { code: 'skipped', label: '已跳过', color: 'default', terminal: true },
    ],
    node: [
      { code: 'pending', label: '未执行', color: 'default' },
      { code: 'ready', label: '待就绪', color: 'default' },
      { code: 'running', label: '执行中', color: 'blue', active: true },
      { code: 'completed', label: '已完成', color: 'green', terminal: true },
      { code: 'failed', label: '失败', color: 'red', terminal: true },
      { code: 'manual', label: '待人工', color: 'orange', active: true },
      { code: 'skipped', label: '已跳过', color: 'gold', terminal: true },
      { code: 'aborted', label: '已终止', color: 'red', terminal: true },
      // 不是"该做没做"，是异或网关选了别的路 —— 别和「未执行」混
      { code: 'bypassed', label: '分支未选中', color: 'default', terminal: true },
    ],
    step: [
      { code: 'pending', label: '未执行', color: 'default' },
      { code: 'running', label: '执行中', color: 'blue', active: true },
      { code: 'completed', label: '已执行', color: 'green', terminal: true },
      { code: 'failed', label: '失败', color: 'red', terminal: true },
      { code: 'skipped', label: '已跳过', color: 'orange', terminal: true },
      { code: 'paused', label: '已暂停', color: 'orange', active: true },
    ],
    experiment: [
      { code: 'created', label: '已建', color: 'default' },
      { code: 'planned', label: '已排程', color: 'blue' },
      { code: 'queued', label: '排队中', color: 'default', active: true },
      { code: 'running', label: '执行中', color: 'processing', active: true },
      { code: 'paused', label: '已暂停', color: 'orange', active: true },
      { code: 'completed', label: '已完成', color: 'green', terminal: true },
      // 库存里完成态是 finished，和 completed 是同一件事，别显示成两个状态
      { code: 'finished', label: '已完成', color: 'green', terminal: true },
      { code: 'failed', label: '失败', color: 'red', terminal: true },
      { code: 'aborted', label: '已终止', color: 'default', terminal: true },
      { code: 'cancelled', label: '已取消', color: 'default', terminal: true },
      { code: 'skipped', label: '已跳过', color: 'default', terminal: true },
      { code: 'deleted', label: '已删除', color: 'default', terminal: true },
    ],
    batch: [
      { code: 'pending', label: '待执行', color: 'default' },
      { code: 'queued', label: '排队中', color: 'default', active: true },
      { code: 'running', label: '执行中', color: 'blue', active: true },
      { code: 'paused', label: '已暂停', color: 'orange', active: true },
      { code: 'completed', label: '已完成', color: 'green', terminal: true },
      { code: 'failed', label: '失败', color: 'red', terminal: true },
      { code: 'aborted', label: '已终止', color: 'default', terminal: true },
    ],
    // 暂停控制过程（experiment_run.control_state）：与 experiment 生命周期正交。
    // NONE 表示没有在途控制过程；其余三态都在途，故 active=true，且都不设终态。
    control: [
      { code: 'NONE', label: '无', color: 'default' },
      { code: 'PAUSE_REQUESTED', label: '已请求暂停', color: 'gold', active: true },
      { code: 'PAUSING', label: '暂停中', color: 'orange', active: true },
      { code: 'RESUMING', label: '恢复中', color: 'blue', active: true },
    ],
  },
  pauseKinds: FALLBACK_PAUSE_KINDS,
  holdKinds: FALLBACK_HOLD_KINDS,
  holdScopes: FALLBACK_HOLD_SCOPES,
  transitions: {
    // run 终态 → experiment 状态（后端 syncExperimentStatus 同一份口径）
    runToExperiment: {
      completed: 'finished',
      failed: 'failed',
      running: 'running',
      paused: 'paused',
      aborted: 'aborted',
      cancelled: 'cancelled',
      skipped: 'skipped',
      queued: 'queued',
    },
  },
};

/** 查到的状态展示信息（未知 code 时 label=code、color=default，不抛异常）。 */
export interface StatusMeta { label: string; color: string; terminal?: boolean; active?: boolean }

/**
 * 取一个状态项的文案 / 颜色 / 活性标记。
 *
 * @param vocab 使用哪份词表（页面从 useStatusVocabulary() 拿；省略则用 FALLBACK）
 */
export function statusMeta(
  scope: StatusScope, code: string, vocab: StatusVocabulary = FALLBACK,
): StatusMeta {
  const e = vocab.scopes[scope]?.find((x) => x.code === code);
  // terminal/active 统一成 boolean：兜底表里为了简洁没写 false，页面若用来判断
  // "还在跑的剩几个"不该拿到 undefined。
  if (!e) return { label: code, color: 'default', terminal: false, active: false };
  return {
    label: e.label || code,
    color: e.color || 'default',
    terminal: !!e.terminal,
    active: !!e.active,
  };
}

/** 状态 → 文案；未知 code 原样返回，不抛异常（词表缺项不能把页面打挂）。 */
export function statusLabel(
  scope: StatusScope, code: string, vocab: StatusVocabulary = FALLBACK,
): string {
  return statusMeta(scope, code, vocab).label;
}

/** 状态 → antd Tag 颜色 token。 */
export function statusColor(
  scope: StatusScope, code: string, vocab: StatusVocabulary = FALLBACK,
): string {
  return statusMeta(scope, code, vocab).color;
}

/** 词表里是否定义了这个 code（RunGraph 用来决定 CSS class 回退，不让新状态随便染色）。 */
export function statusKnown(
  scope: StatusScope, code: string, vocab: StatusVocabulary = FALLBACK,
): boolean {
  return !!vocab.scopes[scope]?.some((x) => x.code === code);
}

/**
 * 颜色 token → hex。
 *
 * <p>只给 SVG / 内联样式用（antd Tag 直接吃 token）。processing 是 antd 的
 * 动态进度色，hex 表没有对应项，取蓝色近似（当前只有 experiment.running 用）。
 */
const COLOR_HEX: Record<string, string> = {
  green: '#22c55e',
  red: '#ef4444',
  orange: '#f59e0b',
  gold: '#ca8a04',
  blue: '#3b82f6',
  default: '#8b93a7',
  volcano: '#ea580c',
  processing: '#3b82f6',
};

/** 状态 → hex（token 认不出时退回 default 的灰，不返回 undefined 把 SVG 画坏）。 */
export function statusHex(
  scope: StatusScope, code: string, vocab: StatusVocabulary = FALLBACK,
): string {
  return COLOR_HEX[statusColor(scope, code, vocab)] ?? COLOR_HEX.default;
}

/** 暂停类型 → 文案 / 颜色 / 是否自动恢复 / 处置提示。未知 code 不崩，按 code 显示。 */
export function pauseKindMeta(code: string, vocab: StatusVocabulary = FALLBACK): PauseKindEntry {
  const e = vocab.pauseKinds.find((x) => x.code === code);
  if (!e) return { code, label: code, color: 'default', auto: false, hint: '' };
  return {
    code: e.code ?? code,
    label: e.label || code,
    color: e.color || 'default',
    auto: !!e.auto,
    hint: e.hint ?? '',
  };
}

/** 现场扣留类型 → 文案 / 颜色。 */
export function holdKindMeta(code: string, vocab: StatusVocabulary = FALLBACK): HoldKindEntry {
  const e = vocab.holdKinds.find((x) => x.code === code);
  if (!e) return { code, label: code, color: 'default' };
  return { code: e.code ?? code, label: e.label || code, color: e.color || 'default' };
}

/** 现场扣留作用域 → 文案（site=全场 / island=岛 / experiment=本实验）。 */
export function holdScopeLabel(scope: string, vocab: StatusVocabulary = FALLBACK): string {
  return vocab.holdScopes.find((x) => x.code === scope)?.label ?? scope;
}

/** run 状态 → 对应的 experiment 状态（后端同一份 runToExperiment 映射）。 */
export function runToExperimentStatus(
  runStatus: string, vocab: StatusVocabulary = FALLBACK,
): string {
  return vocab.transitions.runToExperiment[runStatus] ?? runStatus;
}

/** 把后端返回的数组按 code 合并进兜底数组：后端顺序优先，兜底里多出来的项追加在后面。 */
function mergeEntries<T extends { code: string }>(base: T[], incoming?: T[]): T[] {
  if (!Array.isArray(incoming) || incoming.length === 0) return base;
  const byCode = new Map(base.map((e) => [e.code, e]));
  const out: T[] = [];
  const seen = new Set<string>();
  for (const e of incoming) {
    // 后端某天少个字段/混进脏数据时不能让整表失效：非对象、没 code 的直接跳过
    if (!e || typeof e !== 'object' || !e.code) continue;
    const b = byCode.get(e.code);
    out.push((b ? { ...b, ...e } : e) as T);
    seen.add(e.code);
  }
  for (const b of base) if (!seen.has(b.code)) out.push(b);
  return out;
}

/** 兜底词表 + 后端词表合并（缺项/空表都退化为兜底，页面不白屏）。 */
function mergeVocabulary(base: StatusVocabulary, incoming?: unknown): StatusVocabulary {
  if (!incoming || typeof incoming !== 'object') return base;
  const v = incoming as Partial<StatusVocabulary>;
  const scopes = { ...base.scopes };
  for (const s of STATUS_SCOPES) {
    scopes[s] = mergeEntries(base.scopes[s], v.scopes?.[s]);
  }
  return {
    version: v.version ?? base.version,
    scopes,
    pauseKinds: mergeEntries(base.pauseKinds, v.pauseKinds),
    holdKinds: mergeEntries(base.holdKinds, v.holdKinds),
    holdScopes: mergeEntries(base.holdScopes, v.holdScopes),
    transitions: {
      runToExperiment: {
        ...base.transitions.runToExperiment,
        ...(v.transitions?.runToExperiment ?? {}),
      },
    },
  };
}

/**
 * 按语种重写词表里的 label / hint。
 *
 * <p><b>中文侧什么都不做</b>：后端词表（DB 权威）给的就是中文 label，
 * 后端加了新状态，中文界面立刻就能显示出来 —— 这是词表设计的初衷，别绕过它。
 *
 * <p><b>英文侧按 code 查本地表</b>（{@link CODE_TEXT_EN}）：后端给的是中文，
 * 直接用会把中文塞进英文界面。查不到就显示 code 本身 —— 机器可读、不误导人。
 *
 * <p>hint 同理：英文表里没有的 hint 一律置空（而不是回落成中文提示）。
 */
export function localizeVocabulary(vocab: StatusVocabulary, locale: Locale): StatusVocabulary {
  if (locale === 'zh-CN') return vocab;
  const scopes = { ...vocab.scopes };
  for (const s of STATUS_SCOPES) {
    scopes[s] = (vocab.scopes[s] ?? []).map((e) => ({
      ...e,
      label: CODE_TEXT_EN[`status.${s}.${e.code}`] ?? e.code,
    }));
  }
  return {
    ...vocab,
    scopes,
    pauseKinds: vocab.pauseKinds.map((e) => ({
      ...e,
      label: CODE_TEXT_EN[`pause.${e.code}`] ?? e.code,
      hint: CODE_TEXT_EN[`pause.${e.code}.hint`] ?? '',
    })),
    holdKinds: vocab.holdKinds.map((e) => ({
      ...e,
      label: CODE_TEXT_EN[`hold.${e.code}`] ?? e.code,
    })),
    holdScopes: vocab.holdScopes.map((e) => ({
      ...e,
      label: CODE_TEXT_EN[`holdScope.${e.code}`] ?? e.code,
    })),
  };
}

/**
 * 读状态词表（页面用这个，别直接调 api.statusVocabulary）。
 *
 * <p>staleTime 设 Infinity：状态词表基本不随运行变化，每个页面都重新拉一遍没意义；
 * retry 1：接口还没上线时别让页面按钮一直转圈，失败就安静用 FALLBACK 渲染。
 *
 * <p>返回的词表已按当前语种本地化：中文直接用后端 label，英文按 code 查本地表。
 */
export function useStatusVocabulary(): StatusVocabulary {
  const { data } = useQuery({
    queryKey: ['meta-status-vocabulary'],
    queryFn: api.statusVocabulary,
    staleTime: Infinity,
    retry: 1,
  });
  const { locale } = useI18n();
  return useMemo(
    () => localizeVocabulary(mergeVocabulary(FALLBACK, data), locale),
    [data, locale],
  );
}
