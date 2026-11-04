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
      '/api': { target: 'http://localhost:18080', changeOrigin: true },
      '/vendor': { target: 'http://localhost:18080', changeOrigin: true },
    },
  },
  build: { outDir: 'dist', sourcemap: true },
});
