// Wire DTOs for the T5 STOMP protocol (mirrors server/src/main/java/com/poker/ws/protocol)
// plus the implemented T6 game contract exposed by GameView.
// Instants arrive as ISO-8601 strings; UUIDs as strings. Unknown extra fields are tolerated.

// ---------- T5 room/lobby protocol ----------

export type CommandType =
  | 'RANKED_JOIN'
  | 'RANKED_CANCEL'
  | 'STATE_SYNC'
  | 'ROOM_CREATE'
  | 'ROOM_JOIN'
  | 'ROOM_LEAVE'
  | 'ROOM_SYNC'
  | 'ROOM_KEEP_ALIVE'
  | 'ROOM_UPDATE_SETTINGS'
  | 'ROOM_TAKE_SEAT'
  | 'ROOM_STAND'
  | 'CHAT_SEND'
  // T6 gameplay command types
  | 'GAME_ACT'
  | 'GAME_REBUY'
  | 'GAME_SIT_OUT'
  | 'GAME_SIT_IN'

export type ErrorCode =
  | 'RANKED_UNAVAILABLE'
  | 'INVALID_REQUEST'
  | 'UNAUTHENTICATED'
  | 'ROOM_NOT_FOUND'
  | 'ROOM_FULL'
  | 'ALREADY_IN_ANOTHER_ROOM'
  | 'NOT_A_ROOM_MEMBER'
  | 'SEAT_OUT_OF_RANGE'
  | 'SEAT_OCCUPIED'
  | 'ALREADY_SEATED'
  | 'NOT_SEATED'
  | 'JOIN_CODE_EXHAUSTED'
  | 'INTERNAL_ERROR'
  // T6 gameplay error codes
  | 'NOT_YOUR_TURN'
  | 'STALE_ACTION'
  | 'INVALID_AMOUNT'
  | 'HAND_NOT_ACTIVE'
  | 'HAND_IN_PROGRESS'
  | 'ACTION_NOT_LEGAL'
  | 'INVALID_RAISE_TO'
  | 'INVALID_BUY_IN'
  | 'NOT_ROOM_ADMIN'
  | 'NOT_A_MODERATOR'
  | 'SETTINGS_NOT_EDITABLE'
  | 'REBUY_NOT_AVAILABLE'
  | 'ALREADY_SITTING_IN'
  | 'ALREADY_SITTING_OUT'
  | 'RATE_LIMITED'

export type RoomMemberView = {
  userId: string
  displayName: string
  avatarUrl: string | null
  joinedAt: string
  seat: number | null
  connected: boolean
  disconnectedSince: string | null
  admin: boolean
}

export type SeatView = { seat: number; userId: string | null }

export type ViewerRoomState = {
  userId: string
  member: RoomMemberView | null
  seated: boolean
  seat: number | null
  canTakeSeat: boolean
  canStand: boolean
  busted: boolean
  rebuyAmount: number | null
  rebuyHandsRemaining: number | null
  /** Five-second emergency offer deadline when only one funded player remains seated. */
  rebuyOfferDeadline: number | null
}

export type RoomConfig = {
  smallBlind: number
  bigBlind: number
  minBuyIn: number
  maxBuyIn: number
  actionTimeoutSeconds: number
  startingStack: number
  rebuysEnabled: boolean
  rebuyStack: number
  rebuyDelayHands: number
}

export type TableSettings = Pick<
  RoomConfig,
  'bigBlind' | 'actionTimeoutSeconds' | 'startingStack' | 'rebuysEnabled' | 'rebuyStack' | 'rebuyDelayHands'
>

export type RoomSnapshot = {
  mode?: 'CASUAL' | 'RANKED'
  rankedStatus?: 'SETTLING' | 'CONSOLIDATING' | 'WAITING_FOR_PLAYERS' | 'PLAYING' | null
  roomId: string
  joinCode: string
  name: string
  maxSeats: number
  privateTable: boolean
  canEditSettings: boolean
  membershipEpoch: number
  revision: number
  serverNow: string
  idleExpiresAt: string
  members: RoomMemberView[]
  seats: SeatView[]
  config: RoomConfig
  viewer: ViewerRoomState
  game: Game | null
}

export type LobbyRoomView = {
  mode?: 'CASUAL' | 'RANKED'
  rankedStatus?: 'SETTLING' | 'CONSOLIDATING' | 'WAITING_FOR_PLAYERS' | 'PLAYING' | null
  roomId: string
  joinCode: string
  name: string
  maxSeats: number
  memberCount: number
  seatedCount: number
  connectedMemberCount: number
  createdAt: string
}

export type LobbySnapshot = {
  revision: number
  serverNow: string
  rooms: LobbyRoomView[]
}

export type RankedQueueState = {
  enabled: boolean
  revision: number
  status: 'IDLE' | 'QUEUED' | 'ASSIGNED'
  position: number
  waitingPlayers: number
  roomId: string | null
}

export type StateSyncView = {
  rankedQueue?: RankedQueueState | null
  lobby: LobbySnapshot
  membershipEpoch: number
  room: RoomSnapshot | null
}

export type CommandAck = {
  requestId: string
  command: CommandType
  ok: boolean
  error: ErrorCode | null
  roomId: string | null
  roomRevision: number | null
  lobbyRevision: number | null
  state: StateSyncView | null
  actionSeq?: number | null
  retryAfterMs?: number | null
}

export type RoomExitNotice = {
  roomId: string
  reason: 'LEFT' | 'IDLE_TIMEOUT' | 'DISCONNECTED_TIMEOUT'
  membershipEpoch: number
  serverNow: string
}

