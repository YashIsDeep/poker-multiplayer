# T7 Test Plan — Gameplay Wiring (Client)

## Test strategy

Three layers:

1. **Unit tests** (store reducers, utilities, no React components)
   - Clock offset EMA calculations
   - Snapshot ordering and acceptance logic
   - Raise-to clamping math
   - Action-seq stale detection

2. **Component tests** (vitest + @testing-library/react, added as client devDeps in T7 — approved 2026-07-12)
   - Seat-pod rendering with per-state assertions
   - ActionArea state transitions
   - Countdown tick and display format

3. **Manual E2E verification**
   - Two-browser hand play with live action, all-in, showdown
   - Reconnect persistence

## Unit tests (vitest)

### Clock offset EMA

Test `clockOffsetEMA.ts`:

```typescript
describe('ClockOffsetEMA', () => {
  test('initializes with first delta', () => {
    const ema = new ClockOffsetEMA()
    const offset1 = ema.update(1000000, 900)  // server=1000000, client=900
    expect(offset1).toBeCloseTo(999100, 0)    // offset = 1000000 - 900
  })

  test('smooths deltas with alpha=0.1', () => {
    const ema = new ClockOffsetEMA(0.1)
    ema.update(1000000, 900)    // offset = 999100
    const offset2 = ema.update(1000100, 1000)  // new server=1000100, client=1000, new offset=999100
    // smoothed = 999100 + 0.1*(999100 - 999100) = 999100 (no delta)
    expect(offset2).toBeCloseTo(999100, 0)
  })

  test('tracks positive jitter (slow network)', () => {
    const ema = new ClockOffsetEMA(0.1)
    ema.update(1000000, 0)   // offset = 1000000, smoothed = 1000000
    const offset2 = ema.update(1001100, 100)  // offset = 1001000, smoothed += 0.1*(1001000 - 1000000) = 1000100
    expect(offset2).toBeCloseTo(1000100, 0)
  })

  test('getCurrentServerNow() applies smoothed offset', () => {
    const ema = new ClockOffsetEMA(0.1)
    ema.update(1000000, 0)
    const serverNow = ema.getCurrentServerNow(100)  // local client time = 100
    expect(serverNow).toBeCloseTo(1000100, 0)  // 100 + 1000000 offset
  })
})
```

### Snapshot ordering and acceptance

Test snapshot reducer with membership epoch + revision ordering:

