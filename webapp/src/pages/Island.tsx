import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert, Button, Card, Col, Descriptions, Empty, Form, Input, InputNumber,
  Modal, Row, Select, Space, Spin, Tag, Typography, message,
} from 'antd';
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { useReadOnly } from '../auth';
import { useT } from '../i18n';
import { translate } from '../i18n/core';

interface Dev {
  /** 心跳：能不能连上。与 status 区分——online 是通不通，status 是在干什么 */
  online?: boolean;
  status?: string;
  currentMethod?: string | null;
  hasPlate?: boolean;
  resourceId: number; code: string; name: string;
  model?: string; vendor?: string; capability?: string;
  /** 设备示意图（/devices/xxx.svg），没有就退回能力色块 */
  icon?: string | null;
  ip?: string; driverEndpoint?: string;
  x?: number | null; y?: number | null;
  instanceId?: number | null;
}
interface Island {
  id: number; code: string; name: string; deviceCount: number; devices: Dev[];
}
/** 设备型号（池子的元素）：一个型号可以拖多次，每次部署一台真实设备。 */
interface DeviceModel {
  modelId: number; code: string; name: string;
  deviceType?: string; vendor?: string;
  capability?: string; deployed: number;
  /** 设备池里登记的示意图/实物照片（/devices/xxx.svg）。没有就按能力画图标。 */
  icon?: string | null;
}

/**
 * 设备池里的缩略图：优先用登记的示意图/照片，**加载失败自动退回能力图标**。
 *
 * <p>为什么要退：老设备的 icon 指向老服务器的 /image/device/... ，那些文件在
 * 新环境里 404，直接 img 会显示成破图，比没有图更难看。
 */
function DevIcon({ icon, cap, name, size }: {
  icon?: string | null; cap?: string | null; name?: string; size: number;
}) {
  const [bad, setBad] = useState(false);
  if (!icon || bad) {
    return <Thumb cap={cap ?? undefined} size={size} />;
  }
  return (
    <img
      src={icon}
      alt={name ?? cap ?? ''}
      width={size}
      height={size}
      onError={() => setBad(true)}
      style={{ objectFit: 'cover', borderRadius: 4, flex: `0 0 ${size}px` }}
    />
  );
}

function PoolIcon({ m }: { m: DeviceModel }) {
  return <DevIcon icon={m.icon} cap={m.capability} name={m.name} size={32} />;
}

/** 位置（孵箱层位 / 存储槽位 / 转运点）的占用状态。 */
interface PositionState {
  resourceId: number; code: string; name: string; kind: string;
  plateId?: string | null; reserved: boolean; reservedByRun?: number | null;
  occupiedSince?: string | null; available: boolean;
  parentDeviceId?: number | null; parentDeviceCode?: string | null;
}

interface Method {
  method: string; name: string;
  params?: Array<{ name: string; control_type?: string; default?: string; unit?: string; range?: string }>;
}

const TOKEN = () => localStorage.getItem('labos_token');
const auth = () => ({ Authorization: `Bearer ${TOKEN()}` });

/**
 * 带鉴权的 fetch。
 *
 * 关键：401 时清 token 并派发事件让 App 跳登录页。
 * 之前直接用裸 fetch，token 过期后页面只会"空"，用户完全不知道发生了什么。
 */
async function apiFetch(path: string, init?: RequestInit): Promise<Response> {
  const r = await fetch(path, {
    ...init,
    headers: { ...auth(), ...(init?.headers ?? {}) },
  });
  if (r.status === 401) {
    localStorage.removeItem('labos_token');
    window.dispatchEvent(new Event('labos:unauthorized'));
    throw new Error(translate('island.error.sessionExpired'));
  }
  return r;
}

const CAP_COLOR: Record<string, string> = {
  TRANSFER: '#3b82f6', ALIQUOT: '#8b5cf6', ANALYZE: '#06b6d4',
  READ_PLATE: '#06b6d4', INCUBATE: '#f59e0b', CENTRIFUGE: '#ec4899',
  STORE: '#64748b', PCR: '#22c55e',
};
const PHOTO = new Set(['INCUBATE', 'TRANSFER', 'ALIQUOT', 'READ_PLATE', 'ANALYZE', 'CENTRIFUGE', 'STORE', 'PCR']);

/** 设备缩略图：有照片用照片，没有用能力首字母色块。 */
function Thumb({ cap, size = 48 }: { cap?: string; size?: number }) {
  const [ok, setOk] = useState(true);
  const color = CAP_COLOR[cap ?? ''] ?? '#64748b';
  if (cap && PHOTO.has(cap) && ok) {
    return (
      <img src={`/devices/${cap}.jpg`} alt={cap} onError={() => setOk(false)}
        style={{ width: size * 1.33, height: size, objectFit: 'cover',
          borderRadius: 4, border: `1px solid ${color}55` }} />
    );
  }
  return (
    <div style={{
      width: size, height: size, borderRadius: 4, background: `${color}22`,
      border: `1px solid ${color}55`, display: 'flex', alignItems: 'center',
      justifyContent: 'center', color, fontWeight: 700, fontSize: 12,
    }}>{(cap ?? '?').slice(0, 3)}</div>
  );
}

/**
 * 岛屿视图：**可拖拽建设**。
 *
 * 左：设备池（还没挂到岛上的）  右：岛屿画布
 * 拖一个进去 → PUT /devices/{id}/island → 立刻刷新
 */
