import { api } from '../api/client';
import { useMemo, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert, Button, Card, Form, Input, InputNumber, Modal, Popconfirm, Progress,
  Space, Table, Tag, Typography, message,
} from 'antd';
import { useReadOnly } from '../auth';
import { useT } from '../i18n';

interface Duration {
  method: string;
  p50Ms: number;
  p90Ms: number;
  minMs: number;
  maxMs: number;
  sampleCount: number;
}

/**
 * 弹窗表单。**单位统一是毫秒**（后端 duration_model 存的就是 ms），
 * 表格里为了好读仍然显示成秒（改动前的口径不变）。
 */
interface DurationForm {
  method: string;
  p50Ms: number;
  p90Ms: number;
  minMs: number;
  maxMs: number;
  sampleCount: number;
}

const EMPTY_FORM: DurationForm = {
  method: '', p50Ms: 0, p90Ms: 0, minMs: 0, maxMs: 0, sampleCount: 1,
};

const sec = (ms: number) => `${(ms / 1000).toFixed(ms < 1000 ? 2 : 1)}s`;

/** 时长列的渲染：等宽字体，方便上下对齐比较。 */
const msCell = (v: number) => (
  <span style={{ fontFamily: 'monospace' }}>{sec(Number(v) || 0)}</span>
);

/**
 * 时长分布：同一个方法在不同设备上的耗时差异，是排程和预测的依据。
 *
 * 「波动比」= p90 / p50：> 3 的方法说明耗时极不稳定，
 * 用它做预测会偏乐观，需要按 p90 排期。
 *
 * 2026-11-15：加了写口（新建 / 编辑 / 删除）。原先只有 GET ⇒ 现场标定出来的
 * 真实时长进不来，甘特预测与仿真一直用旧值。方法名是主键（tenant_id, method），
 * 所以编辑时只读；要改名就新建一条再删旧的。
 */
