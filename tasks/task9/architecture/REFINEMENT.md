# T9 Architecture Refinement Record

## Refinements applied

| Ambiguity | Final architecture decision |
|---|---|
| “~10/s” scope | Token bucket per authenticated application UUID, shared across tabs; 10/s refill, burst 20, one token per valid `/app` command. |
| How clients learn they were limited | Normal correlated `RATE_LIMITED` ack with advisory retry delay; do not close the socket or auto-replay actions. |
| Limiter-map growth | Idle TTL, bounded incremental cleanup, hard entry cap, fail closed only for unseen identities at cap. |
| Abuse monitoring | Low-cardinality Micrometer metrics plus sampled process-salted user keys; never payloads or raw identifiers. |
| How limits are tuned | Repeatable normal/boundary/abusive load profiles and recorded ratios/latency, not intuition. |
| “Idle room” meaning | Every member continuously has zero active STOMP sockets for 30 minutes; connected clients are not surprise-closed. |
| Idle expiry during a hand | Defer healthy running/paced hands to a T6 safe boundary; after bounded recovery, paused/failed tables may close so they cannot leak forever. |
| Reconnect versus expiry race | Presence generation under room lock decides; scheduled-task cancellation is only resource cleanup. |
| Kill-server `ROOM_NOT_FOUND` | `state.sync` returns null room; client then sends `room.sync(oldRoomId)` to receive the missing-room ack. |
| Multi-tab ownership | Server supports all sockets; session-local acks, user-wide snapshots and rate budget, T6 sequence guard for racing actions. |
| Existing T5 hardening | Keep strict structural DTO validation, 16 KiB inbound frames, subscription ownership, and atomic last-member GC in T5; test, do not duplicate. |
| Malformed frames bypass decoded-command limits | Add a coarse pre-conversion session/user frame budget; repeated coarse abuse may close only that socket. |
| Per-controller wrapper was bypass-prone | Require one `@AdmittedCommand` per mapping and enforce it with a centralized aspect plus architecture rules. |
| Gameplay flood could starve recovery | Give state sync, room sync, and leave a separate bounded control bucket. |
| Hard cap raced concurrent first use | Reserve/release registry slots under a short creation lock; existing buckets remain independently concurrent. |
| Bounded cleanup could starve entries | Rotate one queue node per live identity so every entry is revisited fairly without full scans. |
| Socket-only reconciliation missed joins/leaves | Reconcile complete committed membership after every room membership mutation as well as socket zero/one transitions. |
| Disconnect may lack a principal | Maintain atomic user-to-sessions and session-to-user indexes and remove by session ID. |
| Half-open sockets defeated idle policy | Configure broker heartbeats and start grace only after server-side liveness closure commits. |
| Paused hand could block GC forever | Honor T6 recovery, then allow disconnected paused/failed rooms to close after the bounded policy window. |
| T10 wire contract drift | Standardize `/user/queue/room-events` `RoomExitNotice(IDLE_TIMEOUT)` and `state.sync -> null`, followed by `room.sync(oldId) -> ROOM_NOT_FOUND`. |

## Cross-task reconciliation required during implementation

- T10's kill-server test must retain the cached room ID and issue `room.sync`
  after successful reconnect/bootstrap; expecting `ROOM_NOT_FOUND` directly
  from `state.sync` is not implementable on a fresh process.
- T10 handles idle closure through the existing `/user/queue/room-events`
  `RoomExitNotice` with reason `IDLE_TIMEOUT`; there is no separate
  `room.closed` destination or payload.
- T10's client-only `BroadcastChannel` takeover is compatible with the chosen
  server policy. Both server sockets may stay connected; client action locking
  is UX, while T6 `actionSeq` remains the correctness guard.
- T6 internal timeout/pacing callbacks bypass command admission. Only external
  authenticated `/app` messages consume user tokens.
- T7's STOMP client must advertise/send the negotiated 10-second heartbeats;
  T9's server-side liveness policy is not effective if the client disables
  heartbeats.
- T5's immediate last-member cleanup remains the stronger rule and does not
  wait for disconnected grace.
