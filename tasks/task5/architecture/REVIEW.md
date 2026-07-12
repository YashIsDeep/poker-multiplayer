# T5 Architecture Review

## Current result

**ARCHITECTURE_REVIEWED.** `README.md`, `REFINEMENT.md`, and `TEST_PLAN.md`
contain explicit resolutions for every finding below. The revised contracts
were accepted for implementation and are now covered by the T5 code/test
seams.

## Resolution record

| Finding | Resolution in the revised plan |
| --- | --- |
| AR-01 — Unrecoverable committed create | Added session-local `state.sync`, returning authoritative membership epoch and nullable current room without a caller-known room ID. |
| AR-02 — Undefined T6 lock handoff | Added registry plus per-room locks, fixed `registry -> one room` ordering, room-only T6 actions, and atomic membership+game projections. |
| AR-03 — Post-commit publication ambiguity | Added a `RoomEventSink` port and `PublicationReport`; every enqueue is attempted independently and committed-success ack remains mandatory. |
| AR-04 — Domain-to-WebSocket dependency | Replaced wire DTOs in domain results with immutable transport-neutral states and added an executable `room -> ws` dependency ban. |
| AR-05 — Stale exit notices | Added checked per-user membership epochs to state sync, room snapshots, and exit notices, including same-room rejoin tests. |
| AR-06 — UNSUBSCRIBE rejected | Authenticated session-owned UNSUBSCRIBE is allowed and lifecycle-tested; ACK/NACK remain denied. |
| AR-07 — Boundary policy ambiguous | Set explicit message/buffer/time limits, a strict STOMP-only Jackson mapper, cascaded validation, and clarified T5/T9 ownership. |
| AR-08 — Invalid concurrency oracle | Replaced the blanket one-winner rule with enumerated linear outcomes per race. |
| AR-09 — Duplicate CSRF exposure | Selected `HttpSessionCsrfTokenRepository` and authenticated JSON as the sole token-delivery mechanism; no XSRF cookie. |
| AR-10 — WebSocket outlives login session | `WebSocketConfig` now uses Spring Session's WebSocket integration so inbound activity refreshes the session and destruction closes all associated sockets. |

## Findings

### [RESOLVED P1] AR-01 — A committed room creation can become unrecoverable

The protocol deliberately has no request-ID replay cache, while room creation
is not idempotent. If create commits but its room snapshot and ack are both lost
(including the documented broker-publication failure path), retrying create
returns `ALREADY_IN_ANOTHER_ROOM`. The reconnect flow cannot recover because
`room.sync` requires a room ID the client may never have learned, and
`lobby.sync` does not identify the viewer's current room.

Define an authoritative recovery path. Viable designs include a bounded
request-result cache keyed by `(userId, requestId)`, an idempotent create that
returns the already-created room for the same request, or a `membership.sync`
operation/`currentRoomId` field that lets the authenticated user discover their
membership. Test commit followed by loss of both publications, reconnect, and
successful recovery without guessing a room ID.

### [RESOLVED P1] AR-02 — The T6 lock and consistency handoff is not designed

The plan says T6 can add a room-confined game lock without changing membership
transactions, but T6 must make leave, stand, seating eligibility, game actions,
timers, and viewer snapshots consistent with game state. T5 maps complete wire
snapshots while holding the global registry lock. Adding a second room/game
lock later creates an unspecified lock order and makes it unclear how a single
snapshot can atomically represent both membership and game state.

Define the extension seam now: aggregate ownership, the one permitted lock
order, which operations need both locks, and how an immutable membership+game
projection is captured before publication. Add an architecture test or
concurrency scenario that pins the rule. Without this, T6 will either serialize
all game actions through the global registry lock or risk deadlock/torn
snapshots.

### [RESOLVED P1] AR-03 — Post-commit publication failure and ack delivery are underspecified

`RoomCommandService` is said to publish and then construct the ack, while a
publisher failure must still produce a success ack. That only works if every
individual publication failure is caught and isolated and controller return is
still reached. A single throwing `convertAndSend...` can otherwise abort the
remaining fan-out and route the command through the unexpected-error handler,
incorrectly reporting `INTERNAL_ERROR` after commit.

Specify an explicit post-commit delivery result: attempt each independent
recipient/lobby publication, collect failures, log one correlation record, and
always construct the committed-success ack. Also state what happens if the ack
send itself fails (the command remains committed and recovery is by sync). The
test must fail the first of multiple sends and prove later sends and the success
ack are still attempted.

### [RESOLVED P2] AR-04 — The room domain is coupled to WebSocket DTOs

`RoomManager` returns a `RoomMutation` containing already-mapped per-viewer wire
DTOs, and `RoomSnapshotMapper` lives in `com.poker.room` while producing
`com.poker.ws.protocol` objects. This makes the authoritative room/domain layer
depend on its transport representation, despite the stated controller/service
boundary. The proposed architecture test notably forbids several dependencies
from `room` but does not forbid `room -> ws`.

Keep the under-lock capture immutable without reversing the dependency: have
the domain return transport-neutral immutable room/lobby projections, then map
those to STOMP DTOs in the application/adapter layer. Extend the architecture
gate to forbid `com.poker.room` dependencies on `com.poker.ws` and Spring
messaging types.

