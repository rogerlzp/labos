import { useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Card, Empty, Segmented, Space, Spin, Tag, Tooltip, Typography, message } from 'antd';
import { apiFetch } from '../api/client';
import { useReadOnly } from '../auth';
import { useT } from '../i18n';
import type { MessageKey, MessageVars } from '../i18n/core';

interface TlNode {
  nodeKey: string; name?: string; resourceCode?: string | null; resourceId?: number | null;
  startSec: number; endSec: number; durationSec: number;
  estimateSource?: string; estimateSamples?: number;
}
interface Tl { totalSec: number; nodes: TlNode[] }
interface ExpRow {
  id: number; experimentNo: string; name: string; islandCode?: string | null;
  flowCount?: number; status: string;
  /** 排程器按它从大到小排（人工插队就是改这个） */
  priority?: number;
}

/** 一步的绝对时间（从"现在"算起），已考虑设备占用。 */
interface Seg {
  expId: number;
  expName: string;
  flowId: number;
  nodeKey: string;
  name: string;
  resource: string;      // 设备 code，未绑定记 '未绑定设备'
  start: number;
  end: number;
  duration: number;
  source?: string;
  samples?: number;
}

const PALETTE = ['#60a5fa', '#34d399', '#f472b6', '#fbbf24', '#a78bfa', '#22d3ee'];
const colorOf = (code: string, t: (k: MessageKey, vars?: MessageVars) => string) => {
  if (code === t('scheduleGantt.unboundDevice')) return '#94a3b8';
  let h = 0;
  for (let i = 0; i < code.length; i++) h = (h * 31 + code.charCodeAt(i)) % 997;
  return PALETTE[h % PALETTE.length];
};

const fmt = (sec: number, t: (k: MessageKey, vars?: MessageVars) => string) => (sec < 60
  ? t('scheduleGantt.duration.seconds', { n: Math.round(sec) })
  : t('scheduleGantt.duration.minutes', { n: Math.round(sec / 60) }));

// 文案 key（不是文案本身）：渲染时再查表
const SOURCE_LABEL: Record<string, MessageKey> = {
  manual: 'scheduleGantt.source.manual', history: 'scheduleGantt.source.history',
  device: 'scheduleGantt.source.device', default: 'scheduleGantt.source.default',
};

/**
 * 排程甘特（只读第一版）。
 *
 * <p>与「分析 → 甘特」不同：那个看**已经跑完**的一次执行；这个看**还没跑**的实验排下去要多久。
 *
 * <p>两种视角：
 * <ul>
 *   <li><b>按实验</b>：一行一个实验，色块=一条流程（回答"总共要多久"）</li>
 *   <li><b>按设备</b>：一行一台设备，色块=一步操作（回答"哪台设备是瓶颈 / 什么时候空着"）</li>
 * </ul>
 *
 * <p>排程规则刻意只做**串行下界**：实验按顺序一个接一个，同一时间只跑一个实验。
 * 设备级并行交错要先定"谁先占哪台设备"的算法，现在画等于把没验证的假设画成图。
 */
