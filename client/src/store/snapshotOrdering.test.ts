import { describe, expect, it } from 'vitest'
import { isSnapshotNewer } from './types'
import type { Game, RoomSnapshot } from './types'
import { useGameStore } from './gameStore'

describe('isSnapshotNewer', () => {
  it('accepts anything when there is no current stamp', () => {
    expect(isSnapshotNewer(null, { epoch: 1, revision: 1 })).toBe(true)
  })

  it('prefers a higher membership epoch regardless of revision', () => {
    expect(isSnapshotNewer({ epoch: 1, revision: 99 }, { epoch: 2, revision: 1 })).toBe(true)
    expect(isSnapshotNewer({ epoch: 2, revision: 1 }, { epoch: 1, revision: 99 })).toBe(false)
  })

  it('compares revisions within the same epoch', () => {
    expect(isSnapshotNewer({ epoch: 1, revision: 5 }, { epoch: 1, revision: 6 })).toBe(true)
    expect(isSnapshotNewer({ epoch: 1, revision: 5 }, { epoch: 1, revision: 5 })).toBe(false)
    expect(isSnapshotNewer({ epoch: 1, revision: 5 }, { epoch: 1, revision: 4 })).toBe(false)
  })
})

// ---------- store-level acceptance ----------

function snapshot(overrides: Partial<RoomSnapshot> & { membershipEpoch: number; revision: number }): RoomSnapshot {
  return {
    roomId: 'room-1',
    joinCode: 'ABC123',
    name: 'Test Table',
    maxSeats: 6,
    serverNow: new Date().toISOString(),
    idleExpiresAt: new Date(Date.now() + 60 * 60_000).toISOString(),
    members: [
      {
        userId: 'u-1',
        displayName: 'alice',
        avatarUrl: null,
        joinedAt: new Date().toISOString(),
        seat: 0,
        connected: true,
        disconnectedSince: null,
      },
    ],
    seats: [{ seat: 0, userId: 'u-1' }],
    config: {
      smallBlind: 5,
      bigBlind: 10,
      minBuyIn: 200,
      maxBuyIn: 2000,
      actionTimeoutSeconds: 30,
    },
    viewer: {
      userId: 'u-1',
      member: null,
      seated: true,
      seat: 0,
      canTakeSeat: false,
      canStand: true,
    },
    game: null,
    ...overrides,
  }
}

function makeGame(actionSeq: number): Game {
  return {
    tableStatus: 'RUNNING',
    handId: 1,
    phase: 'FLOP',
    completionReason: null,
    board: ['Kh', '9c', '2d'],
    buttonSeat: 0,
    smallBlindSeat: 0,
    bigBlindSeat: 1,
    actionSeq,
    actingSeat: 0,
    deadline: Date.now() + 30_000,
    minRaiseTo: 20,
    maxRaiseTo: 100,
    toCall: 0,
    currentBet: 0,
    pots: [{ amount: 30, eligibleSeats: [0] }],
    awards: [],
    seats: [{
      seat: 0,
      userId: 'u-1',
      stack: 100,
      seatStatus: 'ACTIVE',
      committed: 0,
      holeCards: ['As', 'Kd'],
      lastAction: null,
      finalHandRank: null,
    }],
    viewer: {
      seated: true,
      seat: 0,
      canAct: true,
      rebuyMinimum: null,
      rebuyMaximum: null,
      legalActions: {
        canFold: true,
        canCheck: false,
        canCall: true,
        callAmount: 10,
        toCall: 10,
        canRaise: true,
        minRaiseTo: 20,
        maxRaiseTo: 100,
      },
    },
  }
}

