# T6 Architecture Review

## Current result

**ARCHITECTURE_REVIEWED.** `README.md`, `REFINEMENT.md`, and `TEST_PLAN.md`
resolve every finding below. The revised contracts were accepted by the user
and `TASKS.md` has been reconciled to `ARCHITECTURE_REVIEWED`.

The review checked the plan against the implemented T3 engine, the T5 room and
STOMP boundaries, downstream T7/T10 contracts, general concurrency and security
architecture, and ordinary Java/Spring coding practices.

## Resolution record

| Finding | Resolution in the revised plan |
| --- | --- |
| AR-01 — Paced stack source | All dealt-in public ledger fields come only from the current presented transition; persistent next-hand stacks remain private until the result boundary. |
| AR-02 — Underivable DTO | Last actions come from typed events, ranks are evaluated for non-folded showdown players, all non-folded hands reveal, and ordered awards replace singular winner/best-five assumptions. |
| AR-03 — Global gameplay serialization | Gameplay briefly resolves under registry lock, then releases it and mutates under only the room lock; closed-reference and cross-room concurrency tests pin the rule. |
| AR-04 — Undefined pause | Added WAITING/RUNNING/PAUSED/FAILED states, visible disabled pause, bounded recovery, terminal failure, and T9 cleanup semantics. |
| AR-05 — Scheduling race | Added monotonic schedule intents and compare-by-generation reconciliation, including guarded pause/resume mutations. |
| AR-06 — Lifecycle guard | Added checked `lifecycleSeq` with an exhaustive invalidation list and stale-start tests. |
| AR-07 — Incomplete action DTO | Added nullable actor-only `LegalActionsView` mapping every T3 flag and amount. |
| AR-08 — Early callback ambiguity | Early callbacks reschedule the exact remainder under a newer schedule generation; duplicates still yield one timeout. |
| AR-09 — Partial mirrored mutation | Added validate-before-write, copy-on-write seat maps, assign-after-start, fault injection, and failed-hand quarantine using the last committed ledger. |

## Findings

### [RESOLVED P1] AR-01 — Paced presentation has no unambiguous public stack source

The plan copies the engine's completed stacks into persistent table seats
"exactly once" as soon as the hand completes, while separately holding an older
`presentedSnapshot` and revealing settlement transitions over time. It does not
say whether `GameSeatView.stack` comes from the persistent seat or the current
presentation frame. Reading the table seat leaks future payout totals before
the corresponding pot/result frame; reading the engine frame without an
explicit overlay can disagree with table status and queued mutations.

Define one projection rule for every presentation step. A safe rule is that
dealt-in players' public stack/commitment/pot/award fields come exclusively from
the current `HandTransition.snapshotAfter` until the final result frame, while
table state remains the private next-hand ledger. Queued boundary changes must
not become visible in the paced hand frame early. Add a multi-pot test proving
that no viewer sees a later award or final stack in an earlier frame.

### [RESOLVED P1] AR-02 — The proposed wire DTO cannot be derived from the T3 API

`GameSeatView` includes `lastAction` and `finalHandRank`, and the reveal rule
refers to players whose cards are "required" by the showdown result. T3's
`HandSnapshot` contains none of those concepts: it has no final hand ranks, no
best-five cards, and no reveal/muck decision. `lastAction` exists only indirectly
in transition events. Downstream T7 additionally expects a showdown winner,
amount, winning cards, and rank, which T6's `GameView` does not define.

Choose and document a derivation contract: which values are accumulated from
trusted transitions, which are evaluated from the final seven cards, and
whether all non-folded showdown hands or only a precisely defined subset reveal.
Then reconcile the exact Java and TypeScript schemas. Do not make the redactor
guess poker policy or reconstruct missing history ad hoc.

### [RESOLVED P1] AR-03 — The global registry lock serializes gameplay across all rooms

The plan calls the room the concurrency boundary but also requires every game
action and timer callback to acquire `registryLock` before the room lock. That
makes unrelated tables block one another for every action, snapshot capture,
timeout, and presentation callback. T5's own architecture review identified
this as the failure mode its per-room lock was meant to avoid.

Separate room lookup/lifetime protection from room mutation. Define a safe
lease/reference or registry protocol that prevents deletion between lookup and
room-lock acquisition, while allowing steady-state gameplay to hold only one
room lock. If global serialization is intentionally accepted for this product,
state the capacity ceiling and measure it; do not describe it as room-confined
concurrency.

### [RESOLVED P1] AR-04 — `PAUSED` is neither modeled nor recoverable

