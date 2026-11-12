/**
 * 前端唯一的数据出入口。
 *
 * 契约先行的意义：后端字段改名时，这里编译不过，而不是页面静默显示 undefined。
 * 所有类型与 `labos-kernel` 的 JSON 输出一一对应，改动后端要同步改这里。
 */

// 状态词表类型放在 utils/status.ts：那里同时内置 FALLBACK 与 react-query hook，
// 页面消费的是"后端词表 + 兜底合并"后的结果，不是裸响应。type-only 引用不会造成运行期循环依赖。
import type { StatusVocabulary } from '../utils/status';
// 错误文案也要多语种：这里的异常 message 会直接显示在页面上。
import { translate } from '../i18n/core';

const BASE = import.meta.env.VITE_API_BASE ?? '';
const TOKEN_KEY = 'labos_token';

export const tokenStore = {
  get: () => localStorage.getItem(TOKEN_KEY),
  set: (t: string) => localStorage.setItem(TOKEN_KEY, t),
  clear: () => localStorage.removeItem(TOKEN_KEY),
};

/** 401 时清 token 并通知外层跳登录（用事件避免 client 依赖 router）。 */
function onUnauthorized() {
  tokenStore.clear();
  window.dispatchEvent(new Event('labos:unauthorized'));
}

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
  ) {
    super(message);
  }
}

/**
 * 带鉴权的裸 fetch。
 *
 * 页面里不能用裸 fetch('/api/...')：token 过期时只会拿到 401 的 JSON，
 * 页面表现为"空"，用户不知道要重新登录。这个函数会清 token 并通知 App。
 */
export async function apiFetch(path: string, init?: RequestInit): Promise<Response> {
  const token = tokenStore.get();
  const r = await fetch(`${BASE}${path}`, {
    ...init,
    headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(init?.headers ?? {}),
    },
  });
  if (r.status === 401) {
    onUnauthorized();
    throw new ApiError(401, 'unauthorized', translate('error.sessionExpired'));
  }

  // P1-23 修复（评审 20261102-03）：原来只拦 401，其余非 2xx **原样返回**。
  // 调用点普遍写成 `apiFetch(...).then(r => r.json())` —— 404/500 的
  // 错误 JSON 也能被解析成一个对象，页面当成"成功"照常渲染：
  // 列表显示空、保存提示"已保存"，用户以为一切正常。
  //
  // 现在非 2xx 一律抛 ApiError，把"响了但没做"变成"明确报错"。
  // 注意用 r.clone()：原 body 还要留给调用方读（有些地方要 r.text()）。
  if (!r.ok) {
    let msg = `HTTP ${r.status}`;
    try {
      const j = await r.clone().json() as { error?: unknown; message?: unknown };
      const detail = j?.error ?? j?.message;
      if (detail) msg = String(detail);
    } catch {
      // 非 JSON（比如网关返回 HTML）：保留状态码即可
    }
    throw new ApiError(r.status, 'http_error', msg);
  }
  return r;
}

async function req<T>(path: string, init?: RequestInit): Promise<T> {
  const token = tokenStore.get();
  const res = await fetch(BASE + path, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...init?.headers,
    },
  });

  if (res.status === 401) {
    onUnauthorized();
    throw new ApiError(401, 'unauthorized', translate('error.unauthorized'));
  }
  const text = await res.text();
  let body: unknown;
  try {
    body = JSON.parse(text);
  } catch {
    body = { raw: text };
  }

  // 后端有若干接口错误时仍返回 200（见审核待办），所以这里同时看 status 和 error 字段
  const err = body && typeof body === 'object' && 'error' in body
    ? String((body as { error: unknown }).error)
    : null;
  if (!res.ok || err) {
    throw new ApiError(res.status, err ? 'api_error' : 'http_error', err ?? `HTTP ${res.status}`);
  }
  return body as T;
}

// ---------------------------------------------------------------- 类型

/** 值守模式。无人值守下 MANUAL 升为 error 告警。 */
export type Attendance = 'attended' | 'unattended';

export type NodeStatus =
  | 'pending' | 'ready' | 'running'
  | 'completed' | 'failed' | 'skipped' | 'aborted' | 'manual';

export type RunStatus =
  | 'draft' | 'pending' | 'planned' | 'queued'
  | 'running' | 'paused'
  | 'completed' | 'failed' | 'aborted' | 'skipped';

export interface WorkflowSummary {
  id: number;
  code: string;
  name: string;
  category: string;
  nodeCount: number;
  /** 权威岛（`workflow.island_id`）：流程的执行边界。null = 未定岛（旧流程） */
  islandId?: number | null;
}