describe('gameStore snapshot acceptance', () => {
  // NOTE: the store is a module singleton; each test uses strictly increasing
  // epochs so earlier state never masks later assertions.

  it('applies a fresh snapshot and extracts the viewer game state', () => {
    const store = useGameStore.getState()
    store.__applyRoomSnapshot(snapshot({ membershipEpoch: 10, revision: 1, game: makeGame(5) }))
    const s = useGameStore.getState()
    expect(s.room?.revision).toBe(1)
    expect(s.game?.actionSeq).toBe(5)
    expect(s.yourSeat).toBe(0)
    expect(s.myHoleCards).toEqual(['As', 'Kd'])
  })

  it('ignores an older revision within the same epoch', () => {
    const store = useGameStore.getState()
    store.__applyRoomSnapshot(snapshot({ membershipEpoch: 20, revision: 5 }))
    store.__applyRoomSnapshot(snapshot({ membershipEpoch: 20, revision: 4, name: 'STALE' }))
    expect(useGameStore.getState().room?.revision).toBe(5)
    expect(useGameStore.getState().room?.name).not.toBe('STALE')
  })

  it('replaces state on an equal-revision resync duplicate', () => {
    const store = useGameStore.getState()
    store.__applyRoomSnapshot(snapshot({ membershipEpoch: 30, revision: 7 }))
    store.__applyRoomSnapshot(snapshot({ membershipEpoch: 30, revision: 7, name: 'RESYNC' }))
    expect(useGameStore.getState().room?.name).toBe('RESYNC')
  })

  it('ignores snapshots from an older membership epoch', () => {
    const store = useGameStore.getState()
    store.__applyRoomSnapshot(snapshot({ membershipEpoch: 40, revision: 1 }))
    store.__applyRoomSnapshot(snapshot({ membershipEpoch: 39, revision: 99, name: 'OLD-EPOCH' }))
    expect(useGameStore.getState().room?.name).not.toBe('OLD-EPOCH')
    expect(useGameStore.getState().membershipEpoch).toBe(40)
  })

  it('accepts a newer epoch even with a lower revision', () => {
    const store = useGameStore.getState()
    store.__applyRoomSnapshot(snapshot({ membershipEpoch: 50, revision: 42 }))
    store.__applyRoomSnapshot(snapshot({ membershipEpoch: 51, revision: 1, name: 'NEW-EPOCH' }))
    expect(useGameStore.getState().room?.name).toBe('NEW-EPOCH')
  })

  it('clears a pending action once a snapshot advances actionSeq past it', () => {
    const store = useGameStore.getState()
    store.__applyRoomSnapshot(snapshot({ membershipEpoch: 60, revision: 1, game: makeGame(5) }))
    useGameStore.setState({
      pendingAction: { type: 'call', actionSeq: 5, requestId: 'r-1', sentAt: Date.now() },
    })
    // same actionSeq → still pending
    store.__applyRoomSnapshot(snapshot({ membershipEpoch: 60, revision: 2, game: makeGame(5) }))
    expect(useGameStore.getState().pendingAction).not.toBeNull()
    // newer actionSeq → stale, auto-cleared
    store.__applyRoomSnapshot(snapshot({ membershipEpoch: 60, revision: 3, game: makeGame(6) }))
    expect(useGameStore.getState().pendingAction).toBeNull()
  })

  it('does not let a stale state.sync response clear a newer room', () => {
    const store = useGameStore.getState()
    store.__applyRoomSnapshot(snapshot({ membershipEpoch: 70, revision: 3, name: 'CURRENT' }))
    store.__applyStateSync({
      membershipEpoch: 69,
      room: null,
      lobby: { revision: 1, serverNow: new Date().toISOString(), rooms: [] },
    })
    expect(useGameStore.getState().room?.name).toBe('CURRENT')
    expect(useGameStore.getState().membershipEpoch).toBe(70)
  })

  it('ignores an exit notice for a different room', () => {
    const store = useGameStore.getState()
    store.__applyRoomSnapshot(snapshot({ membershipEpoch: 80, revision: 1, name: 'CURRENT' }))
    store.__handleRoomEvent({
      roomId: 'some-other-room',
      reason: 'IDLE_TIMEOUT',
      membershipEpoch: 80,
      serverNow: new Date().toISOString(),
    })
    expect(useGameStore.getState().room?.name).toBe('CURRENT')
    expect(useGameStore.getState().roomClosed).toBe(false)
  })

  it('turns both terminal recovery errors into table-loss for room-scoped commands', () => {
    const store = useGameStore.getState()
    useGameStore.setState({ roomGone: false, toasts: [] })
    const failure = {
      requestId: 'missing-room',
      ok: false,
      error: 'ROOM_NOT_FOUND' as const,
      roomId: null,
      roomRevision: null,
      lobbyRevision: null,
      state: null,
      actionSeq: null,
      retryAfterMs: null,
    }

    store.__handleAck({ ...failure, command: 'ROOM_JOIN' })
    expect(useGameStore.getState().roomGone).toBe(false)

    store.__handleAck({ ...failure, command: 'ROOM_SYNC' })
    expect(useGameStore.getState().roomGone).toBe(true)

    useGameStore.setState({ roomGone: false })
    store.__handleAck({
      ...failure,
      requestId: 'not-member',
      command: 'ROOM_SYNC',
      error: 'NOT_A_ROOM_MEMBER',
    })
    expect(useGameStore.getState().roomGone).toBe(true)
  })

  it('treats disconnected-member eviction as terminal room recovery', () => {
    const store = useGameStore.getState()
    store.__applyRoomSnapshot(snapshot({ membershipEpoch: 85, revision: 1 }))
    useGameStore.setState({ roomGone: false })
    store.__handleRoomEvent({
      roomId: 'room-1',
      reason: 'DISCONNECTED_TIMEOUT',
      membershipEpoch: 86,
      serverNow: new Date().toISOString(),
    })
    expect(useGameStore.getState().room).toBeNull()
    expect(useGameStore.getState().roomGone).toBe(true)
  })

  it('clamps raises in the store and refuses actions the server did not mark legal', () => {
    const store = useGameStore.getState()
    store.__setMockMode(true)
    store.__applyRoomSnapshot(snapshot({ membershipEpoch: 90, revision: 1, game: makeGame(12) }))

    useGameStore.setState({ pendingAction: null })
    store.act('raiseTo', 999)
    expect(useGameStore.getState().pendingAction).toMatchObject({
      type: 'raiseTo',
      amount: 100,
      actionSeq: 12,
    })

    useGameStore.setState({ pendingAction: null })
    store.act('check')
    expect(useGameStore.getState().pendingAction).toBeNull()
  })
})
