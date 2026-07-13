import { describe, expect, it } from 'vitest'
import { generateRandomTableName } from './tableNames'

describe('generateRandomTableName', () => {
  it('uses the random source to choose both words', () => {
    const values = [0, 0.999]

    expect(generateRandomTableName(() => values.shift() ?? 0)).toBe('Bold Queens')
  })

  it('always fits the table-name limit', () => {
    for (let index = 0; index < 100; index += 1) {
      expect(generateRandomTableName).not.toThrow()
      expect(generateRandomTableName().length).toBeLessThanOrEqual(40)
    }
  })
})
