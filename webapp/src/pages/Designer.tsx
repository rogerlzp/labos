import { useCallback, useEffect, useRef, useState } from 'react';
import type { DragEvent as ReactDragEvent } from 'react';
import DeviceImageRenderer, { iconFor, isArm } from '../bpmn/DeviceImageRenderer';
import { workflowPaletteModule } from '../bpmn/WorkflowPalette';
import { GatewayShapeRenderer, GatewayRoleSync, isGatewayType } from '../bpmn/GatewayVisuals';
import { NodeAutoNameLabelRenderer, NodeAutoNameShapeRenderer } from '../bpmn/NodeAutoNameRenderer';
import { gatewayRoleCode, isLocalizableAutoName, isMergeRole, localizedNeutralLabel } from '../bpmn/nodeRoleLabel';
import {
  buildFlowSpec, parseFlowSpec, FLOW_SPEC_EXAMPLE, type FlowSpecDevice,
} from '../bpmn/flowSpec';
import {
  applyOps, describeOp, parseOps, specFromCanvas, type FlowOp,
} from '../bpmn/flowOps';
import { compilePlan, parsePlan } from '../bpmn/flowPlan';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert, Button, Card, Checkbox, Col, Descriptions, Empty, Input, InputNumber, Modal,
  Popconfirm, Row, Select, Space, Spin, Table, Tabs, Tag, Tooltip, Typography, message,
} from 'antd';
import BpmnModeler from 'bpmn-js/lib/Modeler';
import 'bpmn-js/dist/assets/diagram-js.css';
import 'bpmn-js/dist/assets/bpmn-js.css';
import 'bpmn-js/dist/assets/bpmn-font/css/bpmn.css';
import { apiFetch, api, type PreflightIssue } from '../api/client';
import { statusColor, useStatusVocabulary } from '../utils/status';
import { useReadOnly } from '../auth';
import { useI18n, useT } from '../i18n';
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
  /** 自动对齐用：按 delta 平移元素（走 commandStack，页面会标脏）。 */
  moveElements: (elements: unknown[], delta: { x: number; y: number }) => void;
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
  /** 这个节点**属于哪个岛**：搬运的机械臂必须同岛（P-04「LastDropped 保存归属」）。 */
  islandId?: number;
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
 * 变量名选择器：**下拉选已有变量，也能直接输入一个新名字**。
 *
 * <p>为什么是"下拉 + 可输"而不是纯输入框：
 * <ul>
 *   <li>变量名要能当 {@code ${name}} 引用，**手打最容易打错**（大小写、下划线）⇒
 *       下拉给出候选，选中就不会错；</li>
 *   <li>变量有**初始值和类型** ⇒ 下拉里顺带显示，不用切到变量管理页才知道初值；</li>
 *   <li>但流程里**临时用一个新变量**是常态 ⇒ 必须还能直接输入。</li>
 * </ul>
 *
 * <p><b>措辞上刻意区分「初值」和「当前值」</b>：这里显示的是**声明时的初始值**
 * （流程级变量的 default_value），**不是**这条执行此刻的值 ——
 * 写成 {@code "plateCount = 1"} 会让人以为是"现在这个变量等于 1"，
 * 而运行期的真实值在运行详情页的「运行变量」里看。
 */
function VariablePicker({ vars, value, onChange }: {
  vars: Array<{ name: string; type?: string; defaultValue?: string | null }>;
  value: string;
  onChange: (v: string) => void;
}) {
  const t = useT();
  return (
    <Select
      size="small" style={{ width: '100%' }}
      mode="tags" showSearch allowClear maxTagCount={1}
      value={value ? [value] : []}
      placeholder={t('designer.placeholder.varName')}
      onChange={(v: string[]) => onChange(v[v.length - 1] ?? '')}
      options={vars.map((v) => ({
        value: v.name,
        // 明确写「初值」：这是声明的初始值，不是运行期的当前值
        label: (
          <span>
            <strong>{v.name}</strong>
            {v.defaultValue != null && v.defaultValue !== '' ? (
              <span style={{ color: '#94a3b8', marginLeft: 6, fontSize: 12 }}>
                {t('designer.vars.initialValue', { v: v.defaultValue })}
              </span>
            ) : null}
          </span>
        ),
      }))}
      // 输入新名字时按变量的命名规则校验（后端同一套规则）
      filterOption={(input, opt) =>
        String(opt?.label ?? '').toLowerCase().includes(input.toLowerCase())}
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
function NodeSettings({ wfId, nodeKey, islandId, onSaved, onPatched }: {
  wfId: string;
  nodeKey: string;
  /** 只显示这个岛的设备；null = 全部岛（旧流程回落） */
  islandId: number | null;
  onSaved: () => void;
  onPatched?: (patch: {
    method: string;
    params: Record<string, unknown>;
    resourceId: number | null;
  }) => void;
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

  // 这条流程的**变量清单**：变量名改成下拉（可搜、可直接输入新名字）。
  // 为什么要有下拉：变量名是 ${name} 引用，**手打最容易打错**（大小写/下划线），
  // 而变量还有初始值和类型，得在选择时就能看见 —— 否则只能等运行时才发现变量不存在。
  const vars = useQuery({
    queryKey: ['workflow-vars', wfId],
    queryFn: () => apiFetch(`/api/workflows/${wfId}/variables`).then((r) => r.json()) as Promise<{
      items: Array<{ name: string; type?: string; defaultValue?: string | null;
                     description?: string | null }>;
    }>,
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
          // P-10：0 = 显式解绑，缺字段 = 不改。内置节点不是设备节点，
          // 必须显式解绑（否则历史绑定会留在表/图里）；设备节点没选设备也是 0。
          resourceId: isDevice ? (deviceId ?? 0) : 0,
        }),
      });
      const j = await r.json();
      if (!r.ok) {
        message.error(j.error ?? t('designer.error.saveFailed'));
        return;
      }
      onPatched?.({
        method,
        params: params && typeof params === 'object' && !Array.isArray(params)
          ? (params as Record<string, unknown>) : {},
        resourceId: isDevice ? (deviceId ?? null) : null,
      });
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
            <VariablePicker vars={vars.data?.items ?? []}
              value={varName} onChange={setVarName} />
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
            <VariablePicker vars={vars.data?.items ?? []}
              value={varName} onChange={setVarName} />
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
        <MaterialEffectCard wfId={wfId} nodeKey={nodeKey} />
      </Space>
    </Card>
  );
}

/**
 * §3.3b 物料效果声明表单。
 *
 * <p>此前 material_effect 只能直接 UPDATE 列（无 API、无设计器入口），业务上不可用。
 * 现在在节点设置卡里直接配置，保存走 PUT /api/workflows/{id}/nodes/{key}/material-effect，
 * 后端复用 run 启动时同一套契约预检（DeclarationValidator + 字典 + 节点种类）。
 */
function MaterialEffectCard({ wfId, nodeKey }: { wfId: string; nodeKey: string }) {
  const t = useT();
  const ro = useReadOnly();
  const qc = useQueryClient();
  const wfNum = Number(wfId);
  const [mode, setMode] = useState<'none' | 'transition' | 'legacy_unspecified'>('none');
  const [barcode, setBarcode] = useState('');
  const [processState, setProcessState] = useState<string | undefined>(undefined);
  const [sealState, setSealState] = useState<string | undefined>(undefined);
  const [levels, setLevels] = useState<string[]>(['device_ack']);
  const [saving, setSaving] = useState(false);

  const decl = useQuery({
    queryKey: ['material-effect', wfId, nodeKey],
    queryFn: () => apiFetch(
      `/api/workflows/${wfNum}/nodes/${encodeURIComponent(nodeKey)}/material-effect`)
      .then((r) => r.json() as Promise<{
        declaration: import('../api/client').MaterialEffectDecl | null;
      }>),
    retry: false,
  });
  const dict = useQuery({
    queryKey: ['plate-process-states'],
    queryFn: () => api.plateProcessStates(),
    retry: false,
  });

  useEffect(() => {
    const d = decl.data?.declaration;
    if (!d) {
      setMode('none');
      setBarcode('');
      setProcessState(undefined);
      setSealState(undefined);
      setLevels(['device_ack']);
      return;
    }
    setMode(d.mode ?? 'none');
    setBarcode(d.target?.barcode ?? '');
    setProcessState(d.set?.processState);
    setSealState(d.set?.sealState);
    setLevels(d.acceptedEvidenceLevels ?? ['device_ack']);
  }, [decl.data]);

  const save = async () => {
    setSaving(true);
    try {
      const body: import('../api/client').MaterialEffectDecl = { mode: 'none' };
      if (mode === 'transition') {
        body.mode = 'transition';
        body.target = { barcode: barcode.trim() };
        body.set = {
          ...(processState ? { processState } : {}),
          ...(sealState ? { sealState } : {}),
        };
        body.acceptedEvidenceLevels = levels;
      } else {
        body.mode = mode;
      }
      await api.saveMaterialEffect(wfNum, nodeKey, body);
      message.success(t('designer.material.saved'));
      await qc.invalidateQueries({ queryKey: ['material-effect', wfId, nodeKey] });
    } catch (e) {
      message.error(String(e));
    } finally {
      setSaving(false);
    }
  };

  const clear = async () => {
    try {
      await api.clearMaterialEffect(wfNum, nodeKey);
      message.success(t('designer.material.cleared'));
      await qc.invalidateQueries({ queryKey: ['material-effect', wfId, nodeKey] });
    } catch (e) {
      message.error(String(e));
    }
  };

  return (
    <Card size="small" title={t('designer.material.title')}>
      <Space direction="vertical" size={6} style={{ width: '100%' }}>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {t('designer.material.hint')}
        </Typography.Text>
        <Select
          size="small"
          value={mode}
          onChange={(v) => setMode(v)}
          options={[
            { value: 'none', label: 'none' },
            { value: 'transition', label: 'transition' },
            { value: 'legacy_unspecified', label: 'legacy_unspecified' },
          ]}
        />
        {mode === 'transition' ? (
          <>
            <Input
              size="small"
              placeholder={t('designer.material.barcode')}
              value={barcode}
              onChange={(e) => setBarcode(e.target.value)}
            />
            <Select
              size="small"
              allowClear
              placeholder={t('designer.material.processState')}
              value={processState}
              onChange={setProcessState}
              options={(dict.data?.items ?? []).map((x) => ({
                value: x.code, label: x.displayName || x.code,
              }))}
            />
            <Select
              size="small"
              allowClear
              placeholder={t('designer.material.sealState')}
              value={sealState}
              onChange={setSealState}
              options={[
                { value: 'none', label: 'none' },
                { value: 'sealed', label: 'sealed' },
              ]}
            />
            <Select
              size="small"
              mode="multiple"
              placeholder={t('designer.material.evidence')}
              value={levels}
              onChange={setLevels}
              options={[
                { value: 'device_ack', label: 'device_ack' },
                { value: 'physical_confirmed', label: 'physical_confirmed' },
                { value: 'manual_confirmed', label: 'manual_confirmed' },
                { value: 'legacy', label: 'legacy' },
              ]}
            />
          </>
        ) : null}
        <Space size={6}>
          <Button size="small" type="primary" loading={saving}
            disabled={ro.disabled} onClick={save}>
            {t('designer.action.save')}
          </Button>
          <Popconfirm title={t('designer.material.clearConfirm')} onConfirm={clear}>
            <Button size="small" disabled={ro.disabled}>
              {t('designer.material.clear')}
            </Button>
          </Popconfirm>
        </Space>
      </Space>
    </Card>
  );
}

/** 画布上的一个网关及其入边/出边数量。 */
interface GatewayWiring {
  id: string;
  name: string;
  ins: number;
  outs: number;
}