export interface WorkflowDetail {
  workflow: WorkflowSummary;
  tasks: Array<{
    name: string;
    capability: string;
    source: 'explicit' | 'protocol' | 'protocol-short' | 'heuristic' | 'logic' | 'unknown';
    method: string;
  }>;
  edgeCount: number;
}

/** 编译预览：一个业务节点展开成几步。 */
export interface CompileResult {
  workflow: { id: number; name: string };
  totalSteps: number;
  recipeNodes: number;
  nodes: Array<{
    nodeId: number;
    nodeKey: string;
    name: string;
    capability: string;
    capabilitySource: string;
    fromRecipe: boolean;
    sampleCount: number;
    stepCount: number;
    steps: Array<{ seq: number; method: string }>;
  }>;
}

export type IssueLevel = 'ERROR' | 'WARN' | 'INFO';

export interface PreflightIssue {
  level: IssueLevel;
  code: string;          // PF-01…PF-08
  nodeKey: string | null;
  message: string;
}

export interface PreflightReport {
  workflowId: number;
  workflowName: string;
  taskCount: number;
  reachableCount: number;
  defaultEdges: number;
  blocking: boolean;
  errors: number;
  warnings: number;
  issues: PreflightIssue[];
}

export interface RunNode {
  id: number;
  nodeKey: string;
  name: string;
  status: NodeStatus;
  attempt: number;
  resourceId: number | null;
  /** 设备编号（如 ID1183）。后端 JOIN resource_node 带出，比裸 id 好认 */
  resourceCode?: string | null;
  capabilityId?: number | null;
  estimateSec?: number;
  level: 0 | 1;                 // 0=业务节点 1=子步骤
  /** 循环圈次：0=汇总行（该节点最终状态），1..N=第几圈的实际执行 */
  loopIndex?: number;
  parentNodeId: number | null;
  stepSeq: number | null;
  reason: string | null;
  /** 内置逻辑节点的一句人话（"等待 gate = open（已等 12 秒）"）。
   *  没有它就只知道节点名「等待节点」，不知道它在等什么。 */
  note?: string | null;
  /** 中控错误码（如 EC-08-400C），后端从 run_node.error_code 带出 */
  errorCode?: string | null;
}

export interface RunEvent {
  occurredAt: string;
  type: string;
  payload: Record<string, unknown> & { seq?: number };
}

export interface RunDetail {
  run: {
    id: number;
    runNo: string;
    runType: string;
    status: RunStatus;
    priority: number;
    attendance: Attendance;
    actualStart: string | null;
    actualEnd: string | null;
    /** 暂停类型：paused_fault / waiting_human / paused_unknown */
    pauseKind?: string | null;
    pauseReason?: string | null;
    pauseNodeKey?: string | null;
  };
  nodes: RunNode[];
  events: RunEvent[];
}

export interface ExecuteResult {
  runId: number;
  workflowName: string;
  total: number;
  completed: number;
  manual: number;
  skipped: number;
  viaDriver: boolean;
  attendance: Attendance;
  eventsWritten: number;
  alerts: AlertItem[];
  notifyCount: number;
}

export interface AlertItem {
  kind: 'OPENED' | 'ESCALATED' | 'SUMMARY';
  fingerprint: string;      // 错误码|设备
  count: number;            // 同指纹下受影响的节点数
  severity: 'info' | 'warn' | 'error';
  lastOutcome: string;
  lastMessage: string;
  firstAt: string | null;
  lastAt: string | null;
  /** 处置状态，来自 alert_state */
  state: 'open' | 'acked' | 'closed' | 'silenced';
  assignee: string | null;
  note: string | null;
}

export interface ProcessStatusItem {
  runId: number;
  runNo: string;
  attendance: Attendance;
  startedAt: string;
  total: number;
  done: number;
  manual: number;
  current: {
    nodeKey: string;
    name: string;
    status: string;
    resourceId: number | null;
    reason: string;
    lastEvent: string;
  } | null;
}

export interface ProcessStatus {
  running: number;
  source?: 'memory' | 'db';
  items: ProcessStatusItem[];
}

export interface Policy {
  id?: number;
  errorCodeId: number | null;
  errorCode: string | null;
  scope: string | null;
  strategy: string;
  maxAttempts: number;
  backoff: string;
  cooldownMs: number;
  priority: number;
  enabled: boolean;
  origin?: string;
}

export interface PolicyValidation {
  errorCode: string;
  codeExists: boolean;
  historyHits: number;
  shadowedBy: number;
  ok: boolean;
  warning: string | null;
}

export interface Capability {
  code: string;
  name: string;
  devices: number;
}

// ---------------------------------------------------------------- 实验级运行控制

