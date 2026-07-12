# T6 Code Architecture — Server Gameplay Wiring

## Goal

Attach the pure T3 `HoldemHand` engine to the T5 room aggregate and realtime
protocol without weakening either boundary. T6 adds table configuration, chip
stacks, hand lifecycle, guarded player actions, server-owned turn timers,
paced all-in/showdown presentation, sit-out/rebuy queues, and a single
allow-list redaction boundary for every per-viewer game snapshot.

The room remains the authoritative concurrency boundary. Every gameplay
mutation, timer callback, and presentation callback runs while holding the same
room lock already used for membership and seating. Steady-state gameplay never
holds the global registry lock while it holds or waits for a room lock, so one
busy table cannot serialize unrelated tables. The scheduler never owns game
state and outbound publication never runs while a lock is held.

## Scope and resolved contracts

T6 owns:

- gameplay configuration and initial buy-in validation;
- the room-confined `TableGame` lifecycle around `HoldemHand`;
- `game.act`, `game.sit-out`, `game.sit-in`, and `game.rebuy` commands;
- `actionSeq`, deadlines, stale-action rejection, and timeout behavior;
- hand-start, runout, result, and next-hand pacing;
- stack transfer between completed engine snapshots and persistent seat state;
- the only mapping path from trusted engine state to public game DTOs; and
- deterministic scheduler and two-client STOMP integration tests.

T6 does not implement client state, animations, rate limiting, idle-room
eviction, persistence of rooms/chips, or server-side multi-tab arbitration.
Those remain T7/T8, T9, and T10 concerns.

The following ambiguities in downstream draft plans are resolved here:

1. `HoldemHand` has no `SHOWDOWN` phase. The wire layer adds a presentation
   phase while the engine remains `COMPLETE`; `completionReason` distinguishes
   fold wins from showdown.
2. T5 has no separate spectator capacity. A spectator for T6 privacy tests is
   an authenticated room member who has not taken a seat; the existing
   `members <= maxSeats` cap remains unchanged.
3. Taking a seat now requires an initial `buyIn`. It is not silently chosen by
   the server.
4. `stand` during a hand is queued until the hand boundary. `leave` during a
   hand is rejected with `HAND_IN_PROGRESS`; this avoids ghost players and
   preserves T5 membership/seat invariants. Disconnect still is not leave.
5. A timeout checks when legal, otherwise folds, and queues that seat to sit
   out after the hand. A timeout is never submitted as a client command.

## Package and file layout

```text
server/src/main/java/com/poker/room/
  PokerRoom.java                    owns one TableGame under PokerRoom.lock
  RoomManager.java                  registry + locked gameplay mutations
  RoomConfig.java                   blinds, buy-in range, action timeout
  TableGame.java                    package-private lifecycle aggregate
  TableStatus.java                  WAITING/RUNNING/PAUSED/FAILED
  TableSeatState.java               stack and queued seat flags
  GameChange.java                   immutable post-commit publication data
  GameplayScheduleIntent.java       generation-stamped desired callback
  GameError.java                    transport-neutral gameplay rejections

server/src/main/java/com/poker/room/application/
  GameplayCommandService.java       mutate -> unlock -> publish -> schedule
  GameplayScheduler.java            outbound scheduler port
  ScheduledGameplayCoordinator.java task handles and guarded callbacks
  DeckFactory.java                  injected shuffled-deck source

server/src/main/java/com/poker/ws/
  GameplayMessageController.java    STOMP adapter for four game commands
  GameSnapshotRedactor.java         trusted state -> viewer allow-list DTO
  RoomSnapshotMapper.java           delegates game field only to redactor

server/src/main/java/com/poker/ws/protocol/
  ActRequest.java
  SitOutRequest.java
  SitInRequest.java
  RebuyRequest.java
  GameView.java
  GameSeatView.java
  LegalActionsView.java
  PotView.java
  AwardView.java

server/src/test/java/com/poker/room/
  TableGameTest.java
  GameplayConcurrencyTest.java
  GameplayTimerTest.java

server/src/test/java/com/poker/ws/
  GameSnapshotRedactorTest.java
  GameSnapshotSerializationTest.java
  GameplayStompIntegrationTest.java
```

`room` remains free of Spring, Jackson, messaging, servlet, and JPA types.
`TableGame` may depend on `com.poker.engine.game` and `engine.cards`; neither the
engine nor room packages may depend on `ws`. Architecture tests enforce these
directions.

## Configuration and seating contract

Extend `CreateRoomRequest` and the immutable room state with:

