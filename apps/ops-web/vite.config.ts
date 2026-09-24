import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// 개발 서버만 /api 를 ops-api(.env 의 OPS_API_PORT, 기본 8080)로 넘긴다. 컨테이너에서는 nginx 가 같은 일을 한다(같은 출처, CORS 를 열지 않는다).
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: { '/api': process.env.OPS_API_URL ?? 'http://localhost:8080' },
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
    setupFiles: ['src/test/setup.ts'],
  },
});