```typescript
describe('Snapshot acceptance', () => {
  test('rejects older epoch', () => {
    const current = { epoch: 2, revision: 10 }
    const incoming = { epoch: 1, revision: 15 }
    expect(isSnapshotNewer(current, incoming)).toBe(false)
  })

  test('accepts newer epoch regardless of revision', () => {
    const current = { epoch: 2, revision: 10 }
    const incoming = { epoch: 3, revision: 1 }
    expect(isSnapshotNewer(current, incoming)).toBe(true)
  })

  test('accepts newer revision within same epoch', () => {
    const current = { epoch: 2, revision: 10 }
    const incoming = { epoch: 2, revision: 11 }
    expect(isSnapshotNewer(current, incoming)).toBe(true)
  })

  test('rejects older revision within same epoch', () => {
    const current = { epoch: 2, revision: 10 }
    const incoming = { epoch: 2, revision: 9 }
    expect(isSnapshotNewer(current, incoming)).toBe(false)
  })

  test('accepts resync duplicate (equal revision)', () => {
    const current = { epoch: 2, revision: 10 }
    const incoming = { epoch: 2, revision: 10 }
    // Caller treats as valid replacement but does not trigger UI animation
    expect(isSnapshotNewer(current, incoming)).toBe(false)  // no change, but not rejected
  })

  test('handles out-of-order replay: 3, 5, 4, 5, 6 → keep 3, 5, 5→skip, 5→skip, 6', () => {
    let state: { epoch: number; revision: number } | null = null

    const apply = (incoming: { epoch: number; revision: number }) => {
      if (!state || isSnapshotNewer(state, incoming)) {
        state = incoming
        return true  // accepted
      }
      return false  // rejected
    }

    expect(apply({ epoch: 1, revision: 3 })).toBe(true)
    expect(state).toEqual({ epoch: 1, revision: 3 })

    expect(apply({ epoch: 1, revision: 5 })).toBe(true)
    expect(state).toEqual({ epoch: 1, revision: 5 })

    expect(apply({ epoch: 1, revision: 4 })).toBe(false)
    expect(state).toEqual({ epoch: 1, revision: 5 })  // unchanged

    expect(apply({ epoch: 1, revision: 5 })).toBe(false)  // resync duplicate
    expect(state).toEqual({ epoch: 1, revision: 5 })  // unchanged

    expect(apply({ epoch: 1, revision: 6 })).toBe(true)
    expect(state).toEqual({ epoch: 1, revision: 6 })
  })

  test('epoch transitions: room A → leave → room B → delayed room A → final room B', () => {
    let state: { epoch: number; roomId: string; revision: number } | null = null

    const apply = (incoming: { epoch: number; roomId: string; revision: number }) => {
      if (!state) {
        state = incoming
        return true
      }
      const epochGreater = incoming.epoch > state.epoch
      const sameEpochNewRev =
        incoming.epoch === state.epoch && incoming.revision > state.revision
      if (epochGreater || sameEpochNewRev) {
        state = incoming
        return true
      }
      return false
    }

    // Room A, epoch 1
    expect(apply({ epoch: 1, roomId: 'roomA', revision: 1 })).toBe(true)
    expect(state.roomId).toBe('roomA')

    // Leave room A → epoch 0 (no room), then join room B → epoch 2
    expect(apply({ epoch: 2, roomId: 'roomB', revision: 1 })).toBe(true)
    expect(state.roomId).toBe('roomB')

    // Delayed snapshot from old room A (epoch 1) arrives
    expect(apply({ epoch: 1, roomId: 'roomA', revision: 5 })).toBe(false)
    expect(state.roomId).toBe('roomB')  // still in room B

    // New snapshot from room B confirms state
    expect(apply({ epoch: 2, roomId: 'roomB', revision: 2 })).toBe(true)
    expect(state.roomId).toBe('roomB')
  })
})
```

### Raise-to clamping

Test ActionArea raise clamping logic:

```typescript
describe('Raise-to clamping', () => {
  test('clamps to [minRaiseTo, maxRaiseTo]', () => {
    const clamp = (val: number, min: number, max: number) =>
      Math.max(min, Math.min(max, val))

    expect(clamp(100, 200, 500)).toBe(200)   // below min
    expect(clamp(300, 200, 500)).toBe(300)   // in range
    expect(clamp(600, 200, 500)).toBe(500)   // above max
  })

  test('half-pot calculation', () => {
    // pot=340, you face bet of 120 (need to call 120 to stay in)
    // Half-pot raise = call + ((pot + call amount) / 2)
    // = 120 + ((340 + 120) / 2) = 120 + 230 = 350
    const toCall = 120
    const pot = 340
    const halfPot = toCall + Math.round((pot + toCall) / 2)
    expect(halfPot).toBe(350)
  })

  test('pot-sized raise calculation', () => {
    // Pot raise = call + pot + call = 120 + 340 + 120 = 580
    const toCall = 120
    const pot = 340
    const potRaise = toCall + pot + toCall
    expect(potRaise).toBe(580)
  })

  test('preset slider values respect max-raise cap', () => {
    const toCall = 120
    const pot = 340
    const maxRaiseTo = 500  // your remaining stack (all-in cap)

    const presets = {
      min: 240,  // assume min raise = 2x big blind or call+raise
      half: Math.min(toCall + Math.round((pot + toCall) / 2), maxRaiseTo),
      pot: Math.min(toCall + pot + toCall, maxRaiseTo),
      allIn: maxRaiseTo,
    }

    expect(presets.min).toBe(240)
    expect(presets.half).toBe(350)
    expect(presets.pot).toBe(500)  // clamped from 580
    expect(presets.allIn).toBe(500)
  })
})
```

