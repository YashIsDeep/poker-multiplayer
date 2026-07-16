import { describe, expect, it } from 'vitest'
import {
  buildShowdownPrelude,
  buildStreetFrames,
  deriveAnimationDiff,
  settledPotAmount,
  stageAwardsUntilArrival,
  stageBoardUntilBetsCollected,
  stagePotUntilArrival,
} from './useAnimationQueue'
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
    potTotal: 20,
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
        actions: [],
        position: 'BN',
        finalHandRank: null,
        rebuyHandsRemaining: 0,
        rebought: false,
        rebuyCount: 0,
      },
    ],
    viewer: {
      seated: true,
      seat: 0,
      canAct: false,
      rebuyAmount: null,
      rebuyHandsRemaining: null,
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
      potTotal: 40,
      pots: [{ amount: 40, eligibleSeats: [0, 1] }],
      seats: [{ ...previous.seats[0], committed: 20 }],
    })

    const diff = deriveAnimationDiff(previous, current)
    expect(diff.boardDealFrom).toBe(0)
    expect(diff.potChanged).toBe(true)
    expect([...diff.chipFlySeats]).toEqual([0])
    expect(diff.collectingBets).toEqual([])
    expect(diff.holeDeal).toBe(false)
    expect(diff.handChanged).toBe(false)
  })

  it('treats a new hand as a hole-card deal with fresh blind movement', () => {
    const diff = deriveAnimationDiff(game({ handId: 1 }), game({ handId: 2 }))
    expect(diff.holeDeal).toBe(true)
    expect(diff.handChanged).toBe(true)
    expect([...diff.chipFlySeats]).toEqual([0])
  })

  it('emits no transient events when the game disappears', () => {
    expect(deriveAnimationDiff(game(), null)).toEqual({
      boardDealFrom: null,
      potChanged: false,
      chipFlySeats: new Set(),
      collectingBets: [],
      holeDeal: false,
      handChanged: false,
      phaseFlash: null,
      action: null,
      awardsStarted: false,
    })
  })

  it('does not replay awards when the first snapshot is already complete', () => {
    const complete = game({
      phase: 'COMPLETE',
      completionReason: 'SHOWDOWN',
      awards: [{ potIndex: 0, potAmount: 20, winners: [{ seat: 0, amount: 20 }] }],
    })
    expect(deriveAnimationDiff(null, complete).awardsStarted).toBe(false)
  })

  it('keeps disappearing committed chips alive long enough to collect into the pot', () => {
    const previous = game({
      seats: [{ ...game().seats[0], committed: 120 }],
      potTotal: 30,
    })
    const current = game({
      phase: 'TURN',
      board: ['Ah', '9c', '2d', 'Ks'],
      seats: [{ ...previous.seats[0], committed: 0 }],
      potTotal: 150,
    })

    const diff = deriveAnimationDiff(previous, current)
    expect(diff.collectingBets).toEqual([{ seat: 0, amount: 120 }])
    expect(diff.phaseFlash).toBe('TURN')
  })

  it('collects the final river bets before a completed hand is presented', () => {
    const previous = game({
      phase: 'RIVER',
      board: ['Ah', '9c', '2d', 'Ks', 'Qc'],
      seats: [{ ...game().seats[0], committed: 120 }],
    })
    const complete = game({
      phase: 'COMPLETE',
      completionReason: 'SHOWDOWN',
      board: previous.board,
      seats: [{ ...previous.seats[0], committed: 0 }],
      awards: [{ potIndex: 0, potAmount: 120, winners: [{ seat: 0, amount: 120 }] }],
    })

    expect(deriveAnimationDiff(previous, complete).collectingBets).toEqual([
      { seat: 0, amount: 120 },
    ])
  })

  it('does not treat a same-street refund as chips collecting into the pot', () => {
    const previous = game({ seats: [{ ...game().seats[0], committed: 120 }] })
    const current = game({ seats: [{ ...previous.seats[0], committed: 80 }] })

    expect(deriveAnimationDiff(previous, current).collectingBets).toEqual([])
  })

  it('derives one readable action callout but suppresses bulk recovery changes', () => {
    const previous = game()
    const raised = game({
      actionSeq: 2,
      seats: [{
        ...previous.seats[0],
        committed: 120,
        lastAction: { action: 'RAISE_TO', amount: 120 },
      }],
    })
    expect(deriveAnimationDiff(previous, raised).action).toMatchObject({
      seat: 0,
      label: 'RAISE TO 120',
      tone: 'raise',
    })
  })
})

