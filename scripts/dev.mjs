import { spawn } from 'node:child_process'
import { resolve } from 'node:path'
import { prepareDatabaseForDev } from './database.mjs'
import { assertLocalAuthentication, developmentProfiles } from './auth-mode.mjs'

let profiles
try {
  profiles = developmentProfiles(process.env)
  assertLocalAuthentication(process.env, profiles)
} catch (error) {
  console.error(error.message)
  process.exit(1)
}

await prepareDatabaseForDev()

const concurrently = resolve('node_modules', 'concurrently', 'dist', 'bin', 'index.js')
const serverCommand = `cd server && .\\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=${profiles}`
const serverOnly = process.argv.includes('--server-only')
const commands = serverOnly ? [serverCommand] : [serverCommand, 'npm run dev:client']
const names = serverOnly ? 'server' : 'server,client'
const colors = serverOnly ? 'blue' : 'blue,green'

const child = spawn(
  process.execPath,
  [concurrently, '-k', '-n', names, '-c', colors, ...commands],
  { stdio: 'inherit', env: {
    ...process.env,
    // OAuth-only tunnel runs retain the development database and loopback listener.
    SERVER_ADDRESS: process.env.SERVER_ADDRESS || '127.0.0.1',
    SPRING_DATASOURCE_URL: process.env.SPRING_DATASOURCE_URL
      || `jdbc:h2:tcp://${process.env.DB_HOST || '127.0.0.1'}:${process.env.DB_PORT || 9092}/./data/poker`,
  } },
)

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => child.kill(signal))
}

child.on('error', (error) => {
  console.error(`Unable to start development services: ${error.message}`)
  process.exit(1)
})

child.on('exit', (code, signal) => {
  if (signal) process.kill(process.pid, signal)
  else process.exit(code ?? 1)
})
