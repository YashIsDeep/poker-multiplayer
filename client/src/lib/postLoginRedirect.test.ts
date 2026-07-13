import { describe, expect, it } from 'vitest'
import {
  isSafeAppPath,
  rememberPostLoginRedirect,
  takePostLoginRedirect,
} from './postLoginRedirect'

function memoryStorage(): Storage {
  const values = new Map<string, string>()
  return {
    get length() {
      return values.size
    },
    clear: () => values.clear(),
    getItem: (key) => values.get(key) ?? null,
    key: (index) => [...values.keys()][index] ?? null,
    removeItem: (key) => {
      values.delete(key)
    },
    setItem: (key, value) => {
      values.set(key, value)
    },
  }
}

describe('post-login redirect', () => {
  it('round-trips an internal room URL once', () => {
    const storage = memoryStorage()
    const room = '/room/a055e903-b2bd-40f2-8bcf-e8a09863d6f3?view=table'

    rememberPostLoginRedirect(room, storage)

    expect(takePostLoginRedirect(storage)).toBe(room)
    expect(takePostLoginRedirect(storage)).toBeNull()
  })

  it('rejects absolute and protocol-relative URLs', () => {
    expect(isSafeAppPath('https://example.com/room/1')).toBe(false)
    expect(isSafeAppPath('//example.com/room/1')).toBe(false)

    const storage = memoryStorage()
    rememberPostLoginRedirect('//example.com/room/1', storage)
    expect(takePostLoginRedirect(storage)).toBeNull()
  })
})
