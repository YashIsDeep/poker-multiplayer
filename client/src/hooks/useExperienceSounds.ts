// Non-gameplay sound cues derived from successive store snapshots. This keeps
// transient audio out of the authoritative Zustand state while still covering
// room presence, chat, connection recovery, and blocking resilience events.

import { useEffect, useRef } from 'react'
import { playSound, type SoundName } from '../lib/sound'
import { useGameStore } from '../store/gameStore'
import type { ConnectionState } from '../store/types'

const MAX_PRESENCE_DELTA = 2

export type ExperienceSoundState = {
  connection: ConnectionState
  readOnly: boolean
  takenOver: boolean
  roomClosed: boolean
  roomGone: boolean
  autoSeatRoomId: string | null
  room: {
    roomId: string
    membershipEpoch: number
    viewerId: string
    memberIds: string[]
  } | null
  messages: Array<{ id: string; senderId: string }>
}

function newlyCritical(prev: ExperienceSoundState, next: ExperienceSoundState): boolean {
  return (
    (!prev.takenOver && next.takenOver) ||
    (!prev.roomClosed && next.roomClosed) ||
    (!prev.roomGone && next.roomGone)
  )
}

export function deriveExperienceSoundEvents(
  prev: ExperienceSoundState | null,
  next: ExperienceSoundState,
): SoundName[] {
  if (!prev) return []

  const events: SoundName[] = []
  const critical = newlyCritical(prev, next)

  // A blocking resilience event is more important than the connection state
  // change that may accompany it, so emit one decisive alert rather than two.
  if (critical) {
    events.push('error')
  } else if (
    prev.connection === 'connected' &&
    (next.connection === 'reconnecting' || next.connection === 'offline') &&
    !next.readOnly
  ) {
    events.push('connectionLost')
  } else if (
    (prev.connection === 'reconnecting' || prev.connection === 'offline') &&
    next.connection === 'connected'
  ) {
    events.push('connectionRestored')
  }

  if (prev.autoSeatRoomId === null && next.autoSeatRoomId !== null) {
    events.push('open')
  }

  const sameLiveRoom =
    prev.connection === 'connected' &&
    next.connection === 'connected' &&
    prev.room !== null &&
    next.room !== null &&
    prev.room.roomId === next.room.roomId &&
    prev.room.membershipEpoch === next.room.membershipEpoch

  if (!sameLiveRoom || !prev.room || !next.room) return events

  const previousMembers = new Set(prev.room.memberIds)
  const nextMembers = new Set(next.room.memberIds)
  const joined = next.room.memberIds.filter((id) => !previousMembers.has(id))
  const left = prev.room.memberIds.filter((id) => !nextMembers.has(id))
  const presenceDelta = joined.length + left.length

  // Large deltas indicate a recovered/bulk snapshot, not live table activity.
  if (presenceDelta > 0 && presenceDelta <= MAX_PRESENCE_DELTA) {
    if (left.length > 0) events.push('playerLeave')
    if (joined.length > 0) events.push('playerJoin')
  }

  const previousMessages = new Set(prev.messages.map((message) => message.id))
  const incomingMessage = next.messages.some(
    (message) =>
      !previousMessages.has(message.id) &&
      message.senderId !== next.room?.viewerId,
  )
  if (incomingMessage) events.push('message')

  return events
}

export function useExperienceSounds() {
  const connection = useGameStore((state) => state.connection)
  const readOnly = useGameStore((state) => state.readOnly)
  const takenOver = useGameStore((state) => state.takenOver)
  const roomClosed = useGameStore((state) => state.roomClosed)
  const roomGone = useGameStore((state) => state.roomGone)
  const autoSeatRoomId = useGameStore((state) => state.autoSeatRoomId)
  const room = useGameStore((state) => state.room)
  const chatMessages = useGameStore((state) => state.chatMessages)
  const previous = useRef<ExperienceSoundState | null>(null)

  useEffect(() => {
    const next: ExperienceSoundState = {
      connection,
      readOnly,
      takenOver,
      roomClosed,
      roomGone,
      autoSeatRoomId,
      room: room
        ? {
            roomId: room.roomId,
            membershipEpoch: room.membershipEpoch,
            viewerId: room.viewer.userId,
            memberIds: room.members.map((member) => member.userId),
          }
        : null,
      messages: chatMessages.map(({ id, senderId }) => ({ id, senderId })),
    }

    const events = deriveExperienceSoundEvents(previous.current, next)
    previous.current = next
    for (const event of events) playSound(event)
  }, [
    autoSeatRoomId,
    chatMessages,
    connection,
    readOnly,
    room,
    roomClosed,
    roomGone,
    takenOver,
  ])
}
