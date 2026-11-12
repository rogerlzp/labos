import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Card, Space, Table, Tag, Typography, message } from 'antd';
import { ApiError, apiFetch, tokenStore } from '../api/client';
import type { MessageKey } from '../i18n/core';
import { useT } from '../i18n';

/**
 * S2 物料身份与状态 · 测试用例台。
 *
 * <p>用例清单与脚本运行结果都是 public/ 下的数据文件（不是写死在组件里）：
 *   s2-test-cases.json    ← 用例定义，与 docs/页面实操验证报告-S2物料身份与状态-20261112.md 同步
 *   s2-test-results.json  ← scripts/verify-s2-material.py 跑完自动回填
 *
 * <p>T2 里标 browser 的用例可以**直接在本页点击运行**：在已登录的页面上下文里
 * 调后端接口并断言状态码/结构，结果即时回填并存 localStorage。标 script 的用例
 * （需要连库造数 / Playwright / 真实 barcode）由 Python 脚本跑，结果从 JSON 回填。
 */
type Status = 'pass' | 'fail' | 'skip' | 'idle' | 'running';

interface CaseDef {
  id: string;
  layer: 'T0' | 'T1' | 'T2';
  title: string;
  opinion: string;
  mode: 'browser' | 'script';
  note: string;
}

interface ScriptEntry {
  id: string | null;
  status: string;
  message: string;
  detail: string;
}

interface ScriptReport {
  generatedAt: string;
  command: string;
  pass: number;
  fail: number;
  skip: number;
  results: ScriptEntry[];
}

interface CaseFile { cases: CaseDef[] }
interface RunResult { status: Status; detail: string; at: string }
type Row = CaseDef & { script?: ScriptEntry; browser?: RunResult };

interface ComplexEvidence {
  runId: number;
  label: string;
  runStatus: string;
  declarations: string;
  effects: string;
  events: string;
  actions: string;
  q18Rows: string;
  manualErrors: string;
}
interface ComplexReport {
  generatedAt: string;
  command: string;
  pass: number;
  fail: number;
  skip: number;
  evidence: Record<string, ComplexEvidence>;
}

const STORAGE_KEY = 's2_browser_results';

const STATUS_KEY: Record<Status, MessageKey> = {
  pass: 's2.status.pass',
  fail: 's2.status.fail',
  skip: 's2.status.skip',
  idle: 's2.status.idle',
  running: 's2.status.running',
};

const STATUS_COLOR: Record<Status, string> = {
  pass: 'green', fail: 'red', skip: 'orange', idle: 'default', running: 'blue',
};

/** 带鉴权的探针：返回状态码与解析后的 body，不因 4xx 抛错（4xx 本身就是断言目标）。 */
async function probe(path: string, init?: RequestInit): Promise<{ status: number; data: unknown }> {
  try {
    const r = await apiFetch(path, init);
    const text = await r.text();
    try {
      return { status: r.status, data: JSON.parse(text) };
    } catch {
      return { status: r.status, data: text };
    }
  } catch (e) {
    if (e instanceof ApiError) return { status: e.status, data: { error: e.message } };
    return { status: -1, data: { error: String(e) } };
  }
}

const JSON_HEADERS = { 'Content-Type': 'application/json' };

