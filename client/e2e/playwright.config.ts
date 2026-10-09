// Run Vite against an isolated packaged loopback server and in-memory database.
// Build the server before starting this suite.
//
// Scenario gating:
//   E2E_GAME=0       disables gameplay assertions for transport-only debugging
//   E2E_REUSE=1      opts in to an existing local server at E2E_BASE_URL

import { defineConfig } from '@playwright/test'

const reuse = process.env.E2E_REUSE === '1'
const origin = new URL(process.env.E2E_BASE_URL || 'http://127.0.0.1:15173')
if (origin.protocol !== 'http:' || origin.hostname !== '127.0.0.1'
    || origin.pathname !== '/' || origin.search || origin.hash || origin.username || origin.password) {
  throw new Error('E2E_BASE_URL must be an HTTP origin on 127.0.0.1.')
}
const baseURL = origin.origin

export default defineConfig({
  testDir: './scenarios',
  fullyParallel: false, // scenarios share one server; several use multi-context choreography
  workers: 1,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  retries: 0,
  use: {
    baseURL,
    trace: 'retain-on-failure',
    video: 'retain-on-failure',
  },
  webServer: {
    command: 'node scripts/e2e-server.mjs',
    env: { E2E_BASE_URL: baseURL },
    cwd: '../..',
    url: `${baseURL}/api/meta`,
    reuseExistingServer: reuse,
    timeout: 180_000,
  },
})