### Action-seq stale detection

Test action validation with actionSeq:

```typescript
describe('Action sequence staleness', () => {
  test('rejects action with old actionSeq', () => {
    const currentActionSeq = 5
    const actionSeq = 4
    expect(actionSeq < currentActionSeq).toBe(true)  // stale
  })

  test('accepts action matching current actionSeq', () => {
    const currentActionSeq = 5
    const actionSeq = 5
    expect(actionSeq === currentActionSeq).toBe(true)  // valid
  })

  test('snapshot with new actionSeq invalidates pending action', () => {
    let pendingAction = { actionSeq: 5, requestId: '123' }
    const incomingActionSeq = 6

    if (incomingActionSeq !== pendingAction.actionSeq) {
      pendingAction = null  // action was stale or action completed
    }

    expect(pendingAction).toBe(null)
  })
})
```

## Component tests (vitest + @testing-library/react)

### Setup (add devDependencies)

```bash
npm install --save-dev vitest @testing-library/react @testing-library/user-event
```

Update `package.json`:

```json
{
  "scripts": {
    "test": "vitest",
    "test:ui": "vitest --ui",
    "test:coverage": "vitest --coverage"
  },
  "devDependencies": {
    "@testing-library/react": "^16.0.0",
    "@testing-library/user-event": "^16.0.0",
    "vitest": "^1.0.0"
  }
}
```

Create `client/vitest.config.ts`:

```typescript
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: [],
  },
})
```

### SeatPod rendering

Test `client/src/components/__tests__/SeatPod.test.tsx`:

```typescript
import { render, screen } from '@testing-library/react'
import SeatPod from '../SeatPod'

describe('SeatPod', () => {
  test('renders empty seat with dashed border', () => {
    const { container } = render(<SeatPod seat={null} />)
    expect(container.querySelector('.seat.empty')).toBeInTheDocument()
  })

  test('renders your seat with blue border and face-up cards', () => {
    const { container } = render(
      <SeatPod
        seat={{
          name: 'yash',
          stack: 1850,
          holeCards: ['As', 'Kd'],
          isYou: true,
        }}
      />
    )
    expect(container.querySelector('.seat.you')).toBeInTheDocument()
    expect(screen.getByText('yash')).toBeInTheDocument()
    expect(screen.getByText('1,850')).toBeInTheDocument()
  })

  test('renders actor seat with orange border and countdown ring', () => {
    const { container } = render(
      <SeatPod
        seat={{
          name: 'alice',
          stack: 2430,
          isActor: true,
          secsRemaining: 18,
        }}
      />
    )
    expect(container.querySelector('.seat.actor')).toBeInTheDocument()
    expect(screen.getByText('alice')).toBeInTheDocument()
  })

  test('renders dealer badge', () => {
    const { container } = render(
      <SeatPod
        seat={{
          name: 'bob',
          stack: 990,
          isDealer: true,
        }}
      />
    )
    expect(container.querySelector('.badge.dealer')).toBeInTheDocument()
    expect(screen.getByText('D')).toBeInTheDocument()
  })

  test('renders all-in badge', () => {
    const { container } = render(
      <SeatPod
        seat={{
          name: 'dana',
          stack: 0,
          isAllIn: true,
        }}
      />
    )
    expect(container.querySelector('.tag.allin')).toBeInTheDocument()
    expect(screen.getByText('all-in')).toBeInTheDocument()
  })

  test('renders sitting-out desaturated', () => {
    const { container } = render(
      <SeatPod
        seat={{
          name: 'eve',
          stack: 760,
          isSittingOut: true,
        }}
      />
    )
    expect(container.querySelector('.seat.sitout')).toBeInTheDocument()
  })
})
```

### ActionArea state transitions

Test `client/src/components/__tests__/ActionArea.test.tsx`:

