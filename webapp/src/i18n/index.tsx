/**
 * React 层的 i18n 接入点。
 *
 * <p>核心逻辑在 {@link ./core.ts}（不依赖 React），这里只做三件事：
 * <ul>
 *   <li>把语种放进 Context，让组件能拿到 {@code locale} 与 {@code t}；</li>
 *   <li>把语种同步回 core，让 api/client.ts 这类非 React 模块读得到；</li>
 *   <li>切 dayjs 的 locale（antd 的 DatePicker 日历文字由 dayjs 决定，
 *       不受 antd locale 影响）。</li>
 * </ul>
 */
import {
  createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode,
} from 'react';
import dayjs from 'dayjs';
import 'dayjs/locale/en';
import 'dayjs/locale/zh-cn';
import {
  getLocale, persistLocale, resolveInitialLocale, setLocale as setCoreLocale, translate,
  type Locale, type MessageKey, type MessageVars,
} from './core';

export type { Locale, MessageKey, MessageVars };
export { DEFAULT_LOCALE, LOCALES, LOCALE_STORAGE_KEY } from './core';

export type TranslateFn = (key: MessageKey, vars?: MessageVars) => string;

interface I18nValue {
  /** 当前语种。 */
  locale: Locale;
  /** 切换语种（同时写入 localStorage）。 */
  setLocale: (locale: Locale) => void;
  /** 取文案。 */
  t: TranslateFn;
}

const I18nContext = createContext<I18nValue | null>(null);

/**
 * 语种 Provider。放在 ConfigProvider **外面** ——
 * antd 的 locale 要跟着语种走，所以壳层要在 Provider 内部再读一次 locale。
 */
export function I18nProvider({ children }: { children: ReactNode }) {
  const [locale, setLocaleState] = useState<Locale>(() => resolveInitialLocale());

  // 同步给非 React 模块（api/client.ts 抛错、utils/format.ts 格式化）。
  // 放在 render 期而不是 useEffect：子组件在**同一次渲染**里就该拿到正确语种，
  // 放 effect 会晚一帧，出现"英文界面 + 中文报错"的错位。
  if (getLocale() !== locale) setCoreLocale(locale);

  // dayjs 的月份/星期名由 dayjs 自己管，antd locale 管不到，必须单独切。
  useEffect(() => {
    dayjs.locale(locale === 'en-US' ? 'en' : 'zh-cn');
  }, [locale]);

  // 标签页标题与 <html lang> 跟随语种：英文部署下标题不该永远是中文，
  // lang 也要给屏幕阅读器正确提示。
  useEffect(() => {
    document.title = translate('app.title', undefined, locale);
    document.documentElement.lang = locale;
  }, [locale]);

  const setLocale = useCallback((next: Locale) => {
    setCoreLocale(next);
    persistLocale(next);
    setLocaleState(next);
  }, []);

  const value = useMemo<I18nValue>(() => ({
    locale,
    setLocale,
    t: (key, vars) => translate(key, vars, locale),
  }), [locale, setLocale]);

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

/** 读语种与文案函数；必须在 <I18nProvider> 内使用（用错时直接报错，比静默 undefined 好查）。 */
export function useI18n(): I18nValue {
  const value = useContext(I18nContext);
  if (!value) throw new Error('useI18n must be called inside <I18nProvider>');
  return value;
}

/** 只要文案函数的场合用这个。 */
export function useT(): TranslateFn {
  return useI18n().t;
}
