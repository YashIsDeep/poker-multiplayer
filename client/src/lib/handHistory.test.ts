import { describe, expect, it } from 'vitest'
import { describeHistoryEvent } from './handHistory'
import type { HandHistory, HandHistoryEvent } from '../store/types'

const hand: HandHistory = {
  handId: 7,
  startedAt: '2026-07-13T00:00:00Z',
  completedAt: null,
  phase: 'PREFLOP',
  completionReason: null,
  buttonSeat: 0,
  smallBlindSeat: 0,
  bigBlindSeat: 1,
  smallBlind: 5,
  bigBlind: 10,
  board: [],
  players: [
    {
      seat: 0,
      userId: 'a',
      displayName: 'Alice',
      startingStack: 500,
      endingStack: 470,
      netChange: -30,
      won: 0,
      folded: false,
      holeCards: ['As', 'Kh'],
      finalHandRank: null,
    },
  ],
  events: [],
}

const base: HandHistoryEvent = {
  sequence: 1,
  occurredAt: hand.startedAt,
  phase: 'PREFLOP',
  type: 'PLAYER_ACTED',
  seat: 0,
  action: 'RAISE_TO',
  amount: 25,
  total: 30,
  stackAfter: 470,
  cards: [],
  potIndex: null,
  potAmount: null,
  winners: [],
}

describe('describeHistoryEvent', () => {
  it('describes raise-to totals rather than the incremental contribution', () => {
    expect(describeHistoryEvent(base, hand)).toBe('Alice raises to 30')
  })

  it('describes multi-winner pot awards', () => {
    expect(
      describeHistoryEvent(
        {
          ...base,
          type: 'POT_AWARDED',
          seat: null,
          action: null,
          amount: null,
          total: null,
          stackAfter: null,
          potIndex: 0,
          potAmount: 60,
          winners: [{ seat: 0, amount: 60 }],
        },
        hand,
      ),
    ).toBe('Main pot · Alice wins 60')
  })
})
