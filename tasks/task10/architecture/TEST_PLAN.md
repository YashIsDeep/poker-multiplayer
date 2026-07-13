# T10 Test Plan — Client resilience + E2E

## Overview

T10's test strategy comprises three layers:

1. **Unit tests**: backoff scheduling, toast queue, tab-arbiter logic, snapshot
   reconciliation rule enforcement.
2. **Integration/manual**: overlay component rendering, modal animations, multi-tab
   BroadcastChannel signalling (manual browser testing or headless with Playwright
   multi-context).
3. **E2E (Playwright)**: 12 comprehensive scenarios covering healthy gameplay and
   all recovery paths, with real STOMP traffic, server-side game logic, and
   infrastructure events (server kill/restart, room GC).

## Unit tests

### Backoff scheduling (`utils/backoff.test.ts`)

```typescript
describe('ExponentialBackoff', () => {
  test('starts at 500ms initial delay', () => {
    const backoff = new ExponentialBackoff()
    expect(backoff.nextDelay()).toBe(500)
  })

  test('doubles each attempt: 500, 1000, 2000, 4000, 8000, 16000, 30000', () => {
    const backoff = new ExponentialBackoff()
    const delays = []
    for (let i = 0; i < 7; i++) {
      delays.push(backoff.nextDelay())
      backoff.advance()
    }
    expect(delays).toEqual([500, 1000, 2000, 4000, 8000, 16000, 30000])
  })

  test('caps at 30s and stays there', () => {
    const backoff = new ExponentialBackoff()
    for (let i = 0; i < 10; i++) backoff.advance()
    expect(backoff.nextDelay()).toBe(30000)
    backoff.advance()
    expect(backoff.nextDelay()).toBe(30000) // still capped
  })

  test('reset() returns to initial 500ms', () => {
    const backoff = new ExponentialBackoff()
    backoff.advance()
    backoff.advance()
    backoff.reset()
    expect(backoff.nextDelay()).toBe(500)
  })

  test('cancel() prevents scheduled retry', (done) => {
    const backoff = new ExponentialBackoff()
    let callCount = 0
    const timer = backoff.scheduleRetry(() => callCount++)
    backoff.cancel()
    setTimeout(() => {
      expect(callCount).toBe(0)
      done()
    }, 600)
  })

  test('manual retry while offline resets schedule', () => {
    const backoff = new ExponentialBackoff()
    for (let i = 0; i < 7; i++) backoff.advance() // reach cap at 30s
    backoff.manualRetry()
    expect(backoff.nextDelay()).toBe(500) // back to start
  })
})
```

### Toast queue (`hooks/useToast.test.tsx`)

```typescript
describe('useToast', () => {
  test('addToast returns a toast ID', () => {
    const { result } = renderHook(() => useToast())
    const id = result.current.addToast('test message', 'info')
    expect(typeof id).toBe('string')
  })

  test('toasts list includes the new toast', () => {
    const { result } = renderHook(() => useToast())
    result.current.addToast('hello', 'info')
    expect(result.current.toasts.length).toBe(1)
    expect(result.current.toasts[0].message).toBe('hello')
  })

  test('dismissToast removes by ID', () => {
    const { result } = renderHook(() => useToast())
    const id = result.current.addToast('test', 'warn')
    expect(result.current.toasts.length).toBe(1)
    result.current.dismissToast(id)
    expect(result.current.toasts.length).toBe(0)
  })

  test('auto-dismiss timer fires after 5s for warn toasts', (done) => {
    const { result } = renderHook(() => useToast())
    result.current.addToast('warn test', 'warn')
    setTimeout(() => {
      expect(result.current.toasts.length).toBe(0)
      done()
    }, 5100)
  })

  test('auto-dismiss timer fires after 8s for info toasts', (done) => {
    const { result } = renderHook(() => useToast())
    result.current.addToast('info test', 'info')
    setTimeout(() => {
      expect(result.current.toasts.length).toBe(0)
      done()
    }, 8100)
  })

  test('multiple toasts stack independently', () => {
    const { result } = renderHook(() => useToast())
    const id1 = result.current.addToast('first', 'info')
    const id2 = result.current.addToast('second', 'warn')
    const id3 = result.current.addToast('third', 'error')
    expect(result.current.toasts.length).toBe(3)
    result.current.dismissToast(id2)
    expect(result.current.toasts.length).toBe(2)
    expect(result.current.toasts.map(t => t.message)).toEqual(['first', 'third'])
  })
})
```

