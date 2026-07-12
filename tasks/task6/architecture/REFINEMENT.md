# T6 Architecture Refinement Record

## Refinements applied

The initial task sentence and downstream T7/T10 drafts left several decisions
open. This pass resolves them before implementation:

| Ambiguity | Final architecture decision |
|---|---|
| T5 create payload has no blinds/buy-in/timer | Extend the strict create DTO and immutable room config; do not hide product choices in constants. |
| T5 take-seat has no stack amount | Require `buyIn`; repeated calls cannot top up. |
| T7 assumes `SHOWDOWN`, engine does not | Keep engine unchanged; add wire presentation phase derived from `COMPLETE + completionReason`. |
| Engine can finish an all-in in one call | Separate completed trusted state from the current public presentation frame and advance frames with guarded callbacks. |
| Cancellation races with actions | Correctness comes from `(roomId, handId, actionSeq, actor)` guards under lock, never from task cancellation. |
| Stand/leave can invalidate an active engine player | Queue stand; reject leave for current participants until the safe boundary. Disconnect remains non-mutating. |
| “Spectator” conflicts with T5 member cap | Treat an unseated room member as the privacy-test spectator; do not expand T6 scope into a new spectator model. |
| Redaction could be bypassed by serialization | `GameSnapshotRedactor` is the sole allow-list mapper and production serialization tests scan the entire payload. |
| Timeout policy unspecified | Check if legal, otherwise fold, then queue sit-out after the hand. |
| Scheduler failure could freeze a live hand | Pause the table and expose disabled actions after a failed guarded reschedule. |
| Paced settlement could leak final stacks | Every dealt-in public ledger field comes from the current presented engine frame until the final result; persistent next-hand stacks remain private. |
| T3 lacks rank/reveal/last-action DTO fields | Accumulate last actions from presented events, evaluate final ranks only for non-folded showdown players, and reveal all non-folded hands; folded hands never reveal. |
| Registry-before-room on every action serializes tables | Resolve briefly under the registry lock, release it, then lock/revalidate the room; deletion marks retained room references closed. |
| Scheduler pause had no recovery model | Add WAITING/RUNNING/PAUSED/FAILED status, generation-stamped intents, recovery sweep, and bounded failure transition. |
| Hand-start guard was implicit | Add checked `lifecycleSeq` and enumerate every eligibility/lifecycle invalidation. |
| Legal action wire fields were incomplete | Use a nullable actor-only `LegalActionsView` mapping every T3 legality flag and amount. |
| Mirrored state could partially mutate | Validate first, use copy-on-write seat-map commits, assign new engines only after successful start, and quarantine unexpected engine failures. |
| Early scheduler delivery was ambiguous | Reschedule the exact positive remainder with a newer schedule generation and the same action guard. |

## Cross-task reconciliation required during implementation

- T7's TypeScript type should consume the final `GameView` names and derive its
  showdown screen from ordered awards plus per-seat final ranks; it must not
  assume a singular winner or `winningCards` field.
- T7 needs initial buy-in input on take-seat even though its current architecture
  only describes rebuy controls.
- T10's spectator privacy scenario must join as an unseated member while room
  membership capacity is still available.
- T9 rate limiting applies only to inbound client commands; T6 scheduler-generated
  timeout actions bypass it and remain guarded by the room aggregate.

These are contract corrections, not new T6 scope. The T6 implementation should
update downstream protocol typings/tests when those tasks are implemented or
reviewed.
