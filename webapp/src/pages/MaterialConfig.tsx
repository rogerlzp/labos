import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert, Button, Card, Empty, Select, Space, Steps, Table, Tag, Typography, message,
} from 'antd';
import { apiFetch } from '../api/client';
import { useOperateOnly } from '../auth';
import { useT } from '../i18n';

/**
 * 操作员工作台：模板 → 实例化 → 配位置 → 核验 → 启动。
 *
 * <p>为什么这一页是"应用者/操作员"的终点：工程师在 `/designer` 维护工艺图，
 * 应用者在 `/templates` 组合模板，操作员在这里只需要回答两件事：
 * 「这批用什么模板」「东西放在哪」。**不该再回到设计器或流程库**。
 *
 * <p>深链：`/material-config?templateId=7&runId=123`（模板页的"实例化并配位置"
 * 直接跳到这里）——刷新/转发链接都不会丢上下文。
 */

interface Template {
  id: number; templateNo: string; name: string; status: string; islandId?: number;
}
interface Position {
  pointId: number | null; code: string; parentDeviceCode?: string | null;
}
interface Placement {
  id: number; roleCode: string; materialKind: string; materialRef?: number | null;
  quantity: number; required: boolean; status: string;
  positionId?: number | null; positionCode?: string | null; deviceCode?: string | null;
  lifecycle?: string | null;
}
interface Island { id: number; code: string; name?: string }

const STATUS_COLOR: Record<string, string> = {
  unassigned: 'default',
  assigned: 'blue',
  verified: 'green',
  loaded: 'cyan',
  consumed: 'purple',
  unloaded: 'default',
  absent: 'red',
};

