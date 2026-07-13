// T8: derive juice-animation events from snapshot diffs. The store stays the
// single source of truth; this hook only observes successive Game values and
// emits transient presentation events (cleared once their CSS runs out).
// No DOM observation — everything derives from data diffs.

import { useEffect, useRef, useState } from 'react'
import type { Game } from '../store/types'

export type AnimationEvents = {
  /** Board index where newly dealt cards start (deal-stagger those), or null. */
  boardDealFrom: number | null
  /** Bumped whenever the displayed pot total changes → keys the pop animation. */
  potPopKey: number
  /** Seats whose committed bet increased this snapshot → chip fly entrance. */
  chipFlySeats: ReadonlySet<number>
  /** True briefly when a new hand's hole cards land → entrance stagger. */
  holeDeal: boolean
}

const EMPTY: ReadonlySet<number> = new Set()
const CLEAR_AFTER_MS = 900 // longest entrance (delay + duration) rounded up

function potTotal(game: Game | null): number {
  if (!game) return 0
  return game.potTotal
}

export type AnimationDiff = {
  boardDealFrom: number | null
  potChanged: boolean
  chipFlySeats: ReadonlySet<number>
  holeDeal: boolean
}

export function deriveAnimationDiff(prev: Game | null, game: Game | null): AnimationDiff {
  if (!game) {
    return { boardDealFrom: null, potChanged: false, chipFlySeats: EMPTY, holeDeal: false }
  }

  const sameHand = prev !== null && prev.handId === game.handId
  const prevBoard = sameHand ? prev.board.length : 0
  const boardDealFrom = game.board.length > prevBoard ? prevBoard : null
  const potChanged = prev !== null && potTotal(prev) !== potTotal(game)
  const chipFlySeats = new Set<number>()
  if (sameHand && prev) {
    for (const seat of game.seats) {
      const before = prev.seats.find((candidate) => candidate.seat === seat.seat)?.committed ?? 0
      if ((seat.committed ?? 0) > before) chipFlySeats.add(seat.seat)
    }
  }

  return {
    boardDealFrom,
    potChanged,
    chipFlySeats: chipFlySeats.size > 0 ? chipFlySeats : EMPTY,
    holeDeal: !sameHand && prev !== null,
  }
}

export function useAnimationQueue(game: Game | null, reducedMotion: boolean): AnimationEvents {
  const prevRef = useRef<Game | null>(null)
  const [events, setEvents] = useState<AnimationEvents>({
    boardDealFrom: null,
    potPopKey: 0,
    chipFlySeats: EMPTY,
    holeDeal: false,
  })

  useEffect(() => {
    const prev = prevRef.current
    prevRef.current = game
    if (!game || reducedMotion) {
      setEvents((current) => ({
        ...current,
        boardDealFrom: null,
        chipFlySeats: EMPTY,
        holeDeal: false,
      }))
      return
    }
    const diff = deriveAnimationDiff(prev, game)
    const changed =
      diff.boardDealFrom !== null || diff.potChanged || diff.chipFlySeats.size > 0 || diff.holeDeal

    if (!changed) return

    setEvents((current) => ({
      boardDealFrom: diff.boardDealFrom,
      potPopKey: diff.potChanged ? current.potPopKey + 1 : current.potPopKey,
      chipFlySeats: diff.chipFlySeats,
      holeDeal: diff.holeDeal,
    }))

    const timer = setTimeout(() => {
      setEvents((current) => ({
        ...current,
        boardDealFrom: null,
        chipFlySeats: EMPTY,
        holeDeal: false,
      }))
    }, CLEAR_AFTER_MS)
    return () => clearTimeout(timer)
  }, [game, reducedMotion])

  return events
}
