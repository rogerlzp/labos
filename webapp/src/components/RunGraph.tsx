import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Empty, Space, Tag, Typography } from 'antd';
import BpmnViewer from 'bpmn-js/lib/Viewer';
import 'bpmn-js/dist/assets/diagram-js.css';
import 'bpmn-js/dist/assets/bpmn-js.css';
import 'bpmn-js/dist/assets/bpmn-font/css/bpmn.css';
import { apiFetch } from '../api/client';
import { statusMeta, statusKnown, useStatusVocabulary } from '../utils/status';
import { useI18n, useT } from '../i18n';
import NodeAutoNameLabelRenderer, { NodeAutoNameShapeRenderer } from '../bpmn/NodeAutoNameRenderer';
import { isLocalizableAutoName } from '../bpmn/nodeRoleLabel';

/**
 * 一次执行的流程图：把 run_node 的状态画回 BPMN 图上。
 *
 * <p>为什么要有：运行详情只有一张表，看不出"图上有几步、卡在哪一步、
 * 后面哪几步压根没跑"。表格适合查细节，图适合一眼看全局，两个都要。
 *
 * <p>实现：bpmn-js 的 **Viewer**（只读，不是 Modeler —— 别让人在运行记录上改图），
 * 按 `nodeKey` 给元素打 marker 类，颜色由下面的 CSS 决定。
 * `node_key` 就是 BPMN 元素 id，所以能直接对上。
 */

export interface RunGraphNode {
  nodeKey: string;
  name?: string | null;
  status: string;
  errorCode?: string | null;
}

interface RunGraphData {
  runId: number;
  workflowId: number;
  runStatus: string;
  xml: string | null;
  nodes: RunGraphNode[];
}

/**
 * STYLE 里真正写了颜色的状态。
 *
 * <p>文案/颜色已收口到 utils/status.ts，这里只剩"CSS class 怎么拼"。词表新增但
 * STYLE 没写样式的状态（如 node.ready）退回 pending 的灰，不半透明乱染。
 */
const STYLED = new Set([
  'completed', 'failed', 'aborted', 'manual', 'skipped', 'running', 'pending', 'bypassed',
]);

/** 节点染色规则。bpmn-js 把 marker 类加在 `.djs-element` 上，形状是它的第一个子元素。 */
const STYLE = `
.rg-completed .djs-visual > :first-child { fill: #dcfce7 !important; stroke: #16a34a !important; }
.rg-failed    .djs-visual > :first-child { fill: #fee2e2 !important; stroke: #dc2626 !important; }
.rg-aborted   .djs-visual > :first-child { fill: #fee2e2 !important; stroke: #dc2626 !important; }
.rg-manual    .djs-visual > :first-child { fill: #ffedd5 !important; stroke: #ea580c !important; }
.rg-skipped   .djs-visual > :first-child { fill: #fef9c3 !important; stroke: #ca8a04 !important; }
.rg-running   .djs-visual > :first-child { fill: #dbeafe !important; stroke: #2563eb !important; }
.rg-pending   .djs-visual > :first-child { fill: #f1f5f9 !important; stroke: #94a3b8 !important;
                                              stroke-dasharray: 5 3 !important; }
/* 分支未选中：比"未执行"更淡，点线，一眼看出这条路没走 */
.rg-bypassed  .djs-visual > :first-child { fill: #f8fafc !important; stroke: #cbd5e1 !important;
                                              stroke-dasharray: 2 3 !important; opacity: .85; }
.rg-failed .djs-visual text, .rg-manual .djs-visual text { font-weight: 700; }
/* 起止节点：不是"任务"，不该和待执行混淆 —— 给它们固定的蓝/灰底。
   ⚠ end 原来是 #f8fafc（近白），和 rg-bypassed 的 #f8fafc 几乎一样 ——
   在浅色画布上「流程在哪结束」根本看不出来（2026-11-05 用户反馈：
   "结束哪里如果没有对应的结束标识，不容易看清楚"）。改成深底白字边 + 更粗的描边。 */
.rg-start .djs-visual > :first-child { fill: #e0f2fe !important; stroke: #0284c7 !important;
                                        stroke-width: 2px !important; }
.rg-end   .djs-visual > :first-child { fill: #334155 !important; stroke: #0f172a !important;
                                        stroke-width: 3px !important; }
/* 网关：两种都是菱形，缩小后分不出 —— 给「并行」描粗边 + 暖色，
   「异或」保持细边冷色，配合图上的名字，一眼能分辨。 */
.rg-parallel .djs-visual > :first-child { stroke-width: 3px !important; }
.rg-exclusive .djs-visual > :first-child { stroke-width: 1.5px !important; }
/* 汇合点：BPMN 惯例是汇合用**双线边框**（分叉单线）。用外描边模拟：
   加一圈半透明的深色外框，视觉上就是"双线"。 */
.rg-join .djs-visual > :first-child { stroke: #b45309 !important; }
.rg-join .djs-visual > :first-child {
  filter: drop-shadow(0 0 0 #b45309) drop-shadow(1px 1px 0 #b45309);
}
`;