```json
{
  "name": "Friday game",
  "maxSeats": 6,
  "smallBlind": 5,
  "bigBlind": 10,
  "minBuyIn": 400,
  "maxBuyIn": 2000,
  "actionTimeoutSeconds": 30
}
```

Validation is explicit and overflow-safe:

- blinds are positive and `bigBlind >= smallBlind`;
- `minBuyIn >= 20 * bigBlind` and `maxBuyIn >= minBuyIn`;
- all chip amounts are positive signed `long` values and checked arithmetic is
  used when totals are formed;
- action timeout is 10–120 seconds; and
- existing room name and 2–9 seat constraints remain.

Extend `TakeSeatRequest` to `{ roomId, seat, buyIn }`. The buy-in must be in the
room range. An idempotent repeat for the same occupied seat succeeds only when
the requested buy-in equals the seat's already-established starting stack;
otherwise it returns `ALREADY_SEATED`. A member cannot add chips through
repeated take-seat calls.

Room and lobby snapshots expose public configuration. Chip stacks exist only
for occupied seats. There is no account wallet in T6: chips are table-scoped,
in-memory play money and disappear when a player safely stands or leaves.

## Aggregate model and invariants

`PokerRoom` gains one final `RoomConfig config` and one final `TableGame game`.
`TableGame` is package-private and can only be called while `PokerRoom.lock` is
held. It owns:

```text
Map<Integer, TableSeatState> seats
HoldemHand activeHand | null
HandSnapshot presentedSnapshot | null
TableStatus tableStatus
long handId
long actionSeq
long lifecycleSeq
long scheduleGeneration
Instant actionDeadline | null
Integer lastButtonSeat | null
PresentationStep pendingPresentation | null
```

Each `TableSeatState` stores `userId`, stack, participation status, and queued
flags (`sitOutAfterHand`, `standAfterHand`, `pendingRebuy`). Membership and seat
identity remain in `PokerRoom`; `TableGame` mirrors occupied seat identity and
asserts equality after every mutation.

T6 also stores each current member's membership epoch on `PokerRoom.Member`.
Create/join increments the existing registry epoch first and installs the same
value on the member while registry and room mutation are serialized; it is
immutable for that membership. Gameplay can therefore capture the complete
recipient/epoch map under only the room lock after revalidation. It does not use
an epoch list captured before a racing join. Leave still increments the global
epoch for the exiting user's notice after removing that member.

Additional invariants:

- an active engine player maps to exactly one still-occupied table seat;
- sum of seat stacks plus active-hand commitments is conserved within a hand;
- `actionDeadline` exists exactly when an actionable actor is publicly exposed
  and `tableStatus == RUNNING`; a paused/failed table exposes no legal actor;
- `handId` and `actionSeq` start at zero before the first hand and are
  non-negative, checked, and monotonic for the room lifetime;
- `lifecycleSeq` and `scheduleGeneration` are positive and monotonic for the
  room lifetime;
- no queued rebuy exists for a positive-stack seat;
- a sitting-out, busted, or pending-stand seat is excluded from a new hand;
- the button always names an eligible participant and advances clockwise among
  eligible seats; and
- the public presentation snapshot never advances ahead of its scheduled step.

Registry mutations still use the existing order `registryLock`, then
`PokerRoom.lock`. Steady-state gameplay uses a two-stage lookup:

1. briefly acquire `registryLock` and resolve the room reference;
2. release `registryLock`, acquire only `PokerRoom.lock`, then re-check
   `room.closed`, membership, and the expected generation before mutation.

Room deletion sets `room.closed=true` while holding registry then room lock
before removing indexes. A gameplay path that retained an old reference locks
it later, observes `closed`, and returns `ROOM_NOT_FOUND`. Because no room-only
path subsequently acquires the registry lock, there is no reverse-order cycle.
Join/leave racing gameplay linearizes at the room lock; the immutable per-member
epoch is captured with the resulting member set and cannot tear. Architecture
and concurrency tests enforce the epoch consistency, no-global-lock
steady-state rule, and deletion race.

## Command protocol

Add destinations to the T5 security allow-list and `CommandType`:

```text
/app/game.act       GAME_ACT
/app/game.sit-out   GAME_SIT_OUT
/app/game.sit-in    GAME_SIT_IN
/app/game.rebuy     GAME_REBUY
```

Requests remain strict concrete records inside `CommandEnvelope<T>`:

```json
{ "roomId": "uuid", "actionSeq": 42, "action": "RAISE_TO", "amount": 120 }
{ "roomId": "uuid" }
{ "roomId": "uuid" }
{ "roomId": "uuid", "amount": 800 }
```

