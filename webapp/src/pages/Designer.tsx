import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert, Button, Card, Col, Descriptions, Empty, Input, InputNumber, Modal,
  Row, Select, Space, Spin, Table, Tag, Tooltip, Typography, message,
} from 'antd';
import BpmnModeler from 'bpmn-js/lib/Modeler';
import 'bpmn-js/dist/assets/diagram-js.css';
import 'bpmn-js/dist/assets/bpmn-js.css';
import 'bpmn-js/dist/assets/bpmn-font/css/bpmn.css';
import { apiFetch, api, type PreflightIssue } from '../api/client';
import { statusColor, useStatusVocabulary } from '../utils/status';
import { useReadOnly } from '../auth';
import { useT } from '../i18n';
import type { MessageKey, MessageVars } from '../i18n/core';

interface CanvasApi {
  zoom: (mode: string) => void;
  addMarker: (id: string, cls: string) => void;
  removeMarker: (id: string, cls: string) => void;
}
interface RegistryApi {
  get: (id: string) => unknown;
  forEach: (fn: (el: { id: string }) => void) => void;
}

/** 老库用 customDevice 表示设备动作，标准 modeler 不认，转成 task 并补 name。 */
function normalize(xml: string): string {
  return xml
    .replace(/<customDevice\b([^>]*?)(\/?)>/g, (_m, attrs: string, selfClose: string) => {
      const proto = /protocolName="([^"]*)"/.exec(attrs);
      const hasName = /\sname="/.test(attrs);
      let out = '<task' + attrs;
      if (!hasName && proto && proto[1]) out += ` name="${proto[1]}"`;
      return out + (selfClose ? '/>' : '>');
    })
    .replace(/<\/customDevice>/g, '</task>');
}

/** 保存前还原：subProcess(含 __s 子节点) → 普通 task。库里不存"步骤子图"。 */
function collapse(xml: string): string {
  const doc = new DOMParser().parseFromString(xml, 'application/xml');
  const NS = 'http://www.omg.org/spec/BPMN/20100524/MODEL';
  const stepIds = new Set<string>();

  for (const sub of Array.from(doc.getElementsByTagName('*'))) {
    if (sub.localName !== 'subProcess') continue;
    const id = sub.getAttribute('id');
    if (!id) continue;
    const kids = Array.from(sub.childNodes)
      .filter((n) => n.nodeType === 1 && (n as Element).localName === 'task');
    const auto = kids.filter((k) => ((k as Element).getAttribute('id') ?? '').includes('__s'));
    if (auto.length === 0) continue;   // 用户自己画的子流程，不动

    const task = doc.createElementNS(NS, 'bpmn:task');
    task.setAttribute('id', id);
    task.setAttribute('name', sub.getAttribute('name') ?? id);
    for (const a of ['protocolName', 'protocolId', 'resource', 'resourceName', 'variablesValue']) {
      const v = sub.getAttribute(a);
      if (v) task.setAttribute(a, v);
    }
    auto.forEach((k) => stepIds.add((k as Element).getAttribute('id')!));
    sub.parentNode?.replaceChild(task, sub);
  }

  for (const el of Array.from(doc.getElementsByTagName('*'))) {
    const be = el.getAttribute?.('bpmnElement');
    if (be && (stepIds.has(be) || /__[sf]\d+$/.test(be))) el.parentNode?.removeChild(el);
    if (el.localName === 'BPMNShape' && be) el.removeAttribute('isExpanded');
  }
  return new XMLSerializer().serializeToString(doc);
}

/** 某条流程的执行记录表（点行 → 运行详情）。 */
function RunsTable({ wfId, onOpen }: { wfId: number; onOpen: (runId: number) => void }) {
  const t = useT();
  // 这列是 run 状态（/api/workflows/{id}/runs），按 run scope 取色（不是 experiment）
  const vocab = useStatusVocabulary();
  const q = useQuery({
    queryKey: ['wf-runs', wfId],
    queryFn: () => apiFetch(`/api/workflows/${wfId}/runs?limit=20`).then((r) => r.json()) as Promise<{
      items: Array<{
        id: number; runNo: string; status: string;
        startedAt?: string | null; durationSec?: number | null;
        total: number; done: number; manual: number; failed: number;
      }>;
    }>,
  });
  const items = q.data?.items ?? [];
  if (!q.isLoading && items.length === 0) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('designer.empty.noRuns')} />;
  }
  return (
    <Table
      size="small" rowKey="id" loading={q.isLoading}
      dataSource={items} pagination={{ pageSize: 10, showSizeChanger: false }}
      onRow={(r) => ({ onClick: () => onOpen(r.id), style: { cursor: 'pointer' } })}
      columns={[
        { title: t('designer.col.run'), dataIndex: 'id', width: 100, render: (v: number) => `#${v}` },
        {
          title: t('designer.col.status'), dataIndex: 'status', width: 100,
          render: (v: string) => (
            <Tag color={statusColor('run', v, vocab)}>{v}</Tag>
          ),
        },
        {
          title: t('designer.col.nodes'), width: 150,
          render: (_, r) => `${r.done}/${r.total}`
            + (r.manual ? t('designer.nodes.manual', { n: r.manual }) : '')
            + (r.failed ? t('designer.nodes.failed', { n: r.failed }) : ''),
        },
        { title: t('designer.col.duration'), dataIndex: 'durationSec', width: 80,
          render: (v?: number | null) => (v != null ? `${v}s` : '—') },
        {
          title: t('designer.col.startedAt'), ellipsis: true,
          render: (_, r) => (
            <span style={{ fontFamily: 'monospace', fontSize: 11 }}>
              {(r.startedAt ?? '').slice(0, 19).replace('T', ' ')}
            </span>
          ),
        },
      ]}
    />
  );
}


