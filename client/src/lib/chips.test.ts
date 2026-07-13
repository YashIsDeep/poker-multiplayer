import { describe, expect, it } from 'vitest'
import { formatChips } from './chips'

describe('formatChips', () => {
  it('keeps small chip counts exact and compacts large stacks', () => {
    expect(formatChips(999)).toBe('999')
    expect(formatChips(100_000)).toBe('100K')
    expect(formatChips(1_250_000)).toBe('1.25M')
    expect(formatChips(10_000_000)).toBe('10M')
    expect(formatChips(100_000_000)).toBe('100M')
    expect(formatChips(1_250_000_000)).toBe('1.25B')
    expect(formatChips(2_500_000_000_000)).toBe('2.5T')
    expect(formatChips(undefined)).toBe('—')
    expect(formatChips(Number.NaN)).toBe('—')
  })
})
