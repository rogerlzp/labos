import type { ReactNode } from 'react';
import {
  BarChartOutlined, DatabaseOutlined, DeploymentUnitOutlined,
  PlayCircleOutlined, SettingOutlined, ToolOutlined,
} from '@ant-design/icons';
import type { MessageKey } from '../i18n/core';
import type { Role } from '../auth';

/** 导航项：文案走 key，不写死中文（渲染时按当前语种查表）。 */
export interface NavChild {
  key: string;
  labelKey: MessageKey;
  icon?: ReactNode;
  /** 可见角色；缺省 = 全部角色可见（逐项填 roles 是可分批的独立改动）。 */
  roles?: Role[];
}

export interface NavGroup {
  key: string;
  labelKey: MessageKey;
  icon: ReactNode;
  children: NavChild[];
  /** 整组可见角色；缺省 = 全部角色可见。 */
  roles?: Role[];
}

/**
 * 导航按角色任务分，不是按数据表分。
 * 详见 docs/UI-界面设计说明书.md 第 2 章。
 *
 * <p>label 存的是文案 key 而不是中文本身：英文版靠同一份 key 查另一张表，
 * 不需要第二份菜单结构（两份结构迟早会漂）。
 *
 * <p>放在独立模块里：标签条的标题推导（`titleFor`）也要用这张表，
 * 两处共用一份才不会出现"菜单叫什么、标签叫什么"不一致。
 */
export const NAV: NavGroup[] = [
  {
    key: 'author',
    labelKey: 'nav.group.author',
    icon: <DeploymentUnitOutlined />,
    children: [
      { key: '/workflows', labelKey: 'nav.workflows' },
      { key: '/experiments', labelKey: 'nav.experiments' },
      // 实验的**执行记录**总表：所有实验跑过哪几次、现在哪些卡着
      { key: '/experiment-runs', labelKey: 'nav.experimentRuns' },
      { key: '/canvas', labelKey: 'nav.canvas' },
    ],
  },
  {
    key: 'run',
    labelKey: 'nav.group.run',
    icon: <PlayCircleOutlined />,
    children: [
      { key: '/runs', labelKey: 'nav.runs' },
      { key: '/scheduler', labelKey: 'nav.scheduler' },
      { key: '/schedule', labelKey: 'nav.schedule' },
      // 排程参数管理（designer/admin）
      { key: '/schedule-plans', labelKey: 'nav.schedulePlans', roles: ['designer', 'admin'] },
      { key: '/paused', labelKey: 'nav.paused' },
      { key: '/manual-windows', labelKey: 'nav.manualWindows' },
      { key: '/quarantined', labelKey: 'nav.quarantined' },
      { key: '/handoffs', labelKey: 'nav.handoffs' },
      // 操作员工作台：样品/试剂/耗材放哪、一键启动实验
      { key: '/material-config', labelKey: 'nav.materialConfig' },
      { key: '/plates', labelKey: 'nav.plates' },
      // 板子流转：板号在设备之间怎么流转的
      { key: '/plate-traces', labelKey: 'nav.plateTraces' },
    ],
  },
  {
    key: 'analyse',
    labelKey: 'nav.group.analyse',
    icon: <BarChartOutlined />,
    children: [
      { key: '/gantt', labelKey: 'nav.gantt' },
      { key: '/duration', labelKey: 'nav.duration' },
    ],
  },
  {
    key: 'ops',
    labelKey: 'nav.group.ops',
    icon: <ToolOutlined />,
    children: [
      { key: '/alerts', labelKey: 'nav.alerts' },
      { key: '/topology', labelKey: 'nav.topology' },
      // 型号库是本组里唯一"数据对账"入口，给个图标便于评审时快速定位
      // （其余子项无图标，若要求视觉完全统一可去掉 icon）
      { key: '/models', labelKey: 'nav.models', icon: <DatabaseOutlined /> },
      { key: '/island', labelKey: 'nav.island' },
      { key: '/island-flow', labelKey: 'nav.islandFlow' },
      // 位置/耗材两个视角看整个岛，并可关联设备修改
      { key: '/positions', labelKey: 'nav.positions' },
      { key: '/consumables', labelKey: 'nav.consumables' },
      { key: '/policies', labelKey: 'nav.policies' },
      { key: '/variables', labelKey: 'nav.variables' },
      { key: '/error-codes', labelKey: 'nav.errorCodes' },
      // S2 物料身份与状态验收：用例清单 + 页面内运行 + 脚本结果回填
      { key: '/s2-test-cases', labelKey: 'nav.s2TestCases', roles: ['admin'] },
    ],
  },
  {
    key: 'system',
    labelKey: 'nav.group.system',
    icon: <SettingOutlined />,
    children: [
      { key: '/users', labelKey: 'nav.users', roles: ['admin'] },
      { key: '/audit', labelKey: 'nav.audit', roles: ['admin'] },
      // 机械臂调试台（仅管理员）
      { key: '/arm-console', labelKey: 'nav.armConsole', roles: ['admin'] },
    ],
  },
];

/**
 * 动态路由 → 标签标题的规则。
 *
 * <p>为什么要动态标题：现场同时开着几个视图，标签上只写「运行列表」分不清
 * 是哪一个 ⇒ 带上 id 才认得出。
 */
const DYNAMIC: Array<{ re: RegExp; key: MessageKey; withId?: boolean }> = [
  { re: /^\/runs\/(\d+)\/flow$/, key: 'tabs.runFlow', withId: true },
  { re: /^\/runs\/(\d+)$/, key: 'tabs.runDetail', withId: true },
  { re: /^\/workflows\/(\d+)\/flow$/, key: 'tabs.workflowFlow', withId: true },
  { re: /^\/experiments\/(\d+)\/runs$/, key: 'tabs.experimentRuns', withId: true },
  { re: /^\/experiments\/(\d+)\/canvas$/, key: 'tabs.experimentCanvas', withId: true },
  { re: /^\/experiments\/(\d+)$/, key: 'tabs.experiment', withId: true },
  { re: /^\/canvas\/(\d+)$/, key: 'tabs.experimentCanvas', withId: true },
  { re: /^\/designer\/(\d+)$/, key: 'tabs.designer', withId: true },
];

/**
 * 路由 path → 标签标题。
 *
 * @param t i18n 取文案（动态标题要用当前语种）
 */
export function titleFor(
  path: string,
  t: (k: MessageKey, vars?: Record<string, string | number>) => string,
): string {
  for (const d of DYNAMIC) {
    const m = path.match(d.re);
    if (m) {
      return d.withId && m[1] ? `${t(d.key)} #${m[1]}` : t(d.key);
    }
  }
  for (const g of NAV) {
    const hit = g.children.find((c) => c.key === path);
    if (hit) return t(hit.labelKey);
  }
  return path;
}
