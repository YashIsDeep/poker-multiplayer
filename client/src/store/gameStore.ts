// The game store (T7) + resilience machine (T10).
// One STOMP socket per tab feeds this store; components read state and call the
// command methods. All freshness rules (membership epoch → room revision) and
// the reconnect/backoff/multi-tab machinery live here.

import { create } from 'zustand'
import { RANKED_SETTLED_EVENT } from '../lib/ranked'
import { PokerStompClient } from '../lib/stompClient'
import { createTabArbiter } from '../lib/tabArbiter'
import type { TabArbiter } from '../lib/tabArbiter'
import { createBackoff, MAX_ATTEMPTS } from '../lib/backoff'
import { createEMA, getServerNow, updateEMA } from '../lib/clockOffsetEMA'
import type {
  CommandAck,
  RankedQueueState,
  ChatMessage,
  ConnectionState,
  ErrorCode,
  Game,
  LobbySnapshot,
  PendingAction,
  PlayerActionType,
  RoomExitNotice,
  RoomSnapshot,
  StateSyncView,
  TableSettings,
} from './types'

// ---------- toasts ----------

export type ToastType = 'info' | 'ok' | 'warn' | 'error'
export type Toast = { id: number; type: ToastType; message: string; detail?: string }

const TOAST_TTL: Record<ToastType, number> = { warn: 5000, error: 5000, info: 8000, ok: 8000 }

// ---------- store shape ----------

export type GameStore = {
  rankedQueue: RankedQueueState | null
  joinRanked: () => Promise<CommandAck>
  cancelRanked: () => Promise<CommandAck>
  connection: ConnectionState
  reconnectAttempt: number
  readOnly: boolean
  lastError: string | null

  lobby: LobbySnapshot | null
  membershipEpoch: number
  room: RoomSnapshot | null
  game: Game | null
  yourSeat: number | null
  myHoleCards: string[] | null
  /** Set only by a fresh create/join/rebuy ack; reconnect and refresh never reopen the prompt. */
  autoSeatRoomId: string | null
  chatMessages: ChatMessage[]

  /** Monotonic tick bumped on every EMA update so hooks depending on serverNow re-derive. */
  clockTick: number

  pendingAction: PendingAction | null
  toasts: Toast[]

  roomClosed: boolean
  roomGone: boolean
  takenOver: boolean

  // lifecycle
  init: () => void
  manualRetry: () => void
  claimThisTab: () => void

  // room commands (resolve with the server ack)
  stateSync: () => Promise<CommandAck>
  createRoom: (
    name: string,
    maxSeats: number,
    privateTable: boolean,
    settings?: TableSettings,
  ) => Promise<CommandAck>
  joinRoom: (joinCode: string) => Promise<CommandAck>
  syncRoom: (roomId: string) => Promise<CommandAck>
  keepRoomAlive: () => Promise<CommandAck>
  updateRoomSettings: (settings: TableSettings) => Promise<CommandAck>
  leaveRoom: () => Promise<CommandAck | null>
  takeSeat: (seat: number) => Promise<CommandAck>
  stand: () => Promise<CommandAck>
  sendChatMessage: (message: string) => Promise<CommandAck | null>

  // game commands (T6 destinations)
  act: (action: PlayerActionType, amount?: number) => void
  rebuy: (amount: number) => void
  sitOut: () => Promise<CommandAck | null>
  sitBack: () => void

  // ui helpers
  pushToast: (type: ToastType, message: string, detail?: string) => void
  dismissToast: (id: number) => void
  dismissAutoSeat: () => void
  acknowledgeRoomClosed: () => void
  acknowledgeRoomGone: () => void

  // exposed for tests / dev mock driver
  __applyRoomSnapshot: (snapshot: RoomSnapshot) => void
  __applyStateSync: (state: StateSyncView) => void
  __handleAck: (ack: CommandAck) => void
  __handleRoomEvent: (notice: RoomExitNotice) => void
  __handleChat: (message: ChatMessage) => void
  __setMockMode: (on: boolean) => void
}

// ---------- module-level (non-reactive) machinery ----------

const ema = createEMA(0.1)
let clockNow = () => Date.now()

export function serverNow(): number {
  return getServerNow(ema, clockNow())
}

type PendingRequest = {
  resolve: (ack: CommandAck) => void
  reject: (err: Error) => void
  timer: ReturnType<typeof setTimeout>
}
const pendingRequests = new Map<string, PendingRequest>()