### Tab arbiter (`hooks/useBroadcastTakeover.test.tsx`)

```typescript
describe('useBroadcastTakeover', () => {
  test('broadcasts tabConnected on mount with current timestamp', () => {
    const bc = new MockBroadcastChannel()
    const { result } = renderHook(() => useBroadcastTakeover(), {
      wrapper: ({ children }) => (
        <BroadcastChannelProvider channel={bc}>
          {children}
        </BroadcastChannelProvider>
      )
    })
    expect(bc.postMessage).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'tabConnected' })
    )
    expect(bc.postMessage.mock.calls[0][0].timestamp).toBeGreaterThan(0)
  })

  test('when newer tab connects, older tab is marked read-only', () => {
    const bc = new MockBroadcastChannel()
    const { result: result1 } = renderHook(() => useBroadcastTakeover(), {
      wrapper: ({ children }) => (
        <BroadcastChannelProvider channel={bc}>
          {children}
        </BroadcastChannelProvider>
      )
    })
    const timestamp1 = Date.now()

    // Simulate a new tab connecting with a later timestamp
    bc.simulate('message', {
      data: { type: 'tabConnected', timestamp: timestamp1 + 1000 }
    })

    expect(result1.current.isActive).toBe(false)
    expect(result1.current.showTakeoverModal).toBe(true)
  })

  test('requestTakeover closes active tab and signals old tab', () => {
    const bc = new MockBroadcastChannel()
    const { result } = renderHook(() => useBroadcastTakeover(), {
      wrapper: ({ children }) => (
        <BroadcastChannelProvider channel={bc}>
          {children}
        </BroadcastChannelProvider>
      )
    })

    result.current.requestTakeover()
    expect(bc.postMessage).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'requestTakeover' })
    )
    // Active tab should close its socket (T7 store will handle this)
  })

  test('when BroadcastChannel is unavailable, fallback to single-tab mode', () => {
    // Simulate undefined BroadcastChannel
    const { result } = renderHook(() => useBroadcastTakeover(), {
      wrapper: ({ children }) => (
        <BroadcastChannelProvider channel={undefined}>
          {children}
        </BroadcastChannelProvider>
      )
    })
    expect(result.current.isActive).toBe(true)
    expect(result.current.showTakeoverModal).toBe(false)
    // No broadcast or takeover logic
  })
})
```

### Snapshot reconciliation (`stores/resilience.test.ts`)

```typescript
describe('Snapshot reconciliation', () => {
  test('newer membershipEpoch always replaces old state', () => {
    const oldSnapshot = { roomId: '1', membershipEpoch: 5, revision: 10 }
    const newSnapshot = { roomId: '1', membershipEpoch: 6, revision: 3 }
    const result = shouldAcceptSnapshot(oldSnapshot, newSnapshot)
    expect(result).toBe(true)
  })

  test('same membershipEpoch, newer revision is accepted', () => {
    const oldSnapshot = { roomId: '1', membershipEpoch: 5, revision: 10 }
    const newSnapshot = { roomId: '1', membershipEpoch: 5, revision: 15 }
    const result = shouldAcceptSnapshot(oldSnapshot, newSnapshot)
    expect(result).toBe(true)
  })

  test('same membershipEpoch, same revision is a resync duplicate (accept but no-op)', () => {
    const oldSnapshot = { roomId: '1', membershipEpoch: 5, revision: 10 }
    const newSnapshot = { roomId: '1', membershipEpoch: 5, revision: 10 }
    const result = shouldAcceptSnapshot(oldSnapshot, newSnapshot)
    expect(result).toBe(true) // accept but store recognizes as duplicate
  })

  test('same membershipEpoch, older revision is rejected (out-of-order)', () => {
    const oldSnapshot = { roomId: '1', membershipEpoch: 5, revision: 10 }
    const newSnapshot = { roomId: '1', membershipEpoch: 5, revision: 8 }
    const result = shouldAcceptSnapshot(oldSnapshot, newSnapshot)
    expect(result).toBe(false)
  })

  test('older membershipEpoch is rejected (user left and did not rejoin)', () => {
    const oldSnapshot = { roomId: '1', membershipEpoch: 6, revision: 5 }
    const newSnapshot = { roomId: '1', membershipEpoch: 5, revision: 20 }
    const result = shouldAcceptSnapshot(oldSnapshot, newSnapshot)
    expect(result).toBe(false)
  })
})
```

