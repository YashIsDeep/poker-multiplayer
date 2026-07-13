import { describe, expect, it } from 'vitest'
import { clampRaise, parseCard, raisePresets } from './cards'

describe('parseCard', () => {
  it('parses ranks and suits', () => {
    expect(parseCard('As')).toEqual({ rank: 'A', suit: 's', red: false })
    expect(parseCard('Kd')).toEqual({ rank: 'K', suit: 'd', red: true })
    expect(parseCard('Th')).toEqual({ rank: '10', suit: 'h', red: true })
    expect(parseCard('9c')).toEqual({ rank: '9', suit: 'c', red: false })
    expect(parseCard('10h')).toEqual({ rank: '10', suit: 'h', red: true })
  })

  it('rejects malformed codes without throwing', () => {
    expect(parseCard('')).toBeNull()
    expect(parseCard('X')).toBeNull()
    expect(parseCard('Zz')).toBeNull()
    expect(parseCard('AA')).toBeNull()
    expect(parseCard('Ah!')).toBeNull()
  })
})

describe('raisePresets', () => {
  it('computes min / half-pot / pot / all-in inside the legal window', () => {
    const presets = raisePresets({ pot: 340, toCall: 120, minRaiseTo: 240, maxRaiseTo: 1_850 })
    expect(presets.min).toBe(240)
    expect(presets.half).toBe(120 + Math.round((340 + 120) / 2)) // 350
    expect(presets.pot).toBe(120 + 340 + 120) // 580
    expect(presets.allin).toBe(1_850)
  })

  it('clamps presets when the stack caps the raise', () => {
    const presets = raisePresets({ pot: 10_000, toCall: 500, minRaiseTo: 1_000, maxRaiseTo: 1_200 })
    expect(presets.half).toBe(1_200)
    expect(presets.pot).toBe(1_200)
    expect(presets.allin).toBe(1_200)
  })

  it('clamps up to minRaiseTo when pot-based math falls below it', () => {
    const presets = raisePresets({ pot: 10, toCall: 0, minRaiseTo: 100, maxRaiseTo: 900 })
    expect(presets.half).toBe(100)
    expect(presets.pot).toBe(100)
  })
})

describe('clampRaise', () => {
  it('clamps into [min, max] and rounds', () => {
    expect(clampRaise(50, 100, 900)).toBe(100)
    expect(clampRaise(950, 100, 900)).toBe(900)
    expect(clampRaise(444.6, 100, 900)).toBe(445)
    expect(clampRaise(Number.NaN, 100, 900)).toBe(100)
  })
})