/** 现场扣留作用域：全场 / 岛 / 单实验（越内层越优先覆盖）。 */
export type HoldScope = 'site' | 'island' | 'experiment';

/** 现场扣留类型，与后端 `RuntimeHold.Kind.code()` 同口径。 */
export type HoldKind = 'fault' | 'plate_unknown' | 'human' | 'manual';

/** 一条生效的扣留（GET /api/runtime/holds）。 */
export interface RuntimeHoldItem {
  id: number;
  scope: HoldScope;
  scopeId: number | null;
  kind: HoldKind;
  reason: string | null;
  heldAt: string | null;
  heldBy: number | null;
}

export interface RuntimeHolds {
  anyActive: boolean;
  /** 读扣留失败(=UNKNOWN)：调度器已 fail-closed 阻断新派发，界面须显式提示（ADR-PAUSE-010）。 */
  unknown: boolean;
  unknownReason: string | null;
  holds: RuntimeHoldItem[];
}

export interface ExperimentPauseResult {
  runId: number; experimentId: number;
  status: string; scope: HoldScope; kind: string;
  /** S-04-B：暂停控制过程（PAUSE_REQUESTED / PAUSING / NONE）；受理时 status=accepted */
  controlState?: string | null;
}

export interface ExperimentStopResult {
  runId: number; experimentId: number;
  changed: boolean; status: string;
}

export interface ExperimentResumeResult {
  runId: number; experimentId: number;
  clearedHold: boolean; requeued: boolean;
  stillHeldReason: string | null; status: string;
}

// ---------------------------------------------------------------- S3 分批执行

/**
 * 一次批次尝试（experiment_batch_run）。
 *
 * <p>同一批重跑会新增一行，历史行不动 —— 这是"重跑不覆盖历史"的落库形态。
 * 字段与实施清单 §8.3 的 {@code runs[]} 一一对应，后端改名这里要编译不过。
 */
export interface ExperimentBatchAttempt {
  batchRunId: number;
  runId: number;
  attemptNo: number;
  status: RunStatus;
  startedAt: string | null;
  finishedAt: string | null;
  error: string | null;
}

/** 一个逻辑批次（experiment_batch）。 */
export interface ExperimentBatchItem {
  id: number;
  batchNo: number;
  sampleCount: number;
  /** 首次执行时的样品快照：重跑只用这份，样品集合不会漂移（D2） */
  samples: Array<{ sampleId: number; barcode: string }>;
  status: RunStatus;
  attemptCount: number;
  /** 该批最近一次尝试的 run；还没跑过为 null */
  currentRunId: number | null;
  runs: ExperimentBatchAttempt[];
}

/**
 * GET /api/experiments/{id}/batches 的响应。
 *
 * <p>{@code batchSplit} 为 null = 不分批（非分批实验），此时 items 为空数组，
 * 前端据此整个批次卡片不渲染 —— 非分批实验页面零变化（D1）。
 */
export interface ExperimentBatches {
  experimentId: number;
  batchSplit: number | null;
  items: ExperimentBatchItem[];
  summary: {
    total: number;
    pending: number;
    running: number;
    completed: number;
    failed: number;
  };
}

/** POST /api/experiments/{id}/batches/{batchNo}/run 的响应（只跑该批）。 */
export interface ExperimentBatchRunResult {
  experimentId: number;
  batchNo: number;
  runId: number;
  batchRunId: number;
  attemptNo: number;
  status: RunStatus;
  doneSteps: number;
  totalSteps: number;
  error: string | null;
}

/**
 * PUT /api/experiments/{id}/batch-split 的响应（S3.1 / D11）。
 *
 * <p>batchSplit 为 null = 不分批。注意：一旦建过批次（首次执行后）快照就固化了，
 * 后端会拒绝改这个值 —— 要改需先清批次数据（D2）。
 */
export interface ExperimentBatchSplitResult {
  experimentId: number;
  batchSplit: number | null;
  updated: boolean;
}

/**
 * POST /api/experiments/{id}/run?async=1 的响应（S3.2 分批异步，实施清单 §11.1）。
 *
 * <p>三种 200 形态（用 experimentRunId / held 区分）：
 * <ul>
 *   <li>已入队：{experimentRunId, batchNo, batchRunId, attemptNo, status:'queued', async:true}</li>
 *   <li>批边界被现场扣留（未建 attempt）：{experimentRunId:null, held:true, status:'paused', batchNo, holdReason}</li>
 *   <li>没有待执行批次：{experimentRunId:null, status:实验聚合状态}</li>
 * </ul>
 * 前端必须分开提示：held 不能当"已排队"（会被误以为系统会自己跑），
 * 无更多批也不能当失败。
 */
