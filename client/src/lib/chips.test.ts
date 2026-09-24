import { describe, expect, it } from 'vitest'
import { formatChips, formatSignedChips, tableChipFormat } from './chips'

describe('formatChips', () => {
  it('shows public amounts in BB without rounding away blinds or odd chips', () => {
    const bb = tableChipFormat(10)
    expect(bb.formatChips(1000)).toBe('100 BB')
    expect(bb.formatChips(5)).toBe('0.5 BB')
    expect(bb.formatChips(1)).toBe('0.1 BB')
    expect(bb.formatChips(-15)).toBe('-1.5 BB')
    expect(bb.formatChips(null)).toBe('—')
    expect(tableChipFormat(null).formatChips(1000)).toBe('1K')
  })
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
  it('compacts negative magnitudes with the same suffix math as positive ones', () => {
    expect(formatChips(-999)).toBe('-999')
    expect(formatChips(-100_000)).toBe('-100K')
    expect(formatChips(-1_250_000)).toBe('-1.25M')
    expect(formatChips(-1_250_000_000)).toBe('-1.25B')
    expect(formatChips(-2_500_000_000_000)).toBe('-2.5T')
  })
})

describe('formatSignedChips', () => {
  it('prefixes positive deltas with a plus sign', () => {
    expect(formatSignedChips(350)).toBe('+350')
    expect(formatSignedChips(1_200)).toBe('+1.2K')
  })
  it('uses the formatter native hyphen-minus for negative deltas without an extra sign', () => {
    expect(formatSignedChips(-350)).toBe('-350')
    expect(formatSignedChips(-1_200_000)).toBe('-1.2M')
  })
  it('renders exactly zero as neutral, with no plus or minus', () => {
    expect(formatSignedChips(0)).toBe('0')
  })
  it('passes through the placeholder for missing values', () => {
    expect(formatSignedChips(null)).toBe('—')
    expect(formatSignedChips(undefined)).toBe('—')
  })
  it('renders signed BB deltas when the table is in BB display mode', () => {
    const bb = tableChipFormat(10)
    expect(bb.formatSignedChips(15)).toBe('+1.5 BB')
    expect(bb.formatSignedChips(-15)).toBe('-1.5 BB')
    expect(bb.formatSignedChips(0)).toBe('0 BB')
  })
})
