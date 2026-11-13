import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 开发期把 /api 代理到 Java 内核（18080），生产由网关转发
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // 绑 0.0.0.0，同一局域网的其他机器才能打开（默认只绑 127.0.0.1）
    host: '0.0.0.0',
    proxy: {
      // changeOrigin 只改 Host，不改 Origin。
      // 通过 frp 把本机暴露到公网后，浏览器发来的 Origin 是公网地址
      // （如 http://8.130.12.148:9001），而 Host 被改成 localhost:18080，
      // 内核 Spring 判定 Origin≠Host = 跨域请求 → 403 Invalid CORS request，
      // 登录接口 /api/auth/login 直接被拒。
      // 代理层把 Origin 改写成与目标同源，等价于浏览器同源访问。
      '/api': {
        target: 'http://localhost:18080',
        changeOrigin: true,
        headers: { origin: 'http://localhost:18080' },
      },
      '/vendor': {
        target: 'http://localhost:18080',
        changeOrigin: true,
        headers: { origin: 'http://localhost:18080' },
      },
    },
  },
  build: { outDir: 'dist', sourcemap: true },
});
