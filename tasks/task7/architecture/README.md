# T7 Code Architecture — Gameplay Wiring (Client)

## Goal

Implement the client-side realtime gameplay layer. T7 provides:

- a zustand store backed by STOMP that consumes per-viewer room snapshots and
  maintains authoritative game state;
- a server-clock offset tracking algorithm (EMA) so the countdown clock drives
  from the server's notion of `now`, not the browser's variable refresh rate;
- automatic action-sequence attachment to every act() call so concurrent snapshots
  cannot apply stale actions to a newer game round;
- a functional Room view with deterministic seat positioning via superellipse
  projection, board/pot display, hole-card visibility rules, and per-seat tags
  (dealer, all-in, sitting out, offline, busted);
- an action-area state machine that covers your turn (fold/check-or-call/raise
  controls clamped to `[minRaiseTo, maxRaiseTo]`), waiting, showdown with winner
  reveal, busted-with-rebuy, and sitting-out states; and
- countdown timers ticking from server deadline toward zero, with reconnection
  controls locked during transport loss.

T7 is transport and view plumbing only. It consumes game state from T6 per the
wiring contract below, implements no business logic, and exposes no cards except
your own hole cards and all non-folded players' cards at showdown. T8 will layer
animations and visual polish on this same information architecture without
changing data flow.

## Scope boundary

T7 owns the zustand store shape, STOMP subscription/publication lifecycle,
server-clock offset tracking, snapshot acceptance rules, room view layout,
seat-pod rendering, action-area state transitions, and deadline-countdown
display.

T7 does not own juice animation timing or easing, the WebGL swirl background,
or CRT overlays (T8); reconnect overlays, toasts, exponential-backoff/offline
state machine, or multi-tab UX (T10); rate limiting or abuse policy (T9); or
any server-side game logic (T6). T7 implements the `table-green.html` static
skin (felt, pods, cards, action bar, timer ring) over a plain
`--swirl-1/--swirl-2` gradient background; T8 replaces that gradient with the
shader and layers the remaining juice on the same DOM.

The Room view structure—table element, seats array, action area, board, pot—
is fixed from `tasks/task7/design/mockup.html` and will remain unchanged when
T8 applies the visual skin.

## Dependency contract (T6)

T6 owns the server-side game aggregate and publishes game state inside each
`RoomSnapshot.game` field. T7 assumes T6 will provide the following DTO shape
(viewer-redacted and serialized through the T5 allow-list mapper):

```typescript
// Per-viewer game state, sent inside RoomSnapshot.game
type Game = {
  tableStatus: 'WAITING' | 'RUNNING' | 'PAUSED' | 'FAILED'
  handId: number
  phase: null | 'PREFLOP' | 'FLOP' | 'TURN' | 'RIVER' | 'SHOWDOWN' | 'COMPLETE'
  completionReason: null | 'FOLD_WIN' | 'SHOWDOWN'
  board: string[]  // e.g., ['Kh', '9c', '2d'] for flop; missing or null for empty slots
  buttonSeat: number | null
  smallBlindSeat: number | null
  bigBlindSeat: number | null

  // Action round tracking
  actionSeq: number  // increments on each valid action; stales action attempts
  actingSeat: number | null
  deadline: number | null  // absolute server timestamp; null unless RUNNING with an actor

  // Amount info
  currentBet: number
  pots: Array<{ amount: number; eligibleSeats: number[] }>

  // Per-seat state (own cards before showdown; all non-folded cards at showdown)
  seats: Array<{
    seat: number
    userId: string
    stack: number  // current remaining stack
    seatStatus: 'ACTIVE' | 'ALL_IN' | 'FOLDED' | 'SITTING_OUT' | 'BUSTED'

    // Viewer-redacted hole cards: own only, or null for others unless showdown
    holeCards: string[] | null

    // Last action taken this hand
    lastAction?: {
      action: 'FOLD' | 'CHECK' | 'CALL' | 'RAISE_TO'
      amount?: number
    }

    // Showdown only: final hand strength (optional, for display ranking)
    finalHandRank?: string  // e.g., 'Two pair, Kings and Nines'
  }>

  // Ordered, multi-pot/tie-safe awards accumulated in the presented frame
  awards: Array<{
    potIndex: number
    potAmount: number
    winners: Array<{ seat: number; amount: number }>
  }>

  viewer: {
    seated: boolean
    seat: number | null
    canAct: boolean
    rebuyMinimum: number | null
    rebuyMaximum: number | null
    legalActions: null | {
      canFold: boolean
      canCheck: boolean
      canCall: boolean
      callAmount: number
      toCall: number
      canRaise: boolean
      minRaiseTo: number
      maxRaiseTo: number
    }
  }
}
```

