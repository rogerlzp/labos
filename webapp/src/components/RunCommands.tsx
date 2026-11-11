import { useQuery } from '@tanstack/react-query';
import { Table, Tag, Tooltip, Typography } from 'antd';
import { apiFetch } from '../api/client';
import { useT } from '../i18n';

interface CmdRow {
  id: number;
  commandSeq: number;
  method: string;
  status: string;
  deviceId: number | null;
  sentAt: string | null;
  ackAt: string | null;
  finishedAt: string | null;
  ackLatencyMs: number | null;
  errorCode: string | null;
  payload: string | null;
  result: string | null;
  nodeKey: string | null;
}

/** 把 ISO 时间戳裁成 "MM-DD HH:MM:SS"（列表里够看清先后，不必占宽度）。 */
function shortTime(v: string | null): string {
  if (!v) return '—';
  return v.slice(5, 19).replace('T', ' ');
}

/** 两次时刻之差（秒），用于显示"设备耗时"。 */
function spanSec(a: string | null, b: string | null): string {
  if (!a || !b) return '—';
  const d = (new Date(b).getTime() - new Date(a).getTime()) / 1000;
  return `${d.toFixed(1)}s`;
}

/**
 * 命令级台账：这次 run 给设备**下发过哪些命令**。
 *
 * <p>为什么要有：节点表只说"这一步成功了"，说不出
 * 「给哪台设备发了什么方法、带了什么参数、什么时候发的、等了多久才回、报了什么码」。
 * 现场查设备问题时，这张表就是不用翻日志也能对上的那份现场。
 *
 * <p>数据来自 {@code GET /api/runs/{id}/commands}（后端 {@code selectRunCommands}）：
 * `sentAt` 是**命令真正下发到设备的时刻**，`finishedAt` 是完成落库时刻，
 * 两者之差 = 设备侧耗时；`payload` 是实际下发的参数。
 */
export default function RunCommands({ runId }: { runId: number }) {
  const t = useT();
  const q = useQuery({
    queryKey: ['run-commands', runId],
    queryFn: () => apiFetch(`/api/runs/${runId}/commands`).then((r) => r.json()) as Promise<{
      total: number;
      commands: CmdRow[];
    }>,
    enabled: runId != null,
  });

  const rows = q.data?.commands ?? [];

  return (
    <Table<CmdRow>
      size="small" pagination={false} rowKey="id" loading={q.isLoading}
      dataSource={rows}
      locale={{ emptyText: <Typography.Text type="secondary">{t('runCommands.empty')}</Typography.Text> }}
      columns={[
        { title: t('runCommands.col.seq'), width: 50, render: (_, r) => r.commandSeq },
        { title: t('runCommands.col.node'), dataIndex: 'nodeKey', width: 110,
          render: (v: string | null) => v ?? '—' },
        { title: t('runCommands.col.method'), dataIndex: 'method', width: 120 },
        { title: t('runCommands.col.device'), dataIndex: 'deviceId', width: 80,
          render: (v: number | null) => (v == null ? '—' : `#${v}`) },
        { title: t('runCommands.col.status'), dataIndex: 'status', width: 90,
          render: (v: string) => (
            <Tag color={v === 'completed' ? 'green' : v === 'failed' ? 'red' : 'blue'}>
              {v}
            </Tag>
          ) },
        { title: t('runCommands.col.sentAt'), dataIndex: 'sentAt', width: 145,
          render: (v: string | null) => shortTime(v) },
        { title: t('runCommands.col.finishedAt'), dataIndex: 'finishedAt', width: 145,
          render: (v: string | null) => shortTime(v) },
        { title: t('runCommands.col.duration'), width: 90,
          render: (_, r) => spanSec(r.sentAt, r.finishedAt) },
        { title: t('runCommands.col.payload'), ellipsis: true,
          render: (v: string | null) => (
            v ? (
              <Tooltip title={<pre style={{ margin: 0, whiteSpace: 'pre-wrap' }}>{v}</pre>}>
                <code style={{ fontSize: 11 }}>{v}</code>
              </Tooltip>
            ) : '—'
          ) },
        { title: t('runCommands.col.error'), dataIndex: 'errorCode', width: 110,
          render: (v: string | null) => v ?? '—' },
      ]}
    />
  );
}