## Integration & Manual Tests

### Overlay component rendering

```typescript
describe('ConnectionBanner', () => {
  test('renders amber banner when status is "reconnecting"', () => {
    render(<ConnectionBanner status="reconnecting" />)
    expect(screen.getByText(/Reconnecting/i)).toBeVisible()
    expect(screen.getByRole('button', { name: /Retry/i })).not.toBeInTheDocument()
  })

  test('renders red banner with Retry button when status is "offline"', () => {
    const onRetry = jest.fn()
    render(<ConnectionBanner status="offline" onRetry={onRetry} />)
    expect(screen.getByText(/Offline/i)).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: /Retry/i }))
    expect(onRetry).toHaveBeenCalled()
  })

  test('does not render when status is "connected"', () => {
    const { container } = render(<ConnectionBanner status="connected" />)
    expect(container.firstChild).toBeNull()
  })
})

describe('ToastHost', () => {
  test('renders toast stack in bottom-right corner', () => {
    const toasts = [
      { id: '1', message: 'Test message', type: 'info' }
    ]
    render(<ToastHost toasts={toasts} onDismiss={jest.fn()} />)
    expect(screen.getByText('Test message')).toBeVisible()
  })

  test('dismiss button removes toast', () => {
    const onDismiss = jest.fn()
    const toasts = [{ id: '1', message: 'Test', type: 'info' }]
    render(<ToastHost toasts={toasts} onDismiss={onDismiss} />)
    fireEvent.click(screen.getByRole('button', { name: '✕' }))
    expect(onDismiss).toHaveBeenCalledWith('1')
  })
})

describe('RoomClosedModal', () => {
  test('renders countdown 3→0', () => {
    jest.useFakeTimers()
    render(<RoomClosedModal onRedirect={jest.fn()} />)
    expect(screen.getByText(/3s/i)).toBeInTheDocument()
    jest.advanceTimersByTime(1000)
    expect(screen.getByText(/2s/i)).toBeInTheDocument()
  })

  test('calls onRedirect when countdown reaches 0', (done) => {
    jest.useFakeTimers()
    const onRedirect = jest.fn()
    render(<RoomClosedModal onRedirect={onRedirect} />)
    jest.advanceTimersByTime(3000)
    setTimeout(() => {
      expect(onRedirect).toHaveBeenCalled()
      done()
    }, 0)
  })

  test('immediate redirect on manual button click', () => {
    const onRedirect = jest.fn()
    render(<RoomClosedModal onRedirect={onRedirect} />)
    fireEvent.click(screen.getByRole('button', { name: /Back to lobby now/i }))
    expect(onRedirect).toHaveBeenCalled()
  })
})

describe('TabTakeoverModal', () => {
  test('renders two buttons: Use here instead + Close tab', () => {
    render(<TabTakeoverModal onUseMeInstead={jest.fn()} />)
    expect(screen.getByRole('button', { name: /Use here instead/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Close tab/i })).toBeInTheDocument()
  })

  test('Use here instead calls the handler', () => {
    const onUseMeInstead = jest.fn()
    render(<TabTakeoverModal onUseMeInstead={onUseMeInstead} />)
    fireEvent.click(screen.getByRole('button', { name: /Use here instead/i }))
    expect(onUseMeInstead).toHaveBeenCalled()
  })
})
```

### Manual multi-tab test (headless Playwright)

