import { describe, expect, it } from 'vitest'
import { deriveSoundEvents } from './useGameSounds'
import type { Game, GameSeat } from '../store/types'

function seat(overrides: Partial<GameSeat> = {}): GameSeat {
  return {
    seat: 0,
    userId: 'alice',
    stack: 990,
    seatStatus: 'ACTIVE',
    committed: 10,
    holeCards: null,
    lastAction: null,
    finalHandRank: null,
    ...overrides,
  }
}

function game(overrides: Partial<Game> = {}): Game {
  return {
    tableStatus: 'RUNNING',
    handId: 1,
    phase: 'FLOP',
    completionReason: null,
    board: ['Ah', '9c', '2d'],
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
    seats: [seat({ seat: 0 }), seat({ seat: 1, userId: 'bob' })],
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

describe('deriveSoundEvents', () => {
  it('stays silent on the first snapshot (no prev) and page loads', () => {
    expect(deriveSoundEvents(null, game(), 0)).toEqual([])
    expect(deriveSoundEvents(game(), null, 0)).toEqual([])
  })

  it('deals on a new hand', () => {
    expect(deriveSoundEvents(game({ handId: 1 }), game({ handId: 2, board: [] }), 0)).toContain(
      'deal',
    )
  })

  it('flips a card when the board grows within a hand', () => {
    const prev = game({ board: [] })
    const next = game({ board: ['Ah', '9c', '2d'] })
    expect(deriveSoundEvents(prev, next, 0)).toContain('card')
  })

  it('maps a seat lastAction change to its action sound', () => {
    const prev = game()
    const raised = game({
      seats: [
        seat({ seat: 0, lastAction: { action: 'RAISE_TO', amount: 60 } }),
        seat({ seat: 1, userId: 'bob' }),
      ],
    })
    expect(deriveSoundEvents(prev, raised, 1)).toEqual(['raise'])

    const folded = game({
      seats: [seat({ seat: 0, lastAction: { action: 'FOLD', amount: null } }), seat({ seat: 1 })],
    })
    expect(deriveSoundEvents(prev, folded, 1)).toEqual(['fold'])
  })

  it('suppresses action sounds on a bulk resync of many seats', () => {
    const prev = game({ seats: [seat({ seat: 0 }), seat({ seat: 1 }), seat({ seat: 2 })] })
    const bulk = game({
      seats: [
        seat({ seat: 0, lastAction: { action: 'CALL', amount: 10 } }),
        seat({ seat: 1, lastAction: { action: 'CALL', amount: 10 } }),
        seat({ seat: 2, lastAction: { action: 'CHECK', amount: null } }),
      ],
    })
    expect(deriveSoundEvents(prev, bulk, 0)).toEqual([])
  })

  it('rings a prompt when it becomes your turn', () => {
    const prev = game({ actingSeat: 1 })
    const mine = game({
      actingSeat: 0,
      viewer: { seated: true, seat: 0, canAct: true, rebuyMinimum: null, rebuyMaximum: null, legalActions: null },
    })
    expect(deriveSoundEvents(prev, mine, 0)).toContain('yourTurn')
    // no repeat when it was already your turn
    expect(deriveSoundEvents(mine, mine, 0)).not.toContain('yourTurn')
  })

  it('celebrates louder when you are among the winners', () => {
    const prev = game({ phase: 'RIVER' })
    const youWin = game({
      phase: 'COMPLETE',
      completionReason: 'SHOWDOWN',
      awards: [{ potIndex: 0, potAmount: 100, winners: [{ seat: 0, amount: 100 }] }],
    })
    expect(deriveSoundEvents(prev, youWin, 0)).toContain('winBig')

    const theyWin = game({
      phase: 'COMPLETE',
      completionReason: 'SHOWDOWN',
      awards: [{ potIndex: 0, potAmount: 100, winners: [{ seat: 1, amount: 100 }] }],
    })
    expect(deriveSoundEvents(prev, theyWin, 0)).toContain('win')
    expect(deriveSoundEvents(prev, theyWin, 0)).not.toContain('winBig')
  })
})
