# T9 Architecture Review

## Current result

**ARCHITECTURE_REVIEWED.** `README.md`, `REFINEMENT.md`, and `TEST_PLAN.md`
resolve every finding below. The revised contracts were accepted by the user
and `TASKS.md` has been reconciled to `ARCHITECTURE_REVIEWED`.

The review checked the plan against the implemented T5 WebSocket/session/room
code, the proposed T6 lifecycle, downstream T10 recovery behavior, general
security and concurrency architecture, and ordinary Java/Spring coding
practices.

## Resolution record

| Finding | Resolution in the revised plan |
| --- | --- |
| AR-01 — Malformed-frame bypass | Added a coarse authenticated pre-conversion session/user budget and repeated-abuse close policy ahead of Jackson. |
| AR-02 — Bypass-prone wrappers | Added mandatory `@AdmittedCommand`, a centralized aspect, destination/type checks, and controller dependency restrictions. |
| AR-03 — Membership-only idle changes | Added post-commit reconciliation for create/join/leave/deletion as well as socket transitions. |
| AR-04 — Missing reverse socket index | Added atomic user-to-sessions and session-to-user indexes with transition-returning APIs and principal-less disconnect tests. |
| AR-05 — Hard-cap race | New identities reserve slots under a short registry lock; concurrent cap/reclaim tests prove no overshoot or double issue. |
| AR-06 — Dead sockets | Added negotiated 10-second heartbeats, bounded liveness detection, client requirements, and ungraceful-loss tests. |
| AR-07 — Cleanup starvation | Added one-node-per-live-entry fair queue rotation with bounded batches and eventual-reclamation tests. |
| AR-08 — Recovery starvation | Added a separate bounded control bucket for state sync, room sync, and leave. |
| AR-09 — T10 protocol drift | Standardized idle closure on `RoomExitNotice(IDLE_TIMEOUT)` and restart recovery on `state.sync room=null` then `room.sync(oldId)`. |
| AR-10 — Paused-room leak | Healthy hands wait for boundaries; paused tables get bounded recovery and paused/failed disconnected rooms may then close. |

## Findings

### [RESOLVED P1] AR-01 — Admission after deserialization does not bound malformed-frame abuse

The proposed token bucket runs inside `@MessageMapping` methods after Jackson
decoding and Bean Validation. A client can therefore send malformed or
validation-failing frames at an unlimited rate and consume inbound executor,
JSON parsing, allocation, validation, exception mapping, and log capacity
without spending a token. A 16 KiB frame cap bounds the cost of one message,
not the rate of that work.

Add a cheap pre-conversion admission layer on the inbound channel, keyed by the
already-authenticated principal/session, and retain a correlated controller
ack path where appropriate. Define behavior for malformed floods separately
from normal valid-command limiting (for example, a stricter per-session budget
and eventual connection close). Load-test malformed and unknown-field traffic,
not only valid commands.

### [RESOLVED P1] AR-02 — A wrapper in every controller is a bypass-prone cross-cutting design

Every handler must remember to call `CommandAdmissionService.execute`. The
proposed reflection test can enumerate annotated methods but cannot prove from
ordinary reflection that each method's body invokes the wrapper. Future
controllers can compile and run while silently bypassing the limit.

Enforce admission structurally through one inbound interceptor/aspect or a
declarative annotation processed centrally. If a controller-level layer is
kept for correlated acks, make it impossible to inject the unrestricted command
services into controllers and add an executable dependency/architecture rule
that actually detects bypasses.

### [RESOLVED P1] AR-03 — Room idleness is reconciled only on socket transitions

Socket zero/one transitions are not the only events that change whether all
room members are disconnected. Example: member A is connected, member B is
disconnected, and A leaves the room. The remaining room is now fully
disconnected, but no socket transition occurs, so no grace timer is scheduled.
Conversely, a connected user joining an idle-pending room must immediately
clear the pending generation, not wait for the old callback to discover it.

Reconcile presence after every membership mutation: create, join, leave,
last-member deletion, idle close, and any administrative removal. Have
`RoomManager` return an immutable presence intent or invoke a post-commit
coordinator port without publishing under locks. Add explicit tests for
connected-member leave, connected-user join during grace, and membership
replacement at the due-time boundary.

### [RESOLVED P1] AR-04 — The socket registry lacks the reverse index needed for reliable disconnects

The layout describes only `user -> active STOMP session IDs`. Disconnect and
session-destruction events can be duplicated and may not reliably carry the
same usable principal as CONNECT. Without `simpSessionId -> userId`, removal can
be ambiguous, expensive, or leak a socket count forever. Multi-tab correctness
also requires the zero-to-one and one-to-zero transitions to be atomic with
both indexes.

