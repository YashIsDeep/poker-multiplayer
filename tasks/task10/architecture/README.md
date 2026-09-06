# T10 Code Architecture — Client resilience + E2E

## Goal

Add client-side resilience overlays and end-to-end testing for the multiplayer app. T10 provides:

- a connection-state machine with exponential backoff and manual retry for socket
  reconnection;
- layered overlay components (banner, toasts, modals) that render atop any screen
  without altering the Room or Table view;
- multi-tab arbitration using `BroadcastChannel` to ensure only one tab actively
  holds the session; older tabs enter read-only mode with a takeover modal;
- deterministic handling of `RoomExitNotice(IDLE_TIMEOUT)` and `ROOM_NOT_FOUND` with
  navigational recovery to the lobby;
- hard-refresh resilience: session cookie re-auth → rejoin-as-resync → restore
  seat and current hand state;
- non-fatal action rejections mapped to warn toasts with automatic state reconciliation;
  and
- a full two-browser Playwright E2E suite exercising the 12 healthy/recovery scenarios
  from the design (seats, blinds, heads-up action, timeouts, refresh, multi-tab,
  spectator hole-card privacy, all-in runouts, rebuy, kill-server recovery, room GC).

T10 is the resilience boundary of the client. It establishes the toast/modal UX,
network-recovery semantics, and cross-tab policies that keep the player experience
usable during transient failures and intentional recovery paths.

## Scope boundary

T10 owns the connection state machine, exponential backoff scheduling, overlay
rendering (banners, toasts, modals), tab-takeover signalling via `BroadcastChannel`,
deterministic navigation on idle-timeout exit notices and `ROOM_NOT_FOUND`, and the
full Playwright E2E suite with dev-server orchestration.

T10 does not modify Room or Table view components; those remain T7's responsibility.
T10 does not own server recovery semantics (epoch reconciliation, snapshot acceptance
rules, ROOM_NOT_FOUND ack generation)—T5 defines those invariants. T10 does not
own server-side multi-tab policy or connection limits; T9 will define whether the
server kicks old tabs or allows concurrent sockets. T10 does assume T9 will provide
server-side hardenings (rate limits, abuse monitoring) and will test that
kill-server recovery works end-to-end.

Client reconnect will always attempt a fresh `/api/csrf` token before STOMP CONNECT,
per T5 CSRF flow, but T10 does not own the CSRF implementation—it only invokes
the token refresh as part of reconnect.

## Dependency contracts

### T7 store interface (assumed)

T10 builds on T7's zustand STOMP store. T10 assumes the following contracts:

```typescript
// Store shape per the T7 architecture (tasks/task7/architecture/README.md):
{
  // connection state
  connection: 'connecting' | 'connected' | 'reconnecting' | 'offline'
  serverNow: number // EMA clock offset

  // membership and room state
  membershipEpoch: number
  roomSnapshot: RoomSnapshot | null
  lobbySnapshot: LobbySnapshot | null

  // actions
  clearRoom(): void
  setConnection(status: 'connecting' | 'connected' | 'reconnecting' | 'offline'): void

  // reconnect and sync (T7 exposes these as its reconnect entry points;
  // T7 ships a simple fixed 2s retry that T10's backoff machine replaces)
  subscribe(): Promise<void> // (re)subscribe to STOMP destinations
  syncState(): Promise<void> // call /app/state.sync
  syncRoom(roomId: string): Promise<void> // call /app/room.sync for cached recovery
  act(command, payload): Promise<void> // send command with actionSeq
}
```

T7 owns STOMP connection lifecycle, snapshot updates from the server, and `act()`
auto-sequencing. T10 depends on `connectionStatus` to display the reconnect banner,
on `membershipEpoch` and `roomSnapshot` to reconcile snapshots after a reconnect or
rejection, and on the ability to call `syncState()` and `subscribe()` to initiate
recovery.

If T7's actual store shape differs, T10's integration points (hooks that read status,
call sync, handle rejections) will need adjustment; the resilience logic itself is
independent of the store shape.

### T9 server behavior (assumed)

T10 assumes the following server behavior from T9:

