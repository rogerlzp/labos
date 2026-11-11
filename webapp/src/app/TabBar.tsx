import { Tabs, Tooltip } from 'antd';
import { useTabs } from './tabs';

/**
 * 顶部**标签条**：同时打开多个视图，现场能来回切。
 *
 * <p><b>关键实现点</b>：这里只渲染**标签条**，不渲染标签的内容 ——
 * 内容仍然由路由决定（只有当前标签的页面会挂载）。
 * 否则十个标签的页面同时挂载 ⇒ 十倍的请求同时打出去，浏览器直接卡死。
 *
 * <p>标签标题从路由推导：静态路径查菜单表，动态路径（{@code /runs/123}）
 * 用「类型 + id」，现场一眼知道在看哪个。
 */
export default function TabBar() {
  const { tabs, activeKey, activate, close, closeOthers } = useTabs();

  if (tabs.length === 0) return null;

  const items = tabs.map((t) => ({
    key: t.key,
    label: (
      <Tooltip title={t.key} mouseEnterDelay={0.6}>
        <span
          onDoubleClick={() => closeOthers(t.key)}
          onContextMenu={(e) => {
            // 右键：关掉其它（现场常见操作：看一眼就关掉）
            e.preventDefault();
            closeOthers(t.key);
          }}
        >
          {t.title}
        </span>
      </Tooltip>
    ),
    closable: true,
    children: null,
  }));

  return (
    <div style={{
      marginBottom: 12, marginInline: -20, marginTop: -20,
      paddingInline: 12, background: '#11151c', borderBottom: '1px solid #262c38',
    }}>
      <Tabs
        type="editable-card"
        hideAdd
        size="small"
        activeKey={tabs.some((t) => t.key === activeKey) ? activeKey : undefined}
        items={items}
        onChange={(k) => activate(k)}
        onEdit={(k, action) => {
          if (action === 'remove') close(String(k));
        }}
        tabBarExtraContent={
          <span style={{ fontSize: 11, color: '#6b7280', paddingInline: 8 }}>
            双击标签＝只留这一个
          </span>
        }
        className="labos-tabbar"
      />
    </div>
  );
}
