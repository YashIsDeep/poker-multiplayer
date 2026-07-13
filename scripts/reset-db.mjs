import { readdir, rm } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const scriptDirectory = dirname(fileURLToPath(import.meta.url))
const dataDirectory = resolve(scriptDirectory, '..', 'server', 'data')
const primaryDatabaseFile = 'poker.mv.db'
const auxiliaryDatabaseFiles = ['poker.lock.db', 'poker.temp.db', 'poker.trace.db']

let files

try {
  files = await readdir(dataDirectory)
} catch (error) {
  if (error.code === 'ENOENT') {
    console.log('Database is already reset.')
    process.exit(0)
  }

  throw error
}

const databaseFiles = [primaryDatabaseFile, ...auxiliaryDatabaseFiles]
  .filter((name) => files.includes(name))

if (databaseFiles.length === 0) {
  console.log('Database is already reset.')
  process.exit(0)
}

try {
  // Remove the primary file first so a running server cannot leave a partial reset.
  for (const name of databaseFiles) {
    await rm(resolve(dataDirectory, name))
  }
} catch (error) {
  if (error.code === 'EBUSY' || error.code === 'EPERM') {
    console.error(
      'Unable to reset the database while it is in use. Stop the application, run `npm run stop:db`, '
        + 'then run `npm run reset:db` again.',
    )
    process.exit(1)
  }

  throw error
}

console.log('Database reset. It will be recreated by Flyway the next time the server starts.')