/**
 * 节点设置：把选中的 task 设成**设备任务 / 变量节点 / 表达式节点**，并填参数。
 *
 * <p>为什么单独做成一个受控小卡片：bpmn-js 的 modeling 对 `protocolName` 这种
 * 未声明的自定义属性支持不稳，直接在画布上改可能把它丢掉。这里走服务端接口
 * （改 workflow_node.params + patch XML 里那一个属性），保存后**重新导入 XML**。</p>
 */
function NodeSettings({ wfId, nodeKey, onSaved }: {
  wfId: string;
  nodeKey: string;
  onSaved: () => void;
}) {
  const t = useT();
  // 节点设置里的"保存"会改 workflow_node.params：viewer 禁用
  const ro = useReadOnly();
  const [kind, setKind] = useState<'device' | '__setVar' | '__expr'>('device');
  const [varName, setVarName] = useState('');
  const [val, setVal] = useState('');
  const [saving, setSaving] = useState(false);
  const [loaded, setLoaded] = useState(false);
  /** 设备任务的绑定：设备 → 方法 → 参数（JSON） */
  const [deviceId, setDeviceId] = useState<number | null>(null);
  /** 设备任务要调的方法名（设备任务的 protocolName） */
  const [methodName, setMethodName] = useState<string>('');
  const [paramsText, setParamsText] = useState('{}');

  // 选中节点变化时读一次当前配置
  useQuery({
    queryKey: ['node-binding', wfId, nodeKey],
    queryFn: async () => {
      const r = await apiFetch(`/api/workflows/${wfId}/nodes/${encodeURIComponent(nodeKey)}`);
      const j = await r.json();
      const m: string = j.method ?? '';
      const builtin = m === '__setVar' || m === '__expr';
      setKind(builtin ? m : 'device');
      setVarName(j.params?.var ?? '');
      setVal(m === '__expr' ? (j.params?.expr ?? '') : (j.params?.value ?? ''));
      if (!builtin) {
        setDeviceId(j.resourceId ?? null);
        setMethodName(m);
        // 老数据里 method_params 可能是 0 / 字符串（不是对象）——
        // 直接 stringify 会把 "0" 塞进输入框，结构化输入就全空了。统一归一成对象。
        const p0 = j.params as unknown;
        setParamsText(p0 && typeof p0 === "object" && !Array.isArray(p0)
          ? JSON.stringify(p0) : "{}");
      }
      setLoaded(true);
      return j;
    },
  });

  // 设备池：与「岛屿视图」同一份数据（resource_node.kind='device'）
  const pool = useQuery({
    queryKey: ['device-pool'],
    queryFn: () => apiFetch('/api/devices/pool').then((r) => r.json()) as Promise<{
      items: Array<{ resourceId: number; code: string; name?: string; capability?: string }>;
    }>,
    enabled: kind === 'device',
  });

  // 选中设备的方法（来自 device_method，入池时从型号库同步过来的）
  const methods = useQuery({
    queryKey: ['device-methods', deviceId],
    queryFn: () => apiFetch(`/api/devices/${deviceId}/methods`).then((r) => r.json()) as Promise<{
      items: Array<{ method: string; name?: string; estimateSec?: number }>;
    }>,
    enabled: kind === 'device' && !!deviceId,
  });

  /**
   * 方法参数定义（来自 device_method.param_schema）。
   *
   * <p>有 schema 时渲染结构化输入框（不用让人手写 JSON）；
   * 没有就退回 JSON 文本框，至少还能传。
   */
  const schema = useQuery({
    queryKey: ['method-params', methodName],
    queryFn: () => apiFetch(`/api/methods/${encodeURIComponent(methodName)}/params`)
      .then((r) => r.json()) as Promise<{
        found: boolean;
        params: Array<{ name: string; desc?: string; type?: string; required?: boolean }>;
      }>,
    enabled: kind === 'device' && !!methodName,
  });

  /** 读当前 JSON 里的某个参数值 */
  const paramValue = (name: string): string => {
    try {
      const o = JSON.parse(paramsText || '{}') as Record<string, unknown>;
      const v = o[name];
      return v == null ? '' : String(v);
    } catch {
      return '';
    }
  };

  /** 改一个参数：直接改那份 JSON，保持单一数据源（保存时不用再拼） */
  const setParamValue = (name: string, v: string) => {
    let o: Record<string, unknown> = {};
    try {
      o = paramsText.trim() ? JSON.parse(paramsText) as Record<string, unknown> : {};
    } catch {
      o = {};
    }
    if (v === '') {
      delete o[name];
    } else {
      o[name] = v;
    }
    setParamsText(Object.keys(o).length ? JSON.stringify(o) : '');
  };

  const save = async () => {
    setSaving(true);
    try {
      const isDevice = kind === 'device';
      const method = isDevice ? (methodName || '') : kind;
      let params: unknown = null;
      if (isDevice) {
        try {
          params = paramsText.trim() ? JSON.parse(paramsText) : {};
        } catch {
          message.error(t('designer.error.badJson'));
          return;
        }
      } else {
        params = kind === '__setVar'
          ? { var: varName, value: val }
          : { var: varName, expr: val };
      }
      const r = await apiFetch(`/api/workflows/${wfId}/nodes/${encodeURIComponent(nodeKey)}`, {
        method: 'PUT',
        body: JSON.stringify({
          method, params,
          resourceId: isDevice ? (deviceId ?? 0) : undefined,
        }),
      });
      const j = await r.json();
      if (!r.ok) {
        message.error(j.error ?? t('designer.error.saveFailed'));
        return;
      }
      message.success(kind === 'device' ? t('designer.toast.deviceSaved') : t('designer.toast.builtinSaved'));
      onSaved();
    } finally {
      setSaving(false);
    }
  };

  if (!loaded) return <Spin size="small" />;

  return (
    <Card size="small" title={t('designer.settings.title', { key: nodeKey })}
      extra={<Button size="small" type="primary" loading={saving} {...ro} onClick={save}>
        {t('designer.action.save')}
      </Button>}>
      <Space direction="vertical" size={8} style={{ width: '100%' }}>
        <Select
          size="small" style={{ width: '100%' }} value={kind}
          onChange={(v) => setKind(v as 'device' | '__setVar' | '__expr')}
          options={[
            { value: 'device', label: t('designer.kind.device') },
            { value: '__setVar', label: t('designer.kind.setVar') },
            { value: '__expr', label: t('designer.kind.expr') },
          ]}
        />
        {kind === 'device' ? (
          <>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('designer.field.device')}</Typography.Text>
            <Select
              size="small" style={{ width: '100%' }} showSearch optionFilterProp="label"
              value={deviceId ?? undefined}
              placeholder={pool.isLoading ? t('designer.placeholder.loadingDevices') : t('designer.placeholder.pickDevice')}
              onChange={(v: number) => { setDeviceId(v); setMethodName(''); }}
              options={(pool.data?.items ?? []).map((d) => ({
                value: d.resourceId,
                label: `${d.code}${d.name ? ' · ' + d.name : ''}（${d.capability ?? '—'}）`,
              }))}
            />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('designer.field.method')}</Typography.Text>
            <Select
              size="small" style={{ width: '100%' }} showSearch optionFilterProp="label"
              value={methodName || undefined}
              disabled={!deviceId}
              placeholder={deviceId ? t('designer.placeholder.pickMethod') : t('designer.placeholder.pickDeviceFirst')}
              onChange={(v: string) => setMethodName(v)}
              options={(methods.data?.items ?? []).map((m) => ({
                value: m.method,
                label: `${m.method}${m.name ? ' · ' + m.name : ''}`,
              }))}
            />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {t('designer.field.params')}
            </Typography.Text>
            {(schema.data?.params ?? []).length > 0 ? (
              <Space direction="vertical" size={4} style={{ width: '100%' }}>
                {(schema.data?.params ?? []).map((pm) => (
                  <div key={pm.name}>
                    <Typography.Text style={{ fontSize: 12 }}>
                      {pm.name}
                      {pm.required ? <span style={{ color: '#f5222d' }}> *</span> : null}
                    </Typography.Text>
                    <Input
                      size="small"
                      placeholder={pm.desc || pm.type || ''}
                      value={paramValue(pm.name)}
                      onChange={(e) => setParamValue(pm.name, e.target.value)}
                    />
                  </div>
                ))}
                <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                  {t('designer.params.fromSchema')}
                </Typography.Text>
                <Input.TextArea
                  size="small" rows={2}
                  placeholder={t('designer.params.placeholder')}
                  value={paramsText}
                  onChange={(e) => setParamsText(e.target.value)}
                />
              </Space>
            ) : (
              <Input.TextArea
                size="small" rows={2}
                placeholder={'{"plateID":"PL001","cycles":3}'}
                value={paramsText}
                onChange={(e) => setParamsText(e.target.value)}
              />
            )}
            {!deviceId && (
              <Typography.Text type="warning" style={{ fontSize: 12 }}>
                {t('designer.params.noDevice')}
              </Typography.Text>
            )}
          </>
        ) : (
          <>
            <Input size="small" placeholder={t('designer.placeholder.varName')}
              value={varName} onChange={(e) => setVarName(e.target.value)} />
            <Input size="small"
              placeholder={kind === '__setVar'
                ? t('designer.placeholder.varValue')
                : t('designer.placeholder.expr')}
              value={val} onChange={(e) => setVal(e.target.value)} />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {t('designer.expr.hint')}
            </Typography.Text>
          </>
        )}
      </Space>
    </Card>
  );
}

