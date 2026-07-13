import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// changeOrigin stays false so Spring sees host 5173 and generates
// OAuth redirect URIs on the proxied origin.
const tunnelMode = process.env.TUNNEL_MODE === 'true'
const tunnelHost = process.env.VITE_TUNNEL_HOST?.trim()

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
    // Allow only the hostname published by dev:proxy.
    allowedHosts: tunnelHost ? [tunnelHost] : [],
    proxy: {
      '/api': 'http://127.0.0.1:8080',
      '/auth': 'http://127.0.0.1:8080',
      '/oauth2': 'http://127.0.0.1:8080',
      '/login': 'http://127.0.0.1:8080',
      '/logout': 'http://127.0.0.1:8080',
      '/ws': {
        target: 'http://127.0.0.1:8080',
        ws: true,
        ...(tunnelMode
          ? {
              // The remote browser is same-origin with the Vite proxy. Keep
              // Spring's loopback origin allow-list while forwarding the
              // tunnel handshake through the local development proxy.
              configure: (proxy) => {
                proxy.on('proxyReqWs', (proxyRequest) => {
                  proxyRequest.setHeader('origin', 'http://127.0.0.1:5173')
                })
              },
            }
          : {}),
      },
    },
  },
})
