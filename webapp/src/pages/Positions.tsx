import { Fragment, useEffect, useMemo, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert, Button, Card, Drawer, Empty, Form, Input, InputNumber, Modal, Popconfirm, Select, Space, Switch, Table, Tag, Typography, message,
} from 'antd';
import { api, apiFetch, type PositionMethodBinding } from '../api/client';
import { useT, type MessageKey } from '../i18n';

interface Island { id: number; code: string; name: string; }
interface Device { resourceId: number; code: string; name: string; }
interface Position {
  resourceId: number; code: string; name: string; kind: string;
  plateId?: string | null; reserved: boolean;
  parentDeviceId: number; parentDeviceCode: string;
  pointRole?: string | null; robotReachable?: boolean | null;
  isEntry?: boolean | null; isExit?: boolean | null;
  contentKind?: string | null;
  contentCode?: string | null; contentName?: string | null;
  gridRow?: number | null; gridCol?: number | null;
  maxHeightMm?: number | null; plateTypes?: string | null;
  orderInDevice?: number | null;
  /** 现场贴的位置标识（空 = 回退显示系统编码 code）。 */
  positionLabel?: string | null;
  /** true = 网格之外的单独位置（培养箱/堆栈的进口/出口装载口）。 */
  standalone?: boolean | null;
  /**
   * 配置层主键 **device_point.id**。
   *
   * <p>⚠ 点位×方法绑定（position_method）用的就是这个 id，**不是** resourceId ——
   * 后端 BindingController 按 device_point.id 查，绑到 resourceId 会绑错行。
   */
  pointId?: number | null;
}

/**
 * 点位类型 → 文案 key。
 *
 * <p>机械臂只认 incubator / storage / transfer_point 三类（后端选位 SQL 硬过滤），
 * 所以批量造点位与点位表共用同一份词表。
 */
const KIND_KEYS: Record<string, MessageKey> = {
  storage: 'positions.kind.storage',
  incubator: 'positions.kind.incubator',
  transfer_point: 'positions.kind.transfer_point',
};
/** 角色 → 文案 key（进口/出口那套说法，批量造点位里的两个开关也用它）。 */
const ROLE_KEYS: Record<string, MessageKey> = {
  entry: 'positions.role.entry',
  exit: 'positions.role.exit',
  both: 'positions.role.both',
  internal: 'positions.role.internal',
};
/** 角色 → 标签颜色（展示约定，跟着角色走，文案在 i18n）。 */
const ROLE_COLORS: Record<string, string> = {
  entry: 'blue', exit: 'purple', both: 'green', internal: 'default',
};

/**
 * 点位×方法绑定（position_method）的作用域 / 分配方式词表。
 *
 * <p>这六个取值是**后端词表**（BindingController 的 SCOPE_KINDS / ALLOCATIONS），
 * 前端只做展示映射与下拉选项，不自己发明取值。
 */
const METHOD_SCOPE_KINDS = ['global', 'workflow', 'experiment'] as const;
type MethodScopeKind = (typeof METHOD_SCOPE_KINDS)[number];
const METHOD_SCOPE_KEYS: Record<MethodScopeKind, MessageKey> = {
  global: 'positions.method.scope.global',
  workflow: 'positions.method.scope.workflow',
  experiment: 'positions.method.scope.experiment',
};
const METHOD_ALLOCATIONS = ['explicit', 'auto_first_free', 'auto_next_free'] as const;
type MethodAllocation = (typeof METHOD_ALLOCATIONS)[number];
const METHOD_ALLOCATION_KEYS: Record<MethodAllocation, MessageKey> = {
  explicit: 'positions.method.allocation.explicit',
  auto_first_free: 'positions.method.allocation.auto_first_free',
  auto_next_free: 'positions.method.allocation.auto_next_free',
};

/** 设备方法清单里的一项（GET /api/devices/{resourceId}/methods）。 */
interface DeviceMethod {
  /**
   * device_method.id —— position_method.method_id 强制要它。
   *
   * <p>方法名字符串在同型号多实例下会重复，只有 id 唯一。
   */
  methodId?: number | null;
  method: string;
  name?: string | null;
  capability?: string | null;
}

/** 全岛矩阵的一行（GET /api/positions/method-matrix）。 */
interface MatrixRow {
  positionId: number;
  positionCode?: string | null;
  deviceCode?: string | null;
  /** null = 这个点位一条绑定都没有。 */
  bindingId?: number | null;
  methodName?: string | null;
  scopeKind?: string | null;
  scopeId?: number | null;
  purpose?: string | null;
  enabled?: boolean | null;
}

/** 绑定表单的值（加方法 / 编辑绑定共用一套字段）。 */
interface MethodFormValues {
  methodId?: number;
  scopeKind?: MethodScopeKind;
  scopeId?: number | null;
  purpose?: string;
  allocation?: MethodAllocation;
  priority?: number;
  enabled?: boolean;
}

/**
 * 坑位语义（slotKind ⇒ device_point.point_kind）——**与点位类型（pointKind）是两个轴**：
 * pointKind 说「这个节点是什么」（机械臂只认 incubator/storage/transfer_point），
 * slotKind 说「这个坑位在设备里扮演什么角色」。
 */
const SLOT_KINDS = ['nest', 'waste', 'deck_in', 'deck_out'] as const;
type SlotKind = (typeof SLOT_KINDS)[number];
/** 坑位语义 → 文案 key。 */
const SLOT_KIND_KEYS: Record<SlotKind, MessageKey> = {
  nest: 'positions.slotKind.nest',
  waste: 'positions.slotKind.waste',
  deck_in: 'positions.slotKind.deck_in',
  deck_out: 'positions.slotKind.deck_out',
};
/**
 * 这两种语义由**后端推导**点位角色与机械臂可达性
 * （deck_in ⇒ 内部位 + 不可达；deck_out ⇒ 进出共用 + 可达），
 * 所以界面上不用让用户再单独设那两个开关。
 */
const DECK_SLOT_KINDS: readonly string[] = ['deck_in', 'deck_out'];

/** 一行点位规格（按规格造点位用）。 */
interface SpecRow {
  /** React key（后端不认）。 */
  key: number;
  /** 点位编号（留空则由设备编码自动拼）。 */
  code: string;
  /** 位置标签（现场贴的那个）。 */
  label: string;
  slotKind: SlotKind;
  /** 槽位编号（留空 = 不指定）。 */
  slotIndex: number | null;
  pointKind: string;
}

/** 批量造点位的默认参数（转盘式提取仪 8 板位这一类最常见）。 */
const GRID_INITIAL = {
  cols: 8, rows: 1, pointKind: 'transfer_point',
  slotKind: 'nest', slotIndexBase: 1,
  maxStack: 1, maxHeightMm: 45, plateTypes: 'pcr96,deep96',
  isEntry: true, isExit: true, robotReachable: true,
};

