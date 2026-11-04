/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE?: string;
  /** 构建期默认语种（'zh-CN' | 'en-US'）。不设则默认中文；用户可在界面里切换。 */
  readonly VITE_DEFAULT_LOCALE?: string;
}
