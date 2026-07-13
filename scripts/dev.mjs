import { spawn } from 'node:child_process'
import { resolve } from 'node:path'
import { prepareDatabaseForDev } from './database.mjs'

const oauthVariables = [
  'GOOGLE_CLIENT_ID',
  'GOOGLE_CLIENT_SECRET',
  'DISCORD_CLIENT_ID',
  'DISCORD_CLIENT_SECRET',
]

const configuredVariables = oauthVariables.filter((name) => process.env[name]?.trim())
const missingVariables = oauthVariables.filter((name) => !process.env[name]?.trim())

if (configuredVariables.length > 0 && missingVariables.length > 0) {
  console.error(
    `OAuth configuration is incomplete. Set ${missingVariables.join(', ')} in .env, `
      + 'or leave all four OAuth values blank to use the local dev login only.',
  )
  process.exit(1)
}

const profiles = missingVariables.length === 0 ? 'dev,oauth' : 'dev'

if (profiles === 'dev') {
  console.log('OAuth credentials are not configured; starting with the local dev login only.')
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
  { stdio: 'inherit', env: process.env },
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