```typescript
describe('Multi-tab BroadcastChannel', () => {
  test('manual: open same room in two tabs, verify takeover modal', async () => {
    const context1 = await browser.createIncognitoBrowserContext()
    const page1 = await context1.newPage()

    const context2 = await browser.createIncognitoBrowserContext()
    const page2 = await context2.newPage()

    // Both use same user (dev-login with same name)
    await page1.goto(`http://localhost:5173/auth/dev?u=alice`)
    await page2.goto(`http://localhost:5173/auth/dev?u=alice`)

    // page1 joins room first
    await page1.goto(`http://localhost:5173/room/abc123`)
    await page1.waitForSelector('[data-qa="room-loaded"]')

    // page2 joins same room
    await page2.goto(`http://localhost:5173/room/abc123`)

    // Wait a beat for BroadcastChannel to signal
    await page2.waitForTimeout(500)

    // page1 should now show takeover modal
    const modalText = await page1.textContent('[data-qa="takeover-modal"]')
    expect(modalText).toContain('Opened in another tab')

    // page1 controls should be frozen (disabled)
    const foldBtn = await page1.$('[data-qa="action-fold"]')
    const isDisabled = await foldBtn.evaluate(el => el.disabled)
    expect(isDisabled).toBe(true)

    // Click "Use here instead" on page1
    await page1.click('[data-qa="takeover-use-here"]')

    // page2's socket should close, page1 becomes active
    await page1.waitForTimeout(500)
    const modal2Gone = await page2.$('[data-qa="takeover-modal"]')
    expect(modal2Gone).toBeNull() // no modal on new tab anymore

    // page1 controls should be re-enabled
    const foldBtnNow = await page1.$('[data-qa="action-fold"]')
    const isEnabledNow = await foldBtnNow.evaluate(el => el.disabled)
    expect(isEnabledNow).toBe(false)
  })
})
```

## E2E Test Scenarios (Playwright)

Each scenario lives in its own file under `e2e/scenarios/` and follows this structure:

```typescript
// e2e/scenarios/NN-description.spec.ts
import { test, expect } from '@playwright/test'
import { devLogin, asAlice, asBob, asSpectator } from '../fixtures/dev-login'