/**
 * 只读的运行态流程图。
 *
 * <p>两种用法：
 * <ul>
 *   <li>{@code runId} —— 有这次执行：图按 **这次执行的状态**着色，并每 2 秒刷新；
 *       数据来自 {@code /api/runs/{id}/graph}（含 xml + nodes + run 状态）；</li>
 *   <li>只有 {@code workflowId}、没有 {@code runId} —— 这一步**失败在建执行单之前**
 *       （设备抢不到锁、展开失败等）⇒ 没有"这次执行"可着色 ⇒ 只画流程定义，
 *       并由调用方说明"这一步没有执行记录"。</li>
 * </ul>
 */
export default function RunGraph({ runId, workflowId }:
  { runId?: number; workflowId?: number }) {
  const vocab = useStatusVocabulary();
  const t = useT();
  const { locale } = useI18n();
  const holder = useRef<HTMLDivElement | null>(null);
  const viewerRef = useRef<BpmnViewer | null>(null);
  /** 已经打上去的 marker，换状态前要先清掉，否则一个节点会同时挂两个颜色类 */
  const applied = useRef<Array<{ key: string; cls: string }>>([]);
  const [data, setData] = useState<RunGraphData | null>(null);
  const [err, setErr] = useState<string | null>(null);

  // 轮询：跑到一半的时候图必须跟着变。
  //
  // 一开始写成"单子停了就停轮询"，结果 paused 时轮询停了 —— 用户点「继续」之后
  // run 变 running→completed，图却一直停在"待人工 1"，看着像没跑。
  // 一条小查询而已，挂着就一直轮（页面本身也是 2 秒刷一次）。
  useEffect(() => {
    let alive = true;

    const load = async () => {
      try {
        if (runId == null) {
          // 没有这次执行 ⇒ 只画流程定义（不着色）
          const r = await apiFetch(`/api/workflows/${workflowId}/graph`);
          const txt = await r.text();
          let xml = txt;
          try {
            const j = JSON.parse(txt) as { xml?: string };
            if (j && typeof j.xml === 'string') xml = j.xml;
          } catch { /* 不是 JSON 就是裸 XML */ }
          if (alive) setData({ runId: 0, workflowId: workflowId ?? 0, runStatus: '', xml, nodes: [] });
          return;
        }
        const r = await apiFetch(`/api/runs/${runId}/graph`);
        const j = (await r.json()) as RunGraphData;
        if (alive) setData(j);
      } catch (e) {
        if (alive) setErr(String((e as Error).message));
      }
    };

    load();
    // 没有 runId 时是一次性取图，不需要轮询
    const timer = runId == null ? undefined : setInterval(load, 2000);
    return () => {
      alive = false;
      if (timer) clearInterval(timer);
    };
  }, [runId, workflowId]);

  // 建 viewer + 导入 XML：**只在 XML 变化时**（轮询只更新 marker，不重建画布，否则会闪）
  useEffect(() => {
    if (!data?.xml || !holder.current) return;
    // 名字渲染：把库里存的**中性 code**（XOR_MERGE / __expr / ARM_TRANSFER 等）
    // 显示成本地化文案 —— 与设计器画布同一套机制（见 bpmn/NodeAutoNameRenderer.ts）。
    // 分两个渲染器是因为 bpmn-js 把名字画在两个位置：外置标签（网关）与
    // task 图形内部（内置逻辑节点）。
    // ⚠ 之前这里漏注册了渲染器 —— 角色名在运行图上一直是原文（英文界面下是中文）。
    const viewer = new BpmnViewer({
      container: holder.current,
      additionalModules: [{
        __init__: ['nodeAutoNameLabelRenderer', 'nodeAutoNameShapeRenderer'],
        nodeAutoNameLabelRenderer: ['type', NodeAutoNameLabelRenderer],
        nodeAutoNameShapeRenderer: ['type', NodeAutoNameShapeRenderer],
      }],
    } as any);
    viewerRef.current = viewer;
    let disposed = false;
    viewer.importXML(data.xml)
      .then(() => {
        if (disposed) return;
        const canvas = viewer.get('canvas') as {
          zoom: (z: string) => void;
          addMarker: (id: string, cls: string) => void;
        };
        canvas.zoom('fit-viewport');
        // 起止节点不是任务，没有执行状态；单独染一色，避免和"未执行"的灰虚线混在一起
        const registry = viewer.get('elementRegistry') as {
          forEach: (fn: (el: {
            id: string;
            type: string;
            /** incoming / outgoing 挂在 businessObject 上（bpmn-moddle 的关联属性），
             *  **不在 element 上** —— 读错位置恒为 0，汇合点就永远识别不出来。 */
            businessObject?: { incoming?: unknown[]; outgoing?: unknown[] };
          }) => void) => void;
        };
        registry.forEach((el) => {
          if (el.type === 'bpmn:StartEvent') canvas.addMarker(el.id, 'rg-start');
          if (el.type === 'bpmn:EndEvent') canvas.addMarker(el.id, 'rg-end');
          // 网关：两种都是菱形，靠形状分不出 —— 按类型给不同的描边粗细；
          // 入边 > 1 的按后端口径是「汇合」（WorkflowGraphParser 同规则），再加双线标记。
          if (el.type === 'bpmn:ParallelGateway') canvas.addMarker(el.id, 'rg-parallel');
          if (el.type === 'bpmn:ExclusiveGateway') canvas.addMarker(el.id, 'rg-exclusive');
          if ((el.type === 'bpmn:ParallelGateway' || el.type === 'bpmn:ExclusiveGateway')
              && (el.businessObject?.incoming?.length ?? 0) > 1) {
            canvas.addMarker(el.id, 'rg-join');
          }
        });
      })
      .catch((e) => { if (!disposed) setErr(t('runGraph.error.render', { err: String(e) })); });
    return () => {
      disposed = true;
      viewerRef.current = null;
      applied.current = [];
      try { viewer.destroy(); } catch { /* 卸载时销毁失败无所谓 */ }
    };
  }, [data?.xml]);

  // 语种切换后重画角色名标签（与设计器画布同一套机制，见 Designer.tsx）。
  //
  // 为什么需要：画布是 bpmn-js 的 SVG，不受 React 重渲染影响；角色名的显示
  // 文案是按**当前语种**查出来的 ⇒ 切语种后必须重画，否则画布上是旧语种的
  // 文案、页面上其它地方却是新语种。
  //
  // 只重画命中自动名（中性 code / 老数据中文名）的元素，不碰 marker 染色、
  // 不重建 viewer。
  //
  // ⚠ 要重画**两类**位置：外置标签（网关）与 task 图形本身（内置逻辑节点）——
  // 只重画标签的话，切语种后内置节点仍停在旧语种。
  useEffect(() => {
    const v = viewerRef.current;
    if (!v) return;
    const registry = v.get('elementRegistry') as {
      getAll: () => Array<{
        id: string;
        type?: string;
        businessObject?: { name?: string };
        labelTarget?: { businessObject?: { name?: string } };
      }>;
      getGraphics: (el: unknown) => SVGElement | undefined;
    };
    const graphicsFactory = v.get('graphicsFactory') as {
      update: (type: string, element: unknown, gfx: SVGElement) => void;
    };
    registry.getAll().forEach((el) => {
      const bo = el.type === 'label' ? el.labelTarget?.businessObject : el.businessObject;
      if (!isLocalizableAutoName(bo?.name)) return;
      const gfx = registry.getGraphics(el);
      if (gfx) graphicsFactory.update('shape', el, gfx);
    });
  }, [locale]);

  // 打 marker：每次状态变化重打一遍（先清先前的）
  const paint = useCallback((nodes: RunGraphNode[]) => {
    const canvas = viewerRef.current?.get('canvas') as {
      addMarker?: (id: string, cls: string) => void;
      removeMarker?: (id: string, cls: string) => void;
    } | undefined;
    if (!canvas?.addMarker) return;
    for (const m of applied.current) {
      try { canvas.removeMarker?.(m.key, m.cls); } catch { /* 元素没了就算了 */ }
    }
    const next: Array<{ key: string; cls: string }> = [];
    for (const n of nodes) {
      // CSS class 仍是 'rg-'+code；词表没定义 / STYLE 没写样式的退回 pending 灰
      const cls = statusKnown('node', n.status, vocab) && STYLED.has(n.status)
        ? `rg-${n.status}`
        : 'rg-pending';
      try {
        canvas.addMarker(n.nodeKey, cls);
        next.push({ key: n.nodeKey, cls });
      } catch {
        // 图上没有这个元素（节点后来被删了），跳过，不影响别的
      }
    }
    applied.current = next;
  }, [vocab]);

  useEffect(() => {
    if (data?.nodes) paint(data.nodes);
  }, [data?.nodes, paint]);

  if (err) {
    return <Alert type="warning" showIcon message={t('runGraph.error.load')} description={err} />;
  }
  if (!data) {
    return <Typography.Text type="secondary">{t('runGraph.loading')}</Typography.Text>;
  }
  if (!data.xml) {
    return (
      <Empty
        image={Empty.PRESENTED_IMAGE_SIMPLE}
        description={t('runGraph.noBpmn')}
      />
    );
  }

  const used = Array.from(new Set(data.nodes.map((n) => n.status)));

  return (
    <div>
      <style>{STYLE}</style>
      <Space size={6} wrap style={{ marginBottom: 8 }}>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {t('runGraph.legend')}
        </Typography.Text>
        {used.map((s) => {
          const m = statusMeta('node', s, vocab);
          const cnt = data.nodes.filter((n) => n.status === s).length;
          return <Tag key={s} color={m.color}>{m.label} {cnt}</Tag>;
        })}
      </Space>
      <div
        ref={holder}
        style={{ height: 380, background: '#fff', borderRadius: 6, border: '1px solid #262c38' }}
      />
    </div>
  );
}
