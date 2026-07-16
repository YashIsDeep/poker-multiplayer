import type { CSSProperties } from 'react'
import { buildChipVisuals } from '../lib/chipVisuals'

type ChipStackProps = {
  amount: number
  compact?: boolean
  className?: string
}

export default function ChipStack({ amount, compact = false, className = '' }: ChipStackProps) {
  const chips = buildChipVisuals(amount, compact ? 16 : 20)
  const stackCount = chips.reduce((count, chip) => Math.max(count, chip.stackIndex + 1), 1)
  const stackStep = compact ? 18 : 21
  const baseWidth = compact ? 36 : 42
  return (
    <span
      className={`chip-stack${compact ? ' compact' : ''}${className ? ` ${className}` : ''}`}
      style={
        {
          '--chip-stack-width': `${baseWidth + (stackCount - 1) * stackStep}px`,
        } as CSSProperties
      }
      aria-hidden="true"
    >
      {chips.map((chip, index) => (
        <span
          key={`${chip.denomination}-${chip.ordinal}-${index}`}
          className={`chip-token chip-${chip.tone} chip-tier-${chip.tier}`}
          style={
            {
              '--chip-stack-x': `${baseWidth / 2 + chip.stackIndex * stackStep}px`,
              '--chip-wobble': `${((chip.ordinal % 3) - 1) * 0.7}px`,
              '--chip-tilt': `${((chip.ordinal % 4) - 1.5) * 0.35}deg`,
              '--chip-index': chip.ordinal,
            } as CSSProperties
          }
        />
      ))}
    </span>
  )
}