After two scheduler failures the plan marks a table `PAUSED`, but `TableGame`'s
state, `GameView.phase`, invariants, commands, and recovery flow contain no such
state. An exposed actor without a deadline contradicts the invariant that an
actionable actor always has a deadline. Nothing retries or resumes a paused
table, and T9 idle expiry waits for a safe boundary that a paused hand may never
reach.

Model operational pause explicitly and end to end: state ownership, public
status, legal command behavior, deadline semantics, retry/backoff or operator
recovery, terminal fallback, and idle-close behavior. Test both scheduler
recovery and permanent failure without leaving an immortal live hand.

### [RESOLVED P1] AR-05 — Post-commit scheduling can race newer mutations

The mutation flow unlocks, publishes a snapshot with a deadline, and only then
reconciles scheduler handles. Another command/callback can commit a newer intent
between those steps. Guards make a stale callback harmless, but they do not
prove that the newest deadline has a live scheduled task, or that a scheduler
failure transitions the room to `PAUSED` and publishes that new state exactly
once. Publication also advertises a timer before scheduling is known to have
succeeded.

Give scheduling intents their own monotonic generation and define an idempotent
coordinator state machine for install, replace, reject, retry, and cancel. The
room must commit any pause/recovery transition through the normal mutation and
publication path. Add a deterministic race where intent N is being reconciled
after intent N+1 commits and prove N+1 remains scheduled.

### [RESOLVED P2] AR-06 — The lifecycle guard is referenced but not specified

Hand-start callbacks capture `expectedLifecycleSeq`, yet `lifecycleSeq` is not
part of the aggregate state listing and the plan never enumerates which changes
increment it. Seat take/stand, sit-in/out, rebuy replacement, leave, eligibility
changes, and cancellation can therefore invalidate a scheduled start without a
reviewed rule for making its callback stale.

Add the sequence to the model, define every increment point and overflow policy,
and test each invalidating mutation against an already-scheduled start. Prefer
one named lifecycle generation over several implicit booleans and task-handle
cancellation.

### [RESOLVED P2] AR-07 — The action DTO omits the legality contract clients require

The package list introduces `LegalActionsView`, but the documented `GameView`
only lists `toCall`, `minRaiseTo`, and `maxRaiseTo`. It omits `canFold`,
`canCheck`, `canCall`, `callAmount`, and `canRaise`, even though T3 exposes these
distinctions and T7 must choose between check and call without recreating poker
rules. Zero raise bounds alone are not a complete or type-safe legal-action
contract.

Define one nested `LegalActionsView` for the authenticated actor and either
`null` or an explicitly disabled value for everyone else. Map every T3 field,
specify JSON nullability, and add schema/serialization tests consumed by the
client contract.

### [RESOLVED P2] AR-08 — Early timer callbacks have contradictory behavior

The main plan says a callback no-ops unless the deadline has elapsed. The test
plan says a callback before the deadline "no-ops/reschedules safely." A pure
no-op can permanently lose the timeout if a scheduler fires early; an
unconditional reschedule can duplicate handles unless it is generation-aware.

Select one rule. The callback should normally return a guarded schedule intent
for the remaining duration when it is early, with an exact minimum delay and
the same opportunity generation. Test repeated early delivery, duplicate
delivery, and eventual single timeout mutation.

### [RESOLVED P2] AR-09 — Mirrored seat ownership lacks atomic failure semantics

`PokerRoom` remains authoritative for membership/seat identity while
`TableGame` mirrors occupied seats and mutable gameplay status. "Assert equality
after every mutation" detects divergence after it has happened; it does not
prevent a multi-object mutation from partially applying if checked arithmetic,
engine start, snapshot construction, or an invariant throws. Java in-memory
mutations do not roll back automatically.

Document mutation ordering and failure atomicity for take-seat, stand, boundary
application, stack copy-back, and hand start. Validate all fallible inputs before
the first write, or stage a replacement state and commit it once. Add fault
injection tests at multi-object mutation seams, not only expected engine
rejections.

## Preserved strengths

- T3 remains transport-neutral and confined behind a table aggregate.
- Stale actions and callbacks are guarded by authoritative server sequences,
  not task cancellation.
- Publishing occurs outside locks and reconnect sync remains authoritative.
- The redactor is an allow-list mapper and trusted engine types are never sent
  to Jackson directly.
- Deterministic clocks/schedulers and real two-session STOMP coverage are the
  right combination for temporal and integration verification.
- Queued stand/leave semantics protect active engine participants from being
  removed mid-hand.

## Review completion gate

AR-01 through AR-09 are resolved in the revised architecture and test plan. The
user accepted the revised plan, so the review gate is complete.
