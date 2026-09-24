const FULL_CHIPS = new Intl.NumberFormat('en-US', { maximumFractionDigits: 0 })

export function formatChips(value: number | null | undefined): string {
  if (typeof value !== 'number' || !Number.isFinite(value)) return '—'
  const absolute = Math.abs(value)
  if (absolute < 1_000) return FULL_CHIPS.format(value)

  const [divisor, suffix] = absolute >= 1_000_000_000_000
    ? [1_000_000_000_000, 'T'] as const
    : absolute >= 1_000_000_000
      ? [1_000_000_000, 'B'] as const
      : absolute >= 1_000_000
        ? [1_000_000, 'M'] as const
        : [1_000, 'K'] as const
  const scaled = value / divisor
  const digits = Math.abs(scaled) >= 100 ? 0 : Math.abs(scaled) >= 10 ? 1 : 2
  return `${scaled.toFixed(digits).replace(/\.0+$|(?<=\.[0-9])0+$/, '')}${suffix}`
}

export function formatChipsFull(value: number | null | undefined): string {
  if (typeof value !== 'number' || !Number.isFinite(value)) return '—'
  return FULL_CHIPS.format(value)
}

/** Result deltas read as gains and losses, so keep the direction explicit. */
function signedFormat(format: (value: number | null | undefined) => string) {
  return (value: number | null | undefined): string =>
    typeof value === 'number' && Number.isFinite(value) && value > 0 ? `+${format(value)}` : format(value)
}

export const formatSignedChips = signedFormat(formatChips)

const BB_NUMBER = new Intl.NumberFormat('en-US', { maximumFractionDigits: 2 })
const BB_FULL = new Intl.NumberFormat('en-US', { maximumFractionDigits: 8 })

/** Presentation only: actions and accounting always retain integer chip amounts. */
export function tableChipFormat(bigBlind: number | null) {
  if (bigBlind === null || !Number.isFinite(bigBlind) || bigBlind <= 0) {
    return { formatChips, formatChipsFull, formatSignedChips }
  }
  const format = (value: number | null | undefined, full: boolean) =>
    typeof value === 'number' && Number.isFinite(value)
      ? `${(full ? BB_FULL : BB_NUMBER).format(value / bigBlind)} BB` : '—'
  const bbFormatChips = (value: number | null | undefined) => format(value, false)
  return {
    formatChips: bbFormatChips,
    formatChipsFull: (value: number | null | undefined) => format(value, true),
    formatSignedChips: signedFormat(bbFormatChips),
  }
}