const backoff = createBackoff()
let client: PokerStompClient | null = null
let arbiter: TabArbiter | null = null
let initialized = false
let intentionalClose = false
let leavingRoomId: string | null = null
let recoveryRoomId: string | null = null
let reconnectScheduled = false
let mockMode = false
let toastSeq = 0

const ACK_TIMEOUT_MS = 10_000
const CHAT_MESSAGE_TTL_MS = 6_000
const chatExpiryTimers = new Map<string, ReturnType<typeof setTimeout>>()

function clearChatExpiryTimers() {
  for (const timer of chatExpiryTimers.values()) clearTimeout(timer)
  chatExpiryTimers.clear()
}

function rejectPendingRequests(message: string) {
  for (const pending of pendingRequests.values()) {
    clearTimeout(pending.timer)
    pending.reject(new Error(message))
  }
  pendingRequests.clear()
}

// T6 gameplay destinations.
const GAME_ACT_DESTINATION = '/app/game.act'
const GAME_REBUY_DESTINATION = '/app/game.rebuy'
const GAME_SIT_OUT_DESTINATION = '/app/game.sit-out'
const GAME_SIT_BACK_DESTINATION = '/app/game.sit-in'

function newRequestId(): string {
  return crypto.randomUUID()
}

function tickClock(serverNowIso: string | undefined, set: (partial: Partial<GameStore>) => void, tick: number) {
  if (!serverNowIso) return
  const parsed = Date.parse(serverNowIso)
  if (!Number.isNaN(parsed)) {
    updateEMA(ema, parsed, clockNow())
    set({ clockTick: tick + 1 })
  }
}

type AckErrorCopy = { message: string; detail?: string }

function ackErrorCopy(ack: Pick<CommandAck, 'command' | 'error'>): AckErrorCopy {
  const error = ack.error as ErrorCode

  // A join-code lookup uses ROOM_NOT_FOUND for a well-formed code that does
  // not match a live table, while malformed codes arrive as INVALID_REQUEST.
  // Both are join-form errors, not evidence that the current player lost a
  // table membership.
  if (ack.command === 'ROOM_JOIN') {
    switch (error) {
      case 'INVALID_REQUEST':
      case 'ROOM_NOT_FOUND':
        return {
          message: 'Invalid table code',
          detail: 'check the 6-character code and try again',
        }
      case 'ROOM_FULL':
        return { message: 'Table is full', detail: 'try another table or wait for a seat' }
      case 'ALREADY_IN_ANOTHER_ROOM':
        return {
          message: 'Already at another table',
          detail: 'leave your current table before joining this one',
        }
      default:
        break
    }
  }

  switch (error) {
    case 'RANKED_UNAVAILABLE':
      return { message: 'Ranked play unavailable', detail: 'Ranked must be enabled and requires an eligible account.' }
    case 'ROOM_NOT_FOUND':
      return {
        message: 'Table no longer available',
        detail: 'it may have been closed or the server restarted',
      }
    case 'NOT_A_ROOM_MEMBER':
      return {
        message: 'You are no longer in this table',
        detail: 'return to the lobby and join another table',
      }
    case 'ROOM_FULL':
      return { message: 'Table is full', detail: 'try another table or wait for a seat' }
    case 'ALREADY_IN_ANOTHER_ROOM':
      return {
        message: 'Already at another table',
        detail: 'leave your current table before joining another one',
      }
    case 'INVALID_REQUEST':
      return { message: 'Request rejected', detail: 'check the details and try again' }
    case 'UNAUTHENTICATED':
      return { message: 'Signed out', detail: 'please sign in again' }
    case 'NOT_YOUR_TURN':
    case 'STALE_ACTION':
      return { message: 'Not your turn', detail: 'controls re-synced to current hand' }
    case 'SEAT_OUT_OF_RANGE':
      return { message: 'Invalid seat', detail: 'choose a seat at the table' }
    case 'SEAT_OCCUPIED':
      return { message: 'Seat already taken', detail: 'choose another open seat' }
    case 'ALREADY_SEATED':
      return { message: 'Already seated', detail: 'stand up before choosing another seat' }
    case 'NOT_SEATED':
      return { message: 'You are not seated', detail: 'take a seat before doing that' }
    case 'INVALID_BUY_IN':
      return { message: 'Invalid buy-in', detail: 'choose an amount within the table limits' }
    case 'INVALID_AMOUNT':
      return { message: 'Invalid amount', detail: 'enter a valid amount and try again' }
    case 'INVALID_RAISE_TO':
      return { message: 'Invalid raise', detail: 'choose an amount within the allowed range' }
    case 'HAND_NOT_ACTIVE':
      return { message: 'Hand is not active', detail: 'wait for the next hand' }
    case 'HAND_IN_PROGRESS':
      return { message: 'Hand in progress', detail: 'try again when the hand is complete' }
    case 'ACTION_NOT_LEGAL':
      return { message: 'Action not available', detail: 'controls were re-synced to the current hand' }
    case 'NOT_ROOM_ADMIN':
      return { message: 'Admin access required', detail: 'only the table admin can do that' }
    case 'NOT_A_MODERATOR':
      return {
        message: 'Moderators only',
        detail: 'public tables are moderator-created — make a private table instead',
      }
    case 'SETTINGS_NOT_EDITABLE':
      return { message: 'Settings locked', detail: 'table settings cannot change during a hand' }
    case 'REBUY_NOT_AVAILABLE':
      return { message: 'Rebuy unavailable', detail: 'there is no rebuy offer right now' }
    case 'ALREADY_SITTING_IN':
      return { message: 'Already sitting in', detail: 'you are already playing' }
    case 'ALREADY_SITTING_OUT':
      return { message: 'Already sitting out', detail: 'you are already sitting out' }
    case 'RATE_LIMITED':
      return { message: 'Too many requests', detail: 'slow down and try again shortly' }
    case 'JOIN_CODE_EXHAUSTED':
      return { message: 'Could not create a table', detail: 'please try again shortly' }
    case 'INTERNAL_ERROR':
      return { message: 'Something went wrong', detail: 'please try again' }
    default:
      return { message: `Action rejected — ${String(error).replaceAll('_', ' ').toLowerCase()}` }
  }
}

