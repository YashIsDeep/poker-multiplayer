import { spawn } from 'node:child_process'
import { resolve } from 'node:path'

const root = resolve('.')
const productionMode = process.env.PROXY_MODE === 'production'
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
  const isWindows = process.platform === 'win32'
  const npx = isWindows ? 'npx.cmd' : 'npx'

  switch (provider) {
    case 'localtunnel': {
      const args = ['--yes', 'localtunnel', '--port', String(port), '--local-host', '127.0.0.1']
      const subdomain = process.env.TUNNEL_SUBDOMAIN?.trim()
      if (subdomain) {
        if (!/^[a-z0-9-]+$/i.test(subdomain)) {
          throw new Error('TUNNEL_SUBDOMAIN may contain only letters, numbers, and hyphens.')
        }
        args.push('--subdomain', subdomain)
      }
      // Windows cannot spawn .cmd shims directly with Node's default shell
      // mode. Running the npm shim through cmd.exe avoids EINVAL while the
      // subdomain validation above keeps shell arguments constrained.
      return { command: npx, args, shell: isWindows }
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
  if (!['http:', 'https:'].includes(url.protocol)) {
    throw new Error('the public URL must use http:// or https://')
  }
  return url.origin
}

function isRecord(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function productionEnvironment(publicUrl) {
  const environment = { ...process.env }
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
  const origins = Array.isArray(configuredOrigins)
    ? configuredOrigins.filter((origin) => typeof origin === 'string' && origin.trim())
    : ['http://127.0.0.1:5173', 'http://localhost:5173']

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
      if (expectedTunnelHost(url.hostname)) return url.origin
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
  env: process.env,
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
  appEnvironment = productionMode
    ? productionEnvironment(publicUrl)
    : {
        ...process.env,
        TUNNEL_MODE: 'true',
        VITE_TUNNEL_HOST: publicHost,
      }
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
