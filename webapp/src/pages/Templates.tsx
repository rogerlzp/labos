import { useMemo, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert, Button, Card, Drawer, Empty, Form, Input, InputNumber, Modal, Select,
  Space, Switch, Table, Tag, Typography, message,
} from 'antd';
import { useNavigate } from 'react-router-dom';
import { apiFetch } from '../api/client';
import { useT } from '../i18n';
import { useDesignOnly, useOperateOnly } from '../auth';

/**
 * 实验模板（P4 · 组合层）。
 *
 * <p>这一页解决的是「应用者不该进设计器拖节点」的问题：
 * 工程师在 `/designer/:id` 维护**工艺图**（43 节点 + 30 条节点绑定），
 * 应用者在这里只需 ① 选流程 ② 填参数 ③ 声明耗材与点位白名单，
 * 操作员再去 `/material-config` 配位置启动。
 *
 * <p>**克隆**是这一层的关键：模板必须持有自己的流程副本，
 * 否则改 A 模板的顺序会串到 B 模板（共用同一条 workflow）。
 */

interface TemplateRow {
  id: number;
  templateNo: string;
  name: string;
  islandId: number;
  status: string;
  workflowIds?: number[];
  spec?: Record<string, unknown>;
}

interface TemplateDetail extends TemplateRow {
  pointWhitelist?: { pointIds?: number[] } | null;
  processes?: Array<{ workflowId: number; seq: number }>;
  manifestItems?: Array<{
    roleCode: string | null;
    materialKind: string;
    materialRef?: number | null;
    quantity: number;
    required: boolean;
  }>;
}

interface Island { id: number; code: string; name?: string }
interface WorkflowOpt { id: number; code: string; name: string }
interface PositionOpt {
  pointId: number | null; code: string; parentDeviceCode?: string | null;
}

const MATERIAL_KINDS = ['plate', 'consumable', 'reagent', 'sample', 'lid'];
const STATUS_COLOR: Record<string, string> = {
  draft: 'default', published: 'green', archived: 'orange',
};