export type ExperimentAsyncRunResult =
  | {
      experimentId: number; experimentRunId: number; batchNo: number; batchRunId: number;
      attemptNo: number; status: string; async: true;
    }
  | {
      experimentId: number; experimentRunId: null; held: true; status: string;
      batchNo: number; holdReason: string | null;
    }
  | { experimentId: number; experimentRunId: null; status: string };

// ---------------------------------------------------------------- 并行调度可观测量（M9）

/**
 * 一条**资源占用**：某个实验正在占用的一台设备
 * （`GET /api/scheduler/status` 的 resourceLocks[]）。
 *
 * <p>2026-11-10 起不再是「岛锁」：调度粒度改成资源级后，一个岛可以同时有多个
 * 实验在跑（各用各的设备），值班员要看到的是「哪台设备被谁占着」。
 *
 * <p>为什么占用要带 `experimentNo`：值班员要看的是"离心机被 EXP0812 占着"，
 * 而不是"#100033 被 run#91 占着"——后者还得再查一次库才能判断该不该人工干预。
 */
export interface SchedulerResourceLock {
  /** 被占用的资源（设备/机械臂）的 resource_node.id */
  resourceId: number;
  /** 资源编码；联查不到时可能为 null（界面主显示名称，编码仅作 tooltip） */
  resourceCode: string | null;
  /** 资源名（界面主显示；联查不到时回退 resourceCode） */
  resourceName?: string | null;
  /** 占锁主体：实验级 run 的 id */
  experimentRunId: number;
  /** 后端 §7.1 示例里的历史别名（与 experimentRunId 同值）；前端只用后者 */
  runId?: number | null;
  experimentId: number;
  experimentNo: string;
  /** 占锁时刻（ISO 串；带 Z 时按 UTC 转本地显示） */
  since: string | null;
  /** 锁 TTL 到期时刻：到点内核会强制释放，用来判断"锁是不是漏放了" */
  expiresAt: string | null;
}

/** 排队中的一条实验（`GET /api/scheduler/status` 的 queue[]，已按派发顺序排好）。 */
export interface SchedulerQueueItem {
  /** 派发顺序，从 1 开始（priority DESC → deadline ASC → submittedAt ASC） */
  position: number;
  experimentId: number;
  experimentNo: string;
  experimentRunId: number;
  islandId: number | null;
  islandCode: string | null;
  islandName?: string | null;
  priority: number;
  deadline: string | null;
  submittedAt: string | null;
}

/** 执行线程池快照（回答"是池满了还是锁没释放"）。 */
export interface SchedulerWorkers {
  /** 池大小 */
  pool: number;
  /** 正在跑实验的 worker 数 */
  active: number;
  /** 池里还没被 worker 取走的任务数（当前恒 0，保留字段给未来队列化） */
  queue: number;
}

/** `GET /api/scheduler/status`：调度器自述"我在想什么"。 */
export interface SchedulerStatus {
  /** 正在占岛执行的实验数 */
  running: number;
  /** 排队等待派发的实验数 */
  queued: number;
  /** 同时运行的实验上限 */
  maxConcurrent: number;
  /** 队列非空但没有任何派发 = 有锁没释放 / 池满 / 调度线程停了，值班第一眼先看它 */
  stalled: boolean;
  /**
   * 累计派发次数（§7.1 契约，示例 `started: 41`）。
   *
   * <p>注意语义是**次数**，不是"是否已启动"——同名字段是 boolean，
   * 2026-11-03 起按 §7.1 改成计数；"循环起没起"看 {@link SchedulerStatus.loopStarted}。
   */
  started: number;
  /** 调度循环是否已启动（boolean 语义，向后兼容保留；内核可能没有该字段） */
  loopStarted?: boolean;
  lastDispatchAt: string | null;
  resourceLocks: SchedulerResourceLock[];
  queue: SchedulerQueueItem[];
  workers: SchedulerWorkers;
}

/** 一条实验预计等待（`GET /api/experiments/eta` 的 items[]）。 */
export interface ExperimentEtaItem {
  experimentId: number;
  experimentNo: string;
  /** 实验/运行状态（running / queued / …），决定前端显示"运行中"还是等待 */
  status: string;
  islandId: number | null;
  islandCode: string | null;
  /** 是否在调度队列里：只有排队中才有位次和等待估算 */
  queued: boolean;
  /** 队列位次，1 开始；不在队列为 null */
  queuePosition: number | null;
  /** 该实验自身预计耗时（秒），与甘特/时间条同一条估算链 */
  estimateSec: number | null;
  /** 预计等待秒数；null = 不适用（前端显示「—」，不要编数字） */
  estimatedWaitSec: number | null;
  /** 人话原因，前端 tooltip 直接展示 */
  reason: string | null;
}

