import { describe, expect, it } from 'vitest'
import { displayedHoleCards } from './SeatPod'
import type { GameSeat } from '../store/types'

function gameSeat(overrides: Partial<GameSeat> = {}): GameSeat {
  return {
    seat: 0,
    userId: 'alice',
    stack: 1_000,
    seatStatus: 'ACTIVE',
    committed: 0,
    holeCards: null,
    lastAction: null,
    actions: [],
    position: 'BN',
    finalHandRank: null,
    rebuyHandsRemaining: 0,
    rebought: false,
    ...overrides,
  }
}

describe('displayedHoleCards', () => {
  it('does not deal cards to a player who takes a seat after the hand starts', () => {
    expect(displayedHoleCards(gameSeat({ position: null }), false)).toBeNull()
  })

  it('shows card backs only for players captured in the current hand', () => {
    expect(displayedHoleCards(gameSeat(), false)).toEqual(['back', 'back'])
  })
})