`action` is one of `FOLD`, `CHECK`, `CALL`, `RAISE_TO`. `amount` must be null
except for `RAISE_TO`, where it must be positive. The server derives user ID,
seat, legal actions, and maximum stack from the authenticated principal and
room state.

Add stable errors:

```text
HAND_NOT_ACTIVE
HAND_IN_PROGRESS
NOT_SEATED
NOT_YOUR_TURN
STALE_ACTION
ACTION_NOT_LEGAL
INVALID_RAISE_TO
INVALID_BUY_IN
REBUY_NOT_AVAILABLE
ALREADY_SITTING_IN
ALREADY_SITTING_OUT
```

An accepted mutation returns its committed `roomRevision` and `actionSeq` in
the ack. Expected failures do not mutate state and use the normal session-local
ack. `STALE_ACTION` is checked before actor/action legality so a delayed click
cannot reveal whether a newer actor's action is legal.

## Mutation and publication flow

Every command and callback follows one template:

1. Resolve authenticated user and validate the concrete request.
2. Resolve the room/epochs briefly under the registry lock, release it, then
   acquire only the room lock and revalidate the open room/membership.
3. Verify membership and apply exactly one domain operation.
4. Increment `roomRevision` once for each externally visible committed frame.
5. Capture immutable room/game states, intended recipients, and any scheduling
   intent while locked.
6. Release all locks.
7. Publish per-viewer snapshots through `RoomEventSink`.
8. Reconcile the generation-stamped scheduler intent; a rejection commits a
   guarded pause through `RoomManager` and publishes that newer frame.
9. Return the command ack.

Publishing is at-least-attempted and best effort, as in T5. A failed enqueue
does not roll back a committed game action; reconnect or `room.sync` returns
the current presented state.

`GameplayScheduleIntent` contains `(roomId, scheduleGeneration, kind, dueAt,
guardTuple)`. `ScheduledGameplayCoordinator` stores at most one desired intent
per room/kind and applies compare-by-generation semantics: an older install,
cancel, retry, or rejection cannot replace a newer intent. Scheduler
reconciliation may race later commands without losing the latest timer. A
snapshot may briefly be published before its schedule attempt fails; the
guarded pause is a second externally visible revision that disables actions.

## Hand lifecycle

At every safe boundary, `TableGame` applies pending stand, sit-in/out, and rebuy
operations, then selects seats with positive stacks and `ACTIVE` status. With
at least two eligible seats it schedules a new hand after a configurable
2-second inter-hand delay; otherwise it remains waiting.

Every change that can invalidate a pending hand start increments
`lifecycleSeq`: take-seat, immediate or queued stand, leave, sit-in/out, rebuy
creation/replacement/application, boundary status application, table pause or
resume, and room close. `lifecycleSeq` uses checked increment; exhaustion moves
the table to `FAILED` rather than wrapping. The start intent carries the exact
sequence and never relies on task cancellation.

The guarded start callback captures `(roomId, expectedLifecycleSeq)`. Under the
room lock it no-ops if membership, eligibility, or lifecycle sequence changed.
Otherwise it:

1. advances the button clockwise among eligible seats;
2. creates a fresh shuffled `Deck` from injected `DeckFactory`;
3. calls `HoldemHand.start(HandConfig, players, button, deck)`;
4. increments `handId` and `actionSeq`;
5. stores the engine and initial presented snapshot;
6. establishes the first actor deadline; and
7. emits one new room revision and snapshot.

On `game.act`, the aggregate validates the supplied `actionSeq`, maps the wire
enum to `PlayerAction`, and invokes `HoldemHand.act`. Ordinary action results
publish their final transition snapshot once. If the engine produces multiple
automatic runout/showdown transitions, T6 stores immutable presentation steps
and exposes them one at a time; the completed engine state is never used by the
redactor until its step becomes current.

After a completed hand, engine stacks are copied into table seat state exactly
once. Zero-stack seats become `BUSTED`. The result frame remains visible for
3 seconds, queued changes are then applied, and the next eligible hand is
scheduled. Fold wins use the same result boundary but do not reveal folded
hole cards.

Multi-object table updates are staged before commit. Configuration, buy-in,
seat identity, checked totals, deck construction, and engine-start inputs are
validated before the first authoritative write. Boundary changes and completed
stack copy-back build a replacement immutable seat map and swap it once; a new
`HoldemHand` is assigned only after `start` succeeds. Expected T3 action
rejections are atomic by T3 contract. An unexpected engine/invariant failure is
not reported as a client error: the active engine is quarantined, the table
moves to `FAILED`, no potentially inconsistent engine frame is published, and
the last committed persistent table ledger remains authoritative. Because chips
are in-memory play money with no wallet, recovery closes/recreates the table
rather than attempting to serialize or repair a corrupt hand.