// ---------- the store ----------

export const useGameStore = create<GameStore>()((set, get) => {
  function pushToast(type: ToastType, message: string, detail?: string) {
    const id = ++toastSeq
    set({ toasts: [...get().toasts, { id, type, message, detail }] })
    setTimeout(() => {
      const { toasts } = get()
      if (toasts.some((t) => t.id === id)) set({ toasts: toasts.filter((t) => t.id !== id) })
    }, TOAST_TTL[type])
  }

  function extractView(room: RoomSnapshot | null): Pick<GameStore, 'room' | 'game' | 'yourSeat' | 'myHoleCards'> {
    if (!room) return { room: null, game: null, yourSeat: null, myHoleCards: null }
    const game = room.game ?? null
    const yourSeat = room.viewer?.seat ?? null
    const myHoleCards =
      game && yourSeat !== null
        ? (game.seats.find((s) => s.seat === yourSeat)?.holeCards ?? null)
        : null
    return { room, game, yourSeat, myHoleCards }
  }

  function applyRoomSnapshot(snapshot: RoomSnapshot) {
    const state = get()
    const current = state.room
    const currentEpoch = state.membershipEpoch
    // Membership epoch first, then room revision. Equal (epoch, revision) is a
    // resync duplicate and replaces state silently.
    if (snapshot.membershipEpoch < currentEpoch) return
    if (
      current &&
      snapshot.membershipEpoch === currentEpoch &&
      current.roomId === snapshot.roomId &&
      snapshot.revision < current.revision
    ) {
      return
    }

    tickClock(snapshot.serverNow, set, get().clockTick)

    const next: Partial<GameStore> = {
      membershipEpoch: Math.max(currentEpoch, snapshot.membershipEpoch),
      ...extractView(snapshot),
    }

    if (!current || current.roomId !== snapshot.roomId || snapshot.membershipEpoch !== currentEpoch) {
      clearChatExpiryTimers()
      next.chatMessages = []
      next.pendingAction = null
      if (current?.mode === 'RANKED' && snapshot.mode === 'RANKED' && current.roomId !== snapshot.roomId) {
        pushToast('info', 'Ranked tables combined', 'Your stack and rebuy wait moved with you.')
      }
    }

    if (
      state.autoSeatRoomId === snapshot.roomId &&
      (snapshot.viewer.seated || !snapshot.viewer.canTakeSeat)
    ) {
      next.autoSeatRoomId = null
    }

    // A snapshot with a newer actionSeq invalidates any in-flight action.
    const pending = state.pendingAction
    if (
      pending &&
      pending.actionSeq !== undefined &&
      snapshot.game &&
      snapshot.game.actionSeq > pending.actionSeq
    ) {
      next.pendingAction = null
    }
    set(next)
    if (typeof window !== 'undefined' && snapshot.mode === 'RANKED' && snapshot.game?.phase === 'COMPLETE'
      && (current?.roomId !== snapshot.roomId || current.game?.handId !== snapshot.game.handId || current.game?.phase !== 'COMPLETE')) {
      window.dispatchEvent(new Event(RANKED_SETTLED_EVENT))
    }
  }

  function applyLobby(lobby: LobbySnapshot) {
    const current = get().lobby
    if (current && lobby.revision <= current.revision) return
    tickClock(lobby.serverNow, set, get().clockTick)
    set({ lobby })
  }

  function applyStateSync(view: StateSyncView) {
    if (view.membershipEpoch < get().membershipEpoch) return
    if (view.rankedQueue && (!get().rankedQueue || view.rankedQueue.revision >= get().rankedQueue!.revision)) {
      set({ rankedQueue: view.rankedQueue })
    }
    applyLobby(view.lobby)
    const epoch = view.membershipEpoch
    if (view.room) {
      set({ membershipEpoch: epoch })
      applyRoomSnapshot(view.room)
    } else {
      clearChatExpiryTimers()
      set({
        membershipEpoch: epoch,
        room: null,
        game: null,
        yourSeat: null,
        myHoleCards: null,
        autoSeatRoomId: null,
        chatMessages: [],
      })
    }
  }

  function handleAck(ack: CommandAck) {
    const pending = pendingRequests.get(ack.requestId)
    if (pending) {
      clearTimeout(pending.timer)
      pendingRequests.delete(ack.requestId)
      pending.resolve(ack)
    }

    if (ack.ok && ack.state) applyStateSync(ack.state)

    if (!ack.ok && ack.error) {
      handleAckError(ack)
    }
  }

  function handleAckError(ack: CommandAck) {
    const error = ack.error as ErrorCode
    const state = get()
    // Any in-flight game action is dead on a rejection; reconcile to the snapshot.
    if (state.pendingAction) set({ pendingAction: null })

    const copy = ackErrorCopy(ack)
    pushToast(error === 'UNAUTHENTICATED' ? 'error' : 'warn', copy.message, copy.detail)

    if (error === 'ROOM_NOT_FOUND' || error === 'NOT_A_ROOM_MEMBER') {
      if (!['STATE_SYNC', 'ROOM_CREATE', 'ROOM_JOIN'].includes(ack.command)) {
        set({ roomGone: true })
      }
    }
  }

  function handleRoomEvent(notice: RoomExitNotice) {
    const state = get()
    if (notice.membershipEpoch < state.membershipEpoch) return // stale
    const wasVoluntary = leavingRoomId !== null && leavingRoomId === notice.roomId
    const affectsCurrentRoom = state.room !== null && state.room.roomId === notice.roomId
    if (!affectsCurrentRoom) {
      if (wasVoluntary) leavingRoomId = null
      set({ membershipEpoch: Math.max(state.membershipEpoch, notice.membershipEpoch) })
      return
    }
    clearChatExpiryTimers()
    set({
      membershipEpoch: Math.max(state.membershipEpoch, notice.membershipEpoch),
      room: null,
      game: null,
      yourSeat: null,
      myHoleCards: null,
      pendingAction: null,
      autoSeatRoomId: null,
      chatMessages: [],
      roomClosed: !wasVoluntary && notice.reason === 'IDLE_TIMEOUT',
      roomGone: !wasVoluntary && notice.reason === 'DISCONNECTED_TIMEOUT',
    })
    leavingRoomId = null
  }

  function handleChat(message: ChatMessage) {
    const state = get()
    if (!state.room || message.roomId !== state.room.roomId) return
    if (state.chatMessages.some((current) => current.id === message.id)) return
    set({ chatMessages: [...state.chatMessages, message].slice(-100) })
    const timer = setTimeout(() => {
      chatExpiryTimers.delete(message.id)
      set((current) => ({
        chatMessages: current.chatMessages.filter((currentMessage) => currentMessage.id !== message.id),
      }))
    }, CHAT_MESSAGE_TTL_MS)
    chatExpiryTimers.set(message.id, timer)
  }

  // ----- connection machine (T10) -----

  function ensureClient(): PokerStompClient {
    if (client) return client
    client = new PokerStompClient({
      onConnected: () => {
        // Queue revisions are ephemeral and restart from zero with the server.
        set({ rankedQueue: null })
        void recoverAfterConnect()
      },
      onClosed: () => {
        if (intentionalClose) return
        recoveryRoomId ??= get().room?.roomId ?? null
        rejectPendingRequests('connection closed before acknowledgement')
        scheduleReconnect()
      },
      onLobby: (body) => applyLobby(body as LobbySnapshot),
      onAck: (body) => handleAck(body as CommandAck),
      onRoom: (body) => applyRoomSnapshot(body as RoomSnapshot),
      onRoomEvent: (body) => handleRoomEvent(body as RoomExitNotice),
      onChat: (body) => handleChat(body as ChatMessage),
      onRanked: (body) => {
        const queue = body as RankedQueueState
        if (get().rankedQueue && queue.revision < get().rankedQueue!.revision) return
        set({ rankedQueue: queue })
        if (queue.status === 'ASSIGNED' && queue.roomId !== get().room?.roomId) {
          void get().stateSync().catch(() => {})
        }
      },
    })
    return client
  }

  function scheduleReconnect() {
    const state = get()
    if (state.readOnly) return // demoted tab stays down until reclaim
    if (reconnectScheduled) return
    set({ connection: 'reconnecting', pendingAction: null })
    const scheduled = backoff.schedule(() => {
      reconnectScheduled = false
      set({ reconnectAttempt: backoff.attempts })
      void ensureClient().connect()
    })
    if (!scheduled) {
      reconnectScheduled = false
      set({ connection: 'offline', reconnectAttempt: MAX_ATTEMPTS })
    } else {
      reconnectScheduled = true
      set({ reconnectAttempt: backoff.attempts })
    }
  }

  async function recoverAfterConnect() {
    try {
      const stateAck = await get().stateSync()
      if (!stateAck.ok || !stateAck.state) throw new Error('state sync failed')

      const cachedRoomId = recoveryRoomId
      if (stateAck.state.room) {
        recoveryRoomId = null
      } else if (cachedRoomId) {
        const roomAck = await get().syncRoom(cachedRoomId)
        recoveryRoomId = null
        if (
          !roomAck.ok &&
          roomAck.error !== 'ROOM_NOT_FOUND' &&
          roomAck.error !== 'NOT_A_ROOM_MEMBER'
        ) {
          throw new Error(`room recovery failed: ${roomAck.error ?? 'unknown error'}`)
        }
      }

      if (get().readOnly) return
      reconnectScheduled = false
      backoff.reset()
      set({ connection: 'connected', reconnectAttempt: 0, lastError: null })
      arbiter?.announceConnected()
    } catch (error) {
      set({ lastError: error instanceof Error ? error.message : 'connection recovery failed' })
      scheduleReconnect()
    }
  }

  // ----- command plumbing -----

  function sendCommand(destination: string, payload: unknown, requestId = newRequestId()): Promise<CommandAck> {
    return new Promise((resolve, reject) => {
      const state = get()
      if (state.readOnly) {
        reject(new Error('tab is read-only'))
        return
      }
      if (mockMode) {
        reject(new Error('mock mode: commands disabled'))
        return
      }
      const timer = setTimeout(() => {
        pendingRequests.delete(requestId)
        reject(new Error(`ack timeout for ${destination}`))
      }, ACK_TIMEOUT_MS)
      pendingRequests.set(requestId, { resolve, reject, timer })
      const sent = ensureClient().publish(destination, { requestId, payload })
      if (!sent) {
        clearTimeout(timer)
        pendingRequests.delete(requestId)
        reject(new Error('not connected'))
      }
    })
  }

  function requireRoomId(): string | null {
    return get().room?.roomId ?? null
  }

  return {
    rankedQueue: null,
    joinRanked: () => sendCommand('/app/ranked.join', {}),
    cancelRanked: () => sendCommand('/app/ranked.cancel', {}),
    connection: 'connecting',
    reconnectAttempt: 0,
    readOnly: false,
    lastError: null,
    lobby: null,
    membershipEpoch: 0,
    room: null,
    game: null,
    yourSeat: null,
    myHoleCards: null,
    autoSeatRoomId: null,
    chatMessages: [],
    clockTick: 0,
    pendingAction: null,
    toasts: [],
    roomClosed: false,
    roomGone: false,
    takenOver: false,

    init() {
      if (initialized) return
      initialized = true
      arbiter = createTabArbiter({
        onDemoted: () => {
          intentionalClose = true
          backoff.cancel()
          reconnectScheduled = false
          rejectPendingRequests('tab became read-only before acknowledgement')
          void client?.disconnect()
          set({ readOnly: true, takenOver: true, connection: 'offline', pendingAction: null })
        },
        onReclaim: () => {
          get().claimThisTab()
        },
      })
      intentionalClose = false
      set({ connection: 'connecting' })
      void ensureClient().connect()
    },

    manualRetry() {
      backoff.reset()
      reconnectScheduled = false
      intentionalClose = false
      set({ connection: 'reconnecting', reconnectAttempt: 1 })
      void ensureClient().connect()
    },

    claimThisTab() {
      arbiter?.requestTakeover()
      backoff.reset()
      reconnectScheduled = false
      intentionalClose = false
      set({ readOnly: false, takenOver: false, connection: 'reconnecting', reconnectAttempt: 1 })
      void ensureClient().connect()
    },

    stateSync() {
      return sendCommand('/app/state.sync', {})
    },
    async createRoom(name: string, maxSeats: number, privateTable: boolean, settings?: TableSettings) {
      const ack = await sendCommand('/app/room.create', { name, maxSeats, privateTable, ...settings })
      const room = get().room
      if (
        ack.ok &&
        ack.roomId &&
        room?.roomId === ack.roomId &&
        !room.viewer.seated &&
        room.viewer.canTakeSeat
      ) {
        set({ autoSeatRoomId: ack.roomId })
      }
      return ack
    },
    async joinRoom(joinCode: string) {
      const ack = await sendCommand('/app/room.join', { joinCode })
      const room = get().room
      if (
        ack.ok &&
        ack.roomId &&
        room?.roomId === ack.roomId &&
        !room.viewer.seated &&
        room.viewer.canTakeSeat
      ) {
        set({ autoSeatRoomId: ack.roomId })
      }
      return ack
    },
    syncRoom(roomId: string) {
      return sendCommand('/app/room.sync', { roomId })
    },
    keepRoomAlive() {
      const roomId = requireRoomId()
      if (!roomId) return Promise.reject(new Error('not in a room'))
      return sendCommand('/app/room.keep-alive', { roomId })
    },
    updateRoomSettings(settings: TableSettings) {
      const roomId = requireRoomId()
      if (!roomId) return Promise.reject(new Error('not in a room'))
      return sendCommand('/app/room.update-settings', { roomId, ...settings })
    },
    async leaveRoom() {
      const roomId = requireRoomId()
      if (!roomId) return null
      leavingRoomId = roomId
      try {
        const ack = await sendCommand('/app/room.leave', { roomId })
        if (ack.ok) {
          recoveryRoomId = null
          clearChatExpiryTimers()
          set({ ...extractView(null), pendingAction: null, autoSeatRoomId: null, chatMessages: [] })
        }
        return ack
      } finally {
        // room-events notice may still arrive; leavingRoomId suppresses the modal
        setTimeout(() => {
          if (leavingRoomId === roomId) leavingRoomId = null
        }, 5000)
      }
    },
    async takeSeat(seat: number) {
      const roomId = requireRoomId()
      if (!roomId) return Promise.reject(new Error('not in a room'))
      const config = get().room?.config
      const buyIn = config?.startingStack ?? config?.minBuyIn
      if (!buyIn) return Promise.reject(new Error('room buy-in configuration is unavailable'))
      const ack = await sendCommand('/app/room.take-seat', { roomId, seat, buyIn })
      if (ack.ok) set({ autoSeatRoomId: null })
      return ack
    },
    stand() {
      const roomId = requireRoomId()
      if (!roomId) return Promise.reject(new Error('not in a room'))
      return sendCommand('/app/room.stand', { roomId })
    },

    sendChatMessage(message: string) {
      const roomId = requireRoomId()
      const text = message.trim()
      if (!roomId || !text) return Promise.resolve(null)
      if (text.length > 50) return Promise.reject(new Error('chat message is too long'))
      return sendCommand('/app/room.chat', { roomId, message: text })
    },

    act(action: PlayerActionType, amount?: number) {
      const state = get()
      const roomId = requireRoomId()
      const game = state.game
      const legal = game?.viewer.legalActions
      if (!roomId || !game || !legal || !game.viewer.canAct || state.pendingAction || state.readOnly) return
      const allowed =
        (action === 'fold' && legal.canFold) ||
        (action === 'check' && legal.canCheck) ||
        (action === 'call' && legal.canCall) ||
        (action === 'raiseTo' && legal.canRaise)
      if (!allowed) return
      let normalizedAmount = amount
      if (action === 'raiseTo') {
        if (amount === undefined || !Number.isFinite(amount)) return
        normalizedAmount = Math.round(Math.min(legal.maxRaiseTo, Math.max(legal.minRaiseTo, amount)))
      }
      const requestId = newRequestId()
      const actionSeq = game.actionSeq
      set({
        pendingAction: { type: action, amount: normalizedAmount, actionSeq, requestId, sentAt: clockNow() },
      })
      if (mockMode) return
      sendCommand(GAME_ACT_DESTINATION, { roomId, action, amount: normalizedAmount, actionSeq }, requestId)
        .then((ack) => {
          if (get().pendingAction?.actionSeq === actionSeq) set({ pendingAction: null })
          void ack
        })
        .catch(() => {
          if (get().pendingAction?.actionSeq === actionSeq) set({ pendingAction: null })
        })
    },

    rebuy(amount: number) {
      const roomId = requireRoomId()
      const viewer = get().room?.viewer
      const emergencyOffer = viewer?.rebuyOfferDeadline != null
        && clockNow() < viewer.rebuyOfferDeadline
      if (
        !roomId ||
        get().pendingAction ||
        viewer?.rebuyAmount == null ||
        (viewer.rebuyHandsRemaining !== 0 && !emergencyOffer) ||
        !Number.isFinite(amount) ||
        Math.round(amount) !== viewer.rebuyAmount
      ) return
      const normalizedAmount = viewer.rebuyAmount
      const requestId = newRequestId()
      set({ pendingAction: { type: 'rebuy', amount: normalizedAmount, requestId, sentAt: clockNow() } })
      if (mockMode) return
      sendCommand(GAME_REBUY_DESTINATION, { roomId, amount: normalizedAmount }, requestId)
        .then((ack) => {
          set({ pendingAction: null })
          const room = get().room
          if (
            ack.ok &&
            room?.roomId === roomId &&
            !room.viewer.seated &&
            room.viewer.canTakeSeat
          ) {
            set({ autoSeatRoomId: roomId })
          }
        })
        .catch(() => set({ pendingAction: null }))
    },

    sitOut() {
      const roomId = requireRoomId()
      if (!roomId || mockMode) return Promise.resolve(null)
      return sendCommand(GAME_SIT_OUT_DESTINATION, { roomId })
    },

    sitBack() {
      const roomId = requireRoomId()
      if (!roomId || mockMode) return
      sendCommand(GAME_SIT_BACK_DESTINATION, { roomId }).catch(() => {})
    },

    pushToast,
    dismissToast(id: number) {
      set({ toasts: get().toasts.filter((t) => t.id !== id) })
    },
    dismissAutoSeat() {
      set({ autoSeatRoomId: null })
    },

    acknowledgeRoomClosed() {
      set({ roomClosed: false })
    },
    acknowledgeRoomGone() {
      clearChatExpiryTimers()
      set({
        roomGone: false,
        room: null,
        game: null,
        yourSeat: null,
        myHoleCards: null,
        pendingAction: null,
        autoSeatRoomId: null,
        chatMessages: [],
      })
      void get().stateSync().catch(() => {})
    },

    __applyRoomSnapshot: applyRoomSnapshot,
    __applyStateSync: applyStateSync,
    __handleAck: handleAck,
    __handleRoomEvent: handleRoomEvent,
    __handleChat: handleChat,
    __setMockMode(on: boolean) {
      mockMode = on
      if (on) {
        intentionalClose = true
        backoff.cancel()
        void client?.disconnect()
        set({ connection: 'connected' })
      }
    },
  }
})

// test hook: lets vitest control time
export function __setClock(now: () => number) {
  clockNow = now
}