Specify both indexes, conflict behavior for a reused session ID, and one atomic
register/remove API returning the transition. Test disconnect without a
principal, duplicate connect/disconnect, session destruction, and concurrent
connect/disconnect for the same user.

### [RESOLVED P1] AR-05 — The limiter hard cap is not concurrency-safe as described

A `ConcurrentHashMap` plus a configured cap does not by itself enforce a hard
entry limit. Concurrent first commands for many unseen UUIDs can all observe
space and then insert, overshooting the cap. `size()` checks are also expensive
under contention and cannot serve as a reservation.

Define an atomic slot-reservation mechanism (a small registry lock, semaphore,
or equivalent) around new-entry creation and expiry reclamation while leaving
existing-bucket consumption independent. Add a many-thread test proving the
entry count never exceeds the configured cap and that reclaimed slots are not
double-issued.

### [RESOLVED P1] AR-06 — Dead sockets can keep rooms alive indefinitely

The idle policy treats any active socket as presence, but the current
`enableSimpleBroker` configuration does not configure broker heartbeats. A
half-open connection can remain registered after a laptop sleeps, network path
dies, or browser process disappears without a clean DISCONNECT, defeating the
30-minute policy.

Define heartbeat and stale-transport detection as part of presence semantics,
including scheduler ownership and acceptable detection delay. Test or
integration-simulate an ungraceful socket loss; idle grace should start after
the server declares that transport dead, not require a client DISCONNECT.

### [RESOLVED P2] AR-07 — Bounded cleanup has no fairness algorithm

"Bounded chunks" over a `ConcurrentHashMap` is not a complete cleanup design.
Starting a fresh weakly-consistent iterator each sweep can repeatedly inspect
the same region and starve expired entries elsewhere; opportunistic cleanup can
also concentrate work on hot request paths.

Choose a concrete fair structure: an access/expiry queue with generation-stamped
entries, a timing wheel, or a persisted scan cursor that eventually covers the
registry. Specify stale queue-node handling and bounds. Test that every expired
entry is eventually reclaimed while concurrent accesses continually refresh
other entries.

### [RESOLVED P2] AR-08 — One shared bucket lets an abusive tab starve recovery commands

All `/app` commands share one per-user bucket. A noisy tab can consume the
entire budget and prevent another tab from calling `state.sync`, `room.sync`, or
`room.leave`, even though those commands are the recovery and safety paths the
architecture relies on. This is a self-DoS amplified by the explicit multi-tab
support.

Define command classes/costs or a small reserved control-plane budget. Gameplay
actions should remain bounded, but sync/leave recovery must stay usable under a
flood without becoming an unlimited abuse path. Test a saturated gameplay tab
while a second tab performs recovery and leaves safely.

### [RESOLVED P2] AR-09 — Idle-close wire behavior is not reconciled with T10

T9 specifies `RoomExitNotice(reason=IDLE_TIMEOUT)`, while T10 still describes a
`room.closed` event. T10's restart test also still expects `state.sync` itself
to return `ROOM_NOT_FOUND`, contradicting T9's refined and implementable
`state.sync -> room:null`, then `room.sync(oldRoomId) -> ROOM_NOT_FOUND`
contract.

Select exact destinations, DTO names, reason enums, and client transition rules,
then update the downstream architecture and E2E test plan in the same contract
change. Cross-task notes are not enough when executable acceptance tests still
assert the old protocol.

### [RESOLVED P2] AR-10 — Paused T6 hands can defeat idle cleanup permanently

T9 waits indefinitely for a T6 safe boundary. T6's current scheduler-failure
plan can leave an active hand `PAUSED` with no defined recovery, so a fully
disconnected room may never become safe and never be reclaimed by the overdue
sweeper.

Define a bounded operational policy shared with T6: successful resume, forced
deterministic hand termination/refund, or eventual room close with a clearly
documented chip-loss policy for in-memory play money. Add the paused-hand idle
expiry case to both task test plans.

## Preserved strengths

- User-level sharing across tabs is the correct identity scope for gameplay
  admission.
- Stable correlated `RATE_LIMITED` acks without automatic action replay preserve
  `actionSeq` correctness.
- Low-cardinality metrics and process-salted sampled logs avoid sensitive labels
  and metric-cardinality explosions.
- Guard generations, not scheduled-task cancellation, correctly decide expiry
  races.
- Immediate T5 last-member GC remains distinct from disconnected-member grace.
- The restart contract correctly distinguishes unknowable `state.sync` state
  from a caller-supplied stale room ID.

## Review completion gate

AR-01 through AR-10 are resolved in the revised architecture and test plan. The
user accepted the revised plan, so the review gate is complete.