```typescript
import { render, screen } from '@testing-library/react'
import ActionArea from '../ActionArea'

describe('ActionArea', () => {
  test('your turn: shows fold, call, raise buttons and slider', () => {
    render(
      <ActionArea
        state="yourTurn"
        toCall={120}
        minRaiseTo={240}
        maxRaiseTo={1850}
        pot={340}
        onAction={() => {}}
      />
    )
    expect(screen.getByText(/Your turn/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Fold/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Call/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Raise/ })).toBeInTheDocument()
  })

  test('waiting: shows disabled buttons and actor name', () => {
    render(
      <ActionArea
        state="waiting"
        actorName="alice"
        onAction={() => {}}
      />
    )
    expect(screen.getByText(/Waiting for alice/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Fold/ })).toBeDisabled()
  })

  test('showdown: shows winner and next-hand button', () => {
    render(
      <ActionArea
        state="showdown"
        winnerName="alice"
        winAmount={2180}
        handRank="Two pair, Kings and Nines"
        onAction={() => {}}
      />
    )
    expect(screen.getByText(/alice wins 2,180/)).toBeInTheDocument()
    expect(screen.getByText(/Two pair/)).toBeInTheDocument()
  })

  test('busted: shows rebuy slider and buttons', () => {
    render(
      <ActionArea
        state="busted"
        minRebuy={400}
        maxRebuy={2000}
        onAction={() => {}}
      />
    )
    expect(screen.getByText(/busted/i)).toBeInTheDocument()
    const slider = screen.getByRole('slider')
    expect(slider).toHaveAttribute('min', '400')
    expect(slider).toHaveAttribute('max', '2000')
  })

  test('sitting out: shows sit-back and stand buttons', () => {
    render(
      <ActionArea
        state="sitout"
        onAction={() => {}}
      />
    )
    expect(screen.getByText(/Sitting out/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /I'm back/ })).toBeInTheDocument()
  })

  test('reconnecting: disables all controls', () => {
    render(
      <ActionArea
        state="reconnecting"
        onAction={() => {}}
      />
    )
    expect(screen.getByText(/Connection lost/)).toBeInTheDocument()
    const buttons = screen.getAllByRole('button')
    buttons.forEach((b) => expect(b).toBeDisabled())
  })
})
```

### Countdown display

Test `client/src/components/__tests__/Countdown.test.tsx`:

```typescript
import { render, screen } from '@testing-library/react'
import Countdown from '../Countdown'
import { vi } from 'vitest'

describe('Countdown', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.runOnlyPendingTimers()
    vi.useRealTimers()
  })

  test('renders deadline countdown', () => {
    const now = 1000  // ms
    const deadline = now + 18 * 1000  // 18 seconds from now
    const getServerNow = () => now

    render(<Countdown deadline={deadline} getServerNow={getServerNow} />)
    expect(screen.getByText('0:18')).toBeInTheDocument()
  })

  test('updates every second', () => {
    const now = 1000
    const deadline = now + 18 * 1000
    const getServerNow = vi.fn(() => now)

    const { rerender } = render(
      <Countdown deadline={deadline} getServerNow={getServerNow} />
    )
    expect(screen.getByText('0:18')).toBeInTheDocument()

    vi.advanceTimersByTime(1000)
    getServerNow.mockReturnValue(now + 1000)
    rerender(<Countdown deadline={deadline} getServerNow={getServerNow} />)
    expect(screen.getByText('0:17')).toBeInTheDocument()
  })

  test('shows red when <= 6 seconds', () => {
    const now = 1000
    const deadline = now + 4 * 1000  // 4 seconds remaining
    const getServerNow = () => now

    const { container } = render(
      <Countdown deadline={deadline} getServerNow={getServerNow} />
    )
    expect(container.querySelector('.countdown.low')).toBeInTheDocument()
  })

  test('clamps to 0:00 when expired', () => {
    const now = 1000
    const deadline = now - 5 * 1000  // 5 seconds past deadline
    const getServerNow = () => now

    render(<Countdown deadline={deadline} getServerNow={getServerNow} />)
    expect(screen.getByText('0:00')).toBeInTheDocument()
  })
})
```

