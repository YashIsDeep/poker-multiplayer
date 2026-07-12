# T9 Code Architecture — Server Hardening

## Goal

Harden the authenticated STOMP/room boundary after gameplay wiring lands. T9
adds bounded per-user command admission, measurable abuse signals and a tuning
workflow, an explicit disconnected-room expiry policy, and executable recovery
and multi-tab semantics. It preserves T5's strict payload/frame validation and
T6's room-lock/action-sequence correctness rather than duplicating them.

## Scope and non-goals

T9 owns:

- a coarse pre-conversion per-session frame budget for malformed-flood defense;
- a per-authenticated-user token bucket shared by every socket and tab;
- stable `RATE_LIMITED` command acks without closing healthy sockets;
- bounded limiter state and sampled privacy-safe abuse logging;
- Micrometer counters/gauges/timers and a repeatable tuning load test;
- active socket presence tracking for room-idle policy;
- guarded cleanup of rooms whose members are all disconnected beyond grace;
- precise server-restart/`ROOM_NOT_FOUND` recovery behavior; and
- integration tests for same-user tabs, concurrent commands, disconnect grace,
  reconnect cancellation, and room expiry.

T9 does not reimplement structural DTO validation, maximum STOMP frame size,
subscription ownership, session authentication, last-member deletion, gameplay
timers, or `actionSeq`. Those are established by T5/T6. It also does not add
Redis, a distributed broker, room persistence, a replay cache, device limits,
IP bans, CAPTCHA, or client `BroadcastChannel` UX.

## Final product policies

### Multi-tab

The server permits multiple simultaneous authenticated WebSocket connections
for one user, including connections from different HTTP sessions/devices.
There is no “kick old tab” message.

- A command ack stays on the initiating STOMP session because controllers use
  `@SendToUser(broadcast = false)`.
- Authoritative room snapshots continue to fan out to all connections for the
  user.
- All connections share user-level gameplay and reserved-control budgets; each
  socket also has its own coarse pre-conversion frame budget.
- Concurrent gameplay commands are serialized by the room lock; T6
  `actionSeq` ensures at most one stale-racing action commits.
- Repeating `room.join` for the same room remains idempotent. Joining another
  room remains `ALREADY_IN_ANOTHER_ROOM`.
- T10 may choose one active browser tab locally, but server correctness never
  depends on that client convention.

### Idle rooms

T5's immediate, atomic last-member garbage collection remains unchanged. T9
adds one policy for non-empty abandoned rooms:

> Close a room after every member has had zero active authenticated STOMP
> connections continuously for 30 minutes.

The grace is configurable (`poker.rooms.disconnected-grace`, default `30m`,
minimum `1m`, maximum `24h`). A connected but inactive member keeps the room;
T9 does not surprise-close a table visible in an open client. T6 action timers
continue while sockets are absent and eventually sit timed-out players out.
Cleanup waits until the hand/presentation boundary for healthy running tables;
if grace expires during an active hand, it records expiry-pending and closes
after T6 reaches a safe boundary. The bounded paused/failed exception is defined
in the presence section so scheduler failure cannot leak a room forever.

Any member reconnect cancels expiry logically. Correctness uses a room presence
generation checked under the room lock, not cancellation of a scheduled task.
Closing removes room ID, join code, user indexes, and lobby entry atomically,
increments each removed member's epoch once, increments lobby revision once,
and attempts `RoomExitNotice(reason=IDLE_TIMEOUT)` for all user destinations.
Usually no socket is present to receive it; reconnect/sync behavior remains
authoritative.

### Server restart and missing rooms

Rooms are intentionally in-memory. After restart the server cannot know that a
newly connected user previously belonged to a particular room. Therefore:

- `state.sync` succeeds with `room: null` and the fresh process membership
  epoch; it cannot truthfully return `ROOM_NOT_FOUND` without an old room ID.
