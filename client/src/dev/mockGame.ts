// DEV-ONLY mock driver (?mock=<state> on /room/*): feeds synthetic per-viewer
// snapshots into the store so every T7/T8 screen state is verifiable before the
// T6 server gameplay lands. Never bundled into a production flow — the Room page
// only calls startMockDriver when import.meta.env.DEV is true.
//
// States: turn | wait | showdown | busted | sitout | hand (scripted interactive
// hand loop). Optional &seats=2..9 controls table size.

import { useGameStore } from '../store/gameStore'
import type { Game, GameSeat, RoomMemberView, RoomSnapshot, SeatView } from '../store/types'

const NAMES = ['yash', 'alice', 'bob', 'carl', 'dana', 'eve', 'frank', 'gus', 'hana']

let revision = 1

function baseRoom(seatCount: number): Omit<RoomSnapshot, 'game'> {
  const members: RoomMemberView[] = NAMES.slice(0, seatCount).map((name, i) => ({
    userId: `u-${name}`,
    displayName: name,
    avatarUrl: null,
    joinedAt: new Date().toISOString(),
    seat: i,
    connected: true,
    disconnectedSince: null,
    admin: i === 0,
  }))
  const seats: SeatView[] = members.map((m, i) => ({ seat: i, userId: m.userId }))
  return {
    roomId: 'mock-room',
    joinCode: 'MOCK01',
    name: 'Mock Table',
    maxSeats: seatCount,
    privateTable: true,
    canEditSettings: true,
    membershipEpoch: 1,
    revision: revision++,
    serverNow: new Date().toISOString(),
    idleExpiresAt: new Date(Date.now() + 60 * 60_000).toISOString(),
    members,
    seats,
    config: {
      smallBlind: 5,
      bigBlind: 10,
      minBuyIn: 200,
      maxBuyIn: 2000,
      actionTimeoutSeconds: 30,
      startingStack: 200,
      rebuysEnabled: true,
      rebuyStack: 200,
      rebuyDelayHands: 5,
    },
    viewer: {
      userId: 'u-yash',
      member: members[0],
      seated: true,
      seat: 0,
      canTakeSeat: false,
      canStand: true,
      busted: false,
      rebuyAmount: null,
      rebuyHandsRemaining: null,
      rebuyOfferDeadline: null,
    },
  }
}

type SeatSpec = Partial<GameSeat> & { seat: number }

const POSITION_LABELS: Record<number, NonNullable<GameSeat['position']>[]> = {
  2: ['BN/SB', 'BB'],
  3: ['BN', 'SB', 'BB'],
  4: ['BN', 'SB', 'BB', 'CO'],
  5: ['BN', 'SB', 'BB', 'UTG', 'CO'],
  6: ['BN', 'SB', 'BB', 'UTG', 'MP', 'CO'],
  7: ['BN', 'SB', 'BB', 'UTG', 'MP', 'HJ', 'CO'],
  8: ['BN', 'SB', 'BB', 'UTG', 'UTG+1', 'MP', 'HJ', 'CO'],
  9: ['BN', 'SB', 'BB', 'UTG', 'UTG+1', 'MP', 'LJ', 'HJ', 'CO'],
}

function gameSeats(seatCount: number, buttonSeat: number, specs: SeatSpec[]): GameSeat[] {
  const stacks = [1850, 2430, 990, 3120, 560, 760, 2010, 1555, 880]
  return Array.from({ length: seatCount }, (_, i) => {
    const spec = specs.find((s) => s.seat === i)
    const seat = {
      seat: i,
      userId: `u-${NAMES[i]}`,
      stack: stacks[i],
      seatStatus: 'ACTIVE',
      holeCards: null,
      committed: 0,
      lastAction: null,
      actions: [],
      position: POSITION_LABELS[seatCount][(i - buttonSeat + seatCount) % seatCount],
      finalHandRank: null,
      bestHandCards: [],
      rebuyHandsRemaining: 0,
      rebought: false,
      rebuyCount: 0,
      ...spec,
    } as GameSeat
    if (spec?.actions === undefined && spec?.lastAction) seat.actions = [spec.lastAction]
    return seat
  })
}

