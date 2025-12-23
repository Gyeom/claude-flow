import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'

// API URL: Docker에서는 VITE_API_URL 환경변수 사용, 로컬에서는 localhost
const apiUrl = process.env.VITE_API_URL || 'http://localhost:8080'

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    port: 3000,
    host: true, // Docker에서 외부 접근 허용
    proxy: {
      // SSE 스트림 전용 프록시 (버퍼링 비활성화)
      '/api/v1/logs/stream': {
        target: apiUrl,
        changeOrigin: true,
      },
      // 일반 API
      '/api': {
        target: apiUrl,
        changeOrigin: true,
      },
    },
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks: {
          // Vendor chunks - split large dependencies
          'vendor-react': ['react', 'react-dom', 'react-router-dom'],
          'vendor-query': ['@tanstack/react-query'],
          'vendor-charts': ['recharts'],
          'vendor-ui': ['lucide-react', 'sonner', 'clsx', 'tailwind-merge'],
        },
      },
    },
  },
})
