# T9 Architecture Test Plan

## Token bucket unit tests

Use a fake monotonic ticker. Cover:

- fresh burst accepts exactly the configured capacity;
- the next request is limited with a bounded positive `retryAfterMs`;
- fractional refill over time and sustained 10/s behavior;
- long pauses cap at burst capacity rather than accumulating unlimited tokens;
- clock edge/overflow handling does not add tokens incorrectly;
- concurrent consumes never accept more than available tokens; and
- two session IDs for one UUID resolve to the same entry.

`BoundedTokenBucketRegistryTest` verifies idle TTL removal, bounded incremental
cleanup, hard-cap fail-closed behavior for unseen UUIDs, continued service for
existing entries, and no full-map scan on command paths. A barrier-started
many-thread first-use test proves the map and reserved-slot count never exceed
the hard cap. Concurrent expiry/replacement proves a slot is released and
issued once. A fair-rotation test continuously refreshes hot entries while every
expired cold entry is eventually visited and removed; queue nodes remain
bounded relative to live entries.

## Admission integration tests

With a small test limit (2/s, burst 2), connect an authenticated STOMP client
and send three valid commands. Assert two reach a mocked command service, the
third returns `RATE_LIMITED` with original request ID/command and no mutation,
publication, or disconnect. Advance the fake ticker and assert admission
recovers.

Enumerate all controller `@MessageMapping` methods and require exactly one
matching `@AdmittedCommand`; reject direct unrestricted command-service fields
in controllers. Include T5 room commands and all T6 gameplay commands.

Send malformed JSON and unknown-field frames below the 16 KiB cap at high rate.
Prove the coarse interceptor bounds frames reaching Jackson, emits no fabricated
correlated ack, samples telemetry, and closes only after three coarse violations
in the configured window. A single coarse rejection does not close the socket.
Oversized frames remain covered by T5 transport tests.

Saturate the shared gameplay bucket from tab A while tab B performs
`state.sync`, `room.sync`, and `room.leave` through the reserved control bucket.
Recovery succeeds within its own limit; flooding control commands is still
bounded and returns correlated `RATE_LIMITED` acks.

## Telemetry tests

- Accepted/limited counters use only command/outcome tags.
- Entry gauge and cap counter reflect registry changes.
- A scan of all meter IDs rejects user/room/request/session identifiers.
- Sampled logging emits first rejection and periodic summary, not every event.
- Captured logs contain no raw UUID, join code, request payload, card code,
  cookie, CSRF token, or session ID.
- A no-op/throwing test meter adapter cannot break command admission.

## Presence and idle policy tests

Use manual clock/scheduler and fake socket registry:

1. One of two tabs disconnects: no idle schedule.
2. Last socket for one member disconnects while another member remains online:
   no idle schedule.
3. Last socket for every member disconnects: one schedule at `now + grace`.
4. A member reconnects before due time: old callback no-ops by generation.
5. Callback before due time: no deletion and a correct reschedule intent.
6. Callback at due time on an idle safe room: one atomic deletion.
7. Callback at due time mid-hand: mark expiry pending; do not interrupt.
8. T6 safe boundary after pending expiry: close immediately and once.
9. Membership changes during grace: callback re-evaluates current members.
10. Duplicate disconnect/connect/callback events are idempotent.
11. Scheduler rejection is recovered by the overdue-room sweeper.
12. Last-member explicit leave still deletes immediately through T5, independent
    of idle policy.
13. A connected member leaves while every remaining member is disconnected:
    grace starts at the leave commit without a socket transition.
14. A connected user joins an idle-pending room: generation increments and the
    pending expiry is cleared immediately.
15. Join/leave racing socket zero/one events produce one state consistent with a
    serial ordering and never retain a stale due time.
16. Disconnect without a principal removes through `sessionId -> userId`;
    duplicate/reordered connect, disconnect, and session-destroy events preserve
    both socket indexes.
17. Simulated missed heartbeats cause server-side transport close, then start
    grace from the committed last-socket removal.
18. A paused table gets its five-minute recovery window; a failed or
    continuously paused fully disconnected room closes once after room grace,
    while a healthy mid-hand room still waits for its boundary.

For deletion assert room ID, join code, user membership indexes, and lobby entry
are all absent; membership epochs increment once per member; lobby revision
increments once; exit notices use `IDLE_TIMEOUT`.

## Multi-tab integration test

Open two authenticated STOMP connections for the same application UUID:

- subscribe both to room snapshots and separate session-local ack collectors;
- create/join from tab A, idempotently join/sync from tab B;
- prove both receive authoritative snapshots but only sender receives ack;
- exhaust the shared test bucket by alternating tabs and observe the combined
  limit rather than one budget per socket;
- saturate gameplay on A and prove B's reserved sync/leave budget remains usable;
- race identical T6 actions carrying one `actionSeq` and prove one commit;
- disconnect A and prove B keeps the room non-idle; and
- reconnect A without membership or seat duplication.

Repeat with different application UUIDs to prove buckets do not interfere.

## Restart/missing-room integration test

Use a persistent test datasource and two application contexts:

1. Context A authenticates a user and creates a room; retain room ID client-side.
2. Stop A and start B with a fresh in-memory `RoomManager`.
3. Reauthenticate/reconnect the same user.
4. `state.sync` must succeed with `room=null`.
5. `room.sync(oldRoomId)` must return `ROOM_NOT_FOUND` to its initiating session.
6. The socket remains usable and can create/join a new room.

This prevents the impossible contract of asking a fresh server to infer an old
room ID from `state.sync`.

## Empirical tuning harness

Run the opt-in load test in three profiles after a warm-up:

```text
normal       10 users, reconnect burst 12, then 5 commands/s each
boundary     50 users, 10 commands/s each for 30s
abusive      10 users, 50 commands/s each for 30s
malformed    10 sessions, invalid 8 KiB frames at 500 frames/s each
```

Record accepted, limited, unexpected errors, disconnects, and admission
p50/p95/p99. Functional gates:

- normal reconnect burst fits fresh capacity;
- boundary traffic is overwhelmingly accepted after scheduler jitter;
- abusive steady-state acceptance converges near 10/s per user plus initial
  burst allowance;
- no connection closes solely because a valid decoded command was limited by
  the user command bucket;
- malformed work reaching Jackson stays within the coarse budget and repeated
  coarse violators close without affecting other sessions;
- reserved control commands remain usable during gameplay saturation;
- limiter entry count returns toward baseline after TTL cleanup; and
- in-process admission p99 is below 5 ms on the development machine.

The report is diagnostic and checked into implementation notes; absolute
throughput is not a portable CI assertion.

## Full-suite gate

From repository root:

```powershell
npm test
```

Acceptance requires existing T1–T6 tests, hardening tests, multi-tab tests, and
restart recovery tests to pass. Repeat concurrency tests enough times to expose
lock/generation races. Run the empirical harness separately and retain its
configuration/results before changing production defaults.
