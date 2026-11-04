import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ConfigProvider, theme } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import enUS from 'antd/locale/en_US';
import App from './App';
import { AuthProvider } from './auth';
import { I18nProvider, useI18n } from './i18n';
import './index.css';

/**
 * 全局查询客户端。
 *
 * 中控场景下数据变化快但人工操作慢，所以：
 * - staleTime 短（10s）：实时看板需要新鲜数据
 * - 失败重试 2 次：内网偶发抖动不该让页面空白
 * - refetchOnWindowFocus 关：值班员切窗口不该触发全量刷新
 */
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 10_000,
      retry: 2,
      refetchOnWindowFocus: false,
    },
  },
});

/**
 * 应用外壳。
 *
 * <p>为什么单独拆一个组件：antd 的 locale 要跟着当前语种走，
 * 而语种存在 I18nProvider 的 Context 里 —— 必须在 Provider **内部**再读一次。
 */
function Shell() {
  const { locale } = useI18n();
  return (
    <ConfigProvider
      locale={locale === 'en-US' ? enUS : zhCN}
      theme={{
        algorithm: theme.darkAlgorithm,
        token: {
          colorPrimary: '#3b82f6',
          borderRadius: 6,
          fontSize: 13,
        },
      }}
    >
      <BrowserRouter>
        {/* 会话/角色 Provider 包在 QueryClientProvider 与 Router 内，App 与所有页面共用 */}
        <AuthProvider>
          <App />
        </AuthProvider>
      </BrowserRouter>
    </ConfigProvider>
  );
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    {/* 语种 Provider 放在最外层：antd locale、页面文案、错误文案都从这里取 */}
    <I18nProvider>
      <QueryClientProvider client={queryClient}>
        <Shell />
      </QueryClientProvider>
    </I18nProvider>
  </React.StrictMode>,
);