export default function TemplatesPage() {
  const t = useT();
  const ro = useDesignOnly();
  const qc = useQueryClient();
  const [editing, setEditing] = useState<number | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [createForm] = Form.useForm();
  const fileRef = useRef<HTMLInputElement>(null);

  /**
   * 导入：把另一个环境导出的 JSON 变成一套新流程 + 新模板。
   *
   * <p>后端是「先全量校验再落库」：缺岛/缺点位会 400 并列出问题，
   * 不会留下半成品；重名流程会自动加 `-IMP-xxxxx` 后缀（warnings 里会说）。
   */
  const importTemplate = async (file: File) => {
    let payload: unknown;
    try {
      payload = JSON.parse(await file.text());
    } catch {
      message.error(t('templates.error.importJson'));
      return;
    }
    const r = await apiFetch('/api/experiment-templates/import', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
    const j = await r.json().catch(() => ({}));
    if (!r.ok) {
      message.error(j.error ?? t('templates.error.import'));
      if (Array.isArray(j.problems) && j.problems.length) {
        console.warn('[template import] problems:', j.problems);
      }
      return;
    }
    if (Array.isArray(j.warnings) && j.warnings.length) {
      message.warning(j.warnings.join('；'), 6);
    }
    message.success(t('templates.toast.imported', { no: j.templateNo ?? '' }));
    invalidate();
    setEditing(j.templateId);
  };

  const templatesQ = useQuery({
    queryKey: ['experiment-templates'],
    queryFn: () => apiFetch('/api/experiment-templates')
      .then((r) => r.json()) as Promise<{ items: TemplateRow[] }>,
  });
  const islandsQ = useQuery({
    queryKey: ['islands'],
    queryFn: () => apiFetch('/api/topology')
      .then((r) => r.json()) as Promise<{ islands?: Island[] }>,
  });
  const workflowsQ = useQuery({
    queryKey: ['workflow-options'],
    queryFn: () => apiFetch('/api/workflows?limit=300')
      .then((r) => r.json()) as Promise<{ items?: WorkflowOpt[] }>,
  });
  const islands = islandsQ.data?.islands ?? [];
  const workflows = workflowsQ.data?.items ?? [];
  const islandCode = (id: number) => islands.find((i) => i.id === id)?.code ?? String(id);

  const invalidate = () => {
    void qc.invalidateQueries({ queryKey: ['experiment-templates'] }).catch(() => undefined);
  };

  const create = async () => {
    const v = await createForm.validateFields();
    const r = await apiFetch('/api/experiment-templates', {
      method: 'POST',
      body: JSON.stringify({
        name: v.name,
        islandId: v.islandId,
        spec: { iterations: v.iterations ?? 1 },
        workflowIds: (v.workflowIds ?? []).map((id: number) => ({ workflowId: id })),
        manifestItems: [],
        pointWhitelist: { pointIds: [] },
      }),
    });
    const j = await r.json();
    if (!r.ok) {
      message.error(j.error ?? t('templates.error.create'));
      return;
    }
    message.success(t('templates.toast.created'));
    setCreateOpen(false);
    createForm.resetFields();
    invalidate();
    setEditing(j.id);
  };

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card
        size="small"
        title={t('templates.title')}
        extra={(
          <Space>
            <input
              ref={fileRef}
              type="file"
              accept=".json,application/json"
              data-testid="template-import-input"
              style={{ display: 'none' }}
              onChange={(e) => {
                const f = e.target.files?.[0];
                if (f) void importTemplate(f);
                e.target.value = '';
              }}
            />
            <Button size="small" disabled={ro.disabled}
              onClick={() => fileRef.current?.click()}>
              {t('templates.action.import')}
            </Button>
            <Button type="primary" size="small" disabled={ro.disabled}
              onClick={() => setCreateOpen(true)}>
              {t('templates.action.create')}
            </Button>
          </Space>
        )}
      >
        <Typography.Paragraph type="secondary" style={{ fontSize: 11 }}>
          {t('templates.hint')}
        </Typography.Paragraph>
        {(templatesQ.data?.items ?? []).length === 0
          ? <Empty description={t('templates.empty')} />
          : (
            <Table
              size="small"
              rowKey="id"
              pagination={false}
              dataSource={templatesQ.data?.items ?? []}
              columns={[
                { title: t('templates.col.no'), dataIndex: 'templateNo', width: 150 },
                { title: t('templates.col.name'), dataIndex: 'name' },
                {
                  title: t('templates.col.island'), dataIndex: 'islandId', width: 130,
                  render: (v: number) => <Tag>{islandCode(v)}</Tag>,
                },
                {
                  title: t('templates.col.status'), dataIndex: 'status', width: 100,
                  render: (v: string) => <Tag color={STATUS_COLOR[v] ?? 'default'}>{v}</Tag>,
                },
                {
                  title: t('templates.col.processes'), width: 90,
                  render: (_: unknown, r: TemplateRow) => (r.workflowIds ?? []).length,
                },
                {
                  title: '', width: 90, align: 'right' as const,
                  render: (_: unknown, r: TemplateRow) => (
                    <Button size="small" onClick={() => setEditing(r.id)}>
                      {t('templates.action.edit')}
                    </Button>
                  ),
                },
              ]}
            />
          )}
      </Card>

      <Modal
        open={createOpen}
        title={t('templates.action.create')}
        onCancel={() => setCreateOpen(false)}
        onOk={create}
        okText={t('templates.action.create')}
        destroyOnClose
      >
        <Form form={createForm} layout="vertical" size="small">
          <Form.Item name="name" label={t('templates.col.name')} rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="islandId" label={t('templates.col.island')} rules={[{ required: true }]}>
            <Select options={islands.map((i) => ({ value: i.id, label: i.code }))} />
          </Form.Item>
          <Form.Item name="workflowIds" label={t('templates.col.processes')}>
            <Select
              mode="multiple" optionFilterProp="label" allowClear
              options={workflows.map((w) => ({ value: w.id, label: `${w.code} · ${w.name}` }))}
            />
          </Form.Item>
          <Form.Item name="iterations" label={t('templates.field.iterations')} initialValue={1}>
            <InputNumber min={1} max={100} style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>

      {editing != null && (
        <TemplateEditor
          id={editing}
          readOnly={ro.disabled}
          onClose={() => setEditing(null)}
          onSaved={invalidate}
        />
      )}
    </Space>
  );
}

