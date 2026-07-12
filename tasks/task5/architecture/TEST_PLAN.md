# T5 Test Plan — STOMP Realtime, Lobby, and Rooms

## Test strategy

Use three layers:

1. fast room-domain tests with deterministic join-code sources and clocks;
2. Spring slice/integration tests for security, controller mapping, and
   publication behavior; and
3. real WebSocket/STOMP integration tests against a random-port
   `@SpringBootTest` using authenticated HTTP session cookies.

Do not mock `RoomManager` in tests intended to prove atomicity or authorization.
Do not treat controller invocation without the messaging security channel as a
WebSocket security test.

## CSRF endpoint and HTTP handshake

Verify:

- authenticated `GET /api/csrf` returns 200, the configured header name, a
  nonblank token, no `XSRF-TOKEN` cookie, and `Cache-Control: no-store`;
- anonymous `GET /api/csrf` returns 401 with no login redirect;
- the response exposes no session ID or authentication fields;
- existing CSRF-protected `POST /logout` succeeds with the issued token and
  fails without one;
- anonymous `/ws` upgrade is rejected with 401;
- an authenticated session supplies the same application UUID as the STOMP
  principal; and
- allowed development origins succeed while an unlisted origin fails.

The test client must retain the exact `SESSION` cookie produced by T4 and must
not synthesize a principal header.

## Spring Session WebSocket lifecycle

Through the production Spring Session WebSocket integration, verify:

- an authenticated inbound STOMP frame updates the JDBC session's last-access
  time while outbound-only traffic does not;
- CSRF-protected `POST /logout` closes every socket opened with that exact
  `SESSION` cookie;
- after deleting or expiring the repository session, the next inbound frame
  emits the destruction path, closes every associated socket, and never reaches
  application code;
- a command attempted after destruction never reaches `RoomManager`; and
- socket closure does not remove room membership, release a seat, or increment
  room/lobby/membership revisions.

Use bounded event awaits and a short test configuration or direct repository
deletion; do not wait for the production session timeout or JDBC cleanup cron.

## STOMP inbound security

With a real inbound channel, verify:

- authenticated CONNECT with the issued CSRF native header succeeds;
- missing, blank, stale, and incorrect CSRF values reject CONNECT;
- CONNECT cannot replace identity using `login`, `passcode`, `user`, or custom
  headers;
- an unexpected authenticated principal type fails closed before room code;
- allowed MESSAGE destinations reach their controller;
- MESSAGE to `/topic/lobby`, `/queue/**`, `/user/**`, an unknown `/app/**`, and
  another user's destination is denied;
- allowed subscriptions succeed;
- an authenticated session can subscribe, unsubscribe its own subscription ID,
  and resubscribe without closing the connection;
- ACK/NACK and malformed or foreign subscription IDs are rejected without a
  room mutation;
- subscription to arbitrary room topics, another physical user queue, or an
  unknown broker destination is denied; and
- denied frames cause no manager invocation or revision change.

## Join code and input validation

Test `JoinCode` with:

- lowercase input canonicalized with `Locale.ROOT`;
- surrounding whitespace removed;
- every allowed character accepted;
- `0`, `1`, `I`, `O`, punctuation, internal whitespace, non-ASCII input, and
  lengths other than six rejected; and
- Turkish default locale unable to alter canonicalization.

Inject deterministic candidates to prove:

- the first unused code is selected;
- collisions retry without overwriting the existing code index;
- the 32nd candidate may succeed;
- 32 collisions return `JOIN_CODE_EXHAUSTED`; and
- exhaustion leaves room, code, user, and revision state unchanged.

Validate every command for null payloads, malformed UUIDs, missing request IDs,
unknown JSON fields under the dedicated strict STOMP mapper, invalid room names,
and seat bounds. Prove the strict setting applies to STOMP without changing the
application's HTTP mapper. Unicode room-name tests cover NFC, collapsed
whitespace, control/format/bidi characters, non-BMP code points, blank input,
and the 40 code-point boundary.

Transport-boundary tests verify the bound production defaults, then override
time limits with small deterministic test values where waiting is required.
They prove:

- a 16 KiB-or-smaller command reaches conversion while an oversized STOMP
  message closes before controller/domain invocation;
- a connection sending no first STOMP frame within 10 seconds is closed;
- slow-consumer sends are bounded by the 10-second send limit and 256 KiB
  per-session buffer; and
- boundary failures expose no raw payload or token in logs.

## Room creation and membership

Verify create:

- allocates distinct UUID/code values and revision 1;
- adds the creator as an unseated member using principal-owned profile data;
- rejects max seats outside 2–9 and invalid names before state mutation;
- rejects a creator already in another room; and
- never trusts identity-like fields injected as unknown payload properties.

Verify uncertain create recovery:

1. create commits;
2. the room snapshot enqueue and command ack delivery are both made unavailable;
3. the client reconnects without a room ID and sends `state.sync`; and
4. the session-local state result contains its authoritative membership epoch
   and current per-viewer room snapshot without creating a second room.

Verify join:

- canonical code lookup succeeds;
- adds an unseated member and increments room/lobby revisions once;
- caps total membership at `maxSeats`;
- returns `ROOM_NOT_FOUND` for an unknown code without revealing near matches;
- rejects membership in another room;
- repeated join of the same room is idempotent, republishes a current snapshot,
  and does not increment either revision; and
- an idempotent join does not silently refresh shared profile presentation data;
  leaving and rejoining captures the current safe principal fields.

Verify leave:

- releases a seat and membership in one mutation;
- removes the user index;
- republishes the remaining room and lobby state;
- sends `RoomExitNotice(LEFT)` to all sessions of the leaving user;
- repeating leave after success is idempotent and cannot affect another room;
- the last leave removes room ID, join code, and membership indexes atomically;
  and
- the removed join code becomes reusable only after deletion commits.

Every create/join/leave membership transition increments that user's checked
membership epoch exactly once. Seat, stand, room sync, and idempotent retries do
not change it.

## Seating

Verify:

- every seat `0..maxSeats-1` can be taken;
- negative and `maxSeats` indexes return `SEAT_OUT_OF_RANGE`;
- nonmembers cannot take or stand;
- two users racing for one seat produce one success and one `SEAT_OCCUPIED`;
- taking the same seat twice is idempotent without a revision;
- moving directly to another seat returns `ALREADY_SEATED`;
- standing clears both sides of the member/seat relationship;
- repeated stand is idempotent without a revision; and
- take/stand updates the lobby seated count and both snapshot revisions exactly
  once per committed visible change.

After every scenario, assert the bidirectional seat invariant rather than only
checking the returned error.

## Concurrency and atomicity

Use barriers/latches and repeated runs for:

- two users joining the final room slot;
- two users taking one seat;
- one user concurrently joining two different rooms;
- leave racing with take-seat;
- last-member leave racing with a same-room idempotent join/sync;
- join-code collision during concurrent room creation; and
- snapshot mapping racing with mutations.

For exclusive races—two users joining the final slot and two users taking one
seat—exactly one operation succeeds. For the other races, enumerate valid
linear histories and assert the observed responses and final state match one:

- take-seat then leave may produce two successes and an absent member; leave
  then take-seat produces leave success plus `NOT_A_ROOM_MEMBER`;
- sync/idempotent join before last leave may succeed before deletion, while the
  reverse order returns `ROOM_NOT_FOUND` (or the defined completed-leave result);
- concurrent creates that initially collide may both succeed with distinct
  retried codes; and
- one user racing to join two rooms has exactly one membership success.

After completion assert:

```text
each user appears in zero or one room
each code resolves to exactly its indexed room
each room ID resolves to one room
member.seat <-> seats[index] is bidirectionally equal
members <= maxSeats
revisions never decrease or duplicate committed visible mutations
all lock acquisition follows registry -> at most one room
```

Capture thousands of post-operation snapshots while multiple worker threads
mutate independent rooms. No snapshot may throw concurrent-modification errors,
contain half a seat relation, or expose a live mutable collection. A T6-shaped
test collaborator performs room-lock-only game mutations while membership
commands run; it must neither deadlock nor produce a projection combining old
membership with new game state.

## Snapshot mapping and revisions

Lobby tests verify deterministic room ordering, deterministic JSON fields,
member/seated counts, join-code presence, server UTC timestamps, and omission of
provider subjects, authorities, cookies, sessions, connection IDs, and internal
objects.

Room tests map the same aggregate for two viewers and verify:

- public room/member/seat data agrees;
- each `viewer` section describes only its target user;
- collection instances are immutable defensive copies;
- member ordering and complete seat-array ordering are deterministic;
- `game` is null in T5;
- no JPA, Spring Security, `PokerRoom`, or T3 trusted type is reachable from the
  DTO object graph; and
- serialized output contains none of synthetic provider subjects, authority
  strings beyond intended fields, CSRF/session tokens, or hidden fixture data.

