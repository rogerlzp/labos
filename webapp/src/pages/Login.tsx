import { useState } from 'react';
import { Alert, Button, Card, Form, Input, Select, Typography } from 'antd';
import { LockOutlined, UserOutlined } from '@ant-design/icons';
import { api, tokenStore } from '../api/client';
import { useI18n, useT } from '../i18n';
import markUrl from '../assets/labos-mark.svg';

export default function LoginPage({ onSuccess }: { onSuccess: () => void }) {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const t = useT();
  const { locale, setLocale } = useI18n();

  const submit = async (v: { username: string; password: string }) => {
    setLoading(true);
    setError(null);
    try {
      const r = await api.login(v.username, v.password);
      tokenStore.set(r.token);
      onSuccess();
    } catch (e) {
      setError(String((e as Error).message));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={{
      height: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center',
      background: '#0d1014',
    }}>
      <Card style={{ width: 360 }}>
        {/* 语言切换必须放在登录页：登录页是**没账号的人**看到的第一屏，
            把它藏进登录后的 Header 等于让英文部署的现场先卡在中文登录框上。 */}
        <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 8 }}>
          <Select
            size="small"
            value={locale}
            onChange={(v) => setLocale(v as 'zh-CN' | 'en-US')}
            style={{ width: 108 }}
            options={[
              { value: 'zh-CN', label: '中文' },
              { value: 'en-US', label: 'English' },
            ]}
          />
        </div>
        {/* 登录页是"没账号的人"看到的第一屏：徽标 + 系统名 + 副标题一起给，
            别让人以为是别家系统的登录框。 */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16 }}>
          <img
            src={markUrl}
            alt="LabOS"
            width={40}
            height={40}
            style={{ borderRadius: 10, display: 'block' }}
          />
          <div>
            <Typography.Title level={4} style={{ marginTop: 0, marginBottom: 2 }}>
              {t('app.title')}
            </Typography.Title>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {t('login.subtitle')}
            </Typography.Text>
          </div>
        </div>

        {error && <Alert type="error" showIcon message={error} style={{ marginTop: 16 }} />}

        <Form layout="vertical" onFinish={submit} style={{ marginTop: 16 }}
          initialValues={{ username: 'admin', password: '' }}>
          <Form.Item name="username" label={t('login.username')} rules={[{ required: true }]}>
            <Input prefix={<UserOutlined />} autoComplete="username" />
          </Form.Item>
          <Form.Item name="password" label={t('login.password')} rules={[{ required: true }]}>
            <Input.Password prefix={<LockOutlined />} autoComplete="current-password" />
          </Form.Item>
          <Button type="primary" htmlType="submit" block loading={loading}>{t('login.submit')}</Button>
        </Form>

        <Typography.Paragraph type="secondary" style={{ fontSize: 11, marginTop: 12, marginBottom: 0 }}>
          {t('login.defaultAccount')}
        </Typography.Paragraph>
      </Card>
    </div>
  );
}