- A client retaining `oldRoomId` must send `/app/room.sync { roomId: oldRoomId }`
  after reconnect. That command returns a normal failure ack with
  `error=ROOM_NOT_FOUND`.
- The ack is session-local and does not close the socket. The client clears its
  cached room and returns to the lobby.

This is the exact contract T10's kill-server flow must test.

## Package and file layout

```text
server/src/main/java/com/poker/hardening/
  InboundFrameBudgetInterceptor.java pre-conversion session/user budget
  AdmittedCommand.java              declarative command class/type annotation
  CommandAdmissionAspect.java       centralized correlated valid-command gate
  CommandAdmissionService.java       user-level admission API
  TokenBucket.java                   synchronized monotonic-time bucket
  BoundedTokenBucketRegistry.java    TTL cleanup + hard entry cap
  AbuseProperties.java               validated rate/burst/state settings
  AbuseTelemetry.java                metrics + sampled safe logs
  PrivacySafeUserKey.java            process-salted one-way log key

server/src/main/java/com/poker/presence/
  UserSocketRegistry.java            atomic user<->STOMP-session indexes
  StompPresenceListener.java         connect/disconnect/session-close events
  RoomIdleCoordinator.java           guarded expiry scheduling
  RoomPresenceReconciler.java        socket + membership lifecycle reconciliation
  RoomIdleProperties.java            disconnected grace

server/src/main/java/com/poker/room/
  RoomManager.java                   atomic idle-close mutation
  RoomChange.java                    IDLE_CLOSED change and exit recipients

server/src/test/java/com/poker/hardening/
  TokenBucketTest.java
  BoundedTokenBucketRegistryTest.java
  CommandAdmissionIntegrationTest.java
  AbuseTelemetryTest.java

server/src/test/java/com/poker/presence/
  UserSocketRegistryTest.java
  RoomIdleCoordinatorTest.java
  RoomIdleIntegrationTest.java

server/src/test/java/com/poker/ws/
  MultiTabIntegrationTest.java
  MissingRoomRecoveryIntegrationTest.java

server/src/test/java/com/poker/load/
  CommandRateLoadTest.java            opt-in empirical tuning harness
```

Hardening code may depend on Micrometer, Spring AOP/messaging, and room
application ports, but never on mutable `PokerRoom` internals. `RoomManager`
remains the only registry/index mutation owner.

## Command admission design

Admission has two deliberate layers.

The first is `InboundFrameBudgetInterceptor`, ordered after T5 authentication
and active-session checks but before Jackson conversion. It admits at most 100
application `MESSAGE` frames/second with burst 200 per STOMP session and also
charges a user-wide coarse bucket when a principal is available. It uses only
message headers, destination, payload byte length, principal, and session ID;
it never parses JSON. Exceeding the coarse budget drops the frame, emits one
sampled transport-abuse signal, and closes the socket after three coarse
violations in 10 seconds. Since request correlation is unavailable before
decoding, this layer does not fabricate a `CommandAck`. Its purpose is to bound
parsing/validation work, including malformed and unknown-field floods.

The second layer uses a monotonic-time token bucket per authenticated
application UUID for successfully decoded application commands:

```text
sustained refill    10 tokens/second
burst capacity      20 tokens
cost                1 token per gameplay/mutation /app command
identity scope      application user UUID across all sessions/tabs
```

Defaults are configurable under `poker.abuse`; production can tune without a
build. A capacity of 20 permits normal reconnect/bootstrap bursts while the
10/s refill bounds sustained abuse. Timer/pacing callbacks generated inside
T6 are not client commands and do not consume tokens.

Every `@MessageMapping` method is annotated with
`@AdmittedCommand(CommandType.X)`. One `CommandAdmissionAspect` runs after
Spring has authenticated and validated/deserialized the strict payload but
before the controller body. It either invokes the handler or returns:

```json
{
  "requestId": "original-request-id",
  "command": "GAME_ACT",
  "ok": false,
  "error": "RATE_LIMITED",
  "retryAfterMs": 73
}
```