- **Multi-tab policy**: The server intentionally supports multiple simultaneous
  authenticated WebSocket connections for one user (T5 already does). T9 does not
  kick old tabs server-side; the client enforces single-tab-active using
  `BroadcastChannel`.

  *Resolved 2026-07-12*: client-only `BroadcastChannel` arbitration is the
  approved design; T9 is not required to provide a takeover signal. Cross-device
  sessions of the same user both stay live, which T5 already tolerates. If T9
  later adds a server-side `tab.takeover` message, T10 may subscribe to it as an
  enhancement without changing the overlay UX.

- **Kill-server recovery**: When the server restarts, all rooms are gone. An old
  room ID cannot be inferred by the fresh server. `state.sync` succeeds with
  `room=null`; if the client retained a cached room ID, it then sends targeted
  `room.sync(oldRoomId)` and receives `ROOM_NOT_FOUND`. T10 displays the "This
  table is gone" modal and clears room state, redirecting to the lobby.

- **Idle-close notice**: T9 sends each removed member a `RoomExitNotice` with
  `reason=IDLE_TIMEOUT` on `/user/queue/room-events`. T10 shows the "Table
  closed" modal with a 3s countdown to lobby. The notice includes room ID and
  membership epoch so stale messages are ignored. There is no separate
  `room.closed` destination. A host/member leaving does not close a non-empty
  room; the ordinary `LEFT` notice applies only to the user who left.

- **Non-fatal action rejections**: Commands like act/join that fail with stable error
  codes (e.g. `NOT_YOUR_TURN`, stale `actionSeq`) are ack'd with `ok: false`. T10
  maps these to warn toasts; the player must re-act after state reconciliation.

## File and module layout

```
client/src/
  hooks/
    useResilience.ts          connection status, backoff, retry trigger
    useRoomRecovery.ts        RoomExitNotice / ROOM_NOT_FOUND handlers
    useBroadcastTakeover.ts   tab takeover signalling and detection
    useToast.ts               toast queue add/remove/dismiss

  stores/
    resilience.ts            backoff state, manualRetry signal

  components/
    resilience/
      ConnectionBanner.tsx    amber/red banner (reconnecting/offline)
      ToastHost.tsx          toast stack (bottom-right)
      RoomClosedModal.tsx    blocking modal, 3s countdown
      RoomGoneModal.tsx       "table is gone" modal
      TabTakeoverModal.tsx    "opened in another tab" modal

  utils/
    backoff.ts               exponential backoff logic and scheduling
    toast.ts                 toast type definitions and helpers

  e2e/
    playwright.config.ts     shared config (dev server, timeouts)
    fixtures/
      contexts.ts            alice/bob/spectator browser contexts
      dev-login.ts           /auth/dev?u=name orchestration
    scenarios/
      01.healthy-hand.spec.ts
      02.action-timer.spec.ts
      03.refresh-mid-hand.spec.ts
      04.tab-close-rejoin.spec.ts
      05.multi-tab-takeover.spec.ts
      06.spectator-privacy.spec.ts
      07.all-in-runout.spec.ts
      08.bust-and-rebuy.spec.ts
      09.kill-server-recovery.spec.ts
      10.room-gc.spec.ts
```

Protocol DTOs and STOMP commands live in T5 (and are re-exported by T7 store).
Connection lifecycle (handshake, CONNECT, subscription) lives in T7. T10 adds only
resilience overlays and E2E orchestration.

## Behaviour matrix

From the approved design mockup, the exhaustive event→UX→recovery mapping:

| Event | Source | UX response | Recovery |
|---|---|---|---|
| Socket drop mid-hand | STOMP `onWebSocketClose` | Amber banner "Reconnecting…", controls locked, table dimmed | Auto-retry w/ backoff (start 500ms, cap 30s); on reconnect, re-subscribe → fresh `/api/csrf` → STOMP CONNECT → server resends full snapshot → banner clears, controls re-enable |
| Retries exhausted (cap reached) | store backoff scheduler | Red banner "Offline — couldn't reach the table", manual **Retry** button | Manual **Retry** restarts backoff cycle from initial delay |
| `RoomExitNotice(IDLE_TIMEOUT)` on `/user/queue/room-events` | T9 closes a continuously disconnected room | Blocking modal "Table closed — this inactive table expired. Taking you back to the lobby in 3s…" | Auto-countdown 3→0; on 0 or manual **Back to lobby now**, navigate `/`, clear room snapshot, clear membership |
| `ROOM_NOT_FOUND` ack | command ack after server restart or room GC | Warn toast "ROOM_NOT_FOUND" + blocking modal "This table is gone — may have been closed or the server restarted…" | **Back to lobby** button navigates `/`, clears room snapshot, refetch lobby list |
| Non-fatal action reject ack | command ack with `error: NOT_YOUR_TURN` or stale `actionSeq` | Warn toast "Not your turn — controls re-synced to current hand" (details in toast) | No optimistic state kept; on close, controls reconcile to latest server snapshot on next `state.sync` |
| Same user joins from 2nd tab | BroadcastChannel `takeover` message | Older tab shows blocking modal "Opened in another tab — your game is now active in a newer tab. This one is read-only." | Two options: (1) **Use here instead** re-grabs session (sends signal via channel, new tab closes its socket); (2) manual close. Socket is closed immediately; UI frozen until click. |
| Hard refresh mid-hand | page reload | Briefly "Connecting…" splash (T7 shows while bootstrapping) | Session cookie re-auths handshake → `state.sync` returns current membership + room snapshot → rejoin-as-resync → same seat, current hand continues with latest snapshot |

## Reconnect state machine

The `useResilience()` hook manages a three-state machine:

```
[connected]
  ↓ (socket closed)
[reconnecting] ← auto-retry backoff
  ↓ (max retries exhausted)
[offline]
  ↓ (manual retry clicked)
[reconnecting] (restart cycle)
  ↓ (reconnect succeeds)
[connected]
```

**Backoff schedule** (exponential, capped):

```
attempt 1:  500ms   (initial)
attempt 2:  1s      (×2)
attempt 3:  2s      (×2)
attempt 4:  4s
attempt 5:  8s
attempt 6:  16s
attempt 7:  30s     (cap)
attempt 8+: 30s     (stay at cap)
```

Backoff resets to initial delay when a successful reconnect occurs. A browser tab
close/refresh or a manual **Retry** button click also resets the schedule.

**Reconnect procedure**:

1. Socket closes (detected via STOMP `onWebSocketClose`).
2. T10 sets `connectionStatus: 'reconnecting'`, starts backoff.
3. On next backoff tick:
   - Call `GET /api/csrf` to refresh the CSRF token (required by T5 CONNECT flow).
   - Call `T7.subscribe()` to re-subscribe to STOMP destinations.
   - On success, T7 calls `state.sync` to bootstrap current state. If it returns
     `room=null` while a pre-disconnect room ID is cached, call
     `room.sync(cachedRoomId)` before clearing that cache. This distinguishes a
     closed/restarted table from a user who was already in the lobby.
   - After authoritative sync/recovery completes, T10 transitions to `connected`
     and clears the banner.
   - On failure, schedule next backoff tick.
4. If backoff cap exhausted, transition to `offline` and show red banner with
   manual **Retry** button.
5. Manual **Retry** resets backoff to initial and steps back to (3).

**Snapshot reconciliation after reconnect**:

T5's snapshot contract requires that clients compare `(membershipEpoch, roomRevision)`
tuples. After a successful reconnect:

- `state.sync` returns the authoritative current state.
- `state.sync room=null` plus a cached prior room ID triggers one targeted
  `room.sync`; `ROOM_NOT_FOUND` is handled as table loss without retrying it.
- If `membershipEpoch` changed (user left/rejoined), clear old room and accept new one.
- If `membershipEpoch` same but `roomRevision` advanced, accept new snapshot.
- If `membershipEpoch` and `roomRevision` both same, it's a resync duplicate; ignore.

T7's store is responsible for this comparison. T10 does not re-implement it; T10
only ensures that `state.sync` is called as the recovery entry point.

## Multi-tab arbitration design

T10 uses `BroadcastChannel('poker-room')` to signal tab takeovers within the same
browser session.

**Takeover flow**:

1. When a tab connects successfully (after STOMP handshake), it broadcasts
   `{ type: 'tabConnected', timestamp: Date.now() }` to all other tabs on the channel.

2. Other tabs receive this message and compare timestamps. The tab with the *most
   recent* connection time becomes the "active" tab; older tabs transition to
   read-only mode and show the takeover modal.

3. The older tab's socket remains open (the server allows concurrent connections),
   but T10 locally prevents action sends: the `act()` call is blocked and displays
   an alert directing the player to click **Use here instead** in the takeover modal.

4. If the player clicks **Use here instead** on the old tab, it broadcasts
   `{ type: 'requestTakeover' }`. The new active tab receives this, closes its
   socket and STOMP subscriptions, and then reloads from the old tab's session
   (since the old tab's socket remains open). The modal dismisses and play resumes
   on the old tab.

5. If the player closes the old tab, the new tab remains active. If the new tab
   closes, the old tab exits read-only mode and becomes active again (automatically
   via broadcast).

*Resolved 2026-07-12*: T10 relies on BroadcastChannel message ordering and
client-side timestamps only; no server connection-ID coordination is required.
If T9 later implements a server-side multi-tab policy (e.g. "kick old tabs"),
T10 adjusts to consume it rather than duplicating it.

## E2E architecture (Playwright)

**Setup**:

- Three persistent browser contexts: alice, bob, spectator (all with same dev-server base URL).
- Dev-login via `GET /auth/dev?u=name` orchestrated before each test to create/auth users.
- Shared `playwright.config.ts` with:
  - dev server startup (via npm run dev, or assume already running).
  - Timeouts: 10s for expect(), 30s for actions requiring server (joins, actions).
  - Video/trace capture on failure.

**Test structure** (12 E2E scenarios from the design):

Each scenario lives in its own `.spec.ts` file and covers a specific recovery path:

1. **01 — Healthy hand** (design steps 1–3): Alice creates a 6-seat 5/10 room
   (buy-in 400–2000, 30s timer); room appears in Bob's lobby within 1s; both
   sit with 1,000; heads-up hand to showdown. Assertions: lobby propagation,
   button acts first preflop / last postflop, raise slider min/max match
   `[minRaiseTo, maxRaiseTo]`, winning ranks named, chips conserved.

2. **02 — Action timer**: One player idles past timer expiry. Assertion: auto-fold/check fires, player marked sitting out.

3. **03 — Refresh mid-hand**: Mid-hand, Alice hard-refreshes. Assertion: reconnects,
   same seat, hole cards restored, hand continues.

4. **04 — Tab close + rejoin**: Mid-hand, close Bob's tab. Action times out to fold;
   reopen and rejoin. Assertion: seat retained, hand state correct, can act again.

5. **05 — Multi-tab takeover**: Open room in 2nd tab as same user. Assertion: old tab
   shows takeover modal (read-only), **Use here instead** works (new tab closes,
   old tab becomes active).

6. **06 — Spectator hole-card privacy**: In a room with at least three member
   slots, two users sit and a third joins but remains unseated;
   check that STOMP frame payloads contain no hole-card strings in frames visible to
   spectator. Assert: observer cannot see private cards via WS inspection.

7. **07 — All-in runout**: Force unequal stacks, both go all-in preflop. Assertion:
   auto-runout with pacing, side pots correct, uncalled-bet refund applied.

8. **08 — Bust + rebuy**: Player busted; rebuy modal appears. Submit rebuy. Assertion:
   rebuy queued and applied next hand.

9. **09 — Kill-server recovery**: Mid-hand, kill the dev server (or send SIGTERM
   to Spring Boot). After reconnect, `state.sync` succeeds with `room=null`; the
   client then sends `room.sync(cachedOldRoomId)` and receives `ROOM_NOT_FOUND`.
   Assertion: toast + modal, redirect to lobby.

10. **10 — Room GC + teardown** (design step 12): All players leave room.
    Assertion: room removed from lobby, private room joinable only by code
    (not listed publicly).

The design README's 12 numbered steps map onto these 10 spec files: step 1
(setup / dev-login) is the shared `fixtures/contexts.ts` fixture, step 2
(lobby create/join) is asserted inside spec 01, and steps 3–12 map to specs
01–10 in order. Spectator sit-down + 3-handed blind order (design step 8,
second half) is asserted at the end of spec 06.

**Dev server orchestration**:

- Tests run against `npm run dev` (Vite + Spring Boot dev mode).
- For kill-server tests, the test runner sends SIGTERM to the Spring Boot process,
  waits for graceful shutdown (or timeout), then restarts it.
- Alternatively, a simpler approach: use a test fixture that toggles an in-memory
  "offline" flag in the server, simulating room removal without actual restart.
  T10 documents both approaches; T9 confirms which is feasible for CI.

**Hole-card privacy assertion** (scenario 06):

Spectator joins as a non-seated member. T10 instructs Playwright to:
1. Intercept all STOMP `MESSAGE` frames sent to the spectator's queue.
2. Parse the `game.seats[].holeCards` field in each snapshot.
3. Assert that the spectator's frame contains no hole-card suit/rank strings for
   seated players (or that hole cards are null/masked).
4. Compare to alice/bob's frames to confirm seated players see their own cards.

This is a WS-layer privacy regression test; it catches serialization bugs that
accidentally leak cards to non-seated users.

## Failure behavior

- **Socket close during reconnect backoff**: Already in backoff queue; no change.
  Next tick retries.
- **CSRF token fetch fails**: Treated as reconnect failure; backoff continues.
- **STOMP subscription fails**: Treated as reconnect failure; backoff continues.
- **BroadcastChannel not supported** (older browsers): T10 provides a no-op fallback
  that allows single-tab mode only (no multi-tab arbitration). Tests skip multi-tab
  scenario.
- **Server returns stale `membershipEpoch` in snapshot**: T5's snapshot contract
  prevents acceptance of old state; T10 logs and re-syncs.
- **Manual action rejected with `ROOM_NOT_FOUND`**: Toast + modal; lobby redirect.
- **Manual action rejected with `NOT_YOUR_TURN`**: Toast; no modal; player re-acts.
- **Toast dismiss**: User can click the ✕ to dismiss individual toasts. Toasts also
  auto-dismiss after 5s (warning toasts) or 8s (info/ok toasts).
- **Modal dismiss (non-blocking)**: Taking a non-blocking modal action (e.g. "Use
  here instead" or "Back to lobby") closes it and executes the associated intent.

## Implementation sequence

1. **Backoff utility** (`utils/backoff.ts`): exponential backoff scheduler, cancel,
   and reset logic. Pure function; testable without STOMP.

2. **Resilience store** (`stores/resilience.ts`): zustand slice with connection status,
   backoff state, manual retry trigger. Coordinate with T7's store definition.

3. **Toast queue hook** (`hooks/useToast.ts` + `utils/toast.ts`): add/remove/dismiss
   logic, persistence of toast ID/type/message.

4. **Overlay components** (in `components/resilience/`):
   - `ConnectionBanner.tsx` (amber/red, spinner, retry button)
   - `ToastHost.tsx` (stack, dismiss button, auto-dismiss timers)
   - `RoomClosedModal.tsx` (countdown, auto-navigate)
   - `RoomGoneModal.tsx` (static, redirect button)
   - `TabTakeoverModal.tsx` (static, two buttons: takeover or close)

5. **Resilience hook** (`hooks/useResilience.ts`): orchestrates backoff, state machine,
   CSRF refresh, subscription retry, snapshot reconciliation on reconnect.

6. **Room recovery hooks** (`hooks/useRoomRecovery.ts`): listens for
   `RoomExitNotice(IDLE_TIMEOUT)` and `ROOM_NOT_FOUND`, triggers appropriate
   modals and navigation.

7. **Multi-tab hook** (`hooks/useBroadcastTakeover.ts`): BroadcastChannel setup,
   takeover signal, read-only enforcement.

8. **App-level integration** (update `App.tsx` or `Room.tsx`): Mount `ToastHost`,
   `ConnectionBanner`, modals, and hooks at the root or room boundary. Ensure they
   render above all other content (z-index, portal, etc.).

9. **Playwright setup** (`e2e/playwright.config.ts`, fixtures, base hooks for
   dev-login).

10. **Scenario specs** (one per spec file): Implement the 12 scenarios; start with
    scenario 1 (healthy hand) to verify the E2E harness, then add recovery paths.

11. **CI integration**: Document how to run E2E tests locally (`npm run e2e`) and in CI
    (e.g., `npm run e2e:ci` with captured video/traces on failure).

12. **Acceptance testing**: Manual smoke-test of all UX states using the mockup.html
    as a visual checklist.

## Acceptance criteria

- Only authenticated sessions can maintain a STOMP connection; unauthenticated
  reconnect attempts are rejected before overlay render.
- Socket drop triggers a "Reconnecting…" banner; controls are locked and table is
  dimmed while reconnecting.
- Exponential backoff with 500ms–30s schedule; manual retry resets the schedule.
- A current-room `RoomExitNotice(IDLE_TIMEOUT)` triggers a blocking modal with
  auto-redirect to lobby in 3s; stale room IDs/epochs and another user's normal
  leave do not close the current room.
- A `ROOM_NOT_FOUND` ack on any action triggers a warn toast + blocking modal,
  redirect to lobby, and lobby list refetch.
- Non-fatal action rejections (not-your-turn, stale actionSeq) trigger warn toasts;
  controls reconcile to latest snapshot without optimistic state.
- Hard refresh mid-hand: session cookie re-auths → same seat, current hand resumes
  with latest server snapshot.
- Multi-tab: when a second tab connects, the older tab enters read-only mode with a
  takeover modal. **Use here instead** works as designed.
- BroadcastChannel takeover messaging uses wall-clock timestamps to determine active tab;
  messages are received and processed in order.
- All 12 design steps pass across the 10 spec files: setup/lobby/healthy hand,
  timer, refresh, tab-close, multi-tab, spectator privacy (incl. sit-down blind
  order), all-in runout, rebuy, kill-server, room GC/teardown.
- Hole-card privacy regression test (scenario 06) asserts that spectator's STOMP
  frames never contain seated players' hole-card values.
- Playwright runs both locally (against dev server) and in CI (headless, with
  captured traces on failure).
- All overlays are positioned above the Room/Table view (z-index: 5 banner, 8 toasts,
  10 modals) and are styled with the minimal palette from the mockup (no T8 polish).
- Toast queue automatically dismisses toasts after 5–8s; user can manually dismiss
  any toast via ✕ button.
- Recovery from kill-server or room GC preserves the 12-scenario E2E as the P7 exit gate.

## Implementation notes

- T10 does not own the Room or Table UI components; T7 and T8 own those. T10's overlays
  must not interfere with their rendering (use portals, careful z-index, event
  capture).
- T7's store will provide the connection status. T10 reads it to display the banner;
  T10 does not drive the store directly (only calls public methods like `syncState()`).
- The CSRF token refresh in T10 is part of the T5 reconnect flow, not a T10
  responsibility. T10 calls a helper; T7 or a shared auth module provides the fetch.
- BroadcastChannel is not available in older browsers or in some mobile contexts.
  T10 provides a no-op fallback; multi-tab tests are skipped if unavailable.
  Mark this as a known limitation in release notes if needed.
- E2E tests run in parallel by default in Playwright; use `fullyParallel: false` or
  serial tests for scenarios that interact (e.g., multi-tab needs the same user in
  two tabs simultaneously).
- Dev-login (`/auth/dev?u=name`) is a T4 feature; T10 only orchestrates it.
  Confirm that T4's endpoint is wired and returns a valid session.

## Architecture boundaries

T10 is a pure client layer; it does not define server behavior. T5 defines the STOMP
protocol and room membership semantics. T6 will define game action semantics and
timers. T7 defines the store and room UI. T8 adds animation polish. T9 defines
server-side rate limits and multi-tab policy.

T10's role: client-side resilience overlays, reconnect backoff, multi-tab coordination,
and comprehensive E2E verification of all healthy and recovery paths. The E2E suite
is the integration test for the entire stack (T5–T8 + T10); it exercises real WebSocket
traffic, real game state, real server logic, and real recovery scenarios.