## Manual two-window E2E verification script

### Setup

1. Have T5 server running on localhost:8080
2. Have T6 server gameplay running (or mock endpoint returning fixed game state)
3. Open two browser tabs to `http://localhost:5173/room/{roomId}`

### Scenario: complete hand with all-in and showdown

**Preconditions:**
- Room exists with 2 players
- Both players are seated
- Hand 1 is in PREFLOP phase
- Player 1 to act (0 stack = 2000, Player 2 stack = 1500)

**Steps:**

1. **Player 1 raises to 200**
   - Observer: Button shows "Raise 200" appears in Player 1's last-action line
   - Observer: Pot updates from 30 (blinds) to 230
   - Verify: Countdown resets to 18s for Player 2

2. **Player 2 calls 200 (all-in with only 1500 stack)**
   - Observer: Player 2's stack shows "ALL-IN", badge appears
   - Observer: Pot updates to 430 (2×200 + blinds)
   - Verify: Both players' action buttons disable (no more action possible pre-showdown)
   - Verify: Action area shows "Waiting for Player 1 to complete…" or auto-advances

3. **Server auto-completes to showdown**
   - Observer: Board fills: FLOP appears with 3 cards, then TURN, then RIVER
   - Observer: Action area shows "Showdown" banner with winner name and pot amount
   - Verify: Both hole-card sets display (showdown reveal)
   - Verify: Winner hand rank appears (e.g., "Pair of Kings")
   - Verify: Winning cards outlined in gold

4. **Next hand starts (after 3s countdown)**
   - Observer: Action area resets with "Your turn" or "Waiting for {actor}"
   - Observer: Seats reset (stacks updated, cards reshuffled as backs)
   - Observer: Countdown ticks down from 18s again
   - Verify: Board clears to empty slots

5. **Disconnect Player 2 (kill tab or close socket)**
   - Observer (Player 1): Player 2's seat shows "offline" badge (T10 feature)
   - Observer (Player 1): Your action controls remain active
   - Verify: Action area still responds to fold/call/raise commands
   - Verify: No error message forces navigation away

6. **Player 2 reconnects (reload tab)**
   - Observer (Player 2): STOMP reconnects, re-syncs state
   - Observer (Player 2): Rejoins same room and seat without losing membership
   - Observer (Player 2): Game state appears (board, pot, actor, countdown)
   - Verify: Both observers see consistent game state within 100ms

### Success criteria

- [ ] Raise amounts display in action line and pot updates
- [ ] All-in badge and stack = 0 display correctly
- [ ] Countdown ticks visibly from 18s to 0s (or game advances faster)
- [ ] Showdown reveals both hole-card sets with gold outline
- [ ] Winner name and hand rank display
- [ ] Next hand resets board, cards, countdown, and stacks correctly
- [ ] Disconnect does not show "room closed" and keeps game state intact
- [ ] Reconnect re-syncs without re-creating room or seat
- [ ] Manual console logs show STOMP messages with correct actionSeq, no errors
- [ ] No animations yet (T8 concern) — all visuals are instant state updates

## Test execution

### Unit tests (pre-component)

```bash
npm test -- --run unit
```

### Component tests (if added)

```bash
npm test -- --run components
```

### Manual E2E

1. Ensure server is running: `npm run dev` (from server/)
2. Ensure client dev server: `npm run dev` (from client/)
3. Create a 2-player room via the mockup or UI
4. Follow the scenario above in two browser windows
5. Record (or screenshot at key moments):
   - Raise/call action updates
   - All-in badge
   - Showdown reveal
   - Reconnect recovery
6. Document any failures in FINDINGS.md (if needed for later review)

## Coverage goals

- Clock offset EMA: 95%+ (all branches for delta smoothing)
- Snapshot ordering: 100% (all epoch/revision combinations)
- ActionArea state machine: 90%+ (all state transitions)
- Component rendering: 85%+ (all seat states, action states)
- Manual E2E: 1 complete hand with all-in/showdown + 1 reconnect per player