describe('chip presentation staging', () => {
  it('shows only chips already collected from completed streets beside the pot', () => {
    const current = game({
      potTotal: 270,
      seats: [
        { ...game().seats[0], seat: 0, committed: 120 },
        { ...game().seats[0], seat: 1, committed: 120 },
      ],
    })

    expect(settledPotAmount(current)).toBe(30)
    expect(settledPotAmount(
      game({ ...current, seats: current.seats.map((seat) => ({ ...seat, committed: 0 })) }),
      [{ seat: 0, amount: 120 }, { seat: 1, amount: 120 }],
    )).toBe(30)
  })

  it('keeps the old pot visible until a bet flight arrives', () => {
    const previous = game()
    const current = game({
      potTotal: 40,
      pots: [{ amount: 40, eligibleSeats: [0, 1] }],
      seats: [{ ...previous.seats[0], stack: 980, committed: 20 }],
    })

    const staged = stagePotUntilArrival(previous, current)
    expect(staged.potTotal).toBe(20)
    expect(staged.pots).toEqual(previous.pots)
    expect(staged.seats[0]).toMatchObject({ stack: 980, committed: 20 })
  })

  it('keeps the pot and winner stack unchanged until award chips arrive', () => {
    const previous = game({
      phase: 'RIVER',
      board: ['Ah', '9c', '2d', 'Ks', 'Qc'],
      potTotal: 120,
      pots: [{ amount: 120, eligibleSeats: [0] }],
      seats: [{ ...game().seats[0], stack: 880, committed: 120 }],
    })
    const complete = game({
      phase: 'COMPLETE',
      completionReason: 'SHOWDOWN',
      board: previous.board,
      potTotal: 0,
      pots: [],
      awards: [{ potIndex: 0, potAmount: 120, winners: [{ seat: 0, amount: 120 }] }],
      seats: [{ ...previous.seats[0], stack: 1_000, committed: 0 }],
    })

    const staged = stageAwardsUntilArrival(previous, complete)
    expect(staged.potTotal).toBe(120)
    expect(staged.pots).toEqual(previous.pots)
    expect(staged.seats[0]).toMatchObject({ stack: 880, committed: 0 })
    expect(staged.awards).toEqual(complete.awards)
  })

  it('keeps the previous board in place until street chips have been collected', () => {
    const previous = game({ phase: 'FLOP', board: ['Ah', '9c', '2d'] })
    const turn = game({ phase: 'TURN', board: [...previous.board, 'Ks'] })

    const staged = stageBoardUntilBetsCollected(previous, turn)
    expect(staged.phase).toBe('FLOP')
    expect(staged.board).toEqual(previous.board)
    expect(staged.awards).toEqual([])
  })
})

describe('buildStreetFrames', () => {
  it('turns a multi-card runout snapshot into flop, turn, and river frames', () => {
    const previous = game({ phase: 'PREFLOP', board: [] })
    const river = game({ phase: 'RIVER', board: ['Ah', '9c', '2d', 'Ks', 'Qc'] })

    const frames = buildStreetFrames(previous, river)
    expect(frames.map((frame) => [frame.phase, frame.board.length])).toEqual([
      ['FLOP', 3],
      ['TURN', 4],
      ['RIVER', 5],
    ])
  })

  it('preserves the final showdown frame after staging an all-in runout', () => {
    const previous = game({ phase: 'PREFLOP', board: [] })
    const complete = game({
      phase: 'COMPLETE',
      completionReason: 'SHOWDOWN',
      board: ['Ah', '9c', '2d', 'Ks', 'Qc'],
      potTotal: 0,
      pots: [],
      awards: [{ potIndex: 0, potAmount: 20, winners: [{ seat: 0, amount: 20 }] }],
      seats: [{ ...previous.seats[0], stack: 1_010, committed: 0 }],
    })

    const frames = buildStreetFrames(previous, complete)
    expect(frames.map((frame) => frame.phase)).toEqual([
      'FLOP',
      'TURN',
      'RIVER',
      'COMPLETE',
    ])
    expect(frames.every((frame) => frame.potTotal === previous.potTotal)).toBe(true)
    expect(frames.at(-1)?.seats[0].stack).toBe(previous.seats[0].stack)
    expect(frames.at(-1)?.awards).toEqual(complete.awards)
  })
})

describe('buildShowdownPrelude', () => {
  it('keeps opponents face-down and awards hidden until the reveal stage', () => {
    const previous = game({
      phase: 'RIVER',
      board: ['Ah', '9c', '2d', 'Ks', 'Qc'],
      seats: [
        { ...game().seats[0], holeCards: ['As', 'Kd'] },
        { ...game().seats[0], seat: 1, userId: 'bob', holeCards: null },
      ],
    })
    const complete = game({
      phase: 'COMPLETE',
      completionReason: 'SHOWDOWN',
      board: previous.board,
      awards: [{ potIndex: 0, potAmount: 40, winners: [{ seat: 1, amount: 40 }] }],
      seats: [
        { ...previous.seats[0], finalHandRank: 'Pair of Aces' },
        {
          ...previous.seats[1],
          holeCards: ['Qh', 'Qc'],
          finalHandRank: 'Three of a Kind, Queens',
          bestHandCards: ['Qh', 'Qc', 'Qc', 'Ah', 'Ks'],
        },
      ],
    })

    const prelude = buildShowdownPrelude(previous, complete)
    expect(prelude.phase).toBe('RIVER')
    expect(prelude.completionReason).toBeNull()
    expect(prelude.awards).toEqual([])
    expect(prelude.seats[0].holeCards).toEqual(['As', 'Kd'])
    expect(prelude.seats[1].holeCards).toBeNull()
    expect(prelude.seats[1].finalHandRank).toBeNull()
    expect(prelude.seats[1].bestHandCards).toBeUndefined()
  })
})
