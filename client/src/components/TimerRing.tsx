// In-pod countdown meter for the acting player. Keeping the timer in normal
// layout flow prevents it from colliding with hole cards or position badges.
// Driven by the server-clock EMA countdown.

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
    <div
      className={`turn-timer${low ? ' low' : ''}`}
      style={style}
      data-testid="timer-ring"
      role="timer"
      aria-label={`Turn timer: ${secondsRemaining} seconds remaining`}
      aria-atomic="true"
    >
      <span className="turn-timer__seconds num" aria-hidden="true">{secondsRemaining}</span>
    </div>
  )
}
