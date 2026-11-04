/**
 * i18n 运行时核心（**不依赖 React**）。
 *
 * <p>为什么要自己写而不是引 i18next / react-intl：
 * <ul>
 *   <li>本项目前端零 UI 框架外的额外依赖是硬约束（内核是 JDK 17 零依赖，
 *       前端也不该为了换语言引入一整套运行时）；</li>
 *   <li>实际需求只有两件事：**按 key 取文案** + **按领域 code 取文案**，
 *       不需要复数、性别、插值管线这些重武器；</li>
 *   <li>自研的代价是"两个语言包必须键对齐"，这个用 TypeScript 类型强制
 *       （见 messages/en-US.ts 的 {@code Record<keyof typeof zhCN, string>}），
 *       编译期就报错，不靠人肉核对。</li>
 * </ul>
 *
 * <p>本文件被 React 层（index.tsx）与非 React 模块（api/client.ts、utils/format.ts）
 * 共用，所以**不能 import React**。
 */
import { zhCN } from './messages/zh-CN';
import { enUS } from './messages/en-US';

/** 支持的语种。中文是产品默认（现场值班员以中文为准），英文是出海版本。 */
export type Locale = 'zh-CN' | 'en-US';

export const DEFAULT_LOCALE: Locale = 'zh-CN';
export const LOCALES: readonly Locale[] = ['zh-CN', 'en-US'];

/** localStorage 键：记住上次选择的语种（换语言不该每次都重选）。 */
export const LOCALE_STORAGE_KEY = 'labos.locale';

/** 文案 key 的全集由中文包定义 —— 中文是"主包"，英文包必须与它逐键对齐。 */
export type MessageKey = keyof typeof zhCN;

/** 插值变量：文案里的 {name} 占位符。 */
export type MessageVars = Record<string, string | number>;

const PACKS: Record<Locale, Record<MessageKey, string>> = {
  'zh-CN': zhCN,
  'en-US': enUS,
};

/**
 * 当前语种（模块级）。
 *
 * <p>为什么要模块级：api/client.ts 抛异常、utils/format.ts 格式化等待时间，
 * 这两处都不是 React 组件，拿不到 Context。Provider 会在每次渲染时同步这里
 * （见 index.tsx），所以它们读到的就是当前界面语种。
 */
let currentLocale: Locale = DEFAULT_LOCALE;

export function getLocale(): Locale {
  return currentLocale;
}

/** 设置当前语种（不做持久化；持久化由 React 层的 persistLocale 负责）。 */
export function setLocale(locale: Locale): void {
  currentLocale = locale;
}

export function isLocale(v: unknown): v is Locale {
  return v === 'zh-CN' || v === 'en-US';
}

/**
 * 首次进入时的语种：**localStorage > 构建期 VITE_DEFAULT_LOCALE > 默认中文**。
 *
 * <p>为什么不做浏览器语言探测：e2e 跑在默认 en-US 的浏览器里，
 * 自动跟随浏览器会让"中文界面"变成"看运气"。要跟随浏览器必须显式开开关。
 */
export function resolveInitialLocale(): Locale {
  try {
    const saved = localStorage.getItem(LOCALE_STORAGE_KEY);
    if (isLocale(saved)) return saved;
  } catch {
    // 隐私模式 / 无 localStorage：忽略，走构建期或默认值
  }
  const env = import.meta.env.VITE_DEFAULT_LOCALE;
  if (isLocale(env)) return env;
  return DEFAULT_LOCALE;
}

/** 记住用户选择。存不进去（隐私模式）不影响功能，只是下次要重选。 */
export function persistLocale(locale: Locale): void {
  try {
    localStorage.setItem(LOCALE_STORAGE_KEY, locale);
  } catch {
    // 忽略：语种切换不能因为存不了 localStorage 就抛错
  }
}

/**
 * 取文案。
 *
 * <p>缺 key 的处理：回落到**英文包**，再回落到 key 本身，**绝不抛异常** ——
 * 一条文案缺失不该让整页白屏。
 *
 * @param vars 插值变量，替换文案里的 {name}
 */
export function translate(
  key: MessageKey,
  vars?: MessageVars,
  locale: Locale = currentLocale,
): string {
  const raw = PACKS[locale][key] ?? PACKS[DEFAULT_LOCALE][key] ?? key;
  if (!vars) return raw;
  return raw.replace(/\{(\w+)\}/g, (m, name: string) => {
    const v = vars[name];
    return v === undefined || v === null ? m : String(v);
  });
}
