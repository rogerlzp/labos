import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Empty, Space, Tag, Typography } from 'antd';
import BpmnViewer from 'bpmn-js/lib/Viewer';
import 'bpmn-js/dist/assets/diagram-js.css';
import 'bpmn-js/dist/assets/bpmn-js.css';
import 'bpmn-js/dist/assets/bpmn-font/css/bpmn.css';
import { apiFetch } from '../api/client';
import { statusMeta, statusKnown, useStatusVocabulary } from '../utils/status';
import { useT } from '../i18n';

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
/* 起止节点：不是"任务"，不该和待执行混淆 —— 给它们固定的蓝/灰底 */
.rg-start .djs-visual > :first-child { fill: #e0f2fe !important; stroke: #0284c7 !important; }
.rg-end   .djs-visual > :first-child { fill: #f8fafc !important; stroke: #475569 !important;
                                        stroke-width: 2px !important; }
`;

export default function RunGraph({ runId }: { runId: number }) {
  const vocab = useStatusVocabulary();
  const t = useT();
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
        const r = await apiFetch(`/api/runs/${runId}/graph`);
        const j = (await r.json()) as RunGraphData;
        if (alive) setData(j);
      } catch (e) {
        if (alive) setErr(String((e as Error).message));
      }
    };

    load();
    const timer = setInterval(load, 2000);
    return () => {
      alive = false;
      clearInterval(timer);
    };
  }, [runId]);

  // 建 viewer + 导入 XML：**只在 XML 变化时**（轮询只更新 marker，不重建画布，否则会闪）
  useEffect(() => {
    if (!data?.xml || !holder.current) return;
    const viewer = new BpmnViewer({ container: holder.current });
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
          forEach: (fn: (el: { id: string; type: string }) => void) => void;
        };
        registry.forEach((el) => {
          if (el.type === 'bpmn:StartEvent') canvas.addMarker(el.id, 'rg-start');
          if (el.type === 'bpmn:EndEvent') canvas.addMarker(el.id, 'rg-end');
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