/**
 * 时间条：把每个节点画成一根按时间比例定位的条。
 *
 * <p>故意不引图表库：甘特还没成型，先用 div + 百分比把"谁在什么时候占哪台设备"画出来，
 * 够用且不会把依赖搞重。
 */
function TimelineChart({ data }: {
  data?: {
    totalSec: number;
    nodes: Array<{
      nodeKey: string; name?: string; resourceCode?: string | null;
      startSec: number; endSec: number; durationSec: number; estimated: boolean;
      estimateSource?: string; estimateSamples?: number;
    }>;
  };
}) {
  const t = useT();
  if (!data) return <Spin size="small" />;
  const total = Math.max(1, data.totalSec);
  if (data.nodes.length === 0) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('designer.empty.noTasks')} />;
  }
  // 只有真·兜底（既没人工、也没历史、也没型号库）才提示，否则会冤枉已配置的节点
  const anyDefault = data.nodes.some((n) => (n.estimateSource ?? 'default') === 'default');
  return (
    <Space direction="vertical" size={8} style={{ width: '100%' }}>
      {anyDefault && (
        <Alert
          type="warning" showIcon
          message={t('designer.estimate.alertTitle')}
          description={t('designer.estimate.alertDesc')}
        />
      )}
      {data.nodes.map((n) => (
        <div key={n.nodeKey}>
          <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12 }}>
            <span>{n.name ?? n.nodeKey} <Tag>{n.resourceCode ?? t('designer.estimate.noDevice')}</Tag></span>
            <span style={{ color: '#64748b' }}>
              {fmtWait(n.startSec, t)} → {fmtWait(n.endSec, t)} ({fmtWait(n.durationSec, t)})
              <Tooltip title={t(ESTIMATE_HINT[n.estimateSource ?? 'default'])}>
                <Tag style={{ marginLeft: 6 }} color={SOURCE_COLOR[n.estimateSource ?? 'default']}>
                  {t(SOURCE_LABEL[n.estimateSource ?? 'default'])}
                  {n.estimateSource === 'history' && n.estimateSamples
                    ? t('designer.estimate.samples', { n: n.estimateSamples }) : ''}
                </Tag>
              </Tooltip>
            </span>
          </div>
          <div style={{
            position: 'relative', height: 16, background: '#f1f5f9',
            borderRadius: 3, marginTop: 2,
          }}>
            <div style={{
              position: 'absolute',
              left: `${(n.startSec / total) * 100}%`,
              width: `${Math.max(2, (n.durationSec / total) * 100)}%`,
              height: '100%',
              background: n.estimateSource === 'manual' || n.estimateSource === 'history'
                ? '#60a5fa'
                : (n.estimateSource === 'device' ? '#818cf8' : '#fbbf24'),
              borderRadius: 3,
            }} />
          </div>
        </div>
      ))}
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        {t('designer.estimate.total', { wait: fmtWait(total, t) })}
      </Typography.Text>
    </Space>
  );
}