/** `GET /api/experiments/eta` 响应。 */
export interface ExperimentEta {
  items: ExperimentEtaItem[];
}

// ---------------------------------------------------------------- 设备型号库元数据（M10）

/**
 * 已适配型号明细（Evidence=MANUAL；`GET /api/meta/device-models` 的 manual[]）。
 *
 * <p>为什么这些字段要上次界面：评审/现场问"这型号能不能接真机"，答案不是"能"，
 * 而是"手册哪一版、几条指令里几条没证实、动作超时手册写没写"——这些正是 M10 要看的。
 */
export interface DeviceModelManualItem {
  /** 型号 id（DeviceModels 的 key，如 aspin-2k） */
  id: string;
  /** 设备类型码：centrifuge / sealer …（状态词表不覆盖设备类型，前端按码显示） */
  type: string;
  vendor: string;
  model: string;
  /** 协议形态：xml-dll / binary-serial / json-tcp */
  protocol: string;
  /** 物理层说明（CAN 卡 / 串口 115200 8N1 …） */
  transport: string;
  /** 手册路径（仓库相对路径）；没有手册为 null */
  manual: string | null;
  /** 证据等级，本列表恒 MANUAL（有手册、逐条可核） */
  evidence: string;
  /** 门数（离心机 A/B 门）；null = 未定义 */
  doorCount: number | null;
  /** 手册给出的动作超时（毫秒）；0 = 手册没写 */
  actionTimeoutMs: number | null;
  /** 指令总数 */
  methodCount: number;
  /** 手册逐条可核的指令数 */
  documentedMethodCount: number;
  /** 手册没写、框架补的指令数（接真机必须现场确认） */
  unverifiedMethodCount: number;
  /** 错误码条数 */
  errorCount: number;
  /**
   * 错误码块（如 0x40 = 64），用于拼 EC-08-<块><字节>；
   * 后端按 int 输出，这里留 string 兼容"0x41"写法。
   */
  errorBlock: number | string | null;
  /** 接真机前必须确认的补充说明 */
  note: string | null;
}

/** 待手册型号（Evidence=PLANNED；`GET /api/meta/device-models` 的 planned[]）。 */
export interface DeviceModelPlannedItem {
  /** 型号 id（DeviceModels.Planned 的 key，如 hettich-rotanta-460r） */
  id: string;
  /** 设备类型码：centrifuge / sealer … */
  type: string;
  /** 厂商 + 型号（人读，如 Hettich Rotanta 460R（库里 model=360R）） */
  vendorModel: string;
  /** 缺什么（资料清单一句话） */
  missing: string;
  /** 库内识别名：device.model 里的值（如 360R / PlateLoc）；null = 库里还没有这台 */
  dbModelAlias: string | null;
  /** 产线上在跑的台数（device 表里 model = dbModelAlias 的实例数） */
  deployedCount: number;
}

/** `GET /api/meta/device-models` 响应：型号库适配状态（M10 前置）。 */
export interface DeviceModelsMeta {
  totals: {
    /** 有手册、可对接真机的型号数 */
    manual: number;
    /** 只有占位、缺手册的型号数 */
    planned: number;
    /** 缺手册但产线上已部署的设备台数（M10 阻塞量） */
    plannedDeployed: number;
  };
  manual: DeviceModelManualItem[];
  planned: DeviceModelPlannedItem[];
}

// ---------------------------------------------------------------- 接口