The socket remains open. Rejection does not publish a snapshot or mutate a
revision. `retryAfterMs` is clamped to 1–1000 and is advisory; clients must not
auto-replay gameplay actions because their `actionSeq` may become stale.

`STATE_SYNC`, `ROOM_SYNC`, and `ROOM_LEAVE` use a separate reserved control
bucket (2/s, burst 5) so a flooding gameplay tab cannot starve recovery or safe
leave. They still consume the coarse frame budget. All other decoded commands
use the shared 10/s bucket. Defaults and command-class assignments are
configuration-backed but validated as a coherent set.

The centralized aspect preserves request-ID/command correlation and avoids a
remember-to-wrap convention. An architecture test requires exactly one
`@AdmittedCommand` on every `@MessageMapping`, verifies its `CommandType`
matches the destination, and forbids controllers from injecting unrestricted
room/game command services except through the admitted application facade.
Malformed traffic is covered by the pre-conversion layer rather than relabeled
as a normal rate-limit ack.

## Bounded limiter state

`BoundedTokenBucketRegistry` uses a `ConcurrentHashMap<UUID, Entry>` with:

- monotonic `System.nanoTime` supplied through an injectable ticker;
- 10-minute idle entry expiry by default;
- a `ConcurrentLinkedQueue<UUID>` containing exactly one rotation node per live
  entry for fair bounded cleanup;
- cleanup on a low-frequency scheduler plus bounded opportunistic rotation;
- a configurable hard cap of 100,000 entries; and
- fail-closed admission for a new identity if the cap is reached and no expired
  entry can be reclaimed.

Existing authenticated users already in the map continue to use their bucket.
New-entry creation takes a short registry-only lock: it rechecks the map,
rotates a bounded cleanup batch, reserves one capacity slot, inserts the entry,
then enqueues its single rotation node. Removal and slot release use
compare-and-remove under the same lock, so concurrent unseen identities cannot
overshoot the cap or double-issue reclaimed slots. Existing-bucket consumption
never takes this registry lock.

The cleanup worker polls at most N queue nodes. A refreshed live entry is
re-enqueued once; an expired entry is compare-removed and releases its slot; a
stale node for an already removed/replaced entry is discarded. Thus every live
entry is revisited after at most the current queue length without full-map scans
or first-bucket starvation, and the queue remains bounded by live entries plus
transient nodes held by active sweepers. Cap rejections increment a distinct
metric. Bucket refill and consume are atomic per entry, and elapsed-time
arithmetic is saturating so ticker wrap/large pauses cannot mint excess tokens.

Rate limiting is application-level defense in depth, not a substitute for
edge/proxy connection or bandwidth controls.

## Abuse monitoring and tuning

Add `spring-boot-starter-actuator` for Micrometer integration, but expose only
the existing health endpoint by default. Metrics are available to the process's
configured registry; T9 does not create an unauthenticated metrics endpoint.

Record low-cardinality metrics:

```text
poker.stomp.commands          counter tags: command, outcome=accepted|limited
poker.stomp.frames            counter tags: outcome=accepted|coarse_limited|closed
poker.stomp.limit.retry       distribution summary (milliseconds)
poker.stomp.limiter.entries   gauge
poker.stomp.limiter.cap       counter
poker.rooms.idle.scheduled    counter
poker.rooms.idle.cancelled    counter
poker.rooms.idle.closed       counter
poker.rooms.active_sockets    gauge
```

Never tag metrics with user ID, room ID, request ID, session ID, join code, or
IP address. Those would create unbounded cardinality.

Limited-command logs are sampled per user/command window: log the first
rejection and then one summary per 100 rejections or 60 seconds. The user key
is the first 12 hex characters of SHA-256 over a random per-process salt and
UUID. Logs include command, safe user key, rejection count, and configured
limit, but never payloads, cards, cookies, tokens, join codes, raw UUIDs, or IPs.
Accepted commands are not individually logged.