/** 模板编辑器：流程清单 / 物料清单 / 点位白名单（整包替换语义）。 */
function TemplateEditor({ id, readOnly, onClose, onSaved }: {
  id: number; readOnly: boolean; onClose: () => void; onSaved: () => void;
}) {
  const t = useT();
  // 实例化是「开一批」（运行侧）：designer 只编模板，不启动实验
  const roOp = useOperateOnly();
  const nav = useNavigate();
  const qc = useQueryClient();
  const [saving, setSaving] = useState(false);
  const [name, setName] = useState('');
  const [status, setStatus] = useState('draft');
  const [iterations, setIterations] = useState(1);
  const [workflowIds, setWorkflowIds] = useState<number[]>([]);
  const [pointIds, setPointIds] = useState<number[]>([]);
  const [items, setItems] = useState<Array<{
    roleCode: string; materialKind: string; quantity: number; required: boolean;
  }>>([]);
  const [loaded, setLoaded] = useState(false);

  const detailQ = useQuery({
    queryKey: ['experiment-template', id],
    queryFn: async () => {
      const r = await apiFetch(`/api/experiment-templates/${id}`);
      const j = await r.json() as TemplateDetail;
      setName(j.name ?? '');
      setStatus(j.status ?? 'draft');
      const spec = (j.spec ?? {}) as Record<string, unknown>;
      setIterations(Number(spec.iterations ?? 1));
      const ids = (j.processes ?? []).map((p) => p.workflowId);
      setWorkflowIds((j.workflowIds ?? []).length ? (j.workflowIds as number[]) : ids);
      setPointIds(j.pointWhitelist?.pointIds ?? []);
      setItems((j.manifestItems ?? []).map((m) => ({
        roleCode: m.roleCode ?? '', materialKind: m.materialKind,
        quantity: m.quantity ?? 1, required: m.required !== false,
      })));
      setLoaded(true);
      return j;
    },
  });

  /** 模板自检：还差什么才能实例化并启动（静态检查，不碰设备/物料摆放）。 */
  const checkQ = useQuery({
    queryKey: ['template-check', id],
    queryFn: () => apiFetch(`/api/experiment-templates/${id}/check`)
      .then((r) => r.json()) as Promise<{
        ok: boolean; errors: number; warnings: number;
        issues: Array<{ level: string; code: string; message: string }>;
        stats: { processes: number; manifestItems: number; whitelistPoints: number; status?: string };
      }>,
    enabled: loaded,
  });

  const template = detailQ.data;
  const islandId = template?.islandId;
  const positionsQ = useQuery({
    queryKey: ['positions', islandId],
    queryFn: () => apiFetch(`/api/positions?islandId=${islandId}`)
      .then((r) => r.json()) as Promise<{ items?: PositionOpt[] }>,
    enabled: islandId != null,
  });
  const workflowOptions = useQuery({
    queryKey: ['workflow-options'],
    queryFn: () => apiFetch('/api/workflows?limit=300')
      .then((r) => r.json()) as Promise<{ items?: WorkflowOpt[] }>,
  });
  const wfLabel = useMemo(() => {
    const m = new Map<number, string>();
    for (const w of workflowOptions.data?.items ?? []) m.set(w.id, `${w.code} · ${w.name}`);
    return m;
  }, [workflowOptions.data]);

  /** 克隆选中流程：模板必须持有自己的副本，否则两个模板会共改同一张图。 */
  const cloneSelected = async () => {
    if (workflowIds.length === 0) return;
    const cloned: number[] = [];
    for (const wid of workflowIds) {
      try {
        const r = await apiFetch(`/api/workflows/${wid}/clone`, {
          method: 'POST',
          body: JSON.stringify({ name: `${wfLabel.get(wid) ?? wid}（模板副本）` }),
        });
        const j = await r.json();
        if (r.ok && j.id) cloned.push(j.id);
        else message.error(j.error ?? t('templates.error.clone'));
      } catch (e) {
        message.error(String((e as Error).message));
      }
    }
    if (cloned.length) {
      setWorkflowIds(cloned);
      void qc.invalidateQueries({ queryKey: ['workflow-options'] }).catch(() => undefined);
      message.success(t('templates.toast.cloned', { n: cloned.length }));
    }
  };

  /**
   * 实例化并直接跳到物料配置。
   *
   * <p>这是"应用者 → 操作员"的交接点：模板里定了流程/参数/耗材清单，
   * 实例化会生成 experiment + experiment_run + manifest_placement，
   * 操作员在物料配置页只需要配位置、核验、启动。
   */
  const instantiate = async () => {
    setSaving(true);
    try {
      const r = await apiFetch(`/api/experiment-templates/${id}/instantiate`, {
        method: 'POST',
        body: JSON.stringify({}),
      });
      const j = await r.json();
      if (!r.ok) {
        message.error(j.error ?? t('templates.error.instantiate'));
        return;
      }
      message.success(t('templates.toast.instantiated', { run: j.runId }));
      nav(`/material-config?templateId=${id}&runId=${j.runId}`);
    } catch (e) {
      message.error(String((e as Error).message));
    } finally {
      setSaving(false);
    }
  };

  /** 导出为 JSON 文件（含引用的流程：图 + 阶段 + 绑定，全部业务码）。 */
  const exportJson = async () => {
    const r = await apiFetch(`/api/experiment-templates/${id}/export`);
    if (!r.ok) {
      message.error(t('templates.error.export'));
      return;
    }
    const j = await r.json();
    const blob = new Blob([JSON.stringify(j, null, 2)], { type: 'application/json' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `template-${template?.templateNo ?? id}.json`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(a.href);
  };

  const save = async () => {
    setSaving(true);
    try {
      const r = await apiFetch(`/api/experiment-templates/${id}`, {
        method: 'PUT',
        body: JSON.stringify({
          name,
          status,
          spec: { iterations },
          workflowIds: workflowIds.map((w) => ({ workflowId: w })),
          manifestItems: items
            .filter((i) => i.roleCode.trim() !== '')
            .map((i) => ({
              roleCode: i.roleCode.trim(), materialKind: i.materialKind,
              quantity: i.quantity, required: i.required,
            })),
          pointWhitelist: { pointIds },
        }),
      });
      const j = await r.json();
      if (!r.ok) {
        message.error(j.error ?? t('templates.error.save'));
        return;
      }
      message.success(t('templates.toast.saved'));
      onSaved();
      void qc.invalidateQueries({ queryKey: ['experiment-template', id] }).catch(() => undefined);
      void qc.invalidateQueries({ queryKey: ['template-check', id] }).catch(() => undefined);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Drawer
      open width={760} onClose={onClose}
      title={t('templates.editor.title', { no: template?.templateNo ?? id })}
      extra={(
        <Space>
          <Button size="small" onClick={onClose}>{t('templates.action.close')}</Button>
          <Button size="small" onClick={exportJson}>{t('templates.action.export')}</Button>
          <Button size="small" disabled={readOnly || roOp.disabled || !loaded}
            title={roOp.title} onClick={instantiate}>
            {t('templates.action.instantiate')}
          </Button>
          <Button size="small" type="primary" loading={saving}
            disabled={readOnly || !loaded} onClick={save}>
            {t('templates.action.save')}
          </Button>
        </Space>
      )}
    >
      {!loaded ? null : (
        <Space direction="vertical" size={14} style={{ width: '100%' }}>
          <Space style={{ width: '100%' }} size={12}>
            <div style={{ flex: 1 }}>
              <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                {t('templates.col.name')}
              </Typography.Text>
              <Input size="small" value={name} disabled={readOnly}
                onChange={(e) => setName(e.target.value)} />
            </div>
            <div style={{ width: 150 }}>
              <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                {t('templates.col.status')}
              </Typography.Text>
              <Select size="small" style={{ width: '100%' }} value={status} disabled={readOnly}
                onChange={setStatus}
                options={['draft', 'published', 'archived'].map((v) => ({ value: v, label: v }))} />
            </div>
            <div style={{ width: 120 }}>
              <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                {t('templates.field.iterations')}
              </Typography.Text>
              <InputNumber size="small" min={1} max={100} style={{ width: '100%' }}
                value={iterations} disabled={readOnly}
                onChange={(v) => setIterations(Number(v ?? 1))} />
            </div>
          </Space>

          <Card size="small" title={t('templates.section.processes')}>
            <Space direction="vertical" size={8} style={{ width: '100%' }}>
              <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                {t('templates.hint.clone')}
              </Typography.Text>
              <Select
                mode="multiple" style={{ width: '100%' }} disabled={readOnly}
                optionFilterProp="label"
                value={workflowIds}
                onChange={setWorkflowIds}
                options={(workflowOptions.data?.items ?? []).map((w) => ({
                  value: w.id, label: `${w.code} · ${w.name}`,
                }))}
              />
              <Space>
                <Button size="small" disabled={readOnly || workflowIds.length === 0}
                  onClick={cloneSelected}>
                  {t('templates.action.clone')}
                </Button>
                <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                  {t('templates.hint.cloneOnce')}
                </Typography.Text>
              </Space>
            </Space>
          </Card>

          <Card
            size="small"
            title={t('templates.section.materials')}
            extra={(
              <Button size="small" disabled={readOnly}
                onClick={() => setItems((v) => [...v, {
                  roleCode: '', materialKind: 'plate', quantity: 1, required: true,
                }])}>
                {t('templates.action.addItem')}
              </Button>
            )}
          >
            {items.length === 0
              ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('templates.emptyItems')} />
              : (
                <Table
                  size="small" rowKey={(_, i) => String(i)} pagination={false}
                  dataSource={items}
                  columns={[
                    {
                      title: t('templates.col.role'), width: 180,
                      render: (_: unknown, r, i) => (
                        <Input size="small" value={r.roleCode} disabled={readOnly}
                          placeholder="mainPlate / magPlate…"
                          onChange={(e) => setItems((v) => v.map((x, k) =>
                            k === i ? { ...x, roleCode: e.target.value } : x))} />
                      ),
                    },
                    {
                      title: t('templates.col.kind'), width: 130,
                      render: (_: unknown, r, i) => (
                        <Select size="small" style={{ width: '100%' }} value={r.materialKind}
                          disabled={readOnly}
                          onChange={(v) => setItems((arr) => arr.map((x, k) =>
                            k === i ? { ...x, materialKind: v } : x))}
                          options={MATERIAL_KINDS.map((k) => ({ value: k, label: k }))} />
                      ),
                    },
                    {
                      title: t('templates.col.qty'), width: 90,
                      render: (_: unknown, r, i) => (
                        <InputNumber size="small" min={1} style={{ width: '100%' }}
                          value={r.quantity} disabled={readOnly}
                          onChange={(v) => setItems((arr) => arr.map((x, k) =>
                            k === i ? { ...x, quantity: Number(v ?? 1) } : x))} />
                      ),
                    },
                    {
                      title: t('templates.col.required'), width: 70,
                      render: (_: unknown, r, i) => (
                        <Switch size="small" checked={r.required} disabled={readOnly}
                          onChange={(v) => setItems((arr) => arr.map((x, k) =>
                            k === i ? { ...x, required: v } : x))} />
                      ),
                    },
                    {
                      title: '', width: 50,
                      render: (_: unknown, __, i) => (
                        <Button size="small" danger type="text" disabled={readOnly}
                          onClick={() => setItems((arr) => arr.filter((__, k) => k !== i))}>
                          ×
                        </Button>
                      ),
                    },
                  ]}
                />
              )}
          </Card>

          <Card size="small" title={t('templates.section.whitelist')}>
            <Typography.Paragraph type="secondary" style={{ fontSize: 11, marginBottom: 8 }}>
              {t('templates.hint.whitelist')}
            </Typography.Paragraph>
            <Select
              mode="multiple" style={{ width: '100%' }} disabled={readOnly}
              optionFilterProp="label"
              value={pointIds}
              onChange={setPointIds}
              options={(positionsQ.data?.items ?? [])
                .filter((p) => p.pointId != null)
                .map((p) => ({
                  value: p.pointId as number,
                  label: `${p.parentDeviceCode ?? ''} · ${p.code}`,
                }))}
            />
          </Card>

          <Card
            size="small"
            title={(
              <Space>
                {t('templates.section.check')}
                {checkQ.data && (
                  <Tag color={checkQ.data.ok ? 'green' : 'red'}>
                    {checkQ.data.ok
                      ? t('templates.check.pass')
                      : t('templates.check.blocked', { n: checkQ.data.errors })}
                  </Tag>
                )}
                {checkQ.data && checkQ.data.warnings > 0 && (
                  <Tag color="orange">{t('templates.check.warnings', { n: checkQ.data.warnings })}</Tag>
                )}
              </Space>
            )}
          >
            <Typography.Paragraph type="secondary" style={{ fontSize: 11 }}>
              {t('templates.check.hint')}
            </Typography.Paragraph>
            {checkQ.data && (
              <>
                <Space size={16} style={{ marginBottom: 8 }}>
                  <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                    {t('templates.check.stats', {
                      p: checkQ.data.stats.processes,
                      m: checkQ.data.stats.manifestItems,
                      w: checkQ.data.stats.whitelistPoints,
                    })}
                  </Typography.Text>
                </Space>
                {checkQ.data.issues.length === 0
                  ? <Alert type="success" showIcon message={t('templates.check.allGood')} />
                  : (
                    <Space direction="vertical" size={6} style={{ width: '100%' }}>
                      {checkQ.data.issues.map((it) => (
                        <Alert
                          key={it.code + it.message}
                          type={it.level === 'ERROR' ? 'error' : 'warning'}
                          showIcon
                          message={it.message}
                          description={<Typography.Text code style={{ fontSize: 10 }}>{it.code}</Typography.Text>}
                        />
                      ))}
                    </Space>
                  )}
              </>
            )}
          </Card>

          <Alert type="info" showIcon message={t('templates.hint.saveSemantics')} />
        </Space>
      )}
    </Drawer>
  );
}