export const api = {
  health: () => req<{ status: string; db: string }>('/health'),

  /** S0-safety：调试通路开关状态（前端据此隐藏/显示 shadow-run / shadow/probe 调试入口）。 */
  debugSwitches: () =>
    req<{ shadowRunEnabled: boolean; deviceProbeEnabled: boolean }>('/api/meta/debug-switches'),

  login: (username: string, password: string) =>
    req<{ token: string; username: string; displayName: string; role: string }>(
      '/api/auth/login', {
        method: 'POST',
        body: JSON.stringify({ username, password }),
      },
    ),

  me: () => req<{ username: string; displayName: string; role: string }>('/api/auth/me'),

  logout: () => req<{ ok: boolean }>('/api/auth/logout', { method: 'POST' }),

  workflows: (limit = 100) =>
    req<{ items: WorkflowSummary[] }>(`/api/workflows?limit=${limit}`),

  workflow: (id: number) => req<WorkflowDetail>(`/api/workflows/${id}`),

  compile: (id: number) => req<CompileResult>(`/api/workflows/${id}/compile`),

  preflight: (id: number) => req<PreflightReport>(`/api/workflows/${id}/preflight`),

  /** 流程级选项：目前只有循环圈数上限（回边最多走几圈，1..100，后端默认 10）。 */
  workflowOptions: (id: number) =>
    req<{ maxIterations: number }>(`/api/workflows/${id}/options`),

  saveWorkflowOptions: (id: number, maxIterations: number) =>
    req<{ maxIterations: number }>(`/api/workflows/${id}/options`, {
      method: 'PUT',
      body: JSON.stringify({ maxIterations }),
    }),

  /** withSteps=true 时多步节点返回为可折叠 subProcess（只影响展示，不改库） */
  graphXml: async (id: number, withSteps = false) => {
    const token = tokenStore.get();
    const r = await fetch(`${BASE}/api/workflows/${id}/graph${withSteps ? '?steps=1' : ''}`, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    });
    if (r.status === 401) {
      onUnauthorized();
      throw new ApiError(401, 'unauthorized', translate('error.unauthorized'));
    }
    if (!r.ok) {
      throw new ApiError(r.status, 'graph', translate('error.loadGraph', { status: r.status }));
    }
    return r.text();
  },

  /**
   * 保存流程图。
   *
   * <p><b>P0-7 修复（评审 20261102-03）</b>：修复以下问题
   * <ul>
   *   <li><b>不带 Authorization</b> —— 鉴权打开后必然 401</li>
   *   <li><b>不校验 r.ok</b> —— 401/500 时 `.json()` 仍能解析出错误对象，
   *       调用方拿到"对象"就以为成功，界面提示"已保存"，
   *       <b>用户关掉页面才发现图没保存（数据丢失）</b></li>
   * </ul>
   *
   * <p>现在：带 token；非 2xx 一律抛 ApiError，让调用方的 catch 能提示失败。
   */
  saveGraph: async (id: number, xml: string, opts?: { draft?: boolean }) => {
    const token = tokenStore.get();
    // 自动化/编辑器中间态：拖入节点、自动插机械臂、删节点后还没补全整图，
    // 这时允许按 draft 落库；显式保存仍走完整图校验。
    const draft = opts?.draft === true || (typeof window !== 'undefined'
      && window.localStorage.getItem('labos.designer.draft') === '1');
    const url = `${BASE}/api/workflows/${id}/graph${draft ? '?draft=1' : ''}`;
    const r = await fetch(url, {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/xml',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: xml,
    });
    if (r.status === 401) {
      onUnauthorized();
      throw new ApiError(401, 'unauthorized', translate('error.unauthorizedGraph'));
    }
    if (!r.ok) {
      let detail = '';
      try { detail = (await r.text()).trim(); } catch { /* ignore */ }
      throw new ApiError(r.status, 'saveGraph',
        detail || translate('error.saveGraph', { status: r.status }));
    }
    return r.json();
  },

  /** 执行工艺。mode=unattended 以无人值守启动；async=1 立即返回 runId */
  execute: (id: number, opts?: { mode?: Attendance; async?: boolean }) => {
    const q = new URLSearchParams();
    if (opts?.mode) q.set('mode', opts.mode);
    if (opts?.async) q.set('async', '1');
    const s = q.toString();
    return req<ExecuteResult>(`/api/workflows/${id}/run${s ? '?' + s : ''}`, { method: 'POST' });
  },

  runs: (limit = 20) => req<{ items: RunSummary[] }>(`/api/runs?limit=${limit}`),

  run: (id: number) => req<RunDetail>(`/api/runs/${id}`),

  /** 暂停：kind=paused_fault|waiting_human|paused_unknown */
  pauseRun: (runId: number, kind: string, reason?: string, nodeKey?: string) => {
    const q = new URLSearchParams({ kind });
    if (reason) q.set('reason', reason);
    if (nodeKey) q.set('nodeKey', nodeKey);
    return req<{ ok: boolean; runId: number; pauseKind: string; releasedResources: number }>(
      `/api/runs/${runId}/pause?${q.toString()}`, { method: 'POST' },
    );
  },

  /** 恢复：action=continue|retry|skip|abort；corrections 用于板位校正 */
  resumeRun: (
    runId: number,
    action: string,
    corrections?: Record<string, string>,
  ) => req<{ ok: boolean; runId: number; action: string; correctedPlates: number; error?: string }>(
    `/api/runs/${runId}/resume?action=${action}`, {
      method: 'POST',
      body: JSON.stringify(corrections ? { corrections } : {}),
    },
  ),

  /** 当前所有暂停的执行 */
  pausedRuns: () => req<Array<{
    runId: number; runNo: string; pauseKind: string;
    pauseReason?: string; pauseNodeKey?: string; pausedAt?: string;
  }>>('/api/runs/paused'),

  // -------------------------------------------------------------- 实验级运行控制

  /**
   * 生效的现场扣留（实验级暂停的落库形态）。
   *
   * <p>为什么单独查：实验级暂停不是把某条 run 改成 paused 就完事，而是抬一道
   * {@code runtime_hold} 闸门 —— 被覆盖的实验都停在动作边界，且不释放板位。
   * 界面上必须能看到"现在被什么扣着、谁扣的"，否则用户只会看到实验莫名不跑。
   */
  runtimeHolds: () => req<RuntimeHolds>('/api/runtime/holds'),

  /**
   * 实验级暂停：抬一道现场扣留。
   *
   * <p>与流程级 {@link pauseRun} 是两套口径：那个停单条 run 并释放"已占未用"的预约，
   * 这个是现场闸门 —— 同岛实验一起停、板位原样保留。
   * 未分配岛的实验，岛级作用域会自动退化为实验级（后端 {@code effectiveScope}）。
   */
  pauseExperimentRun: (
    runId: number,
    opts?: { scope?: HoldScope; reason?: string },
  ) => {
    const q = new URLSearchParams();
    if (opts?.scope) q.set('scope', opts.scope);
    if (opts?.reason) q.set('reason', opts.reason);
    const s = q.toString();
    return req<ExperimentPauseResult>(
      `/api/experiments/runs/${runId}/pause${s ? '?' + s : ''}`, { method: 'POST' },
    );
  },

  /** 实验级停止：运行中只落停止请求（动作边界收尾）；非运行态直接 aborted */
  stopExperimentRun: (runId: number) =>
    req<ExperimentStopResult>(`/api/experiments/runs/${runId}/stop`, { method: 'POST' }),

  /**
   * 实验级恢复：解除覆盖该实验的最内层扣留，并把 run 拉回 queued，从断点续跑。
   *
   * <p>site 级扣留（全场事故）不会被单实验恢复顺手解除 —— 此时返回
   * {@code stillHeldReason}，界面要如实提示"先解现场扣留"，不能假装已恢复。
   */
  resumeExperimentRun: (runId: number, note?: string) => {
    const s = note ? `?note=${encodeURIComponent(note)}` : '';
    return req<ExperimentResumeResult>(
      `/api/experiments/runs/${runId}/resume${s}`, { method: 'POST' },
    );
  },

  /**
   * 分批实验的批次列表（S3）。
   *
   * <p>为什么批次要单独查：拆批后"执行历史"里每条 run 只是**某批的一次尝试**，
   * 只看 run 列表分不清"这批到底成没成、试过几次"。批次以 experiment_batch
   * 为主语展示才有可读性；{@code batchSplit} 为 null 时后端返回空 items，
   * 调用方据此隐藏整张卡片，非分批实验不受影响。
   */
  experimentBatches: (experimentId: number) =>
    req<ExperimentBatches>(`/api/experiments/${experimentId}/batches`),

  /**
   * 只重跑某一批（D4）。
   *
   * <p>不是重跑整个实验：已成功的批不动，历史尝试保留，新尝试递增 attempt_no。
   * 这样"第 2 批失败只补第 2 批"才是真的；后端只允许非 completed 批，
   * 前端按同一口径决定按钮是否出现。vars 与整实验执行同口径。
   */
  runExperimentBatch: (
    experimentId: number,
    batchNo: number,
    vars?: Record<string, string>,
  ) => req<ExperimentBatchRunResult>(
    `/api/experiments/${experimentId}/batches/${batchNo}/run`,
    { method: 'POST', body: JSON.stringify({ vars: vars ?? {} }) },
  ),

  /**
   * 设置/关闭分批（S3.1 / D11）。
   *
   * <p>`batchSplit` = 每批样品数（>0），null = 不分批。**已有批次后不可改**：
   * 首次执行时样品已快照固化（D2），改值会让同一实验出现两套切分口径，
   * 后端会回 400 并说明"需先清批次数据"。
   */
  setExperimentBatchSplit: (experimentId: number, batchSplit: number | null) =>
    req<ExperimentBatchSplitResult>(`/api/experiments/${experimentId}/batch-split`, {
      method: 'PUT',
      body: JSON.stringify({ batchSplit }),
    }),

  /**
   * 异步执行（S3.2）：分批实验"建下一批 run 入队、不阻塞页面"，调度器按批自动续队。
   *
   * <p>与 {@link runExp} 的同步执行（阻塞到所有批跑完）互补：同步适合当场看结果，
   * 异步适合长实验/排队；现场扣留时后端返回 held（不建 attempt），不会假排队。
   */
  runExperimentAsync: (experimentId: number, vars?: Record<string, string>) =>
    req<ExperimentAsyncRunResult>(`/api/experiments/${experimentId}/run?async=1`, {
      method: 'POST',
      body: JSON.stringify({ vars: vars ?? {} }),
    }),

  /** 启动前粗筛：资源够不够、被谁占、等多久 */
  canStart: (workflowId: number) => req<{
    canStart: boolean;
    strategy?: string;
    reason?: string;
    estimatedWaitSeconds?: number;
    needDeviceCount?: number;
    totalEstimateSec?: number;
    blocking?: Array<{
      runId: number; runNo: string; deviceCode: string;
      deviceInstanceId: number; etaSeconds: number;
    }>;
  }>(`/api/workflows/${workflowId}/can-start`),

  /** 位置未知/丢失的板 */
  unknownPlates: () => req<Array<{
    barcode: string; positionStatus: string; possibleLocations: number[];
  }>>('/api/plates/unknown'),

  /** 人工盘点 */
  locatePlate: (barcode: string, actualLocation: string) =>
    req<{ ok: boolean; barcode: string; actualLocation: string }>(
      `/api/plates/${encodeURIComponent(barcode)}/locate`, {
        method: 'POST',
        body: JSON.stringify({ actualLocation }),
      },
    ),

  setAttendance: (runId: number, mode: Attendance) =>
    req<{ updated: number; note: string }>(`/api/runs/${runId}/attendance?mode=${mode}`, {
      method: 'POST',
    }),

  processStatus: () => req<ProcessStatus>('/api/process-status'),

  /**
   * 状态词表（P2 收口）：后端是唯一权威，前端 8 个页面共用。
   *
   * <p>页面不要直接调它，用 utils/status.ts 的 useStatusVocabulary()（带 FALLBACK 兜底、
   * staleTime Infinity）；这里保留裸接口是为了"契约先行"——字段改名时编译不过。
   */
  statusVocabulary: () => req<StatusVocabulary>('/api/meta/status-vocabulary'),

  /**
   * 型号库适配状态（M10 前置）：哪些型号有手册能对真机、哪些只是占位、
   * 产线上有几台在跑却缺手册。
   *
   * <p>为什么是 meta 而不是 /api/device-models：那个是"设备池可部署型号"（写操作入口），
   * 这个只读、给人看适配证据，两者别混（见 docs/设备/M10-手册到型号库接入准备清单）。
   */
  metaDeviceModels: () => req<DeviceModelsMeta>('/api/meta/device-models'),

  /**
   * 调度器实时状态（M9 §7.1）：岛锁 / 排队明细 / worker 池。
   *
   * <p>页面 3 秒轮询用：调度循环本身 2 秒一轮，比它慢会错过刚派发的一轮；
   * 比它快只是徒增请求（内存态快照，没有更细的变化粒度）。
   */
  schedulerStatus: () => req<SchedulerStatus>('/api/scheduler/status'),

  /**
   * 实验预计等待（M9 §7.2）。
   *
   * <p>limit 限制条数：后端要按实验算节点估算，不是白拿；列表页与调度器页都取 300
   * 覆盖全量。`estimatedWaitSec` 为 null 表示不适用，前端显示「—」而不是 0。
   */
  experimentEta: (limit = 100) => req<ExperimentEta>(`/api/experiments/eta?limit=${limit}`),

  alerts: (limit = 60) => req<{ total: number; items: AlertItem[] }>(`/api/alerts?limit=${limit}`),

  alertAct: (
    fingerprint: string,
    action: 'ack' | 'close' | 'silence' | 'reopen',
    body?: { assignee?: string; note?: string },
  ) =>
    req<{ updated: number }>(`/api/alerts/${encodeURIComponent(fingerprint)}/${action}`, {
      method: 'POST',
      body: body ? JSON.stringify(body) : undefined,
    }),

  policies: () => req<{ items: Policy[] }>('/api/policies'),

  updatePolicy: (id: number, p: {
    strategy?: string; maxAttempts?: number; priority?: number; enabled?: boolean;
  }) => req<{ updated: number }>(`/api/policies/${id}`, {
    method: 'PUT',
    body: JSON.stringify(p),
  }),

  /** 生效校验：这条策略会不会永远匹配不到 */
  validatePolicy: (errorCode: string, priority: number) =>
    req<PolicyValidation>('/api/policies/validate', {
      method: 'POST',
      body: JSON.stringify({ errorCode, priority }),
    }),

  capabilities: () => req<{ items: Capability[] }>('/api/capabilities'),

  capabilityMethods: (code: string) =>
    req<{ capability: string; methods: string[] }>(`/api/capabilities/${code}/methods`),
};

export interface RunSummary {
  id: number;
  runNo: string;
  status: RunStatus;
  attendance: Attendance;
  nodeCount: number;
  startedAt: string | null;
  endedAt: string | null;
}
