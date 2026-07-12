# T3 Architecture Review

## Current result

**Plan revised; consistency re-review in progress.** `README.md` and
`TEST_PLAN.md` now contain explicit resolutions for all findings from the first
review. T3 remains `ARCHITECTURE_REVIEW_IN_PROGRESS` until the revised contracts
are accepted by the user.

## Resolution record

| Finding | Resolution in the revised plan |
| --- | --- |
| AR-01 — Short BB lowered the bring-in | Preflop `currentBet` is now the nominal configured BB even when either/both blinds post short. A new public scenario requires a full-BB call and two-BB minimum full raise over two short blinds. |
| AR-02 — No representation for no actor | `legalActions()` now returns `Optional<LegalActions>`; snapshot completion and actor absence are explicit, including immediately completed starts. |
| AR-03 — Runout pacing required reconstruction | `HandTransition` now carries a monotonic sequence, typed event batch, and authoritative `snapshotAfter`. Automatic runout emits per-street frames plus settlement. |
| AR-04 — Active pot contradiction | Active snapshots now expose matched pots plus optional `PendingUnmatched`; their sum reconciles to commitments. Actual refund and settlement pots occur only at closed boundaries. |
| AR-05 — Mutable deck ownership | Start requires `remaining() == 52`, validates before drawing, leaves failed starts unchanged, then consumes all 52 cards into private immutable draw order so the caller-held deck reference cannot affect the hand. |
| AR-06 — Unstable exception messages | Expected command rejection now uses `ActionRejectedException` with stable `ActionRejectionReason`; null and invariant failures remain separate. |
| AR-07 — Burn-card exposure | Burn cards remain aggregate-private and are absent from public snapshots/events. T6 must use an allow-list mapper and prove hidden-card redaction. |
| AR-08 — Incomplete overflow handling | All chip addition, subtraction, and multiplication are checked; unrepresentable full-raise bounds have defined legal-action behavior and boundary tests. |
| AR-09 — Undefined equality/order | Public value types have structural equality and every player, pot, award, ID, event, and transition collection has a deterministic order. |
| AR-10 — Undefined “test build” | Invariants are always-on at stable aggregate boundaries and throw `IllegalStateException` on programming defects. |
| AR-11 — Weak import scan | An executable architecture test enforces forbidden dependencies/APIs; the manual import scan is diagnostic only. |
| AR-12 — Brittle simulation quotas | The 10,000-hand simulation now tests termination and invariants. Semantic branch coverage remains in deterministic unit/scenario tests; random counters are diagnostic. |

## Preserved strengths

- The engine remains a deterministic, mutable, room-lock-confined per-hand
  aggregate rather than a table/session service.
- `RaiseTo` remains an unambiguous current-street total.
- Per-player `lastActedFacingBet` preserves correct cumulative short-all-in
  reopening behavior.
- Pots, eligibility, winners, refunds, and awards remain derived from the chip
  ledger rather than client input.
- Fold wins and automatic all-in runouts require no synthetic player action.
- T6 remains responsible for timers, stale action sequences, table lifecycle,
  and per-viewer transport redaction.

## Remaining review gate

Before changing the task to `ARCHITECTURE_REVIEWED`, verify that:

1. no old `initialEvents`, nullable/sentinel actor, actual-short-BB bring-in, or
   active one-contributor-pot contract remains;
2. the public API, transition semantics, invariants, integration contract, and
   test vectors describe the same states;
3. every first-pass finding has an executable verification path; and
4. the user accepts the revised architecture.
