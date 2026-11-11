import { useEffect, useMemo } from 'react';
import { Alert, Button, Layout, Menu, Spin, Typography, Tag, Space, Select } from 'antd';
import TabBar from './app/TabBar';
import { NAV, titleFor } from './app/nav';
import { TabsProvider, useTabs } from './app/tabs';
import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import RunsPage from './pages/Runs';
import PausedRunsPage from './pages/PausedRuns';
import ManualWindowsPage from './pages/ManualWindows';
import QuarantinePage from './pages/Quarantine';
import HandoffsPage from './pages/Handoffs';
import PlatesPage from './pages/Plates';
import PlateTracesPage from './pages/PlateTraces';
import RunDetailPage from './pages/RunDetail';
import RunFlowViewPage from './pages/RunFlowView';
import WorkflowsPage from './pages/Workflows';
import DesignerPage from './pages/Designer';
import AlertsPage from './pages/Alerts';
import PoliciesPage from './pages/Policies';
import VariablesPage from './pages/Variables';
import ErrorCodesPage from './pages/ErrorCodes';
import GanttPage from './pages/Gantt';
import DurationsPage from './pages/Durations';
import ExperimentsPage from './pages/Experiments';
import ExperimentRunsPage from './pages/ExperimentRuns';
import ExperimentRunsListPage from './pages/ExperimentRunsList';
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

const { Header, Sider, Content } = Layout;

/**
 * 外壳：只负责把标签页能力包上去。
 *
 * <p>为什么单独一层：标签状态要跨整棵树（菜单点击、路由变化、标签条），
 * 而 App 里还有"未登录 → 登录页"的早退分支 —— 早退时标签能力用不上，
 * 但 Provider 在外层就没这个问题。
 */
export default function App() {
  return (
    <TabsProvider>
      <AppInner />
    </TabsProvider>
  );
}

function AppInner() {
  const loc = useLocation();
  const { session, loading, error: authError, isAdmin, refresh, logout } = useAuth();
  const { t, locale, setLocale } = useI18n();
  const { open, tabs } = useTabs();

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

  /**
   * 路由变了 ⇒ 把这个页面登记进标签表。
   *
   * <p>为什么要自动登记：现场从**列表点进详情**、从**台账跳到流程图**，
   * 这些是页内跳转 —— 如果不登记，那个页面就没法在标签条上切回去。
   * 登记时用 `titleFor` 算标题（静态路径查菜单表，动态路径带 id）。
   */
  useEffect(() => {
    const path = loc.pathname;
    // /login 不是应用视图（登录页），不该出现在标签条上
    if (!path || path === '/' || path === '/login') return;
    if (!tabs.some((x) => x.key === path)) {
      open(path, titleFor(path, t));
    }
    // 依赖只放 path：open/t 是稳定的引用，标题变化不影响"要不要登记"
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loc.pathname]);

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
              // **开标签而不是替换**：现场要同时看几个视图，来回点菜单不该把上一个顶掉
              if (key.startsWith('/')) open(key, titleFor(key, t));
            }}
          />
        </Sider>

        <Content style={{ padding: 20, overflow: 'auto', background: '#0d1014' }}>
          <TabBar />
          <Routes>
            <Route path="/" element={<Navigate to="/runs" replace />} />
            {/* 登录成功后 URL 停在 /login ⇒ 这里没有匹配路由 ⇒ 内容区空白
                （2026-11-11 发现：登录后一片白，只剩菜单）。⇒ 把它重定向回运行列表。 */}
            <Route path="/login" element={<Navigate to="/runs" replace />} />
            <Route path="/runs" element={<RunsPage />} />
            <Route path="/scheduler" element={<SchedulerPage />} />
            <Route path="/gantt" element={<GanttPage />} />
            <Route path="/runs/:id" element={<RunDetailPage />} />
            {/* 只读运行态流程页（与设计器分开）：有 run 看这次执行的状态着色 */}
            <Route path="/runs/:runId/flow" element={<RunFlowViewPage />} />
            <Route path="/workflows/:workflowId/flow" element={<RunFlowViewPage />} />
            <Route path="/paused" element={<PausedRunsPage />} />
            <Route path="/manual-windows" element={<ManualWindowsPage />} />
            <Route path="/quarantined" element={<QuarantinePage />} />
            <Route path="/handoffs" element={<HandoffsPage />} />
            <Route path="/plates" element={<PlatesPage />} />
            {/* 板子流转：板号在设备之间怎么流转的（执行过程的每一次放板/取板都留痕） */}
            <Route path="/plate-traces" element={<PlateTracesPage />} />
            <Route path="/workflows" element={<WorkflowsPage />} />
            {/* 列表 → 详情：/experiments 看全量，/experiments/:id 才是编排页 */}
            <Route path="/experiments" element={<ExperimentListPage />} />
            <Route path="/experiments/:id" element={<ExperimentsPage />} />
            {/* 实验的**执行记录**单独一页：配置与历史分开，现场看着不乱 */}
            <Route path="/experiments/:id/runs" element={<ExperimentRunsPage />} />
            {/* 全局实验执行记录总表（菜单入口） */}
            <Route path="/experiment-runs" element={<ExperimentRunsListPage />} />
            <Route path="/experiments/:id/canvas" element={<ExperimentCanvasPage />} />
            <Route path="/canvas" element={<ExperimentCanvasPage />} />
            <Route path="/canvas/:id" element={<ExperimentCanvasPage />} />
            <Route path="/island-flow" element={<IslandFlowPage />} />
            <Route path="/designer/:id" element={<DesignerPage />} />
            <Route path="/alerts" element={<AlertsPage />} />
            <Route path="/policies" element={<PoliciesPage />} />
            <Route path="/variables" element={<VariablesPage />} />
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