export default function MaterialConfigPage() {
  const t = useT();
  const ro = useOperateOnly();
  const nav = useNavigate();
  const qc = useQueryClient();
  const [sp, setSp] = useSearchParams();

  /**
   * 深链参数：**从 location 直接读**而不是只用 useSearchParams。
   *
   * <p>实测：首帧 `useSearchParams()` 拿到的可能是空串（路由已带 query，但 hook
   * 还没同步），照它初始化会把参数当"没有"⇒ 紧接着的 effect 把 URL 洗干净，
   * 深链就失效了。location 是浏览器真值，不依赖渲染时序。
   */
  const initial = (() => {
    const q = new URLSearchParams(
      typeof window === 'undefined' ? '' : window.location.search);
    const tid = Number(q.get('templateId'));
    const rid = Number(q.get('runId'));
    return {
      templateId: Number.isFinite(tid) && tid > 0 ? tid : undefined,
      runId: Number.isFinite(rid) && rid > 0 ? rid : undefined,
    };
  })();
  const [templateId, setTemplateId] = useState<number | undefined>(initial.templateId);
  const [runId, setRunId] = useState<number | undefined>(initial.runId);
  const [busy, setBusy] = useState(false);

  const templatesQ = useQuery({
    queryKey: ['experiment-templates'],
    queryFn: () => apiFetch('/api/experiment-templates')
      .then((r) => r.json()) as Promise<{ items: Template[] }>,
  });
  const templates = templatesQ.data?.items ?? [];

  const islandsQ = useQuery({
    queryKey: ['islands'],
    queryFn: () => apiFetch('/api/topology')
      .then((r) => r.json()) as Promise<{ islands?: Island[] }>,
  });
  const islands = islandsQ.data?.islands ?? [];

  /**
   * 岛：**模板自带**（模板的岛就是执行边界），只有模板没设岛时才让用户选。
   * 以前这里写死了一个 1000005，跟真实岛（LAB-EXT-01 = 1000505）对不上，
   * 结果配位置的下拉永远是空的。
   */
  const templateIslandId = useMemo(() => {
    const tpl = templates.find((x) => x.id === templateId);
    return tpl?.islandId ?? undefined;
  }, [templates, templateId]);
  const [manualIslandId, setManualIslandId] = useState<number | undefined>();
  const islandId = templateIslandId ?? manualIslandId;

  // URL 跟随状态：**只补不删**（删参数会让"刷新还能回来"失效，见上面 initial 的说明）
  useEffect(() => {
    const next = new URLSearchParams(sp);
    if (templateId) next.set('templateId', String(templateId));
    if (runId) next.set('runId', String(runId));
    if (next.toString() !== sp.toString()) {
      setSp(next, { replace: true });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [templateId, runId]);

  const positionsQ = useQuery({
    queryKey: ['positions', islandId],
    queryFn: () => apiFetch(`/api/positions?islandId=${islandId}`)
      .then((r) => r.json()) as Promise<{ items?: Position[] }>,
    enabled: islandId != null,
  });
  const positionOptions = (positionsQ.data?.items ?? [])
    .filter((p) => p.pointId != null)
    .map((p) => ({ value: p.pointId as number, label: `${p.parentDeviceCode ?? ''} · ${p.code}` }));

  const manifestQ = useQuery({
    queryKey: ['manifest', runId],
    queryFn: () => apiFetch(`/api/experiment-runs/${runId}/manifest`)
      .then((r) => r.json()) as Promise<{ items: Placement[] }>,
    enabled: runId != null,
    refetchInterval: 5000,
  });
  const items = manifestQ.data?.items ?? [];

  const readinessQ = useQuery({
    queryKey: ['readiness', runId],
    queryFn: () => apiFetch(`/api/experiment-runs/${runId}/readiness`)
      .then((r) => r.json()) as Promise<{
        ready: boolean; reasons: Array<{ code: string; message: string }>;
      }>,
    enabled: runId != null,
    refetchInterval: 5000,
  });

  const instantiate = async () => {
    if (templateId == null) {
      message.warning(t('materialConfig.warn.selectTemplate'));
      return;
    }
    setBusy(true);
    try {
      const r = await apiFetch(`/api/experiment-templates/${templateId}/instantiate`, {
        method: 'POST',
        body: JSON.stringify({}),
      });
      const j = await r.json();
      if (!r.ok) {
        message.error(j.error ?? t('materialConfig.error.instantiate'));
        return;
      }
      setRunId(j.runId);
      message.success(t('materialConfig.toast.instantiated', { run: j.runId }));
      void qc.invalidateQueries({ queryKey: ['manifest'] }).catch(() => undefined);
    } catch (e) {
      message.error(t('materialConfig.error.instantiateDetail', { msg: String((e as Error).message) }));
    } finally {
      setBusy(false);
    }
  };

  const place = async (pid: number, positionId: number) => {
    try {
      const r = await apiFetch(`/api/experiment-runs/${runId}/placements/${pid}`, {
        method: 'PUT',
        body: JSON.stringify({ positionId }),
      });
      if (!r.ok) {
        const j = await r.json().catch(() => ({}));
        message.error(j.error ?? t('materialConfig.error.place'));
        return;
      }
      void qc.invalidateQueries({ queryKey: ['manifest'] }).catch(() => undefined);
      void qc.invalidateQueries({ queryKey: ['readiness'] }).catch(() => undefined);
    } catch (e) {
      message.error(t('materialConfig.error.placeDetail', { msg: String((e as Error).message) }));
    }
  };

  const verify = async (pid: number) => {
    try {
      const r = await apiFetch(
        `/api/experiment-runs/${runId}/placements/${pid}/verify`, { method: 'POST' });
      if (!r.ok) {
        const j = await r.json().catch(() => ({}));
        message.error(j.error ?? t('materialConfig.error.verify'));
        return;
      }
      void qc.invalidateQueries({ queryKey: ['manifest'] }).catch(() => undefined);
      void qc.invalidateQueries({ queryKey: ['readiness'] }).catch(() => undefined);
    } catch (e) {
      message.error(t('materialConfig.error.verifyDetail', { msg: String((e as Error).message) }));
    }
  };

  const start = async () => {
    setBusy(true);
    try {
      const r = await apiFetch(`/api/experiment-runs/${runId}/start`, { method: 'POST' });
      const j = await r.json().catch(() => ({}));
      if (!r.ok) {
        // 未齐套时后端带原因：原样显示，别让操作员猜"为什么点不动"
        message.error(j.error ?? t('materialConfig.error.start'));
        return;
      }
      message.success(t('materialConfig.toast.started'));
      nav(`/runs/${runId}`);
    } catch (e) {
      message.error(t('materialConfig.error.startDetail', { msg: String((e as Error).message) }));
    } finally {
      setBusy(false);
    }
  };

  const placedCount = items.filter((i) => i.positionId != null).length;
  const verifiedCount = items.filter((i) => i.status === 'verified').length;
  const step = runId == null ? 0 : readinessQ.data?.ready ? 2 : 1;

  return (
    <div style={{ padding: 16 }}>
      <Card
        size="small"
        title={t('materialConfig.title')}
        extra={(
          <Space>
            {islandId == null && (
              <Select
                size="small" allowClear placeholder={t('materialConfig.field.island')}
                style={{ width: 150 }}
                value={manualIslandId}
                onChange={setManualIslandId}
                options={islands.map((i) => ({ value: i.id, label: i.code }))}
              />
            )}
            <Select
              size="small" allowClear showSearch optionFilterProp="label"
              placeholder={t('materialConfig.field.template')}
              style={{ width: 260 }}
              value={templateId}
              onChange={(v?: number) => { setTemplateId(v); setRunId(undefined); }}
              options={templates.map((x) => ({
                value: x.id, label: `${x.name}（${x.templateNo}）`,
              }))}
            />
            <Button size="small" type="primary" loading={busy}
              disabled={ro.disabled || templateId == null} onClick={instantiate}>
              {t('materialConfig.action.instantiate')}
            </Button>
            {runId != null && (
              <Button size="small" onClick={() => nav(`/runs/${runId}`)}>
                {t('materialConfig.action.openRun')}
              </Button>
            )}
          </Space>
        )}
      >
        <Steps
          size="small"
          current={step}
          style={{ marginBottom: 12 }}
          items={[
            { title: t('materialConfig.step.template') },
            { title: t('materialConfig.step.place') },
            { title: t('materialConfig.step.start') },
          ]}
        />
        {runId == null ? (
          <Empty description={t('materialConfig.empty.noRun')} />
        ) : (
          <>
            <Space size={16} style={{ marginBottom: 8 }}>
              <Typography.Text type="secondary">
                {t('materialConfig.summary', {
                  run: runId, placed: placedCount, total: items.length,
                  verified: verifiedCount,
                })}
              </Typography.Text>
              {templateIslandId != null && (
                <Tag>{islands.find((i) => i.id === templateIslandId)?.code ?? templateIslandId}</Tag>
              )}
            </Space>
            {readinessQ.data && (
              <Alert
                style={{ marginBottom: 8 }}
                type={readinessQ.data.ready ? 'success' : 'warning'}
                showIcon
                message={readinessQ.data.ready
                  ? t('materialConfig.ready.ok') : t('materialConfig.ready.notReady')}
                description={readinessQ.data.reasons.length
                  ? readinessQ.data.reasons.map((r) => r.message).join('；')
                  : undefined}
              />
            )}
            <Table<Placement>
              size="small" rowKey="id" dataSource={items} pagination={false}
              columns={[
                {
                  title: t('materialConfig.col.role'), dataIndex: 'roleCode', width: 150,
                  render: (v: string) => <Typography.Text code>{v}</Typography.Text>,
                },
                { title: t('materialConfig.col.kind'), dataIndex: 'materialKind', width: 100 },
                {
                  title: t('materialConfig.col.required'), dataIndex: 'required', width: 80,
                  render: (v: boolean) => (v
                    ? <Tag color="red">{t('materialConfig.tag.required')}</Tag> : '—'),
                },
                {
                  title: t('materialConfig.col.position'), key: 'pos', width: 280,
                  render: (_: unknown, r: Placement) => (
                    <Select
                      size="small" allowClear showSearch optionFilterProp="label"
                      style={{ width: '100%' }}
                      placeholder={t('materialConfig.placeholder.position')}
                      value={r.positionId ?? undefined}
                      disabled={ro.disabled}
                      options={positionOptions}
                      onChange={(v: number) => place(r.id, v)}
                    />
                  ),
                },
                {
                  title: t('materialConfig.col.verify'), key: 'verify', width: 100,
                  render: (_: unknown, r: Placement) => (
                    <Button
                      size="small"
                      disabled={ro.disabled || r.positionId == null || r.status === 'verified'}
                      onClick={() => verify(r.id)}
                    >
                      {t('materialConfig.action.verify')}
                    </Button>
                  ),
                },
                {
                  title: t('materialConfig.col.status'), dataIndex: 'status', width: 100,
                  render: (v: string) => (
                    <Tag color={STATUS_COLOR[v] ?? 'default'}>
                      {t(`materialConfig.status.${v}` as Parameters<typeof t>[0], {})}
                    </Tag>
                  ),
                },
                {
                  title: t('materialConfig.col.lifecycle'), dataIndex: 'lifecycle', width: 110,
                  render: (v?: string | null) => {
                    if (v === 'process') return <Tag color="orange">{t('materialConfig.lifecycle.process')}</Tag>;
                    if (v === 'inventory') return <Tag color="green">{t('materialConfig.lifecycle.inventory')}</Tag>;
                    return '—';
                  },
                },
              ]}
            />
            <Space style={{ marginTop: 12 }}>
              <Button type="primary" loading={busy}
                disabled={ro.disabled || !readinessQ.data?.ready} onClick={start}>
                {t('materialConfig.action.start')}
              </Button>
              <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                {t('materialConfig.hint.start')}
              </Typography.Text>
            </Space>
          </>
        )}
      </Card>
    </div>
  );
}
