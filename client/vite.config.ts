import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// changeOrigin must stay false so Spring sees the browser's host (:5173) and
// generates OAuth redirect URIs on the proxied origin. Vite expands the
// shorthand string form to `{ target, changeOrigin: true }`, which would send
// providers back to :8080, so every entry spells the option out.
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
      '/api': { target: 'http://127.0.0.1:8080', changeOrigin: false },
      '/auth': { target: 'http://127.0.0.1:8080', changeOrigin: false },
      '/oauth2': { target: 'http://127.0.0.1:8080', changeOrigin: false },
      // Only the OAuth callback belongs to the server; /login itself is the SPA sign-in route.
      '/login/oauth2': { target: 'http://127.0.0.1:8080', changeOrigin: false },
      '/logout': { target: 'http://127.0.0.1:8080', changeOrigin: false },
      '/ws': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: false,
        ws: true,
        // Preserve the browser Origin. dev:proxy configures the exact public
        // origin in Spring's allowlist when it starts an OAuth-only tunnel.
      },
    },
  },
})