export default function IslandPage() {
  const t = useT();
  const qc = useQueryClient();
  const nav = useNavigate();
  // 设备部署/移出/删除/改位置、建岛删岛、下发指令都是写操作：viewer 只读
  const ro = useReadOnly();
  const [code, setCode] = useState<string | null>(
    new URLSearchParams(window.location.search).get('code'));
  const [picked, setPicked] = useState<Dev | null>(null);
  const [methods, setMethods] = useState<Method[] | null>(null);
  const [method, setMethod] = useState<string | null>(null);
  const [pv, setPv] = useState<Record<string, unknown>>({});
  const [firing, setFiring] = useState(false);
  const [result, setResult] = useState<{ ok: boolean; elapsedMs: number; summary: string;
    rawResponse?: string; contract?: Record<string, boolean> } | null>(null);
  const [newIsland, setNewIsland] = useState(false);
  const [islandForm] = Form.useForm();
  const [newDevice, setNewDevice] = useState(false);
  const [deviceForm] = Form.useForm();
  const [dragOver, setDragOver] = useState(false);
  const [poolOver, setPoolOver] = useState(false);
  /** 设备在岛上的坐标（像素，相对画布左上角）。 */
  const [pos, setPos] = useState<Record<number, { x: number; y: number }>>({});
  /** 选中的两台设备，用于画搬运路径。 */
  const [pathTo, setPathTo] = useState<Dev | null>(null);
  /** 最新坐标快照：回放是 async 的，闭包里的 pos 可能是旧的。 */
  const posRef = useRef<Record<number, { x: number; y: number }>>({});
  useEffect(() => { posRef.current = pos; }, [pos]);

  /** 板子当前位置（画布坐标）；null 表示还没上料。 */
  const [board, setBoard] = useState<{ x: number; y: number } | null>(null);
  /** 板子是否在岛内（不在时提示"执行的设备不在本岛"）。 */
  const [boardOutside, setBoardOutside] = useState(false);
  const [demoRunning, setDemoRunning] = useState(false);
  /** 搬运序列：按顺序经过的设备。 */
  const [seq, setSeq] = useState<Array<{ dev: Dev; method: string }>>([]);
  /** 每台设备的方法列表缓存（选站时用）。 */
  const [devMethods, setDevMethods] = useState<Record<number, Method[]>>({});
  const [seqRunning, setSeqRunning] = useState(false);
  const [savingSeq, setSavingSeq] = useState(false);

  // 执行期间轮询运行态：当前节点在哪台设备，板子就画到哪台
  const live = useQuery({
    queryKey: ['live-status'],
    queryFn: () => apiFetch('/api/process-status')
      .then((r) => r.json()) as Promise<{
        items: Array<{ runId: number; attendance?: string;
          current?: { name?: string; resourceId?: number; status?: string } }>;
      }>,
    refetchInterval: demoRunning ? 700 : false,
  });



  // 手动拉拓扑：不走 React Query，避免缓存/重试干扰排查
  const [islands, setIslands] = useState<Island[]>([]);
  const [topoErr, setTopoErr] = useState<string | null>(null);
  const [topoLoading, setTopoLoading] = useState(true);
  const [topoReload, setTopoReload] = useState(0);

  // 首次加载显示 loading；之后的轮询静默更新，不闪
  const firstLoad = useRef(true);
  useEffect(() => {
    let alive = true;
    if (firstLoad.current) {
      setTopoLoading(true);
    }
    apiFetch('/api/topology')
      .then((r) => (r.ok ? r.json() : r.text().then((t) => {
        throw new Error(`HTTP ${r.status}：${t.slice(0, 150)}`);
      })))
      .then((d: { islands?: Island[] }) => {
        if (!alive) return;
        setIslands(Array.isArray(d.islands) ? d.islands : []);
        setTopoErr(Array.isArray(d.islands) ? null : t('island.error.islandsField'));
      })
      .catch((e) => {
        if (!alive) return;
        setIslands([]);
        setTopoErr(String((e as Error).message));
      })
      .finally(() => {
        if (alive) {
          setTopoLoading(false);
          firstLoad.current = false;
        }
      });
    return () => { alive = false; };
  }, [topoReload]);

  // 每 5 秒静默刷新一次：设备上线/离线、运行中状态要靠这个才看得到。
  // 不刷新的话，只有手动 reload 页面才能发现"设备掉线了"。
  useEffect(() => {
    const timer = setInterval(() => setTopoReload((n) => n + 1), 5000);
    return () => clearInterval(timer);
  }, []);
  const [poolItems, setPoolItems] = useState<DeviceModel[]>([]);
  const [poolLoading, setPoolLoading] = useState(true);
  const [poolReload, setPoolReload] = useState(0);

  useEffect(() => {
    let alive = true;
    setPoolLoading(true);
    apiFetch('/api/device-models')
      .then((r) => r.json())
      .then((d: { items?: DeviceModel[] }) => {
        if (alive) setPoolItems(d.items ?? []);
      })
      .catch(() => { if (alive) setPoolItems([]); })
      .finally(() => { if (alive) setPoolLoading(false); });
    return () => { alive = false; };
  }, [poolReload]);

  // 默认选"已布局设备最多"的岛：说明这个岛是有人认真摆过的，
  // 比"设备最多"（可能是老库脏数据）或"最新"（可能刚建还没设备）更贴近意图。
  const laidOut = (i: Island) => (i.devices ?? []).filter((d) => d.x != null).length;
  const current = islands.find((i) => i.code === code)
    ?? [...islands].sort((a, b) => laidOut(b) - laidOut(a))[0];
  const devs = useMemo(() => current?.devices ?? [], [current]);

  // 池子里有 76 个型号，没有搜索只能一路滚（S100 这种"只部署过 1 台"的排在很后面）
  const [poolQ, setPoolQ] = useState('');
  const poolDevs = poolQ.trim()
    ? poolItems.filter((m) => {
        const q = poolQ.trim().toLowerCase();
        return (m.name ?? '').toLowerCase().includes(q)
            || (m.code ?? '').toLowerCase().includes(q)
            || (m.capability ?? '').toLowerCase().includes(q);
      })
    : poolItems;

  useEffect(() => {
    const cur = live.data?.items?.[0]?.current;
    if (!cur?.resourceId) return;
    const dev = devs.find((d) => d.resourceId === cur.resourceId);
    if (dev && pos[dev.resourceId]) {
      setBoard({ x: pos[dev.resourceId].x + 58, y: pos[dev.resourceId].y + 26 });
      setBoardOutside(false);
    } else {
      setBoardOutside(true);
    }
  }, [live.data, devs, pos]);

  // 载入坐标：有存的用存的，没存的按网格自动排
  useEffect(() => {
    const next: Record<number, { x: number; y: number }> = {};
    devs.forEach((d, i) => {
      next[d.resourceId] = (d.x != null && d.y != null)
        ? { x: Number(d.x), y: Number(d.y) }
        : { x: 24 + (i % 4) * 190, y: 24 + Math.floor(i / 4) * 132 };
    });
    setPos(next);
    setPathTo(null);
  }, [devs]);

  /** 拖动设备：跟手移动，松手后保存坐标。 */
  const startDragDevice = (rid: number, e: React.MouseEvent) => {
    e.stopPropagation();
    if (ro.disabled) return;   // 拖动会保存坐标（PUT position）：只读不拖
    const origin = pos[rid];
    if (!origin) return;
    const sx = e.clientX;
    const sy = e.clientY;
    const move = (ev: MouseEvent) => {
      setPos((prev) => ({
        ...prev,
        [rid]: { x: Math.max(0, origin.x + ev.clientX - sx),
                 y: Math.max(0, origin.y + ev.clientY - sy) },
      }));
    };
    const up = (ev: MouseEvent) => {
      window.removeEventListener('mousemove', move);
      window.removeEventListener('mouseup', up);
      const final = { x: Math.max(0, origin.x + ev.clientX - sx),
                      y: Math.max(0, origin.y + ev.clientY - sy) };
      apiFetch(`/api/devices/${rid}/position`, {
        method: 'PUT', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(final),
      }).catch(() => { /* 位置存不上不影响当前视图 */ });
    };
    window.addEventListener('mousemove', move);
    window.addEventListener('mouseup', up);
  };

  // 详情打开时拉位置占用（用于显示层位）
  const positions = useQuery({
    queryKey: ['positions', current?.id, picked?.resourceId],
    queryFn: () => apiFetch(`/api/positions?islandId=${current!.id}`)
      .then((r) => r.json()) as Promise<{ items: PositionState[] }>,
    enabled: picked != null && current != null,
  });

  // 选设备 -> 拉方法
  useEffect(() => {
    if (!picked) { setMethods(null); setMethod(null); return; }
    apiFetch(`/api/devices/${picked.resourceId}/methods`)
      .then((r) => r.json())
      .then((d) => setMethods(d.items ?? []))
      .catch(() => setMethods([]));
  }, [picked]);

  // 换方法 -> 默认值回填
  useEffect(() => {
    const m = methods?.find((x) => x.method === method);
    const next: Record<string, unknown> = {};
    for (const p of m?.params ?? []) {
      const dv = p.default;
      if (dv && dv.length < 60 && !dv.includes('{')) next[p.name] = dv;
    }
    setPv(next);
    setResult(null);
  }, [method, methods]);

  const createIsland = async () => {
    const v = await islandForm.validateFields();
    const r = await apiFetch('/api/islands', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(v),
    });
    const j = await r.json();
    if (!r.ok) { message.error(j.error); return; }
    message.success(t('island.toast.islandCreated', { code: v.code }));
    setNewIsland(false); islandForm.resetFields();
    await qc.invalidateQueries({ queryKey: ['topology'] });
    setCode(v.code);
  };

  /** 拖进岛：改资源树归属 */
  /**
   * 拖型号进岛 = 部署一台新设备。
   *
   * 池子里是"型号"，每拖一次产生一台独立设备（编号递增、IP 留空）。
   * 同一个型号拖 N 次就是 N 台 —— 对应"这个岛要 3 台孵箱"。
   */
  const deployModelToIsland = async (modelId: number) => {
    const target = current?.id;
    if (!target) { message.warning(t('island.warn.pickIsland')); return; }
    const r = await apiFetch(`/api/device-models/${modelId}/deploy?islandId=${target}`, {
      method: 'POST',
    });
    const j = await r.json();
    if (!r.ok) { message.error(j.error); return; }
    message.success(t('island.toast.deployed'));
    setPoolReload((x) => x + 1);
    setTopoReload((x) => x + 1);
  };

  const dropDevice = async (rid: number, islandId?: number) => {
    const target = islandId ?? current?.id;
    if (!target) return;
    const r = await apiFetch(`/api/devices/${rid}/island?islandId=${target}`, {
      method: 'PUT',
    });
    const j = await r.json();
    if (!r.ok) { message.error(j.error); return; }
    message.success(t('island.toast.deployedShort'));
    setPoolReload((x) => x + 1);
    setTopoReload((x) => x + 1);
  };

  /** 按能力推荐方法：转运优先 Transfer/TransferPlate，放板优先 Deposit*，取板优先 Withdraw*。 */
  const recommend = (cap: string | undefined, methods: Method[]): string => {
    const names = methods.map((m) => m.method);
    const pick = (pre: string[]) => names.find((n) => pre.some((p) => n.startsWith(p)));
    const byCap: Record<string, string[]> = {
      TRANSFER: ['Transfer', 'TransferPlate', 'MovePlate'],
      INCUBATE: ['Deposit', 'Put', 'Load'],
      STORE: ['Input', 'Deposit', 'Put'],
      READ_PLATE: ['Read', 'Scan'],
      ALIQUOT: ['Dispense', 'Transfer'],
      CENTRIFUGE: ['Load', 'Run'],
    };
    return pick(byCap[cap ?? ''] ?? []) ?? names[0] ?? '';
  };

  const addToSeq = async (d: Dev) => {
    if (seq.some((x) => x.dev.resourceId === d.resourceId)) {
      message.info(t('island.info.alreadyInSeq'));
      return;
    }
    let ms = devMethods[d.resourceId];
    if (!ms) {
      const j = await apiFetch(`/api/devices/${d.resourceId}/methods`).then((r) => r.json());
      ms = j.items ?? [];
      setDevMethods((prev) => ({ ...prev, [d.resourceId]: ms! }));
    }
    setSeq([...seq, { dev: d, method: recommend(d.capability, ms) }]);
  };

  /**
   * 走一遍搬运序列：逐个设备下发一条真实命令，板子跟着移动。
   *
   * 不生成流程、不落 run——这是"验证岛内物流"的演示通道。
   * 要留痕的正式编排走「实验编排」（workflow 组合）。
   */
  const runSeq = async () => {
    if (seq.length === 0) { message.warning(t('island.warn.addDevicesFirst')); return; }
    setSeqRunning(true);
    try {
      for (const d of seq) {
        const pnt = pos[d.dev.resourceId];
        if (pnt) setBoard({ x: pnt.x + 58, y: pnt.y + 26 });
        // 取该设备第一个方法下发（真实调用驱动）
        const method = d.method;
        if (!method) { message.warning(t('island.warn.noMethod', { code: d.dev.code })); continue; }
        const r = await apiFetch(
          `/api/shadow/probe?resourceId=${d.dev.resourceId}&method=${encodeURIComponent(method)}`,
          { method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ params: {} }) },
        ).then((x) => x.json());
        if (!r.ok) message.warning(t('island.warn.stepFailed', { code: d.dev.code, method, error: r.errorCode }));
        await new Promise((res) => setTimeout(res, 900));   // 让板子动画走完
      }
      message.success(t('island.toast.seqDone', { n: seq.length }));
    } finally {
      setSeqRunning(false);
    }
  };

  /** 把搬运序列落成正式流程（可反复执行、可进实验编排）。 */
  const saveAsWorkflow = async () => {
    if (seq.length === 0) { message.warning(t('island.warn.addDevicesFirst')); return; }
    if (seq.some((x) => !x.method)) { message.warning(t('island.warn.someStationNoMethod')); return; }
    setSavingSeq(true);
    try {
      const r = await apiFetch('/api/choreography', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: t('island.flowName', { name: current?.name ?? t('island.title.island'), time: new Date().toLocaleString() }),
          steps: seq.map((x) => ({ resourceId: x.dev.resourceId, method: x.method })),
        }),
      });
      const j = await r.json();
      if (!r.ok) throw new Error(j.error);
      message.success(t('island.toast.flowCreated', { id: j.workflowId, n: j.steps }));
      // 直接进设计器：存完就能微调，不用回流程库再找
      setTimeout(() => nav(`/designer/${j.workflowId}`), 400);
    } catch (e) {
      message.error(String((e as Error).message));
    } finally {
      setSavingSeq(false);
    }
  };

  /** 跑一条流程，板子跟着当前节点在岛内设备间移动。 */
  const runFlow = async () => {
    setDemoRunning(true);
    setBoard(null);
    setBoardOutside(false);
    // 先把板子放到第一台设备上（有坐标的）
    const first = devs.find((d) => pos[d.resourceId]);
    if (first) setBoard({ x: pos[first.resourceId].x + 58, y: pos[first.resourceId].y + 26 });

    try {
      // 优先跑"搬运流程"——它绑死了具体设备，板子才会真的换地方。
      // 普通流程只绑能力，两个同能力节点会选到同一台，板子不动。
      // P1-24 修复（评审 20261102-03）：原来自带兜底 `wfId = 239`，
      // 那是开发库里的特定流程 id —— 换一个环境（或那条流程被删）
      // 就会"跑了一个不存在的流程"，报错还莫名其妙。
      // 现在取不到就明确告诉用户，不猜。
      let wfId: number;
      try {
        // 专用接口（列表按 id 降序），第一条就是最新的搬运流程
        const list = await apiFetch('/api/choreographies')
          .then((x) => x.json()) as { items?: Array<{ id: number }> };
        if (!list.items?.length) {
          message.error(t('island.error.noChoreography'));
          return;
        }
        wfId = list.items[0].id;
      } catch (e) {
        message.error(t('island.error.getChoreography', { msg: String((e as Error).message) }));
        return;
      }

      const isl = current?.id ? `?islandId=${current.id}` : '';
      const r = await apiFetch(`/api/workflows/${wfId}/run${isl}`, {
        method: 'POST',
      }).then((x) => x.json());
      if (r.error) { message.error(r.error); return; }
      message.success(t('island.toast.execDone', { done: r.completed, total: r.total, wf: wfId }));

      // 按"节点绑定设备"的顺序回放。
      // 不用 run_node.resource_id：它目前是空的（开发日志 B8），
      // 而搬运流程的节点本来就绑死了设备，直接读更可靠。
      const detail = await apiFetch(`/api/workflows/${wfId}`)
        .then((x) => x.json()) as {
          tasks?: Array<{ name: string; resourceId?: number | null }>;
        };
      const path = (detail.tasks ?? [])
        .map((t) => t.resourceId)
        .filter((id): id is number => id != null);

      if (path.length === 0) {
        message.warning(t('island.warn.noDeviceBinding'));
      } else {
        for (const rid of path) {
          const pnt = posRef.current[rid];
          const dev = devs.find((d) => d.resourceId === rid);
          if (!pnt) {
            message.warning(t('island.warn.noCoords', { id: rid }));
            continue;
          }
          setBoard({ x: pnt.x + 22, y: pnt.y + 40 });
          message.info(t('island.info.plateAt', { code: dev?.code ?? rid }));
          await new Promise((res) => setTimeout(res, 1000));
        }
      }
    } catch (e) {
      message.error(String((e as Error).message));
    } finally {
      // 留 2 秒让最后一步动画走完
      setTimeout(() => setDemoRunning(false), 2000);
    }
  };

  /** 模拟搬运：板子从上一台选中设备滑到当前选中设备（纯前端，演示物流）。 */
  const simulateTransfer = () => {
    if (!picked || !pathTo) return;
    const a = pos[pathTo.resourceId];
    const b = pos[picked.resourceId];
    if (!a || !b) { message.warning(t('island.warn.bothNeedCoords')); return; }
    setBoard({ x: a.x + 58, y: a.y + 26 });
    // 下一帧再设目标，触发 transition
    setTimeout(() => setBoard({ x: b.x + 58, y: b.y + 26 }), 60);
    message.success(t('island.toast.simTransfer', { from: pathTo.code, to: picked.code }));
  };

  const fire = async () => {
    if (!picked || !method) return;
    setFiring(true); setResult(null);
    try {
      const r = await apiFetch(
        `/api/shadow/probe?resourceId=${picked.resourceId}&method=${encodeURIComponent(method)}`,
        { method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ params: pv }) },
      ).then((x) => x.json());
      if (r.error) { message.error(r.error); return; }
      setResult(r);
      message[r.ok ? 'success' : 'warning'](
        r.ok
          ? t('island.toast.fireOk', { code: picked.code, method, ms: r.elapsedMs })
          : t('island.toast.fireFailed', { method, error: r.errorCode }));
    } finally { setFiring(false); }
  };

  const curMethod = methods?.find((m) => m.method === method);

  return (
    <Row gutter={12} style={{ height: 'calc(100vh - 120px)' }}>
      {/* 左：设备池 */}
      <Col flex="280px">
        <Card size="small" style={{ height: '100%', overflow: 'auto' }}
          title={<Space><span>{t('island.pool.title')}</span><Tag>{poolDevs.length}</Tag></Space>}
          extra={
            <Space size={4}>
              <Button size="small" type="primary" ghost icon={<PlusOutlined />} {...ro}
                onClick={() => { setNewDevice(true); deviceForm.resetFields(); }}>
                {t('island.action.addDevice')}
              </Button>
              <Button size="small" icon={<ReloadOutlined />}
                onClick={() => { setPoolReload((x) => x + 1);
                                 setTopoReload((x) => x + 1); }} />
            </Space>
          }

          styles={{ body: { minHeight: 200 } }}>
          <Input
            allowClear size="small" placeholder={t('island.placeholder.searchPool')}
            value={poolQ} onChange={(e) => setPoolQ(e.target.value)}
            style={{ marginBottom: 8 }}
          />
          <div
            onDragOver={(e) => { e.preventDefault(); setPoolOver(true); }}
            onDragLeave={() => setPoolOver(false)}
            onDrop={(e) => {
              e.preventDefault(); setPoolOver(false);
              if (ro.disabled) return;
              const rid = Number(e.dataTransfer.getData('text/plain'));
              if (rid) dropDevice(rid, 0);   // 0 = 移出
            }}
            style={{
              padding: 6, marginBottom: 8, borderRadius: 6,
              border: `1px dashed ${poolOver ? '#f59e0b' : '#262c38'}`,
              background: poolOver ? '#f59e0b12' : 'transparent',
            }}
          >
            <Typography.Text type="secondary" style={{ fontSize: 10 }}>
              {poolOver ? t('island.pool.dragHint')
                : t('island.pool.explain')}
            </Typography.Text>
          </div>
          <Space direction="vertical" size={8} style={{ width: '100%' }}>
            {poolDevs.map((m) => (
              <div key={m.modelId} draggable={!ro.disabled} title={ro.title}
                onDragStart={(e) => e.dataTransfer.setData('text/plain', String(m.modelId))}
                style={{
                  display: 'flex', gap: 8, alignItems: 'center', padding: 6,
                  border: '1px solid #262c38', borderRadius: 6, cursor: 'grab',
                  background: '#12161d',
                }}>
                <PoolIcon m={m} />
                <div style={{ minWidth: 0, flex: 1 }}>
                  <div style={{ fontSize: 11, fontWeight: 600 }}>{m.name}</div>
                  <div style={{ fontSize: 10, color: '#8b93a7' }}>
                    {m.capability ?? '—'} · {t('island.pool.deployed', { n: m.deployed })}
                  </div>

                </div>
              </div>
            ))}
            {poolLoading && (
              <div style={{ fontSize: 11, color: '#8b93a7', padding: 8 }}>{t('island.loading')}</div>
            )}
            {!poolLoading && poolDevs.length === 0 && (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('island.empty.noDevice')} />
            )}
          </Space>
        </Card>
      </Col>

      {/* 右：岛 */}
      <Col flex="auto">
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Card size="small"
            title={<Space>
              <span>{t('island.title.island')}</span>
              {current && <Tag color="blue">{current.code}</Tag>}
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {t('island.islandInfo', { name: current?.name, n: devs.length })}
              </Typography.Text>
            </Space>}
            extra={<Space>
              <Select style={{ width: 240 }} value={current?.code}
                onChange={(v) => { setCode(v); setPicked(null); }}
                options={islands.map((i) => ({
                  value: i.code, label: `${i.code} ${i.name}（${i.deviceCount}）`,
                }))} />
              {boardOutside && (
                <Tag color="orange">{t('island.warn.deviceNotHere')}</Tag>
              )}
              <Button type="primary" ghost loading={demoRunning} {...ro} onClick={runFlow}>
                {t('island.hint.runToSeePlate')}
              </Button>
              <Button icon={<PlusOutlined />} {...ro} onClick={() => setNewIsland(true)}>{t('island.action.newIsland')}</Button>
              <Button danger disabled={ro.disabled || !current} title={ro.title} onClick={async () => {
                if (!current) return;
                if (!confirm(t('island.confirm.deleteIsland', { name: current.name }))) return;
                const r = await apiFetch(`/api/islands/${current.id}`, {
                  method: 'DELETE',
                });
                const j = await r.json();
                if (!r.ok) { message.error(j.error); return; }
                message.success(t('island.toast.deleted'));
                setCode(null);
                await qc.invalidateQueries({ queryKey: ['topology'] });
              }}>{t('island.action.deleteIsland')}</Button>
            </Space>}>
            {seq.length > 0 && (
              <div style={{ marginBottom: 10, padding: 8, borderRadius: 6,
                background: '#12161d', border: '1px solid #262c38' }}>
                <Space wrap>
                  <Typography.Text style={{ fontSize: 12 }}>{t('island.sequence.label')}</Typography.Text>
                  {seq.map((st, i) => (
                    <Space key={st.dev.resourceId} size={4}>
                      <Tag color="orange" closable style={{ marginInlineEnd: 0 }}
                        onClose={() => setSeq(seq.filter((x) => x.dev.resourceId !== st.dev.resourceId))}>
                        {i + 1}. {st.dev.code}
                      </Tag>
                      <Select
                        size="small" style={{ width: 190 }}
                        value={st.method || undefined}
                        placeholder={t('island.placeholder.pickMethod')}
                        onChange={(v) => {
                          const next = [...seq];
                          next[i] = { ...st, method: v };
                          setSeq(next);
                        }}
                        options={(devMethods[st.dev.resourceId] ?? []).map((m) => ({
                          value: m.method, label: m.method,
                        }))}
                      />
                    </Space>
                  ))}
                  <Button size="small" type="primary" loading={seqRunning} {...ro} onClick={runSeq}>
                    {t('island.action.runSeq')}
                  </Button>
                  <Button size="small" loading={savingSeq} {...ro} onClick={saveAsWorkflow}>
                    {t('island.action.saveAsFlow')}
                  </Button>
                  <Button size="small" onClick={() => setSeq([])}>{t('island.action.clearSeq')}</Button>
                </Space>
              </div>
            )}

            <div
              onDragOver={(e) => { e.preventDefault(); setDragOver(true); }}
              onDragLeave={() => setDragOver(false)}
              onDrop={(e) => {
                e.preventDefault(); setDragOver(false);
                if (ro.disabled) return;
                const mid = Number(e.dataTransfer.getData('text/plain'));
                if (mid) deployModelToIsland(mid);
              }}
              style={{
                minHeight: 460, height: '100%', borderRadius: 8, padding: 0,
                overflow: 'auto', position: 'relative',
                border: `2px dashed ${dragOver ? '#3b82f6' : '#262c38'}`,
                background: dragOver ? '#3b82f610' : 'transparent',
                transition: 'all .15s',
              }}
            >
              {topoErr && (
                <div style={{ background: '#7f1d1d', color: '#fff', padding: 8,
                  borderRadius: 4, marginBottom: 8, fontSize: 12 }}>
                  {t('island.error.topoLoad', { msg: topoErr })}
                  <button onClick={() => setTopoReload((x) => x + 1)}
                    style={{ marginLeft: 8, background: 'transparent', color: '#fff',
                      border: '1px solid #fff5', borderRadius: 3, cursor: 'pointer' }}>
                    {t('island.action.retry')}
                  </button>
                </div>
              )}
              {topoLoading && (
                <div style={{ fontSize: 12, color: '#8b93a7', marginBottom: 8 }}>{t('island.loading')}</div>
              )}
              {devs.length === 0 ? (
                <div style={{ textAlign: 'center', padding: '60px 0', color: '#5b6376' }}>
                  {t('island.hint.dragDevices')}
                </div>
              ) : (
                <div style={{ position: 'relative', width: '100%', height: '100%', minHeight: 420 }}>
                  {/* 搬运路径：从上一台选中设备到当前选中 */}
                  <svg style={{ position: 'absolute', inset: 0, pointerEvents: 'none' }}>
                    {picked && pathTo && pos[picked.resourceId] && pos[pathTo.resourceId] && (() => {
                      const a = pos[pathTo.resourceId];
                      const b = pos[picked.resourceId];
                      const mx = (a.x + b.x) / 2 + 70;
                      const my = (a.y + b.y) / 2 - 70;
                      const dist = Math.round(Math.hypot(b.x - a.x, b.y - a.y));
                      return (
                        <g>
                          <path d={`M${a.x + 70},${a.y + 24} Q${mx},${my} ${b.x + 70},${b.y + 24}`}
                            fill="none" stroke="#f59e0b" strokeWidth="2"
                            strokeDasharray="6 4" opacity="0.85" />
                          <text x={mx} y={my} fill="#f59e0b" fontSize="11" textAnchor="middle">
                            {t('island.distance', { n: dist })}
                          </text>
                          <circle cx={a.x + 70} cy={a.y + 24} r="4" fill="#f59e0b" />
                          <circle cx={b.x + 70} cy={b.y + 24} r="4" fill="#f59e0b" />
                        </g>
                      );
                    })()}
                  </svg>

                  {devs.map((d) => {
                    const color = CAP_COLOR[d.capability ?? ''] ?? '#64748b';
                    const active = picked?.resourceId === d.resourceId;
                    const p = pos[d.resourceId] ?? { x: 0, y: 0 };
                    return (
                      <div key={d.resourceId}
                        onMouseDown={(e) => startDragDevice(d.resourceId, e)}
                        onClick={() => {
                          if (picked && picked.resourceId !== d.resourceId) setPathTo(picked);
                          else setPathTo(null);
                          setPicked(d);
                        }}
                        style={{
                          position: 'absolute', left: p.x, top: p.y, width: 150,
                          cursor: 'grab', padding: 8, borderRadius: 8,
                          border: `1px solid ${active ? color : '#262c38'}`,
                          background: active ? `${color}14` : '#12161d',
                          userSelect: 'none',
                        }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                          <Space size={4}>
                            <DevIcon icon={d.icon} cap={d.capability} name={d.code} size={36} />
                            {/* 在线/离线/运行中：三种状态用三种颜色，一眼看出哪台掉线了 */}
                            <span title={
                                d.online === false ? t('island.device.offline')
                                  : d.status === 'running'
                                    ? t('island.device.running', { method: d.currentMethod ?? '' })
                                    : t('island.device.idle')
                              }
                              style={{
                                width: 8, height: 8, borderRadius: 4, display: 'inline-block',
                                background: d.online === false ? '#6b7280'
                                  : d.status === 'running' ? '#f59e0b'
                                  : d.status === 'error' ? '#ef4444' : '#22c55e',
                                boxShadow: d.online === false ? 'none'
                                  : `0 0 6px ${d.status === 'running' ? '#f59e0b' : '#22c55e'}`,
                              }} />
                          </Space>
                          <Tag color={color} style={{ marginInlineEnd: 0, fontSize: 10 }}>
                            {d.capability ?? '—'}
                          </Tag>
                        </div>
                        <div style={{ marginTop: 4, fontSize: 12, fontWeight: 600 }}>{d.code}</div>
                        <div style={{ fontSize: 10, color: '#8b93a7', overflow: 'hidden',
                          textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={d.name}>
                          {d.name}
                        </div>
                        <div style={{ marginTop: 2, fontSize: 10, fontFamily: 'monospace', color: '#5b6376' }}>
                          {d.ip ?? t('island.device.ipMissing')}
                        </div>
                        <Space size={6} style={{ marginTop: 2 }}>
                          <Button size="small" type="link"
                            style={{ padding: 0, height: 18, fontSize: 10 }}
                            onClick={(e) => { e.stopPropagation(); setPicked(d); }}>
                            {t('island.action.details')}
                          </Button>
                          <Button size="small" type="link"
                            style={{ padding: 0, height: 18, fontSize: 10 }}
                            onClick={(e) => { e.stopPropagation(); addToSeq(d); }}>
                            + {t('island.action.addToSeq')}
                          </Button>
                        </Space>
                      </div>
                    );
                  })}

                  {/* 板子：CSS transition 平滑移动 */}
                  {board && (
                    <div style={{
                      position: 'absolute', left: board.x, top: board.y,
                      width: 44, height: 30, borderRadius: 5,
                      background: 'linear-gradient(135deg, #f59e0b, #fbbf24)',
                      border: '2px solid #fff',
                      boxShadow: '0 0 18px 4px #f59e0bcc',
                      transition: 'left .8s cubic-bezier(.4,0,.2,1), top .8s cubic-bezier(.4,0,.2,1)',
                      zIndex: 999, pointerEvents: 'none',
                      display: 'flex', alignItems: 'center', justifyContent: 'center',
                      fontSize: 11, fontWeight: 800, color: '#7c2d12',
                    }} title={t('island.plate.label')}>PL</div>
                  )}
                </div>
              )}
            </div>
          </Card>

          {/* 设备详情 + 方法 + 动作 */}
          {picked && (
            <Modal open width={900} title={`${picked.code} · ${picked.name}`}
              onCancel={() => setPicked(null)}
              footer={<Button onClick={() => setPicked(null)}>{t('island.action.close')}</Button>}>
              <Descriptions size="small" column={4} style={{ marginBottom: 10 }}>
                <Descriptions.Item label={t('island.field.capability')}>
                  <Tag color={CAP_COLOR[picked.capability ?? '']}>{picked.capability ?? '—'}</Tag>
                </Descriptions.Item>
                <Descriptions.Item label={t('island.field.model')}>{picked.model ?? '—'}</Descriptions.Item>
                <Descriptions.Item label="IP">
                  <Space size={4}>
                    <span style={{ fontFamily: 'monospace', fontSize: 12 }}>
                      {picked.ip ?? t('island.device.ipNotSet')}
                    </span>
                    <Button size="small" type="link" style={{ padding: 0, fontSize: 11 }} {...ro}
                      onClick={async () => {
                        const v = prompt(t('island.prompt.setIp', { code: picked.code }), picked.ip ?? '');
                        if (v == null) return;
                        const r = await apiFetch(`/api/devices/${picked.resourceId}`, {
                          method: 'PUT',
                          headers: { 'Content-Type': 'application/json' },
                          body: JSON.stringify({ ip: v.trim() }),
                        });
                        if (r.ok) {
                          message.success(t('island.toast.ipUpdated'));
                          setTopoReload((x) => x + 1);
                          setPicked({ ...picked, ip: v.trim() });
                        } else {
                          message.error(t('island.error.updateFailed'));
                        }
                      }}>{t('island.action.change')}</Button>
                  </Space>
                </Descriptions.Item>
                <Descriptions.Item label={t('island.field.driverEndpoint')}>
                  <Typography.Text code style={{ fontSize: 10 }}>
                    {picked.driverEndpoint ?? t('island.device.noDriver')}
                  </Typography.Text>
                </Descriptions.Item>
              </Descriptions>

              {(() => {
                const mine = (positions.data?.items ?? [])
                  .filter((p) => p.parentDeviceId === picked.resourceId);
                if (mine.length === 0) return null;
                const used = mine.filter((p) => p.plateId || p.reserved).length;
                return (
                  <Card size="small" style={{ marginBottom: 12 }}
                    title={<Space>
                      <span>{t('island.plate.slot')}</span>
                      <Tag>{mine.length}</Tag>
                      <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                        {t('island.plate.usage', { used, free: mine.length - used })}
                      </Typography.Text>
                    </Space>}>
                    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                      {mine.map((p) => {
                        const color = p.plateId ? '#ec4899'
                          : p.reserved ? '#f59e0b' : '#22c55e';
                        const label = p.plateId ? p.plateId
                          : p.reserved ? t('island.plate.reserved') : t('island.plate.empty');
                        return (
                          <div key={p.resourceId}
                            title={`${p.code} · ${label}`
                              + (p.occupiedSince ? t('island.plate.since', { time: p.occupiedSince.slice(0, 19) }) : '')}
                            style={{
                              padding: '4px 8px', borderRadius: 4, fontSize: 10,
                              border: `1px solid ${color}66`, background: `${color}18`,
                              color, minWidth: 92, textAlign: 'center',
                            }}>
                            <div style={{ fontWeight: 600 }}>{p.code}</div>
                            <div style={{ fontFamily: 'monospace' }}>{label}</div>
                          </div>
                        );
                      })}
                    </div>
                    <Typography.Paragraph type="secondary"
                      style={{ fontSize: 10, marginBottom: 0, marginTop: 8 }}>
                      {t('island.plate.legend')}
                    </Typography.Paragraph>
                  </Card>
                );
              })()}

              {pathTo && (
                <Space style={{ marginBottom: 10 }}>
                  <Button size="small" {...ro} onClick={simulateTransfer}>
                    {t('island.simTransfer.title', { from: pathTo.code, to: picked.code })}
                  </Button>
                </Space>
              )}

              <Space style={{ marginBottom: 10 }} wrap>
                <Typography.Text style={{ fontSize: 12 }}>
                  {t('island.methods.label', { n: methods?.length ?? 0 })}
                </Typography.Text>
                {methods == null ? <Spin size="small" /> : (
                  <Select showSearch style={{ width: 260 }} size="small"
                    placeholder={t('island.placeholder.pickOneMethod')} value={method ?? undefined}
                    onChange={setMethod} optionFilterProp="label"
                    options={methods.map((m) => ({
                      value: m.method,
                      label: `${m.method}${m.params?.length ? t('island.method.params', { n: m.params.length }) : ''}`,
                    }))} />
                )}
                <Button type="primary" size="small" loading={firing}
                  disabled={ro.disabled || !method} title={ro.title} onClick={fire}>{t('island.action.fire')}</Button>
              </Space>

              {(curMethod?.params?.length ?? 0) > 0 && (
                <Space direction="vertical" size={6} style={{ width: '100%', marginBottom: 10 }}>
                  {curMethod!.params!.map((p) => (
                    <div key={p.name} style={{ maxWidth: 380 }}>
                      <Typography.Text style={{ fontSize: 11 }}>
                        {p.name}{p.unit ? <Typography.Text type="secondary">（{p.unit}）</Typography.Text> : null}
                      </Typography.Text>
                      {p.control_type === 'number' ? (
                        <InputNumber size="small" style={{ width: '100%' }}
                          value={pv[p.name] as number | undefined}
                          onChange={(v) => setPv({ ...pv, [p.name]: v })} />
                      ) : p.control_type === 'select' ? (
                        <Select size="small" style={{ width: '100%' }} allowClear
                          value={pv[p.name] as string | undefined}
                          onChange={(v) => setPv({ ...pv, [p.name]: v })}
                          options={(p.range ?? '').split(/[,，;；\n]/).filter(Boolean)
                            .map((o) => ({ value: o.trim(), label: o.trim() }))} />
                      ) : (
                        <Input size="small" value={pv[p.name] as string | undefined}
                          onChange={(e) => setPv({ ...pv, [p.name]: e.target.value })} />
                      )}
                    </div>
                  ))}
                </Space>
              )}

              {result && (
                <Alert type={result.ok ? 'success' : 'warning'} showIcon
                  message={result.summary}
                  description={
                    <Space direction="vertical" size={2}>
                      <Typography.Text style={{ fontSize: 11 }}>
                        {result.elapsedMs}ms
                        {result.contract && ` ｜ ${Object.entries(result.contract)
                          .map(([k, v]) => `${k}${v ? '✓' : '✗'}`).join(' ')}`}
                      </Typography.Text>
                      {result.rawResponse && (
                        <Typography.Paragraph style={{ fontSize: 10, fontFamily: 'monospace',
                          marginBottom: 0, maxHeight: 80, overflow: 'auto', wordBreak: 'break-all' }}>
                          {result.rawResponse}
                        </Typography.Paragraph>
                      )}
                    </Space>
                  } />
              )}
            </Modal>
          )}
        </Space>
      </Col>

      <Modal open={newDevice} title={t('island.modal.addDeviceTitle', { extra: current ? t('island.modal.addDeviceExtra', { name: current.name }) : '' })}
        width={520} onCancel={() => setNewDevice(false)} okText={t('island.modal.create')}
        onOk={async () => {
          const v = await deviceForm.validateFields();
          try {
            const r = await apiFetch('/api/devices', {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ ...v, islandId: current?.id }),
            });
            const j = await r.json();
            if (!r.ok) throw new Error(j.error);
            message.success(t('island.toast.deviceCreated'));
            setNewDevice(false);
            deviceForm.resetFields();
            setTopoReload((x) => x + 1);
            setPoolReload((x) => x + 1);
          } catch (e) {
            message.error(String((e as Error).message));
          }
        }}>
        <Form form={deviceForm} layout="vertical">
          <Space size={12} style={{ width: '100%' }}>
            <Form.Item name="code" label={t('island.field.deviceCode')} rules={[{ required: true }]}
              style={{ width: 200 }}>
              <Input placeholder="X900" />
            </Form.Item>
            <Form.Item name="name" label={t('island.field.deviceName')} rules={[{ required: true }]}
              style={{ width: 240 }}>
              <Input placeholder="Incubator_37C-1" />
            </Form.Item>
          </Space>
          <Space size={12} style={{ width: '100%' }}>
            <Form.Item name="capability" label={t('island.field.capability')} style={{ width: 200 }}
              extra={t('island.field.capabilityExtra')}>
              <Input placeholder="INCUBATE / TRANSFER / READ_PLATE" />
            </Form.Item>
            <Form.Item name="deviceType" label={t('island.field.deviceType')} style={{ width: 240 }}>
              <Input placeholder="Incubators" />
            </Form.Item>
          </Space>
          <Form.Item name="ip" label={t('island.field.ip')}
            extra={t('island.field.ipExtra')}>
            <Input placeholder="192.0.2.10" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal open={newIsland} title={t('island.modal.newIslandTitle')} onCancel={() => setNewIsland(false)}
        onOk={createIsland} okText={t('island.modal.create')}>
        <Form form={islandForm} layout="vertical">
          <Form.Item name="code" label={t('island.field.islandCode')} rules={[{ required: true }]}
            extra={t('island.field.islandCodeExtra')}>
            <Input placeholder="LAB-A" />
          </Form.Item>
          <Form.Item name="name" label={t('island.field.islandName')} rules={[{ required: true }]}>
            <Input placeholder={t('island.placeholder.islandName')} />
          </Form.Item>
        </Form>
      </Modal>
    </Row>
  );
}
