import { useQuery } from '@tanstack/react-query';
import { Space, Table, Tag, Tooltip, Typography } from 'antd';
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
  /** 后端 keyValueArrayJson 产出的是**已解析的对象**（不是 JSON 字符串）⇒
   *  直接当 React child 渲染会崩（Objects are not valid as a React child）。 */
  payload: unknown;
  result: unknown;
  /** 设备回包原文。 */
  rawResponse?: unknown;
  /** 内置逻辑节点的**一句人话**（"等待 gate = open（超时 120 秒）—— 已等 12 秒"）。 */
  note?: string | null;
  nodeKey: string | null;
}

// 内置逻辑节点的方法名 → 中文标签（与只读流程页共用一份口径）
import { BUILTIN_LABEL } from '../bpmn/builtinLabels';

/**
 * 解析后端的时间戳。
 *
 * <p>后端 `java.sql.Timestamp` 序列化出来是 **"YYYY-MM-DD HH:MM:SS[.fff]"**
 * （空格分隔、**无时区**）⇒ 直接 `new Date(...)` 在部分浏览器里是 Invalid Date
 * ⇒ 耗时列会全变成「—」。⇒ 这里把空格换成 "T"（当成本地时间）再解析。
 */
function parseTime(v: string | null): number | null {
  if (!v) return null;
  const iso = v.includes('T') ? v : v.replace(' ', 'T');
  const t = new Date(iso).getTime();
  return Number.isFinite(t) ? t : null;
}

/**
 * 把可能是对象/字符串的字段转成可显示的文本。
 *
 * <p>后端的 payload 是 **jsonb**，取出来是**字符串**（`"{\"sec\": \"3\"}"`）。
 * 直接显示就是一串带反斜杠的转义 ⇒ 现场根本读不出来。
 * ⇒ 这里先试着 parse 成对象再美化；parse 不了就按原样显示。
 */
function asText(v: unknown): string {
  if (v == null) return '—';
  if (typeof v === 'string') {
    const t = v.trim();
    if (t.startsWith('{') || t.startsWith('[')) {
      try {
        return JSON.stringify(JSON.parse(t));
      } catch {
        return v;
      }
    }
    return v;
  }
  try {
    return JSON.stringify(v);
  } catch {
    return String(v);
  }
}

/** 把时间戳裁成 "MM-DD HH:MM:SS"（列表里够看清先后，不必占宽度）。 */
function shortTime(v: string | null): string {
  if (!v) return '—';
  return v.slice(5, 19).replace('T', ' ');
}

/** 两次时刻之差（秒），用于显示"设备耗时"。 */
function spanSec(a: string | null, b: string | null): string {
  const ta = parseTime(a);
  const tb = parseTime(b);
  if (ta == null || tb == null) return '—';
  return `${((tb - ta) / 1000).toFixed(1)}s`;
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
    // 台账必须**自己会刷新**。
    //
    // 为什么：以前这个组件只有一次性查询 ⇒ 执行中新下的命令、刚完成的节点，
    // 都要**手动刷整页**才看得见（2026-11-11 用户现场："每次需要刷新页面，
    // 才能看到执行完的节点命令"）。运行页每 2 秒刷一次也没用 ——
    // 刷的是节点表，台账是另一个查询，不会跟着重取。
    //
    // 跑完就停：否则一条历史 run 的页面会永远在轮询。
    refetchInterval: (st) => {
      const s = (st.state.data as { status?: string } | undefined)?.status;
      return s === 'running' || s === 'paused' ? 2000 : false;
    },
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
        { title: t('runCommands.col.method'), dataIndex: 'method', width: 120,
          render: (v: string) => (
            BUILTIN_LABEL[v]
              ? <Space size={4}>
                  <Tag color="purple">{BUILTIN_LABEL[v]}</Tag>
                  <Typography.Text type="secondary" style={{ fontSize: 11 }}>{v}</Typography.Text>
                </Space>
              : v
          ) },
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
        { title: t('runCommands.col.payload'), dataIndex: 'payload', ellipsis: true,
          render: (v: unknown) => {
            const text = asText(v);
            return text === '—' ? '—' : (
              <Tooltip title={<pre style={{ margin: 0, whiteSpace: 'pre-wrap' }}>{text}</pre>}>
                <code style={{ fontSize: 11 }}>{text}</code>
              </Tooltip>
            );
          } },
        { title: t('runCommands.col.note'), ellipsis: true,
          render: (_: unknown, r) => {
            // 内置逻辑节点的人话说明（"等待 gate = open（已等 12 秒）"）——
            // 没有它就不知道这个节点到底在做什么。
            const note = r.note ?? '';
            return note ? (
              <Tooltip title={<pre style={{ margin: 0, whiteSpace: 'pre-wrap' }}>{note}</pre>}>
                <Typography.Text style={{ fontSize: 12 }}>{note}</Typography.Text>
              </Tooltip>
            ) : '—';
          } },
        { title: t('runCommands.col.error'), dataIndex: 'errorCode', width: 110,
          render: (v: string | null) => v ?? '—' },
      ]}
    />
  );
}