test.describe('Scenario NN — Description', () => {
  test('main flow', async ({ page }) => {
    // arrange, act, assert
  })
})
```

### Scenario 01 — Healthy hand

**File**: `e2e/scenarios/01-healthy-hand.spec.ts`

**Setup**:
- Alice dev-logs in, creates a 6-seat 5/10 room with 30s timer.
- Bob dev-logs in, joins via code.
- Both sit (alice: seat 0, bob: seat 1).

**Main flow**:
1. Verify both are seated with 1,000 chip stacks.
2. Verify button and blind order (2-player heads-up: alice=button, bob=BB).
3. Wait for action (alice acts first preflop).
4. Alice raises to 40; bob calls.
5. Flop dealt; bob checks, alice bets, bob calls.
6. Turn dealt; bob checks, alice checks.
7. River dealt; bob checks, alice checks.
8. Showdown: verify hands are revealed (hole cards visible in UI), winner named.
9. Verify chip total conserved: 2,000 total before and after.

**Key assertions**:
- Raise slider min/max match engine logic: `[minRaiseTo: 80, maxRaiseTo: 2000]` etc.
- Winner correctly named (e.g. "alice wins with pair of kings").
- Chip stacks reflect hand outcome (winner +X, loser -X).
- Board and hole cards shown after showdown.

---

### Scenario 02 — Action timer

**File**: `e2e/scenarios/02-action-timer.spec.ts`

**Setup**: Healthy hand in progress, it's alice's turn preflop.

**Main flow**:
1. Do not send any action for alice.
2. Wait for action timer to expire (30s + buffer).
3. Verify auto-fold fires (alice's seat shows folded state).
4. Verify alice is marked "sitting out" (grey badge on her seat).
5. Hand continues with bob winning by default.

**Key assertions**:
- Timer fires within expected time window.
- Fold is applied without user interaction.
- Sitting-out state persists until next hand or player sits back in.

---

### Scenario 03 — Refresh mid-hand

**File**: `e2e/scenarios/03-refresh-mid-hand.spec.ts`

**Setup**: Heads-up hand, alice on turn to act.

**Main flow**:
1. Hard refresh alice's page (`page.reload()`).
2. Verify alice's session cookie is still valid (reauth happens).
3. Verify connection state goes `connected` → `reconnecting` (brief) → `connected`.
4. Verify alice's hole cards are restored (visible in UI).
5. Verify alice's seat and position are unchanged.
6. Verify alice can act (controls enabled).

**Key assertions**:
- Reconnect banner does not persist after socket recovers.
- Snapshot after refresh matches pre-refresh state (same seat, same hole cards,
  same action info).

---

### Scenario 04 — Tab close + rejoin

**File**: `e2e/scenarios/04-tab-close-rejoin.spec.ts`

**Setup**: Heads-up hand, bob on turn to act.

**Main flow**:
1. Close bob's tab.
2. Verify action timer fires; bob auto-folds (server times out his action).
3. Hand continues with alice winning.
4. Reopen bob's browser and dev-login as bob again.
5. Navigate to the room (by code or from lobby).
6. Verify bob's seat is retained (rejoin-as-resync).
7. Verify bob's stack is correct (post-loss from hand).

**Key assertions**:
- Seat survives tab close (T5's disconnect-is-not-leave invariant).
- Rejoin returns bob to the same seat and same hand state.
- No double-acting or re-playing of the hand.

---

### Scenario 05 — Multi-tab takeover

**File**: `e2e/scenarios/05-multi-tab-takeover.spec.ts`

**Setup**: Alice is seated in a room, mid-hand, it's her turn.

**Main flow**:
1. Open alice's room in a second browser tab (same user session, BroadcastChannel works).
2. Verify tab1 (old) shows takeover modal: "Opened in another tab".
3. Verify tab1 is read-only: fold/call/raise buttons are disabled.
4. Verify tab2 (new) shows controls normally.
5. Click "Use here instead" on tab1.
6. Verify tab2's socket closes, controls are frozen.
7. Verify tab1's controls re-enable (become active).

**Key assertions**:
- Modal appears within 1s of second tab connecting.
- Takeover action atomically closes new tab's socket and re-enables old tab's.

---

### Scenario 06 — Spectator hole-card privacy

**File**: `e2e/scenarios/06-spectator-privacy.spec.ts`

**Setup**: 6-seat table. Alice and bob are seated and playing. Charlie joins as
spectator (not seated, because seats are full or he chooses not to sit).

**Main flow**:
1. Verify charlie can see the table and board.
2. Use Playwright's request interception to capture all STOMP MESSAGE frames
   sent to charlie's `/user/queue/room`.
3. Parse the `game.seats[0].holeCards` and `game.seats[1].holeCards` fields
   (alice and bob's hole cards).
4. Verify these fields are null, undefined, or empty (no actual card values).
5. Compare to the same frames received by alice: alice should see her own hole
   cards, but not bob's.

**Key assertions**:
- Spectator frame: `game.seats[0].holeCards === null` (alice's cards hidden).
- Spectator frame: `game.seats[1].holeCards === null` (bob's cards hidden).
- Alice frame: `game.seats[0].holeCards !== null` (her own cards visible).
- Alice frame: `game.seats[1].holeCards === null` (bob's cards still hidden).

---

### Scenario 07 — All-in runout

**File**: `e2e/scenarios/07-all-in-runout.spec.ts`

**Setup**: Unequal stacks (e.g. alice 1,000, bob 200). Get to all-in condition
preflop (e.g. bob pushes 200, alice calls).

**Main flow**:
1. Verify both players are marked all-in (red badges).
2. Verify auto-runout starts (no user action needed for remaining streets).
3. Verify runout completes with pacing (flop dealt, pause, turn, pause, river).
4. Verify final hand evaluation is correct (winner named, chips awarded).
5. Verify side-pot and uncalled-bet logic: if alice had 1,500 and bob pushed 200,
   verify the pot distribution is correct.

**Key assertions**:
- All-in runout does not require player action.
- Board delivered with visual pacing between streets.
- Chips awarded correctly (main pot, side pot, refunds).

---

### Scenario 08 — Bust + rebuy

**File**: `e2e/scenarios/08-bust-and-rebuy.spec.ts`

**Setup**: Alice busted in a hand (lost all chips). Rebuy is enabled (buy-in
range includes options).

**Main flow**:
1. After hand ends, verify alice sees rebuy modal: "You busted! Rebuy for: 400–2,000?"
2. Alice clicks rebuy for 800 chips.
3. Verify rebuy is queued (modal dismisses, alice is marked "sitting out").
4. Next hand deals; alice joins back in with 800 chips at her seat.

**Key assertions**:
- Rebuy modal appears immediately after bust.
- Rebuy amount is clamped to the buy-in range.
- Alice's stack is restored next hand.

---

### Scenario 09 — Kill-server recovery

**File**: `e2e/scenarios/09-kill-server-recovery.spec.ts`

**Setup**: Healthy hand in progress. Alice and bob are seated.

**Main flow**:
1. Mid-hand, kill the Spring Boot process (SIGTERM or equivalent).
2. Verify both clients show "Reconnecting…" banner within 2s.
3. Verify backoff schedule: first retry ~500ms later.
4. After server restarts (or dev script restarts it), clients attempt to reconnect.
5. Once connected, both clients attempt `state.sync`.
6. `state.sync` succeeds with `room=null` and a fresh membership epoch.
7. Each client sends `room.sync(cachedOldRoomId)` retained before restart.
8. Server returns session-local `ROOM_NOT_FOUND`; the socket remains usable.
9. Verify both clients show error toast and the blocking "This table is gone" modal.
10. Click "Back to lobby"; verify room state clears and the lobby is refetched.

**Key assertions**:
- Reconnect banner appears immediately on socket loss.
- Backoff retries are scheduled correctly (500ms initial).
- `state.sync` does not claim knowledge of the old room; correlated
  `room.sync(oldRoomId)` produces the non-fatal `ROOM_NOT_FOUND` ack and modal.
- Navigation to lobby clears room state.

---

### Scenario 10 — Room GC

**File**: `e2e/scenarios/10-room-gc.spec.ts`

**Setup**: 3-player game (alice, bob, charlie). Game ends, all players still seated.

**Main flow**:
1. Alice, bob, and charlie all leave the room (click stand/leave).
2. Verify room is removed from both players' lobby list (refresh to be sure).
3. Verify room cannot be rejoined by code (404 or similar error).
4. Verify private room is not visible in public lobby (it's empty and deleted).

**Key assertions**:
- Last leave triggers room deletion (T5's atomicity).
- Lobby revision increments; clients fetch updated lobby.
- Room is not discoverable after GC.

---

### Scenario 11 & 12 — Reserved

Placeholder for future edge cases:
- Concurrent action ordering (multiple actions queued while reconnecting).
- Seat swap mid-hand (if supported).
- Large table scaling (8+ players, verify seat layout and message throughput).

---

## E2E Test Infrastructure

### Fixtures and setup (`e2e/fixtures/dev-login.ts`)

```typescript
import { Page } from '@playwright/test'

