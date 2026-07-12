# T5 Code Architecture — STOMP Realtime, Lobby, and Rooms

## Goal

Add the authenticated realtime boundary for the multiplayer app. T5 provides:

- a native WebSocket/STOMP endpoint at `/ws` backed by the authenticated HTTP
  session from T4;
- an explicit request/acknowledgement protocol for client commands;
- an in-memory lobby and room model with create, join, leave, take-seat, and
  stand operations;
- collision-safe human join codes and deterministic empty-room collection;
- revisioned lobby and per-viewer room snapshots; and
- reconnect-as-resync behavior without treating a transport disconnect as a
  room leave.

T5 is the transport and table-membership layer. It establishes the security,
concurrency, and DTO seams that T6 will use when it attaches `HoldemHand` to a
room and T7 will use when it adds the client STOMP store.

## Scope boundary

T5 owns WebSocket configuration, STOMP inbound authorization, CSRF delivery for
the SPA, command acknowledgement, room membership, seating, join codes, room
revisions, lobby summaries, and empty-room deletion.

T5 does not start hands, own chips or buy-ins, invoke the T3 engine, schedule
turn timers, expose cards, pace showdowns, implement client state management,
or add production rate limits. T6 owns gameplay and the hole-card redaction
boundary. T7 owns the functional STOMP client. T9 will harden rate limits and
multi-tab policy, but T5 already defines deterministic behavior that is safe
for multiple simultaneous connections belonging to one user.

Room state is intentionally in memory. Restarting the server removes every
room; authenticated user accounts and sessions remain database-backed. A
post-restart sync returns `ROOM_NOT_FOUND` for an old room identifier. T9 owns
the final client recovery UX for that case.

## Dependencies and package layout

Add `spring-security-messaging`; its version is managed by Spring Boot. Keep
using the simple in-process STOMP broker—no external broker or SockJS fallback
is introduced.

```text
server/src/main/java/com/poker/api/
  CsrfController.java              authenticated token materialization endpoint
  CsrfResponse.java                header name plus token value

server/src/main/java/com/poker/ws/
  WebSocketConfig.java             Spring Session integration, endpoint, broker
  PokerWebSocketProperties.java    validated origins and transport limits
  WebSocketSecurityConfig.java     inbound CONNECT/MESSAGE/SUBSCRIBE rules
  StompMessageConfig.java          strict STOMP-only Jackson converter
  ActiveWebSocketSessionInterceptor.java  fail-closed JDBC session validity guard
  WebSocketSessionLogoutHandler.java      JDBC logout-to-socket-close bridge
  CurrentStompUser.java            fail-closed CurrentUserPrincipal extraction
  RoomMessageController.java       STOMP destination adapter only
  StompErrorHandler.java           safe expected/unexpected ack mapping
  RoomSnapshotMapper.java          domain projection to wire allow-list mapper
  StompRoomEventSink.java          best-effort per-recipient STOMP adapter

server/src/main/java/com/poker/ws/protocol/
  CommandEnvelope.java             requestId plus validated payload
  CommandAck.java                  stable success/failure envelope
  CommandType.java                 stable command names
  ErrorCode.java                   stable client-visible rejection codes
  CreateRoomRequest.java
  JoinRoomRequest.java
  LeaveRoomRequest.java
  TakeSeatRequest.java
  StandRequest.java
  SyncStateRequest.java
  SyncRoomRequest.java
  StateSyncView.java
  LobbySnapshot.java
  LobbyRoomView.java
  RoomSnapshot.java
  RoomMemberView.java
  SeatView.java
  ViewerRoomState.java
  RoomExitNotice.java               clear stale room state in all user sessions

server/src/main/java/com/poker/room/
  RoomManager.java                 authoritative registry and membership index
  PokerRoom.java                   package-private mutable aggregate
  JoinCodeGenerator.java           injected secure/testable code generation
  JoinCode.java                    canonical validated value type
  RoomId.java                      UUID-backed value type
  RoomException.java               expected domain rejection
  RoomError.java                   domain-level rejection enum
  RoomChange.java                  immutable transport-neutral committed change
  RoomState.java                   immutable room projection captured under lock
  LobbyState.java                  immutable lobby projection captured under lock
  MemberState.java                 immutable member projection

server/src/main/java/com/poker/room/application/
  RoomCommandService.java          command transaction and publication ordering
  RoomEventSink.java               outbound port accepting domain projections
  PublicationReport.java           attempted/failed enqueue metadata

server/src/test/java/com/poker/ws/
  WebSocketSecurityIntegrationTest.java
  SessionWebSocketIntegrationTest.java
  RoomMessageControllerIntegrationTest.java
  StompErrorHandlerTest.java
  RoomSnapshotMapperTest.java

server/src/test/java/com/poker/room/
  JoinCodeTest.java
  RoomManagerTest.java
  RoomManagerConcurrencyTest.java
  RoomArchitectureTest.java

server/src/test/java/com/poker/room/application/
  RoomCommandServiceTest.java
```

