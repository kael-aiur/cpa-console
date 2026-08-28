import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => ({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': 'http://127.0.0.1:8080',
      '/admin': 'http://127.0.0.1:8080',
    },
  },
  build: {
    sourcemap: mode !== 'production',
    assetsInlineLimit: 4096,
  },
}))