## Timers and stale callback safety

Use Spring `TaskScheduler` behind `GameplayScheduler`; tests provide a manual
scheduler and mutable clock. Do not call `ScheduledExecutorService` directly
from the aggregate.

Each action opportunity increments `actionSeq`, stores an absolute `Instant`
deadline, and schedules a callback carrying:

```text
(roomId, handId, actionSeq, expectedActorUserId)
```

The callback reacquires the room through `RoomManager` and no-ops unless all
four identity values still match. If the deadline has elapsed, it chooses
`CHECK` when `legalActions.canCheck`, otherwise `FOLD`, calls the same engine
mutation path, and sets `sitOutAfterHand` for that seat. Old callback handles
may run after cancellation; guards, not cancellation, provide correctness.

If a valid callback arrives before its deadline, it returns a new intent for
the exact remaining duration with the same action opportunity but a newer
`scheduleGeneration`. Repeated early or duplicate callbacks therefore remain
bounded and eventually produce exactly one timeout mutation. The minimum
reschedule delay is 1 ms; a non-positive remainder executes the timeout path.

Presentation callbacks similarly carry `(roomId, handId,
presentationSequence)`. They only advance the currently expected step. Default
pacing is 750 ms between flop, turn, and river steps and 1.5 seconds before the
showdown/result step. Values are configuration properties so tests use zero or
a manual clock without sleeping.

## Viewer-safe game DTO and redaction

`RoomSnapshot` gains nullable `GameView game`; it is null before any player has
taken a seat. `GameSnapshotRedactor` accepts only immutable room/game state and
the authenticated viewer UUID. It constructs protocol records field by field;
Jackson never serializes `HoldemHand`, `HandSnapshot`, `PlayerSnapshot`,
`HandEvent`, or `TableGame` directly.

`GameView` contains:

```text
tableStatus, handId, phase|null, completionReason|null, actionSeq, actingSeat, deadline
buttonSeat|null, smallBlindSeat|null, bigBlindSeat|null, board
currentBet, pots[] { amount, eligibleSeats[] }
awards[] { potIndex, potAmount, winners[] { seat, amount } }
seats[] { seat, userId, stack, seatStatus, holeCards, lastAction, finalHandRank }
viewer {
  seated, seat, canAct, rebuyMinimum, rebuyMaximum,
  legalActions: null | {
    canFold, canCheck, canCall, callAmount, toCall,
    canRaise, minRaiseTo, maxRaiseTo
  }
}
```

`legalActions` is non-null only for the authenticated current actor while the
table is `RUNNING`; it maps every field of T3 `LegalActions`. Other viewers
receive `null`, not zero-valued poker hints. JSON nullability and names are
shared verbatim with T7's generated/manual TypeScript protocol type.

After the first seat but before a hand, `tableStatus=WAITING`, `handId=0`,
`actionSeq=0`, and `phase`, `completionReason`, actor, and deadline are null.
`PAUSED` retains the current phase for display but has no actor/legal actions;
`FAILED` quarantines and clears the active/presented hand, returns to nullable
phase using the last committed table ledger, and is a safe boundary for
stand/leave and T9 cleanup.

`lastAction` is accumulated by `TableGame` from the latest publicly presented
`HandEvent.PlayerActed` per seat and resets at hand start. Fold/check carry no
amount, call carries the actual contributed amount, and raise carries the
`RaiseTo` street-total from the event's typed action. At a
`COMPLETE(SHOWDOWN)` presentation step, `finalHandRank` is computed by the
redactor's trusted showdown mapper using `HandEvaluator` over each non-folded
player's two cards plus the five-card board. All non-folded players reveal at
showdown; folded players never reveal. T6 does not invent a muck policy.
`awards` is derived from the presented snapshot's ordered `PotAward` values for
both fold wins and showdown. T7 derives winner banners from those possibly
multi-pot, tied awards; there is deliberately no singular `winningSeat` or
`winningCards` field.

Cards are encoded by one shared explicit codec (`As`, `Kh`, etc.). Privacy
rules are fail-closed:

- before showdown, only the seated viewer sees their own hole cards;
- an unseated member sees no hole cards;
- a fold-win result reveals no additional hole cards;
- a showdown reveals every non-folded player's hole cards; folded hands remain
  hidden; and
- absent or inconsistent viewer identity produces a spectator-redacted view.

Legal actions are populated only for the current authenticated actor; other
viewers receive `legalActions=null` and `canAct=false`. `deadline` is still
public while the table is running so all clients can render the current actor's
timer.