Protocol DTOs never contain `PokerRoom`, JPA entities, Spring Security objects,
or T3 trusted snapshots. The `room` package contains no dependency on `ws`,
Spring messaging, Jackson, JPA, or servlet APIs. `RoomManager` captures
transport-neutral immutable states while holding the appropriate locks;
`RoomSnapshotMapper` converts those states to allow-listed wire DTOs in the
adapter layer. Controllers delegate immediately to `RoomCommandService`; they
do not reach into manager maps or room internals.

## WebSocket and STOMP configuration

`WebSocketConfig` extends Spring Session's
`AbstractSessionWebSocketMessageBrokerConfigurer<Session>` and enables the
message broker with this exact routing:

```text
handshake endpoint      /ws
application prefix      /app
simple broker prefixes  /topic, /queue
user prefix             /user
```

`/ws` is a native WebSocket endpoint. The Vite proxy already forwards it with
`ws: true`. SockJS is excluded because current target browsers support native
WebSockets and a second transport would multiply authentication, CSRF, and test
paths without product value.

The Spring Session WebSocket integration installs its handshake/inbound
interceptor, connect-handler decorator, and registry listener. Authenticated
inbound STOMP activity refreshes the JDBC-backed session's last-access time.
Because the JDBC repository does not publish deletion events itself, logout
explicitly publishes the event that immediately closes every WebSocket tied to
that HTTP session. A fail-closed inbound guard detects externally deleted or
expired sessions before their next frame reaches application code, publishes
the same close event, and rejects the frame. Room membership survives the
resulting transport disconnect. A socket must never remain an authorization
capability after its session has ended.

Configure the native STOMP transport through validated
`PokerWebSocketProperties` with these production defaults rather than
inheriting framework defaults:

```text
maximum inbound STOMP message   16 KiB
maximum outbound send buffer    256 KiB per session
send time limit                 10 seconds
time to first STOMP frame       10 seconds
```

T5 commands are intentionally small, so exceeding the inbound limit closes the
connection before JSON/domain work. The outbound buffer leaves headroom for a
full T6 room snapshot while bounding a slow consumer. T9 may tune these values
from observed metrics but cannot remove the bounds.

The HTTP security chain continues to require authentication for `/ws` and
`/ws/**`. Spring copies the authenticated HTTP `Principal` into the WebSocket
session during the upgrade. No STOMP frame may provide or override a user ID,
name, session ID, or authorization bearer token.

`CurrentStompUser` accepts the message `Principal` only when it is an
authenticated `Authentication` whose principal implements
`CurrentUserPrincipal`. It returns the T4 application UUID and safe profile
fields. An absent, anonymous, or unexpected principal is rejected before a
room service call. `Principal.getName()` remains the UUID string used by Spring
user destinations.

## CSRF and inbound authorization