### [RESOLVED P2] AR-05 — Exit notices have no defined stale-message rule

Snapshots have per-room revisions, but `RoomExitNotice` has no documented
fields or acceptance rule. Since committed publications may arrive out of
order, a delayed notice for room A can arrive after the user has joined room B
and clear the new room state in every tab.

Include at least the exited `roomId` and require clients to apply the notice
only when it matches their current room. A monotonic membership generation is
safer if future transitions can reuse a room. Add a forced reordering test:
leave A, join B, deliver B's snapshot, then deliver A's notice; B must remain.

### [RESOLVED P2] AR-06 — Denying every unlisted STOMP frame rejects normal unsubscribe

The allow-list names CONNECT, DISCONNECT, MESSAGE, and SUBSCRIBE, then denies
all other inbound frame types. A normal STOMP client also sends UNSUBSCRIBE
when replacing a subscription. Treating it as an attack can close a valid
connection during route or store lifecycle changes.

Define safe handling for destination-less protocol frames (at minimum
UNSUBSCRIBE for subscriptions owned by the same WebSocket session) and test
subscribe/unsubscribe/resubscribe. Continue denying client SEND/MESSAGE to
broker destinations. Spring Security's messaging rules should distinguish
frame type from destination rather than use one catch-all denial.

### [RESOLVED P2] AR-07 — Boundary limits and JSON compatibility policy are deferred or ambiguous

T5 accepts unauthenticated-to-domain JSON at a persistent WebSocket boundary,
but the plan does not choose explicit inbound message, send-buffer, send-time,
or time-to-first-frame limits. Spring supplies defaults, but relying on them
silently makes the reviewed operational contract version-dependent. The test
plan also says unknown JSON fields follow "the configured Jackson policy"
without selecting that policy, while simultaneously expecting injected
identity-like fields to be rejected. `TASKS.md` assigns payload validation to
T9 even though T5 already requires it.

Set and test explicit transport limits appropriate for these small commands,
choose strict or forward-compatible unknown-field handling for protocol DTOs,
and make T5 versus T9 ownership unambiguous: T5 must own structural validation
and bounded frames; T9 may add rate limiting and abuse controls. Spring exposes
these controls through `WebSocketTransportRegistration`.

### [RESOLVED P3] AR-08 — The concurrency test oracle is incorrect for several races

The test plan says exactly one operation may win every listed race. That is not
true for leave racing with take-seat (both can commit in the serialization
take-then-leave), last-member leave racing with sync/idempotent join, or two
room creations that initially collide on a join code but retry successfully.
This wording will produce flaky tests or reject valid linearizable behavior.

For each race, enumerate the valid serial outcomes and assert the final
invariants plus responses that correspond to one of those histories. Reserve
"exactly one winner" for genuinely exclusive races such as two users taking
the same seat or two users joining the final slot.

### [RESOLVED P3] AR-09 — The CSRF cookie is made script-readable without need

The plan returns the token from authenticated `/api/csrf` and keeps it in
memory, yet also selects `CookieCsrfTokenRepository.withHttpOnlyFalse()`. The
non-HttpOnly setting is needed when JavaScript reads the cookie directly; it is
redundant when the endpoint returns the token. Pick one delivery contract. If
JSON is authoritative, keep the backing cookie HttpOnly (or use the session
repository); if cookie reading is authoritative, remove the duplicate token
body and document the SPA request handler behavior after authentication and
logout.

### [RESOLVED P1] AR-10 — An authenticated socket can outlive logout or expiry

Handshake authentication establishes the socket principal once. Without the
Spring Session WebSocket lifecycle integration, invalidating the HTTP session
does not by itself define closure of that already-authenticated transport.

Use `AbstractSessionWebSocketMessageBrokerConfigurer` so inbound messages
refresh last-access time and `SessionDestroyedEvent` closes every WebSocket
registered to the ended session. Test both CSRF-protected logout and repository
session destruction, and prove closure is transport-only rather than room
leave.

## Preserved strengths

- HTTP-session identity is retained as the only authority; STOMP headers cannot
  select a user.
- CONNECT CSRF plus an explicit origin allow-list is an appropriate defense for
  browser WebSockets.
- Client MESSAGE destinations are allow-listed and broker destinations are not
  writable by clients.
- Per-viewer snapshots and an explicit allow-list mapper establish the right
  privacy boundary for T6 hole cards.
- Registry plus per-room locks preserve atomic one-room-per-user membership
  while giving T6 a room-confined concurrency boundary.
- Revisions, immutable publication data, deterministic ordering, and
  disconnect-as-transport-loss are good foundations for reconnect behavior.
- The test plan correctly combines domain, messaging-security, and real STOMP
  integration coverage and avoids fixed sleeps.

## Review completion gate

Before changing T5 to `ARCHITECTURE_REVIEWED`:

1. resolve AR-01 through AR-10 in the architecture and test plan;
2. perform a consistency pass across command semantics, publication behavior,
   package boundaries, T6 handoff, and test oracles;
3. verify every resolved finding has an executable test or architecture gate;
   and
4. obtain user acceptance of the revised plan.