function makeGame(seatCount: number, overrides: Partial<Game>, specs: SeatSpec[]): Game {
  const actingSeat = overrides.actingSeat === undefined ? 1 % seatCount : overrides.actingSeat
  const toCall = overrides.toCall ?? 120
  const minRaiseTo = overrides.minRaiseTo ?? 240
  const maxRaiseTo = overrides.maxRaiseTo ?? 1850
  const canAct = actingSeat === 0
  const buttonSeat = overrides.buttonSeat ?? Math.min(3, seatCount - 1)
  const pots = overrides.pots ?? [
    { amount: 340, eligibleSeats: Array.from({ length: seatCount }, (_, seat) => seat) },
  ]
  const potTotal = overrides.potTotal ?? pots.reduce((total, pot) => total + pot.amount, 0)
  return {
    tableStatus: 'RUNNING',
    handId: 42,
    phase: 'FLOP',
    completionReason: null,
    board: ['Kh', '9c', '2d'],
    buttonSeat,
    smallBlindSeat: seatCount === 2 ? buttonSeat : 0,
    bigBlindSeat: seatCount === 2 ? (buttonSeat + 1) % seatCount : 1 % seatCount,
    actionSeq: 7,
    actingSeat,
    deadline: Date.now() + 18_000,
    minRaiseTo,
    maxRaiseTo,
    toCall,
    currentBet: 120,
    potTotal,
    pots,
    awards: [],
    seats: gameSeats(seatCount, buttonSeat, specs),
    viewer: {
      seated: true,
      seat: 0,
      canAct,
      rebuyAmount: null,
      rebuyHandsRemaining: null,
      legalActions: canAct
        ? {
            canFold: true,
            canCheck: toCall === 0,
            canCall: toCall > 0,
            callAmount: toCall,
            toCall,
            canRaise: maxRaiseTo >= minRaiseTo && maxRaiseTo > 0,
            minRaiseTo,
            maxRaiseTo,
          }
        : null,
    },
    ...overrides,
  }
}

function apply(room: Omit<RoomSnapshot, 'game'>, game: Game | null) {
  useGameStore.getState().__applyRoomSnapshot({ ...room, revision: revision++, serverNow: new Date().toISOString(), game })
}

const COMMON_SPECS: SeatSpec[] = [
  { seat: 0, holeCards: ['As', 'Kd'] },
  { seat: 1, committed: 120, lastAction: { action: 'RAISE_TO', amount: 120 } },
  { seat: 2, seatStatus: 'FOLDED', lastAction: { action: 'FOLD', amount: null } },
  { seat: 3, committed: 120, lastAction: { action: 'CALL', amount: 120 } },
  { seat: 4, seatStatus: 'ALL_IN', stack: 0, committed: 560, lastAction: { action: 'RAISE_TO', amount: 560 } },
  { seat: 5, lastAction: { action: 'CHECK', amount: null } },
  { seat: 6, seatStatus: 'SITTING_OUT' },
  { seat: 7, committed: 120, lastAction: { action: 'CALL', amount: 120 } },
  { seat: 8, seatStatus: 'FOLDED', lastAction: { action: 'FOLD', amount: null } },
]

