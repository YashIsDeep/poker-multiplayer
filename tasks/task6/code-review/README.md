# T6 Code Review

## Result

`CODE_REVIEWED` after comparing the implementation with
`tasks/task6/architecture/README.md`, its refinement record, and test plan.

## Material findings resolved

- Added generation-guarded two-second hand starts and invalidation-safe
  presentation/next-hand callbacks so stale tasks cannot skip public frames.
- Made scheduler rejection visible as `PAUSED`, added bounded recovery sweeps,
  and quarantined persistent failures without corrupting the seat ledger.
- Enforced explicit initial buy-ins, original-buy-in idempotency, strict action
  amount semantics, and gameplay acknowledgements carrying `actionSeq`.
- Made redaction fail closed for unknown viewers; verified pre-showdown,
  fold-win, and showdown card visibility; corrected raise-to last-action values.
- Exercised deterministic timeout/check-or-fold behavior, queued sit-out,
  paced all-in board progression, stack conservation, engine-start failure,
  reconnect sync, and stale action/callback rejection.
- Reconciled the client with the final T6 wire contract: public room config,
  explicit buy-in payloads, `buttonSeat`, ordered awards/ties, per-seat ranks,
  and the configured action timeout.

## Verification

- `npm.cmd test`: 118 tests, 0 failures, 0 errors.
- `npx.cmd vitest run` in `client`: 23 tests passed.
- `npm.cmd run build` in `client`: TypeScript and Vite production build passed.
- `git diff --check`: passed.

No blocking code-review finding remains.