T5 completes the SPA CSRF decision deferred by T4. Use
`HttpSessionCsrfTokenRepository`; the authenticated JSON endpoint, rather than
a script-readable cookie, is the single client delivery mechanism. Add
authenticated `GET /api/csrf`, returning:

```json
{
  "headerName": "X-XSRF-TOKEN",
  "token": "materialized-token-value"
}
```

The endpoint materializes the session token and sets `Cache-Control: no-store`.
It does not create an `XSRF-TOKEN` cookie. The client fetches it immediately
before activating STOMP and sends the returned name/value as a STOMP `CONNECT`
native header. It keeps the token in memory only. This same repository supports
future browser `POST /logout`; the token is not authentication and must never
replace the session cookie.

Use Spring Security Messaging's CSRF interceptor and authorization manager.
The allow-list is:

```text
CONNECT       authenticated and valid CSRF token
DISCONNECT    authenticated connection
UNSUBSCRIBE   authenticated connection; session-owned subscription ID only
MESSAGE       /app/state.sync
MESSAGE       /app/room.create
MESSAGE       /app/room.join
MESSAGE       /app/room.leave
MESSAGE       /app/room.sync
MESSAGE       /app/room.take-seat
MESSAGE       /app/room.stand
SUBSCRIBE     /topic/lobby
SUBSCRIBE     /user/queue/acks
SUBSCRIBE     /user/queue/room
SUBSCRIBE     /user/queue/room-events
```

ACK and NACK are denied because server subscriptions use automatic
acknowledgement; all other inbound frame types and destinations are denied. In
particular,
clients cannot `MESSAGE` broker destinations, subscribe to another user's
physical queue, select arbitrary `/topic/room/{id}` paths, or spoof a `/user`
destination. The simple broker is outbound-only from the application's point
of view.

The WebSocket origin policy uses an explicit configurable same-origin allowlist
rather than `*`. Development allows `http://127.0.0.1:5173` and
`http://localhost:5173`; deployment supplies its HTTPS origin. Origin checking
is defense in depth beside session authentication and CONNECT CSRF.

## Command and acknowledgement protocol

Every application command uses a JSON envelope:

```json
{
  "requestId": "7dc9e0a5-a230-4eb5-b45f-8d668e8cb788",
  "payload": {}
}
```

`requestId` is a canonical UUID string. It is generated by the client, limited
to 36 characters, used only for correlation, and never used as authority. Each
command has a concrete payload record; generic maps are forbidden.

STOMP uses a dedicated `MappingJackson2MessageConverter` built from a copy of
the application mapper with `FAIL_ON_UNKNOWN_PROPERTIES` enabled. Every
`CommandEnvelope<T>` validates a nonnull request ID and cascades `@Valid` into a
nonnull concrete payload. This strict policy prevents ignored identity-like
fields from creating a false impression that the server honored them; future
protocol evolution adds explicit optional fields instead.

The controller returns one session-local acknowledgement to
`/user/queue/acks`. Use `@SendToUser(broadcast = false)` so another tab for the
same account does not receive the command's ack:

```json
{
  "requestId": "7dc9e0a5-a230-4eb5-b45f-8d668e8cb788",
  "command": "ROOM_JOIN",
  "ok": true,
  "error": null,
  "roomId": "8fe16d5f-9248-4a50-9e1b-2a688fd47c66",
  "roomRevision": 4,
  "lobbyRevision": 12
}
```

Failures use the same shape with `ok: false`, nullable revision fields, and a
stable `error` enum. Exception messages and stack traces are never sent:

```text
INVALID_REQUEST
UNAUTHENTICATED
ROOM_NOT_FOUND
ROOM_FULL
ALREADY_IN_ANOTHER_ROOM
NOT_A_ROOM_MEMBER
SEAT_OUT_OF_RANGE
SEAT_OCCUPIED
ALREADY_SEATED
NOT_SEATED
JOIN_CODE_EXHAUSTED
INTERNAL_ERROR
```