The mapper accepts only immutable `RoomState`/`LobbyState` projections. Tests
must not obtain or pass a live `PokerRoom`, and the domain architecture test
forbids `com.poker.room -> com.poker.ws` dependencies.

Feed same-membership snapshots to a test reducer in room-revision order
`3, 5, 4, 5, 6` and assert that accepted state progresses `3, 5, 6`. Equal
revision is accepted only as a resync replacement, never interpreted as a new
mutation animation. Then force this cross-membership order: room A snapshot,
leave-A notice, room B snapshot, delayed room A snapshot, delayed leave-A
notice. Membership epoch ordering must retain room B. Also cover leave and
rejoin of the same room ID so room-ID comparison alone cannot pass the test.

## Ack and error mapping

For every command, assert exactly one ack with the original valid `requestId`
and stable `command` enum. Success carries committed revision metadata; failure
does not claim a revision it did not commit.

Verify:

- validation errors map to `INVALID_REQUEST` without exception prose;
- every `RoomError` maps to the intended public `ErrorCode`;
- unexpected exceptions map only to `INTERNAL_ERROR`;
- malformed JSON with no recoverable ID produces a null-ID error ack;
- an ack goes only to the originating STOMP session even when the user has two
  connected sessions; and
- room snapshots/notices fan out to all sessions for each affected user.

Capture logs for synthetic failures and assert they omit raw frames, payloads,
cookies, CSRF tokens, session IDs, and `CurrentUserPrincipal.toString()`.

## Publication behavior

With a fake `RoomEventSink`, prove the service receives an immutable,
transport-neutral change after commit and never publishes while a registry or
room lock is held.

With a failing publisher, verify:

- committed state and revisions remain committed;
- no automatic mutation retry occurs;
- failure of the first recipient enqueue does not skip remaining recipients or
  the lobby enqueue;
- a success ack is attempted with the committed revisions regardless of the
  publication report;
- failure of that ack still leaves `state.sync` able to return authoritative
  membership and room state; and
- the error is logged once with a server correlation ID.

Force revision N+1 to publish before N and verify the client-side reference
reducer retains N+1. Force an older membership epoch to arrive after a newer
one and verify the newer membership remains. This pins the rule that
membership epoch, then room revision—not cross-destination arrival order—
establishes freshness.

## Disconnect, reconnect, and GC

Using two authenticated STOMP clients:

1. create and seat a user;
2. close the socket without sending leave;
3. assert membership, seat, and revisions are unchanged;
4. reconnect with the same HTTP session and fresh CSRF token;
5. subscribe before sending `state.sync`; and
6. assert it returns the current membership epoch and per-viewer room state.

Also verify a second tab for the same account does not create duplicate
membership, receives room snapshot fan-out, and does not receive the first
tab's session-local ack.

GC tests prove:

- last explicit leave deletes the room and every index;
- no public operation or failure path commits an empty indexed room;
- no scheduled sweep exists to hide an empty-room invariant defect;
- nonempty rooms survive regardless of age or socket presence; and
- a deleted room returns `ROOM_NOT_FOUND` on sync.

## Full two-user STOMP scenario

Run one random-port integration scenario through the production security chain,
broker, controllers, manager, mapper, and publisher:

1. create two dev-authenticated HTTP sessions and obtain separate CSRF tokens;
2. connect both clients and subscribe to lobby, ack, room, and closed queues;
3. Alice creates a 2-seat room;
4. Bob joins with the canonicalized code;
5. both observe increasing lobby and viewer-specific room snapshots;
6. Alice takes seat 0 and Bob races for seat 0, receiving `SEAT_OCCUPIED`;
7. Bob takes seat 1, disconnects, reconnects, and uses `state.sync` without
   losing it;
8. Bob stands and leaves;
9. Alice leaves last; and
10. both observe the final lobby without the room and sync returns
    `ROOM_NOT_FOUND`.

Use bounded awaits with diagnostic state dumps; no fixed sleeps.

## Architecture and regression gates

`RoomArchitectureTest` verifies:

- `room` does not depend on `api`, `ws`, Spring messaging, Jackson, JPA
  entities, servlet APIs, or client code;
- `PokerRoom` is package-private and absent from public method signatures;
- `RoomManager` exposes only transport-neutral immutable changes/projections;
- `ws.protocol` DTOs do not reference room internals, Spring Security, or T3
  trusted types;
- controllers depend on `RoomCommandService`, not manager internals; and
- no generic `Map<String, Object>` protocol payload exists.

Run:

```powershell
npm test
```

The full existing T2 engine and T4 auth/session suites must remain green.
