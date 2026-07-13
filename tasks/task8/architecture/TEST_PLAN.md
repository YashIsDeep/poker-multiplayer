# T8 Test Plan — Balatro UI

## Unit Tests

### `useAnimationQueue.ts`

**Test: snapshot diff → animation event derivation**
- Setup: previous snapshot with 2 board cards, new snapshot with 4 board cards.
- Expected: returns array with 2 `card-entrance` events with correct indices and delays.
- Test variant: no previous snapshot (first render) → returns empty array (no animations).

**Test: pot change detection**
- Setup: previous pot = 100, new pot = 340.
- Expected: returns array with 1 `pot-pop` event.
- Test variant: pot unchanged → no events.

**Test: actor change detection**
- Setup: previous snapshot has no actor, new snapshot has actor at seat 3.
- Expected: returns `actor-pulse` event targeting seat 3.
- Test variant: actor unchanged → no events.

**Test: animation cleanup via useEffect return**
- Setup: render hook with snapshot change.
- Expected: cleanup timers are set for each animation; timers fire after animation duration.
- Unmount before timers fire → cleanup function cancels all timers.

**Test: multiple simultaneous events**
- Setup: board gets 1 new card, pot changes, actor changes in one snapshot.
- Expected: returns 3 events in correct order.

**Test: stagger calculation**
- Setup: 5 new board cards.
- Expected: delays are [0, 120, 240, 360, 480]ms.

### `useReducedMotion.ts`

**Test: returns false when prefers-reduced-motion is not set**
- Expected: hook returns `false`.

**Test: returns true when prefers-reduced-motion: reduce**
- Setup: mock `matchMedia('(prefers-reduced-motion: reduce)').matches = true`.
- Expected: hook returns `true`.

**Test: updates on media query change**
- Setup: hook initially returns `false`.
- Fire media query change event.
- Expected: hook re-evaluates and returns new value.

### `useWebGLCanvas.ts`

**Test: successful WebGL context creation**
- Setup: canvas ref provided, WebGL available.
- Expected: hook compiles shader and returns context reference; no errors logged.

**Test: WebGL unavailable**
- Setup: canvas ref provided, `getContext('webgl')` returns `null`.
- Expected: hook catches gracefully; returns `null`; component renders without error.

**Test: shader compilation error**
- Setup: provide invalid shader source.
- Expected: shader compilation fails; caught by `gl.compileShader()` check.
- Fallback: canvas remains blank; CSS gradient is visible underneath.

### Card animation helpers

**Test: `deriveCardAnimations(prevCards, nextCards)`**
- Setup: prev = ['As', 'Kd', '9c'], next = ['As', 'Kd', '9c', 'Qh', 'Jd'] (2 new cards).
- Expected: returns array with 2 card-entrance events with correct indices.

### Pot change trigger

**Test: `useAnimatePopOnChange(potAmount)` triggers animation**
- Setup: hook watches pot amount.
- Fire snapshot with new pot value.
- Expected: `.pop` class is applied to pot element; animation fires.
- After 350ms: `.pop` class is removed (animation cleanup).

## Manual / Visual Tests

### WebGL swirl background

**Test: renders on desktop (1280×800)**
- Load Room with active game.
- Expected: WebGL swirl animates smoothly at 60fps (measure via DevTools Performance tab).
- Visual check: Perlin noise pattern has visible fractal flow, colors match `#2b4b6f` and `#16283c`.

**Test: renders on tablet (768×1024)**
- Rotate device or resize to tablet viewport.
- Expected: canvas scales correctly; animation continues at 60fps.

**Test: renders on mobile (375×812)**
- Resize to mobile viewport.
- Expected: canvas scales; animation continues (may see slight frame drops due to lower DPR, acceptable).

**Test: DPR undersampling (0.66×)**
- Measure canvas `clientWidth`/`clientHeight` vs. actual `canvas.width`/`canvas.height`.
- Expected: pixel size ≈ 66% of logical size (soft Perlin effect does not require full resolution).

**Test: WebGL unavailable**
- Open DevTools → disable WebGL via browser settings or use Firefox with WebGL disabled.
- Load Room.
- Expected: canvas renders nothing; CSS background gradient (`radial-gradient(...)`) is visible behind content; game is playable.

