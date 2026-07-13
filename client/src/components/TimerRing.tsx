// Conic-gradient countdown ring above the acting player's pod (T7 structure,
// T8 red/tremble low state). Driven by the server-clock EMA countdown.

import { useServerCountdown } from '../hooks/useServerCountdown'
import type { CSSProperties } from 'react'

type TimerRingProps = {
  deadline: number
  budgetMs?: number
}

export default function TimerRing({ deadline, budgetMs = 30_000 }: TimerRingProps) {
  const { secondsRemaining, percentRemaining, low } = useServerCountdown(deadline, budgetMs)
  const style = { '--p': String(percentRemaining) } as CSSProperties
  return (
    <span className={`timer-ring${low ? ' low' : ''}`} style={style} data-testid="timer-ring">
      <i>{secondsRemaining}</i>
    </span>
  )
}
