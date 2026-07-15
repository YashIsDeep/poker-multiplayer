import { describe, expect, it } from 'vitest'
import {
  deriveExperienceSoundEvents,
  type ExperienceSoundState,
} from './useExperienceSounds'

function state(overrides: Partial<ExperienceSoundState> = {}): ExperienceSoundState {
  return {
    connection: 'connected',
    readOnly: false,
    takenOver: false,
    roomClosed: false,
    roomGone: false,
    autoSeatRoomId: null,
    room: {
      roomId: 'room-1',
      membershipEpoch: 1,
      viewerId: 'alice',
      memberIds: ['alice', 'bob'],
    },
    messages: [],
    ...overrides,
  }
}

describe('deriveExperienceSoundEvents', () => {
  it('stays quiet for the initial app snapshot', () => {
    expect(deriveExperienceSoundEvents(null, state())).toEqual([])
  })

  it('announces connection loss and recovery without sounding on initial connect', () => {
    const lost = state({ connection: 'reconnecting' })
    expect(deriveExperienceSoundEvents(state(), lost)).toEqual(['connectionLost'])
    expect(deriveExperienceSoundEvents(lost, state())).toEqual(['connectionRestored'])
    expect(
      deriveExperienceSoundEvents(state({ connection: 'connecting' }), state()),
    ).toEqual([])
  })

  it('uses one critical alert for takeover instead of stacking a disconnect cue', () => {
    const takenOver = state({
      connection: 'offline',
      readOnly: true,
      takenOver: true,
    })
    expect(deriveExperienceSoundEvents(state(), takenOver)).toEqual(['error'])
  })

  it('announces small live room joins and leaves', () => {
    const joined = state({
      room: {
        roomId: 'room-1',
        membershipEpoch: 1,
        viewerId: 'alice',
        memberIds: ['alice', 'bob', 'carol'],
      },
    })
    expect(deriveExperienceSoundEvents(state(), joined)).toEqual(['playerJoin'])
    expect(deriveExperienceSoundEvents(joined, state())).toEqual(['playerLeave'])
  })

  it('suppresses presence noise for bulk recovered snapshots', () => {
    const recovered = state({
      room: {
        roomId: 'room-1',
        membershipEpoch: 1,
        viewerId: 'alice',
        memberIds: ['alice', 'bob', 'carol', 'dave', 'erin'],
      },
    })
    expect(deriveExperienceSoundEvents(state(), recovered)).toEqual([])
  })

  it('chimes only for newly received messages from another player', () => {
    const ownMessage = state({ messages: [{ id: 'm1', senderId: 'alice' }] })
    expect(deriveExperienceSoundEvents(state(), ownMessage)).toEqual([])

    const incoming = state({ messages: [{ id: 'm2', senderId: 'bob' }] })
    expect(deriveExperienceSoundEvents(state(), incoming)).toEqual(['message'])
    expect(deriveExperienceSoundEvents(incoming, incoming)).toEqual([])
  })

  it('announces the auto-seat flow when it opens', () => {
    expect(
      deriveExperienceSoundEvents(state(), state({ autoSeatRoomId: 'room-1' })),
    ).toEqual(['open'])
  })
})
