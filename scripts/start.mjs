import { spawn } from 'node:child_process'
import { existsSync, readdirSync, statSync } from 'node:fs'
import { resolve } from 'node:path'
import { assertLocalAuthentication, productionProfiles } from './auth-mode.mjs'

const targetDirectory = resolve('server', 'target')

let profiles
try {
  profiles = productionProfiles(process.env, process.argv.slice(2))
  assertLocalAuthentication(process.env, profiles, process.argv.slice(2))
} catch (error) {
  console.error(error.message)
  process.exit(1)
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