export async function devLogin(page: Page, username: string) {
  await page.goto(`http://localhost:5173/auth/dev?u=${username}`)
  await page.waitForURL('**/lobby')
  await page.waitForLoadState('networkidle')
}

export async function createRoomAsAlice(page: Page) {
  // Click "Create room" button, fill form, submit
  // Returns roomCode
}

export async function joinRoomAsBob(bobPage: Page, roomCode: string) {
  // Enter code and join
}
```

### Playwright config (`e2e/playwright.config.ts`)

```typescript
import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './scenarios',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined, // single worker in CI for kill-server
  reporter: 'html',
  use: {
    baseURL: 'http://localhost:5173',
    screenshot: 'only-on-failure',
    trace: 'on-first-retry',
    video: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
    // Add Firefox, WebKit if desired
  ],
  webServer: {
    command: 'npm run dev',
    port: 5173,
    reuseExistingServer: !process.env.CI,
  },
})
```

### Running E2E locally

```bash
# Start dev server manually or let Playwright do it
npm run e2e

# Run a single scenario
npm run e2e -- 01-healthy-hand.spec.ts

# Run with UI (watch mode)
npm run e2e -- --ui
```

### Running E2E in CI

```bash
npm run e2e:ci
# Runs all scenarios, headless, collects traces/videos, exits with failure
# if any test fails.
```

---

## Failure modes and debugging

### Socket reconnect fails repeatedly

- Check dev server is running: `curl http://localhost:5173`.
- Check Spring Boot server: `curl http://localhost:8080/health`.
- Check CSRF endpoint: `curl -H "Cookie: SESSIONID=..." http://localhost:8080/api/csrf`.
- Inspect browser console for STOMP errors.
- Check backoff schedule is running (timers in DevTools).

