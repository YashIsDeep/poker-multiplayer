import { describe, expect, it } from 'vitest'
import { displayedHoleCards, splitShowdownHand } from './SeatPod'
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
    rebuyCount: 0,
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

describe('splitShowdownHand', () => {
  it('puts the category first and keeps the high-card tie-break visible', () => {
    expect(splitShowdownHand('King-high Flush')).toEqual({
      category: 'Flush',
      detail: 'King-high',
    })
  })

  it('keeps grouped hand details available for same-category comparisons', () => {
    expect(splitShowdownHand('Two Pair, Kings and Nines')).toEqual({
      category: 'Two Pair',
      detail: 'Kings and Nines',
    })
  })
})