This is the finalized T6 contract. T7 derives winner banners from ordered
awards and supports ties and multiple side pots; it never assumes one winning
seat or reconstructs best-five cards. Action controls render only from
`viewer.legalActions`, so the client does not recreate server poker rules.
Unknown additional response fields remain forward-compatible.

## File/module layout

Client file tree with one-line purpose:

```
client/src/
  store/
    gameStore.ts              zustand store, STOMP lifecycle, snapshot acceptance
    types.ts                  game DTO + wire types, snapshot envelope shape
  lib/
    stompClient.ts            STOMP connection, auth flow, subscription manager
    clockOffsetEMA.ts         serverNow EMA: delta tracking and smoothed offset
  pages/
    Room.tsx                  route handler, store subscription, layout wrapper
  components/
    Table.tsx                 superellipse seat layout, board, pot display
    SeatPod.tsx               individual seat with player name, stack, badges
    ActionArea.tsx            state machine: turn / wait / showdown / busted / sit-out
    Countdown.tsx             deadline tick-down via store serverNow + local timer
```

## Store design (zustand)

The game store holds:

```typescript
type GameStoreState = {
  // STOMP connection state (T10 reads this to drive its banner/offline UX)
  connection: 'connecting' | 'connected' | 'reconnecting' | 'offline'
  lastError?: string

  // Snapshots and membership (from T5)
  membership: {
    epoch: number
    roomId: string | null
  }
  snapshot: RoomSnapshot | null  // full T5 snapshot
  serverNow: number  // EMA-smoothed server clock offset (milliseconds)

  // Game view data
  game: Game | null
  yourSeat: number | null  // viewer's seat number, or null if not seated
  myHoleCards: string[] | null  // visible only to you

  // Current action round tracking
  currentActionSeq: number  // echoed on act() calls
  pendingAction: {
    type: 'fold' | 'check' | 'call' | 'raise'
    amount?: number
    actionSeq: number
    requestId: string
  } | null

  // Methods
  init: (wsUrl: string) => Promise<void>
  act: (action: 'fold' | 'check' | 'call' | 'raise', amount?: number) => void
  rebuy: (amount: number) => void
  sitBack: () => void
  standUp: () => void
}
```

### STOMP lifecycle

1. On Room component mount, call `store.init(wsUrl)`:
   - Fetch CSRF token from `GET /api/csrf`
   - Connect to `/ws` with CSRF in STOMP CONNECT header
   - Enable 10-second incoming/outgoing STOMP heartbeats required by T9's
     half-open connection detection
   - Subscribe to `/topic/lobby`, `/user/queue/acks`, `/user/queue/room`,
     `/user/queue/room-events`
   - Send `state.sync` to bootstrap membership epoch and current room snapshot

2. On snapshot arrival (`/user/queue/room`):
   - Check membership epoch; if older, discard
   - Check room revision within epoch; ignore if <= current revision (unless equal,
     which is a resync duplicate and replaces)
   - Store snapshot and extract `game`, your seat number, and your hole cards
   - Tick serverNow EMA with new `serverNow` from snapshot

3. On ack arrival (`/user/queue/acks`):
   - Correlate by `requestId`; set `pendingAction` and lock controls until
     resolved or timeout

4. On `RoomExitNotice` (`/user/queue/room-events`):
   - Verify epoch >= current membership epoch before clearing
   - Return to lobby and clear room state

5. On disconnect:
   - Set connection state to 'reconnecting'
   - Keep room state; do NOT clear on transport loss
   - T7 ships a simple fixed retry (@stomp/stompjs `reconnectDelay`, 2s),
     refetching `/api/csrf` before each CONNECT attempt; the exponential
     backoff schedule, 'offline' terminal state, and manual-retry UX replace
     this in T10 via the same `connection` field and `subscribe()`/`syncState()`
     entry points
   - On reconnect, resubscribe and call `state.sync` without losing seat/game

### Clock offset EMA

Server sends `serverNow: number` in every snapshot (milliseconds since Unix
epoch). Client tracks elapsed wall time and smooths the offset to prevent jitter
from network latency:

```typescript
interface ClockOffsetEMA {
  lastServerNow: number
  clientTimestamp: number  // when we received the snapshot
  offsetMS: number  // server - client at that moment
  smoothedOffsetMS: number
  alpha: number  // EMA weight; use 0.1 for gentle smoothing
}

export function updateEMA(
  ema: ClockOffsetEMA,
  newServerNow: number,
  now: number
): number {
  const clientNow = now + ema.smoothedOffsetMS
  const delta = newServerNow - clientNow
  ema.smoothedOffsetMS += ema.alpha * delta
  return ema.smoothedOffsetMS
}

export function getServerNow(ema: ClockOffsetEMA, now: number): number {
  return now + ema.smoothedOffsetMS
}
```

The countdown display uses `serverNow()` and compares to the action `deadline`:
`timeRemaining = Math.max(0, (deadline - serverNow()) / 1000)` to show
countdown in seconds.