export type ChatMessage = {
  id: string
  roomId: string
  senderId: string
  senderName: string
  message: string
  sentAt: string
}

// ---------- T6 per-viewer game contract ----------

export type GamePhase = 'PREFLOP' | 'FLOP' | 'TURN' | 'RIVER' | 'COMPLETE'

export type SeatStatus = 'ACTIVE' | 'ALL_IN' | 'FOLDED' | 'SITTING_OUT' | 'BUSTED'

export type LastAction = {
  action: 'FOLD' | 'CHECK' | 'CALL' | 'RAISE_TO'
  amount: number | null
}

export type GameSeat = {
  seat: number
  userId: string
  stack: number
  seatStatus: SeatStatus
  /** Chips committed to the current betting round (drives the bet chips on the felt). */
  committed: number
  /** Viewer-redacted: your own cards, or winners' at showdown; null/absent otherwise. */
  holeCards: string[] | null
  lastAction: LastAction | null
  /** Ordered actions made by this player during the currently displayed betting round. */
  actions: LastAction[]
  /** Standard poker position for this hand; null when the seat is not in the hand. */
  position: 'BN/SB' | 'BN' | 'SB' | 'BB' | 'UTG' | 'UTG+1' | 'MP' | 'LJ' | 'HJ' | 'CO' | null
  finalHandRank: string | null
  /** Authoritative five cards used by this seat's awarded showdown hand. */
  bestHandCards?: string[]
  /** Completed hands still required before this busted player may rebuy. */
  rebuyHandsRemaining: number
  /** True once at least one accepted rebuy has been applied to this seat. */
  rebought: boolean
  /** Number of accepted rebuys applied to this seat during the table session. */
  rebuyCount: number
  /** endingStack - startingStack for the presented hand; null unless that hand is complete. */
  netChange: number | null
}

export type LegalActions = {
  canFold: boolean
  canCheck: boolean
  canCall: boolean
  callAmount: number
  toCall: number
  canRaise: boolean
  minRaiseTo: number
  maxRaiseTo: number
}

export type Game = {
  tableStatus: 'WAITING' | 'RUNNING' | 'PAUSED' | 'SETTLING' | 'FAILED'
  handId: number
  phase: GamePhase | null
  completionReason: 'FOLD_WIN' | 'SHOWDOWN' | null
  board: string[]
  buttonSeat: number | null
  smallBlindSeat: number | null
  bigBlindSeat: number | null
  actionSeq: number
  actingSeat: number | null
  /** Absolute server timestamp (millis since epoch) for the acting player's deadline. */
  deadline: number | null
  minRaiseTo: number
  maxRaiseTo: number
  toCall: number
  currentBet: number
  /** All chips currently wagered, including a temporarily unmatched bet. */
  potTotal: number
  pots: { amount: number; eligibleSeats: number[] }[]
  awards: { potIndex: number; potAmount: number; winners: { seat: number; amount: number }[] }[]
  seats: GameSeat[]
  viewer: {
    seated: boolean
    seat: number | null
    canAct: boolean
    /** The seat's original buy-in; the only accepted rebuy amount. */
    rebuyAmount: number | null
    rebuyHandsRemaining: number | null
    legalActions: LegalActions | null
  }
}

// ---------- On-demand hand-history API ----------

export type HandHistoryPlayer = {
  seat: number
  userId: string
  displayName: string
  startingStack: number
  endingStack: number
  netChange: number
  won: number
  folded: boolean
  holeCards: string[] | null
  finalHandRank: string | null
}

export type HandHistoryEvent = {
  sequence: number
  occurredAt: string
  phase: GamePhase
  type:
    | 'BLIND_POSTED'
    | 'PLAYER_ACTED'
    | 'UNCALLED_BET_REFUNDED'
    | 'STREET_DEALT'
    | 'POT_AWARDED'
    | 'HAND_COMPLETED'
  seat: number | null
  action: string | null
  amount: number | null
  total: number | null
  stackAfter: number | null
  cards: string[]
  potIndex: number | null
  potAmount: number | null
  winners: { seat: number; amount: number }[]
}

export type HandHistory = {
  handId: number
  startedAt: string
  completedAt: string | null
  phase: GamePhase
  completionReason: 'FOLD_WIN' | 'SHOWDOWN' | null
  buttonSeat: number
  smallBlindSeat: number
  bigBlindSeat: number
  smallBlind: number
  bigBlind: number
  board: string[]
  players: HandHistoryPlayer[]
  events: HandHistoryEvent[]
}

export type HandHistoryResponse = { roomId: string; hands: HandHistory[] }

// ---------- Client-side helper shapes ----------

export type PlayerActionType = 'fold' | 'check' | 'call' | 'raiseTo'

export type PendingAction = {
  type: PlayerActionType | 'rebuy' | 'sitBack' | 'sitOut'
  amount?: number
  actionSeq?: number
  requestId: string
  sentAt: number
}

export type ConnectionState = 'connecting' | 'connected' | 'reconnecting' | 'offline'

export type SnapshotStamp = { epoch: number; revision: number }

/** Membership epoch first, then room revision within the epoch (T5 acceptance rule). */
export function isSnapshotNewer(current: SnapshotStamp | null, incoming: SnapshotStamp): boolean {
  if (!current) return true
  if (incoming.epoch > current.epoch) return true
  return incoming.epoch === current.epoch && incoming.revision > current.revision
}