function staticState(state: string, seatCount: number): Game {
  switch (state) {
    case 'wait':
      return makeGame(seatCount, {
        actingSeat: 1 % seatCount,
        pots: [{ amount: 340, eligibleSeats: [0, 1] }, { amount: 890, eligibleSeats: [1] }],
      }, COMMON_SPECS)
    case 'showdown':
      return makeGame(
        seatCount,
        {
          phase: 'COMPLETE',
          completionReason: 'SHOWDOWN',
          board: ['Kh', '9c', '2d', '9s', 'Qc'],
          actingSeat: null,
          pots: [{ amount: 2180, eligibleSeats: [0, 1 % seatCount] }],
          awards: [{
            potIndex: 0,
            potAmount: 2180,
            winners: [{ seat: 1 % seatCount, amount: 2180 }],
          }],
        },
        [
          {
            seat: 0,
            holeCards: ['As', 'Jd'],
            finalHandRank: 'Pair of Nines',
            bestHandCards: ['9c', '9s', 'As', 'Kh', 'Qc'],
            lastAction: { action: 'CALL', amount: 120 },
          },
          {
            seat: 1 % seatCount,
            holeCards: ['Kd', '9d'],
            finalHandRank: 'Nines full of Kings',
            bestHandCards: ['Kh', '9c', '9s', 'Kd', '9d'],
          },
          ...COMMON_SPECS.slice(2),
        ],
      )
    case 'busted':
      return makeGame(
        seatCount,
        {
          actingSeat: 1 % seatCount,
          viewer: {
            seated: true,
            seat: 0,
            canAct: false,
            rebuyAmount: 400,
            rebuyHandsRemaining: 5,
            legalActions: null,
          },
        },
        [
          { seat: 0, seatStatus: 'BUSTED', stack: 0, rebuyHandsRemaining: 5, rebought: true, rebuyCount: 2 },
          ...COMMON_SPECS.slice(1),
        ],
      )
    case 'sitout':
      return makeGame(seatCount, { actingSeat: 1 % seatCount }, [
        { seat: 0, seatStatus: 'SITTING_OUT' },
        ...COMMON_SPECS.slice(1),
      ])
    case 'turn':
    default:
      return makeGame(seatCount, { actingSeat: 0 }, COMMON_SPECS)
  }
}

export function startMockDriver(state: string, search: URLSearchParams) {
  if (!import.meta.env.DEV) return
  const seatCount = Math.min(9, Math.max(2, Number(search.get('seats') ?? 6)))
  const store = useGameStore.getState()
  store.__setMockMode(true)

  if (state !== 'hand') {
    const room = baseRoom(seatCount)
    if (state === 'busted') {
      room.members[0] = { ...room.members[0], seat: null }
      room.seats[0] = { seat: 0, userId: null }
      room.viewer = {
        ...room.viewer,
        member: room.members[0],
        seated: false,
        seat: null,
        canTakeSeat: false,
        canStand: false,
        busted: true,
        rebuyAmount: 400,
        rebuyHandsRemaining: 5,
        rebuyOfferDeadline: Date.now() + 5_000,
      }
    }
    apply(room, staticState(state, seatCount))
    applyResilienceState(search.get('resilience'))
    return
  }

  runScriptedHand(seatCount)
}

function applyResilienceState(state: string | null) {
  switch (state) {
    case 'reconnecting':
      useGameStore.setState({ connection: 'reconnecting', reconnectAttempt: 1 })
      break
    case 'offline':
      useGameStore.setState({ connection: 'offline', reconnectAttempt: 10 })
      break
    case 'closed':
      useGameStore.setState({ roomClosed: true })
      break
    case 'gone':
      useGameStore.setState({ roomGone: true })
      useGameStore.getState().pushToast('error', 'ROOM_NOT_FOUND', 'that table no longer exists')
      break
    case 'takeover':
      useGameStore.setState({ readOnly: true, takenOver: true, connection: 'offline' })
      break
  }
}

