export type ChipTone =
  | 'ivory'
  | 'red'
  | 'green'
  | 'black'
  | 'purple'
  | 'gold'
  | 'orange'
  | 'blue'
  | 'pink'
  | 'teal'
  | 'platinum'

export type ChipTier = 'entry' | 'core' | 'premium' | 'elite' | 'pinnacle'

export type ChipVisual = {
  denomination: number
  tone: ChipTone
  tier: ChipTier
  /** Position within one physical stack of a single denomination. */
  ordinal: number
  /** Adjacent physical stack, kept separate from other denominations. */
  stackIndex: number
}

// Tournament values follow the familiar 1/5/25/100/500 progression used by
// major live events. The 1 and 5 chips keep low-blind tables representable;
// higher values continue the standard colour-up ladder.
const DENOMINATIONS: ReadonlyArray<{ value: number; tone: ChipTone; tier: ChipTier }> = [
  { value: 1_000_000, tone: 'platinum', tier: 'pinnacle' },
  { value: 500_000, tone: 'teal', tier: 'elite' },
  { value: 100_000, tone: 'pink', tier: 'premium' },
  { value: 25_000, tone: 'blue', tier: 'premium' },
  { value: 5_000, tone: 'orange', tier: 'premium' },
  { value: 1_000, tone: 'gold', tier: 'core' },
  { value: 500, tone: 'purple', tier: 'core' },
  { value: 100, tone: 'black', tier: 'core' },
  { value: 25, tone: 'green', tier: 'entry' },
  { value: 5, tone: 'red', tier: 'entry' },
  { value: 1, tone: 'ivory', tier: 'entry' },
]

const MAX_CHIPS_PER_STACK = 5

/**
 * Converts an amount into readable, denomination-specific physical stacks.
 * Common amounts are represented exactly (300 is three 100s; 400 is four
 * 100s), while `limit` remains a final DOM-safety bound for unusual values.
 */
export function buildChipVisuals(amount: number, limit = 20): ChipVisual[] {
  if (!Number.isFinite(amount) || amount <= 0 || limit <= 0) return []

  let remainder = Math.floor(amount)
  const chips: ChipVisual[] = []
  let nextStackIndex = 0
  for (const denomination of DENOMINATIONS) {
    if (chips.length >= limit || remainder < denomination.value) continue
    const actualCount = Math.floor(remainder / denomination.value)
    const visibleCount = Math.min(actualCount, limit - chips.length)
    for (let index = 0; index < visibleCount; index += 1) {
      chips.push({
        denomination: denomination.value,
        tone: denomination.tone,
        tier: denomination.tier,
        ordinal: index % MAX_CHIPS_PER_STACK,
        stackIndex: nextStackIndex + Math.floor(index / MAX_CHIPS_PER_STACK),
      })
    }
    nextStackIndex += Math.ceil(visibleCount / MAX_CHIPS_PER_STACK)
    remainder %= denomination.value
  }

  // Very large or non-standard values can consume the visual budget before a
  // remainder is represented. The adjacent text still provides exact truth.
  return chips
}
