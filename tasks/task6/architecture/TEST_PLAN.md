# T6 Architecture Test Plan

## Test strategy

Use pure aggregate tests for rules, a manual clock/scheduler for temporal
behavior, production Jackson serialization for privacy, and one real
`@SpringBootTest(webEnvironment = RANDOM_PORT)` two-client STOMP test for the
assembled boundary. No correctness test sleeps for a real action timeout.

## Aggregate and lifecycle tests

`TableGameTest` covers:

- room configuration boundary values and checked chip totals;
- take-seat buy-in validation and idempotency;
- automatic hand scheduling at exactly two eligible seats;
- heads-up and multi-player button/blind rotation across sparse seats;
- mapping all four wire actions to the T3 engine;
- one room revision per public frame and monotonic `handId`/`actionSeq`;
- stack copy-back exactly once after fold win and showdown;
- no next hand with fewer than two eligible seats;
- sit-out and sit-in application at safe boundaries;
- stand queued during a hand and immediate outside a hand;
- leave rejection for a current participant;
- bust state, replaceable queued rebuy, range rejection, and boundary apply; and
- conservation before/after a hand while treating rebuy as an explicit external
  chip injection at the boundary.

`GameplayConcurrencyTest` races two actions with the same `actionSeq`. Exactly
one commits; the other returns `STALE_ACTION` or `NOT_YOUR_TURN`, and the room
revision advances once. It also races stand/sit-out with a timer callback and
asserts invariants and lock completion with a bounded test timeout.

It also runs actions concurrently in two different rooms while holding one
room's lock at a test barrier and proves the other room completes. Deletion
racing a retained lookup either linearizes gameplay before close or observes
`room.closed` and returns `ROOM_NOT_FOUND`; it never mutates a removed aggregate.
An architecture test rejects gameplay paths that hold or reacquire
`registryLock` while a room lock is held.

Join/leave racing a gameplay frame proves its recipient set and per-member
epochs come from one room-lock state: a newly included member always has its
installed epoch, and an excluded/leaving member is never paired with another
membership's epoch.

Fault-injection cases fail deck creation, checked-total validation, replacement
seat-map construction, engine start, and completed stack copy-back before the
commit swap and prove membership and persistent seat ledgers remain equal. An
injected unexpected engine failure quarantines the hand, publishes only a
`FAILED` table frame from the last committed ledger, and permits leave/stand.

## Timer and pacing tests

`GameplayTimerTest` uses an injected mutable `Clock` and manual priority queue:

1. Start a hand and capture its timer tuple.
2. Submit an action before the deadline.
3. Advance beyond the old deadline and run the old callback.
4. Assert no mutation, revision, or publication.

Additional cases:

- timeout checks when free and folds when facing a bet;
- timeout queues sit-out after hand;
- callback before deadline no-ops/reschedules safely;
- callback with wrong room, hand, sequence, or actor no-ops;
- all-in transition frames expose flop, turn, river, then showdown in order;
- reconnect sync during pacing returns the current presented step, never the
  engine's future completed snapshot;
- duplicate/out-of-order presentation callbacks do not skip or repeat a frame;
- scheduler rejection pauses actions rather than creating a timerless hand; and
- zero-duration test pacing still uses guarded callbacks instead of recursion.

The early-callback case invokes the same callback repeatedly before due time,
asserts one live desired intent with increasing `scheduleGeneration`, then
advances to the deadline and proves exactly one timeout mutation. A
reconciliation race delays install of intent N until N+1 commits and proves N
cannot replace or cancel N+1. Scheduler rejection cases cover first retry,
visible `PAUSED`, successful resume, overdue immediate callback, five-minute
transition to `FAILED`, and T9 eligibility to close a disconnected paused or
failed table.

Hand-start tests schedule a start, then independently perform every
`lifecycleSeq` invalidator: seat, stand, leave, sit-in/out, rebuy replacement,
boundary apply, pause/resume, and close. Each old callback no-ops. Checked
sequence exhaustion fails closed without wrapping.

## Redaction and serialization tests

Build a deterministic hand with known cards and serialize each `RoomSnapshot`
through the production STOMP message converter for:

- current actor;
- seated opponent;
- unseated room member; and
- unknown/fail-closed viewer.

Before showdown, scan complete textual JSON values in the tree to prove each
viewer sees only permitted card tokens. At fold completion, prove no opponent
cards become visible. At showdown, prove only showdown-eligible cards reveal
and folded cards remain absent. Also assert no trusted property such as
`drawOrder`, `burns`, `playerSnapshots`, `handCommitted`, or Java class metadata
appears in the tree or raw JSON. Exact-value card checks avoid false positives
from display names or property names.

Reflection/architecture tests require every `RoomSnapshot.game` value to be a
wire DTO produced by `GameSnapshotRedactor`; direct Jackson annotations on
engine/domain classes fail the build.

Contract tests also assert actor-only `legalActions`, every T3 legality
flag/amount, null legal actions for other viewers, accumulated `lastAction`,
evaluated ranks for every non-folded showdown player, ordered multi-pot/tied
awards, and absence of a singular-winner field.

A paced multi-side-pot fixture compares every emitted frame. Board, stacks,
commitments, pots, awards, ranks, and queued seat flags must equal the current
presented transition and must not contain a value first introduced later.

## Two-client STOMP integration script

`GameplayStompIntegrationTest` uses two independently authenticated HTTP
sessions (Alice and Bob), fetches a CSRF token per session, connects two native
STOMP clients, and subscribes to each session's ack/room queues.

Script:

1. Alice creates a 2-seat 5/10 room with buy-in 400–2000 and a test timeout.
2. Bob joins; both take seats with 1,000 chips.
3. Await the first game snapshot and assert heads-up button/blinds/actor.
4. Compare Alice/Bob JSON and prove each sees only their own cards.
5. Send one legal action with the current sequence and one replay with the old
   sequence; assert accepted then `STALE_ACTION`.
6. Drive the hand to an all-in using server-advertised raise bounds.
7. Advance the manual scheduler through runout and result frames; assert board
   sizes 0/3/4/5, legal reveal, and chip conservation.
8. Queue a rebuy if busted and advance to the next hand boundary.
9. Disconnect/reconnect one client without leaving and call `state.sync`; assert
   same membership epoch, seat, stack, and viewer-private cards.
10. Start another hand, let an action deadline expire, and assert automatic
    check/fold plus queued sit-out.

The integration test correlates acks by request ID and accepts snapshots only
by `(membershipEpoch, roomRevision)`; it does not assume ordering across STOMP
destinations.

## Full-suite gates

Run from the repository root:

```powershell
npm test
```

Acceptance requires:

- existing T1–T5 tests remain green;
- all deterministic gameplay/timer/privacy tests pass repeatedly;
- the two-client STOMP script passes without `Thread.sleep`-based correctness;
- architecture tests preserve `engine <- room <- application/ws` dependency
  direction;
- no outbound JSON contains a forbidden hole card in the redaction matrix;
- two independent rooms make progress concurrently; and
- lifecycle/schedule generations, pause recovery, and staged failure cases pass
  deterministically.