The opt-in `CommandRateLoadTest` sends realistic reconnect bursts and abusive
sustained streams against a random-port server. It reports accepted/limited
counts and p50/p95/p99 admission latency; it does not assert machine-specific
throughput. The tuning gate asserts functional ratios:

- a 12-command reconnect burst is accepted at a fresh bucket;
- a sustained 10/s stream for 30 seconds is overwhelmingly accepted;
- a sustained 50/s stream is limited near the configured long-run budget after
  the initial burst; and
- a malformed 500/s stream is bounded before conversion and reaches the coarse
  repeated-violation close threshold; and
- admission p99 remains below 5 ms in the local in-process harness.

Any production limit change must include the load report and update the
configuration rationale; do not tune from a single anecdotal client failure.

## Presence and idle cleanup design

`UserSocketRegistry` listens to authenticated STOMP connect/disconnect events
and atomically maintains both `userId -> sessionIds` and
`simpSessionId -> userId`. `register(userId, sessionId)` and
`remove(sessionId)` return explicit `ZERO_TO_ONE`, `ONE_TO_ZERO`, or
`UNCHANGED` transitions. Reusing a live session ID for a different user is a
fail-closed invariant error. Duplicate events are no-ops. The reverse index lets
disconnect/session-destroy cleanup succeed even when the event lacks a usable
principal. Session expiration/logout close events feed the same removal path.

Presence includes liveness detection, not only graceful STOMP `DISCONNECT`.
The simple broker is configured with a dedicated `TaskScheduler` and 10-second
server/client heartbeats. Missing transport activity/heartbeats closes the
WebSocket within a 30-second target; the resulting disconnect removes the
session and can begin room grace. The idle clock starts when the server commits
the `ONE_TO_ZERO`/all-members-disconnected state, not when the network may have
physically failed.

On a transition from one to zero sockets for a user, `RoomIdleCoordinator`
checks that user's current room. When all current members have zero sockets it
asks `RoomManager` to increment a `presenceGeneration`, records
`allDisconnectedSince`, and returns an immutable schedule intent. The callback
captures `(roomId, presenceGeneration, dueAt)`.

On any member's zero-to-one transition, the coordinator increments the same
generation and clears `allDisconnectedSince`. The old scheduled handle may
still fire; `RoomManager.expireIdleRoom` no-ops unless:

- the room still exists;
- generation and due time match;
- every current member still has zero active sockets;
- disconnected duration has reached the configured grace; and
- T6 reports a safe hand boundary, or the bounded paused/failed operational
  close policy below is satisfied.

If the last condition is false, the room records `idleExpiryPending`. T6's
hand-boundary mutation returns a new immediate expiry intent. Cleanup never
interrupts a healthy running hand or paced result.

Presence callbacks follow the existing registry-before-room lock order.
Scheduler operations and event publication occur only after locks are released.
Room membership is re-read during every guarded callback so joins/leaves during
the grace cannot close the wrong membership set.

Socket transitions are not the only reconciliation triggers.
`RoomCommandService` passes the immutable committed `RoomChange` to
`RoomPresenceReconciler` after locks are released for create, join, leave,
last-member deletion, and idle close. The reconciler asks `UserSocketRegistry`
about the change's complete member set and calls one guarded `RoomManager`
presence mutation. A connected user joining an idle-pending room increments the
generation and clears the timer immediately. A connected member leaving a room
whose remaining members are all disconnected establishes `allDisconnectedSince`
at that leave commit and schedules grace even though no socket event occurred.
Reconciliation is idempotent and generation-checked, so socket and membership
events may arrive in either order.

Normal running hands still wait for T6's safe boundary. A `PAUSED` table is
given T6's bounded five-minute recovery window; a `FAILED` table, or a table
still paused after that window, may be atomically idle-closed once the 30-minute
disconnected grace has expired. This explicit operational exception can discard
only room-scoped in-memory play chips—there is no wallet or persisted balance—
and prevents an unrecoverable hand from leaking the room forever.

## Recovery and multi-tab verification

