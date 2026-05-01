import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 3000,
    proxy: {
      '/auth':          { target: 'http://localhost:8081', changeOrigin: true },
      '/api/tickets':   { target: 'http://localhost:8082', changeOrigin: true },
      '/api/orders':    { target: 'http://localhost:8083', changeOrigin: true },
      '/api/pricing':   { target: 'http://localhost:8085', changeOrigin: true },
      '/api/reservations': { target: 'http://localhost:8086', changeOrigin: true },
      '/api/payments':  { target: 'http://localhost:8087', changeOrigin: true },
      '/api/secondary': { target: 'http://localhost:8088', changeOrigin: true },
      '/api/notifications': { target: 'http://localhost:8089', changeOrigin: true },
    },
  },
})
