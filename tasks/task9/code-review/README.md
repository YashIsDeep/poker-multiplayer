# T9 Code Review

## Result

`CODE_REVIEWED` after comparing the implementation with
`tasks/task9/architecture/README.md`, its refinement record, and test plan.

## Material findings resolved

- Hardened the token bucket and capped registry for monotonic-clock edges,
  concurrent first use, expiry races, fair bounded cleanup, and invalid config.
- Added session and user-wide pre-conversion budgets, bounded violation state,
  a ten-second close window, periodic cleanup, and configurable thresholds.
- Enforced one matching admission declaration and session-local ack on every
  STOMP command; kept sync/leave recovery on its independent control budget.
- Made telemetry best effort, low cardinality, bounded, sampled, and keyed only
  by process-salted hashes rather than raw user identifiers.
- Fixed presence scheduling races with exact generation/due guards, reverse
  socket indexing, transition-aware multi-tab handling, heartbeat support,
  membership-driven reconciliation, and stale-task-safe idle retries.
- Preserved a five-minute paused-table recovery window, atomic idle deletion,
  membership epoch advancement, and restart semantics where `state.sync`
  returns no room and `room.sync(oldId)` returns `ROOM_NOT_FOUND`.
- Isolated publication, presence, and gameplay side effects so one failure is
  not mislabeled or executed twice after a committed mutation.

## Verification

- `npm.cmd test`: 118 tests, 0 failures, 0 errors.
- Focused concurrency, admission, presence, restart, and scheduler-recovery
  tests passed as part of the full suite.
- `npx.cmd vitest run` in `client`: 23 tests passed.
- `npm.cmd run build` in `client`: TypeScript and Vite production build passed.
- `git diff --check`: passed.

No blocking code-review finding remains.