**Test: reduced-motion**
- Enable `prefers-reduced-motion: reduce` in DevTools.
- Load Room.
- Expected: WebGL renders exactly one static frame (no RAF loop); appearance is correct.

### CRT overlay effects

**Test: scanlines visible**
- Take screenshot of Room.
- Expected: horizontal scanlines are faintly visible (opacity: 0.5) on the entire screen.

**Test: grain animation**
- Record Room screen for 2 seconds.
- Expected: grain pattern shifts every ~300ms (1.2s animation ÷ 4 steps = 300ms per step).
- Visual check: shifts are visible but subtle (opacity: .11 base, .05 in T8 mockup).

**Test: vignette effect**
- Take screenshot.
- Expected: corners are darkened via radial-gradient; center is unaffected.

**Test: overlays do not interfere with interactivity**
- Click action bar buttons, raise slider, pill buttons.
- Expected: all controls are responsive; `pointer-events: none` on overlays.

### Card animations

**Test: card bob on board and hole**
- Load active game.
- Expected: all card faces on board bob gently (3.4s cycle, ±3px vertical translate).
- Variant with reduced-motion: cards are static (no bob).

**Test: card entrance stagger on board reveal**
- Watch flop reveal (3 cards at once).
- Expected: cards enter with 120ms stagger (first at 0ms, second at 120ms, third at 240ms).
- Visual check: cards appear to "pop" in sequence, not simultaneously.

**Test: winner card outline**
- Reach showdown; see winning hand.
- Expected: winning hole cards and board cards have gold outline with `winpulse` animation (bob 9px at 50%).

**Test: dead cards (folded hand at showdown)**
- Fold before showdown; observe your cards.
- Expected: cards are desaturated and darkened (not animating).

### Pot number pop animation

**Test: pot changes, number pops**
- Watch pot value increase during betting round.
- Expected: `.num` element scales from 1 → 1.35 at 30% keyframe, duration 0.35s.
- Repeat: multiple pot changes each trigger independent pop animations.

**Test: pot pop animation cleanup**
- Inspect DOM: pot number should not have `.pop` class after animation finishes.
- Expected: class is removed; next pop animation can fire cleanly.

### Chip fly-in animation

**Test: chips appear when player bets**
- Player makes a bet; watch bet chip appear at inner ellipse position.
- Expected: chip and label appear with entrance animation (translate from center outward, opacity 0 → 1).
- Duration: ~300ms.

**Test: multiple chips stagger**
- Multiple players place bets in rapid succession.
- Expected: each chip animates independently.

### Actor pod pulse

**Test: active player has pulsing glow**
- Your turn: your pod has orange border + pulsing glow + conic timer ring above.
- Expected: pod glow pulses with 1.1s cycle (brightness increases at 50%).

**Test: actor changes**
- Action moves to next player.
- Expected: previous actor pod loses glow; new actor pod pulses.

### Timer ring animation

**Test: countdown visual decay**
- Your turn with 30s timer.
- Expected: conic-gradient fill depletes linearly over 30s (0% → 100% fill represents remaining time).
- Number inside updates each second.

**Test: "low" state at 6 seconds**
- Timer reaches ≤6 seconds.
- Expected: ring color changes from orange to red; pod begins `tremble` animation (.18s horizontal jitter).

**Test: timer ends**
- Timer reaches 0s.
- Expected: ring is fully red; action auto-submits or AI folds.

### Showdown banner animation

**Test: banner enters on showdown**
- Hand reaches showdown.
- Expected: banner scales from 0.4 → 1.0 with opacity 0 → 1, duration 0.45s, cubic-bezier bounce.
- Banner text: "ALICE WINS", amount "+ 2,180", hand name.

**Test: banner exits**
- Next hand starts.
- Expected: banner is removed from DOM or hidden.

### Action bar styling

**Test: button 3D shadow effect**
- Load action bar.
- Expected: Fold/Call/Raise buttons have colored background with 4px bottom shadow (`box-shadow: 0 4px 0 var(--red-deep)`).

**Test: button press-down animation**
- Click Fold button.
- Expected: button moves down 4px (translateY) and shadow compresses to 0px; feels tactile.
- Active state duration: 60ms.

