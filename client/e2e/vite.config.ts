import { mergeConfig } from 'vite'
import baseConfig from '../vite.config'

// Preserve the regular Vite proxy behavior while targeting the isolated E2E jar.
const target = process.env.E2E_SERVER_ORIGIN || 'http://127.0.0.1:18080'
export default mergeConfig(baseConfig, {
  server: {
    proxy: Object.fromEntries(Object.keys(baseConfig.server?.proxy || {})
      .map((path) => [path, { target }])),
  },
})