export default function DurationsPage() {
  const [kw, setKw] = useState('');
  const t = useT();
  const qc = useQueryClient();
  // 时长模型是配置类写操作：viewer 只读
  const ro = useReadOnly();
  const { data, isLoading, error } = useQuery({
    queryKey: ['durations'],
    queryFn: () => api.durations() as Promise<{ items: Duration[] }>,
  });

  const [open, setOpen] = useState(false);
  /** 非 null = 编辑模式；null = 新建模式（新建时才允许填方法名）。 */
  const [editing, setEditing] = useState<Duration | null>(null);
  const [busy, setBusy] = useState(false);
  const [form] = Form.useForm<DurationForm>();
  const isEdit = editing != null;

  const items = useMemo(() => {
    const all = (data?.items ?? []).filter((d) => !kw || d.method.toLowerCase().includes(kw.toLowerCase()));
    return all.map((d) => ({ ...d, ratio: d.p50Ms > 0 ? d.p90Ms / d.p50Ms : 0 }));
  }, [data, kw]);

  const maxP90 = Math.max(1, ...items.map((d) => d.p90Ms));

  // 即时校验：p50 ≤ p90、min ≤ max、都不为负（与后端护栏同口径，
  // 不让现场填完才吃一个 400）。
  const p50v = Form.useWatch('p50Ms', form) as number | undefined;
  const p90v = Form.useWatch('p90Ms', form) as number | undefined;
  const minv = Form.useWatch('minMs', form) as number | undefined;
  const maxv = Form.useWatch('maxMs', form) as number | undefined;

  const liveErrors = useMemo(() => {
    const errs: string[] = [];
    if (p50v != null && p90v != null && p50v > p90v) errs.push(t('durations.err.p50gtp90'));
    if (minv != null && maxv != null && minv > maxv) errs.push(t('durations.err.mingtmax'));
    if ([p50v, p90v, minv, maxv].some((v) => v != null && v < 0)) errs.push(t('durations.err.negative'));
    return errs;
  }, [p50v, p90v, minv, maxv, t]);

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue(EMPTY_FORM);
    setOpen(true);
  };

  const openEdit = (row: Duration) => {
    setEditing(row);
    form.resetFields();
    form.setFieldsValue({
      method: row.method,
      p50Ms: Number(row.p50Ms) || 0,
      p90Ms: Number(row.p90Ms) || 0,
      minMs: Number(row.minMs) || 0,
      maxMs: Number(row.maxMs) || 0,
      sampleCount: Number(row.sampleCount) || 1,
    });
    setOpen(true);
  };

  const save = async () => {
    let values: DurationForm;
    try {
      values = await form.validateFields();
    } catch {
      // 校验没过：antd 已经在字段下方标红了，不用再弹一次 toast
      return;
    }
    const method = (values.method ?? '').trim();
    if (!method) {
      message.warning(t('durations.err.methodEmpty'));
      return;
    }
    setBusy(true);
    try {
      await api.saveDuration({
        method,
        p50Ms: values.p50Ms,
        p90Ms: values.p90Ms,
        minMs: values.minMs,
        maxMs: values.maxMs,
        sampleCount: values.sampleCount,
      });
      message.success(t('durations.toast.saved', { method }));
      setOpen(false);
      await qc.invalidateQueries({ queryKey: ['durations'] });
    } catch (e) {
      // 后端 400 的原文直接透给现场（如 "p50 不能大于 p90"）
      message.error(t('durations.toast.saveFailed', { msg: (e as Error).message }));
    } finally {
      setBusy(false);
    }
  };

  const remove = async (row: Duration) => {
    try {
      await api.deleteDuration(row.method);
      message.success(t('durations.toast.deleted', { method: row.method }));
      await qc.invalidateQueries({ queryKey: ['durations'] });
    } catch (e) {
      message.error(t('durations.toast.deleteFailed', { msg: (e as Error).message }));
    }
  };

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Alert type="info" showIcon message={t('durations.hint.estimate')} />

      <Card
        size="small"
        title={t('durations.title', { n: items.length })}
        extra={
          <Space size={8}>
            <Input.Search
              placeholder={t('durations.placeholder')} allowClear style={{ width: 220 }}
              onChange={(e) => setKw(e.target.value)}
            />
            <Button type="primary" {...ro} onClick={openCreate}>
              {t('durations.action.create')}
            </Button>
          </Space>
        }
      >
        {error ? (
          <Alert type="error" showIcon message={t('durations.err.load')}
            description={String((error as Error).message)} />
        ) : (
          <Table
            rowKey="method" size="small" loading={isLoading}
            dataSource={items}
            pagination={{ pageSize: 20, size: 'small' }}
            columns={[
              { title: t('durations.col.method'), dataIndex: 'method', width: 240 },
              { title: t('durations.col.p50Ms'), dataIndex: 'p50Ms', width: 80, render: msCell },
              { title: t('durations.col.p90Ms'), dataIndex: 'p90Ms', width: 80, render: msCell },
              { title: t('durations.col.minMs'), dataIndex: 'minMs', width: 80, render: msCell },
              { title: t('durations.col.maxMs'), dataIndex: 'maxMs', width: 80, render: msCell },
              {
                // 用进度条横向对比 p90 的相对大小，一眼看出谁最慢
                title: t('durations.col.p90'), width: 160,
                render: (_, r) => (
                  <Progress
                    percent={Math.round((r.p90Ms / maxP90) * 100)}
                    size="small" showInfo={false}
                    strokeColor={r.ratio > 3 ? '#f59e0b' : '#3b82f6'}
                  />
                ),
              },
              {
                title: t('durations.col.ratio'), dataIndex: 'ratio', width: 100,
                render: (v: number) => (
                  <Tag color={v > 3 ? 'orange' : 'default'}>{v.toFixed(1)}×</Tag>
                ),
              },
              { title: t('durations.col.samples'), dataIndex: 'sampleCount', width: 80 },
              {
                title: t('durations.col.actions'), width: 140,
                render: (_, r) => (
                  <Space size={0}>
                    <Button size="small" type="link" {...ro} onClick={() => openEdit(r)}>
                      {t('durations.action.edit')}
                    </Button>
                    <Popconfirm
                      title={t('durations.confirm.deleteTitle', { method: r.method })}
                      description={t('durations.confirm.deleteDesc')}
                      okText={t('durations.confirm.deleteOk')} okButtonProps={{ danger: true }}
                      onConfirm={() => remove(r)}
                    >
                      <Button size="small" type="link" danger {...ro}>
                        {t('durations.action.delete')}
                      </Button>
                    </Popconfirm>
                  </Space>
                ),
              },
            ]}
          />
        )}
        <Typography.Paragraph type="secondary" style={{ fontSize: 11, marginTop: 10, marginBottom: 0 }}>
          {t('durations.note.ratio')}
        </Typography.Paragraph>
      </Card>

      <Modal
        open={open}
        title={isEdit
          ? t('durations.modal.editTitle', { method: editing?.method ?? '' })
          : t('durations.modal.createTitle')}
        onCancel={() => setOpen(false)}
        onOk={save}
        okText={t('durations.action.save')}
        cancelText={t('durations.action.cancel')}
        confirmLoading={busy}
        okButtonProps={{ disabled: ro.disabled, title: ro.title }}
        width={560}
      >
        <Form form={form} layout="vertical" style={{ marginTop: 12 }}>
          <Form.Item
            name="method"
            label={t('durations.field.method')}
            extra={isEdit ? t('durations.hint.methodImmutable') : t('durations.hint.methodCreate')}
            rules={[
              { required: true, message: t('durations.err.methodEmpty') },
              {
                validator: async (_, v: string) => {
                  if (!(v ?? '').trim()) throw new Error(t('durations.err.methodEmpty'));
                },
              },
            ]}
          >
            <Input readOnly={isEdit}
              placeholder={isEdit ? undefined : t('durations.placeholder.method')} />
          </Form.Item>

          <Space size={12} wrap align="start">
            <Form.Item
              name="p50Ms" label={t('durations.field.p50')}
              style={{ width: 180 }}
              rules={[
                { required: true, message: t('durations.err.required') },
                { type: 'number', min: 0, message: t('durations.err.negative') },
              ]}
            >
              <InputNumber min={0} style={{ width: '100%' }} />
            </Form.Item>

            <Form.Item
              name="p90Ms" label={t('durations.field.p90')}
              style={{ width: 180 }}
              dependencies={['p50Ms']}
              rules={[
                { required: true, message: t('durations.err.required') },
                { type: 'number', min: 0, message: t('durations.err.negative') },
                {
                  // p50 改了要重校验 p90：p50 ≤ p90 与后端护栏同口径
                  validator: async (_, v: number | undefined) => {
                    const p50 = form.getFieldValue('p50Ms') as number | undefined;
                    if (v == null || p50 == null) return;
                    if (v < p50) throw new Error(t('durations.err.p50gtp90'));
                  },
                },
              ]}
            >
              <InputNumber min={0} style={{ width: '100%' }} />
            </Form.Item>
          </Space>

          <Space size={12} wrap align="start">
            <Form.Item
              name="minMs" label={t('durations.field.min')}
              style={{ width: 180 }}
              rules={[
                { required: true, message: t('durations.err.required') },
                { type: 'number', min: 0, message: t('durations.err.negative') },
              ]}
            >
              <InputNumber min={0} style={{ width: '100%' }} />
            </Form.Item>

            <Form.Item
              name="maxMs" label={t('durations.field.max')}
              style={{ width: 180 }}
              dependencies={['minMs']}
              rules={[
                { required: true, message: t('durations.err.required') },
                { type: 'number', min: 0, message: t('durations.err.negative') },
                {
                  validator: async (_, v: number | undefined) => {
                    const min = form.getFieldValue('minMs') as number | undefined;
                    if (v == null || min == null) return;
                    if (v < min) throw new Error(t('durations.err.mingtmax'));
                  },
                },
              ]}
            >
              <InputNumber min={0} style={{ width: '100%' }} />
            </Form.Item>
          </Space>

          <Form.Item
            name="sampleCount" label={t('durations.field.samples')}
            style={{ width: 180 }}
            rules={[
              { required: true, message: t('durations.err.required') },
              { type: 'number', min: 1, message: t('durations.err.samples') },
            ]}
          >
            <InputNumber min={1} style={{ width: '100%' }} />
          </Form.Item>

          {liveErrors.length > 0 && (
            <Alert type="error" showIcon style={{ marginBottom: 12 }}
              message={liveErrors[0]}
              description={liveErrors.length > 1 ? (
                <Space direction="vertical" size={2}>
                  {liveErrors.slice(1).map((e, i) => (
                    <Typography.Text key={i} style={{ fontSize: 12 }}>{e}</Typography.Text>
                  ))}
                </Space>
              ) : undefined} />
          )}

          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {t('durations.hint.unit')}
          </Typography.Text>
        </Form>
      </Modal>
    </Space>
  );
}