/** 浏览器内可跑的断言。与 s2-test-cases.json 里 mode=browser 的 id 一一对应。 */
const checks: Record<string, (runId: number) => Promise<[boolean, string]>> = {
  'TC-P-01': async () => [Boolean(tokenStore.get()),
    tokenStore.get() ? 'token 存在' : '未登录'],

  'TC-P-02': async (runId) => {
    const r = await probe(`/api/runs/${runId}/material-gate`);
    const d = r.data as Record<string, unknown> | null;
    const ok = r.status === 200 && d != null && typeof d === 'object'
      && ['runId', 'blocked', 'blockerCount', 'effects'].every((k) => k in d);
    return [ok, `HTTP ${r.status} blockers=${String(d?.blockerCount ?? '-')}`];
  },

  'TC-P-03': async (runId) => {
    const r = await probe(`/api/runs/${runId}/material-effects`);
    return [r.status === 200 && Array.isArray(r.data),
      `HTTP ${r.status} ${Array.isArray(r.data) ? r.data.length + ' 条' : '非数组'}`];
  },

  'TC-P-04': async () => {
    const r = await probe('/api/material-effects?status=PENDING');
    return [r.status === 200 && Array.isArray(r.data),
      `HTTP ${r.status} ${Array.isArray(r.data) ? r.data.length + ' 条' : '非数组'}`];
  },

  'TC-P-07': async () => {
    const r = await probe('/api/material-effects/999999999/retry',
      { method: 'POST', headers: JSON_HEADERS, body: '{}' });
    return [r.status === 400, `HTTP ${r.status}（期望 400）`];
  },

  'TC-P-08': async () => {
    const r = await probe('/api/material-effects/999999999/retry',
      { method: 'POST', headers: JSON_HEADERS, body: JSON.stringify({ reason: '页面用例' }) });
    const d = r.data as Record<string, unknown> | null;
    const ok = (r.status === 200 || r.status === 409)
      && (r.status === 409 || (d != null && typeof d === 'object' && 'outcome' in d));
    return [ok, `HTTP ${r.status} outcome=${String(d?.outcome ?? '-')}`];
  },

  'TC-P-09': async () => {
    const r = await probe('/quarantined');
    return [r.status === 200, `HTTP ${r.status}`];
  },

  'TC-P-10': async () => {
    const r = await probe('/paused');
    return [r.status === 200, `HTTP ${r.status}`];
  },

  'TC-P-11': async (runId) => {
    const r = await probe(`/runs/${runId}`);
    return [r.status === 200, `HTTP ${r.status}`];
  },

  'TC-P-12': async (runId) => {
    const a = await probe(`/api/runs/${runId}/material-gate`);
    const b = await probe(`/api/runs/${runId}/material-effects`);
    const x = (a.data as { effects?: unknown[] } | null)?.effects?.length ?? -1;
    const y = Array.isArray(b.data) ? b.data.length : -1;
    return [x >= 0 && x === y, `gate.effects=${x} / list=${y}`];
  },

  'TC-P-14': async () => {
    const r = await probe('/api/plates?limit=5');
    const d = r.data;
    const ok = r.status === 200 && Array.isArray(d)
      && (d.length === 0 || ('barcode' in (d[0] as object)
        && 'processState' in (d[0] as object) && 'stateVersion' in (d[0] as object)));
    return [ok, `HTTP ${r.status} ${Array.isArray(d) ? d.length + ' 块板' : '非数组'}`];
  },

  'TC-P-15': async () => {
    const list = await probe('/api/plates?limit=1');
    const first = Array.isArray(list.data) ? list.data[0] as { barcode?: string } : undefined;
    if (!first?.barcode) return [false, '无可用板'];
    const r = await probe(`/api/plates/${encodeURIComponent(first.barcode)}/state-history?limit=5`);
    const d = r.data;
    const ok = r.status === 200 && Array.isArray(d)
      && (d.length === 0 || ('toStateVersion' in (d[0] as object)
        && 'recordedAt' in (d[0] as object) && typeof (d[0] as { recordedAt?: unknown }).recordedAt === 'string'));
    return [ok, `HTTP ${r.status} ${Array.isArray(d) ? d.length + ' 条事件' : '非数组'}`];
  },
};

function StatusTag({ status, detail }: { status?: Status; detail?: string }) {
  const t = useT();
  const s = status ?? 'idle';
  return <Tag color={STATUS_COLOR[s]} title={detail}>{t(STATUS_KEY[s])}</Tag>;
}

