import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

const backend = process.env.VITE_BACKEND_ORIGIN || 'http://127.0.0.1:8080';

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: backend,
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
});
