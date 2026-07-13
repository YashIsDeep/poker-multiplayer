import { describe, expect, it } from 'vitest'
import { closestPower, normalizeTableSettings, STACK_BB_OPTIONS, valueAtPower } from './TableSettingsFields'

describe('power-of-ten table controls', () => {
  it('maps each slider stop to an exact power of ten', () => {
    expect(valueAtPower(1)).toBe(10)
    expect(valueAtPower(5)).toBe(100_000)
    expect(valueAtPower(8)).toBe(100_000_000)
  })

  it('normalizes legacy values to the closest available stop', () => {
    expect(closestPower(200, 2, 8)).toBe(2)
    expect(closestPower(8_000_000, 2, 8)).toBe(7)
    expect(closestPower(Number.NaN, 2, 8)).toBe(2)
  })

  it('normalizes stacks to the closest configured big-blind option', () => {
    expect(normalizeTableSettings({
      bigBlind: 100_000,
      startingStack: 8_000_000,
      rebuysEnabled: true,
      rebuyStack: 900_000,
    })).toEqual({
      bigBlind: 100_000,
      startingStack: 10_000_000,
      rebuysEnabled: true,
      rebuyStack: 5_000_000,
    })
  })

  it('offers the exact requested BB stack stops without a 100M cap', () => {
    expect(STACK_BB_OPTIONS).toEqual([50, 100, 200, 500])
    expect(normalizeTableSettings({
      bigBlind: 1_000_000,
      startingStack: 1_000_000_000,
      rebuysEnabled: true,
      rebuyStack: 1_000_000_000,
    })).toEqual({
      bigBlind: 1_000_000,
      startingStack: 500_000_000,
      rebuysEnabled: true,
      rebuyStack: 500_000_000,
    })
  })
})