export default function S2TestCasesPage() {
  const t = useT();
  const qc = useQueryClient();
  const [running, setRunning] = useState(false);
  const [browserResults, setBrowserResults] = useState<Record<string, RunResult>>(() => {
    try {
      return JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '{}') as Record<string, RunResult>;
    } catch {
      return {};
    }
  });

  const cases = useQuery({
    queryKey: ['s2-test-cases'],
    queryFn: () => fetch('/s2-test-cases.json').then((r) => r.json() as Promise<CaseFile>),
    retry: false,
  });
  const report = useQuery({
    queryKey: ['s2-test-results'],
    queryFn: () => fetch('/s2-test-results.json').then((r) => {
      if (!r.ok) throw new Error('no report');
      return r.json() as Promise<ScriptReport>;
    }),
    retry: false,
  });

  const complex = useQuery({
    queryKey: ['s2-complex-flow'],
    queryFn: () => fetch('/s2-complex-flow-results.json').then((r) => {
      if (!r.ok) throw new Error('no complex report');
      return r.json() as Promise<ComplexReport>;
    }),
    retry: false,
  });

  const scriptMap: Record<string, ScriptEntry> = {};
  for (const r of report.data?.results ?? []) {
    if (r.id) scriptMap[r.id] = r;
  }

  const runBrowser = async () => {
    setRunning(true);
    const acc: Record<string, RunResult> = {};
    const put = (id: string, status: Status, detail: string) => {
      acc[id] = { status, detail, at: new Date().toLocaleString() };
      setBrowserResults({ ...acc });
    };
    try {
      const r = await probe('/api/runs?limit=1');
      const items = (r.data as { items?: { id: number }[] } | null)?.items ?? [];
      const runId = items[0]?.id ?? 0;
      for (const c of (cases.data?.cases ?? []).filter((x) => x.mode === 'browser')) {
        put(c.id, 'running', '');
        const fn = checks[c.id];
        if (!fn) {
          put(c.id, 'skip', '未定义浏览器断言');
          continue;
        }
        try {
          const [ok, detail] = await fn(runId);
          put(c.id, ok ? 'pass' : 'fail', detail);
        } catch (e) {
          put(c.id, 'fail', String(e));
        }
      }
      localStorage.setItem(STORAGE_KEY, JSON.stringify(acc));
      message.success(t('s2.toast.done'));
    } finally {
      setRunning(false);
    }
  };

  const copyCommand = async () => {
    const cmd = 'cd /Users/zhengpingli/work/中控 && python3 scripts/verify-s2-material.py';
    try {
      await navigator.clipboard.writeText(cmd);
      message.success(t('s2.toast.copied'));
    } catch {
      message.info(cmd);
    }
  };

  const refresh = () => {
    void qc.invalidateQueries({ queryKey: ['s2-test-results'] });
    void qc.invalidateQueries({ queryKey: ['s2-test-cases'] });
  };

  const rows: Row[] = (cases.data?.cases ?? []).map((c) => ({
    ...c,
    script: scriptMap[c.id],
    browser: browserResults[c.id],
  }));

  const browserTotal = rows.filter((r) => r.mode === 'browser').length;
  const browserPass = rows.filter((r) => r.browser?.status === 'pass').length;

  const summary = report.data
    ? t('s2.summary', { pass: report.data.pass, fail: report.data.fail, skip: report.data.skip })
      + ' · ' + t('s2.reportAt', { at: report.data.generatedAt })
    : t('s2.noReport');

  return (
    <Card
      title={t('s2.title')}
      extra={(
        <Space wrap>
          <Typography.Text type="secondary">{t('s2.hint')}</Typography.Text>
          <Button type="primary" loading={running} onClick={runBrowser}>
            {t('s2.run')} ({browserPass}/{browserTotal})
          </Button>
          <Button onClick={copyCommand}>{t('s2.copy')}</Button>
          <Button onClick={refresh}>{t('s2.refresh')}</Button>
        </Space>
      )}
    >
      {complex.data ? (
        <Alert
          type={complex.data.fail > 0 ? 'warning' : 'info'}
          showIcon
          message={t('s2.complex.title')}
          description={(
            <Space direction="vertical" size={2}>
              <Typography.Text type="secondary">
                {complex.data.command} · {complex.data.generatedAt}
              </Typography.Text>
              {Object.values(complex.data.evidence).map((ev) => (
                <Typography.Text key={ev.runId}>
                  <Tag color={ev.runStatus === 'completed' ? 'green' : 'orange'}>{ev.label}</Tag>
                  {' '}run {ev.runId} · {ev.runStatus}
                  {' · decl '}{ev.declarations}
                  {' · effects '}{(ev.effects.match(/APPLIED/g) ?? []).length}
                  {' · events '}{ev.events.split('\n').filter(Boolean).length}
                  {' · actions '}{ev.actions.split('\n').filter(Boolean).length}
                  {' · Q18 '}{ev.q18Rows}
                  {ev.manualErrors.split('\n').filter(Boolean).length > 0
                    ? ` · ${ev.manualErrors.split('\n').filter(Boolean)[0]} × ${ev.manualErrors.split('\n').filter(Boolean).length}`
                    : ''}
                </Typography.Text>
              ))}
              <Typography.Text type="secondary">
                {t('s2.complex.summary', {
                  pass: complex.data.pass,
                  fail: complex.data.fail,
                  skip: complex.data.skip,
                })}
              </Typography.Text>
            </Space>
          )}
          style={{ marginBottom: 16 }}
        />
      ) : null}
      <Alert
        type={report.data && report.data.fail > 0 ? 'warning' : 'info'}
        showIcon
        message={summary}
        description={report.data?.command ?? t('s2.noReport')}
        style={{ marginBottom: 16 }}
      />
      <Table<Row>
        rowKey="id"
        size="small"
        loading={cases.isLoading}
        dataSource={rows}
        pagination={false}
        columns={[
          { title: t('s2.col.id'), dataIndex: 'id', width: 116 },
          { title: t('s2.col.layer'), dataIndex: 'layer', width: 62,
            render: (v: Row['layer']) => <Tag>{v}</Tag> },
          { title: t('s2.col.name'),
            render: (_, r) => (
              <Space direction="vertical" size={0}>
                <span>{r.title}</span>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>{r.note}</Typography.Text>
              </Space>
            ) },
          { title: t('s2.col.opinion'), dataIndex: 'opinion', width: 92 },
          { title: t('s2.col.mode'), dataIndex: 'mode', width: 96,
            render: (v: Row['mode']) => (
              <Tag color={v === 'browser' ? 'blue' : 'default'}>
                {v === 'browser' ? t('s2.mode.browser') : t('s2.mode.script')}
              </Tag>
            ) },
          { title: t('s2.col.script'), width: 150,
            render: (_, r) => (
              <Space direction="vertical" size={0}>
                <StatusTag
                  status={r.script ? (r.script.status as Status) : 'idle'}
                  detail={r.script?.detail}
                />
                {r.script?.detail
                  ? <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      {r.script.detail.slice(0, 60)}
                    </Typography.Text>
                  : null}
              </Space>
            ) },
          { title: t('s2.col.browser'), width: 150,
            render: (_, r) => (
              <Space direction="vertical" size={0}>
                <StatusTag status={r.browser?.status} detail={r.browser?.detail} />
                {r.browser?.detail
                  ? <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      {r.browser.detail.slice(0, 60)}
                    </Typography.Text>
                  : null}
              </Space>
            ) },
        ]}
      />
    </Card>
  );
}
