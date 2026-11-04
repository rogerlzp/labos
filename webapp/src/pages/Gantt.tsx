import { useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Card, Empty, Select, Space, Statistic, Tag, Typography } from 'antd';
import { apiFetch, api } from '../api/client';
import { statusHex, useStatusVocabulary } from '../utils/status';
import { useT } from '../i18n';

interface TimelineItem {
  id: number;
  nodeKey: string;
  name: string;
  status: string;
  level: number;
  parentNodeId: number | null;
  stepSeq: number | null;
  resourceId: number | null;
  offsetMs: number;
  durationMs: number;
}

interface Timeline {
  runId: number;
  startedAt: string;
  totalMs: number;
  items: TimelineItem[];
}

/**
 * 甘特：一个 run 的节点/命令时间轴。
 *
 * 用 SVG 而不是 frappe-gantt：我们的数据是「相对毫秒」（相对 run 开始），
 * frappe-gantt 要绝对日期，转来转去会引入时区问题，且暗色主题要改它一堆 CSS。
 * 相对时间的横道图本身只有几行坐标计算，用 SVG 更直接。
 */
export default function GanttPage() {
  // 横道颜色按节点状态（timeline 的 item.status 是 run_node 状态）
  const vocab = useStatusVocabulary();
  const t = useT();
  const [runId, setRunId] = useState<number | null>(null);

  const runs = useQuery({ queryKey: ['runs'], queryFn: () => api.runs(30) });

  // 默认选中最近一次执行：打开就能看到东西，不用先点下拉
  useEffect(() => {
    if (runId == null && runs.data?.items?.length) {
      setRunId(runs.data.items[0].id);
    }
  }, [runs.data, runId]);
  const tl = useQuery({
    queryKey: ['timeline', runId],
    queryFn: () => apiFetch(`/api/runs/${runId}/timeline`).then((r) => r.json()) as Promise<Timeline>,
    enabled: runId != null,
  });

  const data = tl.data;
  const items = data?.items ?? [];

  // 每一行一条横道：level=1 缩进，父节点用粗条
  const rows = useMemo(() => {
    const byParent = new Map<number, TimelineItem[]>();
    const roots: TimelineItem[] = [];
    for (const it of items) {
      if (it.parentNodeId == null) {
        roots.push(it);
      } else {
        if (!byParent.has(it.parentNodeId)) byParent.set(it.parentNodeId, []);
        byParent.get(it.parentNodeId)!.push(it);
      }
    }
    const out: Array<{ item: TimelineItem; indent: number }> = [];
    for (const r of roots) {
      out.push({ item: r, indent: 0 });
      for (const c of byParent.get(r.id) ?? []) out.push({ item: c, indent: 1 });
    }
    return out;
  }, [items]);

  const total = data?.totalMs ?? 0;
  const rowH = 24;
  const labelW = 200;
  const chartW = 640;
  const maxMs = Math.max(total, 1000);

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card
        size="small"
        title={t('gantt.title')}
        extra={
          <Select
            showSearch
            style={{ width: 320 }}
            placeholder={t('gantt.placeholder')}
            value={runId ?? undefined}
            onChange={setRunId}
            optionFilterProp="label"
            options={(runs.data?.items ?? []).map((r) => ({
              value: r.id,
              label: `#${r.id} ${r.runNo} ${r.status}`,
            }))}
          />
        }
      >
        {runId == null ? (
          <Empty description={t('gantt.empty.select')} />
        ) : items.length === 0 ? (
          <Empty description={t('gantt.empty.noTiming')} />
        ) : (
          <>
            <Space size={32} style={{ marginBottom: 12 }}>
              <Statistic title={t('gantt.stat.total')} value={(total / 1000).toFixed(1)} suffix={t('gantt.stat.seconds')} />
              <Statistic title={t('gantt.stat.items')} value={items.length} />
              <Statistic
                title={t('gantt.stat.device')}
                value={new Set(items.map((i) => i.resourceId).filter(Boolean)).size}
              />
            </Space>

            <svg
              width={labelW + chartW + 20}
              height={rows.length * rowH + 28}
              style={{ display: 'block' }}
            >
              {/* 时间刻度 */}
              {[0, 0.25, 0.5, 0.75, 1].map((p) => (
                <g key={p}>
                  <line
                    x1={labelW + p * chartW} y1={0}
                    x2={labelW + p * chartW} y2={rows.length * rowH + 8}
                    stroke="#262c38" strokeDasharray="2 4"
                  />
                  <text
                    x={labelW + p * chartW} y={rows.length * rowH + 22}
                    fill="#5b6376" fontSize={10} textAnchor="middle"
                  >
                    {(maxMs * p / 1000).toFixed(1)}s
                  </text>
                </g>
              ))}

              {rows.map(({ item, indent }, i) => {
                const y = i * rowH + 4;
                const x = labelW + (item.offsetMs / maxMs) * chartW;
                const w = Math.max(2, (item.durationMs / maxMs) * chartW);
                const color = statusHex('node', item.status, vocab);
                return (
                  <g key={item.id}>
                    <text
                      x={indent * 12} y={y + 11}
                      fill={indent ? '#8b93a7' : '#e6e9ef'} fontSize={11}
                    >
                      {indent ? '└ ' : ''}{item.name.slice(0, 16)}
                    </text>
                    <rect
                      x={x} y={y + 2} width={w} height={rowH - 10}
                      rx={2} fill={color} opacity={item.level === 0 ? 0.55 : 0.95}
                    />
                    {item.durationMs > 0 && (
                      <text x={x + w + 4} y={y + 12} fill="#5b6376" fontSize={9}>
                        {(item.durationMs / 1000).toFixed(1)}s
                      </text>
                    )}
                  </g>
                );
              })}
            </svg>

            <Typography.Paragraph type="secondary" style={{ fontSize: 11, marginTop: 8, marginBottom: 0 }}>
              {t('gantt.note.legend')}
              {t('gantt.note.color')}<Tag color="green">completed</Tag>
              <Tag color="red">failed</Tag>
              <Tag color="orange">manual</Tag>
              {t('gantt.note.source')}
            </Typography.Paragraph>
          </>
        )}
      </Card>
    </Space>
  );
}