### action() auto-attaching actionSeq

Every act() call:
1. Reads the current `currentActionSeq` from the game state
2. Sends the action with `{action, amount?, actionSeq, requestId}`
3. Sets `pendingAction` to lock the UI
4. On ack with ok: true, clears `pendingAction`
5. On ack with ok: false or stale actionSeq (ack error), shows error and clears
6. If snapshot arrives with newer actionSeq, auto-clears old `pendingAction`

This prevents the race: snapshot with actionSeq: 5 arrives, you click fold
(actionSeq: 5), but by the time your fold reaches the server, actionSeq is now
6 (someone else acted). The server rejects actionSeq: 5, your UI clears the
pending state, and you see the new prompt.

### Snapshot acceptance rule (from T5)

Membership epoch, then room revision establishes freshness:

```typescript
function isSnapshotNewer(
  current: { epoch: number; revision: number } | null,
  incoming: { epoch: number; revision: number }
): boolean {
  if (!current) return true
  if (incoming.epoch > current.epoch) return true
  if (incoming.epoch === current.epoch && incoming.revision > current.revision) return true
  return false  // stale or resync duplicate
}
```

## Room view component breakdown

### Table component

Renders the superellipse-positioned seats and board/pot at center. Layout math
from `table-green.html`:

```typescript
// Seat i (0 = you, bottom-center) of N total:
const angle = (Math.PI / 2) + (i * 2 * Math.PI / N)
const cos = Math.cos(angle)
const sin = Math.sin(angle)
const K = 0.72  // superellipse power
const seatX = 50 + 46 * Math.sign(cos) * Math.pow(Math.abs(cos), K)
const seatY = 50 + 56 * Math.sign(sin) * Math.pow(Math.abs(sin), K)
// Bet chips at radii 30/36 (inner curve):
const betX = 50 + 30 * Math.sign(cos) * Math.pow(Math.abs(cos), K)
const betY = 50 + 36 * Math.sign(sin) * Math.pow(Math.abs(sin), K)
```

Render board (5 card slots; populated left-to-right as street advances) and pot
at center with street label (PREFLOP, FLOP, TURN, RIVER, SHOWDOWN).

### SeatPod component

Per-seat display (state machine input):

| State | Rendering |
|-------|-----------|
| Empty (no member) | Dashed border, faded |
| You (unseated) | Blue border, your seat indicator |
| You (seated, awaiting action) | Blue border + orange action pulse + countdown ring |
| You (seated, other's turn) | Blue border, your cards face-up |
| Opponent (active) | Panel border, last action label, face-down cards back |
| Opponent (folded) | Desaturated, last action, no cards shown |
| Opponent (all-in) | Red "All-in" badge, stack shows 0 |
| Sitting out | Grey "Sitting out" badge, desaturated |
| Busted | Desaturated, grey "Busted" badge |
| Offline | Grey "offline" badge (T10 concern; show if available) |
| Showdown | Gold outline, reveal face-up cards, show `finalHandRank` |

Countdown ring at top-center during actor's turn: conic-gradient orange (0–100%
of time remaining), red/tremble when ≤6s.

### ActionArea state machine

Current state of the actor view:

#### Your turn
- Heading: "Your turn — act within `HH:MM`" (countdown)
- Buttons: Fold (red), Call `{toCall}` (gray), Raise (primary blue)
- Raise slider: `min` / `½pot` / `pot` / `all-in` presets; range `[minRaiseTo, maxRaiseTo]`
- Controls are disabled if `pendingAction !== null`

#### Waiting
- Heading: "Action"
- Text: "Waiting for {actor name} to act…"
- Buttons: all disabled
- Optional: "You already {lastAction} — pre-actions (check/fold) live here later"

#### Showdown
- Heading: "Showdown"
- Render each ordered pot award and all tied winners, with each revealed
  player's `finalHandRank`; do not collapse side pots into one winner.
- Button: "Sit out next hand"

#### Busted
- Heading: "You busted out"
- Rebuy slider: range `[minRebuy, maxRebuy]` with amount display
- Buttons: Rebuy `{amount}` (primary), Stand up (danger)

#### Sitting out
- Heading: "Sitting out"
- Text: "You are sitting out — dealt out of hands."
- Buttons: "I'm back (sit in)" (primary), Stand up (danger)

#### Reconnecting (connection lost)
- Heading: "Action"
- Text: "Connection lost — reconnecting and re-syncing… controls are locked until
  the snapshot returns."
- All buttons disabled

## Failure behavior

- **Stale actionSeq**: Server rejects with ack error. UI clears `pendingAction`.
  Next snapshot resets to correct actionSeq.
- **Ack errors** (INVALID_REQUEST, UNAUTHENTICATED, etc.): Error logged, `pendingAction`
  cleared, action not retried. User sees locked controls until next snapshot.
