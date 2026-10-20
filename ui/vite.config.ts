import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// base './'：产物用相对路径，供 Spring Boot jar 内静态资源直接托管
export default defineConfig({
  plugins: [vue()],
  base: './',
  build: {
    outDir: 'dist',
    assetsDir: 'assets',
    chunkSizeWarningLimit: 800
  },
  server: {
    proxy: { '/api': 'http://127.0.0.1:8791' }
  }
})