During paced runout and settlement, the current `presentedSnapshot` is the only
source for every dealt-in player's public stack, commitments, pots, awards,
board, phase, and hole-card reveal. The persistent table-seat ledger may already
hold the engine's final stacks for next-hand safety, but it is not projected
over an older presentation frame. Non-hand seat metadata is overlaid only when
it cannot reveal a queued boundary change. Queued stand/sit/rebuy changes become
public after the final result frame. This prevents future awards or final stack
totals from leaking into earlier paced frames.

The serialization regression test uses the production Jackson converter,
serializes snapshots for actor, opponent, and unseated member, scans the full
JSON tree for every forbidden card code, and also asserts trusted engine type
names/fields cannot appear. This is the release gate for the security boundary.

## Sit-out, stand, bust, and rebuy behavior

- `game.sit-out` during a hand sets `sitOutAfterHand`; outside a hand it applies
  immediately. If it is the actor's turn, play continues normally—the command
  does not secretly fold.
- `game.sit-in` clears a queued sit-out or activates a positive-stack seat at
  the next boundary. It cannot make a player join a hand already in progress.
- `/room/stand` during a hand sets `standAfterHand`; after completion the stack
  is discarded and the seat becomes free. Outside a hand it behaves immediately.
- `/room/leave` returns `HAND_IN_PROGRESS` when the member participates in the
  current hand or has a queued seat mutation. The client may remain disconnected
  without losing membership; it can leave after the hand boundary.
- A zero-stack seat becomes `BUSTED` and is ineligible for new hands.
- `game.rebuy` is allowed only for a busted occupied seat, validates the room
  buy-in range, and stores one replaceable pending amount. The amount is applied
  at the next safe boundary and the player returns active unless sitting out.

## Failure behavior

- Engine rejection maps to a stable protocol error and leaves room revision,
  action sequence, and deadline unchanged.
- Scheduler rejection after commit is logged and surfaced by a metric; the
  coordinator immediately performs one guarded retry. If it still fails, a
  generation-checked room mutation sets `tableStatus=PAUSED`, clears the public
  actor/deadline/legal actions, increments the room revision, and publishes the
  pause. A bounded recovery sweep retries the current desired intent with
  exponential backoff capped at 30 seconds. On successful install the room
  commits/publishes `RUNNING`; if the preserved deadline has passed, it installs
  an immediate guarded callback. After 5 minutes continuously paused the table
  moves to `FAILED`; gameplay remains disabled and members may leave/stand.
  T9 may idle-close a fully disconnected `PAUSED`/`FAILED` room after grace.
- Publication failure does not roll back state; sync is authoritative.
- A stale timer/pacing callback is a successful no-op with no publication.
- Arithmetic overflow or an unexpected engine invariant failure is logged with
  a correlation ID, returns `INTERNAL_ERROR`, and moves the table to `FAILED`
  using the last committed persistent seat ledger; staged room/table writes are
  not partially installed.
- Server restart discards rooms, games, timers, and chips consistently with T5's
  in-memory room policy.

## Implementation sequence

1. Add configuration value types and extend create/take-seat protocol.
2. Add table seat state and locked `TableGame` with invariant tests.
3. Add gameplay commands and stable error mapping.
4. Add scheduler port, guarded action timeouts, and lifecycle callbacks.
5. Add presentation queue for runout/result pacing.
6. Add `GameSnapshotRedactor` and production-serializer privacy tests.
7. Extend room snapshots and per-recipient publication.
8. Add scripted two-client STOMP integration coverage.
9. Run the full server test suite and architecture dependency tests.

## Acceptance criteria

- All gameplay mutations and callbacks are confined by the room lock and obey
  the two-stage lookup rule; unrelated rooms can mutate concurrently and no
  room-only path acquires the registry lock.
- Stale client actions and stale scheduled callbacks cannot mutate a newer hand.
- No non-owner receives a private card before an eligible showdown reveal.
- All-in boards and results are published in deterministic paced steps.
- Earlier presentation frames never expose later awards, final stacks, ranks,
  or queued boundary mutations.
- Stack totals are conserved through actions, payouts, queued rebuy, and next
  hand initialization.
- Bust, sit-out/sit-in, queued stand, and rebuy states survive sync/reconnect.
- Two authenticated STOMP clients can play a scripted hand end to end, including
  a timeout and a reconnect sync, with deterministic tests and no wall-clock sleep.
- Stale scheduler reconciliation cannot replace the latest intent; scheduler
  failure produces a visible, recoverable pause and a bounded failed state.
