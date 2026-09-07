// T10 E2E config. Runs against the local dev stack (Vite on 5173 proxying the
// Spring Boot server on 8080). Reuses an already-running `npm run dev`; when
// none is running it boots one from the repo root.
//
// Scenario gating:
//   E2E_GAME=1        enables gameplay assertions (requires the T6 server work)
//   E2E_KILL_SERVER=1 enables the kill-server recovery scenario

import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './scenarios',
  fullyParallel: false, // scenarios share one server; several use multi-context choreography
  workers: 1,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  retries: 0,
  use: {
    baseURL: 'http://127.0.0.1:5173',
    trace: 'retain-on-failure',
    video: 'retain-on-failure',
  },
  webServer: {
    command: 'npm run dev',
    env: { POKER_RANKED_ENABLED: 'true' },
    cwd: '../..',
    // Wait for the proxied Spring endpoint, not only Vite. Vite becomes ready
    // much earlier and otherwise the first dev-login can race server startup.
    url: 'http://127.0.0.1:5173/api/meta',
    reuseExistingServer: true,
    timeout: 180_000,
  },
})
