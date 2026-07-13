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
    // PORT override lets tooling run a second instance; OAuth flows still
    // need the default 5173 origin registered with the providers.
    port: Number(process.env.PORT) || 5173,
    strictPort: true,
    proxy: {
      '/api': 'http://127.0.0.1:8080',
      '/auth': 'http://127.0.0.1:8080',
      '/oauth2': 'http://127.0.0.1:8080',
      '/login': 'http://127.0.0.1:8080',
      '/logout': 'http://127.0.0.1:8080',
      '/ws': { target: 'http://127.0.0.1:8080', ws: true },
    },
  },
})