**Test: raise slider fill gradient**
- Adjust raise slider.
- Expected: track fills with orange gradient from left to slider thumb position (`--fill` variable updates).
- Thumb is orange with 3px bottom shadow.

**Test: pill buttons (Min/½Pot/Pot/All-in)**
- Click each pill.
- Expected: selected pill gets orange border + text highlight; raise slider updates accordingly.

**Test: action bar disabled during reconnect**
- Simulate reconnect by watching store (mock a connection loss).
- Expected: all buttons have reduced opacity/saturation; slider is disabled; action bar appears "grayed out".

### Reduced-motion mode checklist

**Test: animations disabled**
- Enable `prefers-reduced-motion: reduce`.
- Load active game and play a hand.
- Expected:
  - No card bob animation; cards are static.
  - No pot pop; number changes instantly.
  - No chip fly-in; chips appear instantly.
  - No pod pulse or glow; actor pod is highlighted but not animating.
  - No timer ring animation; ring shows static percentage.
  - No scanline animation; scanlines are static.
  - No grain shift; grain is static.
  - Game is fully playable and visually complete.

**Test: transitions disabled**
- Expected: button press-down effect is instant (no 60ms ease).

### Responsive design

**Test: desktop (1280×800)**
- Expected: full layout, all elements clearly visible.

**Test: tablet landscape (768×1024)**
- Expected: table scales down; seats remain evenly spaced; action bar is readable.

**Test: tablet portrait (1024×768)**
- Expected: table may be slightly cramped; game remains playable.

**Test: mobile (375×812)**
- Expected: table is smaller; action bar takes up larger proportion of screen; all buttons are tappable (≥44px).

**Test: ultra-wide (1920×1080)**
- Expected: table scales up; seats and cards remain proportionate.

### Performance checklist

**Test: 60fps during active gameplay (DevTools Performance)**
- Load Room with 8-player game.
- Play one complete hand (preflop → showdown).
- Record performance.
- Expected: sustained 60fps (or 59fps with 1-frame dips is acceptable); no dropped frames during animations.

**Test: 60fps with all animations active**
- Multiple players acting, pot changing, chips flying, timer decaying, grain shifting.
- Expected: frame rate ≥55fps.

**Test: memory usage stable**
- Play 5 consecutive hands.
- Check DevTools Memory tab: heap size should not grow unbounded.
- Expected: heap stabilizes; animation cleanup prevents memory leaks.

**Test: no jank on slow device**
- Throttle CPU to "Slow 4G" in DevTools.
- Play one hand.
- Expected: animations drop to lower frame rate (30fps acceptable on throttle) but do not stutter or freeze.

## Browser matrix

| Browser | Version | Desktop | Tablet | Mobile | WebGL | Reduced-motion | Status |
|---------|---------|---------|--------|--------|-------|----------------|--------|
| Chrome  | 130+    | PASS    | PASS   | PASS   | PASS  | PASS           | Target |
| Firefox | 131+    | PASS    | PASS   | PASS   | PASS  | PASS           | Target |
| Safari  | 17+     | PASS    | PASS   | PASS   | PASS  | PASS           | Target |
| Edge    | 130+    | PASS    | PASS   | PASS   | PASS  | PASS           | Target |

**WebGL support note:** If a browser lacks WebGL 1.0 support (very rare in 2024), the app degrades to CSS-only rendering; game is fully playable.

**Reduced-motion support:** All modern browsers support `prefers-reduced-motion`. Fallback for very old browsers: animations play normally (not ideal, but not a blocker).

## Open questions for review

1. **Grain animation timing:** Is 1.2s / 4 steps (300ms per shift) the right cadence, or should it be faster/slower?
2. **Card entrance stagger:** Is 120ms per card the right feel? Could be 80ms or 150ms depending on preference.
3. **Chip fly-in trajectory:** Should chips fly from center outward in a straight line, or with a curve (arc)?
4. **Reconnect visual:** Does T8 own the "action bar disabled" visual feedback, or is that T10's reconnect modal? Clarify with task boundaries.
5. **Swirl animation speed:** Shader time scalar is 0.045; does the pattern flow feel right, or should it be faster (0.06) or slower (0.03)?

## Sign-off

Once all unit tests pass, all visual checklists are signed off, and performance targets are met across the browser matrix, T8 is ready for code review and merge.