`MissingRoomRecoveryIntegrationTest`:

1. Create/join a room and save its ID in the client fixture.
2. start a fresh Spring application context (simulated process restart) with
   the same persisted user/session database but a new `RoomManager`;
3. reconnect and call `state.sync`; assert success with `room=null`;
4. call `room.sync(oldRoomId)`; assert session-local `ROOM_NOT_FOUND` and an
   otherwise usable connection; and
5. create a new room on that same socket to prove recovery is non-fatal.

`MultiTabIntegrationTest` opens two STOMP sessions for the same application
user and verifies:

- both receive room snapshots;
- only the initiating session receives each ack;
- same-room join is idempotent from the second tab;
- different-room join is rejected;
- both tabs consume the same user-level gameplay bucket;
- a gameplay flood in one tab does not consume the reserved sync/leave budget;
- simultaneous same-sequence game actions result in one commit; and
- disconnecting one tab does not begin room-idle grace while another remains.

## Failure behavior

- Limiter registry at capacity: fail closed for unseen users with
  `RATE_LIMITED`; keep existing entries functional and emit cap telemetry.
- Coarse pre-conversion budget exceeded: bound work without parsing the payload,
  sample one safe signal, and close only after the configured repeated-violation
  threshold; valid-command limiting alone never closes a healthy socket.
- Metrics registry unavailable/no-op: command correctness continues; telemetry
  calls must not throw into request handling.
- Duplicate/missing disconnect event: registry operations are idempotent; T5's
  session validity checks remain the authorization boundary.
- Scheduler rejection for idle cleanup: retry once through the coordinator,
  then log/metric an operational error. A low-frequency bounded sweeper also
  revisits overdue disconnected rooms so they are not leaked permanently.
- Reconnect racing expiry: both paths serialize through registry/room locks;
  generation decides the winner. A reconnect before committed deletion keeps
  the room, while reconnect after deletion observes no membership.
- Idle close publication failure: deletion remains committed; lobby/state sync
  is authoritative.
- Server restart: limiter, presence, rooms, and cleanup tasks reset together;
  no stale scheduled callback survives the process.

## Implementation sequence

1. Add validated hardening properties and `RATE_LIMITED` ack fields.
2. Implement/test token buckets, atomic-cap registry, and fair rotation cleanup.
3. Add the pre-conversion frame budget, declarative admission annotation/aspect,
   reserved control bucket, and executable controller coverage rules.
4. Add privacy-safe telemetry and opt-in load harness; record baseline.
5. Implement bidirectional socket registry, heartbeats, and idempotent listener.
6. Add socket- and membership-driven reconciliation, guarded scheduling, and
   atomic idle close.
7. Integrate T6 safe-boundary deferral.
8. Add missing-room and multi-tab integration tests.
9. Run full server suite and repeated concurrency/load checks.

## Acceptance criteria

- Valid sustained traffic near 10 commands/s remains usable; abusive sustained
  traffic receives correlated `RATE_LIMITED` acks without socket termination.
- Malformed/unknown-field floods are bounded before Jackson conversion and
  repeated coarse abuse is the only rate-policy path that closes a socket.
- Saturated gameplay traffic cannot starve reserved sync/leave recovery.
- Limiter memory and telemetry cardinality remain bounded.
- Metrics and sampled logs contain no sensitive or user-identifying raw values.
- A room is not expired while any member has a socket or while a healthy T6
  hand is mid-hand; only the documented bounded paused/failed exception applies.
- Ungraceful transport loss is detected by heartbeats; membership-only changes
  also start/cancel grace correctly.
- A continuously disconnected room expires once after grace and all indexes,
  epochs, and lobby revision remain consistent.
- Restart recovery returns `ROOM_NOT_FOUND` only to a command carrying the old
  room ID, while `state.sync` truthfully returns a null room.
- Multiple tabs are server-safe, share admission budget, receive fan-out state,
  and retain initiating-session-only acknowledgements.
