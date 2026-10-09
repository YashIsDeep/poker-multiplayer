import { spawn } from 'node:child_process'
import { existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

// This launcher deliberately does not load .env or start the shared H2 process.
// Tests use Vite (including development-only UI fixtures) and a packaged server
// with a disposable in-memory database.
const root = fileURLToPath(new URL('..', import.meta.url))
const jar = fileURLToPath(new URL('../server/target/server-0.0.1-SNAPSHOT.jar', import.meta.url))
const origin = new URL(process.env.E2E_BASE_URL || 'http://127.0.0.1:15173')
const serverPort = Number(process.env.E2E_SERVER_PORT || 18080)
if (!Number.isInteger(serverPort) || serverPort < 1 || serverPort > 65535
    || serverPort === Number(origin.port || 80)) {
  throw new Error('E2E_SERVER_PORT must be a valid port distinct from E2E_BASE_URL.')
}
if (origin.protocol !== 'http:' || origin.hostname !== '127.0.0.1'
    || origin.pathname !== '/' || origin.search || origin.hash || origin.username || origin.password) {
  throw new Error('E2E_BASE_URL must be an HTTP origin on 127.0.0.1.')
}
if (!existsSync(jar)) {
  throw new Error('Packaged application not found. Run npm run build before the browser suite.')
}

const environment = Object.fromEntries(Object.entries(process.env).filter(([name]) =>
  !/^(SPRING_|SERVER_|POKER_|DB_|GOOGLE_|DISCORD_|OAUTH_|SESSION_|TUNNEL_|PROXY_|VITE_|ANALYTICS_|HAND_ARCHIVE_)/i.test(name)
  && !/^(JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS)$/i.test(name)))

const server = spawn('java', [
  '-jar', jar,
  '--spring.profiles.active=dev',
  '--server.address=127.0.0.1',
  `--server.port=${serverPort}`,
  '--server.servlet.session.cookie.secure=false',
  '--spring.datasource.url=jdbc:h2:mem:poker-e2e;DB_CLOSE_DELAY=-1',
  '--poker.ranked.enabled=true',
  '--poker.hand-archive.enabled=false',
  '--poker.admin.live.enabled=false',
  `--poker.websocket.allowed-origins=${origin.origin}`,
], { cwd: root, env: { ...environment, OAUTH_ENABLED: 'false' }, stdio: 'inherit', windowsHide: true })

const client = spawn(process.execPath, [
  fileURLToPath(new URL('../client/node_modules/vite/bin/vite.js', import.meta.url)),
  '--config', 'e2e/vite.config.ts',
  '--host', '127.0.0.1', '--port', origin.port || '80', '--strictPort',
], {
  cwd: fileURLToPath(new URL('../client', import.meta.url)),
  env: { ...environment, E2E_SERVER_ORIGIN: `http://127.0.0.1:${serverPort}` },
  stdio: 'inherit', windowsHide: true,
})

let stopping = false
function stop(signal = 'SIGTERM') {
  if (stopping) return
  stopping = true
  for (const child of [server, client]) {
    if (child.exitCode !== null) continue
    if (process.platform === 'win32') {
      // Node's signal emulation on Windows only targets the immediate child;
      // taskkill also tears down Java/Vite descendants so Playwright can exit.
      spawn('taskkill', ['/pid', String(child.pid), '/t', '/f'], {
        stdio: 'ignore', windowsHide: true,
      })
    } else {
      child.kill(signal)
    }
  }
  // A failed child can otherwise keep the webServer command alive forever if
  // the OS does not deliver an exit event for a descendant.
  setTimeout(() => process.exit(process.exitCode ?? 0), 5000).unref()
}
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => stop(signal))
}
for (const [name, child] of [['server', server], ['client', client]]) {
  child.once('error', (error) => {
    console.error(`Unable to start the browser test ${name}: ${error.message}`)
    process.exitCode = 1
    stop()
  })
  child.once('exit', (code) => {
    if (!stopping) {
      process.exitCode = code ?? 1
      stop()
    }
  })
}
