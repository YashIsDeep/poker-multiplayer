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