- **Out-of-order snapshots**: Membership epoch filtering + room revision
  within-epoch rule ensure only forward progress. Older revisions silently
  ignored; resync duplicates replace state without animation.
- **Transport disconnect**: Do not mutate room membership or seat. Reconnect and
  re-sync without clearing game state. (Formal reconnect overlays live in T10.)
- **Malformed snapshot** (game field missing, invalid card codes): Log error,
  render "Connection error — please refresh" placeholder. Avoid crashing.

## Implementation sequence

1. **Setup** (files, types, store skeleton)
   - Create `client/src/store/types.ts` with Game, RoomSnapshot, and wire DTOs.
   - Create `client/src/store/gameStore.ts` stub with init/act/rebuy/sitBack
     signatures.
   - Create `client/src/lib/clockOffsetEMA.ts` with updateEMA and getServerNow.

2. **STOMP plumbing**
   - Implement `stompClient.ts`: connect, subscribe, CSRF fetch, reconnect loop.
   - Implement store init(): call stompClient.connect(), subscribe to all four
     destinations, send state.sync.
   - Handle snapshot/ack/notice messages and update store state.

3. **Snapshot acceptance and reducer logic**
   - Implement isSnapshotNewer() predicate and snapshot reducer.
   - Test epoch/revision ordering with out-of-order snapshot replay.

4. **Table and layout**
   - Implement superellipse math in Table component.
   - Build seat grid rendering with superellipse positioning (use CSS `left` and
     `top` percentages).
   - Render board (5 card slots) and pot at center.

5. **SeatPod rendering**
   - Implement per-seat state display: name, stack, last action, badges.
   - Hole-card visibility rules: own cards face-up, others face-down or showdown
     reveal.
   - Dealer badge, all-in/sitting-out/busted tags.

6. **Countdown display**
   - Add Countdown component that ticks from deadline via serverNow() EMA.
   - Refresh every 100ms; show HH:MM:SS format.

7. **ActionArea state machine**
   - Implement state transition logic based on game.phase, your seat, actor seat.
   - Your turn → fold/call/raise controls + slider presets + clamping.
   - Waiting → disabled buttons + "waiting for X" text.
   - Showdown → winner banner + next-hand sit-out button.
   - Busted → rebuy slider + buttons.
   - Sitting out → sit-back + stand buttons.
   - Reconnecting → all disabled + "reconnecting" text.

8. **Room page wiring**
   - Mount store.init() on Room component load.
   - Subscribe to store snapshots and pass game state to Table/ActionArea.
   - Show loading state during init.
   - Handle RoomExitNotice and navigate back to Lobby.

9. **Unit tests**
   - Test EMA calculations with synthetic server-now deltas.
   - Test snapshot ordering (epoch > rev > rev, etc.) with replay sequences.
   - Test action-seq stale rejection logic.
   - Test raise-to clamping with various potsize/stack combos.

10. **Manual two-window verification**
    - Open two browser windows, same room, different players.
    - Play a 2–3 hand sequence: action, all-in, showdown.
    - Verify: countdown ticks, action buttons lock on your turn, opponent actions
      display in your seat pod, showdown reveals both hand, next hand resets.
    - Kill one browser tab's connection and verify reconnect re-syncs without
      losing membership.

## Acceptance criteria

- STOMP connection, CSRF, subscriptions, and `state.sync` bootstrap work; logs
  contain no credentials or raw frames.
- Snapshots load with the correct membership epoch and room revision; out-of-order
  snapshots are rejected or resync-duplicated; equal revisions do not retrigger
  animations.
- Server clock offset is tracked via EMA; countdown display agrees with server
  deadline within ±2 seconds over 30 seconds.
- Action buttons and sliders appear only during your turn; raise-to is clamped
  to `[minRaiseTo, maxRaiseTo]` with presets (min/½pot/pot/all-in) reachable
  without the slider.
- act() calls include `actionSeq` from the current game state; stale actionSeq
  is rejected and the UI clears automatically.
- Hole-card visibility: only you see your cards; all players see opponent cards
  at showdown; folded/sitting-out players' cards remain hidden.
- Seat pods display name, stack, last action, and appropriate badges (dealer,
  all-in, sitting out, busted); actor seat pulses orange with countdown ring.
- Manual two-window hand play: action→all-in→showdown→next hand cycles with
  visible countdown, correct pot, and winner reveal.
- Reconnect after disconnect: re-syncs, restores game state, does not treat
  disconnect as room leave.
- Room view structure matches `mockup.html` and wears the approved
  `table-green.html` skin (stadium felt, seat pods, pixel cards, action bar,
  timer ring, CSS card bob) — excluding the WebGL swirl background, CRT
  scanline/grain overlays, and juice animations (chip fly, number pop, deal
  stagger), which land in T8.
