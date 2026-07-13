import { describe, expect, it } from 'vitest'
import { deriveAnimationDiff } from './useAnimationQueue'
import type { Game } from '../store/types'

function game(overrides: Partial<Game> = {}): Game {
  return {
    tableStatus: 'RUNNING',
    handId: 1,
    phase: 'FLOP',
    completionReason: null,
    board: [],
    buttonSeat: 0,
    smallBlindSeat: 0,
    bigBlindSeat: 1,
    actionSeq: 1,
    actingSeat: 0,
    deadline: null,
    minRaiseTo: 20,
    maxRaiseTo: 100,
    toCall: 10,
    currentBet: 10,
    pots: [{ amount: 20, eligibleSeats: [0, 1] }],
    awards: [],
    seats: [
      {
        seat: 0,
        userId: 'alice',
        stack: 990,
        seatStatus: 'ACTIVE',
        committed: 10,
        holeCards: ['As', 'Kd'],
        lastAction: null,
        finalHandRank: null,
      },
    ],
    viewer: {
      seated: true,
      seat: 0,
      canAct: false,
      rebuyMinimum: null,
      rebuyMaximum: null,
      legalActions: null,
    },
    ...overrides,
  }
}

describe('deriveAnimationDiff', () => {
  it('derives board, pot, and committed-chip animations from one snapshot diff', () => {
    const previous = game()
    const current = game({
      board: ['Ah', '9c', '2d'],
      pots: [{ amount: 40, eligibleSeats: [0, 1] }],
      seats: [{ ...previous.seats[0], committed: 20 }],
    })

    const diff = deriveAnimationDiff(previous, current)
    expect(diff.boardDealFrom).toBe(0)
    expect(diff.potChanged).toBe(true)
    expect([...diff.chipFlySeats]).toEqual([0])
    expect(diff.holeDeal).toBe(false)
  })

  it('treats a new hand as a hole-card deal without carrying prior chip movement', () => {
    const diff = deriveAnimationDiff(game({ handId: 1 }), game({ handId: 2 }))
    expect(diff.holeDeal).toBe(true)
    expect(diff.chipFlySeats.size).toBe(0)
  })

  it('emits no transient events when the game disappears', () => {
    expect(deriveAnimationDiff(game(), null)).toEqual({
      boardDealFrom: null,
      potChanged: false,
      chipFlySeats: new Set(),
      holeDeal: false,
    })
  })
})
