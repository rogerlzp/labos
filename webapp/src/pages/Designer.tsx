import { useCallback, useEffect, useRef, useState } from 'react';
import type { DragEvent as ReactDragEvent } from 'react';
import DeviceImageRenderer, { iconFor, isArm } from '../bpmn/DeviceImageRenderer';
import { workflowPaletteModule } from '../bpmn/WorkflowPalette';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert, Button, Card, Checkbox, Col, Descriptions, Empty, Input, InputNumber, Modal,
  Row, Select, Space, Spin, Table, Tabs, Tag, Tooltip, Typography, message,
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

/** 拖入方法节点时用到的 bpmn-js / diagram-js 服务能力。 */
interface BpmnDragApi {
  // 注意：这个 bpmn-js/diagram-js 版本没有 canvas.getAbsoluteOrigin()，
  // 坐标换算只能用 viewbox() + 容器 rect。
  viewbox: () => { x: number; y: number; width: number; height: number; scale: number } | undefined;
  getContainer?: () => { getBoundingClientRect?: () => { left: number; top: number } } | undefined;
  getRootElement: () => unknown;
}
interface BpmnModelingApi {
  createShape: (shape: unknown, position: { x: number; y: number }, parent: unknown) => unknown;
  connect: (source: unknown, target: unknown) => unknown;
  removeElements: (elements: unknown[]) => void;
}
interface BpmnElementFactoryApi {
  createShape: (opts: { type: string }) => {
    id: string;
    businessObject: { name?: string; $attrs?: Record<string, string> };
  };
}
interface BpmnSelectionApi {
  select: (el: unknown) => void;
}
/** modeling.updateProperties / moddle.create：写连线条件与网关默认边要用。 */
interface BpmnUpdateApi {
  updateProperties: (el: unknown, props: Record<string, unknown>) => void;
}
interface BpmnModdleApi {
  create: (type: string, props: Record<string, unknown>) => unknown;
}
/** 受控选中元素：id 用来查编译结果，type 用来区分「连线」与「节点」。 */
interface SelectedElement {
  id: string;
  type: string;
  businessObject?: {
    conditionExpression?: { body?: string };
    sourceRef?: { id: string; $type?: string; default?: { id?: string } };
  };
}
interface BpmnRegistryApi {
  get: (id: string) => unknown;
  forEach: (fn: (el: { id: string; type: string; waypoints?: unknown }) => void) => void;
}

interface PaletteDevice {
  resourceId: number;
  code: string;
  name?: string;
  capability?: string;
  icon?: string | null;
  islandId?: number;
  islandCode?: string;
}

/** 拖进画布的一条「设备方法」：落节点时直接带上绑定，不用再选设备/方法。 */
interface DroppedMethod {
  resourceId?: number;
  code: string;
  method: string;
  /** 逻辑节点（__setVar / __expr）：没有设备，不参与机械臂搬运。 */
  builtin?: boolean;
  capability?: string;
  icon?: string | null;
  islandId?: number;
  islandCode?: string;
}

/** 上一次拖入的节点：用于判断「板子换设备了」并自动插入机械臂搬运。 */
interface LastDropped {
  shapeId: string;
  resourceId?: number;
  code: string;
  builtin?: boolean;
  capability?: string;
  icon: string;
  x: number;
}