Malformed JSON can lack a usable request ID; its ack has `requestId: null`.
Expected validation/domain failures are logged at debug level without the full
payload. Unexpected failures get a generated server correlation ID, are logged
once, and return only `INTERNAL_ERROR`.

An ack states whether the command committed. Snapshots are separate messages
and may arrive before or after the ack. The client must correlate commands by
`requestId` and accept snapshots by revision, not assume cross-destination
ordering. STOMP's in-session ordering is not elevated into a distributed
transaction guarantee.

T5 does not add a request-ID replay cache. The simple broker does not redeliver
application messages, and naturally retryable mutations below have defined
idempotent behavior. A client can still lose an ack after commit, so
`state.sync` is the authoritative uncertain-outcome recovery path, including
for non-idempotent create. Production replay and rate controls belong to T9.

## Commands and deterministic semantics

### `/app/state.sync`

Payload is empty. The session-local success ack contains a `state` field with
an authoritative `StateSyncView`: the current `LobbySnapshot`, the viewer's
`membershipEpoch`, and a nullable current per-viewer `RoomSnapshot`. The
manager captures this state consistently under the registry lock followed, if
the user is a member, by that room's lock. Sync never increments a revision or
publishes to a topic/user queue, so one user's reconnect does not fan out.

This is the bootstrap, reconnect, page-refresh, and uncertain-command-outcome
recovery path. In particular, if create committed but both its normal room
publication and ack were lost, retrying `state.sync` returns the created room
without requiring the client to know its ID. Mutation acks never embed
snapshots.

### `/app/room.create`

Payload contains `name` and `maxSeats`. Room names use the T4 display-name
Unicode safety rules (NFC, collapsed whitespace, no control/format/separator
characters), with 1–40 code points. `maxSeats` is 2–9.

The creator must not already belong to a room. Creation atomically allocates a
room UUID and unique join code, creates membership, and leaves the creator
unseated. Success publishes a per-viewer room snapshot to all of the creator's
connections and a lobby snapshot, then returns the session-local ack.

### `/app/room.join`

Payload contains a join code. Codes are canonicalized before lookup. A user in
another room gets `ALREADY_IN_ANOTHER_ROOM`. A user already in this room gets
idempotent success plus a fresh snapshot without changing revisions. Otherwise
the room must have fewer members than `maxSeats`; joining adds an unseated
member, increments room and lobby revisions, and publishes both snapshots.

Membership count is capped at `maxSeats`; T5 does not model spectators. A seat
is not automatically assigned because clients need an explicit take/stand UX.

### `/app/room.leave`

Payload contains `roomId`. Leaving removes the member and releases their seat
atomically. Repeating leave after a successful leave is idempotent success when
the supplied room no longer exists or the user is not indexed in any room; it
does not permit affecting another room. Remaining members receive a new room
snapshot. The leaving user's connections receive `RoomExitNotice` with
reason `LEFT` so stale tabs clear room state.

If the last member leaves, `RoomManager` removes the room, join-code index, and
user index in the same critical section. The lobby revision increments once.
No empty room is externally observable.

### `/app/room.sync`

Payload contains `roomId`. It never mutates or increments revisions. A current
member receives a fresh per-viewer snapshot on all of their user sessions; a
nonmember gets `NOT_A_ROOM_MEMBER`, and a missing room gets `ROOM_NOT_FOUND`.
This is a targeted refresh once the client knows a room ID; `state.sync` is
used when membership or the outcome of a prior command is uncertain.

### `/app/room.take-seat`

Payload contains `roomId` and `seat` in `0..maxSeats-1`. Only a member may take
a seat. Taking the same seat again is idempotent success without a revision.
Taking a different seat while already seated gets `ALREADY_SEATED`; the user
must stand first. An occupied seat gets `SEAT_OCCUPIED`. Success increments the
room and lobby revisions and republishes snapshots.

### `/app/room.stand`

Payload contains `roomId`. Only a seated member may stand. A member already
standing gets idempotent success without a revision. Success clears the seat,
increments room and lobby revisions, and republishes snapshots. T6 may later
reject stand during a hand or queue it for the next safe boundary without
changing the wire command.