/** 每步耗时从哪来（与后端 estimateSource 对应）。 */
const SOURCE_LABEL: Record<string, MessageKey> = {
  manual: 'designer.source.manual', history: 'designer.source.history',
  device: 'designer.source.device', default: 'designer.source.default',
};
const SOURCE_COLOR: Record<string, string> = {
  manual: 'green', history: 'blue', device: 'geekblue', default: 'orange',
};
const ESTIMATE_HINT: Record<string, MessageKey> = {
  manual: 'designer.sourceHint.manual',
  history: 'designer.sourceHint.history',
  device: 'designer.sourceHint.device',
  default: 'designer.sourceHint.default',
};

/** 秒 → "4 分钟" / "1 小时 5 分"。 */
function fmtWait(sec: number, t: (k: MessageKey, vars?: MessageVars) => string): string {
  if (sec < 60) return t('designer.duration.seconds', { n: Math.round(sec) });
  const m = Math.round(sec / 60);
  if (m < 60) return t('designer.duration.minutes', { n: m });
  return t('designer.duration.hours', {
    h: Math.floor(m / 60),
    m: m % 60 ? t('designer.duration.hoursMinutes', { m: m % 60 }) : '',
  });
}

export default function DesignerPage() {
  const t = useT();
  const { id } = useParams();
  const nav = useNavigate();
  // 流程设计器：浏览/缩放不拦，保存与历史恢复是写操作
  const ro = useReadOnly();
  // 从「实验详情 → 新建流程」过来时会带实验上下文（不请求接口，直接透传展示）
  const [sp] = useSearchParams();
  const fromExpId = sp.get('expId');
  const fromExpName = sp.get('expName') ?? '';
  const fromIsland = sp.get('island') ?? '';
  const qc = useQueryClient();
  const wfId = Number(id);

  const containerRef = useRef<HTMLDivElement>(null);
  const modelerRef = useRef<BpmnModeler | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const [ready, setReady] = useState(false);
  const [paramValues, setParamValues] = useState<Record<string, unknown>>({});
  const [showHistory, setShowHistory] = useState(false);
  /** 执行记录：跑完的单子在别处翻不到，这里按流程查最近 20 次 */
  const [showRuns, setShowRuns] = useState(false);

  /**
   * 现在跑要等多久：按各设备当前占用者的剩余时间取最大值。
   *
   * <p>没有它，点执行之后只能干等 —— 不知道是"马上跑"还是"排在别人后面"。
   */
  /** 时间条弹窗（甘特前置：每步预计开始/结束） */
  const [showTimeline, setShowTimeline] = useState(false);
  const timeline = useQuery({
    queryKey: ['wf-timeline', wfId],
    queryFn: () => apiFetch(`/api/workflows/${wfId}/timeline`).then((r) => r.json()) as Promise<{
      totalSec: number;
      nodes: Array<{
        nodeKey: string; name?: string; resourceCode?: string | null;
        startSec: number; endSec: number; durationSec: number; estimated: boolean;
      }>;
    }>,
    enabled: showTimeline,
    refetchInterval: showTimeline ? 30000 : false,
  });

  const eta = useQuery({
    queryKey: ['wf-eta', wfId],
    queryFn: () => apiFetch(`/api/workflows/${wfId}/eta`).then((r) => r.json()) as Promise<{
      waitSec: number; known: boolean; resources: number;
      busy?: Array<{ resourceId?: number; resourceCode?: string; remainSec: number; holderRunNo?: string }>;
    }>,
    enabled: Number.isFinite(Number(wfId)),
    refetchInterval: 30000,
  });

  const compile = useQuery({ queryKey: ['compile', wfId], queryFn: () => api.compile(wfId) });
  const pre = useQuery({ queryKey: ['preflight', wfId], queryFn: () => api.preflight(wfId) });

  // 初始化 modeler（只跑一次）
  useEffect(() => {
    if (!containerRef.current || modelerRef.current) return;
    const modeler = new BpmnModeler({ container: containerRef.current });
    modelerRef.current = modeler;

    modeler.on('commandStack.changed', () => setDirty(true));
    modeler.on('selection.changed', (e: { newSelection: Array<{ id: string }> }) => {
      setSelected(e.newSelection?.[0]?.id ?? null);
    });
    return () => {
      modeler.destroy();
      modelerRef.current = null;
    };
  }, []);

  // 加载图（steps=1 让多步节点显示为可折叠 subProcess）
  useEffect(() => {
    let alive = true;
    (async () => {
      try {
        const xml = await api.graphXml(wfId, true);
        if (!alive || !modelerRef.current) return;
        await modelerRef.current.importXML(normalize(xml));
        (modelerRef.current.get('canvas') as CanvasApi).zoom('fit-viewport');
        setReady(true);
        applyMarkers();
      } catch (e) {
        message.error(t('designer.error.graphLoad', { msg: String((e as Error).message) }));
      }
    })();
    return () => { alive = false; };
  }, [wfId]);

  // 预检问题可视化：走不到的节点红虚线，汇合卡住橙虚线
  const applyMarkers = useCallback(() => {
    const m = modelerRef.current;
    const data = pre.data;
    if (!m || !data) return;
    const canvas = m.get('canvas') as CanvasApi;
    const reg = m.get('elementRegistry') as RegistryApi;
    // 先清
    reg.forEach((el) => {
      canvas.removeMarker(el.id, 'pf-unreachable');
      canvas.removeMarker(el.id, 'pf-deadlock');
    });
    for (const i of data.issues) {
      if (!i.nodeKey || !reg.get(i.nodeKey)) continue;
      if (i.code === 'PF-03') canvas.addMarker(i.nodeKey, 'pf-unreachable');
      if (i.code === 'PF-05') canvas.addMarker(i.nodeKey, 'pf-deadlock');
    }
  }, [pre.data]);

  useEffect(() => { if (ready) applyMarkers(); }, [ready, applyMarkers]);

  const history = useQuery({
    queryKey: ['wf-history', wfId, showHistory],
    queryFn: async () => {
      const r = await apiFetch(`/api/workflows/${wfId}/history`, {
        headers: { Authorization: `Bearer ${localStorage.getItem('labos_token')}` },
      });
      return r.json() as Promise<{
        items: Array<{ id: number; nodeCount: number; edgeCount: number;
          bytes: number; savedAt: string }>;
      }>;
    },
    enabled: showHistory,
  });

  const plan = compile.data?.nodes.find((n) => n.nodeKey === selected);
  const methodName = plan?.steps?.[0]?.method;

  // 该方法的参数定义（control_type: input / number / select）
  const methodParams = useQuery({
    queryKey: ['method-params', methodName],
    queryFn: async () => {
      const r = await apiFetch(`/api/methods/${encodeURIComponent(methodName!)}/params`, {
        headers: { Authorization: `Bearer ${localStorage.getItem('labos_token')}` },
      });
      return r.json() as Promise<{
        found: boolean;
        params: Array<{
          name: string; desc?: string; control_type?: string;
          required?: number; default?: string; unit?: string; range?: string;
        }>;
      }>;
    },
    enabled: !!methodName,
  });

  // 选中节点变化时，从 BPMN 属性读已存的参数
  useEffect(() => {
    const m = modelerRef.current;
    if (!m || !selected) { setParamValues({}); return; }
    const reg = m.get('elementRegistry') as
      { get: (id: string) => unknown };
    const el = reg.get(selected) as
      { businessObject?: { $attrs?: Record<string, string> } } | undefined;
    const raw = el?.businessObject?.$attrs?.methodParams;
    if (raw) {
      try { setParamValues(JSON.parse(raw)); } catch { setParamValues({}); }
    } else {
      setParamValues({});
    }
  }, [selected]);

  // 参数定义加载后，用 default 回填空值（只在明确是简单值时，避免把 JSON 片段填进去）
  useEffect(() => {
    const defs = methodParams.data?.params;
    if (!defs?.length) return;
    setParamValues((prev) => {
      const next = { ...prev };
      let changed = false;
      for (const d of defs) {
        const cur = next[d.name];
        const empty = cur === undefined || cur === null || cur === '';
        const dv = d.default;
        const simple = dv && dv.length < 80 && !dv.includes('{') && !dv.includes(':');
        if (empty && simple) {
          next[d.name] = d.control_type === 'number' ? Number(dv) : dv;
          changed = true;
        }
      }
      return changed ? next : prev;
    });
  }, [methodParams.data]);

  /** 必填校验：返回未填的参数名。 */
  const missingRequired = (): string[] => {
    const defs = methodParams.data?.params ?? [];
    return defs
      .filter((d) => d.required && (paramValues[d.name] === undefined
        || paramValues[d.name] === null || paramValues[d.name] === ''))
      .map((d) => d.name);
  };

  /** 把参数写回 BPMN 自定义属性，保存时进 XML。 */
  const applyParams = (next: Record<string, unknown>) => {
    setParamValues(next);
    const m = modelerRef.current;
    if (!m || !selected) return;
    const reg = m.get('elementRegistry') as
      { get: (id: string) => unknown };
    const el = reg.get(selected) as
      { businessObject?: { $attrs?: Record<string, string> } } | undefined;
    if (!el?.businessObject) return;
    const bo = el.businessObject as { $attrs?: Record<string, string> };
    bo.$attrs = bo.$attrs ?? {};
    if (Object.keys(next).length === 0) {
      delete bo.$attrs.methodParams;
    } else {
      bo.$attrs.methodParams = JSON.stringify(next);
    }
    setDirty(true);
  };

  const save = async () => {
    const m = modelerRef.current;
    if (!m) return;

    const miss = missingRequired();
    if (miss.length > 0) {
      message.warning(t('designer.warn.missingParams', { names: miss.join(', ') }));
      return;
    }

    setSaving(true);
    try {
      const { xml } = await m.saveXML({ format: true }) as { xml: string };
      await api.saveGraph(wfId, collapse(xml));
      message.success(t('designer.toast.saved'));
      setDirty(false);
      await Promise.all([
        qc.invalidateQueries({ queryKey: ['compile', wfId] }),
        qc.invalidateQueries({ queryKey: ['preflight', wfId] }),
      ]);
    } catch (e) {
      message.error(t('designer.error.saveFailedDetail', { msg: String((e as Error).message) }));
    } finally {
      setSaving(false);
    }
  };

  const issues: PreflightIssue[] = pre.data?.issues ?? [];

  return (
    <>
      {fromExpId && (
        <Alert
          type="info" showIcon
          style={{ marginBottom: 8 }}
          message={t('designer.fromExp.message', { name: fromExpName || fromExpId })
            + (fromIsland ? t('designer.fromExp.island', { island: fromIsland }) : '')}
          description={t('designer.fromExp.desc')}
          action={(
            <Button size="small" type="primary" onClick={() => nav(`/experiments/${fromExpId}`)}>
              {t('designer.action.backToExperiment')}
            </Button>
          )}
        />
      )}
      <Row gutter={16} style={{ height: fromExpId ? 'calc(100vh - 64px - 64px)' : 'calc(100vh - 64px)' }}>
      <Col flex="auto" style={{ display: 'flex', flexDirection: 'column' }}>
        <Card
          size="small"
          style={{ flex: 'none' }}
          title={t('designer.title', { name: compile.data?.workflow.name ?? wfId })}
          extra={
            <Space>
              {dirty && <Tag color="orange">{t('designer.tag.dirty')}</Tag>}
              {eta.data?.known && (
                <Tooltip title={eta.data.waitSec > 0
                  ? (eta.data.busy ?? []).map((b) => t('designer.eta.busy', {
                    code: b.resourceCode ?? b.resourceId ?? '',
                    holder: b.holderRunNo ?? '',
                    n: Math.ceil(b.remainSec / 60),
                  })).join('; ')
                  : t('designer.eta.allFree')}>
                  <Tag color={eta.data.waitSec > 0 ? 'orange' : 'green'}>
                    {eta.data.waitSec > 0
                      ? t('designer.eta.wait', { wait: fmtWait(eta.data.waitSec, t) })
                      : t('designer.eta.free')}
                  </Tag>
                </Tooltip>
              )}
              <Button onClick={() => setShowTimeline(true)}>{t('designer.action.timeline')}</Button>
              <Button onClick={() => setShowRuns(true)}>{t('designer.action.runs')}</Button>
              <Button onClick={() => setShowHistory(true)}>{t('designer.action.history')}</Button>
              <Button onClick={() => nav('/workflows')}>{t('designer.action.back')}</Button>
              <Button type="primary" loading={saving} {...ro} onClick={save}>{t('designer.action.save')}</Button>
            </Space>
          }
        >
          {!ready && <Spin size="small" />}
          {ready && (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {t('designer.summary.nodes', { n: compile.data?.nodes.length ?? 0 })} ·
              {t('designer.summary.steps', { n: compile.data?.totalSteps ?? 0 })} ·
              {t('designer.summary.preflight')}{pre.data?.blocking
                ? <Tag color="red">{t('designer.preflight.blocking', { n: pre.data.errors })}</Tag>
                : pre.data?.warnings
                  ? <Tag color="orange">{t('designer.preflight.warnings', { n: pre.data.warnings })}</Tag>
                  : <Tag color="green">{t('designer.preflight.pass')}</Tag>}
            </Typography.Text>
          )}
        </Card>

        <div
          ref={containerRef}
          style={{ flex: 1, minHeight: 420, background: '#fff', borderRadius: 6, overflow: 'hidden' }}
        />
      </Col>

      <Col flex="320px">
        {!selected ? (
          <Card size="small">
            <Empty description={t('designer.empty.selectNode')} />
          </Card>
        ) : !plan ? (
          <Card size="small">
            <Empty
              description={selected.includes('__s')
                ? t('designer.empty.substep', { key: selected })
                : t('designer.empty.notInResult', { key: selected })}
            />
          </Card>
        ) : (
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            <NodeSettings
              wfId={String(wfId)} nodeKey={String(selected)}
              onSaved={() => {
                // 重新导入 XML，让画布上的属性/名称跟库一致
                apiFetch(`/api/workflows/${wfId}/graph`)
                  .then((r) => r.text())
                  .then((xml) => modelerRef.current?.importXML(normalize(xml)))
                  .then(() => {
                    // 重新导入会清掉选中态 → 右侧面板消失，看起来像"保存把节点弄丢了"。
                    // 这里把刚才那个节点重新选上。
                    const reg = modelerRef.current?.get('elementRegistry') as
                      { get: (id: string) => unknown } | undefined;
                    const sel = modelerRef.current?.get('selection') as
                      { select: (el: unknown) => void } | undefined;
                    const el = reg?.get(String(selected));
                    if (el) sel?.select(el);
                  })
                  .catch(() => { /* 重新导入失败不影响已保存的数据 */ });
              }}
            />
            <Card size="small" title={plan.name}>
              <Descriptions size="small" column={1}>
                <Descriptions.Item label={t('designer.field.capability')}>
                  <Tag color={plan.capability === 'UNKNOWN' ? 'red' : 'blue'}>{plan.capability}</Tag>
                </Descriptions.Item>
                <Descriptions.Item label={t('designer.field.source')}>{plan.capabilitySource}</Descriptions.Item>
                <Descriptions.Item label={t('designer.field.willRun')}>
                    {t('designer.field.steps', { n: plan.stepCount })}
                  </Descriptions.Item>
                {plan.fromRecipe && (
                  <Descriptions.Item label={t('designer.field.templateSamples')}>
                    {t('designer.field.sampleCount', { n: plan.sampleCount })}
                  </Descriptions.Item>
                )}
              </Descriptions>

              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {t('designer.driverMethod')}
              </Typography.Text>
              <div style={{ marginTop: 4 }}>
                <Typography.Text code style={{ fontSize: 12 }}>
                  {compile.data?.nodes.find((n) => n.nodeKey === selected)?.steps[0]?.method ?? t('designer.method.unspecified')}
                </Typography.Text>
              </div>
            </Card>

            <Card size="small" title={t('designer.card.steps')}>
              <Table
                size="small" pagination={false}
                dataSource={plan.steps}
                rowKey="seq"
                columns={[
                  { title: '#', dataIndex: 'seq', width: 40 },
                  { title: t('designer.col.method'), dataIndex: 'method' },
                ]}
              />
              {plan.fromRecipe && (
                <Typography.Paragraph type="secondary" style={{ fontSize: 11, marginTop: 8 }}>
                  {t('designer.steps.hint')}
                </Typography.Paragraph>
              )}
            </Card>

            {methodName && methodParams.data?.params?.length ? (
              <Card size="small" title={t('designer.card.params', { n: methodParams.data.params.length })}
                extra={<Tag color="blue">{methodName}</Tag>}>
                <Space direction="vertical" size={10} style={{ width: '100%' }}>
                  {methodParams.data.params.map((p) => (
                    <div key={p.name}>
                      <Typography.Text style={{ fontSize: 12 }}>
                        {p.name}
                        {p.required ? <Typography.Text type="danger"> *</Typography.Text> : null}
                        {p.unit ? <Typography.Text type="secondary">（{p.unit}）</Typography.Text> : null}
                        {p.required && (paramValues[p.name] === undefined
                          || paramValues[p.name] === '') ? (
                            <Typography.Text type="danger" style={{ fontSize: 10 }}>{t('designer.params.missing')}</Typography.Text>
                          ) : null}
                      </Typography.Text>
                      {p.desc && (
                        <div>
                          <Typography.Text type="secondary" style={{ fontSize: 10 }}>
                            {p.desc}
                          </Typography.Text>
                        </div>
                      )}
                      {p.control_type === 'number' ? (
                        <InputNumber
                          style={{ width: '100%' }} size="small"
                          value={paramValues[p.name] as number | undefined}
                          onChange={(v) => applyParams({ ...paramValues, [p.name]: v })}
                        />
                      ) : p.control_type === 'select' ? (
                        <Select
                          style={{ width: '100%' }} size="small" allowClear
                          value={paramValues[p.name] as string | undefined}
                          onChange={(v) => applyParams({ ...paramValues, [p.name]: v })}
                          options={(p.range ?? '').split(/[,，;；\n]/).filter(Boolean)
                            .map((o) => ({ value: o.trim(), label: o.trim() }))}
                        />
                      ) : (
                        <Input
                          size="small"
                          placeholder={p.default ?? ''}
                          value={paramValues[p.name] as string | undefined}
                          onChange={(e) => applyParams({ ...paramValues, [p.name]: e.target.value })}
                        />
                      )}
                    </div>
                  ))}
                  <Typography.Text type="secondary" style={{ fontSize: 10 }}>
                    {t('designer.params.storedInBpmn')}
                  </Typography.Text>
                </Space>
              </Card>
            ) : null}

            {issues.filter((i) => i.nodeKey === selected).length > 0 && (
              <Card size="small" title={t('designer.card.nodeIssues')}>
                {issues.filter((i) => i.nodeKey === selected).map((i, k) => (
                  <Alert key={k} type={i.level === 'ERROR' ? 'error' : 'warning'}
                    message={`${i.code} ${i.message}`} style={{ marginBottom: 6 }} />
                ))}
              </Card>
            )}
          </Space>
        )}
      </Col>
      <Modal
        open={showTimeline}
        title={t('designer.estimate.title', { name: compile.data?.workflow.name ?? wfId })}
        onCancel={() => setShowTimeline(false)}
        footer={null}
        width={720}
      >
        <TimelineChart data={timeline.data} />
      </Modal>

      <Modal
        open={showRuns}
        title={t('designer.runs.title', { name: compile.data?.workflow.name ?? wfId })}
        onCancel={() => setShowRuns(false)}
        footer={null}
        width={760}
      >
        <Alert
          type="info" showIcon style={{ marginBottom: 12 }}
          message={t('designer.runs.hintTitle')}
          description={t('designer.runs.hintDesc')}
        />
        <RunsTable wfId={Number(wfId)} onOpen={(id) => nav(`/runs/${id}`)} />
      </Modal>

      <Modal
        open={showHistory}
        title={t('designer.history.title', { name: compile.data?.workflow.name ?? wfId })}
        onCancel={() => setShowHistory(false)}
        footer={null}
        width={620}
      >
        <Alert type="info" showIcon style={{ marginBottom: 12 }}
          message={t('designer.history.hintTitle')}
          description={t('designer.history.hintDesc')} />
        <Table
          rowKey="id" size="small" loading={history.isLoading}
          dataSource={history.data?.items ?? []}
          pagination={false}
          columns={[
            {
              title: t('designer.history.col.version'), dataIndex: 'id', width: 70,
              render: (v: number) => `#${v}`,
            },
            {
              title: t('designer.history.col.time'), dataIndex: 'savedAt', width: 170,
              render: (v: string) => (
                <span style={{ fontFamily: 'monospace', fontSize: 11 }}>
                  {(v ?? '').slice(0, 19).replace('T', ' ')}
                </span>
              ),
            },
            {
              title: t('designer.history.col.size'), width: 140,
              render: (_, r) => (
                <Typography.Text style={{ fontSize: 11 }}>
                  {t('designer.history.sizeText', { nodes: r.nodeCount, edges: r.edgeCount })}
                </Typography.Text>
              ),
            },
            {
              title: t('designer.history.col.bytes'), dataIndex: 'bytes', width: 90,
              render: (v: number) => `${(v / 1024).toFixed(1)}KB`,
            },
            {
              title: t('designer.history.col.action'), width: 90,
              render: (_, r) => (
                <Button size="small" type="link" danger {...ro} onClick={async () => {
                  if (!confirm(t('designer.history.restoreConfirm', { id: r.id }))) return;
                  try {
                    const resp = await apiFetch(
                      `/api/workflows/${wfId}/history/${r.id}/restore`, {
                        method: 'POST',
                        headers: { Authorization: `Bearer ${localStorage.getItem('labos_token')}` },
                      });
                    const j = await resp.json();
                    if (!resp.ok) throw new Error(j.error ?? t('designer.history.restoreFailed'));
                    message.success(t('designer.history.restored', { n: j.nodes }));
                    setShowHistory(false);
                    setDirty(false);
                    await Promise.all([
                      qc.invalidateQueries({ queryKey: ['compile', wfId] }),
                      qc.invalidateQueries({ queryKey: ['preflight', wfId] }),
                    ]);
                    // 重新加载画布
                    const xml = await api.graphXml(wfId, true);
                    await modelerRef.current?.importXML(normalize(xml));
                  } catch (e) {
                    message.error(String((e as Error).message));
                  }
                }}>{t('designer.action.restore')}</Button>
              ),
            },
          ]}
        />
      </Modal>
      </Row>
    </>
  );
}
