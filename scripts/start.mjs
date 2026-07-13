import { spawn } from 'node:child_process'
import { existsSync, readdirSync, statSync } from 'node:fs'
import { resolve } from 'node:path'

const targetDirectory = resolve('server', 'target')

function isDisabled(value) {
  return ['false', '0', 'off', 'no'].includes(value?.trim().toLowerCase())
}

if (!existsSync(targetDirectory)) {
  console.error('Production build not found. Run `npm run build` first.')
  process.exit(1)
}

const jars = readdirSync(targetDirectory)
  .filter((name) => /^server-.*\.jar$/.test(name) && !name.endsWith('.original'))
  .map((name) => ({ name, modified: statSync(resolve(targetDirectory, name)).mtimeMs }))
  .sort((left, right) => right.modified - left.modified)

if (jars.length === 0) {
  console.error('Production build not found. Run `npm run build` first.')
  process.exit(1)
}

const oauthEnabled = !isDisabled(process.env.OAUTH_ENABLED)
const configuredProfiles = process.env.SPRING_PROFILES_ACTIVE?.trim()
const profiles = configuredProfiles || (oauthEnabled ? 'oauth' : 'local-auth')

if (!oauthEnabled && profiles.split(',').some((profile) => profile.trim() === 'oauth')) {
  console.error('OAUTH_ENABLED=false cannot be used with SPRING_PROFILES_ACTIVE=oauth.')
  process.exit(1)
}

if (!oauthEnabled) {
  console.log('OAuth is disabled; using the local guest-login profile.')
}

const child = spawn(
  'java',
  [
    '-jar',
    resolve(targetDirectory, jars[0].name),
    `--spring.profiles.active=${profiles}`,
    ...process.argv.slice(2),
  ],
  { stdio: 'inherit', env: process.env },
)

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => child.kill(signal))
}

child.on('error', (error) => {
  console.error(`Unable to start the production server: ${error.message}`)
  process.exit(1)
})

child.on('exit', (code, signal) => {
  if (signal) process.kill(process.pid, signal)
  else process.exit(code ?? 1)
})
