import { describe, expect, it } from 'vitest'
import { buildChipVisuals } from './chipVisuals'

describe('buildChipVisuals', () => {
  it('represents 300 and 400 with the correct count of 100 chips', () => {
    expect(buildChipVisuals(300)).toEqual([
      { denomination: 100, tone: 'black', tier: 'core', ordinal: 0, stackIndex: 0 },
      { denomination: 100, tone: 'black', tier: 'core', ordinal: 1, stackIndex: 0 },
      { denomination: 100, tone: 'black', tier: 'core', ordinal: 2, stackIndex: 0 },
    ])
    expect(buildChipVisuals(400).map((chip) => chip.denomination)).toEqual([100, 100, 100, 100])
  })

  it('separates denominations into adjacent physical stacks', () => {
    const chips = buildChipVisuals(120)
    expect(chips.map((chip) => chip.tone)).toEqual(['black', 'red', 'red', 'red', 'red'])
    expect(chips.map((chip) => chip.stackIndex)).toEqual([0, 1, 1, 1, 1])
  })

  it('bounds the DOM-friendly visual stack while preserving larger denominations', () => {
    const chips = buildChipVisuals(2_888, 7)
    expect(chips).toHaveLength(7)
    expect(chips[0]).toMatchObject({ denomination: 1_000, tone: 'gold' })
    expect(chips.some((chip) => chip.tone === 'purple')).toBe(true)
    expect(new Set(chips.map((chip) => chip.stackIndex)).size).toBeGreaterThan(1)
  })

  it('reserves the most prestigious finishes for elite denominations', () => {
    expect(buildChipVisuals(500_000)[0]).toMatchObject({
      denomination: 500_000,
      tone: 'teal',
      tier: 'elite',
    })
    expect(buildChipVisuals(1_000_000)[0]).toMatchObject({
      denomination: 1_000_000,
      tone: 'platinum',
      tier: 'pinnacle',
    })
  })

  it('returns no decorations for invalid or empty amounts', () => {
    expect(buildChipVisuals(0)).toEqual([])
    expect(buildChipVisuals(Number.NaN)).toEqual([])
  })
})
