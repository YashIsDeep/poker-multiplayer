// Deadline countdown driven by the EMA-corrected server clock (T7).
// Ticks every 100ms while a deadline is set; returns seconds remaining and the
// fraction of the budget left (for the turn timer meter).

import { useEffect, useState } from 'react'
import { serverNow } from '../store/gameStore'

export type Countdown = {
  secondsRemaining: number
  /** 0..100 percentage of the time budget remaining (100 = full). */
  percentRemaining: number
  low: boolean
}

const LOW_THRESHOLD_SECONDS = 6

export function useServerCountdown(deadline: number | null, budgetMs = 30_000): Countdown {
  const [now, setNow] = useState(() => serverNow())

  useEffect(() => {
    if (deadline === null) return
    setNow(serverNow())
    const timer = setInterval(() => setNow(serverNow()), 100)
    return () => clearInterval(timer)
  }, [deadline])

  if (deadline === null) return { secondsRemaining: 0, percentRemaining: 0, low: false }
  const remainingMs = Math.max(0, deadline - now)
  const secondsRemaining = Math.ceil(remainingMs / 1000)
  const percentRemaining = Math.max(0, Math.min(100, (remainingMs / budgetMs) * 100))
  return { secondsRemaining, percentRemaining, low: secondsRemaining <= LOW_THRESHOLD_SECONDS }
}

export function formatCountdown(seconds: number): string {
  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  return `${m}:${String(s).padStart(2, '0')}`
}