export default function PositionsPage() {
  const t = useT();
  const qc = useQueryClient();
  const [islandId, setIslandId] = useState<number | undefined>();
  const [deviceId, setDeviceId] = useState<number | undefined>();
  const [editing, setEditing] = useState<Position | null>(null);
  const [form] = Form.useForm();
  // 单独位置不属于网格，勾上后禁用排/列输入（后端也会把两列清成 NULL）
  const isStandalone = Form.useWatch('standalone', form);
  const [createOpen, setCreateOpen] = useState(false);
  const [createForm] = Form.useForm();
  // 批量造网格点位
  const [gridForm] = Form.useForm();
  // 坑位语义：选 deck_in / deck_out 时，后端按语义推导角色与可达性 ⇒ 那三个开关不生效
  const gridSlotKind = Form.useWatch('slotKind', gridForm) as SlotKind | undefined;
  const gridIsDeck = !!gridSlotKind && DECK_SLOT_KINDS.includes(gridSlotKind);
  // 按规格造点位：一行一个点位规格（可增删）
  const [specRows, setSpecRows] = useState<SpecRow[]>([
    { key: 0, code: '', label: '', slotKind: 'nest', slotIndex: null, pointKind: 'transfer_point' },
  ]);
  const specKeyRef = useRef(0);

  // ---- 点位×方法绑定（position_method）----
  // 抽屉里配单个点位；矩阵卡片做全岛总览
  const [methodTarget, setMethodTarget] = useState<Position | null>(null);
  /** 正在编辑的绑定；null = 「加方法」模式。 */
  const [editingBinding, setEditingBinding] = useState<PositionMethodBinding | null>(null);
  const [methodForm] = Form.useForm<MethodFormValues>();
  // 作用域选了「全局」时不显示 scopeId 输入（global 作用域不认 scope_id）
  const [methodScopeKind, setMethodScopeKind] = useState<MethodScopeKind>('global');

  const islandsQ = useQuery({
    queryKey: ['topology'],
    queryFn: () => apiFetch('/api/topology').then((r) => r.json()) as Promise<{
      islands: (Island & { devices: Device[] })[];
    }>,
  });
  const islands = islandsQ.data?.islands ?? [];
  const devices = islands.find((i) => i.id === islandId)?.devices ?? [];

  const positionsQ = useQuery({
    queryKey: ['positions', islandId],
    queryFn: () => apiFetch(`/api/positions?islandId=${islandId}`).then((r) => r.json()) as Promise<{
      total: number; items: Position[];
    }>,
    enabled: islandId != null,
    refetchInterval: 8000,
  });
  const positions = positionsQ.data?.items ?? [];

  /**
   * 全岛点位×方法矩阵：一行一个绑定；**没绑过方法的点位也会有一行**
   * （bindingId / methodName 为 null）—— 这正是"一眼看出谁没绑"的来源。
   */
  const matrixQ = useQuery({
    queryKey: ['position-method-matrix', islandId],
    queryFn: () => api.positionMethodMatrix(islandId!),
    enabled: islandId != null && islandId > 0,
  });
  const matrixRows: MatrixRow[] = matrixQ.data?.items ?? [];

  /** 抽屉里那一个点位的绑定明细（positionId = device_point.id）。 */
  const bindingsQ = useQuery({
    queryKey: ['position-methods', methodTarget?.pointId],
    queryFn: () => api.positionMethods(methodTarget!.pointId!),
    enabled: methodTarget?.pointId != null,
  });
  const bindings = bindingsQ.data?.bindings ?? [];

  /**
   * 「加方法」下拉的数据源：点位**所属设备**的方法清单。
   *
   * <p>关联字段是点位的 parentDeviceId（= 设备 resource_node.id）——
   * 点位只能绑自己那台设备有的方法。
   */
  const deviceMethodsQ = useQuery({
    queryKey: ['device-methods', methodTarget?.parentDeviceId],
    queryFn: () => apiFetch(`/api/devices/${methodTarget!.parentDeviceId}/methods`)
      .then((r) => r.json()) as Promise<{ items?: DeviceMethod[] }>,
    enabled: methodTarget?.parentDeviceId != null,
  });
  const deviceMethods = deviceMethodsQ.data?.items ?? [];
  /** 下拉选项：值是 methodId（device_method.id），**不是**方法名字符串。 */
  const methodOptions = useMemo(
    () => deviceMethods
      .filter((m) => m.methodId != null)
      .map((m) => ({
        value: m.methodId as number,
        label: m.name ? `${m.method} · ${m.name}` : m.method,
      })),
    [deviceMethods],
  );

  // 每台设备的点位数（合并 topology 设备 + positions）
  const devWithCount = useMemo(() => devices.map((d) => ({
    ...d,
    count: positions.filter((p) => p.parentDeviceId === d.resourceId).length,
  })), [devices, positions]);

  /** 批量造点位要选设备：全岛设备平铺（带岛名，跨岛同名也分得清）。 */
  const allDevices = useMemo(
    () => islands.flatMap((i) => i.devices.map((d) => ({ ...d, islandId: i.id, islandName: i.name }))),
    [islands],
  );

  /** positionId → 该点位在矩阵里的所有行（未绑定的点位也会有一行）。 */
  const matrixByPoint = useMemo(() => {
    const m = new Map<number, MatrixRow[]>();
    for (const r of matrixRows) {
      const list = m.get(r.positionId);
      if (list) list.push(r);
      else m.set(r.positionId, [r]);
    }
    return m;
  }, [matrixRows]);

  /** 矩阵概览：多少点位、多少没绑任何方法（运行时调度不到）。 */
  const matrixStats = useMemo(() => {
    const points = new Set<number>();
    let unbound = 0;
    for (const r of matrixRows) {
      points.add(r.positionId);
      if (r.bindingId == null) unbound++;
    }
    return { points: points.size, unbound };
  }, [matrixRows]);

  const selectedPositions = useMemo(() => {
    if (deviceId == null) return [];
    const pts = positions.filter((p) => p.parentDeviceId === deviceId);
    // 单独位置不占网格，排在最后单独展示
    pts.sort((a, b) => Number(!!a.standalone) - Number(!!b.standalone)
      || (a.gridRow ?? 0) - (b.gridRow ?? 0) || (a.gridCol ?? 0) - (b.gridCol ?? 0));
    return pts;
  }, [positions, deviceId]);

  /** 网格之外的单独位置：培养箱/堆栈的进口/出口装载口。 */
  const standalonePositions = useMemo(
    () => selectedPositions.filter((p) => p.standalone), [selectedPositions]);
  /** 参与「几行几列」的点位（排除单独位置，避免它撑大网格维度）。 */
  const gridPositions = useMemo(
    () => selectedPositions.filter((p) => !p.standalone), [selectedPositions]);

  // 网格维度
  const grid = useMemo(() => {
    const maxRow = Math.max(1, ...gridPositions.map((p) => p.gridRow ?? 1));
    const maxCol = Math.max(1, ...gridPositions.map((p) => p.gridCol ?? 1));
    const hasGrid = gridPositions.some((p) => p.gridRow != null || p.gridCol != null);
    return { maxRow, maxCol, hasGrid };
  }, [gridPositions]);

  const openEdit = (p: Position) => {
    setEditing(p);
    form.setFieldsValue({
      pointRole: p.pointRole ?? 'both',
      robotReachable: p.robotReachable ?? true,
      isEntry: p.isEntry ?? true, isExit: p.isExit ?? true,
      orderInDevice: p.orderInDevice ?? 0,
      gridRow: p.gridRow ?? undefined, gridCol: p.gridCol ?? undefined,
      maxHeightMm: p.maxHeightMm ?? undefined, plateTypes: p.plateTypes ?? '',
      standalone: p.standalone ?? false,
      positionLabel: p.positionLabel ?? '',
      note: '',
    });
  };

  const save = async () => {
    const v = await form.validateFields();
    if (!editing) return;
    // 备注没填就别提交：后端对未提交的备注保持原值（否则每次编辑都会冲掉装载口备注）
    if (!v.note) delete v.note;
    try {
      await apiFetch(`/api/device-points/${editing.resourceId}`, {
        method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(v),
      });
      message.success('点位配置已保存');
      setEditing(null);
      qc.invalidateQueries({ queryKey: ['positions'] });
    } catch (e) { message.error(`保存失败：${(e as Error).message}`); }
  };

  /** 新建单独位置：编码 = 设备编码-后缀（默认 IN），同编码已存在时复用。 */
  const createStandalone = async () => {
    if (deviceId == null) return;
    const v = await createForm.validateFields();
    const mode = v.mode ?? 'both';
    try {
      const r = await apiFetch('/api/device-points/standalone', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          deviceResourceId: deviceId,
          positionLabel: v.positionLabel ?? '',
          codeSuffix: (v.codeSuffix || 'IN').trim(),
          isEntry: mode !== 'exit',
          isExit: mode !== 'entry',
        }),
      });
      const j = await r.json();
      if (!r.ok) throw new Error(j.error ?? '创建失败');
      message.success(`已创建单独位置 ${j.pointCode}`);
      setCreateOpen(false);
      qc.invalidateQueries({ queryKey: ['positions'] });
    } catch (e) { message.error(`创建失败：${(e as Error).message}`); }
  };

  /**
   * 批量造网格点位：POST /api/devices/{resourceId}/points/grid。
   *
   * <p>后端幂等：同编码复用并更新，所以重复执行不会造出第二个点位。
   * <p>板高留空就不发这个字段 —— 后端对板高做 Double.parseDouble，收到空串会直接炸。
   * <p>点位类型只能是 incubator / storage / transfer_point，后端会校验并回 400。
   * <p>坑位语义 slotKind 选 deck_in / deck_out 时，点位角色与机械臂可达性由**后端推导**
   * （界面上的进口/出口/机械臂可达开关对这两项不生效）。
   */
  const generateGrid = async () => {
    const v = await gridForm.validateFields();
    const dev = allDevices.find((d) => d.resourceId === v.deviceResourceId);
    if (!dev) {
      message.error(t('positions.grid.error.deviceRequired'));
      return;
    }
    const body: Record<string, unknown> = {
      deviceCode: dev.code,
      // 数值字段发**裸数字**（不带引号）：内核的 jsonInt / jsonDouble 带不带引号都认，
      // 统一发数字 —— 一来不用赌「哪个字段用哪种解析」，二来"按规格造点位"的槽位编号
      // 必须发数字（那边按 Number 读，带引号会读不出来）。
      cols: Number(v.cols),
      rows: Number(v.rows),
      pointKind: v.pointKind,
      // 坑位语义：deck_in / deck_out 的角色与可达性由后端按语义推导
      slotKind: v.slotKind ?? 'nest',
      // 槽位起始编号：后端按行优先写稳定的 1..N 槽号
      slotIndexBase: Number(v.slotIndexBase ?? 1),
      maxStack: Number(v.maxStack ?? 1),
      plateTypes: v.plateTypes ?? '',
      // 这三个是 JSON 布尔：后端按 body 里有没有 "isEntry":false 判断，不能发字符串
      isEntry: !!v.isEntry,
      isExit: !!v.isExit,
      robotReachable: !!v.robotReachable,
    };
    if (v.maxHeightMm != null) body.maxHeightMm = Number(v.maxHeightMm);
    try {
      const r = await apiFetch(`/api/devices/${dev.resourceId}/points/grid`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      const j = await r.json() as { created?: number };
      message.success(t('positions.grid.toast.created', { n: j.created ?? 0 }));
      // 点位列表按岛过滤 ⇒ 设备在别的岛时先把岛切过去，否则看不到刚造的点位
      setIslandId(dev.islandId);
      setDeviceId(dev.resourceId);
      qc.invalidateQueries({ queryKey: ['positions'] });
    } catch (e) {
      message.error(t('positions.grid.error.generate', { error: (e as Error).message }));
    }
  };

  /** 新一行点位规格（默认：中转位 / 网格式坑位，槽位编号留空）。 */
  const newSpecRow = (): SpecRow => {
    const key = ++specKeyRef.current;
    return { key, code: '', label: '', slotKind: 'nest', slotIndex: null, pointKind: 'transfer_point' };
  };
  const addSpecRow = () => setSpecRows((rows) => [...rows, newSpecRow()]);
  const removeSpecRow = (key: number) => setSpecRows((rows) => rows.filter((r) => r.key !== key));
  const updateSpecRow = (key: number, patch: Partial<SpecRow>) =>
    setSpecRows((rows) => rows.map((r) => (r.key === key ? { ...r, ...patch } : r)));

  /**
   * 按**规格**造点位：POST /api/devices/{resourceId}/points/spec。
   *
   * <p>给「一台设备多个不同工位」用（转盘式提取仪：外部交换位 + 内部操作位）——
   * 网格那套（几行几列）表达不了这种不均匀布局。
   * <p>后端幂等：同编码复用并更新，重复执行不会造出第二个点位。
   */
  const generateSpec = async () => {
    // 设备沿用上方网格表单里选的那台（同一个卡片、同一个设备上下文）
    const dev = allDevices.find((d) => d.resourceId === gridForm.getFieldValue('deviceResourceId'));
    if (!dev) {
      message.error(t('positions.spec.error.deviceRequired'));
      return;
    }
    if (specRows.length === 0) {
      message.error(t('positions.spec.error.empty'));
      return;
    }
    const points = specRows.map((r) => {
      const p: Record<string, unknown> = {
        code: r.code.trim(),
        label: r.label.trim(),
        slotKind: r.slotKind,
        pointKind: r.pointKind,
      };
      // 槽位编号：留空就**不带这个 key**（后端 slotIndex=null）；
      // 有值必须发**裸数字** —— 后端按 Number 读槽位编号，带引号会读不出来。
      if (r.slotIndex != null) p.slotIndex = r.slotIndex;
      return p;
    });
    try {
      const r = await apiFetch(`/api/devices/${dev.resourceId}/points/spec`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ deviceCode: dev.code, points }),
      });
      const j = await r.json() as { created?: number };
      message.success(t('positions.spec.toast.created', { n: j.created ?? 0 }));
      // 点位列表按岛过滤 ⇒ 设备在别的岛时先把岛切过去，否则看不到刚造的点位
      setIslandId(dev.islandId);
      setDeviceId(dev.resourceId);
      qc.invalidateQueries({ queryKey: ['positions'] });
    } catch (e) {
      // 后端 400 的原文（槽位语义非法 / 缺点位规格 …）直接显示给用户
      message.error(t('positions.spec.error.generate', { error: (e as Error).message }));
    }
  };

  // ---- 点位×方法绑定：抽屉的读写 ----

  /** 打开某个点位的「可用方法」抽屉。 */
  const openMethods = (p: Position) => {
    // ⚠ 绑定用的是 device_point.id（pointId），不是 resourceId
    if (p.pointId == null) {
      message.error(t('positions.method.error.noPointId'));
      return;
    }
    setMethodTarget(p);
    setEditingBinding(null);
  };

  /** 切到「加方法」模式。 */
  const startAdd = () => setEditingBinding(null);

  /** 切到「编辑绑定」模式（后端只允许改用途 / 分配方式 / 优先级 / 启用）。 */
  const startEdit = (b: PositionMethodBinding) => setEditingBinding(b);

  /**
   * 表单值跟着当前编辑对象走。
   *
   * <p>方法与作用域是**创建时**定的（后端 update 只改 purpose / allocation /
   * priority / enabled），所以编辑模式下这两项只读显示。
   */
  useEffect(() => {
    if (!methodTarget) return;
    if (editingBinding) {
      const scope = editingBinding.scopeKind as MethodScopeKind;
      setMethodScopeKind(METHOD_SCOPE_KEYS[scope] ? scope : 'global');
      methodForm.setFieldsValue({
        purpose: editingBinding.purpose ?? '',
        allocation: editingBinding.allocation,
        priority: editingBinding.priority,
        enabled: editingBinding.enabled,
      });
    } else {
      setMethodScopeKind('global');
      methodForm.setFieldsValue({
        methodId: undefined,
        scopeKind: 'global',
        scopeId: undefined,
        purpose: undefined,
        allocation: 'explicit',
        priority: 100,
      });
    }
  }, [methodTarget, editingBinding, methodForm]);

  /** 保存（新增或更新）一条绑定。 */
  const submitBinding = async () => {
    const pointId = methodTarget?.pointId;
    if (pointId == null) return;
    // 校验失败由 antd 在字段上标红，这里静默返回，别弹错误提示
    let v: MethodFormValues;
    try {
      v = await methodForm.validateFields();
    } catch {
      return;
    }
    try {
      if (editingBinding) {
        await api.updatePositionMethod(pointId, editingBinding.id, {
          purpose: v.purpose ?? null,
          allocation: v.allocation,
          priority: Number(v.priority ?? 100),
          enabled: !!v.enabled,
        });
        message.success(t('positions.method.toast.updated'));
      } else {
        if (v.methodId == null) {
          message.error(t('positions.method.error.methodRequired'));
          return;
        }
        await api.addPositionMethod(pointId, {
          methodId: Number(v.methodId),
          scopeKind: v.scopeKind,
          scopeId: v.scopeId ?? null,
          purpose: v.purpose ?? null,
          allocation: v.allocation,
          priority: Number(v.priority ?? 100),
        });
        message.success(t('positions.method.toast.added'));
      }
      setEditingBinding(null);
      qc.invalidateQueries({ queryKey: ['position-methods'] });
      qc.invalidateQueries({ queryKey: ['position-method-matrix'] });
    } catch (e) {
      // 后端 400 / 409 原文透给用户（例：绑定已存在）
      message.error(t(editingBinding ? 'positions.method.error.update' : 'positions.method.error.add',
        { error: (e as Error).message }));
    }
  };

  /** 删一条绑定。 */
  const removeBinding = async (b: PositionMethodBinding) => {
    const pointId = methodTarget?.pointId;
    if (pointId == null) return;
    try {
      await api.deletePositionMethod(pointId, b.id);
      message.success(t('positions.method.toast.deleted'));
      qc.invalidateQueries({ queryKey: ['position-methods'] });
      qc.invalidateQueries({ queryKey: ['position-method-matrix'] });
    } catch (e) {
      message.error(t('positions.method.error.delete', { error: (e as Error).message }));
    }
  };

  /** 某个点位已绑的方法标签（没绑过就标「未绑定」）。 */
  const boundTags = (p: Position) => {
    const rows = p.pointId == null ? [] : (matrixByPoint.get(p.pointId) ?? []);
    const bound = rows.filter((r) => r.bindingId != null);
    if (bound.length === 0) {
      return <Tag color="warning">{t('positions.method.matrix.unbound')}</Tag>;
    }
    return (
      <Space size={4} wrap>
        {bound.map((r) => (
          <Tag key={r.bindingId!} color={r.enabled === false ? 'default' : 'blue'}>{r.methodName}</Tag>
        ))}
      </Space>
    );
  };

  /** 作用域 → 文案（后端词表之外的值原样透出，不吞掉）。 */
  const scopeText = (v?: string | null) => {
    const k = v as MethodScopeKind;
    return v && METHOD_SCOPE_KEYS[k] ? t(METHOD_SCOPE_KEYS[k]) : (v ?? '—');
  };

  const cellText = (p?: Position) => {
    if (!p) return '';
    if (p.plateId) return `板 ${p.plateId}`;
    if (p.contentKind === 'consumable') return `耗材 ${p.contentCode ?? ''}`;
    if (p.contentKind === 'reagent') return `试剂 ${p.contentCode ?? ''}`;
    return '';
  };

  const occupiedColor = (p?: Position) => (p && (p.plateId || p.contentKind)) ? '#fff7e6' : undefined;

  /**
   * 网格之外的单独位置：培养箱/耗材堆栈的进口 + 出口。
   * 它不占 grid_row/grid_col，所以不能塞进上面那张网格图里 —— 单独一块展示，
   * 并显式打出「进口 / 出口」标记和位置标识（现场贴的标签，可改）。
   */
  const standaloneBlock = (
    <div style={{ marginTop: 16, paddingTop: 12, borderTop: '1px dashed #d9d9d9' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <Space size={6}>
          <Typography.Text strong>单独位置 · 进口 / 出口</Typography.Text>
          <Tag color="green">{standalonePositions.length}</Tag>
        </Space>
        <Button size="small" type="primary" ghost
          onClick={() => {
            createForm.setFieldsValue({ mode: 'both', positionLabel: '', codeSuffix: 'IN' });
            setCreateOpen(true);
          }}>新建单独位置</Button>
      </div>
      <div style={{ fontSize: 12, color: '#888', margin: '4px 0 8px' }}>
        不属于「几行几列」的网格：机械臂只在这里放板 / 取板。位置标识可改成现场贴的标签，系统编码不变。
      </div>
      {standalonePositions.length === 0 ? (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          该设备还没有单独位置：点右上角「新建单独位置」补一个（也可以把已有装载口在编辑弹窗里勾选为「单独位置」）。
        </Typography.Text>
      ) : (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
          {standalonePositions.map((p) => {
            const label = p.positionLabel || p.code;
            return (
              <div key={p.resourceId} onClick={() => openEdit(p)}
                style={{
                  minWidth: 190, padding: 10, borderRadius: 8, cursor: 'pointer',
                  border: '1px solid #91caff', background: occupiedColor(p) ?? '#f0f8ff',
                }}>
                <div style={{ fontWeight: 600 }}>{label}</div>
                {label !== p.code && (
                  <div style={{ fontFamily: 'monospace', fontSize: 11, color: '#888' }}>{p.code}</div>
                )}
                <Space size={4} wrap style={{ marginTop: 8 }}>
                  {p.isEntry && <Tag color="blue">进口</Tag>}
                  {p.isExit && <Tag color="purple">出口</Tag>}
                  {!p.isEntry && !p.isExit && <Tag>未标记进出口</Tag>}
                  {cellText(p) ? <Tag color="orange">{cellText(p)}</Tag> : <Tag>空</Tag>}
                </Space>
                <div style={{ marginTop: 6 }}>
                  <Button size="small" type="link" style={{ padding: 0, height: 18, fontSize: 11 }}
                    onClick={(e) => { e.stopPropagation(); openMethods(p); }}>
                    {t('positions.method.action.configure')}
                  </Button>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );

  return (
    <div style={{ padding: 16 }}>
      <Card size="small" title="位置管理"
        extra={<Space>
          <Select size="small" allowClear placeholder="选择岛" style={{ width: 200 }}
            value={islandId} onChange={(v) => { setIslandId(v); setDeviceId(undefined); }}
            options={islands.map((i) => ({ value: i.id, label: `${i.name}（${i.code}）` }))} />
          <Button size="small" onClick={() => qc.invalidateQueries({ queryKey: ['positions'] })}>刷新</Button>
        </Space>}>
        {islandId == null ? <Empty description="先选择一个岛" /> : (
          <div style={{ display: 'flex', gap: 16 }}>
            {/* 左：设备列表（全部 13 台） */}
            <div style={{ width: 240, flexShrink: 0 }}>
              <Typography.Text strong>设备（{devWithCount.length} 台）</Typography.Text>
              <div style={{ marginTop: 8, maxHeight: 600, overflow: 'auto' }}>
                {devWithCount.map((d) => (
                  <div key={d.resourceId}
                    onClick={() => {
                      setDeviceId(d.resourceId);
                      // 左侧选中的设备同步到批量造点位的表单，省一次再选
                      gridForm.setFieldsValue({ deviceResourceId: d.resourceId });
                    }}
                    style={{
                      padding: '6px 10px', cursor: 'pointer', borderRadius: 6, marginBottom: 4,
                      background: deviceId === d.resourceId ? '#e6f4ff' : '#fafafa',
                      border: deviceId === d.resourceId ? '1px solid #91caff' : '1px solid #eee',
                    }}>
                    <div><Typography.Text code>{d.code}</Typography.Text></div>
                    <div style={{ fontSize: 12, color: '#888' }}>{d.name}</div>
                    <Tag color={d.count > 0 ? 'green' : 'default'}>{d.count} 个点位</Tag>
                  </div>
                ))}
              </div>
            </div>

            {/* 右：选中设备的点位（网格或表格） */}
            <div style={{ flex: 1 }}>
              {deviceId == null ? <Empty description="点左侧选择一台设备" /> : (
                <>
                  {selectedPositions.length === 0 ? (
                    <Empty description="该设备尚未配置点位（可用下方「新建单独位置」补进出口，或用 gen-grid-points.py 生成网格）" />
                  ) : grid.hasGrid ? (
                      <div>
                        <Typography.Text strong>
                          {selectedPositions[0].parentDeviceCode} · {grid.maxCol} 列 × {grid.maxRow} 层
                        </Typography.Text>
                        <div style={{
                          display: 'grid', gridTemplateColumns: `repeat(${grid.maxCol}, 1fr)`,
                          gap: 6, marginTop: 8,
                        }}>
                          {Array.from({ length: grid.maxRow }).flatMap((_, r) =>
                            Array.from({ length: grid.maxCol }).map((_, c) => {
                              const p = gridPositions.find((x) => x.gridRow === r + 1 && x.gridCol === c + 1);
                              const label = p ? (p.positionLabel || p.code) : '—';
                              return (
                                <div key={`${r}-${c}`} onClick={() => p && openEdit(p)}
                                  title={p ? `${p.code}${p.positionLabel ? ' · ' + p.positionLabel : ''}${cellText(p) ? ' · ' + cellText(p) : ''}` : '空槽'}
                                  style={{
                                    minHeight: 52, padding: 6, borderRadius: 6, cursor: p ? 'pointer' : 'default',
                                    background: occupiedColor(p) ?? '#f5f5f5',
                                    border: '1px solid #e0e0e0', fontSize: 11,
                                  }}>
                                  <div style={{ fontWeight: 600 }}>{label}</div>
                                  {p?.positionLabel && p.positionLabel !== p.code && (
                                    <div style={{ fontFamily: 'monospace', fontSize: 10, color: '#888' }}>{p.code}</div>
                                  )}
                                  <div style={{ color: '#888' }}>{cellText(p)}</div>
                                  {p && (
                                    <Button size="small" type="link" style={{ padding: 0, height: 18, fontSize: 11 }}
                                      onClick={(e) => { e.stopPropagation(); openMethods(p); }}>
                                      {t('positions.method.action.configure')}
                                    </Button>
                                  )}
                                </div>
                              );
                            }))}
                        </div>
                      </div>
                    ) : gridPositions.length > 0 ? (
                      <Table<Position> size="small" rowKey="resourceId" dataSource={gridPositions}
                        pagination={false} columns={[
                          { title: '位置标识', key: 'label', width: 150, render: (_, p) => <Typography.Text code>{p.positionLabel || p.code}</Typography.Text> },
                          { title: '点位编码', dataIndex: 'code', width: 150, render: (v: string) => <Typography.Text code>{v}</Typography.Text> },
                          { title: '类型', dataIndex: 'kind', width: 90, render: (v: string) => (KIND_KEYS[v] ? t(KIND_KEYS[v]) : v) },
                          { title: '角色', dataIndex: 'pointRole', width: 90, render: (v: string) => <Tag color={ROLE_COLORS[v] ?? 'default'}>{ROLE_KEYS[v] ? t(ROLE_KEYS[v]) : (v ?? '—')}</Tag> },
                          { title: '占用', key: 'occ', render: (_, p) => cellText(p) ? <Tag color="orange">{cellText(p)}</Tag> : <Tag>空</Tag> },
                          { title: t('positions.method.field.boundMethods'), key: 'methods', render: (_, p) => boundTags(p) },
                          { title: '', key: 'op', width: 140, render: (_, p) => (
                            <Space size={0}>
                              <Button size="small" type="link" onClick={() => openEdit(p)}>编辑</Button>
                              <Button size="small" type="link" onClick={() => openMethods(p)}>
                                {t('positions.method.action.configure')}
                              </Button>
                            </Space>
                          ) },
                        ]} />
                    ) : (
                      <Empty description="该设备只有单独位置（见下方）" />
                    )}
                  {standaloneBlock}

                  {/* 点位可用方法：本设备的点位 → 绑了哪些方法（未绑的标出来） */}
                  <Card size="small" title={t('positions.method.title')} style={{ marginTop: 16 }}
                    extra={<Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      {t('positions.method.hint')}
                    </Typography.Text>}>
                    {selectedPositions.length === 0 ? (
                      <Empty description={t('positions.method.noPoints')} />
                    ) : (
                      <Table<Position> size="small" rowKey="resourceId" dataSource={selectedPositions}
                        pagination={false} columns={[
                          { title: t('positions.method.field.point'), key: 'p', width: 170,
                            render: (_, p) => <Typography.Text code>{p.positionLabel || p.code}</Typography.Text> },
                          { title: t('positions.method.field.boundMethods'), key: 'm', render: (_, p) => boundTags(p) },
                          { title: '', key: 'op', width: 90,
                            render: (_, p) => (
                              <Button size="small" onClick={() => openMethods(p)}>
                                {t('positions.method.action.configure')}
                              </Button>
                            ) },
                        ]} />
                    )}
                  </Card>
                </>
              )}
            </div>
          </div>
        )}
      </Card>

      <Card size="small" title={t('positions.grid.title')}
        extra={<Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {t('positions.grid.hint')}
        </Typography.Text>}>
        <Form form={gridForm} layout="vertical" initialValues={GRID_INITIAL}>
          <Space size={12} wrap align="start" style={{ width: '100%' }}>
            <Form.Item name="deviceResourceId" label={t('positions.grid.field.device')} style={{ width: 280 }}>
              <Select showSearch allowClear placeholder={t('positions.grid.placeholder.pickDevice')}
                optionFilterProp="label"
                options={allDevices.map((d) => ({
                  value: d.resourceId,
                  label: `${d.code}（${d.name}）· ${d.islandName}`,
                }))} />
            </Form.Item>
            <Form.Item name="cols" label={t('positions.grid.field.cols')} style={{ width: 120 }}>
              <InputNumber min={1} max={200} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="rows" label={t('positions.grid.field.rows')} style={{ width: 120 }}>
              <InputNumber min={1} max={200} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="pointKind" label={t('positions.grid.field.kind')} style={{ width: 180 }}>
              <Select options={[
                { value: 'transfer_point', label: t('positions.kind.transfer_point') },
                { value: 'storage', label: t('positions.kind.storage') },
                { value: 'incubator', label: t('positions.kind.incubator') },
              ]} />
            </Form.Item>
            <Form.Item name="slotKind" label={t('positions.grid.field.slotKind')} style={{ width: 200 }}
              tooltip={t('positions.grid.field.slotKindTooltip')}>
              <Select options={SLOT_KINDS.map((k) => ({ value: k, label: t(SLOT_KIND_KEYS[k]) }))} />
            </Form.Item>
            <Form.Item name="slotIndexBase" label={t('positions.grid.field.slotIndexBase')} style={{ width: 150 }}
              tooltip={t('positions.grid.field.slotIndexBaseTooltip')}>
              <InputNumber min={1} max={10000} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="maxStack" label={t('positions.grid.field.maxStack')} style={{ width: 140 }}>
              <InputNumber min={1} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="maxHeightMm" label={t('positions.grid.field.maxHeightMm')} style={{ width: 150 }}>
              <InputNumber min={0} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="plateTypes" label={t('positions.grid.field.plateTypes')} style={{ width: 200 }}>
              <Input placeholder="pcr96,deep96" />
            </Form.Item>
            <Form.Item name="isEntry" label={t('positions.grid.field.isEntry')} valuePropName="checked">
              <Switch disabled={gridIsDeck} />
            </Form.Item>
            <Form.Item name="isExit" label={t('positions.grid.field.isExit')} valuePropName="checked">
              <Switch disabled={gridIsDeck} />
            </Form.Item>
            <Form.Item name="robotReachable" label={t('positions.grid.field.robotReachable')} valuePropName="checked">
              <Switch disabled={gridIsDeck} />
            </Form.Item>
          </Space>
          {gridIsDeck && (
            <Alert type="info" showIcon style={{ marginBottom: 12 }}
              message={t('positions.grid.slotKind.derived')} />
          )}
          <Button type="primary" onClick={generateGrid}>{t('positions.grid.action.generate')}</Button>
        </Form>

        {/* 按规格造点位：同一张卡片里的**第二个**入口 —— 给「一台设备多个不同工位」用
            （转盘式提取仪：外部交换位 deck_out + 内部操作位 deck_in，网格那套表达不了）。 */}
        <div style={{ marginTop: 16, paddingTop: 12, borderTop: '1px dashed #d9d9d9' }}>
          <Typography.Text strong>{t('positions.spec.title')}</Typography.Text>
          <div style={{ fontSize: 12, color: '#888', margin: '4px 0 8px' }}>{t('positions.spec.hint')}</div>
          {specRows.length === 0 ? (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('positions.spec.empty')}</Typography.Text>
          ) : (
            <div style={{
              display: 'grid',
              gridTemplateColumns: 'minmax(140px, 1.3fr) minmax(110px, 1fr) minmax(190px, 1.7fr) 120px minmax(150px, 1.2fr) 44px',
              gap: 8, alignItems: 'start',
            }}>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('positions.spec.field.code')}</Typography.Text>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('positions.spec.field.label')}</Typography.Text>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('positions.spec.field.slotKind')}</Typography.Text>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('positions.spec.field.slotIndex')}</Typography.Text>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t('positions.spec.field.pointKind')}</Typography.Text>
              <span />
              {specRows.map((r) => (
                <Fragment key={r.key}>
                  <Input size="small" value={r.code} placeholder={t('positions.spec.placeholder.code')}
                    onChange={(e) => updateSpecRow(r.key, { code: e.target.value })} />
                  <Input size="small" value={r.label} placeholder={t('positions.spec.placeholder.label')}
                    onChange={(e) => updateSpecRow(r.key, { label: e.target.value })} />
                  <div>
                    <Select size="small" style={{ width: '100%' }} value={r.slotKind}
                      onChange={(v) => updateSpecRow(r.key, { slotKind: v as SlotKind })}
                      options={SLOT_KINDS.map((k) => ({ value: k, label: t(SLOT_KIND_KEYS[k]) }))} />
                    {/* deck_in / deck_out 的角色与可达性由后端推导 ⇒ 写清楚，免得用户以为漏设 */}
                    {r.slotKind === 'deck_in' && (
                      <div style={{ fontSize: 10, color: '#888', marginTop: 2 }}>{t('positions.slotKind.deckInDesc')}</div>
                    )}
                    {r.slotKind === 'deck_out' && (
                      <div style={{ fontSize: 10, color: '#888', marginTop: 2 }}>{t('positions.slotKind.deckOutDesc')}</div>
                    )}
                  </div>
                  <InputNumber size="small" style={{ width: '100%' }} min={1} value={r.slotIndex ?? undefined}
                    placeholder={t('positions.spec.placeholder.slotIndex')}
                    onChange={(v) => updateSpecRow(r.key, { slotIndex: typeof v === 'number' ? v : null })} />
                  <Select size="small" style={{ width: '100%' }} value={r.pointKind}
                    onChange={(v) => updateSpecRow(r.key, { pointKind: v })}
                    options={[
                      { value: 'transfer_point', label: t('positions.kind.transfer_point') },
                      { value: 'storage', label: t('positions.kind.storage') },
                      { value: 'incubator', label: t('positions.kind.incubator') },
                    ]} />
                  <Button size="small" type="text" danger title={t('positions.spec.action.removeRow')}
                    aria-label={t('positions.spec.action.removeRow')}
                    onClick={() => removeSpecRow(r.key)}>×</Button>
                </Fragment>
              ))}
            </div>
          )}
          <Space style={{ marginTop: 10 }}>
            <Button size="small" onClick={addSpecRow}>{t('positions.spec.action.addRow')}</Button>
            <Button type="primary" onClick={generateSpec}>{t('positions.spec.action.generate')}</Button>
          </Space>
        </div>
      </Card>

      {/* 全岛点位×方法矩阵总览：一眼看出哪些点位还没绑方法（运行时调度不到） */}
      <Card size="small" title={t('positions.method.matrix.title')} style={{ marginTop: 16 }}
        extra={<Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {t('positions.method.matrix.hint')}
        </Typography.Text>}>
        {islandId == null ? (
          <Empty description={t('positions.method.matrix.empty')} />
        ) : matrixQ.isError ? (
          <Alert type="error" showIcon
            message={t('positions.method.error.load', { error: (matrixQ.error as Error)?.message ?? '' })} />
        ) : matrixRows.length === 0 ? (
          <Empty description={t('positions.method.matrix.empty')} />
        ) : (
          <>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {t('positions.method.matrix.summary', { points: matrixStats.points, unbound: matrixStats.unbound })}
            </Typography.Text>
            <Table<MatrixRow> size="small" style={{ marginTop: 8 }}
              rowKey={(r) => `${r.positionId}-${r.bindingId ?? 'none'}`}
              dataSource={matrixRows} pagination={false} columns={[
                { title: t('positions.method.matrix.device'), dataIndex: 'deviceCode', width: 150 },
                { title: t('positions.method.matrix.position'), dataIndex: 'positionCode', width: 180,
                  render: (v: string | null) => <Typography.Text code>{v ?? '—'}</Typography.Text> },
                { title: t('positions.method.matrix.method'), dataIndex: 'methodName', width: 180,
                  render: (v: string | null) => (v
                    ? <Typography.Text code>{v}</Typography.Text>
                    : <Tag color="warning">{t('positions.method.matrix.unbound')}</Tag>) },
                { title: t('positions.method.matrix.scope'), dataIndex: 'scopeKind', width: 110,
                  render: (v: string | null) => scopeText(v) },
                { title: t('positions.method.matrix.purpose'), dataIndex: 'purpose',
                  render: (v: string | null) => v || '—' },
                { title: t('positions.method.matrix.status'), key: 'st', width: 100,
                  render: (_, r) => (r.bindingId == null
                    ? <Tag color="warning">{t('positions.method.matrix.unbound')}</Tag>
                    : r.enabled === false
                      ? <Tag>{t('positions.method.matrix.disabled')}</Tag>
                      : <Tag color="green">{t('positions.method.matrix.bound')}</Tag>) },
              ]} />
          </>
        )}
      </Card>

      {/* 点位×方法绑定抽屉：单个点位的增删改 */}
      <Drawer open={methodTarget != null} width={760}
        onClose={() => { setMethodTarget(null); setEditingBinding(null); }}
        title={t('positions.method.drawer.title', { code: methodTarget?.code ?? '' })}>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {t('positions.method.drawer.desc')}
        </Typography.Text>

        <Table<PositionMethodBinding> size="small" rowKey="id" dataSource={bindings}
          pagination={false} style={{ marginTop: 12 }}
          locale={{ emptyText: t('positions.method.empty') }}
          columns={[
            { title: t('positions.method.field.method'), dataIndex: 'methodName', width: 160,
              render: (v: string) => <Typography.Text code>{v}</Typography.Text> },
            { title: t('positions.method.field.scopeKind'), dataIndex: 'scopeKind', width: 100,
              render: (v: string) => <Tag>{scopeText(v)}</Tag> },
            { title: t('positions.method.field.scopeId'), dataIndex: 'scopeId', width: 90,
              render: (v: number | null) => v ?? '—' },
            { title: t('positions.method.field.purpose'), dataIndex: 'purpose',
              render: (v: string | null) => v || '—' },
            { title: t('positions.method.field.allocation'), dataIndex: 'allocation', width: 150,
              render: (v: string) => (METHOD_ALLOCATION_KEYS[v as MethodAllocation]
                ? t(METHOD_ALLOCATION_KEYS[v as MethodAllocation]) : v) },
            { title: t('positions.method.field.priority'), dataIndex: 'priority', width: 70 },
            { title: t('positions.method.field.enabled'), dataIndex: 'enabled', width: 90,
              render: (v: boolean) => (v
                ? <Tag color="green">{t('positions.method.matrix.bound')}</Tag>
                : <Tag>{t('positions.method.matrix.disabled')}</Tag>) },
            { title: '', key: 'op', width: 120, render: (_, b) => (
              <Space size={0}>
                <Button size="small" type="link" onClick={() => startEdit(b)}>
                  {t('positions.method.action.edit')}
                </Button>
                <Popconfirm title={t('positions.method.action.deleteConfirm')}
                  okText={t('positions.method.action.delete')}
                  cancelText={t('positions.method.action.cancel')}
                  onConfirm={() => removeBinding(b)}>
                  <Button size="small" type="link" danger>{t('positions.method.action.delete')}</Button>
                </Popconfirm>
              </Space>
            ) },
          ]} />

        {/* 加方法 / 编辑绑定：同一套字段，编辑时方法与作用域只读 */}
        <div style={{ marginTop: 20, paddingTop: 16, borderTop: '1px dashed #d9d9d9' }}>
          <Typography.Text strong>
            {editingBinding ? t('positions.method.form.editTitle') : t('positions.method.form.addTitle')}
          </Typography.Text>
          {!editingBinding && deviceMethodsQ.isError && (
            <Alert type="error" showIcon style={{ marginTop: 8, marginBottom: 12 }}
              message={t('positions.method.error.load', { error: (deviceMethodsQ.error as Error)?.message ?? '' })} />
          )}
          {!editingBinding && !deviceMethodsQ.isError && deviceMethods.length === 0 && (
            <Alert type="warning" showIcon style={{ marginTop: 8, marginBottom: 12 }}
              message={t('positions.method.noDeviceMethods')} />
          )}
          <Form form={methodForm} layout="vertical" style={{ marginTop: 8 }}>
            {editingBinding ? (
              <Alert type="info" showIcon style={{ marginBottom: 12 }}
                message={t('positions.method.form.editLocked')} />
            ) : (
              <>
                <Form.Item name="methodId" label={t('positions.method.field.method')}
                  rules={[{ required: true, message: t('positions.method.error.methodRequired') }]}>
                  <Select showSearch optionFilterProp="label"
                    placeholder={t('positions.method.placeholder.method')}
                    options={methodOptions} />
                </Form.Item>
                <Form.Item name="scopeKind" label={t('positions.method.field.scopeKind')}>
                  <Select options={METHOD_SCOPE_KINDS.map((k) => ({ value: k, label: t(METHOD_SCOPE_KEYS[k]) }))}
                    onChange={(v: MethodScopeKind) => {
                      setMethodScopeKind(v);
                      // 切回「全局」时清掉 scopeId，别把上一个流程 id 带过去
                      if (v === 'global') methodForm.setFieldValue('scopeId', undefined);
                    }} />
                </Form.Item>
                {methodScopeKind !== 'global' && (
                  <Form.Item name="scopeId" label={t('positions.method.field.scopeId')}
                    extra={t('positions.method.scopeIdHint')}>
                    <InputNumber min={1} style={{ width: '100%' }}
                      placeholder={t('positions.method.placeholder.scopeId')} />
                  </Form.Item>
                )}
              </>
            )}
            <Form.Item name="purpose" label={t('positions.method.field.purpose')}>
              <Input placeholder={t('positions.method.placeholder.purpose')} />
            </Form.Item>
            <Form.Item name="allocation" label={t('positions.method.field.allocation')}>
              <Select options={METHOD_ALLOCATIONS.map((a) => ({ value: a, label: t(METHOD_ALLOCATION_KEYS[a]) }))} />
            </Form.Item>
            <Form.Item name="priority" label={t('positions.method.field.priority')}>
              <InputNumber min={0} max={9999} style={{ width: '100%' }} />
            </Form.Item>
            {editingBinding && (
              <Form.Item name="enabled" label={t('positions.method.field.enabled')} valuePropName="checked">
                <Switch />
              </Form.Item>
            )}
            <Space>
              <Button type="primary" onClick={submitBinding}>
                {editingBinding ? t('positions.method.action.save') : t('positions.method.action.add')}
              </Button>
              {editingBinding && (
                <Button onClick={startAdd}>{t('positions.method.action.cancel')}</Button>
              )}
            </Space>
          </Form>
        </div>
      </Drawer>

      <Modal title={`编辑点位 · ${editing?.code ?? ''}`} open={editing != null}
        onCancel={() => setEditing(null)} onOk={save} okText="保存" cancelText="取消">
        <Form form={form} layout="vertical">
          <Form.Item name="standalone" label="单独位置（网格之外的进口 / 出口）"
            tooltip="培养箱、耗材堆栈除几行几列的存储格外，还有一个机械臂放板/取板的位置。勾上后不参与网格排布。"
            valuePropName="checked"><Switch /></Form.Item>
          <Form.Item name="positionLabel" label="位置标识"
            tooltip="现场贴的标签/编号；留空则页面显示系统编码。改它不会改点位编码。"
            extra={editing ? `系统编码：${editing.code}` : undefined}>
            <Input placeholder="如：X229 进出口 / IN-1" />
          </Form.Item>
          <Form.Item name="pointRole" label="角色" rules={[{ required: true }]}>
            <Select options={[
              { value: 'entry', label: t('positions.role.entry') },
              { value: 'exit', label: t('positions.role.exit') },
              { value: 'both', label: t('positions.role.both') },
              ...(isStandalone ? [] : [{ value: 'internal', label: t('positions.role.internalHint') }]),
            ]} />
          </Form.Item>
          <Form.Item name="robotReachable" label="机械臂可达" valuePropName="checked"><Switch /></Form.Item>
          <Form.Item name="isEntry" label="是进口" valuePropName="checked"><Switch /></Form.Item>
          <Form.Item name="isExit" label="是出口" valuePropName="checked"><Switch /></Form.Item>
          <Form.Item name="orderInDevice" label="设备内顺序"><Input type="number" /></Form.Item>
          <Form.Item name="gridRow" label="排（gridRow）"
            extra={isStandalone ? '单独位置不参与网格' : undefined}>
            <InputNumber min={0} disabled={!!isStandalone} />
          </Form.Item>
          <Form.Item name="gridCol" label="列（gridCol）"
            extra={isStandalone ? '单独位置不参与网格' : undefined}>
            <InputNumber min={0} disabled={!!isStandalone} />
          </Form.Item>
          <Form.Item name="maxHeightMm" label="最高板高（mm）"><InputNumber min={0} /></Form.Item>
          <Form.Item name="plateTypes" label="可放板型（逗号分隔）"><Input placeholder="pcr96,deep96" /></Form.Item>
          <Form.Item name="note" label="备注"><Input.TextArea rows={2} /></Form.Item>
        </Form>
      </Modal>

      <Modal title="新建单独位置（进口 / 出口）" open={createOpen}
        onCancel={() => setCreateOpen(false)} onOk={createStandalone}
        okText="创建" cancelText="取消">
        <Alert type="info" showIcon style={{ marginBottom: 12 }}
          message="网格之外的独立点位：机械臂只在这里放板 / 取板，不占「几行几列」。"
          description="编码 = 设备编码-后缀（默认 IN）；同编码已存在时复用并更新配置，重复创建不会造出第二个装载口。" />
        <Form form={createForm} layout="vertical">
          <Form.Item name="mode" label="进出口" rules={[{ required: true }]}>
            <Select options={[
              { value: 'both', label: '进口 + 出口（进出共用）' },
              { value: 'entry', label: '只进口' },
              { value: 'exit', label: '只出口' },
            ]} />
          </Form.Item>
          <Form.Item name="positionLabel" label="位置标识"
            tooltip="现场贴的标签/编号；留空则用系统编码。之后可在编辑弹窗里随时改。">
            <Input placeholder="如：X229 进出口" />
          </Form.Item>
          <Form.Item name="codeSuffix" label="编码后缀" extra="默认 IN；最终编码 = 设备编码-后缀">
            <Input placeholder="IN" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