### Multi-tab takeover modal doesn't appear

- Check BroadcastChannel is available in the browser (Chrome, Firefox, Safari all
  support it).
- Verify both tabs are on the same origin and same user session.
- Check browser console for BroadcastChannel errors.
- Add debug logging: `console.log('[takeover] broadcasting:', message)`.

### Hole-card privacy test assertion fails

- Inspect the STOMP frame payload in the test: log the `game.seats` array.
- Verify T6's redaction logic is correctly masking hole cards for non-seated users.
- Check RoomSnapshotMapper is using the correct viewer ID.

### Kill-server test hangs

- Ensure SIGTERM is sent to the right process (check `ps aux | grep java`).
- Add a timeout to the kill action: `setTimeout(killServer, 5000)`.
- Manually kill and restart the server to verify dev script works.

---

## Acceptance checklist

- [ ] All 10 core scenarios (01–10) pass consistently in local Playwright runs.
- [ ] Scenarios 11–12 are documented as reserved and pass placeholder tests.
- [ ] Unit tests for backoff, toast queue, tab arbiter, snapshot reconciliation
      all pass with >90% code coverage.
- [ ] Integration tests for overlay components render correctly and respond to
      user actions.
- [ ] Manual smoke test: open mockup.html, visually verify all overlays match
      the design.
- [ ] Multi-tab manual test: open same room in two tabs, observe takeover modal,
      click "Use here instead", verify old tab becomes active.
- [ ] Kill-server test: manually kill and restart Spring Boot, observe reconnect
      banner, then ROOM_NOT_FOUND modal, then redirect to lobby.
- [ ] Hole-card privacy test: verify spectator's STOMP frames do not contain seated
      players' hole cards.
- [ ] CI pipeline runs E2E suite on pull requests and main branch.
- [ ] Traces and videos are captured on failure; developers can download and
      debug.
- [ ] All 12 scenarios documented and spec files present (even if 11–12 are
      placeholder).
- [ ] Performance baseline: E2E suite completes in <10 min locally, <20 min in CI.

---

## Known limitations and future work

- **BroadcastChannel fallback**: Older browsers without BroadcastChannel support
  will not get multi-tab arbitration. T10 provides a no-op fallback (single-tab
  only). Document this as a known limitation and consider a polyfill if needed.
- **Server-side multi-tab policy**: T10 assumes the server does not kick old tabs.
  If T9 implements server-side takeover, T10 will need to subscribe to that event
  instead of relying purely on BroadcastChannel.
- **Offline detection**: T10 uses backoff timeout to detect offline state. There is
  no explicit network-level offline detection (e.g. listening to `navigator.onLine`
  or `offline` events). If desired, add that in a future pass.
- **CSRF token expiry**: T10 refreshes CSRF on reconnect, but if a token expires
  mid-session (e.g. session expires after 30 min), the next action will fail and
  require re-login. T5 owns that policy; T10 just enforces the reconnect flow.
- **Server restart timing**: Kill-server tests assume a graceful restart takes
  <5s. If your CI environment is slower, adjust timeouts in the test.

---

## Summary

T10's test strategy is:
1. **Unit**: backoff, toast, tab arbiter, snapshot reconciliation.
2. **Integration**: overlay component rendering and interaction.
3. **E2E**: 12 comprehensive Playwright scenarios covering healthy play and all
   recovery paths, with real STOMP traffic and server state.

The E2E suite is the acceptance gate for T10. All 12 scenarios must pass, with
hole-card privacy verified and kill-server recovery demonstrated, before shipping.