export default function ScheduleGanttPage() {
  const t = useT();
  const [view, setView] = useState<'exp' | 'device'>('exp');
  /** 拖拽后的本地顺序（null = 用服务端的 priority 顺序） */
  const [orderIds, setOrderIds] = useState<number[] | null>(null);
  // 拖拽源放 ref：dragstart 与 drop 可能在同一轮事件里到达（自动化测试/合成事件），
  // 用 state 会读到还没提交的过期值。state 只负责视觉反馈。
  const dragFromRef = useRef<number | null>(null);
  const [dragFrom, setDragFrom] = useState<number | null>(null);
  const [saving, setSaving] = useState(false);
  // 拖拽调序会写 priority：viewer 取消 draggable/onDrop 并给提示，不能只靠后端 403
  const ro = useReadOnly();

  const exps = useQuery({
    queryKey: ['gantt-experiments'],
    queryFn: () => apiFetch('/api/experiments?limit=60').then((r) => r.json()) as Promise<{
      items: ExpRow[];
    }>,
  });

  /**
   * 排程器按 priority 从大到小排，页面必须用同一口径，
   * 否则"拖完的顺序"和"实际会先跑哪个"是两回事。
   */
  const picked = useMemo(() => {
    const base = (exps.data?.items ?? [])
      .filter((e) => (e.flowCount ?? 0) > 0)
      .sort((a, b) => (b.priority ?? 0) - (a.priority ?? 0) || b.id - a.id);
    if (!orderIds) return base.slice(0, 8);
    const byId = new Map(base.map((e) => [e.id, e]));
    const arranged = orderIds
      .map((id) => byId.get(id))
      .filter((e): e is ExpRow => Boolean(e));
    const rest = base.filter((e) => !orderIds.includes(e.id));
    return [...arranged, ...rest].slice(0, 8);
  }, [exps.data, orderIds]);

  const detail = useQuery({
    queryKey: ['gantt-detail', picked.map((e) => e.id).join(',')],
    enabled: picked.length > 0,
    queryFn: async () => {
      const out: Array<{ exp: ExpRow; flows: Array<{ workflowId: number; tl: Tl }> }> = [];
      for (const e of picked) {
        const p = await apiFetch(`/api/experiments/${e.id}/processes`).then((r) => r.json());
        const flows: Array<{ workflowId: number; tl: Tl }> = [];
        for (const it of (p.items ?? [])) {
          const tl = await apiFetch(`/api/workflows/${it.workflowId}/timeline`).then((r) => r.json());
          flows.push({ workflowId: it.workflowId, tl });
        }
        out.push({ exp: e, flows });
      }
      return out;
    },
  });

  /**
   * 摊平成一串绝对时间的步骤。
   *
   * 每步 `start = max(该实验上一步结束, 这台设备空出来的时刻)`。
   * 因为实验是串行的，设备时刻通常不会成为瓶颈 —— 但**同一条流程里两个节点用同一台设备**时
   * 会正确串起来，而不是重叠。
   */
  /** 只有还没跑的实验能调序（跑过/在跑的插到前面没有意义） */
  const canDrag = (e: ExpRow) => !ro.disabled
    && (e.status === 'created' || e.status === 'planned');

  const applyOrder = async (all: number[]) => {
    // 页面里混着"跑过的"和"还没跑的"：只有没跑过的能写 priority。
    // 全量提交会被后端拒（"只有还没跑的实验能调序"），所以这里只取可拖的那部分，
    // 保持它们在新顺序里的相对次序。
    const ids = all.filter((id) => {
      const row = expRows.find((r) => r.exp.id === id);
      return row ? canDrag(row.exp) : false;
    });
    if (ids.length < 2) {
      message.info(t('scheduleGantt.info.needTwo'));
      return;
    }
    const prev = orderIds;
    setOrderIds(all);          // 先动，界面才不卡（含不可拖的行，视觉上一起让位）
    setSaving(true);
    try {
      const r = await apiFetch('/api/experiments/reorder', {
        method: 'POST',
        body: JSON.stringify({ ids }),
      });
      const j = await r.json();
      if (!r.ok) {
        setOrderIds(prev);     // 失败要退回去，否则界面顺序和服务端不一致
        message.error(j.error ?? t('scheduleGantt.error.reorderFailed'));
        return;
      }
      message.success(t('scheduleGantt.toast.reordered', { n: j.reordered }));
      await exps.refetch();
    } catch (e) {
      setOrderIds(prev);
      message.error(String((e as Error).message));
    } finally {
      setSaving(false);
    }
  };

  const dropTo = (to: number) => {
    const from = dragFromRef.current;
    dragFromRef.current = null;
    setDragFrom(null);
    if (from == null || from === to) {
      return;
    }
    const ids = expRows.map((r) => r.exp.id);
    const [moved] = ids.splice(from, 1);
    ids.splice(to, 0, moved);
    void applyOrder(ids);
  };

  const { segs, expRows, total } = useMemo(() => {
    const all: Seg[] = [];
    const rows: Array<{ exp: ExpRow; start: number; end: number }> = [];
    const deviceFree = new Map<string, number>();
    let cursor = 0;

    for (const { exp, flows } of (detail.data ?? [])) {
      const rowStart = cursor;
      for (const f of flows) {
        for (const n of (f.tl.nodes ?? [])) {
          const res = n.resourceCode || t('scheduleGantt.unboundDevice');
          const free = deviceFree.get(res) ?? 0;
          const start = Math.max(cursor, free);
          const end = start + (n.durationSec ?? 0);
          all.push({
            expId: exp.id, expName: exp.name, flowId: f.workflowId,
            nodeKey: n.nodeKey, name: n.name ?? n.nodeKey, resource: res,
            start, end, duration: n.durationSec ?? 0,
            source: n.estimateSource, samples: n.estimateSamples,
          });
          deviceFree.set(res, end);
          cursor = Math.max(cursor, end);
        }
      }
      rows.push({ exp, start: rowStart, end: cursor });
    }
    return { segs: all, expRows: rows, total: cursor };
  }, [detail.data]);

  const devices = useMemo(() => {
    const m = new Map<string, Seg[]>();
    for (const s of segs) {
      if (!m.has(s.resource)) m.set(s.resource, []);
      m.get(s.resource)!.push(s);
    }
    return [...m.entries()].sort((a, b) => b[1].length - a[1].length);
  }, [segs]);

  const pct = (v: number) => `${(v / Math.max(1, total)) * 100}%`;

  if (exps.error) {
    return <Alert type="error" showIcon message={t('scheduleGantt.error.load')}
      description={String((exps.error as Error).message)} />;
  }

  const axis = (
    <div style={{ display: 'flex', fontSize: 11, color: '#94a3b8', marginBottom: 4 }}>
      <div style={{ width: 220 }} />
      <div style={{ flex: 1, display: 'flex', justifyContent: 'space-between' }}>
        <span>0</span>
        <span>{fmt(total / 2, t)}</span>
        <span>{fmt(total, t)}</span>
      </div>
    </div>
  );

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <Alert
        type="info" showIcon
        message={t('scheduleGantt.alert.title')}
        description={t('scheduleGantt.alert.desc')
          + (ro.disabled ? '\n' + t('scheduleGantt.alert.readOnly') : '')}
      />
      <Card
        size="small"
        title={t('scheduleGantt.title', {
            n: expRows.length, m: segs.length, total: fmt(total, t),
          })}
        extra={<Segmented
          size="small"
          value={view}
          onChange={(v) => setView(v as 'exp' | 'device')}
          options={[{ label: t('scheduleGantt.view.exp'), value: 'exp' },
            { label: t('scheduleGantt.view.device'), value: 'device' }]}
        />}
      >
        {exps.isLoading || detail.isLoading ? <Spin /> : segs.length === 0 ? (
          <Empty description={t('scheduleGantt.empty')} />
        ) : (
          <div>
            {axis}

            {view === 'exp' ? expRows.map((r, i) => (
              <div
                key={r.exp.id}
                title={ro.title}
                draggable={canDrag(r.exp) && !saving}
                onDragStart={() => { dragFromRef.current = i; setDragFrom(i); }}
                onDragOver={(e) => e.preventDefault()}
                onDrop={() => dropTo(i)}
                style={{
                  display: 'flex', alignItems: 'center', marginBottom: 8,
                  cursor: canDrag(r.exp) ? 'grab' : 'not-allowed',
                  opacity: dragFrom === i ? 0.45 : 1,
                  borderTop: dragFrom != null && dragFrom !== i ? '1px dashed #cbd5e1' : undefined,
                }}
              >
                <div style={{ width: 220, fontSize: 12, overflow: 'hidden', whiteSpace: 'nowrap' }}>
                  <span style={{ color: canDrag(r.exp) ? '#94a3b8' : '#e2e8f0', marginRight: 4 }}>
                    ⠿
                  </span>
                  <Typography.Text ellipsis style={{ maxWidth: 200 }}>{r.exp.name}</Typography.Text>
                  <div style={{ color: '#94a3b8', fontSize: 11 }}>
                    {r.exp.experimentNo}{r.exp.islandCode ? ` · ${r.exp.islandCode}` : ''}
                  </div>
                </div>
                <div style={{
                  flex: 1, position: 'relative', height: 26,
                  background: 'rgba(148,163,184,0.18)', borderRadius: 4,
                }}>
                  {segs.filter((s) => s.expId === r.exp.id).map((s) => (
                    <Tooltip key={s.nodeKey} title={`${s.name} · ${s.resource} · ${fmt(s.duration, t)}`}>
                      <div style={{
                        position: 'absolute', left: pct(s.start),
                        width: `${Math.max(0.4, ((s.end - s.start) / Math.max(1, total)) * 100)}%`,
                        top: 3, bottom: 3, background: colorOf(s.resource, t), borderRadius: 2,
                      }} />
                    </Tooltip>
                  ))}
                </div>
              </div>
            )) : devices.map(([code, list]) => (
              <div key={code} style={{ display: 'flex', alignItems: 'center', marginBottom: 8 }}>
                <div style={{ width: 220, fontSize: 12, display: 'flex', gap: 6 }}>
                  <Tag color={colorOf(code, t) === '#94a3b8' ? 'default' : undefined}
                    style={{ background: colorOf(code, t), color: '#fff', border: 'none' }}>
                    {code}
                  </Tag>
                  <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                    {t('scheduleGantt.steps', { n: list.length })}
                  </Typography.Text>
                </div>
                <div style={{ flex: 1, position: 'relative', height: 26, background: 'rgba(148,163,184,0.18)', borderRadius: 4 }}>
                  {list.map((s) => (
                    <Tooltip
                      key={`${s.expId}-${s.nodeKey}`}
                      title={(
                        <div style={{ fontSize: 12 }}>
                          <div>{s.expName} · {s.name}</div>
                          <div>{fmt(s.start, t)} → {fmt(s.end, t)}（{fmt(s.duration, t)}）</div>
                          <div>{t('scheduleGantt.sourceLabel', {
                            source: t(SOURCE_LABEL[s.source ?? 'default'] ?? 'scheduleGantt.source.default'),
                          })}
                            {s.source === 'history' && s.samples ? t('scheduleGantt.samples', { n: s.samples }) : ''}</div>
                        </div>
                      )}
                    >
                      <div style={{
                        position: 'absolute', left: pct(s.start),
                        width: `${Math.max(0.4, ((s.end - s.start) / Math.max(1, total)) * 100)}%`,
                        top: 3, bottom: 3, background: colorOf(code, t),
                        borderRadius: 2, opacity: 0.9,
                      }} />
                    </Tooltip>
                  ))}
                </div>
              </div>
            ))}

            <Space size={12} style={{ marginTop: 8 }}>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {view === 'exp'
                  ? t('scheduleGantt.legend.exp')
                  : t('scheduleGantt.legend.device')}
              </Typography.Text>
              <Tag>{t('scheduleGantt.total', { total: fmt(total, t) })}</Tag>
            </Space>
          </div>
        )}
      </Card>
    </Space>
  );
}
