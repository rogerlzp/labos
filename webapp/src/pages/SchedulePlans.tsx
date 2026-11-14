import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  Alert, Button, Card, Empty, Form, Input, InputNumber, Modal, Space, Table, Tag,
  Typography, message,
} from 'antd';
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { api } from '../api/client';
import { formatTime } from '../utils/format';
import { useT } from '../i18n';

/**
 * 排程参数（schedule_plan）管理页——designer 角色。
 *
 * <p>对应 Director §5.6 的 Schedule / Optimization Factor / Max Iterations Running。
 * 这些参数由调度循环每 30 tick（约 60s）自动重载（见 /api/scheduler/config），
 * 新建/编辑后约 1 分钟生效。
 */
interface Plan {
  id: number;
  planNo: string;
  status: string;
  optimizationFactor: number;
  maxIterationsRunning: number;
  iterationDelaySec: number;
  createdAt?: string;
}

export default function SchedulePlansPage() {
  const t = useT();
  const qc = useQueryClient();
  const [form] = Form.useForm();
  const [editForm] = Form.useForm();
  const [editing, setEditing] = useState<Plan | null>(null);

  const { data, error, refetch, isFetching } = useQuery({
    queryKey: ['schedule-plans'],
    queryFn: api.schedulePlans,
  });

  const createPlan = useMutation({
    mutationFn: api.createSchedulePlan,
    onSuccess: () => {
      message.success(t('schedulePlans.toast.created'));
      form.resetFields();
      qc.invalidateQueries({ queryKey: ['schedule-plans'] });
      qc.invalidateQueries({ queryKey: ['scheduler-config'] });
    },
    onError: (e) => message.error(String((e as Error).message)),
  });

  const updatePlan = useMutation({
    mutationFn: ({ id, body }: { id: number; body: Parameters<typeof api.updateSchedulePlan>[1] }) =>
      api.updateSchedulePlan(id, body),
    onSuccess: () => {
      message.success(t('schedulePlans.toast.updated'));
      setEditing(null);
      qc.invalidateQueries({ queryKey: ['schedule-plans'] });
      qc.invalidateQueries({ queryKey: ['scheduler-config'] });
    },
    onError: (e) => message.error(String((e as Error).message)),
  });

  const archivePlan = useMutation({
    mutationFn: (id: number) => api.updateSchedulePlan(id, { status: 'archived' }),
    onSuccess: () => {
      message.success(t('schedulePlans.toast.archived'));
      qc.invalidateQueries({ queryKey: ['schedule-plans'] });
      qc.invalidateQueries({ queryKey: ['scheduler-config'] });
    },
    onError: (e) => message.error(String((e as Error).message)),
  });

  const reloadNow = useMutation({
    mutationFn: api.reloadSchedulerConfig,
    onSuccess: (r) => {
      message.success(t('schedulePlans.toast.reloaded', {
        factor: r.optimizationFactor, max: r.maxConcurrent,
      }));
      qc.invalidateQueries({ queryKey: ['scheduler-config'] });
    },
    onError: (e) => message.error(String((e as Error).message)),
  });

  const staleSetting = useQuery({
    queryKey: ['stale-pause-setting'],
    queryFn: api.stalePauseSetting,
  });
  const saveStaleSetting = useMutation({
    mutationFn: (days: number) => api.updateStalePauseSetting(days),
    onSuccess: (r) => {
      message.success(t('schedulePlans.toast.staleSaved', { d: r.autoDismissDays }));
      qc.invalidateQueries({ queryKey: ['stale-pause-setting'] });
    },
    onError: (e) => message.error(String((e as Error).message)),
  });

  if (error) {
    return <Alert type="error" showIcon message={t('schedulePlans.error.load')}
      description={String((error as Error).message)} />;
  }

  const items: Plan[] = data?.items ?? [];

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card size="small" title={t('schedulePlans.createTitle')}>
        <Form form={form} layout="inline"
          onFinish={(v) => createPlan.mutate({
            planNo: v.planNo || undefined,
            optimizationFactor: v.optimizationFactor,
            maxIterationsRunning: v.maxIterationsRunning,
            iterationDelaySec: v.iterationDelaySec,
          })}>
          <Form.Item name="planNo">
            <Input placeholder={t('schedulePlans.planNo')} style={{ width: 180 }} allowClear />
          </Form.Item>
          <Form.Item name="optimizationFactor" initialValue={50}
            tooltip={t('schedulePlans.factorHint')}>
            <InputNumber min={0} max={100} placeholder="50" />
          </Form.Item>
          <Form.Item name="maxIterationsRunning" initialValue={4}
            tooltip={t('schedulePlans.maxHint')}>
            <InputNumber min={1} max={4} placeholder="4" />
          </Form.Item>
          <Form.Item name="iterationDelaySec" initialValue={0}>
            <InputNumber min={0} placeholder="0" />
          </Form.Item>
          <Button type="primary" htmlType="submit" icon={<PlusOutlined />}
            loading={createPlan.isPending}>
            {t('schedulePlans.create')}
          </Button>
        </Form>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {t('schedulePlans.reloadHint')}
        </Typography.Text>
      </Card>

      <Card size="small"
        title={t('schedulePlans.listTitle', { n: items.length })}
        extra={<Space>
          <Button size="small" type="primary" loading={reloadNow.isPending}
            onClick={() => reloadNow.mutate()}>
            {t('schedulePlans.action.reloadNow')}
          </Button>
          <Button size="small" icon={<ReloadOutlined />} onClick={() => refetch()}>
            {t('schedulePlans.refresh')}
          </Button>
        </Space>}>
        <Table<Plan>
          rowKey="id"
          size="small"
          loading={isFetching}
          dataSource={items}
          pagination={false}
          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={t('schedulePlans.empty')} /> }}
          columns={[
            {
              title: t('schedulePlans.col.planNo'), dataIndex: 'planNo',
              render: (v: string) => <Typography.Text code>{v}</Typography.Text>,
            },
            {
              title: t('schedulePlans.col.status'), dataIndex: 'status', width: 100,
              render: (v: string) => (
                <Tag color={v === 'archived' ? 'default' : 'blue'}>{v}</Tag>
              ),
            },
            { title: t('schedulePlans.col.factor'), dataIndex: 'optimizationFactor', width: 90 },
            { title: t('schedulePlans.col.max'), dataIndex: 'maxIterationsRunning', width: 90 },
            { title: t('schedulePlans.col.delay'), dataIndex: 'iterationDelaySec', width: 90 },
            {
              title: t('schedulePlans.col.createdAt'), dataIndex: 'createdAt', width: 170,
              render: (v: string | null) => (v ? formatTime(v) : '—'),
            },
            {
              title: t('schedulePlans.col.action'), width: 180,
              render: (_, r) => (
                <Space size={4}>
                  {r.status !== 'archived' && (
                    <>
                      <Button size="small" type="link"
                        onClick={() => {
                          setEditing(r);
                          editForm.setFieldsValue(r);
                        }}>
                        {t('schedulePlans.action.edit')}
                      </Button>
                      <Button size="small" type="link" danger
                        loading={archivePlan.isPending}
                        onClick={() => archivePlan.mutate(r.id)}>
                        {t('schedulePlans.action.archive')}
                      </Button>
                    </>
                  )}
                </Space>
              ),
            },
          ]}
        />
      </Card>

      <Card size="small" title={t('schedulePlans.staleTitle')}>
        <Space size={12} wrap>
          <Typography.Text>{t('schedulePlans.staleAutoDismiss')}</Typography.Text>
          <InputNumber
            min={0} max={365}
            value={staleSetting.data?.autoDismissDays ?? 0}
            onChange={(v) => {
              if (v != null) {
                // 本地先改，点保存才落库（与排程参数一样，保存后约 60s 生效）
                saveStaleSetting.mutate(v);
              }
            }}
            disabled={saveStaleSetting.isPending}
          />
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {t('schedulePlans.staleHint')}
          </Typography.Text>
        </Space>
      </Card>

      <Modal
        title={t('schedulePlans.editTitle', { no: editing?.planNo ?? '' })}
        open={editing != null}
        onCancel={() => setEditing(null)}
        okText={t('schedulePlans.action.save')}
        confirmLoading={updatePlan.isPending}
        onOk={() => editForm.submit()}
      >
        <Form form={editForm} layout="vertical"
          onFinish={(v) => editing && updatePlan.mutate({
            id: editing.id,
            body: {
              optimizationFactor: v.optimizationFactor,
              maxIterationsRunning: v.maxIterationsRunning,
              iterationDelaySec: v.iterationDelaySec,
            },
          })}>
          <Form.Item name="optimizationFactor" label={t('schedulePlans.col.factor')}
            tooltip={t('schedulePlans.factorHint')}>
            <InputNumber min={0} max={100} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="maxIterationsRunning" label={t('schedulePlans.col.max')}
            tooltip={t('schedulePlans.maxHint')}>
            <InputNumber min={1} max={4} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="iterationDelaySec" label={t('schedulePlans.col.delay')}>
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  );
}
