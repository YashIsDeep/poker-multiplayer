// Card-code helpers. Wire format: rank char(s) + suit char, e.g. 'As', 'Kd', 'Th', '9c'.

export type ParsedCard = { rank: string; suit: 's' | 'h' | 'd' | 'c'; red: boolean }

const SUIT_GLYPH: Record<string, string> = { s: '♠', h: '♥', d: '♦', c: '♣' }
const RED_SUITS = new Set(['h', 'd'])
const VALID_RANKS = new Set(['2', '3', '4', '5', '6', '7', '8', '9', 'T', '10', 'J', 'Q', 'K', 'A'])

export function parseCard(code: string): ParsedCard | null {
  if (typeof code !== 'string' || code.length < 2 || code.length > 3) return null
  const suit = code.slice(-1).toLowerCase()
  const rank = code.slice(0, -1).toUpperCase()
  if (!(suit in SUIT_GLYPH) || !VALID_RANKS.has(rank)) return null
  return {
    rank: rank === 'T' ? '10' : rank,
    suit: suit as ParsedCard['suit'],
    red: RED_SUITS.has(suit),
  }
}

export function suitGlyph(suit: string): string {
  return SUIT_GLYPH[suit] ?? '?'
}

/** Raise-to presets, clamped to [minRaiseTo, maxRaiseTo]. */
export function raisePresets(input: {
  pot: number
  toCall: number
  minRaiseTo: number
  maxRaiseTo: number
}): { min: number; half: number; pot: number; allin: number } {
  const { pot, toCall, minRaiseTo, maxRaiseTo } = input
  const clamp = (v: number) => Math.min(Math.max(v, minRaiseTo), maxRaiseTo)
  return {
    min: clamp(minRaiseTo),
    half: clamp(toCall + Math.round((pot + toCall) / 2)),
    pot: clamp(toCall + pot + toCall),
    allin: maxRaiseTo,
  }
}

export function clampRaise(value: number, minRaiseTo: number, maxRaiseTo: number): number {
  if (Number.isNaN(value)) return minRaiseTo
  return Math.min(Math.max(Math.round(value), minRaiseTo), maxRaiseTo)
}
