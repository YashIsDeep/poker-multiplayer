import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// changeOrigin stays false so Spring sees host 5173 and generates
// OAuth redirect URIs on the proxied origin.
export default defineConfig({
  plugins: [react()],
  server: {
    // Bind IPv4 loopback explicitly: on Windows, binding the default
    // 'localhost' can pick ::1 only, leaving 127.0.0.1 unreachable.
    host: '127.0.0.1',
    strictPort: true,
    proxy: {
      '/api': 'http://localhost:8080',
      '/auth': 'http://localhost:8080',
      '/oauth2': 'http://localhost:8080',
      '/login': 'http://localhost:8080',
      '/logout': 'http://localhost:8080',
      '/ws': { target: 'http://localhost:8080', ws: true },
    },
  },
})
