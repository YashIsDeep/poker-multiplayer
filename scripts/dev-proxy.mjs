import { spawn } from 'node:child_process'
import { assertTunnelAuthentication } from './auth-mode.mjs'
import { resolve } from 'node:path'

const root = resolve('.')
const productionMode = process.env.PROXY_MODE === 'production'
try {
  assertTunnelAuthentication(process.env, productionMode, process.argv.slice(2))
} catch (error) {
  console.error(error.message)
  process.exit(1)
}
const provider = (process.env.TUNNEL_PROVIDER || 'localtunnel').trim().toLowerCase()
const port = Number(
  process.env.TUNNEL_PORT
    || (productionMode ? 8080 : (process.env.PORT || 5173)),
)
const target = (process.env.TUNNEL_TARGET || `http://127.0.0.1:${port}`).trim()
const startupTimeoutMs = Number(process.env.TUNNEL_STARTUP_TIMEOUT_MS || 45_000)

if (!Number.isInteger(port) || port < 1 || port > 65_535) {
  console.error(`Invalid tunnel port: ${port}`)
  process.exit(1)
}

try {
  const targetUrl = new URL(target)
  if (!['http:', 'https:'].includes(targetUrl.protocol)) {
    throw new Error('the target must use http:// or https://')
  }
} catch (error) {
  console.error(`Invalid TUNNEL_TARGET: ${error.message}`)
  process.exit(1)
}

function tunnelCommand() {
  switch (provider) {
    case 'localtunnel': {
      const executable = resolve(root, 'node_modules', 'localtunnel', 'bin', 'lt.js')
      const args = [executable, '--port', String(port), '--local-host', '127.0.0.1']
      const subdomain = process.env.TUNNEL_SUBDOMAIN?.trim()
      if (subdomain) {
        if (!/^[a-z0-9-]+$/i.test(subdomain)) {
          throw new Error('TUNNEL_SUBDOMAIN may contain only letters, numbers, and hyphens.')
        }
        args.push('--subdomain', subdomain)
      }
      return { command: process.execPath, args }
    }
    case 'cloudflared':
      return { command: 'cloudflared', args: ['tunnel', '--url', target] }
    case 'ngrok': {
      const args = ['http']
      const domain = process.env.TUNNEL_DOMAIN?.trim()
      if (domain) {
        if (!/^[a-z0-9-]+(\.[a-z0-9-]+)+$/i.test(domain)) {
          throw new Error('TUNNEL_DOMAIN must be a hostname such as my-table.ngrok-free.dev.')
        }
        // Reserved ngrok domains keep the public URL stable across restarts.
        // `--domain` works on both old and current agents; newer ngrok builds
        // spell the same option `--url`, which 3.x agents reject outright.
        args.push(`--domain=${domain}`)
      }
      args.push(target)
      return { command: 'ngrok', args }
    }
    default:
      throw new Error(
        `Unsupported TUNNEL_PROVIDER "${provider}". Use localtunnel, cloudflared, or ngrok.`,
      )
  }
}

function tunnelEnvironment() {
  const allowed = [
    'PATH', 'Path', 'PATHEXT', 'SystemRoot', 'WINDIR', 'TEMP', 'TMP',
    'HTTP_PROXY', 'HTTPS_PROXY', 'NO_PROXY', 'http_proxy', 'https_proxy', 'no_proxy',
  ]
  if (provider === 'ngrok') allowed.push('NGROK_AUTHTOKEN')
  return Object.fromEntries(allowed
    .filter(key => process.env[key] !== undefined)
    .map(key => [key, process.env[key]]))
}

function expectedTunnelHost(hostname) {
  switch (provider) {
    case 'localtunnel':
      return hostname.endsWith('.loca.lt') || hostname.endsWith('.localtunnel.me')
    case 'cloudflared':
      return hostname.endsWith('.trycloudflare.com')
    case 'ngrok':
      return hostname.endsWith('.ngrok-free.app')
        || hostname.endsWith('.ngrok-free.dev')
        || hostname.endsWith('.ngrok.app')
        || hostname.endsWith('.ngrok.dev')
        || hostname.endsWith('.ngrok.io')
    default:
      return false
  }
}

function normalizePublicUrl(value) {
  const url = new URL(value)
  if (url.protocol !== 'https:') {
    throw new Error('the public OAuth URL must use https://')
  }
  return url.origin
}

