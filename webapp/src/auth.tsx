/**
 * 会话与权限（RBAC）：前端唯一的"我是谁、能不能写"来源。
 *
 * <p>背景：后端已把 {@code sys_user.role} 收口为 admin / operator / viewer，写操作要求
 * admin/operator，{@code /api/users} 仅 admin。前端如果只知道"有没有 token"，
 * viewer 会点得到所有写按钮，然后收到一个 403 toast —— 现场看不懂，还以为系统坏了。
 * 这里把角色拉回来，页面据此**提前**禁用写操作并说明原因。
 *
 * <p>为什么用 Context 而不是每页各查一次 /api/auth/me：会话是全局单例，
 * 每页都查既浪费也会出现"这页以为能写、那页以为只读"的闪烁。
 */
import {
  createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode,
} from 'react';
import { ApiError, api, tokenStore } from './api/client';
import { useI18n } from './i18n';
import { getLocale, translate, type Locale } from './i18n/core';

/** 后端角色取值（未知值按只读处理）。 */
export type Role = 'admin' | 'operator' | 'viewer';

/** GET /api/auth/me 返回的会话。 */
export interface Session {
  username: string;
  displayName: string;
  role: string;
}

/**
 * 角色 → antd Tag 颜色：Header 的角色 Tag 与用户管理页共用同一份。
 */
export const ROLE_COLOR: Record<string, string> = {
  admin: 'red', operator: 'blue', viewer: 'default',
};

/**
 * 角色 → 文案（未知角色原样显示，不显示空白）。
 *
 * <p>文案唯一来源是文案包（i18n/messages），这里不再另存一份中文 ——
 * 两份中文各改各的就会漂。角色不是后端词表（内核写死三值），
 * 所以中英两侧都由前端维护。
 */
export function roleLabel(role?: string | null, locale: Locale = getLocale()): string {
  if (!role) return translate('role.anonymous', undefined, locale);
  if (role === 'admin') return translate('role.admin', undefined, locale);
  if (role === 'operator') return translate('role.operator', undefined, locale);
  if (role === 'viewer') return translate('role.viewer', undefined, locale);
  return role;
}

/** 角色 → antd Tag 颜色。 */
export function roleColor(role?: string | null): string {
  return role ? (ROLE_COLOR[role] ?? 'default') : 'default';
}

/**
 * 角色是否可写。
 *
 * <p>未知角色（后端将来加角色、或 /me 字段异常）一律按只读处理 ——
 * 权限判断宁可保守，不能因为没认出来就放开写。
 */
export function roleCanWrite(role?: string | null): boolean {
  return role === 'admin' || role === 'operator';
}

interface AuthValue {
  /** 当前会话；null = 未登录 */
  session: Session | null;
  /** 正在恢复会话（有 token 时首次拿 /api/auth/me） */
  loading: boolean;
  /**
   * 会话恢复失败的**非 401** 原因（网络/内核重启/500）。
   *
   * <p>此时 token 仍然保留（可能只是内核没起来），App 给"重试 / 重新登录"两个出口，
   * 而不是像 401 那样直接踢回登录页。
   */
  error: string | null;
  /** 可执行写操作（admin/operator） */
  canWrite: boolean;
  /** 管理员（账号管理等仅 admin 的能力） */
  isAdmin: boolean;
  /** 重新拉会话：登录成功后调用 */
  refresh: () => Promise<void>;
  /** 退出：调后端 logout，失败也清本地 token */
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthValue | null>(null);

/**
 * 会话 Provider。放在 QueryClientProvider 内即可（本身不依赖 react-query）。
 *
 * <p>挂载时：有 token → 调 /api/auth/me 恢复会话（显示 loading，避免闪一下登录页）；
 * 无 token → 直接未登录。
 *
 * <p><b>失败分两类</b>（2026-11-03 收尾）：401 说明 token 真失效 → 清 token 回登录页；
 * 网络错误 / 500 / 内核重启 → **保留 token**，暴露 error 让 App 显示"重试 / 重新登录"。
 * 之前一律清 token 的后果是：内核重启一次，全场值班账号一起被踢回登录页。
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null);
  const [loading, setLoading] = useState<boolean>(!!tokenStore.get());
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    if (!tokenStore.get()) {
      setSession(null);
      setError(null);
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const me = await api.me();
      setSession({ username: me.username, displayName: me.displayName, role: me.role });
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        // token 确实失效：client 已清 token 并广播，这里确保会话归零
        tokenStore.clear();
        setSession(null);
        setError(null);
      } else {
        // 网络/内核未起/500：token 可能还有效，不能替用户"登出"；只报错并留重试入口
        setSession(null);
        setError(String((e as Error)?.message || e));
      }
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void refresh(); }, [refresh]);

  // 任意接口 401（client 统一 broadcast）：会话立即失效，回登录页（自动回登录保留）
  useEffect(() => {
    const onUnauthorized = () => {
      setSession(null);
      setLoading(false);
      setError(null);
    };
    window.addEventListener('labos:unauthorized', onUnauthorized);
    return () => window.removeEventListener('labos:unauthorized', onUnauthorized);
  }, []);

  const logout = useCallback(async () => {
    try {
      await api.logout();
    } catch {
      // token 已失效/内核没起也照样退：本地必须清干净，不能让用户卡在"退不出去"
    }
    tokenStore.clear();
    setSession(null);
    setError(null);
  }, []);

  const value = useMemo<AuthValue>(() => ({
    session,
    loading,
    error,
    canWrite: roleCanWrite(session?.role),
    isAdmin: session?.role === 'admin',
    refresh,
    logout,
  }), [session, loading, error, refresh, logout]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

/** 读会话；必须在 <AuthProvider> 内使用（用错时直接报错，比静默 undefined 好查）。 */
export function useAuth(): AuthValue {
  const value = useContext(AuthContext);
  if (!value) throw new Error('useAuth must be called inside <AuthProvider>');
  return value;
}

/**
 * 写操作按钮的只读属性，用法 {@code <Button {...ro} onClick={...}>}。
 *
 * <p>用原生 title 而不是 antd Tooltip：disabled 的按钮不触发 Tooltip（鼠标事件被丢弃），
 * 但原生 title 在 Chrome/Firefox 仍会显示，正好满足"禁用 + 告诉为什么"。
 */
export function useReadOnly(): { disabled: boolean; title?: string } {
  const { canWrite } = useAuth();
  const { locale } = useI18n();
  return canWrite
    ? { disabled: false }
    : { disabled: true, title: translate('auth.readOnlyTitle', undefined, locale) };
}