/** 左侧「按岛设备」面板里的一台设备：点开才懒加载它的方法。 */
function DeviceMethodRow({ d, disabled }: { d: PaletteDevice; disabled: boolean }) {
  const t = useT();
  const [open, setOpen] = useState(false);
  // 与 NodeSettings 共用 queryKey，选中同一台设备时不会重复请求
  const methods = useQuery({
    queryKey: ['device-methods', d.resourceId],
    queryFn: () => apiFetch(`/api/devices/${d.resourceId}/methods`)
      .then((r) => r.json()) as Promise<{
        items?: Array<{ method: string; name?: string }>;
      }>,
    enabled: open,
  });
  const list = methods.data?.items ?? [];

  return (
    <div style={{ marginBottom: 4, border: '1px solid #ffffff14', borderRadius: 4 }}>
      <div
        data-testid="palette-device"
        data-icon={iconFor(d.icon, d.capability)}
        onClick={() => setOpen((v) => !v)}
        style={{
          display: 'flex', alignItems: 'center', gap: 6, padding: '5px 7px',
          cursor: 'pointer', fontSize: 12, userSelect: 'none',
        }}
      >
        <span style={{ color: '#8b93a7' }}>{open ? '▾' : '▸'}</span>
        <img
          src={iconFor(d.icon, d.capability)}
          alt={d.code}
          style={{ width: 34, height: 24, objectFit: 'cover', borderRadius: 3,
                   flexShrink: 0, border: '1px solid #ffffff22' }}
          onError={(e) => { (e.currentTarget as HTMLImageElement).style.display = 'none'; }}
        />
        <span style={{ fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
          {d.code}
        </span>
        <span style={{ color: '#8b93a7', fontSize: 10, marginLeft: 'auto' }}>
          {d.capability ?? '—'}
        </span>
      </div>
      {open && (
        <div style={{ padding: '2px 6px 6px 18px' }}>
          {methods.isLoading && <Spin size="small" />}
          {!methods.isLoading && list.length === 0 && (
            <Typography.Text type="secondary" style={{ fontSize: 11 }}>
              {t('designer.palette.noMethods')}
            </Typography.Text>
          )}
          {list.map((m) => (
            <div
              key={m.method}
              data-testid="palette-method"
              data-method={m.method}
              data-resource-id={d.resourceId}
              draggable={!disabled}
              onDragStart={(e) => {
                const payload: DroppedMethod = {
                  resourceId: d.resourceId,
                  code: d.code,
                  method: m.method,
                  capability: d.capability,
                  icon: d.icon,
                  islandId: d.islandId,
                  islandCode: d.islandCode,
                };
                e.dataTransfer.setData('application/x-labos-method', JSON.stringify(payload));
                e.dataTransfer.effectAllowed = 'copy';
              }}
              title={disabled ? t('designer.palette.readonly') : t('designer.palette.dragHint')}
              style={{
                padding: '4px 7px', marginBottom: 3, borderRadius: 3,
                border: '1px dashed #0ea5e955', background: '#0ea5e914',
                fontSize: 11, cursor: disabled ? 'not-allowed' : 'grab',
              }}
            >
              ⠿ {m.method}{m.name ? ' · ' + m.name : ''}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

/** 内置逻辑节点的四种方法名（protocolName 以 __ 开头，不找设备、不走驱动）。 */
type BuiltinKind = '__setVar' | '__expr' | '__sleep' | '__wait';

/** 内置节点拖到画布时写进 `businessObject.name` 的显示名。 */
function builtinNodeLabel(t: (k: MessageKey) => string, method: string): string {
  return t(method === '__sleep' ? 'designer.node.sleep'
    : method === '__wait' ? 'designer.node.wait'
    : method === '__expr' ? 'designer.node.expr'
    : 'designer.node.setVar');
}

/** 左侧「逻辑节点」面板里的一行：拖到画布生成内置节点（不带设备）。 */
function LogicNodeRow({ method, label, disabled }: {
  method: BuiltinKind;
  label: string;
  disabled: boolean;
}) {
  const t = useT();
  return (
    <div
      data-testid="palette-logic"
      data-method={method}
      draggable={!disabled}
      onDragStart={(e) => {
        const payload: DroppedMethod = { code: method, method, builtin: true };
        e.dataTransfer.setData('application/x-labos-method', JSON.stringify(payload));
        e.dataTransfer.effectAllowed = 'copy';
      }}
      title={disabled ? t('designer.palette.readonly') : t('designer.palette.dragHint')}
      style={{
        padding: '5px 7px', marginBottom: 4, borderRadius: 3,
        border: '1px dashed #a855f755', background: '#a855f714',
        fontSize: 11, cursor: disabled ? 'not-allowed' : 'grab',
      }}
    >
      ⠿ {label}
    </div>
  );
}

/** 用 customDevice 表示设备动作，标准 modeler 不认，转成 task 并补 name。 */
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
    // methodParams 必须一起搬：拖入的方法节点参数写在 XML 属性上，展开视图
    // 把它变成 subProcess 时若白名单漏了，保存就会把参数丢掉。
    for (const a of ['protocolName', 'protocolId', 'resource', 'resourceName',
                     'variablesValue', 'methodParams', 'icon']) {
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
function NodeSettings({ wfId, nodeKey, islandId, onSaved }: {
  wfId: string;
  nodeKey: string;
  /** 只显示这个岛的设备；null = 全部岛（旧流程回落） */
  islandId: number | null;
  onSaved: () => void;
}) {
  const t = useT();
  // 节点设置里的"保存"会改 workflow_node.params：viewer 禁用
  const ro = useReadOnly();
  const [kind, setKind] = useState<'device' | BuiltinKind>('device');
  const [varName, setVarName] = useState('');
  const [val, setVal] = useState('');
  /** 等待节点的超时秒数（留空 = 后端默认 300）。 */
  const [waitTimeout, setWaitTimeout] = useState('');
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
      const builtin = m === '__setVar' || m === '__expr' || m === '__sleep' || m === '__wait';
      setKind(builtin ? (m as BuiltinKind) : 'device');
      setVarName(j.params?.var ?? '');
      setVal(m === '__expr' ? (j.params?.expr ?? '')
        : m === '__sleep' ? (j.params?.sec ?? '')
        : (j.params?.value ?? ''));
      setWaitTimeout(m === '__wait' ? (j.params?.timeout ?? '') : '');
      if (!builtin) {
        setDeviceId(j.resourceId ?? null);
        setMethodName(m);
        // 存量数据里 method_params 可能是 0 / 字符串（不是对象）——
        // 直接 stringify 会把 "0" 塞进输入框，结构化输入就全空了。统一归一成对象。
        const p0 = j.params as unknown;
        setParamsText(p0 && typeof p0 === "object" && !Array.isArray(p0)
          ? JSON.stringify(p0) : "{}");
      }
      setLoaded(true);
      return j;
    },
  });

  // 设备池：按所属岛过滤（islandId 为空 = 全部岛，兼容旧流程与岛屿视图）
  const pool = useQuery({
    queryKey: ['device-pool', islandId],
    queryFn: () => apiFetch(`/api/devices/pool${islandId ? `?islandId=${islandId}` : ''}`)
      .then((r) => r.json()) as Promise<{
      items: Array<{ resourceId: number; code: string; name?: string;
                     capability?: string; islandCode?: string }>;
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
      } else if (kind === '__sleep') {
        // 延时节点：sec = 在这里停多少秒（后端按正数校验，空/非正数会直接报错）
        params = { sec: val };
      } else if (kind === '__wait') {
        // 等待节点：var/value 必填；timeout 留空则由后端按 300 秒兜底
        params = waitTimeout.trim()
          ? { var: varName, value: val, timeout: waitTimeout.trim() }
          : { var: varName, value: val };
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
          onChange={(v) => setKind(v as 'device' | BuiltinKind)}
          options={[
            { value: 'device', label: t('designer.kind.device') },
            { value: '__setVar', label: t('designer.kind.setVar') },
            { value: '__expr', label: t('designer.kind.expr') },
            { value: '__sleep', label: t('designer.kind.sleep') },
            { value: '__wait', label: t('designer.kind.wait') },
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
                label: `${d.code}${d.name ? ' · ' + d.name : ''}（${d.capability ?? '—'}）@${d.islandCode ?? '—'}`,
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
        ) : kind === '__sleep' ? (
          <>
            <Input size="small" placeholder={t('designer.placeholder.sleepSec')}
              value={val} onChange={(e) => setVal(e.target.value)} />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {t('designer.sleep.hint')}
            </Typography.Text>
          </>
        ) : kind === '__wait' ? (
          <>
            <Input size="small" placeholder={t('designer.placeholder.varName')}
              value={varName} onChange={(e) => setVarName(e.target.value)} />
            <Input size="small" placeholder={t('designer.placeholder.waitValue')}
              value={val} onChange={(e) => setVal(e.target.value)} />
            <Input size="small" placeholder={t('designer.placeholder.waitTimeout')}
              value={waitTimeout} onChange={(e) => setWaitTimeout(e.target.value)} />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {t('designer.wait.hint')}
            </Typography.Text>
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
 * 流程选项：目前只有**流程级**的循环圈数上限（回边最多走几圈）。
 *
 * <p>为什么不放在节点设置里：`maxIterations` 存在 {@code workflow.attr}，
 * 是整条流程的属性，不是某个网关/节点的属性。放这里避免"设了某条回边却没生效"。
 */
function FlowOptionsCard({ wfId }: { wfId: number }) {
  const t = useT();
  const ro = useReadOnly();
  const qc = useQueryClient();
  const [n, setN] = useState<number | null>(null);
  const [saving, setSaving] = useState(false);
  const q = useQuery({
    queryKey: ['wf-options', wfId],
    queryFn: () => api.workflowOptions(wfId),
    enabled: Number.isFinite(wfId),
  });
  useEffect(() => { if (q.data) setN(q.data.maxIterations); }, [q.data]);

  const save = async () => {
    if (n == null) return;
    setSaving(true);
    try {
      await api.saveWorkflowOptions(wfId, n);
      message.success(t('designer.toast.optionsSaved'));
      qc.invalidateQueries({ queryKey: ['wf-options', wfId] });
    } catch (e) {
      message.error(String((e as Error).message));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Card size="small" title={t('designer.options.title')} style={{ marginBottom: 12 }}>
      <Space direction="vertical" size={6} style={{ width: '100%' }}>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {t('designer.options.maxIterations')}
        </Typography.Text>
        <InputNumber
          size="small" min={1} max={100} style={{ width: '100%' }}
          value={n ?? undefined}
          onChange={(v) => setN(v ?? null)}
        />
        <Typography.Text type="secondary" style={{ fontSize: 11 }}>
          {t('designer.options.hint')}
        </Typography.Text>
        <Button size="small" type="primary" loading={saving} {...ro} onClick={save}>
          {t('designer.action.save')}
        </Button>
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
  // 新建流程时选的岛（URL 透传，刷新后仍在）；旧流程未选岛 → null = 全部岛
  const [islandId, setIslandId] = useState<number | null>(() => {
    const v = Number(sp.get('islandId'));
    return Number.isFinite(v) && v > 0 ? v : null;
  });
  const islandsQ = useQuery({
    queryKey: ['designer-islands'],
    queryFn: () => apiFetch('/api/topology').then((r) => r.json()) as Promise<{
      islands?: Array<{ id: number; code: string; name?: string }>;
    }>,
  });
  // 左侧设备面板：与 NodeSettings 共用 ['device-pool', islandId] 缓存，不额外发请求
  const paletteDevices = useQuery({
    queryKey: ['device-pool', islandId],
    queryFn: () => apiFetch(`/api/devices/pool${islandId ? `?islandId=${islandId}` : ''}`)
      .then((r) => r.json()) as Promise<{ items?: PaletteDevice[] }>,
  });
  const qc = useQueryClient();
  const wfId = Number(id);

  const containerRef = useRef<HTMLDivElement>(null);
  const modelerRef = useRef<BpmnModeler | null>(null);
  // 上一次拖入的节点：判断「板子换设备」→ 自动插入机械臂 Transfer 并连线
  const lastDroppedRef = useRef<LastDropped | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  /** 选中元素的 bpmn 类型：用来区分「节点」与「连线」（连线不在 compile 结果里）。 */
  const [selType, setSelType] = useState('');
  /** 选中网关的显示名（写进 BPMN 的 name 属性） */
  const [nodeName, setNodeName] = useState('');
  /** 选中网关的角色建议：并行分叉 / 并行汇合 / 异或分叉 / 异或汇合 */
  const [gatewayHint, setGatewayHint] = useState('');
  /** 连线的条件表达式（选中 SequenceFlow 时才有意义） */
  const [flowCond, setFlowCond] = useState('');
  /** 这条连线是否为源网关的默认边（无条件兜底） */
  const [flowDefault, setFlowDefault] = useState(false);
  /** 源节点是不是「网关」：只有网关的 default 属性会被解析器认 */
  const [flowSrcGateway, setFlowSrcGateway] = useState(false);
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
    // 自定义 renderer：带 $attrs.icon 的设备节点画成设备图片卡片，而不是统一矩形。
    // 优先级 2000 > bpmnRenderer 默认 1000；只对 bpmn:Task 接管，展开视图回落原生渲染。
    const modeler = new BpmnModeler({
      container: containerRef.current,
      additionalModules: [{
        __init__: ['deviceImageRenderer'],
        deviceImageRenderer: ['type', DeviceImageRenderer],
      },
      // palette：补并行网关入口 + 移除解析器不认的入口（见 WorkflowPalette.ts 注释）
      workflowPaletteModule],
    } as any);
    modelerRef.current = modeler;

    modeler.on('commandStack.changed', () => setDirty(true));
    modeler.on('selection.changed', (e: { newSelection: Array<{ id: string; type: string }> }) => {
      const el = e.newSelection?.[0];
      setSelected(el?.id ?? null);
      setSelType(el?.type ?? '');
      // 网关的显示名与角色建议：选中即读一次（角色由入/出边数推出，与后端同一口径）
      if (el?.id) {
        const r = readNodeName(el.id);
        setNodeName(r.name);
        setGatewayHint(r.hint);
      } else {
        setNodeName('');
        setGatewayHint('');
      }
    });
    return () => {
      modeler.destroy();
      modelerRef.current = null;
    };
  }, []);

  /**
   * 读节点的显示名 + 算网关默认名建议。
   *
   * <p>为什么需要：两种网关在页面上都是菱形（✕ / ＋），缩小后几乎分不出；
   * 而「分叉」还是「汇合」光看形状也看不出来 —— 引擎是按连线数自动判定的
   * （后端 `WorkflowGraphParser`：入边 > 1 是汇合、出边 > 1 是分叉、
   * 都 > 1 按汇合先等再分、单进单出等价于直通）。
   * 给个默认名，图上一眼就知道这个菱形在干什么。
   *
   * <p>推导规则**必须和后端一致**，否则名字会骗人。
   */
  const readNodeName = (key: string): { name: string; hint: string } => {
    const m = modelerRef.current;
    if (!m || !key) return { name: '', hint: '' };
    const reg = m.get('elementRegistry') as {
      get: (id: string) => {
        type?: string;
        businessObject?: {
          name?: string;
          /** incoming / outgoing 挂在 businessObject 上（bpmn-moddle 的关联属性），
           *  **不在 element 上** —— 读错位置会拿到 undefined，边数恒为 0，
           *  于是所有网关都被判成「直通」（2026-11-05 实测踩到）。 */
          incoming?: unknown[];
          outgoing?: unknown[];
        };
      } | undefined;
    };
    const el = reg.get(key);
    const bo = el?.businessObject;
    const type = el?.type ?? '';
    const ins = bo?.incoming?.length ?? 0;
    const outs = bo?.outgoing?.length ?? 0;
    let hint = '';
    if (type === 'bpmn:ExclusiveGateway') {
      // 异或：二选一（分叉）/ 任一条到达即放行（汇合）
      hint = ins > 1 ? '异或汇合' : outs > 1 ? '异或分叉' : '';
    } else if (type === 'bpmn:ParallelGateway') {
      // 并行：全走（分叉）/ 所有入边到齐才放行（汇合）
      hint = ins > 1 ? '并行汇合' : outs > 1 ? '并行分叉' : '';
    }
    return { name: el?.businessObject?.name ?? '', hint };
  };

  /**
   * 把显示名写回 BPMN 的 name 属性。
   *
   * <p>走 bpmn-js 的 `modeling.updateProperties` 而不是直接改 businessObject：
   * 前者进 commandStack，上面监听的 `commandStack.changed` 会自动把页面标脏，
   * 「保存」按钮才会亮；直接赋值不会。
   */
  const writeNodeName = (key: string, name: string) => {
    const m = modelerRef.current;
    if (!m || !key) return;
    const modeling = m.get('modeling') as {
      updateProperties: (el: unknown, props: Record<string, unknown>) => void;
    } | undefined;
    const reg = m.get('elementRegistry') as { get: (id: string) => unknown };
    const el = reg.get(key);
    if (!modeling || !el) return;
    const next = name.trim();
    modeling.updateProperties(el, { name: next || undefined });
  };

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

  // 选中连线时：读回它现有的条件表达式与「默认边」归属
  useEffect(() => {
    if (selType !== 'bpmn:SequenceFlow') return;
    const m = modelerRef.current;
    if (!m || !selected) return;
    const reg = m.get('elementRegistry') as { get: (id: string) => SelectedElement | undefined };
    const bo = reg.get(selected)?.businessObject;
    setFlowCond(bo?.conditionExpression?.body ?? '');
    const src = bo?.sourceRef;
    setFlowDefault(!!src?.default && src.default.id === selected);
    setFlowSrcGateway(!!src && (src.$type === 'bpmn:ExclusiveGateway'
      || src.$type === 'bpmn:ParallelGateway'));
  }, [selected, selType]);

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
    // 同 onDropMethod：$attrs 可能是只读 getter，不能整体赋值
    let attrs = bo.$attrs;
    if (!attrs) {
      attrs = {};
      Object.defineProperty(bo, '$attrs', {
        value: attrs, writable: true, configurable: true, enumerable: false,
      });
    }
    if (Object.keys(next).length === 0) {
      delete attrs.methodParams;
    } else {
      attrs.methodParams = JSON.stringify(next);
    }
    setDirty(true);
  };

  /** 图变更后立刻落库并刷新 compile（拖入 / 插机械臂 / 删除共用）。 */
  const persistAfterDrop = async (nodeKey: string, toast?: string) => {
    const m = modelerRef.current;
    if (!m) return;
    setSaving(true);
    try {
      const { xml } = await m.saveXML({ format: true }) as { xml: string };
      // 故意不走 save()：它带「必填参数」守卫，而刚拖入的节点还没有参数定义。
      // saveGraph 的 INSERT 分支直接把 protocolName/resource 写进 workflow_node，
      // 也就避开了 e2e-sim-flow 里「绑完设备还要再点一次保存」的老坑。
      await api.saveGraph(wfId, collapse(xml));
      setDirty(false);
      await Promise.all([
        qc.invalidateQueries({ queryKey: ['compile', wfId] }),
        qc.invalidateQueries({ queryKey: ['preflight', wfId] }),
        qc.invalidateQueries({ queryKey: ['node-binding', String(wfId), nodeKey] }),
      ]);
      message.success(toast ?? t('designer.toast.methodDropped'));
    } catch (e) {
      message.error(t('designer.error.saveFailedDetail', { msg: String((e as Error).message) }));
    } finally {
      setSaving(false);
    }
  };

  /** $attrs 在新建 shape 上是只读 getter，只能取回对象逐属性写。 */
  const setShapeAttrs = (
    shape: ReturnType<BpmnElementFactoryApi['createShape']>,
    kv: Record<string, string>,
  ) => {
    const bo = shape.businessObject;
    let attrs = bo.$attrs as Record<string, string> | undefined;
    if (!attrs) {
      attrs = {};
      Object.defineProperty(bo, '$attrs', {
        value: attrs, writable: true, configurable: true, enumerable: false,
      });
    }
    Object.assign(attrs, kv);
  };

  /**
   * 把左侧的「设备方法」拖到画布：生成已绑定设备 + 方法 + 图片的 bpmn:Task。
   * 若板子从设备 A 换到设备 B，按设计 §14.2/§14.3 在中间自动插入岛上机械臂的
   * Transfer 节点并用 SequenceFlow 串起来 —— 否则板子永远搬不过去。
   */
  const onDropMethod = async (e: ReactDragEvent<HTMLDivElement>) => {
    e.preventDefault();
    if (ro.disabled) return;
    const raw = e.dataTransfer.getData('application/x-labos-method');
    if (!raw) return;
    let p: DroppedMethod;
    try {
      p = JSON.parse(raw) as DroppedMethod;
    } catch {
      return;
    }
    const m = modelerRef.current;
    const isBuiltin = !!p?.builtin;
    // 逻辑节点（__setVar / __expr）没有设备，只要求 method；设备方法还要求 resourceId
    if (!m || !p?.method || (!isBuiltin && !p.resourceId)) return;

    const factory = m.get('elementFactory') as unknown as BpmnElementFactoryApi;
    const modeling = m.get('modeling') as unknown as BpmnModelingApi;
    const canvasApi = m.get('canvas') as unknown as BpmnDragApi;
    const selection = m.get('selection') as unknown as BpmnSelectionApi;
    const registry = m.get('elementRegistry') as unknown as BpmnRegistryApi;

    // 屏幕坐标 → diagram 坐标；createShape 的 position 是形状中心（Task 100×80）
    const vb = canvasApi.viewbox();
    const scale = vb?.scale && vb.scale > 0 ? vb.scale : 1;
    const rect = canvasApi.getContainer?.()?.getBoundingClientRect?.()
      ?? (e.currentTarget as HTMLElement).getBoundingClientRect();
    const dropX = (e.clientX - rect.left) / scale + (vb?.x ?? 0) + 50;
    const y = (e.clientY - rect.top) / scale + (vb?.y ?? 0) + 40;
    const prev = lastDroppedRef.current;
    // 连续拖入自动横向排布，避免节点重叠（机械臂插在两者中间）
    const x = prev ? Math.max(dropX, prev.x + 300) : dropX;
    const root = canvasApi.getRootElement();
    const icon = isBuiltin ? '' : iconFor(p.icon, p.capability);

    // 板子换设备必须由机械臂搬运；岛上的 TRANSFER 设备从同一个设备池取。
    // 逻辑节点不占设备，也不参与搬运（否则会在变量节点和真设备之间插一台机械臂）。
    const arm = isBuiltin ? undefined : (paletteDevices.data?.items ?? []).find((d) =>
      isArm(d.capability) && (!p.islandId || d.islandId === p.islandId));
    const needTransfer = !!(prev && !prev.builtin && !isBuiltin
      && prev.resourceId !== p.resourceId
      && !isArm(prev.capability) && !isArm(p.capability) && arm);
    const prevEl = prev ? registry.get(prev.shapeId) : null;

    const shape = factory.createShape({ type: 'bpmn:Task' });
    // resource / icon 只在设备任务上写：内置节点的判定只认 protocolName 的 __ 前缀，
    // 多写一个 resource=0 反而会让"没绑设备"和"绑了 0 号设备"看起来一样。
    setShapeAttrs(shape, isBuiltin
      ? { protocolName: p.method, methodParams: '{}' }
      : { protocolName: p.method, resource: String(p.resourceId), methodParams: '{}', icon });
    shape.businessObject.name = isBuiltin ? builtinNodeLabel(t, p.method) : p.method;
    modeling.createShape(shape, { x, y }, root);

    if (needTransfer && arm && prev && prevEl) {
      const armShape = factory.createShape({ type: 'bpmn:Task' });
      setShapeAttrs(armShape, {
        protocolName: 'Transfer',
        resource: String(arm.resourceId),
        methodParams: JSON.stringify({ source: prev.code, target: p.code }),
        icon: iconFor(arm.icon, arm.capability),
      });
      armShape.businessObject.name = t('designer.node.armTransfer');
      modeling.createShape(armShape, { x: (prev.x + x) / 2, y }, root);
      modeling.connect(prevEl, armShape);
      modeling.connect(armShape, shape);
      message.info(t('designer.toast.armInserted', { from: prev.code, to: p.code }));
    } else if (prev && prevEl) {
      modeling.connect(prevEl, shape);
    }

    lastDroppedRef.current = {
      shapeId: shape.id, resourceId: p.resourceId, code: p.code, builtin: isBuiltin,
      capability: p.capability, icon, x,
    };
    selection.select(shape);
    await persistAfterDrop(shape.id, isBuiltin ? t('designer.toast.logicDropped') : undefined);
  };

  /** 轻校验：括号成对、${ 都闭合。真正的求值在后端 ConditionEvaluator，不在这里做第二套。 */
  const condLooksValid = (s: string): boolean => {
    let depth = 0;
    for (const ch of s) {
      if (ch === '(') depth += 1;
      else if (ch === ')') { depth -= 1; if (depth < 0) return false; }
    }
    if (depth !== 0) return false;
    return (s.match(/\$\{/g) ?? []).length === (s.match(/\}/g) ?? []).length;
  };

  /**
   * 保存连线的条件表达式与「默认边」标记。
   *
   * <p>写的是 bpmn:conditionExpression 子元素与源网关的 default 属性 ——
   * 这正是 {@code WorkflowGraphParser} 读条件（conditionExpression 文本）与
   * 默认边（任意元素上的 default 属性）的两处入口，写别的形式运行时读不到。
   */
  const saveFlow = async () => {
    const m = modelerRef.current;
    if (!m || !selected || ro.disabled) return;
    const reg = m.get('elementRegistry') as { get: (id: string) => SelectedElement | undefined };
    const el = reg.get(selected);
    const bo = el?.businessObject;
    if (!el || !bo) return;
    const body = flowCond.trim();
    if (body && !condLooksValid(body)) {
      message.warning(t('designer.error.badCondition'));
      return;
    }
    const modeling = m.get('modeling') as unknown as BpmnUpdateApi;
    const moddle = m.get('moddle') as unknown as BpmnModdleApi;
    setSaving(true);
    try {
      modeling.updateProperties(el, {
        conditionExpression: body
          ? moddle.create('bpmn:FormalExpression', { body })
          : undefined,
      });
      // default 只有挂在网关（或其它元素）上才会被解析器收；挂在连线上无人读。
      const src = bo.sourceRef;
      if (src && (src.$type === 'bpmn:ExclusiveGateway' || src.$type === 'bpmn:ParallelGateway')) {
        const srcEl = reg.get(src.id);
        if (srcEl) {
          modeling.updateProperties(srcEl, { default: flowDefault ? bo : undefined });
        }
      }
      await persistAfterDrop(selected, t('designer.toast.flowSaved'));
    } finally {
      setSaving(false);
    }
  };

  /**
   * 显式删除入口。bpmn-js 原生的 contextPad 垃圾桶 / Delete 键一直可用，但
   * 「选中才出现 + 无引导 + 删完还要点保存」让用户以为不能删。这里给常驻按钮，
   * 删除后与拖入一样立即落库，并拦住「删空」（saveGraph 拒绝 0 节点图）。
   */
  const deleteSelected = async () => {
    const m = modelerRef.current;
    if (!m || !selected || ro.disabled) return;
    const registry = m.get('elementRegistry') as unknown as BpmnRegistryApi;
    const modeling = m.get('modeling') as unknown as BpmnModelingApi;
    const el = registry.get(selected);
    if (!el) return;
    let nodes = 0;
    registry.forEach((n) => {
      if (n.type !== 'bpmn:Process' && !n.waypoints) nodes += 1;
    });
    if (nodes <= 1) {
      message.warning(t('designer.warn.keepOneNode'));
      return;
    }
    modeling.removeElements([el]);
    if (lastDroppedRef.current?.shapeId === selected) {
      lastDroppedRef.current = null;
    }
    await persistAfterDrop(selected, t('designer.toast.nodeDeleted'));
    setSelected(null);
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
      {/* 按岛设备：岛 → 设备 → 方法；把方法拖进画布直接生成已绑定节点 */}
      <Col flex="240px" style={{ minHeight: 0 }}>
        <Card size="small" title={t('designer.palette.title')}>
          <Select
            size="small" allowClear style={{ width: '100%', marginBottom: 6 }}
            placeholder={t('designer.field.allIslands')}
            value={islandId ?? undefined}
            onChange={(v: number | undefined) => {
              setIslandId(v ?? null);
              const next = new URLSearchParams(sp);
              if (v) next.set('islandId', String(v)); else next.delete('islandId');
              nav({ pathname: `/designer/${wfId}`, search: next.toString() }, { replace: true });
            }}
            options={(islandsQ.data?.islands ?? []).map((i) => ({
              value: i.id,
              label: `${i.code}${i.name ? ' · ' + i.name : ''}`,
            }))}
          />
          <Tabs
            size="small"
            defaultActiveKey="devices"
            items={[
              {
                key: 'devices',
                label: t('designer.palette.tabDevices'),
                children: (
                  <>
                    <Typography.Paragraph type="secondary" style={{ fontSize: 10, marginBottom: 8 }}>
                      {t('designer.palette.hint')}
                    </Typography.Paragraph>
                    <div style={{ maxHeight: 'calc(100vh - 330px)', overflow: 'auto' }}>
                      {paletteDevices.isLoading && <Spin size="small" />}
                      {(paletteDevices.data?.items ?? []).length === 0 && !paletteDevices.isLoading && (
                        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}
                          description={t('designer.palette.noDevices')} />
                      )}
                      {(paletteDevices.data?.items ?? []).map((d) => (
                        <DeviceMethodRow key={d.resourceId} d={d} disabled={ro.disabled} />
                      ))}
                    </div>
                  </>
                ),
              },
              {
                key: 'logic',
                label: t('designer.palette.tabLogic'),
                children: (
                  <>
                    <Typography.Paragraph type="secondary" style={{ fontSize: 10, marginBottom: 8 }}>
                      {t('designer.palette.logicHint')}
                    </Typography.Paragraph>
                    <LogicNodeRow method="__setVar" label={t('designer.kind.setVar')}
                      disabled={ro.disabled} />
                    <LogicNodeRow method="__expr" label={t('designer.kind.expr')}
                      disabled={ro.disabled} />
                    <LogicNodeRow method="__sleep" label={t('designer.kind.sleep')}
                      disabled={ro.disabled} />
                    <LogicNodeRow method="__wait" label={t('designer.kind.wait')}
                      disabled={ro.disabled} />
                  </>
                ),
              },
            ]}
          />
        </Card>
      </Col>
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

        {/* 拖放挂在外层 wrapper：bpmn-js 的 SVG 会吞掉 drop 事件 */}
        <div
          style={{ flex: 1, minHeight: 420, position: 'relative', background: '#fff', borderRadius: 6 }}
          onDragOver={(e) => { e.preventDefault(); e.dataTransfer.dropEffect = 'copy'; }}
          onDrop={onDropMethod}
        >
          <div ref={containerRef} style={{ position: 'absolute', inset: 0, overflow: 'hidden' }} />
        </div>
      </Col>

      <Col flex="320px">
        <FlowOptionsCard wfId={wfId} />
        <Card size="small" style={{ marginBottom: 12 }}>
          <Typography.Text type="secondary"
            style={{ fontSize: 11, display: 'block', marginBottom: 6 }}>
            {t('designer.hint.delete')}
          </Typography.Text>
          <Button size="small" danger block {...ro}
            disabled={!selected || ro.disabled} onClick={deleteSelected}>
            {t('designer.action.deleteNode')}
          </Button>
        </Card>
        {!selected ? (
          <Card size="small">
            <Empty description={t('designer.empty.selectNode')} />
          </Card>
        ) : selType === 'bpmn:ExclusiveGateway'
          || selType === 'bpmn:ParallelGateway' ? (
          /* 网关专用卡片。
             为什么单独一张：网关**不需要**绑定设备/方法，而「节点设置」卡挂在
             compile 结果上（compile 只认 task），所以网关选中后右栏只会显示
             「还没保存，先保存画布才能绑定设备与方法」—— 想给网关起个名都做不到。
             而网关恰恰最需要名字：两种网关都是菱形（✕ / ＋），缩小后分不出，
             「分叉」还是「汇合」光看形状也看不出来（引擎按连线数自动判）。 */
          <Card size="small" title={t('designer.gateway.title')}
            extra={<Button size="small" type="primary" loading={saving} {...ro} onClick={save}>
              {t('designer.action.save')}
            </Button>}>
            <Space direction="vertical" size={8} style={{ width: '100%' }}>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {t('designer.gateway.kindLabel')}
              </Typography.Text>
              <Tag color={selType === 'bpmn:ParallelGateway' ? 'blue' : 'default'}>
                {selType === 'bpmn:ParallelGateway'
                  ? t('designer.gateway.parallel')
                  : t('designer.gateway.exclusive')}
              </Tag>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {t('designer.gateway.roleLabel')}
              </Typography.Text>
              <Tag color={gatewayHint.includes('汇合') ? 'orange' : 'green'}>
                {gatewayHint || t('designer.gateway.roleUnknown')}
              </Tag>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {t('designer.field.nodeName')}
              </Typography.Text>
              <Input
                size="small"
                placeholder={t('designer.placeholder.nodeName')}
                value={nodeName}
                onChange={(e) => setNodeName(e.target.value)}
                onBlur={() => writeNodeName(String(selected), nodeName)}
                onPressEnter={() => writeNodeName(String(selected), nodeName)}
              />
              {gatewayHint && (
                <Button size="small" type="link" style={{ padding: 0, alignSelf: 'flex-start' }}
                  onClick={() => writeNodeName(String(selected), gatewayHint)} {...ro}>
                  {t('designer.action.useSuggestedName', { name: gatewayHint })}
                </Button>
              )}
              <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                {t('designer.gateway.hint')}
              </Typography.Text>
            </Space>
          </Card>
        ) : selType === 'bpmn:SequenceFlow' ? (
          <Card size="small" title={t('designer.flow.title')}
            extra={<Button size="small" type="primary" loading={saving} {...ro} onClick={saveFlow}>
              {t('designer.action.save')}
            </Button>}>
            <Space direction="vertical" size={8} style={{ width: '100%' }}>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {t('designer.flow.condition')}
              </Typography.Text>
              <Input.TextArea
                size="small" rows={2}
                placeholder={'${counter} > 3'}
                value={flowCond}
                onChange={(e) => setFlowCond(e.target.value)}
              />
              <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                {t('designer.flow.hint')}
              </Typography.Text>
              <Checkbox
                checked={flowDefault}
                disabled={!flowSrcGateway}
                onChange={(e) => setFlowDefault(e.target.checked)}
              >
                {t('designer.flow.default')}
              </Checkbox>
              {!flowSrcGateway && (
                <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                  {t('designer.flow.notGateway')}
                </Typography.Text>
              )}
            </Space>
          </Card>
        ) : !plan ? (
          <Card size="small">
            <Empty
              description={selected.includes('__s')
                ? t('designer.empty.substep', { key: selected })
                : t('designer.empty.notSavedYet', { key: selected })}
            />
            {/* 新拖出来的节点还没进库 → compile 里没有它 → 原来只显示一句
                「不在编译结果里」，设备/方法下拉彻底不出现。给一条明确的出路。 */}
            {!selected.includes('__s') && (
              <Button
                size="small" type="primary" block loading={saving} {...ro}
                onClick={async () => {
                  await save();
                  qc.invalidateQueries({ queryKey: ['compile', wfId] });
                }}
              >
                {t('designer.action.saveAndBind')}
              </Button>
            )}
          </Card>
        ) : (
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            <NodeSettings
              wfId={String(wfId)} nodeKey={String(selected)} islandId={islandId}
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
