import { useMemo, type ReactNode } from 'react';
import { Alert, Button, Layout, Menu, Spin, Typography, Tag, Space, Select } from 'antd';
import {
  DatabaseOutlined,
  DeploymentUnitOutlined,
  PlayCircleOutlined,
  BarChartOutlined,
  ToolOutlined,
  SettingOutlined,
} from '@ant-design/icons';
import { Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import RunsPage from './pages/Runs';
import PausedRunsPage from './pages/PausedRuns';
import ManualWindowsPage from './pages/ManualWindows';
import QuarantinePage from './pages/Quarantine';
import PlatesPage from './pages/Plates';
import RunDetailPage from './pages/RunDetail';
import WorkflowsPage from './pages/Workflows';
import DesignerPage from './pages/Designer';
import AlertsPage from './pages/Alerts';
import PoliciesPage from './pages/Policies';
import ErrorCodesPage from './pages/ErrorCodes';
import GanttPage from './pages/Gantt';
import DurationsPage from './pages/Durations';
import ExperimentsPage from './pages/Experiments';
import ExperimentListPage from './pages/ExperimentList';
import ScheduleGanttPage from './pages/ScheduleGantt';
import SchedulerPage from './pages/Scheduler';
import ExperimentCanvasPage from './pages/ExperimentCanvas';
import IslandFlowPage from './pages/IslandFlow';
import LoginPage from './pages/Login';
import IslandPage from './pages/Island';
import UsersPage from './pages/Users';
import AuditPage from './pages/Audit';
import { roleColor, roleLabel, useAuth } from './auth';
import TopologyPage from './pages/Topology';
import ModelLibraryPage from './pages/ModelLibrary';
import { useI18n } from './i18n';
import type { MessageKey } from './i18n/core';

const { Header, Sider, Content } = Layout;

/** 导航项：文案走 key，不写死中文（渲染时按当前语种查表）。 */
interface NavChild {
  key: string;
  labelKey: MessageKey;
  icon?: ReactNode;
}

interface NavGroup {
  key: string;
  labelKey: MessageKey;
  icon: ReactNode;
  children: NavChild[];
}

/**
 * 导航按角色任务分，不是按数据表分。
 * 详见 docs/UI-界面设计说明书.md 第 2 章。
 *
 * <p>label 存的是文案 key 而不是中文本身：英文版靠同一份 key 查另一张表，
 * 不需要第二份菜单结构（两份结构迟早会漂）。
 */
const NAV: NavGroup[] = [
  {
    key: 'author',
    labelKey: 'nav.group.author',
    icon: <DeploymentUnitOutlined />,
    children: [
      { key: '/workflows', labelKey: 'nav.workflows' },
      { key: '/experiments', labelKey: 'nav.experiments' },
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
      { key: '/paused', labelKey: 'nav.paused' },
      { key: '/manual-windows', labelKey: 'nav.manualWindows' },
      { key: '/quarantined', labelKey: 'nav.quarantined' },
      { key: '/plates', labelKey: 'nav.plates' },
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
      { key: '/policies', labelKey: 'nav.policies' },
      { key: '/error-codes', labelKey: 'nav.errorCodes' },
    ],
  },
  {
    key: 'system',
    labelKey: 'nav.group.system',
    icon: <SettingOutlined />,
    children: [
      { key: '/users', labelKey: 'nav.users' },
      { key: '/audit', labelKey: 'nav.audit' },
    ],
  },
];

export default function App() {
  const nav = useNavigate();
  const loc = useLocation();
  const { session, loading, error: authError, isAdmin, refresh, logout } = useAuth();
  const { t, locale, setLocale } = useI18n();

  /**
   * 菜单按角色过滤：用户管理只有 admin 能看。
   *
   * <p>不这么做的话 viewer 点进去只会拿到 /api/users 的 403，页面停在"加载失败"，
   * 用户以为系统坏了而不是"我没权限"。
   */
  const navItems = useMemo(() => {
    const groups = isAdmin
      ? NAV
      : NAV.map((group) => (group.key === 'system'
        ? { ...group, children: group.children.filter((item) => item.key !== '/users') }
        : group));
    // 文案在这里才查表：同一份菜单结构，中英文各查各的表
    return groups.map((g) => ({
      key: g.key,
      label: t(g.labelKey),
      icon: g.icon,
      children: g.children.map((c) => ({ key: c.key, label: t(c.labelKey), icon: c.icon })),
    }));
  }, [isAdmin, t]);

  // 有 token 时先恢复会话：这期间既不闪登录页，也不闪空菜单
  if (loading) {
    return (
      <div style={{
        height: '100vh', display: 'flex', flexDirection: 'column', gap: 12,
        alignItems: 'center', justifyContent: 'center', background: '#0d1014',
      }}>
        <Spin size="large" />
        <Typography.Text type="secondary">{t('app.header.restoring')}</Typography.Text>
      </div>
    );
  }
  if (!session) {
    // 恢复会话失败但不是 401（网络/内核重启/500）：token 还在，给"重试 / 重新登录"，
    // 不白屏、也不像 401 那样直接把人踢回登录页。
    if (authError) {
      return (
        <div style={{
          height: '100vh', display: 'flex', alignItems: 'center',
          justifyContent: 'center', background: '#0d1014', padding: 20,
        }}>
          <Alert
            type="error" showIcon style={{ maxWidth: 620 }}
            message={t('app.error.restoreFailed')}
            description={t('app.error.restoreFailedDesc', { error: authError })}
            action={
              <Space direction="vertical" size={8}>
                <Button type="primary" size="small" onClick={() => { void refresh(); }}>
                  {t('app.action.retry')}
                </Button>
                <Button size="small" onClick={() => { void logout(); }}>
                  {t('app.action.relogin')}
                </Button>
              </Space>
            }
          />
        </div>
      );
    }
    // 登录页只负责拿 token；"我是谁 / 什么角色"统一由 refresh 从 /api/auth/me 拉
    return <LoginPage onSuccess={() => { void refresh(); }} />;
  }

  return (
    <Layout style={{ height: '100vh' }}>
      <Header
        style={{
          display: 'flex', alignItems: 'center', gap: 16,
          padding: '0 20px', background: '#151920', borderBottom: '1px solid #262c38',
        }}
      >
        <Typography.Text strong style={{ fontSize: 15 }}>{t('app.title')}</Typography.Text>
        <Tag color="blue">{t('app.tag.intranet')}</Tag>
        <div style={{ flex: 1 }} />
        <Space size={12}>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {t('app.header.kernel')}
          </Typography.Text>
          {/* 先亮角色：只读账号的写按钮是禁用的，不说清"你是谁"会以为按钮坏了 */}
          <Tag color={roleColor(session.role)}>{roleLabel(session.role, locale)}</Tag>
          <Typography.Text style={{ fontSize: 12 }}>
            {session.displayName}（{session.username}）
          </Typography.Text>
          <Button size="small" onClick={() => { void logout(); }}>{t('app.action.logout')}</Button>
          {/* 语言切换：出海版本需要现场自行切中/英，不依赖构建参数 */}
          <Select
            size="small"
            value={locale}
            onChange={(v) => setLocale(v as 'zh-CN' | 'en-US')}
            style={{ width: 108 }}
            options={[
              { value: 'zh-CN', label: '中文' },
              { value: 'en-US', label: 'English' },
            ]}
          />
        </Space>
      </Header>

      <Layout>
        <Sider width={180} style={{ background: '#151920', borderRight: '1px solid #262c38' }}>
          <Menu
            mode="inline"
            theme="dark"
            style={{ background: 'transparent', borderInlineEnd: 'none' }}
            selectedKeys={[loc.pathname]}
            defaultOpenKeys={['author', 'run', 'ops']}
            items={navItems}
            onClick={({ key }) => {
              if (key.startsWith('/')) nav(key);
            }}
          />
        </Sider>

        <Content style={{ padding: 20, overflow: 'auto', background: '#0d1014' }}>
          <Routes>
            <Route path="/" element={<Navigate to="/runs" replace />} />
            <Route path="/runs" element={<RunsPage />} />
            <Route path="/scheduler" element={<SchedulerPage />} />
            <Route path="/gantt" element={<GanttPage />} />
            <Route path="/runs/:id" element={<RunDetailPage />} />
            <Route path="/paused" element={<PausedRunsPage />} />
            <Route path="/manual-windows" element={<ManualWindowsPage />} />
            <Route path="/quarantined" element={<QuarantinePage />} />
            <Route path="/plates" element={<PlatesPage />} />
            <Route path="/workflows" element={<WorkflowsPage />} />
            {/* 列表 → 详情：/experiments 看全量，/experiments/:id 才是编排页 */}
            <Route path="/experiments" element={<ExperimentListPage />} />
            <Route path="/experiments/:id" element={<ExperimentsPage />} />
            <Route path="/experiments/:id/canvas" element={<ExperimentCanvasPage />} />
            <Route path="/canvas" element={<ExperimentCanvasPage />} />
            <Route path="/canvas/:id" element={<ExperimentCanvasPage />} />
            <Route path="/island-flow" element={<IslandFlowPage />} />
            <Route path="/designer/:id" element={<DesignerPage />} />
            <Route path="/alerts" element={<AlertsPage />} />
            <Route path="/policies" element={<PoliciesPage />} />
            <Route path="/error-codes" element={<ErrorCodesPage />} />
            <Route path="/schedule" element={<ScheduleGanttPage />} />
            <Route path="/duration" element={<DurationsPage />} />
            {/* 账号管理仅 admin：非管理员直接给结论，不让它先请求再吃 403 */}
            <Route path="/users" element={isAdmin ? <UsersPage /> : (
              <Alert
                type="warning" showIcon message={t('app.error.adminOnly')}
                description={t('app.error.adminOnlyDesc', {
                  name: session.displayName,
                  username: session.username,
                  role: roleLabel(session.role, locale),
                })}
              />
            )} />
            <Route path="/audit" element={<AuditPage />} />
            <Route path="/topology" element={<TopologyPage />} />
            <Route path="/models" element={<ModelLibraryPage />} />
            <Route path="/island" element={<IslandPage />} />
          </Routes>
        </Content>
      </Layout>
    </Layout>
  );
}
