import { spawn, spawnSync } from 'node:child_process'
import { closeSync, existsSync, mkdirSync, openSync, readdirSync } from 'node:fs'
import { createConnection } from 'node:net'
import { dirname, resolve } from 'node:path'
import { createInterface } from 'node:readline/promises'
import { fileURLToPath } from 'node:url'

const scriptDirectory = dirname(fileURLToPath(import.meta.url))
const projectDirectory = resolve(scriptDirectory, '..')
const serverDirectory = resolve(projectDirectory, 'server')
const dataDirectory = resolve(serverDirectory, 'data')
// Kept outside server/target so `mvn clean` cannot collide with the jar that a
// running dev database holds open.
const toolsDirectory = resolve(projectDirectory, '.cache', 'db-tools')
const logFile = resolve(dataDirectory, 'h2-server.log')
const host = process.env.DB_HOST?.trim() || '127.0.0.1'
const port = Number.parseInt(process.env.DB_PORT?.trim() || '9092', 10)
const shutdownPassword = 'poker-dev-shutdown'
const startupTimeoutMs = 10_000
const shutdownTimeoutMs = 5_000

if (!Number.isInteger(port) || port < 1 || port > 65_535) {
  throw new Error(`Invalid DB_PORT: ${process.env.DB_PORT}`)
}

function findH2Jar() {
  if (!existsSync(toolsDirectory)) return null

  const copiedJar = readdirSync(toolsDirectory)
    .filter((name) => /^h2-[\d.]+\.jar$/.test(name))
    .sort((left, right) => left.localeCompare(right, undefined, { numeric: true }))
    .at(-1)

  return copiedJar ? resolve(toolsDirectory, copiedJar) : null
}

function ensureH2Jar() {
  const existingJar = findH2Jar()
  if (existingJar) return existingJar

  console.log('Preparing the H2 database tools...')
  const wrapper = process.platform === 'win32' ? 'mvnw.cmd' : './mvnw'
  const mavenArguments = [
    '-B',
    '-ntp',
    'dependency:copy-dependencies',
    '-DincludeGroupIds=com.h2database',
    '-DincludeArtifactIds=h2',
    '-DincludeScope=runtime',
    `-DoutputDirectory=${toolsDirectory}`,
  ]
  const result = spawnSync(
    resolve(serverDirectory, wrapper),
    mavenArguments,
    {
      cwd: serverDirectory,
      stdio: 'inherit',
      shell: process.platform === 'win32',
    },
  )

  if (result.error) throw result.error
  if (result.status !== 0) {
    throw new Error(`Unable to prepare H2 database tools (Maven exited with ${result.status}).`)
  }

  const copiedJar = findH2Jar()
  if (!copiedJar) throw new Error('Maven completed but no H2 jar was found.')
  return copiedJar
}

function canConnect() {
  return new Promise((resolveConnection) => {
    const socket = createConnection({ host, port })
    const finish = (connected) => {
      socket.destroy()
      resolveConnection(connected)
    }

    socket.setTimeout(400)
    socket.once('connect', () => finish(true))
    socket.once('timeout', () => finish(false))
    socket.once('error', () => finish(false))
  })
}

async function waitForState(expectedRunning, timeoutMs) {
  const deadline = Date.now() + timeoutMs

  do {
    if (await canConnect() === expectedRunning) return true
    await new Promise((resolveWait) => setTimeout(resolveWait, 100))
  } while (Date.now() < deadline)

  return false
}

export async function isDatabaseRunning() {
  return canConnect()
}

export async function startDatabase() {
  if (await isDatabaseRunning()) {
    console.log(`Database is already running at tcp://${host}:${port}.`)
    return false
  }

  const h2Jar = ensureH2Jar()
  mkdirSync(dataDirectory, { recursive: true })

  const log = openSync(logFile, 'a')
  let child

  try {
    child = spawn(
      'java',
      [
        '-cp',
        h2Jar,
        'org.h2.tools.Server',
        '-tcp',
        '-tcpPort',
        String(port),
        '-tcpPassword',
        shutdownPassword,
        '-ifNotExists',
        '-baseDir',
        serverDirectory,
      ],
      {
        cwd: serverDirectory,
        detached: true,
        stdio: ['ignore', log, log],
        windowsHide: true,
      },
    )
  } finally {
    closeSync(log)
  }

  child.unref()

  const started = await Promise.race([
    waitForState(true, startupTimeoutMs),
    new Promise((_, rejectStart) => {
      child.once('error', rejectStart)
    }),
  ])

  if (!started) {
    throw new Error(`Database did not start within ${startupTimeoutMs / 1000} seconds. Check ${logFile}.`)
  }

  console.log(`Database started at tcp://${host}:${port}.`)
  return true
}

export async function stopDatabase() {
  if (!await isDatabaseRunning()) {
    console.log('Database is already stopped.')
    return false
  }

  const h2Jar = ensureH2Jar()
  const result = spawnSync(
    'java',
    [
      '-cp',
      h2Jar,
      'org.h2.tools.Server',
      '-tcpShutdown',
      `tcp://${host}:${port}`,
      '-tcpPassword',
      shutdownPassword,
      '-tcpShutdownForce',
    ],
    { cwd: serverDirectory, stdio: 'inherit' },
  )

  if (result.error) throw result.error
  if (result.status !== 0) {
    throw new Error(`Unable to stop the database (Java exited with ${result.status}).`)
  }
  if (!await waitForState(false, shutdownTimeoutMs)) {
    throw new Error(`Database did not stop within ${shutdownTimeoutMs / 1000} seconds.`)
  }

  console.log('Database stopped.')
  return true
}

export async function restartDatabase() {
  if (await isDatabaseRunning()) await stopDatabase()
  else console.log('Database is stopped; starting it now.')

  await startDatabase()
}

async function askToRestart() {
  if (!process.stdin.isTTY || !process.stdout.isTTY) {
    console.log('Database is already running; keeping it because consent cannot be requested in a non-interactive terminal.')
    return false
  }

  const prompt = createInterface({ input: process.stdin, output: process.stdout })

  try {
    const answer = await prompt.question('Database is already running. Restart it before starting dev? [y/N] ')
    return /^(y|yes)$/i.test(answer.trim())
  } finally {
    prompt.close()
  }
}

export async function prepareDatabaseForDev() {
  if (!await isDatabaseRunning()) {
    await startDatabase()
    return
  }

  if (await askToRestart()) await restartDatabase()
  else console.log('Using the running database.')
}

async function main() {
  const action = process.argv[2]

  if (action === 'start') await startDatabase()
  else if (action === 'stop') await stopDatabase()
  else if (action === 'restart') await restartDatabase()
  else {
    console.error('Usage: node scripts/database.mjs <start|stop|restart>')
    process.exitCode = 1
  }
}

const invokedFile = process.argv[1] ? resolve(process.argv[1]) : null
if (invokedFile === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    console.error(error.message)
    process.exitCode = 1
  })
}
