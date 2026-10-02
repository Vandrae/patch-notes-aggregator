import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// Dev: the app runs on :5173 and proxies the API to the Spring Boot server on :8080, so the browser sees one origin
// (cookies and CSRF just work). For Steam sign-in to come back through the proxy, run the backend with
// PUBLIC_BASE_URL=http://localhost:5173.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
  build: { outDir: 'dist' },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test-setup.ts',
    css: false,
  },
});
