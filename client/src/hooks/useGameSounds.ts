// Derive gameplay sound effects from successive Game snapshots — the audio
// sibling of useAnimationQueue. The store stays the single source of truth;
// this only observes data diffs and fires transient effects. The decision of
// *which* sounds a diff warrants is a pure function so it can be unit-tested
// without a Web Audio context.

import { useEffect, useRef } from 'react'
import { playSound, type SoundName } from '../lib/sound'
import type { Game, LastAction } from '../store/types'

// A bulk snapshot (reconnect resync) can move many seats at once; past this we
// treat it as a resync rather than live play and stay quiet to avoid a racket.
const MAX_ACTION_SOUNDS = 2

const ACTION_SOUND: Record<LastAction['action'], SoundName> = {
  FOLD: 'fold',
  CHECK: 'check',
  CALL: 'call',
  RAISE_TO: 'raise',
}

function sameAction(a: LastAction | null, b: LastAction | null): boolean {
  if (a === null || b === null) return a === b
  return a.action === b.action && a.amount === b.amount
}

/**
 * Sounds a transition from `prev` → `game` warrants, for a viewer seated at
 * `yourSeat` (or null when spectating). Returns [] when there is nothing to
 * announce — including the first snapshot (prev === null), so page loads and
 * resyncs stay silent.
 */
export function deriveSoundEvents(
  prev: Game | null,
  game: Game | null,
  yourSeat: number | null,
): SoundName[] {
  if (!game || !prev) return []
  const events: SoundName[] = []

  const sameHand = prev.handId === game.handId

  // New hand dealt (hole cards go out).
  if (!sameHand) {
    events.push('deal')
  }

  // Community cards revealed (flop/turn/river) within the same hand.
  if (sameHand && game.board.length > prev.board.length) {
    events.push('card')
  }

  // Player actions: a seat's lastAction changing to a new non-null value.
  if (sameHand) {
    const actionSounds: SoundName[] = []
    for (const seat of game.seats) {
      const before = prev.seats.find((s) => s.seat === seat.seat)?.lastAction ?? null
      if (seat.lastAction && !sameAction(before, seat.lastAction)) {
        actionSounds.push(ACTION_SOUND[seat.lastAction.action])
      }
    }
    if (actionSounds.length > 0 && actionSounds.length <= MAX_ACTION_SOUNDS) {
      events.push(...actionSounds)
    }
  }

  // Your turn to act just began.
  const wasYourTurn = yourSeat !== null && prev.actingSeat === yourSeat
  const isYourTurn =
    yourSeat !== null && game.actingSeat === yourSeat && game.viewer.canAct
  if (isYourTurn && !wasYourTurn) {
    events.push('yourTurn')
  }

  // Hand just completed with a pot award → win chime (bigger if you won).
  const justCompleted = game.phase === 'COMPLETE' && prev.phase !== 'COMPLETE'
  if (justCompleted && game.awards.length > 0) {
    const winnerSeats = new Set(
      game.awards.flatMap((award) => award.winners.map((w) => w.seat)),
    )
    events.push(yourSeat !== null && winnerSeats.has(yourSeat) ? 'winBig' : 'win')
  }

  return events
}

export function useGameSounds(game: Game | null, yourSeat: number | null) {
  const prevRef = useRef<Game | null>(null)

  useEffect(() => {
    const prev = prevRef.current
    prevRef.current = game
    const events = deriveSoundEvents(prev, game, yourSeat)
    for (const name of events) playSound(name)
  }, [game, yourSeat])
}
