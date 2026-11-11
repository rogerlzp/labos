import {
  createContext, useCallback, useContext, useEffect, useMemo, useState,
  type ReactNode,
} from 'react';
import { useLocation, useNavigate } from 'react-router-dom';

/**
 * **多标签页**（页签）store。
 *
 * <p><b>为什么要有</b>：以前整个内容区只有"当前一个页面"——点左侧菜单就把上一个
 * 页面顶掉了。现场的工作方式是**同时看几个东西**：
 * 一边看实验在跑什么，一边看这条流程画得对不对，再一边盯着某个设备的状态。
 * 来回点菜单 ⇒ 每切一次都要重新加载页面、重新拉数据，现场根本没法盯。
 *
 * <p><b>做法</b>：已打开的视图记在一张表里（key = 路由 path），顶部渲染一条标签条，
 * 点标签切换、点 × 关闭。**只有当前标签的页面会挂载**（否则十个标签十个页面
 * 同时发请求，浏览器直接卡死）—— 所以标签条只负责"导航"，内容仍然由路由渲染。
 *
 * <p>标签表存 localStorage：刷新浏览器后现场正在盯的那几个页面还在。
 */
export interface TabItem {
  /** 路由 path（唯一键） */
  key: string;
  /** 标签标题（已本地化） */
  title: string;
}

interface TabsValue {
  tabs: TabItem[];
  activeKey: string;
  /** 打开一个视图：已开着就激活，没开就新开 */
  open: (key: string, title: string) => void;
  /** 关闭一个视图 */
  close: (key: string) => void;
  /** 切换到某个视图 */
  activate: (key: string) => void;
  /** 关闭全部（回到第一个） */
  closeOthers: (key: string) => void;
}

const STORAGE_KEY = 'labos.tabs.v1';

const TabsContext = createContext<TabsValue | null>(null);

function readStored(): TabItem[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    const arr = JSON.parse(raw);
    return Array.isArray(arr)
      ? arr.filter((x) => x && typeof x.key === 'string' && typeof x.title === 'string'
        // /login 是登录页，不是应用视图 ⇒ 不该留在标签条上（历史脏数据清掉）
        && x.key !== '/login')
      : [];
  } catch {
    // 存坏了就当没有（旧标签结构升级时也会走到这里）
    return [];
  }
}

export function TabsProvider({ children }: { children: ReactNode }) {
  const nav = useNavigate();
  const loc = useLocation();
  const [tabs, setTabs] = useState<TabItem[]>(readStored);

  // 持久化：刷新后现场正在盯的页面还在
  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(tabs));
    } catch {
      // 存不下就算了（隐私模式），不影响使用
    }
  }, [tabs]);

  const open = useCallback((key: string, title: string) => {
    setTabs((prev) => {
      const exist = prev.find((x) => x.key === key);
      if (exist) {
        // 已开着：只更新标题（页面的标题可能带了 id 之类的动态部分）
        return exist.title === title
          ? prev
          : prev.map((x) => (x.key === key ? { ...x, title } : x));
      }
      return [...prev, { key, title }];
    });
    nav(key);
  }, [nav]);

  const activate = useCallback((key: string) => {
    nav(key);
  }, [nav]);

  const close = useCallback((key: string) => {
    setTabs((prev) => {
      const idx = prev.findIndex((x) => x.key === key);
      if (idx < 0) return prev;
      const next = prev.filter((x) => x.key !== key);
      // 关掉的是当前页 ⇒ 切到相邻的一个（否则页面停在已关闭的路由上）
      if (key === loc.pathname) {
        const fallback = next[Math.min(idx, next.length - 1)];
        if (fallback) {
          // 下一个 tick 再导航：此刻 setTabs 还没提交，路由还是旧的
          setTimeout(() => nav(fallback.key), 0);
        } else {
          setTimeout(() => nav('/runs'), 0);
        }
      }
      return next;
    });
  }, [loc.pathname, nav]);

  const closeOthers = useCallback((key: string) => {
    setTabs((prev) => prev.filter((x) => x.key === key));
  }, []);

  const value = useMemo<TabsValue>(() => ({
    tabs,
    activeKey: loc.pathname,
    open, close, activate, closeOthers,
  }), [tabs, loc.pathname, open, close, activate, closeOthers]);

  return <TabsContext.Provider value={value}>{children}</TabsContext.Provider>;
}

export function useTabs(): TabsValue {
  const v = useContext(TabsContext);
  if (!v) {
    throw new Error('useTabs 必须在 TabsProvider 内使用');
  }
  return v;
}