## Room aggregate and invariants

`PokerRoom` is mutable, package-private, and never escapes `room`. It stores:

```text
RoomId id
JoinCode joinCode
String name
int maxSeats
Instant createdAt
long revision
Map<UUID, Member> members
UUID[maxSeats] seats
ReentrantLock lock
```

Each member stores the immutable-at-join safe profile projection (`userId`,
`displayName`, nullable `avatarUrl`), `joinedAt`, and an optional seat. Profile
data comes only from `CurrentUserPrincipal`, never from a command. Sync does not
silently rewrite this shared presentation state; a later profile-refresh
feature must define its own revisioned mutation. Leaving and rejoining captures
the then-current safe profile.

Invariants checked in tests and with package-private assertions are:

- every room has 1–9 members and `members.size() <= maxSeats`;
- each member UUID occurs once and belongs to at most one room globally;
- every occupied seat refers to exactly one member in that room;
- a member's optional seat and the seat array are bidirectionally equal;
- no two rooms share an ID or join code;
- every user's membership epoch is nonnegative and increments exactly once on
  each join, create-membership, or leave transition;
- room revisions are positive and lobby revisions are nonnegative, monotonic
  signed `long` values; and
- every mutation either commits all relevant indexes or changes nothing.

Room, lobby, and membership-epoch increments use `Math.incrementExact`;
overflow is an invariant-fatal server error, never a wrapped negative client
value. `RoomManager` retains a process-lifetime epoch counter for each user it
has seen, including while that user belongs to no room. Server restart resets
these in-memory epochs; a fresh WebSocket connection must bootstrap with
`state.sync` and replace its prior process state.

## Join-code generation