// Scripted interactive hand: blinds → opponents act (chip fly) → your turn
// (any action advances) → flop/turn/river deals → showdown → next hand.
function runScriptedHand(seatCount: number) {
  const room = baseRoom(seatCount)
  let handId = 1
  let actionSeq = 0

  const step = (game: Partial<Game>, specs: SeatSpec[]) => {
    actionSeq += 1
    apply(
      room,
      makeGame(seatCount, { handId, actionSeq, ...game }, specs),
    )
  }

  const waitForYou = (then: () => void) => {
    const unsub = useGameStore.subscribe((s) => {
      if (s.pendingAction) {
        unsub()
        setTimeout(then, 350)
      }
    })
  }

  const you = (over: Partial<GameSeat> = {}): SeatSpec => ({ seat: 0, holeCards: ['As', 'Kd'], ...over })
  const opp = (seat: number, over: Partial<GameSeat> = {}): SeatSpec => ({ seat, ...over })

  const preflop = () => {
    // blinds posted, action on you
    step(
      {
        phase: 'PREFLOP',
        board: [],
        actingSeat: 0,
        deadline: Date.now() + 30_000,
        toCall: 10,
        minRaiseTo: 20,
        maxRaiseTo: 1850,
        pots: [{ amount: 15, eligibleSeats: [0, 1 % seatCount, 2 % seatCount] }],
      },
      [
        you({ committed: 0 }),
        opp(1, { committed: 5, lastAction: null }),
        opp(2 % seatCount, { committed: 10, lastAction: null }),
      ],
    )
    waitForYou(flop)
  }

  const flop = () => {
    step(
      {
        phase: 'FLOP',
        board: ['Kh', '9c', '2d'],
        actingSeat: 1,
        deadline: Date.now() + 18_000,
        toCall: 0,
        pots: [{ amount: 30, eligibleSeats: [0, 1 % seatCount, 2 % seatCount] }],
      },
      [you({ committed: 0, lastAction: { action: 'CALL', amount: 10 } }), opp(1), opp(2 % seatCount)],
    )
    setTimeout(oppBets, 2500)
  }

  const oppBets = () => {
    step(
      {
        phase: 'FLOP',
        board: ['Kh', '9c', '2d'],
        actingSeat: 0,
        deadline: Date.now() + 30_000,
        toCall: 120,
        minRaiseTo: 240,
        maxRaiseTo: 1850,
        // Server potTotal includes both previously collected chips and current
        // street commitments. Keep the visual driver on that same contract.
        pots: [{ amount: 150, eligibleSeats: [0, 1 % seatCount, 2 % seatCount] }],
      },
      [you(), opp(1, { committed: 120, lastAction: { action: 'RAISE_TO', amount: 120 } }), opp(2 % seatCount)],
    )
    waitForYou(river)
  }

  const river = () => {
    step(
      {
        phase: 'RIVER',
        board: ['Kh', '9c', '2d', '9s', 'Qc'],
        actingSeat: null,
        deadline: Date.now() + 5000,
        pots: [{ amount: 270, eligibleSeats: [0, 1 % seatCount] }],
      },
      [you({ lastAction: { action: 'CALL', amount: 120 } }), opp(1)],
    )
    setTimeout(showdown, 2000)
  }

  const showdown = () => {
    step(
      {
        phase: 'COMPLETE',
        completionReason: 'SHOWDOWN',
        board: ['Kh', '9c', '2d', '9s', 'Qc'],
        actingSeat: null,
        pots: [{ amount: 270, eligibleSeats: [0, 1 % seatCount] }],
        awards: [{ potIndex: 0, potAmount: 270, winners: [{ seat: 0, amount: 270 }] }],
      },
      [
        you({
          finalHandRank: 'Two pair, Kings and Nines',
          bestHandCards: ['As', 'Kd', 'Kh', '9c', '9s'],
        }),
        opp(1, {
          holeCards: ['Jd', 'Td'],
          finalHandRank: 'Pair of Nines',
          bestHandCards: ['9c', '9s', 'Kh', 'Qc', 'Jd'],
        }),
      ],
    )
    setTimeout(() => {
      handId += 1
      preflop()
    }, 5000)
  }

  preflop()
}
