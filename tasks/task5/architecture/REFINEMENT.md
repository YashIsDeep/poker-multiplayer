# T5 Architecture Refinement Record

This record captures planning review performed before marking T5
`ARCHITECTURE_PLANNED`. It is not the later formal architecture-review gate.

## Pass 1 — Security and privacy

### Resolved: authenticated upgrade alone does not prevent cross-site STOMP

The first outline relied only on the session-authenticated `/ws` upgrade. A
browser can attach cookies to a cross-site WebSocket request, so the design now
requires both an explicit origin allowlist and Spring Security Messaging CSRF
validation on the STOMP CONNECT frame. `/api/csrf` materializes the token for
the SPA without weakening HTTP CSRF.

### Resolved: shared room topics would block T6 redaction

A conventional `/topic/room/{id}` broadcast would serialize one payload for
every viewer and make opponent hole-card leakage likely when T6 is added. Room
state now travels only through `/user/queue/room`, and every snapshot is mapped
for a named viewer through an allow-list DTO boundary.

### Resolved: user destinations could leak command acks across tabs

Spring user destinations normally fan out to every session for one principal.
That is correct for state snapshots but wrong for request correlation. Command
acks are now session-local with `@SendToUser(broadcast = false)`; authoritative
room snapshots and closed notices intentionally fan out to all user sessions.

## Pass 2 — Atomicity and ordering

### Resolved: independent room locks cannot atomically enforce one-room-per-user

Joining touches room capacity, the user-membership index, and the join-code
index. Unordered independent locks introduce partial cross-room updates. T5 now
defines a registry lock for global indexes plus one lock per room, with exactly
one nested order: registry then at most one room. Membership changes use both;
T6 game actions and timers use only the room lock and may never acquire the
registry lock. Immutable membership+room projections are captured under this
order, while wire mapping and broker I/O remain outside all locks.

### Resolved: committed messages can publish out of order

Unlocking before broker sends is necessary, but concurrent service threads can
then publish revisions in reverse order. Holding the registry lock over broker
I/O was rejected. Every lobby and room snapshot now carries a monotonic
revision, and T7 must ignore older revisions. Sync is authoritative and safe to
repeat.

### Resolved: publication failure cannot roll back in-memory state

The outline did not define whether a broker send failure made the command fail.
Room mutation now commits before publication. A send failure is logged, no
mutation is retried, and the ack reports committed success if it can be sent.
Clients recover with `state.sync`, avoiding duplicated create/join intent.

## Pass 3 — Lifecycle and retry behavior

### Resolved: disconnect-as-leave breaks refresh and reconnect

Transport loss is no longer interpreted as user intent. Membership and seating
survive disconnect; explicit leave is the only normal removal path. The final
member's leave deletes every room index atomically. The defensive GC sweep is
restricted to zero-member rooms and cannot expire an occupied idle room.

### Resolved: retries and uncertain outcomes need deterministic recovery

T5 does not need request replay storage for the in-process simple broker, but
reconnects can cause user retries. Same-room join, same-seat take, already-
standing stand, and completed leave are defined as idempotent successes without
revision increments. Create remains non-idempotent, so `state.sync` returns the
authenticated user's authoritative membership epoch and nullable current room
without requiring a caller-known room ID. T9 can add bounded replay/rate
controls later without changing command shapes.

### Resolved: create-then-seat implicit behavior creates ambiguous failures

Room creation and joining now always produce unseated membership. Taking a seat
is an explicit command with its own ack and race behavior. This keeps room
capacity, seat occupancy, and future T6 buy-in/game eligibility as separate
concepts.

## Planning completion

The refined README and test plan cover security, protocol, ownership,
concurrency, snapshot privacy, ordering, reconnect, GC, failure semantics, and
the T6/T7 handoff. No unresolved architecture decision blocks implementation.

## Formal review refinement

The later architecture review added these resolutions:

- session-backed CSRF with JSON as the sole token-delivery mechanism;
- explicit STOMP message, buffer, send-time, and first-frame limits;
- strict STOMP-only unknown-field rejection and cascaded payload validation;
- `state.sync` recovery when a committed create loses both publications;
- transport-neutral domain projections and a `room -> ws` dependency ban;
- per-user membership epochs for cross-room/rejoin stale-event rejection;
- authenticated UNSUBSCRIBE support with ACK/NACK denied;
- per-recipient best-effort publication with committed-success ack semantics;
- valid-outcome concurrency oracles instead of a blanket one-winner rule; and
- deterministic last-member deletion without an unreachable defensive sweep.
- Spring Session WebSocket integration so logout/expiry closes authenticated
  sockets while inbound play keeps the session active.
