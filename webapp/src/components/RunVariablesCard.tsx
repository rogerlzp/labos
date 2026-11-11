import { useState } from 'react';
import {
  Button, Card, Empty, Input, Space, Table, Tag, Tooltip, Typography, message,
} from 'antd';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from '../api/client';
import { useReadOnly } from '../auth';
import { useT } from '../i18n';

export interface RunVarItem {
  name: string;
  type?: string | null;
  /** 流程里**声明过**这个变量（区别于运行期临时出现的） */
  declared: boolean;
  /** 声明时填的初始值 */
  defaultValue?: string | null;
  /** 这次执行的**当前值** */
  value?: string | null;
  hasValue: boolean;
  description?: string | null;
}

/**
 * 运行期变量卡片：**看得到当前值，也能改**。
 *
 * <p>为什么必须有这个：变量以前只在设计器里声明，运行期到底是多少
 * 只有跑的人才知道 —— 现场要对着参数看板数值，出问题也要能临时改一个值。
 *
 * <p><b>改值的语义</b>：正在跑的流程**下一次读变量时**看到新值。
 * {@code __wait} 节点每轮都重读变量表 ⇒ 改值能唤醒一个正在等条件的节点
 * （这就是"人工放行"的正规做法，比直接点恢复更精准）。
 *
 * <p><b>三列都要说清楚，不能混</b>：
 * <ul>
 *   <li><b>当前值</b> —— 这次执行此刻的值（后端 {@code run.input->'vars}）；</li>
 *   <li><b>初值</b> —— 流程里声明的默认初始值（没被赋值时用它）；</li>
 *   <li><b>来源</b> —— 「流程声明」还是「运行期产生」（__setVar 写的）。</li>
 * </ul>
 */
export default function RunVariablesCard({
  runId, title, style,
}: {
  runId: number;
  title?: string;
  style?: React.CSSProperties;
}) {
  const t = useT();
  const qc = useQueryClient();
  const ro = useReadOnly();
  const [editing, setEditing] = useState<string | null>(null);
  const [draft, setDraft] = useState('');

  const q = useQuery({
    queryKey: ['run-vars', runId],
    queryFn: () => apiFetch(`/api/runs/${runId}/variables`).then((r) => r.json()) as Promise<{
      runId: number; workflowId: number | null; status: string;
      editable: boolean; total: number; items: RunVarItem[];
    }>,
    enabled: Number.isFinite(runId),
    // 运行中/暂停中都可能被人改 ⇒ 跟着状态轮询
    refetchInterval: (st) => {
      const s = (st.state.data as { status?: string } | undefined)?.status;
      return s === 'running' || s === 'paused' ? 3000 : false;
    },
  });

  const items = q.data?.items ?? [];
  const editable = q.data?.editable === true && !ro.disabled;

  const save = async (name: string) => {
    try {
      const r = await apiFetch(`/api/runs/${runId}/variables`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name, value: draft }),
      });
      const j = await r.json().catch(() => ({}));
      if (!r.ok || j.ok === false) {
        message.error(j.error ?? t('runVars.saveFailed'));
        return;
      }
      message.success(t('runVars.saved', { name, value: draft }));
      setEditing(null);
      qc.invalidateQueries({ queryKey: ['run-vars', runId] });
    } catch (e) {
      message.error(String(e));
    }
  };

  return (
    <Card
      size="small" style={style}
      title={title ?? t('runVars.title')}
      extra={
        <Typography.Text type="secondary" style={{ fontSize: 11 }}>
          {t('runVars.hint')}
        </Typography.Text>
      }
    >
      {q.isLoading ? (
        <Typography.Text type="secondary">{t('runVars.loading')}</Typography.Text>
      ) : items.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('runVars.empty')} />
      ) : (
        <Table<RunVarItem>
          rowKey="name" size="small" pagination={false}
          dataSource={items}
          columns={[
            {
              title: t('runVars.col.name'), dataIndex: 'name', width: 180,
              render: (v: string, r) => (
                <Space size={4}>
                  <strong>{v}</strong>
                  {r.type ? <Tag>{r.type}</Tag> : null}
                </Space>
              ),
            },
            {
              // 当前值：这次执行此刻的值
              title: t('runVars.col.value'), dataIndex: 'value', width: 160,
              render: (v: string | null, r) => (
                editing === r.name ? (
                  <Input
                    size="small" autoFocus value={draft}
                    onChange={(e) => setDraft(e.target.value)}
                    onPressEnter={() => save(r.name)}
                    onBlur={() => setEditing(null)}
                    style={{ width: '100%' }}
                  />
                ) : (
                  <Space size={4}>
                    <span style={{
                      fontFamily: 'monospace',
                      color: r.hasValue ? '#1677ff' : '#94a3b8',
                    }}>
                      {r.hasValue ? String(v) : t('runVars.noValue')}
                    </span>
                    {editable && (
                      <Button size="small" type="link" style={{ padding: '0 4px' }}
                        onClick={() => { setEditing(r.name); setDraft(String(v ?? '')); }}>
                        {t('runVars.edit')}
                      </Button>
                    )}
                  </Space>
                )
              ),
            },
            {
              // 初值：声明的默认值（≠ 当前值）
              title: t('runVars.col.default'), dataIndex: 'defaultValue', width: 130,
              render: (v: string | null) => (
                v == null || v === '' ? (
                  <Typography.Text type="secondary">{t('runVars.noDefault')}</Typography.Text>
                ) : (
                  <span style={{ fontFamily: 'monospace' }}>{v}</span>
                )
              ),
            },
            {
              // 来源：声明的还是运行期产生的
              title: t('runVars.col.source'), width: 120,
              render: (_, r) => (
                r.declared
                  ? <Tag color="blue">{t('runVars.src.declared')}</Tag>
                  : <Tooltip title={t('runVars.src.runtimeHint')}>
                      <Tag>{t('runVars.src.runtime')}</Tag>
                    </Tooltip>
              ),
            },
            {
              title: t('runVars.col.description'), dataIndex: 'description', ellipsis: true,
              render: (v: string | null) => v ?? '—',
            },
          ]}
        />
      )}
    </Card>
  );
}