function isRecord(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function productionEnvironment(publicUrl) {
  const environment = { ...process.env, TUNNEL_MODE: 'true' }
  let applicationJson = {}
  const configuredJson = environment.SPRING_APPLICATION_JSON?.trim()

  if (configuredJson) {
    try {
      applicationJson = JSON.parse(configuredJson)
    } catch (error) {
      throw new Error(`Invalid SPRING_APPLICATION_JSON: ${error.message}`)
    }
    if (!isRecord(applicationJson)) {
      throw new Error('SPRING_APPLICATION_JSON must contain a JSON object.')
    }
  }

  const poker = isRecord(applicationJson.poker) ? applicationJson.poker : {}
  const websocket = isRecord(poker.websocket) ? poker.websocket : {}
  const configuredOrigins = websocket['allowed-origins']
  // In development the browser also reaches the Vite dev server directly, so
  // those origins stay. A production tunnel serves the SPA from the public
  // origin alone: seeding the allowlist with localhost there would leave two
  // origins trusted for the STOMP handshake that nothing ever uses.
  const defaultOrigins = productionMode ? [] : ['http://127.0.0.1:5173', 'http://localhost:5173']
  const origins = Array.isArray(configuredOrigins)
    ? configuredOrigins.filter((origin) => typeof origin === 'string' && origin.trim())
    : defaultOrigins

  applicationJson.poker = {
    ...poker,
    websocket: {
      ...websocket,
      'allowed-origins': [...new Set([...origins, publicUrl])],
    },
  }
  environment.SPRING_APPLICATION_JSON = JSON.stringify(applicationJson)
  return environment
}

function findPublicUrl(output) {
  const candidates = output.match(/https?:\/\/[A-Za-z0-9.-]+(?::\d+)?/g) || []
  for (const candidate of candidates.reverse()) {
    try {
      const url = new URL(candidate)
      if (url.protocol === 'https:' && expectedTunnelHost(url.hostname)) return url.origin
    } catch {
      // Ignore non-URL output from the tunnel client.
    }
  }
  return null
}

function stopChild(child, signal = 'SIGTERM') {
  if (!child || child.exitCode !== null || child.killed) return
  try {
    child.kill(signal)
  } catch {
    // The process may already have exited between the checks above.
  }
}

function waitForPublicUrl(tunnel) {
  const configuredUrl = process.env.TUNNEL_PUBLIC_URL?.trim()

  return new Promise((resolveUrl, reject) => {
    let settled = false
    let output = ''
    const timer = setTimeout(() => {
      settleReject(new Error(
        `Timed out waiting for the ${provider} tunnel URL. `
          + 'Set TUNNEL_PUBLIC_URL if the provider uses a custom hostname.',
      ))
    }, startupTimeoutMs)

    const settleResolve = (url) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      resolveUrl(url)
    }

    const settleReject = (error) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      reject(error)
    }

    const consume = (chunk, stream) => {
      const text = chunk.toString()
      stream.write(text)
      output = `${output}${text}`.slice(-16_384)
      const url = findPublicUrl(output)
      if (url) settleResolve(url)
    }

    tunnel.stdout?.on('data', (chunk) => consume(chunk, process.stdout))
    tunnel.stderr?.on('data', (chunk) => consume(chunk, process.stderr))
    tunnel.once('error', (error) => {
      const installHint = provider === 'localtunnel'
        ? ''
        : ` Install ${provider} or set TUNNEL_PROVIDER=localtunnel.`
      settleReject(new Error(`Unable to start ${provider}: ${error.message}.${installHint}`))
    })
    tunnel.once('exit', (code, signal) => {
      settleReject(new Error(
        `${provider} exited before publishing a URL (code=${code ?? 'none'}, signal=${signal ?? 'none'}).`,
      ))
    })

    if (configuredUrl) {
      try {
        settleResolve(normalizePublicUrl(configuredUrl))
      } catch (error) {
        settleReject(new Error(`Invalid TUNNEL_PUBLIC_URL: ${error.message}`))
      }
    }
  })
}

let spec
try {
  spec = tunnelCommand()
} catch (error) {
  console.error(error.message)
  process.exit(1)
}

const appLabel = productionMode ? 'production server' : 'development stack'
console.log(`Starting ${provider} tunnel for ${target}...`)
const tunnel = spawn(spec.command, spec.args, {
  cwd: root,
  // Do not expose application OAuth/database credentials to the tunnel tool.
  env: tunnelEnvironment(),
  shell: spec.shell,
  windowsHide: true,
  stdio: ['inherit', 'pipe', 'pipe'],
})

let publicUrl
try {
  publicUrl = await waitForPublicUrl(tunnel)
} catch (error) {
  stopChild(tunnel)
  console.error(error.message)
  process.exit(1)
}

const publicHost = new URL(publicUrl).hostname
console.log(`Remote URL: ${publicUrl}`)
console.log(`Starting the local ${appLabel}...`)

let appEnvironment
try {
  appEnvironment = productionEnvironment(publicUrl)
  if (!productionMode) appEnvironment.VITE_TUNNEL_HOST = publicHost
} catch (error) {
  stopChild(tunnel)
  console.error(error.message)
  process.exit(1)
}

const app = spawn(
  process.execPath,
  [
    '--env-file=.env',
    productionMode ? 'scripts/start.mjs' : 'scripts/dev.mjs',
    ...process.argv.slice(2),
  ],
  {
    cwd: root,
    env: appEnvironment,
    stdio: 'inherit',
  },
)

let shuttingDown = false
function shutdown(code, signal = 'SIGTERM') {
  if (shuttingDown) return
  shuttingDown = true
  stopChild(app, signal)
  stopChild(tunnel, signal)
  process.exitCode = code
}

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => shutdown(0, signal))
}

app.on('error', (error) => {
  console.error(`Unable to start the local ${appLabel}: ${error.message}`)
  shutdown(1)
})

app.on('exit', (code, signal) => {
  if (!shuttingDown) shutdown(signal ? 1 : (code ?? 1))
})

tunnel.on('error', (error) => {
  if (shuttingDown) return
  console.error(`Tunnel error: ${error.message}`)
  shutdown(1)
})

tunnel.on('exit', (code, signal) => {
  if (shuttingDown) return
  console.error(
    `Tunnel stopped (code=${code ?? 'none'}, signal=${signal ?? 'none'}); stopping the local ${appLabel}.`,
  )
  shutdown(code ?? 1)
})