/**
 * 找出「连线不成立」的网关。
 *
 * <p>为什么要在保存前拦：前端 {@code gatewayRoleCode} 是**按入/出边数推角色**的
 * （`ins>1` ⇒ MERGE、`outs>1` ⇒ SPLIT、单进单出 ⇒ 空串）。孤立网关（无入边或无出边）
 * 连角色都没有，在画布上就是一块没意义的图形；从 palette 拖进来忘了连线再保存，
 * 就会在流程里留下这种脏节点（2026-11-12 实测 `/designer/1000789` 出现 4 个）。
 */
function collectGateways(m: BpmnModeler): GatewayWiring[] {
  const reg = m.get('elementRegistry') as unknown as {
    forEach: (fn: (el: {
      id: string;
      type: string;
      /** 连线来源有两个，**两个都要读**：diagram-js 在 element 上维护
          `incoming/outgoing`，bpmn-moddle 在 `businessObject` 上也有同名关联属性；
          实测刚 importXML / 刚拖入时 `businessObject` 侧可能还是空集合，
          只读它会把连好的网关误判成 0 条边。 */
      incoming?: unknown[];
      outgoing?: unknown[];
      businessObject?: { name?: string; incoming?: unknown[]; outgoing?: unknown[] };
    }) => void) => void;
  };
  const GW = new Set([
    'bpmn:ExclusiveGateway', 'bpmn:ParallelGateway', 'bpmn:InclusiveGateway',
  ]);
  const out: GatewayWiring[] = [];
  reg.forEach((el) => {
    if (!GW.has(el.type)) return;
    out.push({
      id: el.id,
      name: el.businessObject?.name ?? '',
      ins: el.incoming?.length ?? el.businessObject?.incoming?.length ?? 0,
      outs: el.outgoing?.length ?? el.businessObject?.outgoing?.length ?? 0,
    });
  });
  return out;
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
 * 流程级变量卡片：**就在设计器里建变量**。
 *
 * <p>为什么必须在这里建（而不是只能去「变量管理台」）：
 * <ul>
 *   <li>变量的作用域是**一条流程** ⇒ 变量管理台是"流程无关"的页面，
 *       在那儿新建必须先在下拉框里选对流程，选错就建到别的流程头上了 ——
 *       现场表现是"我在设计器里建的变量，这儿怎么没有"；</li>
 *   <li>设计器右栏本来就是"这条流程的配置区"（流程选项、节点设置都在这儿），
 *       变量放这儿 ⇒ 建完立刻能在节点属性的下拉里选到。</li>
 * </ul>
 *
 * <p>变量名的规则由后端统一校验（字母数字下划线、不能以数字开头），
 * 因为它要能当 {@code ${name}} 引用。
 */
function WorkflowVarsCard({ wfId }: { wfId: number }) {
  const t = useT();
  const ro = useReadOnly();
  const qc = useQueryClient();
  const nav = useNavigate();
  const [open, setOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [form, setForm] = useState({
    name: '', type: 'number', defaultValue: '', description: '',
  });

  const vars = useQuery({
    queryKey: ['workflow-vars', String(wfId)],
    queryFn: () => apiFetch(`/api/workflows/${wfId}/variables`).then((r) => r.json()) as Promise<{
      items: Array<{ name: string; type?: string; defaultValue?: string | null;
                     description?: string | null }>;
    }>,
  });
  const items = vars.data?.items ?? [];

  /** 建完/删完都要刷新：节点属性的下拉用的是同一个 queryKey。 */
  const reload = () => {
    qc.invalidateQueries({ queryKey: ['workflow-vars'] });
    qc.invalidateQueries({ queryKey: ['vars'] });
  };

  const save = async () => {
    const name = form.name.trim();
    if (!name) {
      message.error(t('variables.nameRequired'));
      return;
    }
    setSaving(true);
    try {
      const r = await apiFetch(`/api/workflows/${wfId}/variables`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name, type: form.type,
          defaultValue: form.defaultValue, description: form.description,
        }),
      });
      if (!r.ok) {
        const j = await r.json().catch(() => ({}));
        message.error(j.error ?? t('variables.saveFailed'));
        return;
      }
      message.success(t('designer.vars.saved'));
      setOpen(false);
      setForm({ name: '', type: 'number', defaultValue: '', description: '' });
      reload();
    } catch (e) {
      message.error(String(e));
    } finally {
      setSaving(false);
    }
  };

  const remove = async (name: string) => {
    const r = await apiFetch(
      `/api/workflows/${wfId}/variables/${encodeURIComponent(name)}`,
      { method: 'DELETE' },
    );
    if (!r.ok) {
      message.error(t('variables.deleteFailed'));
      return;
    }
    message.success(t('variables.deleted'));
    reload();
  };

  return (
    <Card
      size="small" style={{ marginBottom: 12 }}
      title={t('designer.vars.title')}
      extra={
        <Button size="small" type="primary" disabled={ro.disabled}
          onClick={() => setOpen(true)}>
          {t('designer.vars.new')}
        </Button>
      }
    >
      <Typography.Paragraph type="secondary" style={{ fontSize: 11, marginBottom: 6 }}>
        {t('designer.vars.hint')}
      </Typography.Paragraph>
      <Typography.Link
        style={{ fontSize: 11, display: 'inline-block', marginBottom: 6 }}
        onClick={() => nav(`/variables?workflowId=${wfId}`)}
      >
        {t('designer.vars.openManager')}
      </Typography.Link>
      {items.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('designer.vars.empty')} />
      ) : (
        <Space direction="vertical" size={4} style={{ width: '100%' }}>
          {items.map((v) => (
            <div key={v.name}
              style={{
                display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                gap: 6, fontSize: 12,
              }}>
              <span style={{ minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis' }}>
                <strong>{v.name}</strong>
                <Tag style={{ marginLeft: 4 }}>{v.type ?? 'number'}</Tag>
                {v.defaultValue != null && v.defaultValue !== '' ? (
                  <span style={{ color: '#1677ff' }}>
                    {' '}{t('designer.vars.initialValue', { v: v.defaultValue })}
                  </span>
                ) : (
                  <span style={{ color: '#94a3b8' }}> · {t('designer.vars.noDefault')}</span>
                )}
              </span>
              <Popconfirm
                title={t('variables.deleteConfirm')}
                onConfirm={() => remove(v.name)}
                disabled={ro.disabled}
              >
                <Button size="small" type="link" danger disabled={ro.disabled}
                  style={{ paddingInline: 4 }}>
                  {t('variables.delete')}
                </Button>
              </Popconfirm>
            </div>
          ))}
        </Space>
      )}

      <Modal
        open={open} title={t('designer.vars.new')}
        okText={t('variables.save')} cancelText={t('variables.cancel')}
        confirmLoading={saving} onOk={save} onCancel={() => setOpen(false)}
      >
        <Space direction="vertical" size={10} style={{ width: '100%' }}>
          <Input
            placeholder={t('designer.vars.ph.name')} value={form.name}
            onChange={(e) => setForm({ ...form, name: e.target.value })}
          />
          <Select
            style={{ width: '100%' }} value={form.type}
            onChange={(v: string) => setForm({ ...form, type: v })}
            options={[
              { value: 'number', label: 'number' },
              { value: 'string', label: 'string' },
              { value: 'boolean', label: 'boolean' },
            ]}
          />
          <Input
            placeholder={t('designer.vars.ph.default')} value={form.defaultValue}
            onChange={(e) => setForm({ ...form, defaultValue: e.target.value })}
          />
          <Input
            placeholder={t('designer.vars.ph.desc')} value={form.description}
            onChange={(e) => setForm({ ...form, description: e.target.value })}
          />
        </Space>
      </Modal>
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
            <span>{localizedNeutralLabel(n.name) ?? n.name ?? n.nodeKey} <Tag>{n.resourceCode ?? t('designer.estimate.noDevice')}</Tag></span>
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
  // 语种：画布不是 React 管的，切换语种后要手动重画标签（见下面的 locale 副作用）
  const { locale } = useI18n();
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
  const wfId = Number(id);
  const islandsQ = useQuery({
    queryKey: ['designer-islands'],
    queryFn: () => apiFetch('/api/topology').then((r) => r.json()) as Promise<{
      islands?: Array<{ id: number; code: string; name?: string }>;
    }>,
  });
  /**
   * **权威岛**（服务端 `workflow.island_id`，P-01 定的执行边界）。
   *
   * <p>为什么必须有它：岛原先只是**用户手里一个筛选器**（localStorage 里的
   * islandId），于是"给本岛流程绑一台别岛的设备"没人拦 —— 流程的岛与设备的岛
   * 可以各说各话，运行时按流程的岛判定、硬件却在别的岛（P-04）。
   *
   * <p>有权威岛 ⇒ 设备池按它锁死、岛选择器锁定不可清空；
   * 没有（库里的 island_id 为 NULL 的旧流程）⇒ 仍可手动选岛，保持兼容。
   */
  const wfQ = useQuery({
    queryKey: ['workflow', wfId],
    queryFn: () => api.workflow(wfId),
    enabled: Number.isFinite(wfId) && wfId > 0,
  });
  const authIslandId = wfQ.data?.workflow?.islandId ?? null;
  /** 生效的岛：权威岛优先；没有权威岛才用用户选的那个。 */
  const effectiveIslandId = authIslandId ?? islandId;
  // 左侧设备面板：与 NodeSettings 共用 ['device-pool', islandId] 缓存，不额外发请求
  const paletteDevices = useQuery({
    queryKey: ['device-pool', effectiveIslandId],
    queryFn: () => apiFetch(`/api/devices/pool${effectiveIslandId ? `?islandId=${effectiveIslandId}` : ''}`)
      .then((r) => r.json()) as Promise<{ items?: PaletteDevice[] }>,
  });
  const qc = useQueryClient();

  const containerRef = useRef<HTMLDivElement>(null);
  const modelerRef = useRef<BpmnModeler | null>(null);

  // 上一次拖入的节点：判断「板子换设备」→ 自动插入机械臂 Transfer 并连线
  const lastDroppedRef = useRef<LastDropped | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  /** 选中元素的 bpmn 类型：用来区分「节点」与「连线」（连线不在 compile 结果里）。 */
  const [selType, setSelType] = useState('');
  /** 选中网关的显示名（写进 BPMN 的 name 属性） */
  const [nodeName, setNodeName] = useState('');
  /**
   * 选中网关的角色**中性 code**（XOR_MERGE 之类，与语种无关）。
   * 落库存它，显示时再映射成本地化文案 —— 见 bpmn/nodeRoleLabel.ts。
   */
  const [gatewayRole, setGatewayRole] = useState('');
  /** 连线的条件表达式（选中 SequenceFlow 时才有意义） */
  const [flowCond, setFlowCond] = useState('');
  /** 这条连线是否为源网关的默认边（无条件兜底） */
  const [flowDefault, setFlowDefault] = useState(false);
  /** 源节点是不是「网关」：只有网关的 default 属性会被解析器认 */
  const [flowSrcGateway, setFlowSrcGateway] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  /** 保存时检测到的孤立网关；非空即弹窗拦截（见 save()）。 */
  const [looseGateways, setLooseGateways] = useState<GatewayWiring[] | null>(null);
  const [ready, setReady] = useState(false);
  const [paramValues, setParamValues] = useState<Record<string, unknown>>({});
  const [showHistory, setShowHistory] = useState(false);
  /** 执行记录：跑完的单子在别处翻不到，这里按流程查最近 20 次 */
  const [showRuns, setShowRuns] = useState(false);
  /** 画布缩放比例（%）：跟着 viewbox 变化实时更新，工具条上显示。 */
  const [zoomPct, setZoomPct] = useState(100);
  /** 画布是否处于全屏（全屏时状态由 fullscreenchange 事件同步）。 */
  const [isFullscreen, setIsFullscreen] = useState(false);
  /** 导入流程规格的弹窗开关。 */
  const [showImport, setShowImport] = useState(false);
  /** 导入弹窗里的规格文本（AI 生成 / 脚本产出的 JSON）。 */
  const [importText, setImportText] = useState('');
  /** 导入弹窗里的校验错误（逐条列出，别让用户猜哪错了）。 */
  const [importErrors, setImportErrors] = useState<string[]>([]);
  /** AI 修改流程：面板开关。 */
  const [showAi, setShowAi] = useState(false);
  /** 用户提的需求（一句话）。 */
  const [aiInstruction, setAiInstruction] = useState('');
  /** AI 给的修改清单。 */
  const [aiOps, setAiOps] = useState<FlowOp[]>([]);
  /** 已经问过 AI（区分"还没问"与"AI 说没得改"）。 */
  const [aiAnalyzed, setAiAnalyzed] = useState(false);
  /** AI 环节的错误（网络/未配置/格式）。 */
  const [aiErrors, setAiErrors] = useState<string[]>([]);
  /** AI 环节的忙碌态。 */
  const [aiBusy, setAiBusy] = useState(false);
  /** AI 的补充说明（哪部分做不到、为什么）。 */
  const [aiNotes, setAiNotes] = useState('');
  /** AI 给的是「局部改动（ops）」还是「结构改动（顺序图）」。 */
  const [aiMode, setAiMode] = useState<'ops' | 'plan'>('ops');
  /** 全屏的承载元素：canvas 外层 wrapper（bpmn-js 的 SVG 在它里面）。 */
  const canvasWrapRef = useRef<HTMLDivElement>(null);

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
      // 自动名渲染：把库里存的**中性 code**（XOR_MERGE / __expr / ARM_TRANSFER 等）
      // 显示成本地化文案。分两个渲染器是因为 bpmn-js 把名字画在两个位置：
      // 外置标签（网关等，画在图形外面）与 task 图形内部（内置逻辑节点）。
      // 只接 label 的话，内置节点的 code 会直接露在方框里。
      {
        __init__: ['nodeAutoNameLabelRenderer', 'nodeAutoNameShapeRenderer'],
        nodeAutoNameLabelRenderer: ['type', NodeAutoNameLabelRenderer],
        nodeAutoNameShapeRenderer: ['type', NodeAutoNameShapeRenderer],
      },
      // palette：网关改成 6 个成对入口（并行/异或/条件 × 分叉/汇合）+ 移除解析器不认的入口
      workflowPaletteModule,
      // 网关可视化：分叉绿边、汇合橙边（bpmn-js 自己不画 gatewayDirection，
      // 分叉和汇合画出来一模一样）；并把网关名同步成**实际拓扑**算出来的角色。
      {
        __init__: ['gatewayShapeRenderer', 'gatewayRoleSync'],
        gatewayShapeRenderer: ['type', GatewayShapeRenderer],
        gatewayRoleSync: ['type', GatewayRoleSync],
      }],
    } as any);
    modelerRef.current = modeler;
    // 页面级自动化钩子：Playwright 通过 window.__bpmnModeler 驱动同一实例
    // （createShape/connect 与用户拖拽走同一 modeling API，落库带 DI，画布可渲染）。
    (window as unknown as { __bpmnModeler?: unknown }).__bpmnModeler = modeler;
    // 流程规格导入钩子：脚本/AI 产出一份 Flow Spec JSON，直接调它建图并落库。
    // 走的是和手工拖拽同一条通道（importFlowSpec 内部用 modeling API）。
    (window as unknown as { __labosFlowSpec?: unknown }).__labosFlowSpec = {
      import: (raw: string) => importFlowRef.current?.(raw),
    };

    modeler.on('commandStack.changed', () => setDirty(true));
    // 缩放比例：跟着画布视口变化实时更新，工具条上的百分比才准。
    // （滚轮缩放 / 拖拽平移都会触发这个事件，不只是点工具条按钮。）
    modeler.on('canvas.viewbox.changed', () => {
      const cv = modelerRef.current?.get('canvas') as { zoom: () => number } | undefined;
      if (cv) setZoomPct(Math.round(cv.zoom() * 100));
    });
    // 全屏切换：bpmn-js 的 SVG 尺寸是按容器算的，容器变了必须让它重算一次，
    // 否则画面还是旧尺寸（右侧留白 / 拉伸变形）。
    const onFsChange = () => {
      const cv = modelerRef.current?.get('canvas') as { resized: () => void } | undefined;
      cv?.resized();
      setIsFullscreen(!!document.fullscreenElement);
    };
    document.addEventListener('fullscreenchange', onFsChange);
    modeler.on('selection.changed', (e: { newSelection: Array<{ id: string; type: string }> }) => {
      const el = e.newSelection?.[0];
      setSelected(el?.id ?? null);
      setSelType(el?.type ?? '');
      // 网关的显示名与角色建议：选中即读一次（角色由入/出边数推出，与后端同一口径）
      if (el?.id) {
        const r = readNodeName(el.id);
        setNodeName(r.name);
        setGatewayRole(r.code);
      } else {
        setNodeName('');
        setGatewayRole('');
      }
    });
    return () => {
      document.removeEventListener('fullscreenchange', onFsChange);
      modeler.destroy();
      modelerRef.current = null;
    };
  }, []);

  // 语种切换后重画画布上的中性 code 标签。
  //
  // 为什么需要：画布是 bpmn-js 的 SVG，不受 React 重渲染影响；而中性 code
  // （XOR_MERGE 等）的显示文案是按**当前语种**查出来的 ⇒ 切语种后必须重画，
  // 否则画布上是旧语种的文案、右栏面板却是新语种，两边对不上。
  //
  // 只重画命中自动名的元素（中性 code，或 2026-11-10 之前的老数据中文名），
  // 其余交给 bpmn-js 自己的更新路径，
  // 不碰选中态、不进 commandStack（不影响"保存"按钮的脏标记）。
  //
  // ⚠ 要重画**两类**位置：外置标签（网关，名字画在图形外面）与
  // task 图形本身（内置逻辑节点，名字画在方框**里面**）——
  // 只重画标签的话，切语种后内置节点仍显示旧语种（甚至直接露出 code）。
  useEffect(() => {
    const m = modelerRef.current;
    if (!m) return;
    const registry = m.get('elementRegistry') as {
      getAll: () => Array<{
        id: string;
        type?: string;
        businessObject?: { name?: string };
        labelTarget?: { businessObject?: { name?: string } };
      }>;
      getGraphics: (el: unknown) => SVGElement | undefined;
    };
    const graphicsFactory = m.get('graphicsFactory') as {
      update: (type: string, element: unknown, gfx: SVGElement) => void;
    };
    registry.getAll().forEach((el) => {
      // 连线等元素的 businessObject 也在这里，但它们没有自动名 ⇒ 直接跳过
      const bo = el.type === 'label' ? el.labelTarget?.businessObject : el.businessObject;
      if (!isLocalizableAutoName(bo?.name)) return;
      const gfx = registry.getGraphics(el);
      if (gfx) graphicsFactory.update('shape', el, gfx);
    });
  }, [locale]);

  /**
   * 读节点的显示名 + 算网关角色。
   *
   * <p>为什么需要：两种网关在页面上都是菱形（✕ / ＋），缩小后几乎分不出；
   * 而「分叉」还是「汇合」光看形状也看不出来 —— 引擎是按连线数自动判定的
   * （后端 `WorkflowGraphParser`：入边 > 1 是汇合、出边 > 1 是分叉、
   * 都 > 1 按汇合先等再分、单进单出等价于直通）。
   * 给个默认名，图上一眼就知道这个菱形在干什么。
   *
   * <p>推导规则**必须和后端一致**，否则名字会骗人 —— 这里直接复用
   * {@link gatewayRoleCode}（与后端同一口径）。
   *
   * <p>返回的是**中性 code** 而不是文案：落库存 code，显示时再本地化，
   * 免得同一张图因创建时的界面语种不同而存下不同的名字。
   */
  const readNodeName = (key: string): { name: string; code: string } => {
    const m = modelerRef.current;
    if (!m || !key) return { name: '', code: '' };
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
    return {
      name: el?.businessObject?.name ?? '',
      code: gatewayRoleCode(type, ins, outs),
    };
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
    // 输入框是受控组件：不同步的话，点「使用建议名」后画布变了、输入框还是旧值。
    setNodeName(next);
  };

  /**
   * 自动对齐：按拓扑层级把节点排成从左到右的列，列内按原 y 排序、纵距固定。
   *
   * <p>为什么需要：页面上拖出来的图经常是“全堆在一起、线互相压着”，
   * 人眼难读，预检/评审也难看出网格结构。这里不引 dagre/auto-layout 新依赖，
   * 按“最长路径分层 + 列内排序”已经够把几十个节点的图排整齐。
   *
   * <p>只移动 position，不改连线/拓扑；走 modeling.moveElements 会进 commandStack，
   * 页面会标脏，用户点「保存」后 DI 坐标一起落库（saveXML 包含 BPMNDiagram 坐标）。
   */
  /**
   * 画布缩放：**流程节点一多，鼠标滚轮缩放太慢、又看不见全局**。
   *
   * <p>工具条给的是"看得懂"的三档：缩小 / 当前比例 / 放大，再加一个
   * 「适应画布」（把整张图缩进视口）和「全屏」（铺满整个屏幕）。
   */
  const zoomBy = (delta: number) => {
    const m = modelerRef.current;
    if (!m) return;
    const cv = m.get('canvas') as unknown as { zoom: (v?: number | string) => number };
    const cur = cv.zoom();
    // 按 10% 步进并夹在 20%~400%：再小字都看不清，再大也没什么意义
    const next = Math.min(4, Math.max(0.2, Math.round((cur + delta) * 10) / 10));
    cv.zoom(next);
  };

  /** 适应画布：把整张图缩放进视口（节点多时的"看一眼全局"）。 */
  const fitViewport = () => {
    const m = modelerRef.current;
    if (!m) return;
    (m.get('canvas') as unknown as { zoom: (v: string) => void }).zoom('fit-viewport');
  };

  /**
   * 全屏切换。
   *
   * <p>全屏的是**画布容器**（不是整个页面）—— 右侧的节点设置、顶部的保存按钮
   * 仍然要能点到，所以只把画布那一块放大。
   */
  const toggleFullscreen = async () => {
    const el = canvasWrapRef.current;
    if (!el) return;
    try {
      if (document.fullscreenElement) await document.exitFullscreen();
      else await el.requestFullscreen();
    } catch (e) {
      message.error(t('designer.error.saveFailedDetail', { msg: String((e as Error).message) }));
    }
  };

  /**
   * 导入一份**流程规格**（Flow Spec）并落库。
   *
   * <p>这是「AI / 脚本建流程」在页面上的落点：AI 只需要产出业务视角的 JSON
   * （哪些设备、哪些方法、怎么连），画布怎么摆、图标怎么来、网关叫什么，
   * 全部由这一层翻译 —— 与用户手工拖拽走同一条 modeling 通道。
   *
   * <p>落库走**草稿保存**（和拖入节点同一条路）：后端会把 protocolName /
   * resource / methodParams 一起写进 workflow_node。参数没填全不阻塞 ——
   * 那是「保存」按钮的守卫，不该让导入这一步就被卡住。
   */
  const importFlowSpec = async (
    raw: string,
  ): Promise<{ created: number; errors: string[] }> => {
    const m = modelerRef.current;
    const parsed = parseFlowSpec(raw);
    if (!parsed.spec) return { created: 0, errors: parsed.errors };
    if (!m) return { created: 0, errors: [t('designer.spec.err.canvasNotReady')] };

    // 设备目录：设备池 → resourceId → 设备（解析图标/能力，并校验设备确实存在）。
    // 设备池没加载出来时**不传**目录 —— 否则会把"还没加载"误判成"设备不存在"。
    const pool = paletteDevices.data?.items ?? [];
    const devices = pool.length
      ? new Map<number, FlowSpecDevice>(pool.map((d) => [d.resourceId, {
        resourceId: d.resourceId,
        code: d.code,
        capability: d.capability,
        icon: d.icon ?? null,
        islandId: d.islandId,
      }]))
      : undefined;

    const res = buildFlowSpec(m, parsed.spec, { clear: true, devices });
    if (res.created.length === 0) {
      return {
        created: 0,
        errors: res.errors.length ? res.errors : [t('designer.spec.err.nothingBuilt')],
      };
    }

    const { xml } = await m.saveXML({ format: true }) as { xml: string };
    await api.saveGraph(wfId, collapse(xml), { draft: true });
    setDirty(false);
    await Promise.all([
      qc.invalidateQueries({ queryKey: ['compile', wfId] }),
      qc.invalidateQueries({ queryKey: ['preflight', wfId] }),
    ]);
    fitViewport();
    return { created: res.created.length, errors: res.errors };
  };

  /** 自动化钩子用：永远指向最新的 importFlowSpec（避免闭包拿到旧状态）。 */
  const importFlowRef = useRef(importFlowSpec);
  importFlowRef.current = importFlowSpec;

  /**
   * AI 面板用的**设备目录**：设备 + 方法 + **参数定义**。
   *
   * <p>为什么必须带参数定义：AI 改参数时得知道参数名是真的（"sec" 而不是
   * "duration"），否则改完存不进去、运行时报错。
   */
  const aiCatalogQ = useQuery({
    queryKey: ['ai-catalog', effectiveIslandId],
    enabled: showAi,
    queryFn: async () => {
      const pool = paletteDevices.data?.items ?? [];
      const lines = await Promise.all(pool.map(async (d) => {
        const r = await apiFetch(`/api/devices/${d.resourceId}/methods`);
        const j = await r.json() as {
          items?: Array<{
            method: string;
            params?: Array<{ name: string; type?: string; required?: number | boolean }>;
          }>;
        };
        const ms = (j.items ?? []).map((m) => {
          const ps = (m.params ?? [])
            .map((p) => `${p.name}:${p.type ?? 'string'}${p.required ? '(必填)' : ''}`)
            .join(',');
          return ps ? `${m.method}[${ps}]` : m.method;
        });
        return `- ${d.code} | ${d.name ?? ''} | 能力=${d.capability ?? ''}`
          + ` | 方法: ${ms.join(', ') || '（无）'}`;
      }));
      return lines.join('\n');
    },
  });

  /**
   * 节点 id → 人能看懂的名字（预览清单里用；AI 说的是 id，人看不懂）。
   */
  const aiNodeLabel = (id: string): string => {
    const m = modelerRef.current;
    if (!m) return id;
    const reg = m.get('elementRegistry') as {
      get: (i: string) => { businessObject?: { name?: string } } | undefined;
    };
    const el = reg.get(id);
    const name = el?.businessObject?.name;
    return name && name !== id ? `${name}（${id}）` : id;
  };

  /** 「生成修改建议」：把需求 + 当前图 + 设备目录交给后端，后端调 LLM 拿回 ops。 */
  const analyzeAi = async () => {
    const m = modelerRef.current;
    if (!m) return;
    setAiBusy(true);
    setAiErrors([]);
    try {
      const pool = paletteDevices.data?.items ?? [];
      const codeByResource = new Map<string, string>();
      pool.forEach((d) => codeByResource.set(String(d.resourceId), d.code));
      const currentSpec = specFromCanvas(
        m as unknown as { get: (n: string) => unknown },
        (rid) => codeByResource.get(rid),
      );
      const res = await api.aiPlan(wfId, {
        instruction: aiInstruction,
        currentSpec,
        catalog: aiCatalogQ.data ?? '',
      });
      const mode = (res as { mode?: string }).mode === 'plan' ? 'plan' : 'ops';
      setAiMode(mode);
      if (mode === 'plan') {
        // 结构改动：**顺序图 → 编译器 → 操作**。连线由代码做，模型不碰 connect。
        const parsedPlan = parsePlan(res);
        if (!parsedPlan.plan) {
          setAiOps([]);
          setAiErrors(parsedPlan.errors);
          setAiAnalyzed(true);
          return;
        }
        const byCode = new Map<string, number>();
        pool.forEach((d) => byCode.set(d.code, d.resourceId));
        const compiled = compilePlan(parsedPlan.plan, currentSpec, {
          deviceByCode: byCode,
          currentById: new Map(currentSpec.nodes.map((n) => [n.id, n])),
        });
        setAiOps(compiled.ops);
        setAiErrors([...parsedPlan.errors, ...compiled.errors]);
      } else {
        const parsed = parseOps(res);
        setAiOps(parsed.ops);
        setAiErrors(parsed.errors);
      }
      setAiNotes((res as { notes?: string }).notes ?? '');
      setAiAnalyzed(true);
    } catch (e) {
      const status = (e as { status?: number }).status;
      setAiErrors([status === 503
        ? t('designer.ai.notConfigured')
        : `${t('designer.ai.analyzeFailed')}：${(e as Error).message}`]);
      setAiAnalyzed(true);
    } finally {
      setAiBusy(false);
    }
  };

  /** 「应用到画布」：逐条应用 ops → 落库 → 刷新 compile/preflight。 */
  const applyAiOps = async () => {
    const m = modelerRef.current;
    if (!m || aiOps.length === 0) return;
    setAiBusy(true);
    try {
      const pool = paletteDevices.data?.items ?? [];
      const devices = new Map<number, {
        resourceId: number; code: string; capability?: string; icon?: string | null;
      }>();
      const byCode = new Map<string, number>();
      pool.forEach((d) => {
        devices.set(d.resourceId, {
          resourceId: d.resourceId, code: d.code,
          capability: d.capability, icon: d.icon ?? null,
        });
        byCode.set(d.code, d.resourceId);
      });
      // 新节点的落点：放在最后一个节点右边（交给自动布局再排）
      const ids = (m.get('elementRegistry') as { getAll: () => Array<{ id: string; x?: number; y?: number }> })
        .getAll().filter((el) => el.x != null);
      const last = ids[ids.length - 1];
      const res = applyOps(
        m as unknown as { get: (n: string) => unknown },
        aiOps,
        { devices, deviceByCode: byCode, layout: () => ({ x: (last?.x ?? 200) + 220, y: last?.y ?? 200 }) },
      );
      if (res.applied > 0) {
        const { xml } = await m.saveXML({ format: true }) as { xml: string };
        await api.saveGraph(wfId, collapse(xml), { draft: true });
        setDirty(false);
        // 落库打点：与页面「保存」同一个标记 ⇒ 自动化靠它判断"真的存上了"，
        // 不用固定 sleep（sleep 会读到"操作应用了一半"的画布）。
        if (containerRef.current) {
          containerRef.current.dataset.graphSaved = String(Date.now());
        }
        await Promise.all([
          qc.invalidateQueries({ queryKey: ['compile', wfId] }),
          qc.invalidateQueries({ queryKey: ['preflight', wfId] }),
        ]);
      }
      // 结构重排（顺序图模式）后把图重新排一遍：结构都变了，坐标也该跟着走，
      // 否则新节点会堆在旧坐标上、连线乱成一团。
      if (aiMode === 'plan') autoAlign();
      if (res.errors.length) {
        setAiErrors(res.errors);
        message.warning(t('designer.ai.appliedPartial', { n: res.applied, m: res.errors.length }));
      } else {
        message.success(t('designer.ai.applied', { n: res.applied }));
        setShowAi(false);
        setAiOps([]);
        setAiInstruction('');
        setAiAnalyzed(false);
      }
    } catch (e) {
      message.error(t('designer.error.saveFailedDetail', { msg: String((e as Error).message) }));
    } finally {
      setAiBusy(false);
    }
  };

  /** 导入弹窗的「导入并保存」：校验 → 建图 → 落库。 */
  const doImport = async () => {
    setSaving(true);
    try {
      const res = await importFlowSpec(importText);
      if (res.errors.length > 0) {
        // 弹窗留着，把"哪里没建出来"逐条列出来 —— 只给一句"失败"没人能修
        setImportErrors(res.errors);
        if (res.created === 0) {
          message.error(t('designer.import.invalid'));
        } else {
          message.warning(t('designer.toast.importPartial', { n: res.errors.length }));
        }
        return;
      }
      message.success(t('designer.toast.imported', { n: res.created }));
      setShowImport(false);
      setImportText('');
      setImportErrors([]);
    } catch (e) {
      message.error(t('designer.error.saveFailedDetail', { msg: String((e as Error).message) }));
    } finally {
      setSaving(false);
    }
  };

  /**
   * 自动对齐：先对齐「大节点」——网关分叉/汇合对，再排分支内部节点。
   *
   * 旧实现只按最长路径分层、每层按 y 往下堆：分叉和汇合不在同一水平线上，
   * 分支里的节点也散落在不同层，看起来不合理（2026-11-08 用户指出）。
   *
   * 新实现按流程结构递归排版：
   *   ① 从 start 沿主路径走，遇到网关对（分叉 → 对应汇合）时，把分叉和汇合
   *      放在**同一水平行**上，分支节点排在这一对之间；
   *   ② 分支按顺序上下排开，每个分支内部沿它自己的中心行从左到右；
   *   ③ 分支里再遇到网关对（嵌套），递归套用同样的规则；
   *   ④ 不在任何路径上的孤立节点统一放到最下面一排，避免互相叠住。
   */
  const autoAlign = () => {
    const m = modelerRef.current;
    if (!m) return;
    type ShapeEl = {
      id: string;
      type: string;
      x?: number;
      y?: number;
      width?: number;
      height?: number;
      businessObject?: { incoming?: unknown[]; outgoing?: unknown[] };
    };
    type FlowEl = {
      id: string;
      type: string;
      businessObject?: { sourceRef?: { id?: string }; targetRef?: { id?: string } };
    };
    const reg = m.get('elementRegistry') as unknown as {
      forEach: (fn: (el: ShapeEl | FlowEl) => void) => void;
    };
    const modeling = m.get('modeling') as BpmnModelingApi | undefined;
    if (!modeling?.moveElements) return;

    const SHAPES = new Set([
      'bpmn:StartEvent', 'bpmn:EndEvent', 'bpmn:Task', 'bpmn:UserTask',
      'bpmn:ServiceTask', 'bpmn:ScriptTask', 'bpmn:SendTask', 'bpmn:ReceiveTask',
      'bpmn:ExclusiveGateway', 'bpmn:ParallelGateway', 'bpmn:InclusiveGateway', 'bpmn:SubProcess',
      'bpmn:IntermediateThrowEvent', 'bpmn:IntermediateCatchEvent', 'bpmn:CallActivity',
    ]);
    const shapes: ShapeEl[] = [];
    const flows: FlowEl[] = [];
    const flowEls: unknown[] = [];
    reg.forEach((el) => {
      if (SHAPES.has(el.type) && 'x' in el && (el as ShapeEl).x != null) {
        shapes.push(el as ShapeEl);
      } else if (el.type === 'bpmn:SequenceFlow') {
        flows.push(el as FlowEl);
        flowEls.push(el);
      }
    });
    if (shapes.length === 0) return;

    const byId = new Map(shapes.map((s) => [s.id, s]));
    const out = new Map<string, string[]>();
    const inc = new Map<string, string[]>();
    shapes.forEach((s) => { out.set(s.id, []); inc.set(s.id, []); });
    flows.forEach((f) => {
      const src = f.businessObject?.sourceRef?.id;
      const tgt = f.businessObject?.targetRef?.id;
      if (src && tgt && byId.has(src) && byId.has(tgt)) {
        out.get(src)!.push(tgt);
        inc.get(tgt)!.push(src);
      }
    });

    const size = new Map<string, { w: number; h: number }>();
    shapes.forEach((s) => size.set(s.id, {
      w: s.width ?? 100,
      h: s.height ?? (s.type === 'bpmn:StartEvent' || s.type === 'bpmn:EndEvent' ? 36
        : s.type.includes('Gateway') ? 50 : 80),
    }));
    const nodeH = (id: string) => Math.max(size.get(id)?.h ?? 80, 50);
    const isGatewayType = (t: string) => t.includes('Gateway');
    const isParallelType = (t: string) => t.includes('Parallel');
    const isConditionalType = (t: string) => t.includes('Inclusive');
    // 网关种类决定「分叉配哪个汇合」：并行配并行、异或配异或、条件配条件。
    // 三种混配就是画错了（引擎也是按类型分别判语义的）。
    const kindOf = (id: string) => {
      const t = byId.get(id)?.type ?? '';
      if (t === 'bpmn:EndEvent') return 'end';
      if (t === 'bpmn:StartEvent') return 'start';
      if (isGatewayType(t)) {
        if (isParallelType(t)) return 'par';
        if (isConditionalType(t)) return 'cond';
        return 'xor';
      }
      return 'task';
    };

    // 最长路径 level：用于给 fork 选“最近的”同类型汇合（嵌套时选内层 join）
    const level = new Map<string, number>();
    const starts = shapes.filter((s) => s.type === 'bpmn:StartEvent');
    const q: string[] = [];
    (starts.length ? starts : shapes.filter((s) => (s.businessObject?.incoming?.length ?? 0) === 0))
      .forEach((s) => { if (!level.has(s.id)) { level.set(s.id, 0); q.push(s.id); } });
    let guardLv = 0;
    while (q.length && guardLv++ < 5000) {
      const cur = q.shift()!;
      const lv = level.get(cur) ?? 0;
      for (const nx of out.get(cur) ?? []) {
        if (!level.has(nx) || (level.get(nx) ?? 0) < lv + 1) {
          level.set(nx, lv + 1);
          q.push(nx);
        }
      }
    }
    shapes.forEach((s) => { if (!level.has(s.id)) level.set(s.id, 0); });

    // 可达集（小图 DFS；5000 步防环）
    const reachCache = new Map<string, Set<string>>();
    const reachOf = (id: string): Set<string> => {
      const hit = reachCache.get(id);
      if (hit) return hit;
      const seen = new Set<string>();
      const stack = [...(out.get(id) ?? [])];
      let g = 0;
      while (stack.length && g++ < 5000) {
        const cur = stack.pop()!;
        if (seen.has(cur)) continue;
        seen.add(cur);
        stack.push(...(out.get(cur) ?? []));
      }
      reachCache.set(id, seen);
      return seen;
    };

    const forkIds: string[] = [];
    const joinIds: string[] = [];
    shapes.forEach((s) => {
      if (!isGatewayType(s.type)) return;
      if ((out.get(s.id)?.length ?? 0) > 1) forkIds.push(s.id);
      if ((inc.get(s.id)?.length ?? 0) > 1) joinIds.push(s.id);
    });
    const forkToJoin = new Map<string, string>();
    const joinToFork = new Map<string, string>();
    forkIds.sort((a, b) => (level.get(a) ?? 0) - (level.get(b) ?? 0));
    for (const f of forkIds) {
      const fk = kindOf(f);
      const succ = out.get(f) ?? [];
      if (succ.length === 0) continue;
      const cand = joinIds
        .filter((j) => j !== f && kindOf(j) === fk
          && reachOf(f).has(j) && succ.every((s) => reachOf(s).has(j)))
        .sort((a, b) => (level.get(a) ?? 0) - (level.get(b) ?? 0));
      let chosen: string | undefined = cand[0];
      if (!chosen) {
        // 异或分叉收口到同一个 end（合法图的常见写法）：end 也当排版汇合点
        chosen = shapes
          .filter((s) => s.type === 'bpmn:EndEvent')
          .map((s) => s.id)
          .find((e) => succ.every((s) => reachOf(s).has(e)));
      }
      if (chosen) {
        forkToJoin.set(f, chosen);
        joinToFork.set(chosen, f);
      }
    }

    const COL_GAP = 200;
    const ROW_GAP = 150;
    const MARGIN_X = 200;
    const MARGIN_Y = 200;
    const MIN_BAND = 110;

    // 分支/主路径要占多少列（含嵌套网关对）
    const seqWidthMemo = new Map<string, number>();
    function seqWidth(startId: string, stopId: string): number {
      const key = startId + '>' + stopId;
      const memo = seqWidthMemo.get(key);
      if (memo != null) return memo;
      seqWidthMemo.set(key, 0);
      let cols = 0;
      let n: string | undefined = startId;
      const seen = new Set<string>();
      let g = 0;
      while (n && n !== stopId && g++ < 200 && !seen.has(n)) {
        seen.add(n);
        const pj = forkToJoin.get(n);
        if (pj && pj !== stopId) {
          cols += pairWidth(n, pj);
          n = pj;
        } else {
          cols += 1;
          n = (out.get(n) ?? [])[0];
        }
      }
      seqWidthMemo.set(key, cols);
      return cols;
    }
    function pairWidth(f: string, j: string): number {
      const branches = out.get(f) ?? [];
      const maxBranch = Math.max(0, ...branches.map((s) => seqWidth(s, j)));
      return 1 + maxBranch + 1;
    }

    // 分支/主路径要占多少高度（按嵌套网关对的最外框算）
    const seqHeightMemo = new Map<string, number>();
    function seqHeight(startId: string, stopId: string): number {
      const key = startId + '>' + stopId;
      const memo = seqHeightMemo.get(key);
      if (memo != null) return memo;
      seqHeightMemo.set(key, nodeH(startId));
      let h = nodeH(startId);
      let n: string | undefined = startId;
      const seen = new Set<string>();
      let g = 0;
      while (n && n !== stopId && g++ < 200 && !seen.has(n)) {
        seen.add(n);
        const pj = forkToJoin.get(n);
        if (pj && pj !== stopId) {
          h = Math.max(h, pairHeight(n, pj));
          n = pj;
        } else {
          h = Math.max(h, nodeH(n));
          n = (out.get(n) ?? [])[0];
        }
      }
      seqHeightMemo.set(key, h);
      return h;
    }
    function pairHeight(f: string, j: string): number {
      const branches = out.get(f) ?? [];
      const hs = branches.map((s) => Math.max(seqHeight(s, j), MIN_BAND));
      const total = hs.reduce((a, b) => a + b, 0) + ROW_GAP * Math.max(0, branches.length - 1);
      return Math.max(total, MIN_BAND);
    }

    const placed = new Set<string>();
    const target = new Map<string, { x: number; y: number }>();
    /** 每个节点占的**列号**（换行那一步要用，不能从坐标反推）。 */
    const colOf = new Map<string, number>();
    const placeNode = (id: string, col: number, centerY: number) => {
      if (placed.has(id)) return;
      placed.add(id);
      colOf.set(id, col);
      target.set(id, { x: MARGIN_X + col * COL_GAP, y: MARGIN_Y + centerY });
    };

    function placeSeq(startId: string, stopId: string, col: number, rowCenter: number): number {
      let n: string | undefined = startId;
      let c = col;
      const seen = new Set<string>();
      let g = 0;
      while (n && n !== stopId && g++ < 200 && !seen.has(n)) {
        seen.add(n);
        const pj = forkToJoin.get(n);
        if (pj && pj !== stopId) {
          c = placePair(n, pj, c, rowCenter);
          n = pj;
          continue;
        }
        if (!placed.has(n)) {
          placeNode(n, c, rowCenter);
          c += 1;
        }
        n = (out.get(n) ?? [])[0];
      }
      return c;
    }

    function placePair(f: string, j: string, col: number, centerY: number): number {
      placeNode(f, col, centerY);
      const branches = out.get(f) ?? [];
      const widths = branches.map((s) => seqWidth(s, j));
      const maxW = Math.max(0, ...widths);
      const joinCol = col + 1 + maxW;
      placeNode(j, joinCol, centerY);
      const hs = branches.map((s) => Math.max(seqHeight(s, j), MIN_BAND));
      // 分支整体在 fork/join 之间垂直居中；每个分支内部再在自己的列宽里居中
      // （短分支不贴左，和长分支一样关于这一对的中心线对称）。
      const total = hs.reduce((a, b) => a + b, 0) + ROW_GAP * Math.max(0, branches.length - 1);
      let top = centerY - total / 2;
      branches.forEach((s, i) => {
        const bandCenter = top + hs[i] / 2;
        // 分支**左对齐**：所有分支都从分叉点右边同一列出发 ⇒ 分叉的出边等长、
        // 整齐；短分支后面留空（原来是把短分支居中，两头都不对齐，更难看）。
        // 分支长度一样时（常见情况）首尾自然都对齐。
        placeSeq(s, j, col + 1, bandCenter);
        top += hs[i] + ROW_GAP;
      });
      return joinCol + 1;
    }

    const startIds = (starts.length ? starts : shapes.filter(
      (s) => (s.businessObject?.incoming?.length ?? 0) === 0)).map((s) => s.id);
    const endIds = shapes.filter((s) => s.type === 'bpmn:EndEvent').map((s) => s.id);
    const endId = endIds[0] ?? '';
    let cursor = 0;
    for (const sid of startIds) {
      // 顶层 stopId 必须传 ''：fork→EndEvent 的网关对（异或分支收口到同一个
      // 结束节点，合法图最常见写法）也要展开成分支排版。传 endId 会把 forkToJoin
      // 的汇合点挡在 placeSeq 门外 —— 分支摊不开，走第一条出边的节点挤在主行、
      // 其余分支全部掉进「孤立节点」排到最下面（2026-11-11 用户在设计器 1000499 指出）。
      cursor = placeSeq(sid, '', cursor, 0);
      if (startIds.length > 1) cursor += 1;
    }
    if (endId && !placed.has(endId)) placeNode(endId, cursor, 0);

    // =====================================================================
    // 换行：一整条排成一行看不下去 ⇒ 按列数折行，串行接续到下一行。
    //
    // 口径（用户 2026-11-13 提的）：
    //   ① 先按节点顺序一个接一个摆 —— 串行的（现有行为）；
    //   ② 遇到并行网关，分支并列、**前后端对齐**（现有行为）；
    //   ③ 整条太长 ⇒ 折行，从下一行继续往后排（**本次新增**）。
    //
    // 实现：上面算出来的是一条**线性列序**（列号单调递增）。这里把它切成
    // 若干**原子块**——「网关对 + 它的分支」算一块（**不许拆**：
    // 拆了分支的前后端就对不齐了），其余节点各自成块；
    // 然后按块打包进各行，最后把列号换算成坐标。
    // =====================================================================
    /** 一行最多放多少列（列距 200px ⇒ 一行约 2000px 宽）。 */
    const MAX_COLS_PER_ROW = 10;
    /** 行与行之间的间距。 */
    const ROW_GAP_Y = 150;

    type Block = {
      start: number; end: number;
      ids: string[];
      /** 这块的"主车道" y（网关对 = 分叉节点的 y；单节点 = 自己的 y）。 */
      mainY: number;
      /** 相对主车道的上下范围。 */
      relTop: number; relBot: number;
      /** 打包结果：落在第几行、第几列。 */
      rowIdx: number; newCol: number;
    };

    // ---- ① 切原子块 ----
    const blocks: Block[] = [];
    const taken = new Set<string>();
    const pairs = [...forkToJoin.entries()]
      .map(([f, j]) => ({
        f, j,
        c0: colOf.get(f) ?? 0,
        c1: colOf.get(j) ?? 0,
      }))
      // 外层网关对先处理（起点相同则跨度大的先来），内层的会被外层吸收
      .sort((a, b) => (a.c0 - b.c0) || (b.c1 - a.c1));
    for (const p of pairs) {
      if (taken.has(p.f) || taken.has(p.j)) continue;
      const ids = shapes
        .filter((s) => {
          const c = colOf.get(s.id) ?? 0;
          return c >= p.c0 && c <= p.c1;
        })
        .map((s) => s.id);
      ids.forEach((id) => taken.add(id));
      blocks.push({
        start: p.c0, end: p.c1, ids,
        mainY: target.get(p.f)?.y ?? MARGIN_Y,
        relTop: 0, relBot: 0, rowIdx: 0, newCol: 0,
      });
    }
    shapes.forEach((s) => {
      if (taken.has(s.id)) return;
      const c = colOf.get(s.id) ?? 0;
      blocks.push({
        start: c, end: c, ids: [s.id],
        mainY: target.get(s.id)?.y ?? MARGIN_Y,
        relTop: 0, relBot: 0, rowIdx: 0, newCol: 0,
      });
    });
    blocks.sort((a, b) => a.start - b.start);

    // ---- ② 每块的上下范围（相对主车道） ----
    blocks.forEach((b) => {
      b.ids.forEach((id) => {
        const p = target.get(id);
        if (!p) return;
        const dy = p.y - b.mainY;
        if (dy < b.relTop) b.relTop = dy;
        if (dy > b.relBot) b.relBot = dy;
      });
    });

    // ---- ③ 打包进各行 ----
    type Row = { blocks: Block[]; above: number; below: number; mainY: number };
    const rows: Row[] = [];
    let curRow: Row = { blocks: [], above: 0, below: 0, mainY: MARGIN_Y };
    let rowCursor = 0;
    blocks.forEach((b) => {
      const w = b.end - b.start + 1;
      // 放不下就换行（已经有的内容才换；单块超宽就让它独占一行）
      if (curRow.blocks.length > 0 && rowCursor + w > MAX_COLS_PER_ROW) {
        rows.push(curRow);
        curRow = { blocks: [], above: 0, below: 0, mainY: MARGIN_Y };
        rowCursor = 0;
      }
      b.rowIdx = rows.length;
      b.newCol = rowCursor;
      curRow.blocks.push(b);
      rowCursor += w;
    });
    rows.push(curRow);

    // ---- ④ 每行的高度与主车道 y ----
    rows.forEach((r) => {
      r.above = Math.max(0, ...r.blocks.map((b) => -b.relTop));
      r.below = Math.max(0, ...r.blocks.map((b) => b.relBot));
    });
    rows.forEach((r, i) => {
      if (i === 0) {
        r.mainY = MARGIN_Y;
        return;
      }
      const prev = rows[i - 1];
      // 上一行的下沿 + 行距 + 本行上探的高度 ⇒ 本行主车道的 y
      r.mainY = prev.mainY + prev.below + ROW_GAP_Y + r.above;
    });

    // ---- ⑤ 列号换算成坐标 ----
    const blockOf = new Map<string, Block>();
    blocks.forEach((b) => b.ids.forEach((id) => blockOf.set(id, b)));
    target.forEach((p, id) => {
      const b = blockOf.get(id);
      if (!b) return;
      const newCol = b.newCol + ((colOf.get(id) ?? 0) - b.start);
      p.x = MARGIN_X + newCol * COL_GAP;
      p.y = rows[b.rowIdx].mainY + (p.y - b.mainY);
    });

    // 孤立/没走到的节点：单独一排放在最下面（不参与主链排版）
    let maxY = 0;
    target.forEach((p) => { maxY = Math.max(maxY, p.y); });
    let extraCol = 0;
    shapes.forEach((s) => {
      if (!placed.has(s.id)) {
        placeNode(s.id, extraCol++, maxY + 200);
      }
    });

    // 分支上下展开后可能超过上边界：整体平移到留白以内
    let minY = Infinity;
    target.forEach((p) => { minY = Math.min(minY, p.y); });
    if (minY < 150) {
      const shift = 150 - minY;
      target.forEach((p) => { p.y += shift; });
    }

    // 落到画布：moveElements 用差分；批量相同位移可一次移
    const moves: { el: ShapeEl; dx: number; dy: number }[] = [];
    target.forEach((p, id) => {
      const s = byId.get(id);
      if (!s) return;
      const sz = size.get(id) ?? { w: 100, h: 80 };
      const curX = (s.x ?? 0) + sz.w / 2;
      const curY = (s.y ?? 0) + sz.h / 2;
      const dx = Math.round(p.x - curX);
      const dy = Math.round(p.y - curY);
      if (Math.abs(dx) > 1 || Math.abs(dy) > 1) moves.push({ el: s, dx, dy });
    });
    const groups = new Map<string, { dx: number; dy: number; els: ShapeEl[] }>();
    moves.forEach((mv) => {
      const key = mv.dx + ',' + mv.dy;
      if (!groups.has(key)) groups.set(key, { dx: mv.dx, dy: mv.dy, els: [] });
      groups.get(key)!.els.push(mv.el);
    });
    groups.forEach((g) => { modeling.moveElements(g.els, { x: g.dx, y: g.dy }); });
    // ---- 显式锚点：一个节点的入点/出点分开，多个出点各占一个点 ----
    // bpmn-js 默认所有出边都从同一个中点出去（分叉网关两条线完全重合），
    // 入边也挤同一个点，小图还能看、网关一多就全是叠线（用户 2026-11-08 指出）。
    // 规则：
    //   · 分支在节点上方 → 从**上边**出去；下方 → 从**下边**出去；
    //   · 同高度的目标 → 从**右边**出去，多条按 y 均匀分布（每条一个点）；
    //   · 入边对称：上方来从上边进、下方来从下边进、同高度从左边进，多条均匀分布；
    //   · 节点自身的入点和出点天然分在不同边，不会重合。
    type NodeBox = { cx: number; cy: number; w: number; h: number };
    const box = new Map<string, NodeBox>();
    target.forEach((p, id) => {
      const sz = size.get(id) ?? { w: 100, h: 80 };
      box.set(id, { cx: p.x, cy: p.y, w: sz.w, h: sz.h });
    });
    type EdgeRef = { conn: unknown; src: string; tgt: string };
    const edgeRefs: EdgeRef[] = [];
    flowEls.forEach((c) => {
      const bo = (c as {
        businessObject?: { sourceRef?: { id?: string }; targetRef?: { id?: string } };
      }).businessObject;
      const src = bo?.sourceRef?.id;
      const tgt = bo?.targetRef?.id;
      if (src && tgt && box.has(src) && box.has(tgt)) edgeRefs.push({ conn: c, src, tgt });
    });
    const outRefs = new Map<string, EdgeRef[]>();
    const inRefs = new Map<string, EdgeRef[]>();
    box.forEach((_b, id) => { outRefs.set(id, []); inRefs.set(id, []); });
    edgeRefs.forEach((e) => { outRefs.get(e.src)!.push(e); inRefs.get(e.tgt)!.push(e); });

    type Side = 'left' | 'right';
    type Anchor = { x: number; y: number; side: Side };
    const outAnchor = new Map<unknown, Anchor>();
    const inAnchor = new Map<unknown, Anchor>();

    // 出点：统一从右边出去；多条出边在右边缘按目标 y 均匀分布（每条一个点）。
    // 入点：统一从左边进来；多条入边在左边缘按来源 y 均匀分布。
    // 节点自身的入点/出点分在左右两边，天然不会重合。
    outRefs.forEach((list, id) => {
      const b = box.get(id)!;
      const groupR: EdgeRef[] = [];
      const groupL: EdgeRef[] = [];
      list.forEach((e) => {
        (box.get(e.tgt)!.cx >= b.cx ? groupR : groupL).push(e);
      });
      groupR.sort((a, c) => box.get(a.tgt)!.cy - box.get(c.tgt)!.cy);
      groupL.sort((a, c) => box.get(a.tgt)!.cy - box.get(c.tgt)!.cy);
      const groups: { side: Side; arr: EdgeRef[] }[] = [
        { side: 'right', arr: groupR },
        { side: 'left', arr: groupL },
      ];
      groups.forEach(({ side, arr }) => {
        const n = arr.length;
        arr.forEach((e, i) => {
          const y = b.cy - b.h / 2 + (b.h * (i + 1)) / (n + 1);
          const x = side === 'right' ? b.cx + b.w / 2 : b.cx - b.w / 2;
          outAnchor.set(e.conn, { x, y, side });
        });
      });
    });
    inRefs.forEach((list, id) => {
      const b = box.get(id)!;
      const groupL: EdgeRef[] = [];
      const groupR: EdgeRef[] = [];
      list.forEach((e) => {
        (box.get(e.src)!.cx <= b.cx ? groupL : groupR).push(e);
      });
      groupL.sort((a, c) => box.get(a.src)!.cy - box.get(c.src)!.cy);
      groupR.sort((a, c) => box.get(a.src)!.cy - box.get(c.src)!.cy);
      const groups: { side: Side; arr: EdgeRef[] }[] = [
        { side: 'left', arr: groupL },
        { side: 'right', arr: groupR },
      ];
      groups.forEach(({ side, arr }) => {
        const n = arr.length;
        arr.forEach((e, i) => {
          const y = b.cy - b.h / 2 + (b.h * (i + 1)) / (n + 1);
          const x = side === 'left' ? b.cx - b.w / 2 : b.cx + b.w / 2;
          inAnchor.set(e.conn, { x, y, side });
        });
      });
    });

    const round = (v: number) => Math.round(v);
    const routePoints = (a: Anchor, z: Anchor): { x: number; y: number }[] => {
      const pts: { x: number; y: number }[] = [{ x: round(a.x), y: round(a.y) }];
      if (Math.abs(a.y - z.y) > 2) {
        const midX = (a.x + z.x) / 2;
        pts.push({ x: round(midX), y: round(a.y) });
        pts.push({ x: round(midX), y: round(z.y) });
      }
      pts.push({ x: round(z.x), y: round(z.y) });
      return pts;
    };

    // ---- 换行边：从上一行末尾回到下一行开头 ----
    // 默认走法是"从出点水平走到中点再垂直"，换行边会**横穿整张图**（从行尾
    // 一路拉回行首），很难看 ⇒ 这类边单独走**行间通道**：
    // 出点往右让一让 → 垂直下到两行之间的空档 → 水平回到目标左侧 → 进入目标。
    const rowOf = new Map<string, number>();
    blocks.forEach((b) => b.ids.forEach((id) => rowOf.set(id, b.rowIdx)));
    const rowTop = rows.map((r) => r.mainY - r.above);
    const rowBot = rows.map((r) => r.mainY + r.below);
    const isWrapEdge = (e: EdgeRef) =>
      (rowOf.get(e.tgt) ?? 0) > (rowOf.get(e.src) ?? 0);

    // 换行边的锚点：出点强制在**右**、入点强制在**左**
    // （默认按"目标在左/右"分组，换行边目标在左 ⇒ 出点会跑到源的左边，
    //   那条线就得横穿整行，比原来还糟）。
    edgeRefs.forEach((e) => {
      if (!isWrapEdge(e)) return;
      const sb = box.get(e.src)!;
      const tb = box.get(e.tgt)!;
      const a = outAnchor.get(e.conn);
      const z = inAnchor.get(e.conn);
      if (a) outAnchor.set(e.conn, { x: sb.cx + sb.w / 2, y: a.y, side: 'right' });
      if (z) inAnchor.set(e.conn, { x: tb.cx - tb.w / 2, y: z.y, side: 'left' });
    });

    const wrapRoute = (e: EdgeRef, a: Anchor, z: Anchor): { x: number; y: number }[] => {
      const rSrc = rowOf.get(e.src) ?? 0;
      const rTgt = rowOf.get(e.tgt) ?? 0;
      const channelY = (rowBot[rSrc] + rowTop[rTgt]) / 2;
      const outX = a.x + 40;    // 先往右让一让，别贴着源节点
      const inX = z.x - 40;     // 到目标左边再进
      return [
        { x: round(a.x), y: round(a.y) },
        { x: round(outX), y: round(a.y) },
        { x: round(outX), y: round(channelY) },
        { x: round(inX), y: round(channelY) },
        { x: round(inX), y: round(z.y) },
        { x: round(z.x), y: round(z.y) },
      ];
    };

    const updateWp = (modeling as unknown as {
      updateWaypoints?: (conn: unknown, wps: { x: number; y: number }[]) => void;
    }).updateWaypoints;
    if (updateWp) {
      edgeRefs.forEach((e) => {
        const a = outAnchor.get(e.conn);
        const z = inAnchor.get(e.conn);
        if (!a || !z) return;
        updateWp.call(modeling, e.conn,
          isWrapEdge(e) ? wrapRoute(e, a, z) : routePoints(a, z));
      });
    }
    const cv = m.get('canvas') as unknown as {
      zoom: (v: string) => void;
      viewbox: (v?: { x: number; y: number; width: number; height: number }) =>
        { x: number; y: number; width: number; height: number };
    };
    cv.zoom('fit-viewport');
    // 左侧 palette 是覆盖在画布上的：把视口往左上挪一点，内容整体右下移，
    // 避免最左边的开始节点/第一列被 palette 盖住。
    try {
      const vb = cv.viewbox();
      // 内容整体右移 150（避开左侧 palette），同时把视口放大 300/120，
      // 保证右边/下边也留出边距，不会把结束节点裁掉。
      cv.viewbox({
        x: vb.x - 150,
        y: vb.y - 60,
        width: vb.width + 300,
        height: vb.height + 120,
      });
    } catch { /* 视口 API 不可用时只保留 fit-viewport */ }
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
        if (containerRef.current) {
          containerRef.current.dataset.modelImported = String(Date.now());
        }
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
      || src.$type === 'bpmn:ParallelGateway' || src.$type === 'bpmn:InclusiveGateway'));
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
      // 这里必须按草稿落库：拖入/删节点后的中间态常常还没连到 start/end，
      // 若走完整图校验，前端画布看起来成功、XML/compile/workflow_node 却不会更新。
      // saveGraph 的 INSERT 分支会把 protocolName/resource 直接写进 workflow_node，
      // 也就避开了 e2e-sim-flow 里「绑完设备还要再点一次保存」的老坑。
      await api.saveGraph(wfId, collapse(xml), { draft: true });
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

    // drop 期岛校验（P-04）：设备必须属于本流程的**权威岛**。
    // 设备池已经按权威岛过滤过了，这里是第二道闸 —— 防的是"用户之前切过岛
    // 留在 localStorage 里的旧选择"或"设备从别处进来"的漏网情况。
    if (!isBuiltin && authIslandId != null && p.islandId != null
        && p.islandId !== authIslandId) {
      message.error(t('designer.error.deviceOtherIsland', {
        island: p.islandCode || String(p.islandId),
      }));
      return;
    }

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
    //
    // 机械臂从**上一个节点所在的岛**取（P-04「LastDropped 保存归属」）：
    // 搬运是把板子从上一台设备搬到这一台，两台必须同岛 ⇒ 机械臂也要同岛。
    // 不能按本次拖入的设备找 —— 否则会插进一台别岛的机械臂。
    const armIsland = prev?.islandId ?? effectiveIslandId;
    const arm = isBuiltin ? undefined : (paletteDevices.data?.items ?? []).find((d) =>
      isArm(d.capability) && (!armIsland || d.islandId === armIsland));
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
    // 自动生成的名字落库必须是**与语种无关的中性 code**，不是界面文案：
    // 内置节点的 code 就是它的 method（__setVar/__expr/__sleep/__wait —— 后端
    // 本来就按它识别内置节点），设备节点的 code 就是方法名（与其它设备任务一致）。
    // 显示时再按 code 映射成本地化文案（见 bpmn/nodeRoleLabel.ts）。
    shape.businessObject.name = p.method;
    modeling.createShape(shape, { x, y }, root);

    if (needTransfer && arm && prev && prevEl) {
      const armShape = factory.createShape({ type: 'bpmn:Task' });
      setShapeAttrs(armShape, {
        protocolName: 'Transfer',
        resource: String(arm.resourceId),
        methodParams: JSON.stringify({ source: prev.code, target: p.code }),
        icon: iconFor(arm.icon, arm.capability),
      });
      // 自动插入的搬运节点：name 落**中性 code**，显示时再本地化
      // （ARM_TRANSFER ⇒ 「机械臂搬运」/ "Robot-arm transfer"）。
      // 不能用 method（Transfer）：那有 2000 多行普通搬运任务同名，
      // 拿它当显示标识会把所有搬运节点都显示成「机械臂搬运」。
      armShape.businessObject.name = 'ARM_TRANSFER';
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
      // 记住这个节点属于哪个岛：下一次拖入别的设备时，机械臂要在这个岛上找（P-04）
      islandId: p.islandId ?? effectiveIslandId ?? undefined,
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
      if (src && (src.$type === 'bpmn:ExclusiveGateway' || src.$type === 'bpmn:ParallelGateway'
        || src.$type === 'bpmn:InclusiveGateway')) {
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

  const save = async (force = false) => {
    const m = modelerRef.current;
    if (!m) return;

    const miss = missingRequired();
    if (miss.length > 0) {
      message.warning(t('designer.warn.missingParams', { names: miss.join(', ') }));
      return;
    }

    // 保存前校验网关连线（2026-11-12 用户反馈：拖进来忘了连线就保存，流程里留下孤立网关）。
    // 判定口径与后端 `WorkflowService` 的「孤立节点」检查**完全一致**：
    // 入边 + 出边总数 = 0 才算孤立（只有一边的图后端是允许的，前端不能比它更严）。
    // 单进单出只提醒不阻断 —— 页面上它没有分叉/汇合角色，但常被当临时占位。
    if (!force) {
      const gws = collectGateways(m);
      const orphan = gws.filter((g) => g.ins + g.outs === 0);
      if (orphan.length > 0) {
        setLooseGateways(orphan);
        return;
      }
      const direct = gws.filter((g) => g.ins === 1 && g.outs === 1);
      if (direct.length > 0) {
        message.warning(t('designer.looseGateway.directWarn', {
          names: direct.map((g) => g.name || g.id).join(', '),
        }));
      }
    }

    setSaving(true);
    try {
      const { xml } = await m.saveXML({ format: true }) as { xml: string };
      await api.saveGraph(wfId, collapse(xml));
      message.success(t('designer.toast.saved'));
      setDirty(false);
      // 保存完成打点：画布容器加 data-graph-saved（毫秒时间戳）。
      // 浏览器测试/自动化靠它判断“保存真的落库了”，不再靠固定 sleep ——
      // 设计器保存后会异步 invalidate compile/preflight，画布 DOM 在此期间
      // 不稳定，固定等待经常踩到“元素暂时查不到”。
      if (containerRef.current) {
        containerRef.current.dataset.graphSaved = String(Date.now());
      }
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

  // 网关「名字写的是什么」与「图里实际是什么角色」的对照。
  // 引擎按连线数判角色（入边多=汇合、出边多=分叉），名字骗不了它 ——
  // 两者打架时如实告诉用户，别让画布上写着「并行分叉」而引擎在等齐。
  //
  // ⚠ 实际是**直通**（单进单出）时 actualRoleLabel 为空，也算「打架」——
  //   名字说「并行分叉」而引擎按直通跑，同样是误导，得说出来。
  const nodeNameLabel = localizedNeutralLabel(nodeName);
  const actualRoleLabel = localizedNeutralLabel(gatewayRole);
  const roleMismatch = !!nodeNameLabel && nodeNameLabel !== actualRoleLabel;

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
      {/*
        wrap={false} 是画布进首屏的前提：Row 默认 flex-wrap: wrap，三列一旦
        宽度不够就会被挤成三行，bpmn-js 的画布随之掉到视口之外（实测 y≈921）。
        中间列必须同时给 minWidth: 0 —— flex 子项默认 min-width: auto，
        会被 SVG 的固有宽度顶住不让收缩，于是三轮换行。
      */}
      <Row
        gutter={16}
        wrap={false}
        style={{ height: fromExpId ? 'calc(100vh - 64px - 64px)' : 'calc(100vh - 64px)' }}
      >
      {/* 按岛设备：岛 → 设备 → 方法；把方法拖进画布直接生成已绑定节点 */}
      <Col flex="240px" style={{ minHeight: 0 }}>
        <Card size="small" title={t('designer.palette.title')}>
          <Select
            size="small"
            // 有权威岛 ⇒ 锁定：不可换岛、不可清空（"锁定不可清空"是 P-04 的原话）。
            // 没有权威岛（旧流程）⇒ 仍可手动选岛，且可清空（= 全部岛）。
            allowClear={authIslandId == null}
            disabled={authIslandId != null}
            style={{ width: '100%', marginBottom: 6 }}
            placeholder={t('designer.field.allIslands')}
            value={effectiveIslandId ?? undefined}
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
          {authIslandId != null && (
            <Typography.Text type="secondary" style={{ fontSize: 11, display: 'block', marginBottom: 6 }}>
              {t('designer.field.islandLocked', {
                island: (islandsQ.data?.islands ?? []).find((i) => i.id === authIslandId)?.name
                  ?? String(authIslandId),
              })}
            </Typography.Text>
          )}
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
      {/* minWidth: 0 / minHeight: 0：允许这一列被压缩到比自身内容更窄，
          否则 SVG 会把列撑到内容宽度，把右侧属性栏顶到下一行（三栏塌陷的根因）。 */}
      <Col
        flex="auto"
        style={{ display: 'flex', flexDirection: 'column', minWidth: 0, minHeight: 0 }}
      >
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
              <Button onClick={autoAlign} disabled={ro.disabled} title={t('designer.action.autoAlignHint')}>
                {t('designer.action.autoAlign')}
              </Button>
              {/* 导入流程规格：AI / 脚本产出的 JSON → 一键建图。
                  流程一多就没法手工拖了 —— 这条是"不拖"的入口。 */}
              <Button onClick={() => { setShowImport(true); setImportErrors([]); }}
                disabled={ro.disabled} title={t('designer.import.hint')}>
                {t('designer.action.importFlow')}
              </Button>
              {/* AI 修改流程：在页面上提需求，AI 改这张图、改参数。 */}
              <Button onClick={() => { setShowAi(true); setAiErrors([]); setAiNotes(''); setAiAnalyzed(false); }}
                disabled={ro.disabled} title={t('designer.ai.hint')}>
                {t('designer.action.aiAssist')}
              </Button>
              <Button onClick={() => nav('/workflows')}>{t('designer.action.back')}</Button>
              <Button type="primary" loading={saving} {...ro} onClick={() => void save()}>
                {t('designer.action.save')}
              </Button>
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
          ref={canvasWrapRef}
          style={{
            flex: 1, minHeight: 420, position: 'relative',
            // width:100% + overflow:hidden：画布只能长成"列有多宽就多宽"，
            // 不能让 bpmn-js 的 SVG 反过来决定列宽。
            width: '100%', overflow: 'hidden',
            background: '#fff', borderRadius: 6,
          }}
          onDragOver={(e) => { e.preventDefault(); e.dataTransfer.dropEffect = 'copy'; }}
          onDrop={onDropMethod}
        >
          <div ref={containerRef} style={{ position: 'absolute', inset: 0, overflow: 'hidden' }} />
          {/* 画布视图工具条：缩放 / 适应 / 全屏。
              放在画布容器**里面** —— 全屏时承载的就是这个容器，工具条跟着走。

              ⚠ 位置选**右上角**而不是右下角：bpmn-js 会把 "powered by bpmn.io"
              的 logo 钉在 `bottom:15px; right:15px; z-index:100`，
              放右下角必然被它压住（全屏按钮点不到）。
              z-index 取 200 压过 logo 与 context-pad。

              ⚠ 颜色必须**显式指定**：整个控制台是暗色主题，antd 的按钮文字是
              浅色（rgba(255,255,255,0.85)），而画布是白的 ⇒ 白字白底看不见。 */}
          <div
            data-testid="canvas-toolbar"
            style={{
              position: 'absolute', right: 12, top: 12, zIndex: 200,
              display: 'flex', alignItems: 'center', gap: 2,
              padding: '3px 5px', borderRadius: 6,
              background: 'rgba(255,255,255,0.98)', border: '1px solid #d9d9d9',
              boxShadow: '0 2px 8px rgba(0,0,0,0.12)',
              color: '#1f2937',
            }}
          >
            <Tooltip title={t('designer.canvas.zoomOut')}>
              <Button size="small" type="text" aria-label="zoom-out"
                onClick={() => zoomBy(-0.1)}
                style={{ width: 26, padding: 0, fontSize: 15, lineHeight: '24px',
                         color: '#1f2937' }}>−</Button>
            </Tooltip>
            <Typography.Text
              style={{ fontSize: 11, minWidth: 46, textAlign: 'center',
                       cursor: 'default', color: '#1f2937' }}
            >
              {t('designer.canvas.zoomLevel', { pct: zoomPct })}
            </Typography.Text>
            <Tooltip title={t('designer.canvas.zoomIn')}>
              <Button size="small" type="text" aria-label="zoom-in"
                onClick={() => zoomBy(0.1)}
                style={{ width: 26, padding: 0, fontSize: 15, lineHeight: '24px',
                         color: '#1f2937' }}>+</Button>
            </Tooltip>
            <Button size="small" type="text" onClick={fitViewport}
              style={{ fontSize: 12, padding: '0 8px', color: '#1f2937' }}>
              {t('designer.canvas.fit')}
            </Button>
            <Tooltip title={isFullscreen ? t('designer.canvas.exitFullscreen')
              : t('designer.canvas.fullscreen')}>
              <Button size="small" type="text" aria-label="fullscreen"
                onClick={() => void toggleFullscreen()}
                style={{ fontSize: 13, padding: '0 6px', color: '#1f2937' }}>⛶</Button>
            </Tooltip>
          </div>
        </div>
      </Col>

      {/* 右侧属性栏：三栏不再换行后它只占 320px，内容（变量/选项/节点设置）
          会比列高，必须自己滚动，否则会溢出到视口外。 */}
      <Col flex="320px" style={{ minWidth: 0, minHeight: 0, overflowY: 'auto', overflowX: 'hidden' }}>
        <WorkflowVarsCard wfId={wfId} />
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
        ) : isGatewayType(selType) ? (
          /* 网关专用卡片。
             为什么单独一张：网关**不需要**绑定设备/方法，而「节点设置」卡挂在
             compile 结果上（compile 只认 task），所以网关选中后右栏只会显示
             「还没保存，先保存画布才能绑定设备与方法」—— 想给网关起个名都做不到。
             而网关恰恰最需要名字：三种网关都是菱形（✕ / ＋ / ○），
             缩小后分不出类型，「分叉」还是「汇合」光看形状也看不出来
             （引擎按连线数自动判）—— 所以画布上另用绿边/橙边区分（见 GatewayVisuals.ts）。 */
          <Card size="small" title={t('designer.gateway.title')}
            extra={<Button size="small" type="primary" loading={saving} {...ro}
              onClick={() => void save()}>
              {t('designer.action.save')}
            </Button>}>
            <Space direction="vertical" size={8} style={{ width: '100%' }}>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {t('designer.gateway.kindLabel')}
              </Typography.Text>
              <Tag color={selType === 'bpmn:ParallelGateway' ? 'blue'
                : selType === 'bpmn:InclusiveGateway' ? 'purple' : 'default'}>
                {selType === 'bpmn:ParallelGateway'
                  ? t('designer.gateway.parallel')
                  : selType === 'bpmn:InclusiveGateway'
                    ? t('designer.gateway.conditional')
                    : t('designer.gateway.exclusive')}
              </Tag>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {t('designer.gateway.roleLabel')}
              </Typography.Text>
              <Tag color={isMergeRole(gatewayRole) ? 'orange' : 'green'}>
                {localizedNeutralLabel(gatewayRole) || t('designer.gateway.roleUnknown')}
              </Tag>
              {/* 名字与实际角色打架时如实说：引擎按连线数判，名字骗不了它。 */}
              {roleMismatch && (
                <Alert type="warning" showIcon
                  message={actualRoleLabel
                    ? t('designer.gateway.roleMismatch', {
                      name: nodeNameLabel,
                      role: actualRoleLabel,
                    })
                    : t('designer.gateway.roleMismatchStraight', { name: nodeNameLabel })} />
              )}
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
              {gatewayRole && (
                <Button size="small" type="link" style={{ padding: 0, alignSelf: 'flex-start' }}
                  onClick={() => writeNodeName(String(selected), gatewayRole)} {...ro}>
                  {t('designer.action.useSuggestedName', { name: localizedNeutralLabel(gatewayRole) ?? gatewayRole })}
                </Button>
              )}
              {selType === 'bpmn:InclusiveGateway' && (
                <Alert type="info" showIcon message={t('designer.gateway.needCondition')} />
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
              wfId={String(wfId)} nodeKey={String(selected)} islandId={effectiveIslandId}
              onPatched={({ method, params, resourceId }) => {
                const m = modelerRef.current;
                if (!m || !selected) return;
                const reg = m.get('elementRegistry') as { get: (id: string) => SelectedElement | undefined };
                const el = reg.get(String(selected));
                const bo = el?.businessObject as { $attrs?: Record<string, string>; name?: string } | undefined;
                if (!bo) return;
                let attrs = bo.$attrs;
                if (!attrs) {
                  attrs = {};
                  Object.defineProperty(bo, '$attrs', {
                    value: attrs, writable: true, configurable: true, enumerable: false,
                  });
                }
                attrs.protocolName = method;
                attrs.methodParams = JSON.stringify(params ?? {});
                if (resourceId != null && resourceId > 0) {
                  attrs.resource = String(resourceId);
                } else {
                  delete attrs.resource;
                  delete attrs.icon;
                }
                // name 落中性 code：内置节点用 method（__expr 等），设备节点用方法名
                bo.name = method;
              }}
              onSaved={() => {
                // 不要整图 importXML！节点保存后端已是「只 patch 该节点」，
                // 画布模型本身没变；重新导入会制造一个“旧模型窗口”——用户/测试
                // 紧接着点下一个节点或点画布保存时，会拿旧模型覆盖刚保存的设置
                // （实测连续设置 3 个内置节点只剩 1 个、6 个分支只剩最后 1 个）。
                // 这里只刷新编译/预检数据（右栏信息来自 compile），画布不动。
                void compile.refetch?.();
                void pre.refetch?.();
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
      {/* 保存前拦截孤立网关（入边+出边 = 0）：这种节点没有角色、也不会被引擎执行，
          留在流程里只会让画布和编译结果都出现「看不懂的方块」。
          口径与后端 `WorkflowService` 的孤立节点校验一致 —— 后端本来就会 400 拒绝，
          这里提前拦是为了**指出是哪几个**并能一键定位，而不是等一个泛泛的报错。 */}
      <Modal
        open={looseGateways !== null}
        title={t('designer.looseGateway.title')}
        onCancel={() => setLooseGateways(null)}
        footer={<Button type="primary" onClick={() => setLooseGateways(null)}>
          {t('designer.looseGateway.fix')}
        </Button>}
        width={560}
      >
        <Alert type="warning" showIcon
          message={t('designer.looseGateway.hint')} style={{ marginBottom: 10 }} />
        <Space direction="vertical" size={4} style={{ width: '100%' }}>
          {(looseGateways ?? []).map((g) => (
            <Typography.Text key={g.id} type="secondary">
              <Tag color="orange">{g.name || g.id}</Tag>
              {' '}
              {t('designer.looseGateway.item', { ins: g.ins, outs: g.outs })}
            </Typography.Text>
          ))}
        </Space>
      </Modal>
      <Modal
        open={showTimeline}
        title={t('designer.estimate.title', { name: compile.data?.workflow.name ?? wfId })}
        onCancel={() => setShowTimeline(false)}
        footer={null}
        width={720}
      >
        <TimelineChart data={timeline.data} />
      </Modal>

      {/* 导入流程规格：AI / 脚本产出的 JSON → 直接建图并落库。 */}
      <Modal
        open={showImport}
        title={t('designer.import.title')}
        onCancel={() => { setShowImport(false); setImportErrors([]); }}
        onOk={() => void doImport()}
        okText={t('designer.import.ok')}
        cancelText={t('designer.import.cancel')}
        confirmLoading={saving}
        width={760}
      >
        <Space direction="vertical" size={8} style={{ width: '100%' }}>
          <Alert type="warning" showIcon message={t('designer.import.clearWarn')} />
          <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 0 }}>
            {t('designer.import.hint')}
          </Typography.Paragraph>
          <Input.TextArea
            data-testid="flow-spec-input"
            rows={14}
            value={importText}
            onChange={(e) => { setImportText(e.target.value); setImportErrors([]); }}
            placeholder={t('designer.import.placeholder')}
            style={{ fontFamily: 'monospace', fontSize: 12 }}
          />
          {importErrors.length > 0 && (
            <Alert
              type="error" showIcon
              message={t('designer.import.invalid')}
              description={
                <ul style={{ margin: 0, paddingLeft: 18, fontSize: 12, maxHeight: 180, overflow: 'auto' }}>
                  {importErrors.map((e, i) => <li key={i}>{e}</li>)}
                </ul>
              }
            />
          )}
          <Button size="small" onClick={() => {
            setImportText(JSON.stringify(FLOW_SPEC_EXAMPLE, null, 2));
            setImportErrors([]);
          }}>
            {t('designer.import.example')}
          </Button>
        </Space>
      </Modal>

      {/* AI 修改流程：页面上提需求 → AI 给修改清单 → 预览后应用。 */}
      <Modal
        open={showAi}
        title={t('designer.ai.title')}
        onCancel={() => { setShowAi(false); setAiOps([]); setAiErrors([]); }}
        footer={[
          <Button key="close" onClick={() => { setShowAi(false); setAiOps([]); setAiErrors([]); }}>
            {t('designer.import.cancel')}
          </Button>,
          aiOps.length > 0 && (
            <Button key="apply" type="primary" loading={aiBusy}
              onClick={() => void applyAiOps()}>
              {t('designer.ai.apply')}
            </Button>
          ),
        ].filter(Boolean) as React.ReactNode[]}
        width={760}
      >
        <Space direction="vertical" size={10} style={{ width: '100%' }}>
          <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 0 }}>
            {t('designer.ai.hint')}
          </Typography.Paragraph>
          <Input.TextArea
            data-testid="ai-instruction"
            rows={3}
            value={aiInstruction}
            onChange={(e) => setAiInstruction(e.target.value)}
            placeholder={t('designer.ai.placeholder')}
          />
          <Space>
            <Button type="primary" loading={aiBusy}
              disabled={!aiInstruction.trim()}
              onClick={() => void analyzeAi()}>
              {aiOps.length ? t('designer.ai.reanalyze') : t('designer.ai.analyze')}
            </Button>
            {aiCatalogQ.isFetching && (
              <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                {t('designer.palette.title')}…
              </Typography.Text>
            )}
          </Space>

          {aiOps.length > 0 && (
            <Card size="small" style={{ width: '100%' }}
              title={t('designer.ai.preview', { n: aiOps.length })}
              extra={
                <Tag color={aiMode === 'plan' ? 'purple' : 'blue'}>
                  {aiMode === 'plan'
                    ? t('designer.ai.mode.plan')
                    : t('designer.ai.mode.ops')}
                </Tag>
              }>
              <ol style={{ margin: 0, paddingLeft: 20, fontSize: 12, lineHeight: 1.9 }}>
                {aiOps.map((op, i) => (
                  <li key={i}>{describeOp(op, aiNodeLabel)}</li>
                ))}
              </ol>
            </Card>
          )}
          {aiOps.length === 0 && aiAnalyzed && !aiBusy && (
            <Alert type="info" showIcon message={t('designer.ai.previewEmpty')} />
          )}
          {aiNotes && (
            <Alert type="warning" showIcon message={aiNotes} />
          )}
          {aiErrors.length > 0 && (
            <Alert
              type="error" showIcon
              message={t('designer.ai.analyzeFailed')}
              description={
                <ul style={{ margin: 0, paddingLeft: 18, fontSize: 12, maxHeight: 160, overflow: 'auto' }}>
                  {aiErrors.map((e, i) => <li key={i}>{e}</li>)}
                </ul>
              }
            />
          )}
        </Space>
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