`JoinCode` is six characters from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`, excluding
ambiguous `0`, `1`, `I`, and `O`. Input is trimmed, uppercased with
`Locale.ROOT`, and then validated against that exact alphabet.

Production generation uses `SecureRandom`; tests inject a deterministic source.
`RoomManager` checks the join-code index while holding its registry lock and
retries at most 32 candidates. Exhaustion changes no map and returns
`JOIN_CODE_EXHAUSTED`. Join codes are discovery handles, not secrets, and may
appear in the authenticated lobby snapshot.

## Concurrency, mutation, and publication

`RoomManager` owns one non-fair registry `ReentrantLock` protecting the three
cross-room indexes (`roomId`, `joinCode`, `userId`), immutable lobby-summary
entries keyed by room ID, lobby revision, and per-user membership epochs. Room
membership/seating mutations update the affected cached lobby entry while they
hold registry then room; lobby capture reads only the registry-owned immutable
entries and never locks every room. Each `PokerRoom` owns one non-fair room lock
protecting its members, seats, revision, and future T6 game aggregate. The
single legal nested order is **registry lock, then at most one room lock**.
Code holding a room lock must never acquire the registry lock. T6 game actions
and timers acquire only the room lock; membership-changing commands acquire
registry then room because they change both global indexes and room/game
eligibility. No operation ever holds two room locks.

This ordering lets independent game actions avoid global serialization while
keeping one-room-per-user atomic. State sync captures the lobby and membership
lookup under registry, then the current room projection before releasing the
nested room lock. No
network send, wire mapping, JSON serialization, log formatting, or Spring
callback occurs while either lock is held.

Each operation validates all fallible input first, acquires the required locks
in the fixed order, rechecks state-dependent preconditions, mutates atomically,
checks invariants, and returns an immutable transport-neutral `RoomChange`.
The change contains defensive `RoomState`/`LobbyState` projections captured
under lock, affected user IDs with membership epochs, and committed revisions.
It contains no live room reference, mutable collection, WebSocket DTO, or
Spring type.

`RoomCommandService` is the command boundary. It invokes the manager and then
hands the immutable result to the `RoomEventSink` outbound port. The STOMP
adapter maps domain projections to wire DTOs outside all locks and attempts
every independent room-recipient and lobby enqueue even if an earlier enqueue
throws. It returns a `PublicationReport`; synchronous enqueue failures are
logged once under one server correlation ID.

A publication failure does not roll back committed room state and does not
change the command result. The controller constructs and returns the committed
success ack even when the report contains failures. If sending that ack also
fails, the state remains committed and the client recovers with `state.sync`.
This is best-effort, at-most-once publication to the in-process broker—not a
claim of durable delivery after the broker accepts an enqueue.

Concurrent service threads may publish two committed revisions out of order.
Every room snapshot therefore carries both the viewer's `membershipEpoch` and
the room revision. Consumers first compare membership epoch, then compare room
revision within the same epoch; a greater tuple replaces state and an equal
tuple is a valid resync duplicate. This rule is mandatory for T7. Lobby state
uses its independent lobby revision.

## Snapshot contracts and privacy seam

`LobbySnapshot` contains `revision`, `serverNow`, and rooms sorted by
`createdAt`, then UUID. Each `LobbyRoomView` contains:

```text
roomId, joinCode, name, maxSeats, memberCount, seatedCount, createdAt
```

It contains no session identifiers or provider subjects. The lobby starts at
revision 0. Lobby revision changes
only when a visible lobby field changes: create, join, leave, take-seat, stand,
or room deletion.

`RoomSnapshot` contains:

```text
roomId, joinCode, name, maxSeats, membershipEpoch, revision, serverNow
members[] sorted by joinedAt then UUID
seats[] with one entry for each 0..maxSeats-1
viewer { userId, member, seated, seat, canTakeSeat, canStand }
game null
```

Public member views contain application UUID, display name, avatar URL,
joined-at time, and optional seat. They never contain auth provider, provider
subject, authorities, session ID, or connection IDs. `game` is reserved and
null in T5; T6 replaces it with an explicit redacted game DTO.

Although T5 has no cards, snapshots are mapped independently for each viewer
now. `RoomSnapshotMapper.forViewer(roomState, viewerId, membershipEpoch, now)`
is the only path to a room wire object. It receives only immutable domain
projections, never a live `PokerRoom`. T6 must extend this allow-list mapper (or
a dedicated `Redactor` called by it), never attach trusted engine objects to
the DTO.

Room snapshots publish through `/user/queue/room`, not a shared room topic.
`convertAndSendToUser(userId.toString(), ...)` intentionally reaches all active
STOMP sessions for that application user, keeping tabs consistent. Command
acks remain session-local.

`RoomExitNotice` contains `roomId`, reason, the post-leave
`membershipEpoch`, and `serverNow`. A client applies it only when its epoch is
greater than the current membership epoch; lower epochs are stale and equal
epochs are idempotent duplicates. This prevents a delayed exit from room A
from clearing a later membership in room B—or a later rejoin of room A.

## Disconnect, reconnect, and empty-room GC

A WebSocket disconnect is transport loss, not user intent. It does not leave a
room, release a seat, increment a revision, or broadcast presence. This covers
refreshes, laptop sleep, network changes, and automatic client reconnect.

After reconnect, the client subscribes first and calls `state.sync`. It replaces
its prior-process membership state with the returned epoch and nullable room,
then resumes revision filtering. It may call `room.sync` later for a targeted
refresh. The server re-authorizes both from the current session identity. A
room ID is a locator, not proof of membership.

T5 GC is deterministic empty-room collection: the last explicit leave removes
the room and all indexes atomically. No scheduled defensive sweep is added:
public operations never commit an empty room, so such a sweep would be dead
code masking an invariant defect. T5 never expires a nonempty room based on
socket absence or wall-clock idleness. A future idle-room policy requires a
separate product decision and user-visible warning.

## Failure behavior

- Unauthenticated upgrade: HTTP 401; no WebSocket session.
- Cross-origin upgrade outside the allowlist: handshake rejected.
- Missing/invalid CSRF on STOMP CONNECT: connection rejected before commands.
- Logout, session deletion, or session expiry: every socket for that HTTP
  session is closed before it can process another command; room membership is
  unchanged.
- Unauthorized destination: frame denied and connection closed; no room call.
- Invalid payload: `INVALID_REQUEST`; no mutation or revision increment.
- Oversized inbound frame or missing first STOMP frame: transport closed before
  controller/domain invocation.
- Expected room race: responses and final state match one valid serial history;
  genuinely exclusive races have one winner, while compatible operations may
  both commit in lock order.
- Broker publication failure after commit: all independent enqueues continue,
  success ack is attempted, one correlated error is logged, and recovery uses
  `state.sync`; no mutation replay.
- Unexpected internal failure before commit: `INTERNAL_ERROR`; no partial index
  mutation.
- Server restart: all rooms gone; fresh `state.sync` returns no membership and
  targeted `room.sync` for an old ID reports `ROOM_NOT_FOUND`.

Logs may include request ID, safe command type, application UUID, room UUID,
and error enum. They must not include CSRF tokens, cookies, raw frames, complete
payloads, session IDs, join-code random generator state, or principal objects.

## Implementation sequence

1. Add `spring-security-messaging`, the session CSRF repository, the no-store
   `/api/csrf` endpoint, and focused HTTP security tests.
2. Add Spring Session WebSocket integration, broker/transport limits, the
   strict STOMP Jackson converter, and deny-by-default inbound security rules;
   verify handshake identity, CONNECT CSRF, session-destruction closure, and
   subscribe/unsubscribe lifecycle.
3. Add protocol value types, join-code generation, room aggregate,
   transport-neutral projections, and `RoomManager` with fixed lock-order,
   invariant, membership-epoch, and concurrency tests.
4. Add the `RoomEventSink` port, STOMP adapter, snapshot mapper, and
   `RoomCommandService`, including per-recipient publication-failure semantics.
5. Add STOMP controllers and safe error mapping for state-sync/create/join/leave/
   take-seat/stand.
6. Add end-to-end STOMP integration tests with two authenticated sessions,
   session-local acks, per-user fan-out, reconnect sync, and room deletion.
7. Run the complete Maven suite and an architecture dependency test before
   handing T5 to code implementation.

## Acceptance criteria

- Only a T4-authenticated HTTP session can upgrade `/ws`; STOMP CONNECT also
  requires the issued CSRF token.
- Inbound activity refreshes the Spring Session, and logout/expiry closes all
  sockets for that session without treating disconnect as room leave.
- Inbound destinations are allow-listed and broker destinations cannot be
  written by clients.
- Create, join, leave, sync, take-seat, and stand have stable correlated acks
  and atomic room behavior.
- `state.sync` recovers authoritative membership and room state after reconnect
  or an uncertain committed command outcome without a caller-known room ID.
- Application UUID from `CurrentUserPrincipal` is the sole identity authority;
  payload user fields do not exist.
- Join codes are normalized, collision-checked, securely generated, and fully
  cleaned up with their room.
- A user belongs to at most one room, each seat has at most one occupant, and
  concurrent races preserve every index invariant.
- Registry-then-room is the only nested lock order; T6 actions use the room lock
  only, and snapshots capture membership plus room/game state consistently.
- Lobby and per-viewer room snapshots are immutable, explicitly mapped,
  revisioned, and recoverable through sync.
- Room snapshots use user queues so T6 can safely add viewer-specific cards;
  no internal or trusted engine object crosses the serializer boundary.
- Disconnect does not mutate membership; the last explicit leave deletes the
  room atomically.
- Membership epochs prevent reordered snapshots or exit notices from clearing
  a newer room membership.
- STOMP frame/buffer/time limits and strict command JSON validation are explicit;
  T9 owns rate limiting and abuse policy, not structural payload validation.
- Focused unit/concurrency/integration tests and the full existing Maven suite
  pass without weakening T2–T4 architecture boundaries.
